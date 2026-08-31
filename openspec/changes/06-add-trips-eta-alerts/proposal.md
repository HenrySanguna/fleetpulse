# Proposal: Add Trips, ETA and Alerts

## Intent
Cierra el MVP con la capa que convierte posiciones sueltas en información operativa: agrupar telemetría en viajes, estimar horas de llegada, y consolidar el panel de alertas. También incorpora los rollups históricos que evitan que los gráficos consulten la tabla particionada completa.

## Scope

**In scope**
- Segmentación automática de telemetría en viajes (arranque, parada prolongada, fin).
- Métricas por viaje: distancia, duración, tiempo en ralentí, velocidad máxima y media.
- ETA hacia un destino asignado, recalculado con cada posición.
- Panel de alertas en la consola: geocercas, exceso de velocidad, ralentí excesivo, dispositivo offline.
- Rollups horarios y diarios por vehículo, materializados por tarea programada.
- Informe de actividad por vehículo y rango de fechas.

**Out of scope**
- Enrutamiento con red de carreteras real y tráfico en vivo. El ETA del MVP es una estimación por distancia y velocidad histórica, no una ruta calculada.
- Optimización de rutas multiparada.
- Notificaciones por correo, SMS o push.
- Facturación o control de costes de combustible.

## Approach
La segmentación de viajes corre como tarea `@Scheduled` en `processor`, sobre las posiciones ya persistidas y no en el camino de ingesta: no necesita latencia de segundos, y mantener la ruta de escritura centrada en escribir rápido es más importante. El ETA sí se recalcula en el flujo en vivo, porque es información que el despachador consume en tiempo real.

Los rollups sustituyen a las continuous aggregates de TimescaleDB (descartado por licencia): tablas de agregado materializadas por una tarea `@Scheduled`, que es la ruta estándar al abandonar hypertables.
