# Design: Live Map

## Snapshot + stream: el orden importa

```
1. Obtener credenciales MQTT      GET /mqtt/credentials
2. Conectar y SUSCRIBIRSE         fleet/{orgId}/vehicle/+/telemetry
3. Empezar a acumular en buffer   (aún no se pinta)
4. Pedir snapshot                 GET /fleet/state
5. Aplicar snapshot
6. Aplicar el buffer acumulado, descartando lo más antiguo que el snapshot
7. A partir de aquí, aplicar en directo
```

Suscribirse **antes** de pedir el snapshot es lo que evita el hueco: si se hiciera al revés, las actualizaciones ocurridas entre la respuesta del snapshot y la suscripción se perderían silenciosamente, y un vehículo podría quedarse congelado en el mapa hasta su siguiente emisión.

La guarda al aplicar el buffer es la misma que en `ingest`: comparar `recorded_at` y descartar lo más antiguo.

## Estado: SignalStore para el stream, `httpResource()` para HTTP

```typescript
// Stream MQTT → SignalStore. No es un resource: no hay petición que reintentar.
export const FleetStore = signalStore(
  { providedIn: 'root' },
  withState<{ vehicles: ReadonlyMap<string, VehicleState> }>({ vehicles: new Map() }),
  withComputed(({ vehicles }) => ({
    visibleVehicles: computed(() => applyFilters(vehicles())),
  })),
  withMethods((store) => ({
    applyTelemetry(msg: TelemetryMessage) { /* guarda de monotonía + patchState */ },
    applySnapshot(snapshot: FleetSnapshot) { /* reemplaza el mapa completo */ },
  })),
);

// Histórico del vehículo seleccionado → esto SÍ es una petición:
// depende reactivamente de la selección y del rango, y se recarga sola.
readonly track = httpResource<Position[]>(() => {
  const id = this.selectedVehicleId();
  return id ? { url: `/api/vehicles/${id}/track`, params: { from: this.from(), to: this.to() } } : undefined;
});
```

La distinción no es cosmética. `httpResource` está pensado para datos derivados de una petición con dependencias reactivas: cambia el vehículo seleccionado, se recarga la traza. Un stream MQTT no encaja ahí, porque no hay nada que "recargar": los datos llegan empujados. Usar la herramienta adecuada en cada caso, y poder explicar por qué, es más valioso que forzar todo a una sola API.

## Interpolación de marcadores

Con telemetría cada 5 segundos, mover el marcador de golpe produce saltos. Se interpola la posición entre la anterior y la nueva durante la ventana esperada, con `requestAnimationFrame`.

**Regla:** la interpolación es puramente visual. El estado del vehículo (posición conocida, velocidad) nunca se sustituye por el valor interpolado — si el usuario abre el detalle, ve el dato real, no el estimado. Confundir ambos llevaría a mostrar posiciones que nunca fueron reportadas.

Si un vehículo lleva más de una ventana sin emitir, la interpolación se detiene y el marcador se atenúa: no se extrapola hacia el futuro. Extrapolar sería inventar posiciones.

## Rendimiento con flota grande

- Marcadores con `symbol` layer de MapLibre, no elementos DOM: 500 marcadores DOM matan el navegador, una capa de símbolos no.
- Actualización por diferencia: solo se actualiza la fuente GeoJSON de los vehículos que cambiaron.
- `OnPush` en todos los componentes y selectores granulares por vehículo, de modo que mover un marcador no repinta el panel lateral.
- La traza histórica llega ya simplificada desde el backend (`Geo.simplifyTrack` en `geo-core`) antes de dibujarla: una traza de 8 horas son decenas de miles de puntos, y a escala de pantalla la mayoría son indistinguibles.

## Reconexión

El cliente MQTT reconecta con backoff. Al reconectar se repite el ciclo snapshot + stream completo, porque durante la desconexión se perdieron mensajes y el estado local puede estar obsoleto. Los mensajes retenidos de estado (`status`) llegan solos al resuscribirse, gracias al flag `retain` descrito en el change 03.
