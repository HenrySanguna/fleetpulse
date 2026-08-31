## ADDED Requirements

### Requirement: Deduplicación de telemetría reenviada
El sistema SHALL persistir una sola vez cada posición identificada por vehículo e instante de registro, incluso si el mismo mensaje se recibe múltiples veces.

#### Scenario: Reenvío del mismo mensaje
- **GIVEN** una posición de telemetría ya persistida para un vehículo en un instante dado
- **WHEN** se recibe de nuevo un mensaje con el mismo vehículo y el mismo instante de registro
- **THEN** no se crea una segunda fila para esa combinación
- **AND** el procesamiento no produce error

#### Scenario: Ráfaga de reenvío tras recuperar cobertura
- **GIVEN** un dispositivo que acumuló posiciones sin cobertura y las reenvía todas al reconectar, solapándose parcialmente con posiciones ya recibidas
- **WHEN** se procesa la ráfaga completa
- **THEN** las posiciones nuevas se persisten y las ya conocidas se descartan, sin duplicados en el histórico

### Requirement: Tolerancia a telemetría desordenada
El sistema SHALL preservar el estado actual del vehículo frente a la llegada de posiciones con instante de registro anterior al último procesado, sin dejar de persistirlas en el histórico.

#### Scenario: Posición antigua llega después de una reciente
- **GIVEN** un vehículo cuyo estado actual refleja una posición registrada a las 10:00
- **WHEN** se recibe una posición del mismo vehículo registrada a las 09:30
- **THEN** el estado actual del vehículo sigue reflejando la posición de las 10:00
- **AND** la posición de las 09:30 queda persistida en el histórico

#### Scenario: Posición más reciente actualiza el estado
- **GIVEN** el mismo vehículo con estado actual a las 10:00
- **WHEN** se recibe una posición registrada a las 10:05
- **THEN** el estado actual del vehículo pasa a reflejar la posición de las 10:05

### Requirement: Descarte de posiciones implausibles
El sistema SHALL rechazar la persistencia de posiciones cuya distancia respecto a la anterior implique una velocidad físicamente imposible para el vehículo.

#### Scenario: Salto imposible por error de GPS
- **GIVEN** un vehículo con una posición conocida reciente
- **WHEN** se recibe una posición que implicaría un desplazamiento de cientos de kilómetros en pocos segundos
- **THEN** la posición no se persiste
- **AND** el descarte queda contabilizado para su observación

### Requirement: Detección de desconexión de dispositivo
El sistema SHALL marcar un vehículo como fuera de línea cuando su dispositivo se desconecta del broker de forma no limpia, sin depender de un temporizador de inactividad propio.

#### Scenario: Pérdida abrupta de conexión del dispositivo
- **GIVEN** un dispositivo conectado al broker que ha registrado un mensaje de testamento al conectarse
- **WHEN** la conexión se interrumpe de forma abrupta, sin desconexión ordenada
- **THEN** el broker publica el mensaje de testamento del dispositivo
- **AND** el vehículo asociado queda marcado como fuera de línea

#### Scenario: Reconexión del dispositivo
- **GIVEN** un vehículo marcado como fuera de línea
- **WHEN** su dispositivo se reconecta y publica su estado en línea
- **THEN** el vehículo vuelve a estar marcado como en línea

#### Scenario: Cliente que se suscribe después de la desconexión
- **GIVEN** un vehículo fuera de línea cuyo estado fue publicado como mensaje retenido
- **WHEN** un nuevo cliente se suscribe al tópico de estado de ese vehículo
- **THEN** recibe inmediatamente el estado de fuera de línea, sin esperar a un mensaje posterior

### Requirement: Resistencia del consumidor a mensajes malformados
El sistema SHALL descartar los mensajes de telemetría que no cumplen el contrato de payload, sin interrumpir el procesamiento de los mensajes siguientes.

#### Scenario: Mensaje con payload inválido
- **GIVEN** el consumidor de telemetría en funcionamiento
- **WHEN** recibe un mensaje cuyo payload no cumple el esquema esperado
- **THEN** el mensaje se descarta y se contabiliza
- **AND** el consumidor sigue procesando los mensajes posteriores con normalidad

### Requirement: Consultas históricas sobre datos particionados
El sistema SHALL resolver las consultas de histórico por vehículo y rango temporal aprovechando la poda de particiones y los índices definidos, sin recorrer secuencialmente la tabla de posiciones.

#### Scenario: Consulta de histórico acotada por rango
- **GIVEN** una tabla de posiciones con datos repartidos en varias particiones temporales
- **WHEN** se consulta el histórico de un vehículo para un rango de fechas concreto
- **THEN** el plan de ejecución no incluye un recorrido secuencial de la tabla de posiciones
