# Design: Trips, ETA and Alerts

## Segmentación de viajes

Un viaje empieza cuando el vehículo pasa a `moving` y termina cuando permanece en `stopped` más de un umbral (por defecto 5 minutos). El umbral importa: demasiado bajo y cada semáforo parte el viaje en dos; demasiado alto y dos entregas consecutivas se fusionan en una.

Correr con retraso deliberado (procesar posiciones de hace más de 10 minutos) resuelve un problema real: una parada aún no ha "terminado" hasta que pasa el umbral, así que procesar en vivo obligaría a mantener viajes abiertos y cerrarlos a posteriori. Con retraso, cada ejecución ve la ventana completa y decide una sola vez.

**Idempotencia**: la segmentación se ejecuta sobre ventanas temporales y usa una clave única `(vehicle_id, started_at)`. Reprocesar una ventana no duplica viajes.

## ETA: honestidad sobre lo que es

El ETA del MVP es **distancia en línea recta ajustada por un factor de sinuosidad, dividida por la velocidad media reciente del vehículo**. No consulta red de carreteras ni tráfico.

Esto es una limitación real y debe reflejarse en la interfaz: el ETA se presenta como estimación aproximada, con un margen, no como una hora exacta. Mostrar "llegada 14:32" cuando el cálculo ignora las carreteras sería engañar al despachador.

La alternativa (integrar un motor de rutas) se descarta en el MVP porque los servicios gratuitos de enrutamiento tienen límites de peticiones incompatibles con recalcular por cada posición de cada vehículo. Está documentado como la primera mejora natural post-MVP, y el cálculo está aislado tras una interfaz para poder sustituirlo sin tocar el resto.

## Rollups en lugar de continuous aggregates

```sql
CREATE TABLE vehicle_hourly (
  vehicle_id   uuid NOT NULL,
  hour         timestamptz NOT NULL,
  distance_km  real NOT NULL,
  moving_secs  integer NOT NULL,
  idle_secs    integer NOT NULL,
  max_speed    real,
  PRIMARY KEY (vehicle_id, hour)
);
```

Una tarea `@Scheduled` recalcula las horas cerradas recientes con `INSERT ... ON CONFLICT DO UPDATE`. Recalcular en lugar de acumular incrementalmente hace la tarea idempotente: ejecutarla dos veces sobre la misma hora produce el mismo resultado, lo que importa porque los reenvíos de telemetría desfasada pueden cambiar el agregado de una hora ya calculada.

La ventana de recálculo cubre las últimas horas, no solo la anterior, precisamente para absorber esos reenvíos.

## Panel de alertas

Todas las alertas (geocerca, velocidad, ralentí, offline) comparten tabla y llegan a la consola por el mismo tópico MQTT con QoS 2. La consola las muestra en un panel con filtros y permite marcarlas como atendidas.

**Deduplicación de alertas repetitivas**: un vehículo excediendo velocidad durante 10 minutos no debe generar una alerta por cada posición. Se aplica una ventana de silencio por combinación de vehículo, tipo de alerta y contexto: tras emitir una, no se emite otra igual hasta que pasa la ventana o la condición se resuelve y vuelve a darse.
