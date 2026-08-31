# Design: Telemetry Ingest

## Particionado sin TimescaleDB

```sql
CREATE TABLE positions (
  vehicle_id   uuid        NOT NULL,
  recorded_at  timestamptz NOT NULL,
  received_at  timestamptz NOT NULL DEFAULT now(),
  location     geography(Point, 4326) NOT NULL,
  speed_kmh    real,
  heading      real,
  ignition     boolean,
  PRIMARY KEY (vehicle_id, recorded_at)
) PARTITION BY RANGE (recorded_at);

CREATE INDEX ON positions USING BRIN (recorded_at);
CREATE INDEX ON positions USING GIST (location);
```

- **`PRIMARY KEY (vehicle_id, recorded_at)`** es a la vez la clave de deduplicación: un reenvío del mismo punto choca contra la clave y se descarta con `ON CONFLICT DO NOTHING`. La deduplicación es una garantía de la base de datos, no una comprobación en memoria que se pierde al reiniciar el proceso.
- **BRIN sobre `recorded_at`**: en una tabla append-only cuyo orden físico correlaciona con el tiempo, un índice BRIN ocupa órdenes de magnitud menos que un B-tree y sirve igual para consultas por rango.
- **`pg_partman`** crea particiones semanales por adelantado y retira las que superan la retención.

**`recorded_at` frente a `received_at`** son distintos a propósito: el primero es cuándo ocurrió según el dispositivo, el segundo cuándo llegó al servidor. En un reenvío tras días sin cobertura difieren en horas. El particionado usa `recorded_at` porque es el eje por el que se consulta.

**Mantenimiento de particiones desde Spring, no desde el BGW:**

```java
@Scheduled(cron = "0 0 3 * * *")
public void maintainPartitions() {
    jdbcTemplate.execute("CALL partman.run_maintenance_proc()");
}
```

Neon suspende el compute por inactividad y los background workers no se ejecutan durante la suspensión. Invocarlo explícitamente desde `processor` elimina esa dependencia.

## Escritura por lotes

JPA no es la herramienta para escritura masiva: el contexto de persistencia acumula entidades y el rendimiento se degrada. Para telemetría se usa `JdbcTemplate.batchUpdate` directamente.

```
mensaje MQTT → validar → filtrar implausibles → acumular en buffer
                                                      │
                    ┌─────────────────────────────────┤
              buffer ≥ N filas                  han pasado T ms
                    └─────────────────┬───────────────┘
                                      ▼
                     batchUpdate con ON CONFLICT DO NOTHING
```

Las dos condiciones de descarga son necesarias: solo por tamaño, una flota pequeña tardaría minutos en escribir; solo por tiempo, una ráfaga de reenvíos desbordaría la memoria.

**Pérdida aceptada:** si `processor` muere con el buffer lleno, se pierde hasta una ventana de lote. Para telemetría muestreada cada pocos segundos es irrelevante, y es la razón de que la telemetría use QoS 0 mientras las alertas usan QoS 2. Se documenta explícitamente en lugar de fingir durabilidad total. El buffer se descarga también al recibir la señal de apagado.

## Desorden: el estado actual no se puede sobrescribir a ciegas

La tabla `vehicle_state` guarda la última posición conocida para que el mapa no consulte la tabla particionada. El error fácil sería actualizarla con cada mensaje entrante, pero un reenvío de datos antiguos sobrescribiría la posición actual con una de hace horas.

```sql
UPDATE vehicle_state
SET location = ?, recorded_at = ?, motion_state = ?
WHERE vehicle_id = ?
  AND recorded_at < ?;    -- ← solo si el dato entrante es MÁS NUEVO
```

Esa guarda es lo que hace el sistema tolerante al desorden. Sin ella, un vehículo que sale de un túnel aparecería saltando hacia atrás en el mapa. Lo mismo aplica al estado de movimiento: `MotionDetector` solo se aplica cuando el mensaje es más reciente que el último procesado.

## Detección de offline con Last Will and Testament

MQTT resuelve esto de forma nativa, y es una de las razones de elegirlo. Al conectarse, el dispositivo registra un testamento:

```
tópico:  fleet/{orgId}/vehicle/{vehicleId}/status
payload: { "online": false }
retain:  true
```

Si el dispositivo se desconecta de forma no limpia (pérdida de cobertura, batería, cable), **el broker publica ese mensaje automáticamente**. `processor` lo consume y marca el vehículo como offline. Al reconectar, el dispositivo publica `{ "online": true }` retenido, que sobrescribe el anterior.

El flag `retain` importa: un cliente que se suscribe después recibe de inmediato el último estado conocido de cada vehículo, sin esperar al siguiente mensaje.

Es notablemente más limpio que un heartbeat con temporizador de expiración por vehículo, y es funcionalidad del protocolo, no código propio.

## QoS por tipo de mensaje

| Tópico | QoS | Motivo |
|---|---|---|
| `telemetry` | 0 | Alta frecuencia; perder una muestra no importa, la siguiente llega en segundos |
| `command` | 1 | Un comando debe llegar; un duplicado es tolerable si el comando es idempotente |
| `alerts` | 2 | Una alerta duplicada genera ruido y una perdida es un fallo grave |

Spring Integration MQTT permite configurar el QoS por adaptador de canal entrante.
