# Tasks: Add Telemetry Ingest

## 1. Esquema y particionado
- [ ] 1.1 Migración Flyway: tabla `positions` particionada por rango sobre `recorded_at`, PK `(vehicle_id, recorded_at)`
- [ ] 1.2 Índice BRIN sobre `recorded_at`, GiST sobre `location`
- [ ] 1.3 `pg_partman` configurado con particiones semanales creadas por adelantado y retención configurable
- [ ] 1.4 Tabla `vehicle_state` (última posición, estado de movimiento, online, `recorded_at`)
- [ ] 1.5 Tarea `@Scheduled` que invoca `partman.run_maintenance_proc()` explícitamente

## 2. Consumo MQTT
- [ ] 2.1 Adaptador entrante de Spring Integration suscrito a `fleet/+/vehicle/+/telemetry`
- [ ] 2.2 QoS por adaptador: telemetría 0, comandos 1, alertas 2
- [ ] 2.3 Validación del payload; los mensajes malformados se descartan y se contabilizan, no tumban el consumidor
- [ ] 2.4 Descarte de posiciones implausibles usando `Geo.isImplausible` de `geo-core`

## 3. Escritura por lotes
- [ ] 3.1 Buffer en memoria con descarga por tamaño (N filas) o por tiempo (T ms), lo que ocurra antes
- [ ] 3.2 `JdbcTemplate.batchUpdate` con `ON CONFLICT (vehicle_id, recorded_at) DO NOTHING` — **no usar JPA para esto**
- [ ] 3.3 Descarga del buffer en el apagado ordenado del contexto de Spring

## 4. Estado actual tolerante al desorden
- [ ] 4.1 `UPDATE vehicle_state ... WHERE recorded_at < ?` (guarda de monotonía)
- [ ] 4.2 Aplicar `MotionDetector` solo cuando el mensaje es más reciente que el último procesado

## 5. Presencia con LWT
- [ ] 5.1 Documentar el contrato de testamento que deben registrar los dispositivos al conectar
- [ ] 5.2 Consumidor de `fleet/+/vehicle/+/status` que actualiza el flag online
- [ ] 5.3 Simulador de dispositivo (utilidad de desarrollo) que emite telemetría y registra su testamento

## 6. Tests (Testcontainers con PostGIS y Mosquitto reales)
- [ ] 6.1 Idempotencia: el mismo mensaje procesado dos veces produce una sola fila
- [ ] 6.2 Desorden: un mensaje antiguo tras uno reciente NO retrocede `vehicle_state`
- [ ] 6.3 Desorden: el mensaje antiguo SÍ se persiste en `positions`
- [ ] 6.4 Ráfaga de reenvío: 1.000 posiciones acumuladas se insertan sin duplicados
- [ ] 6.5 Posición implausible descartada, no persistida
- [ ] 6.6 Desconexión abrupta del dispositivo → el broker publica el testamento → el vehículo queda offline
- [ ] 6.7 Reconexión → el vehículo vuelve a online
- [ ] 6.8 El plan de ejecución de la consulta de histórico por vehículo y rango NO contiene recorrido secuencial sobre `positions`
- [ ] 6.9 Payload malformado no derriba el consumidor
- [ ] 6.10 El apagado ordenado descarga el buffer pendiente

## Definición de terminado
- [ ] El simulador emite desde 50 vehículos durante 10 minutos sin crecimiento monótono de memoria del proceso
- [ ] Cortar el simulador de golpe marca esos vehículos como offline sin intervención
- [ ] Las particiones de la semana siguiente existen antes de que empiece esa semana
