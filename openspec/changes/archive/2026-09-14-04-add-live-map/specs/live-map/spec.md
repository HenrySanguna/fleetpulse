## ADDED Requirements

### Requirement: Sincronización inicial sin pérdida de actualizaciones
El sistema SHALL garantizar que ninguna actualización de posición emitida durante la carga inicial del mapa se pierda, suscribiéndose al flujo en vivo antes de solicitar el estado actual de la flota.

#### Scenario: Actualización durante la carga del snapshot
- **GIVEN** una consola iniciando su carga, ya suscrita al flujo en vivo y con la petición de estado actual en curso
- **WHEN** un vehículo emite una nueva posición antes de que llegue la respuesta del estado actual
- **THEN** esa posición se aplica una vez procesado el snapshot
- **AND** el mapa refleja la posición más reciente, no la del snapshot

#### Scenario: Mensaje anterior al snapshot descartado
- **GIVEN** una consola que ha aplicado el estado actual de la flota
- **WHEN** se procesa un mensaje bufferizado cuyo instante de registro es anterior al del snapshot para ese vehículo
- **THEN** el mensaje se descarta sin modificar el estado mostrado

### Requirement: Resincronización tras reconexión
El sistema SHALL repetir el ciclo completo de sincronización inicial cuando la conexión en vivo se restablece tras una interrupción, en lugar de continuar aplicando actualizaciones sobre un estado potencialmente obsoleto.

#### Scenario: Reconexión tras pérdida de red
- **GIVEN** una consola que perdió la conexión con el flujo en vivo durante un periodo en el que hubo actualizaciones
- **WHEN** la conexión se restablece
- **THEN** la consola vuelve a obtener el estado actual completo de la flota antes de continuar aplicando actualizaciones en vivo

### Requirement: Separación entre posición interpolada y posición reportada
El sistema SHALL mostrar exclusivamente posiciones efectivamente reportadas en cualquier vista de datos del vehículo, reservando la interpolación para el desplazamiento visual del marcador en el mapa.

#### Scenario: Consulta de detalle durante una interpolación
- **GIVEN** un marcador de vehículo desplazándose de forma interpolada entre dos posiciones reportadas
- **WHEN** el usuario abre el detalle de ese vehículo
- **THEN** los datos mostrados corresponden a la última posición reportada, no a la posición intermedia calculada

#### Scenario: Ausencia prolongada de actualizaciones
- **GIVEN** un vehículo cuyo marcador se está desplazando de forma interpolada
- **WHEN** no se recibe ninguna posición nueva durante un periodo superior a la ventana esperada
- **THEN** la interpolación se detiene en la última posición reportada, sin extrapolar hacia posiciones futuras
- **AND** el marcador se muestra atenuado para indicar la falta de datos recientes

### Requirement: Independencia del mapa en vivo respecto al servicio HTTP
El sistema SHALL mantener las actualizaciones en vivo del mapa mientras la conexión con el broker esté activa, con independencia de la disponibilidad del servicio HTTP.

#### Scenario: Servicio HTTP no disponible
- **GIVEN** una consola con el mapa cargado y una conexión activa al flujo en vivo
- **WHEN** el servicio HTTP deja de estar disponible
- **THEN** las posiciones de los vehículos siguen actualizándose en el mapa
- **AND** las funciones que dependen del servicio HTTP, como la consulta de histórico, indican su indisponibilidad sin bloquear el mapa
