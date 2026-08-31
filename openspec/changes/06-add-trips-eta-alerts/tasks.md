# Tasks: Add Trips, ETA and Alerts

## 1. Viajes
- [ ] 1.1 Migración Flyway: tabla `trips` con clave única `(vehicle_id, started_at)`
- [ ] 1.2 Tarea `@Scheduled` que segmenta ventanas con retraso deliberado
- [ ] 1.3 Umbral de parada configurable por organización
- [ ] 1.4 Métricas por viaje: distancia (suma de tramos con `geo-core`), duración, ralentí, velocidad máxima y media
- [ ] 1.5 Idempotencia: reprocesar una ventana no duplica ni altera viajes ya cerrados

## 2. ETA
- [ ] 2.1 Asignación de destino a un vehículo
- [ ] 2.2 Cálculo de ETA por distancia con factor de sinuosidad y velocidad media reciente
- [ ] 2.3 Interfaz Java que aísla el cálculo para poder sustituirlo por un motor de rutas real
- [ ] 2.4 Recálculo en el flujo en vivo y publicación al tópico de la organización
- [ ] 2.5 Presentación en la interfaz **como estimación con margen**, nunca como hora exacta

## 3. Alertas
- [ ] 3.1 Migración Flyway: tabla `alerts` común a todos los tipos
- [ ] 3.2 Alertas de exceso de velocidad y de ralentí excesivo
- [ ] 3.3 Ventana de silencio por (vehículo, tipo, contexto) para evitar repetición
- [ ] 3.4 Panel de alertas en la consola con filtros y marcado como atendida

## 4. Rollups e informes
- [ ] 4.1 Tabla `vehicle_hourly` y tarea `@Scheduled` de recálculo idempotente con ventana amplia
- [ ] 4.2 Tabla `vehicle_daily` derivada de la horaria
- [ ] 4.3 Informe de actividad por vehículo y rango, servido desde los rollups
- [ ] 4.4 Gráficos en la consola alimentados por rollups, nunca por la tabla `positions`

## 5. Tests
- [ ] 5.1 Una traza con dos paradas largas produce exactamente tres viajes
- [ ] 5.2 Una parada breve (semáforo) NO parte el viaje
- [ ] 5.3 Reprocesar la misma ventana de segmentación no duplica viajes
- [ ] 5.4 Recalcular un rollup horario dos veces produce el mismo resultado
- [ ] 5.5 Telemetría desfasada que llega tarde actualiza el rollup de su hora correspondiente
- [ ] 5.6 Exceso de velocidad sostenido genera una alerta, no una por posición
- [ ] 5.7 Los gráficos de la consola no consultan `positions` (verificar en el plan de la consulta servida)

## Definición de terminado
- [ ] Un informe de actividad de una semana se sirve en tiempo aceptable sin tocar la tabla particionada de posiciones
- [ ] La interfaz nunca presenta el ETA como una hora exacta sin margen
