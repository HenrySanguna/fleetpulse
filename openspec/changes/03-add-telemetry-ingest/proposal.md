# Proposal: Add Telemetry Ingest

## Intent
Este es el núcleo técnico de FleetPulse: recibir un caudal continuo de posiciones GPS por MQTT y persistirlas de forma que las consultas sigan siendo rápidas cuando la tabla tenga decenas de millones de filas. Tres problemas que ningún proyecto anterior tuvo:

1. **Volumen sostenido de escritura.** Cien vehículos emitiendo cada 5 segundos son 1.7 millones de filas al día. Un `INSERT` por mensaje satura la base de datos mucho antes de eso.
2. **Datos duplicados y desordenados por diseño.** Los dispositivos almacenan telemetría cuando pierden cobertura y la reenvían al recuperarla. Llegan mensajes con timestamps de hace horas, mezclados con los actuales, y algunos por duplicado si el reenvío se solapa con una reconexión.
3. **Detección de dispositivos caídos.** Un vehículo que deja de emitir puede estar apagado, sin cobertura o con el dispositivo averiado — y hay que distinguirlo de uno parado que sigue reportando.

## Scope

**In scope**
- `processor` consumiendo MQTT con Spring Integration, con QoS por tipo de tópico.
- Tabla de posiciones con **particionado declarativo nativo por tiempo** y `pg_partman` para creación y retirada automática de particiones.
- Índices BRIN sobre la columna temporal y GiST sobre la geometría.
- Escritura por lotes con descarga por tamaño o por tiempo, lo que ocurra antes.
- Deduplicación apoyada en restricción única en base de datos.
- Aceptación de posiciones desordenadas sin corromper el estado derivado del vehículo.
- Descarte de posiciones implausibles usando `geo-core`.
- Detección de dispositivo offline mediante **Last Will and Testament** de MQTT.
- Estado actual de cada vehículo en una tabla aparte, de lectura rápida.

**Out of scope**
- Geocercas (change 05) — aquí solo se persiste y se calcula estado de movimiento.
- Visualización (change 04).
- Rollups históricos para gráficos (change 06).

## Approach
`processor` es un proceso separado precisamente porque su perfil de escalado no tiene nada que ver con el de `api`: es intensivo en escritura, sin usuarios esperando respuesta, y debe poder reiniciarse sin afectar a la consola. Acumula posiciones en memoria y las descarga en bloque; ante caída, se pierde como máximo una ventana de lote, que es una pérdida aceptable para telemetría de alta frecuencia y está documentada como tal.

El particionado nativo sustituye a las hypertables de TimescaleDB (descartado por licencia, ver `project.md`): `pg_partman` crea las particiones futuras y retira las antiguas, y los índices BRIN dan sobre datos append-only ordenados por tiempo un rendimiento comparable al de un B-tree con una fracción del tamaño. El mantenimiento se invoca desde una tarea `@Scheduled`, no desde el background worker de `pg_partman`, porque Neon suspende el compute por inactividad y el worker no correría.
