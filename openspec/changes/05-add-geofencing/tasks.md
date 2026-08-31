# Tasks: Add Geofencing

## 1. Modelo
- [ ] 1.1 Migración Flyway: tabla `geofences` con `geography(Polygon, 4326)` e índice GiST
- [ ] 1.2 Tabla `vehicle_fence_state` con `is_inside`, `since` y `pending_since`
- [ ] 1.3 Geocercas circulares almacenadas como polígono (buffer del centro)

## 2. Evaluación
- [ ] 2.1 Consulta nativa única que devuelve todas las geocercas activas que contienen el punto
- [ ] 2.2 Comparación de conjuntos (dentro ahora vs. dentro antes) para derivar entradas y salidas
- [ ] 2.3 Uso de `geo-core` para la decisión pura de transición por geocerca
- [ ] 2.4 Evaluar solo si el mensaje es más reciente que el último evaluado para ese vehículo

## 3. Amortiguación de oscilación
- [ ] 3.1 Confirmación temporal: marcar `pending_since` y confirmar tras N lecturas o T segundos
- [ ] 3.2 Descartar la pendiente si la pertenencia vuelve al estado anterior antes de confirmar
- [ ] 3.3 Margen espacial asimétrico: salida evaluada contra el polígono con buffer

## 4. Reglas y alertas
- [ ] 4.1 Reglas `on_enter`, `on_exit`, `on_dwell` (permanencia superior a `dwell_secs`)
- [ ] 4.2 Publicación de alertas en `fleet/{orgId}/alerts` con QoS 2
- [ ] 4.3 Persistencia de alertas para el histórico y el panel de la consola

## 5. Editor en la consola
- [ ] 5.1 Dibujo de polígonos y círculos sobre MapLibre
- [ ] 5.2 CRUD de geocercas con validación de geometría (polígono cerrado, sin auto-intersección)
- [ ] 5.3 Visualización de geocercas activas en el mapa en vivo

## 6. Tests (Testcontainers con PostGIS real)
- [ ] 6.1 Entrada limpia en geocerca genera exactamente una alerta
- [ ] 6.2 Salida limpia genera exactamente una alerta
- [ ] 6.3 **Oscilación en el borde: una traza con deriva sobre el límite NO genera ninguna alerta**
- [ ] 6.4 Una entrada real tras una oscilación SÍ genera alerta
- [ ] 6.5 Vehículo dentro de dos geocercas solapadas genera una alerta por cada una
- [ ] 6.6 Telemetría antigua reenviada NO genera alertas retroactivas
- [ ] 6.7 `on_dwell` dispara al superar el tiempo de permanencia, una sola vez
- [ ] 6.8 Reinicio de `processor` no pierde el estado de pertenencia (está persistido)
- [ ] 6.9 El plan de ejecución de la consulta de pertenencia usa el índice GiST, sin recorrido secuencial

## Definición de terminado
- [ ] El test 6.3 usa una traza con ruido realista sobre el borde, no dos puntos alternos artificiales
- [ ] Un vehículo simulado que cruza una geocerca genera exactamente dos alertas (entrada y salida), ni una más
