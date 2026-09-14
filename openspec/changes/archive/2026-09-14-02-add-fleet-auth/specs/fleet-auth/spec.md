## ADDED Requirements

### Requirement: Aislamiento de datos entre organizaciones en el broker
El sistema SHALL garantizar que las credenciales MQTT emitidas para un despachador solo permiten suscribirse a tópicos de su propia organización, aplicando la restricción en el broker y no únicamente en la interfaz de usuario.

#### Scenario: Intento de suscripción cruzada entre organizaciones
- **GIVEN** un despachador autenticado de la organización A, con credenciales MQTT válidas emitidas por la API
- **WHEN** intenta suscribirse a un tópico perteneciente a la organización B usando esas credenciales
- **THEN** el broker rechaza la suscripción
- **AND** el despachador no recibe ningún mensaje de la organización B

#### Scenario: Suscripción legítima a la propia organización
- **GIVEN** el mismo despachador de la organización A
- **WHEN** se suscribe al tópico comodín de su propia organización
- **THEN** la suscripción se acepta y recibe la telemetría de los vehículos de su flota

### Requirement: Confinamiento de credenciales de dispositivo a su propio vehículo
El sistema SHALL emitir para cada dispositivo credenciales cuya autorización de publicación se limita exclusivamente a los tópicos de su vehículo asociado.

#### Scenario: Dispositivo intenta suplantar a otro vehículo
- **GIVEN** un dispositivo con credenciales válidas asociadas al vehículo V1
- **WHEN** intenta publicar telemetría en el tópico correspondiente al vehículo V2
- **THEN** el broker rechaza la publicación

#### Scenario: Dispositivo intenta escuchar tópicos de alertas
- **GIVEN** un dispositivo con credenciales válidas
- **WHEN** intenta suscribirse al tópico de alertas de su organización
- **THEN** el broker rechaza la suscripción, ya que un dispositivo solo puede suscribirse a su propio tópico de comandos

### Requirement: Expiración de credenciales efímeras de navegador
El sistema SHALL emitir credenciales MQTT de navegador con una vida limitada, de modo que dejen de ser utilizables una vez expiradas aunque la conexión se intente de nuevo.

#### Scenario: Reconexión con credenciales caducadas
- **GIVEN** unas credenciales MQTT de navegador que han superado su instante de expiración
- **WHEN** se intenta establecer una nueva conexión al broker con ellas
- **THEN** el broker rechaza la conexión

#### Scenario: Renovación tras revocar la sesión del despachador
- **GIVEN** un despachador cuya sesión HTTP ha sido revocada
- **WHEN** su cliente intenta renovar las credenciales MQTT antes de que expiren las actuales
- **THEN** la renovación se rechaza, y la conexión al broker queda cortada al expirar las credenciales vigentes

### Requirement: Revocación inmediata de dispositivos
El sistema SHALL cortar la conexión activa de un dispositivo en el momento en que sus credenciales se revocan, sin esperar a que el dispositivo se reconecte por su cuenta.

#### Scenario: Revocación de un dispositivo conectado
- **GIVEN** un dispositivo con una conexión MQTT activa y publicando telemetría
- **WHEN** un administrador de flota revoca sus credenciales
- **THEN** la conexión existente se cierra
- **AND** cualquier intento posterior de reconexión con esas credenciales es rechazado

### Requirement: Prohibición de acceso anónimo al broker
El sistema SHALL rechazar toda conexión al broker que no aporte credenciales válidas, en todos los listeners habilitados.

#### Scenario: Conexión anónima por el listener WebSocket
- **GIVEN** el broker con su configuración de producción
- **WHEN** un cliente intenta conectarse por el listener de WebSocket sin credenciales
- **THEN** la conexión es rechazada
