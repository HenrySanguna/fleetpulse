## ADDED Requirements

### Requirement: Cálculo de distancia entre coordenadas geográficas
El sistema SHALL calcular la distancia sobre la superficie terrestre entre dos coordenadas, produciendo resultados correctos independientemente de la posición relativa de los puntos respecto al meridiano de origen o al antimeridiano.

#### Scenario: Distancia entre dos puntos conocidos
- **GIVEN** dos coordenadas cuya distancia real es conocida
- **WHEN** se calcula la distancia entre ellas
- **THEN** el resultado coincide con la distancia de referencia dentro de un margen de tolerancia aceptable

#### Scenario: Distancia cruzando el antimeridiano
- **GIVEN** dos coordenadas situadas a ambos lados del meridiano 180
- **WHEN** se calcula la distancia entre ellas
- **THEN** el resultado corresponde a la distancia real corta, no a la vuelta completa al planeta

#### Scenario: Distancia de un punto a sí mismo
- **GIVEN** una coordenada
- **WHEN** se calcula su distancia consigo misma
- **THEN** el resultado es cero

### Requirement: Rechazo de velocidades derivadas de instantes inválidos
El sistema SHALL rechazar el cálculo de velocidad cuando los dos puntos aportados tienen el mismo instante o el instante del segundo punto es anterior al del primero, en lugar de devolver un valor sin sentido.

#### Scenario: Instantes invertidos por reenvío de datos almacenados
- **GIVEN** dos posiciones donde la segunda tiene un instante anterior a la primera, como ocurre cuando un dispositivo reenvía datos acumulados sin cobertura
- **WHEN** se solicita la velocidad entre ambas
- **THEN** el resultado es un valor ausente que el llamante está obligado a tratar, sin devolver una velocidad negativa

#### Scenario: Instantes idénticos
- **GIVEN** dos posiciones con el mismo instante exacto
- **WHEN** se solicita la velocidad entre ambas
- **THEN** el resultado es un valor ausente que el llamante está obligado a tratar, sin producir una división por cero

### Requirement: Estabilidad del estado de movimiento frente a deriva del GPS
El sistema SHALL mantener estable el estado de movimiento de un vehículo detenido, evitando transiciones espurias causadas por la deriva natural de las lecturas de GPS.

#### Scenario: Vehículo aparcado con deriva de GPS
- **GIVEN** una traza de posiciones de un vehículo detenido, con variaciones de pocos metros entre lecturas consecutivas
- **WHEN** se evalúa el estado de movimiento a lo largo de toda la traza
- **THEN** el estado permanece en detenido durante toda la secuencia, sin producir ninguna transición a en movimiento

#### Scenario: Arranque real del vehículo
- **GIVEN** un vehículo en estado detenido que comienza a desplazarse superando de forma sostenida el umbral de arranque
- **WHEN** se evalúa el estado de movimiento
- **THEN** el estado transiciona a en movimiento

### Requirement: Detección de transiciones de geocerca
El sistema SHALL determinar si se ha producido un evento de entrada, de salida o ningún evento, a partir de la pertenencia anterior y la pertenencia actual a una geocerca.

#### Scenario: Entrada en geocerca
- **GIVEN** un vehículo que no estaba dentro de una geocerca
- **WHEN** su posición actual sí está dentro
- **THEN** se reporta una transición de entrada

#### Scenario: Salida de geocerca
- **GIVEN** un vehículo que estaba dentro de una geocerca
- **WHEN** su posición actual ya no está dentro
- **THEN** se reporta una transición de salida

#### Scenario: Permanencia dentro sin evento
- **GIVEN** un vehículo que estaba dentro de una geocerca
- **WHEN** su posición actual sigue estando dentro
- **THEN** no se reporta ninguna transición

### Requirement: Filtrado de posiciones implausibles
El sistema SHALL identificar como implausible una posición que implicaría una velocidad físicamente imposible respecto a la posición anterior, sin intentar corregirla ni interpolarla.

#### Scenario: Salto imposible por error de GPS
- **GIVEN** una posición seguida de otra que implicaría un desplazamiento de cientos de kilómetros en pocos segundos
- **WHEN** se evalúa la plausibilidad de la segunda posición
- **THEN** se marca como implausible

#### Scenario: Desplazamiento rápido pero plausible
- **GIVEN** dos posiciones cuya velocidad implícita es alta pero alcanzable por el tipo de vehículo configurado
- **WHEN** se evalúa la plausibilidad
- **THEN** la posición no se marca como implausible
