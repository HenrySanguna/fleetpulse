## ADDED Requirements

### Requirement: Detección de entrada y salida de geocerca
El sistema SHALL emitir una alerta cuando un vehículo entra o sale de una geocerca activa, según la regla configurada para esa geocerca.

#### Scenario: Entrada en geocerca con regla de entrada
- **GIVEN** una geocerca activa con regla de alerta al entrar, y un vehículo situado fuera de ella
- **WHEN** el vehículo se desplaza al interior de la geocerca de forma sostenida
- **THEN** se emite exactamente una alerta de entrada

#### Scenario: Salida de geocerca con regla de salida
- **GIVEN** una geocerca activa con regla de alerta al salir, y un vehículo situado dentro de ella
- **WHEN** el vehículo se desplaza al exterior de forma sostenida
- **THEN** se emite exactamente una alerta de salida

#### Scenario: Geocercas solapadas
- **GIVEN** dos geocercas activas que se solapan, ambas con regla de alerta al entrar
- **WHEN** un vehículo entra en la zona de solape desde el exterior de ambas
- **THEN** se emite una alerta de entrada por cada geocerca

### Requirement: Amortiguación de oscilación en el límite de geocerca
El sistema SHALL evitar la emisión de alertas cuando la pertenencia de un vehículo a una geocerca alterna por deriva de las lecturas de GPS cerca del límite.

#### Scenario: Vehículo detenido sobre el límite de la geocerca
- **GIVEN** un vehículo detenido justo sobre el límite de una geocerca activa, cuyas lecturas de GPS alternan entre el interior y el exterior por deriva
- **WHEN** se procesa la secuencia completa de lecturas
- **THEN** no se emite ninguna alerta de entrada ni de salida

#### Scenario: Entrada real tras un periodo de oscilación
- **GIVEN** el mismo vehículo, tras un periodo de lecturas oscilantes sobre el límite
- **WHEN** se adentra de forma sostenida en la geocerca superando el criterio de confirmación
- **THEN** se emite una alerta de entrada

### Requirement: Ausencia de alertas retroactivas por telemetría desfasada
El sistema SHALL evaluar las geocercas únicamente para posiciones más recientes que la última evaluada para ese vehículo, sin emitir alertas correspondientes a transiciones ocurridas durante periodos sin cobertura.

#### Scenario: Reenvío de telemetría acumulada sin cobertura
- **GIVEN** un vehículo que atravesó una geocerca mientras estaba sin cobertura, y que al reconectar reenvía las posiciones acumuladas
- **WHEN** se procesan esas posiciones desfasadas
- **THEN** no se emiten alertas de entrada ni de salida para esas transiciones pasadas
- **AND** las posiciones quedan igualmente persistidas en el histórico

### Requirement: Alerta por permanencia excesiva
El sistema SHALL emitir una alerta cuando un vehículo permanece dentro de una geocerca con regla de permanencia durante más tiempo del configurado.

#### Scenario: Permanencia superior al umbral
- **GIVEN** una geocerca con regla de permanencia y un umbral configurado, con un vehículo en su interior
- **WHEN** el vehículo permanece dentro durante un tiempo superior al umbral
- **THEN** se emite una alerta de permanencia excesiva
- **AND** no se emiten alertas adicionales mientras el vehículo siga dentro sin salir

### Requirement: Persistencia del estado de pertenencia
El sistema SHALL conservar el estado de pertenencia de cada vehículo a cada geocerca de forma duradera, de modo que un reinicio del proceso que evalúa la telemetría no provoque alertas espurias ni pérdida de transiciones.

#### Scenario: Reinicio del proceso de procesamiento con vehículos dentro de geocercas
- **GIVEN** varios vehículos situados dentro de geocercas activas, con su estado de pertenencia ya registrado
- **WHEN** el proceso que evalúa la telemetría se reinicia y vuelve a recibir datos de esos vehículos
- **THEN** no se emiten alertas de entrada para vehículos que ya constaban dentro
