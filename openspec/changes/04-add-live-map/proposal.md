# Proposal: Add Live Map

## Intent
La consola del despachador tiene que mostrar la flota moviéndose en un mapa con latencia de segundos. La decisión estructural: **el navegador se suscribe directamente al broker MQTT sobre WebSocket**, en lugar de abrir un canal contra la API.

Es la arquitectura real de los dashboards de flota, y tiene una consecuencia técnica que la hace interesante: el backend deja de estar en el camino de los datos en vivo. Solo emite credenciales (change 02) y sirve el histórico. Si `api` se cae, el mapa sigue actualizándose.

## Scope

**In scope**
- Cliente MQTT sobre WebSocket en `apps/console`, con reconexión y renovación de credenciales.
- Mapa con MapLibre GL y teselas de proveedor gratuito.
- Marcadores de vehículo con rumbo, estado de movimiento y estado de conexión.
- Interpolación de movimiento entre posiciones para que los marcadores no salten.
- Estado del flujo en vivo con NgRx SignalStore; datos por petición con `httpResource()` nativo.
- Carga inicial: estado actual de la flota por HTTP (cliente generado desde OpenAPI); a partir de ahí, todo por MQTT.
- Panel lateral con lista de vehículos, filtros y selección.
- Traza histórica del vehículo seleccionado, simplificada por el backend antes de enviarla.

**Out of scope**
- Geocercas dibujadas sobre el mapa (change 05).
- ETAs y rutas (change 06).
- Aplicación móvil para conductores.

## Approach
Angular 21 zoneless. El flujo MQTT vive en un SignalStore de NgRx; los datos que se piden (histórico, detalle) usan `httpResource()` nativo. La separación es deliberada: `httpResource` está pensado para peticiones con dependencias reactivas, y un stream empujado por el broker no es una petición que se pueda reintentar ni recargar.

El patrón de arranque es "snapshot + stream": una petición HTTP trae el estado actual de toda la flota, y el cliente MQTT se suscribe antes de esa petición para no perder actualizaciones durante la carga. Los mensajes que llegan mientras la petición está en vuelo se aplican después del snapshot, descartando los que sean más antiguos.
