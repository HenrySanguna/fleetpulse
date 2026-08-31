# Tasks: Add Live Map

## 1. Cliente MQTT en el navegador
- [ ] 1.1 Servicio de conexión MQTT (MQTT.js) sobre WebSocket con credenciales de `GET /api/mqtt/credentials`
- [ ] 1.2 Renovación de credenciales antes de expirar
- [ ] 1.3 Reconexión con backoff exponencial
- [ ] 1.4 Suscripción a `fleet/{orgId}/vehicle/+/telemetry` y `.../status`

## 2. Arranque snapshot + stream
- [ ] 2.1 `GET /api/fleet/state` en `api`: estado actual de todos los vehículos de la organización
- [ ] 2.2 Secuencia de arranque: suscribir → bufferizar → snapshot → aplicar buffer → directo
- [ ] 2.3 Guarda de monotonía al aplicar el buffer (descartar lo anterior al snapshot)
- [ ] 2.4 Repetir el ciclo completo al reconectar

## 3. Estado en el cliente
- [ ] 3.1 SignalStore con `Map<vehicleId, VehicleState>` alimentado por MQTT
- [ ] 3.2 Selector computado de vehículos visibles según filtros (estado, online, búsqueda)
- [ ] 3.3 `httpResource` para la traza histórica, consumiendo `libs/api-client`

## 4. Mapa
- [ ] 4.1 Integrar MapLibre GL con teselas de proveedor gratuito
- [ ] 4.2 Capa de símbolos para los vehículos (no marcadores DOM)
- [ ] 4.3 Icono orientado por rumbo; color por estado de movimiento; atenuado si está offline
- [ ] 4.4 Interpolación visual entre posiciones con `requestAnimationFrame`
- [ ] 4.5 Detener la interpolación (sin extrapolar) si no llega posición en la ventana esperada
- [ ] 4.6 Dibujar la traza histórica que el backend ya devuelve simplificada

## 5. Consola
- [ ] 5.1 Panel lateral con lista de vehículos, filtros y búsqueda (PrimeNG), envuelto en `libs/console-ui`
- [ ] 5.2 Selección sincronizada entre lista y mapa
- [ ] 5.3 Detalle de vehículo con datos reales (nunca interpolados)
- [ ] 5.4 Indicador de estado de conexión MQTT en la interfaz

## 6. Tests
- [ ] 6.1 Unitario: aplicar un mensaje más antiguo que el snapshot no modifica el estado
- [ ] 6.2 Unitario: los mensajes bufferizados durante la carga se aplican tras el snapshot
- [ ] 6.3 Unitario: el detalle del vehículo muestra la posición reportada, no la interpolada
- [ ] 6.4 Unitario: sin mensajes durante N ventanas, la interpolación se detiene
- [ ] 6.5 E2E (Playwright): un mensaje MQTT publicado por el test mueve el marcador en pantalla
- [ ] 6.6 E2E: al perder la conexión, la interfaz muestra el aviso; al recuperarla, el estado se resincroniza

## Definición de terminado
- [ ] Con el simulador de 50 vehículos corriendo, el mapa se mantiene fluido y la memoria del navegador no crece de forma monótona durante 10 minutos
- [ ] Detener el proceso `api` con el mapa abierto NO congela las actualizaciones en vivo (siguen llegando por MQTT)
