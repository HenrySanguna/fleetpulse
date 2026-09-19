# Design: Geofencing

## Modelo

```sql
CREATE TABLE geofences (
  id          uuid PRIMARY KEY,
  org_id      uuid NOT NULL,
  name        text NOT NULL,
  area        geography(Polygon, 4326) NOT NULL,
  rule        text NOT NULL,          -- 'on_enter' | 'on_exit' | 'on_dwell'
  dwell_secs  integer,                -- solo para on_dwell
  is_active   boolean NOT NULL DEFAULT true
);
CREATE INDEX ON geofences USING GIST (area);

CREATE TABLE vehicle_fence_state (
  vehicle_id   uuid NOT NULL,
  geofence_id  uuid NOT NULL,
  is_inside    boolean NOT NULL,
  since        timestamptz NOT NULL,
  pending_since timestamptz,          -- candidata a transición, aún sin confirmar
  PRIMARY KEY (vehicle_id, geofence_id)
);
```

Las geocercas circulares se modelan también como `Polygon` (buffer del punto) para tener un solo camino de evaluación en lugar de dos ramas.

## Evaluación: una consulta, no N

El error obvio sería iterar las geocercas de la organización y evaluar una por una. Con una sola consulta espacial se obtienen todas las geocercas que contienen el punto, usando el índice GiST:

```sql
SELECT id FROM geofences
WHERE org_id = :orgId AND is_active
  AND ST_Contains(area::geometry, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326))
```

Se ejecuta como consulta nativa: JPQL no expresa funciones de PostGIS, y traer las geometrías a memoria para evaluarlas en Java sería exactamente lo que `project.md` prohíbe.

El conjunto resultante se compara con el conjunto de geocercas donde el vehículo constaba dentro. La diferencia en un sentido son entradas; en el otro, salidas. Es una operación de conjuntos, no un bucle de consultas.

## El problema real: oscilación en el borde

Un vehículo aparcado justo sobre el límite de una geocerca alterna entre dentro y fuera con cada lectura de GPS por simple deriva. Sin amortiguación, eso genera decenas de alertas de entrada y salida en minutos — el fallo que hace inutilizable un sistema de geocercas en producción.

**Solución de dos partes:**

1. **Confirmación temporal.** Una transición detectada no se emite de inmediato: se marca como pendiente (`pending_since`) y solo se confirma si la nueva pertenencia se mantiene durante N lecturas consecutivas o T segundos. Si vuelve al estado anterior antes de confirmarse, la pendiente se descarta sin emitir nada.

2. **Margen espacial asimétrico.** Se entra usando el polígono real, pero se sale solo al superar un polígono ligeramente mayor (`ST_Buffer` de unos metros). Es la misma idea de histéresis asimétrica que se usó para el estado de movimiento en `geo-core`: los umbrales de entrada y salida no coinciden, y eso es lo que impide la oscilación.

## Telemetría desordenada

Igual que en el change 03, un reenvío de datos antiguos no debe reevaluar el estado actual de pertenencia. La regla: **las geocercas solo se evalúan para mensajes más recientes que el último evaluado para ese vehículo.**

Consecuencia aceptada y documentada: las transiciones ocurridas durante un periodo sin cobertura **no generan alertas retroactivas**. Un vehículo que entró y salió de una geocerca mientras estaba desconectado no dispara alertas al reconectar. Alertar de una entrada de hace tres horas sería peor que no alertar: el despachador no puede actuar sobre ella, y el ruido erosiona la confianza en el sistema.

Lo que sí queda es el rastro: las posiciones se persisten igualmente, así que el histórico permite reconstruir el paso a posteriori.

## Alertas

Se publican en `fleet/{orgId}/alerts` con **QoS 2**: una alerta duplicada genera ruido operativo y una perdida es un fallo grave, así que se paga el coste del protocolo de entrega exactamente-una-vez. Contrasta deliberadamente con la telemetría, que va en QoS 0.
