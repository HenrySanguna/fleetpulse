## ADDED Requirements

### Requirement: Segmentación de telemetría en viajes
El sistema SHALL agrupar las posiciones de un vehículo en viajes delimitados por paradas de duración superior al umbral configurado.

#### Scenario: Traza con dos paradas prolongadas
- **GIVEN** una traza continua de un vehículo que incluye dos paradas de duración superior al umbral configurado
- **WHEN** se ejecuta la segmentación sobre esa traza
- **THEN** se generan exactamente tres viajes

#### Scenario: Parada breve no divide el viaje
- **GIVEN** una traza en la que el vehículo se detiene durante un tiempo inferior al umbral, como en un semáforo
- **WHEN** se ejecuta la segmentación
- **THEN** la parada no divide la traza, y el resultado es un único viaje

#### Scenario: Reprocesamiento de la misma ventana
- **GIVEN** una ventana temporal ya segmentada en viajes
- **WHEN** la segmentación se ejecuta de nuevo sobre esa misma ventana
- **THEN** no se crean viajes duplicados ni se alteran los ya registrados

### Requirement: Idempotencia de los agregados históricos
El sistema SHALL producir el mismo resultado al recalcular un periodo de agregación ya calculado, permitiendo absorber telemetría que llegue con retraso.

#### Scenario: Recálculo de un periodo sin datos nuevos
- **GIVEN** un periodo horario ya agregado para un vehículo
- **WHEN** se recalcula ese mismo periodo sin que hayan llegado posiciones nuevas
- **THEN** los valores agregados permanecen idénticos

#### Scenario: Llegada tardía de telemetría de un periodo ya agregado
- **GIVEN** un periodo horario ya agregado
- **WHEN** llegan posiciones desfasadas correspondientes a ese periodo y se recalcula
- **THEN** los valores agregados reflejan también las posiciones que llegaron con retraso

### Requirement: Supresión de alertas repetitivas
El sistema SHALL evitar emitir alertas equivalentes de forma repetida mientras persiste la misma condición en un vehículo.

#### Scenario: Exceso de velocidad sostenido
- **GIVEN** un vehículo que supera el límite de velocidad configurado de forma continuada durante varios minutos, emitiendo posiciones con frecuencia
- **WHEN** se procesan todas esas posiciones
- **THEN** se emite una única alerta de exceso de velocidad, en lugar de una por cada posición recibida

#### Scenario: Nueva alerta tras resolverse la condición
- **GIVEN** un vehículo cuya alerta de exceso de velocidad ya fue emitida y cuya velocidad volvió posteriormente a valores normales
- **WHEN** el vehículo vuelve a superar el límite tras un periodo dentro de lo normal
- **THEN** se emite una nueva alerta de exceso de velocidad

### Requirement: Presentación del tiempo estimado de llegada como aproximación
El sistema SHALL presentar el tiempo estimado de llegada como una estimación con margen de incertidumbre, dado que el cálculo del MVP no considera la red de carreteras ni las condiciones de tráfico.

#### Scenario: Visualización del ETA en la consola
- **GIVEN** un vehículo con un destino asignado y un tiempo estimado de llegada calculado
- **WHEN** el despachador consulta ese vehículo en la consola
- **THEN** la estimación se presenta acompañada de su margen de incertidumbre, y no como una hora de llegada exacta

### Requirement: Informes servidos desde agregados
El sistema SHALL resolver las consultas de informes de actividad a partir de las tablas de agregados, sin recorrer la tabla de posiciones.

#### Scenario: Informe de actividad semanal
- **GIVEN** un vehículo con telemetría registrada a lo largo de una semana
- **WHEN** se solicita su informe de actividad para ese periodo
- **THEN** la consulta se resuelve a partir de las tablas de agregados
- **AND** el plan de ejecución no accede a la tabla de posiciones
