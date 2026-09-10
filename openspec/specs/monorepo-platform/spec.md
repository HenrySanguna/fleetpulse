## ADDED Requirements

### Requirement: Grafo de Nx unificado y `nx affected` correcto sobre Gradle
El sistema SHALL exponer los módulos Gradle (`api`, `processor`, `domain`, `geo-core`) en el grafo de Nx junto a `apps/console`, vía `@nx/gradle` y `dev.nx.gradle.project-graph`, y SHALL calcular los proyectos afectados según sus dependencias reales.

#### Scenario: Grafo muestra los módulos Gradle
- **GIVEN** el workspace con `@nx/gradle` configurado
- **WHEN** se ejecuta `nx graph`
- **THEN** los cuatro módulos Gradle aparecen junto al proyecto Angular, con sus dependencias reales como aristas

#### Scenario: Cambio en la consola no dispara Gradle
- **GIVEN** un commit que solo modifica archivos de `apps/console`
- **WHEN** se ejecuta `nx affected`
- **THEN** ningún target de `api`, `processor`, `domain` o `geo-core` aparece afectado

#### Scenario: Cambio en domain reconstruye api y processor
- **GIVEN** un commit que modifica archivos de `backend/domain`
- **WHEN** se ejecuta `nx affected`
- **THEN** `api` y `processor` aparecen entre los proyectos afectados

### Requirement: Aislamiento de geo-core frente a Spring y JPA
El sistema SHALL impedir que `backend/geo-core` declare o arrastre una dependencia de Spring o de JPA/Hibernate, verificado por una comprobación del build.

#### Scenario: Build limpio sin dependencias prohibidas
- **GIVEN** la configuración de dependencias vigente de `geo-core`
- **WHEN** se ejecuta la comprobación del build
- **THEN** no se encuentra ninguna dependencia de Spring ni de JPA en su classpath

#### Scenario: Intento de introducir Spring en geo-core
- **GIVEN** un cambio que añade una dependencia de Spring o JPA a `geo-core`
- **WHEN** se ejecuta el build
- **THEN** el build falla señalando la dependencia prohibida

### Requirement: Dirección de dependencias entre módulos del backend
El sistema SHALL restringir las dependencias de compilación de modo que `api` y `processor` dependan de `domain` y `geo-core`, y ninguno dependa del otro.

#### Scenario: Dependencia cruzada entre api y processor
- **GIVEN** un cambio que declara `processor` como dependencia de `api` (o viceversa)
- **WHEN** se ejecuta el build
- **THEN** el build falla

### Requirement: Entorno local levantado con Docker Compose
El sistema SHALL levantar, con `docker compose up`, un PostgreSQL/PostGIS y un broker Mosquitto con healthcheck en ambos, y `api`/`processor` SHALL conectarse al broker al arrancar.

#### Scenario: Servicios saludables y backend conectado
- **GIVEN** el `docker-compose.yml` del repositorio
- **WHEN** se ejecuta `docker compose up`
- **THEN** PostGIS y Mosquitto reportan healthcheck saludable
- **AND** `api` y `processor` establecen conexión con Mosquitto

### Requirement: Migración inicial habilita extensiones geoespaciales y de particionado
El sistema SHALL ejecutar, vía Flyway, una migración inicial que habilite `postgis` y `pg_partman` sobre una base de datos vacía.

#### Scenario: Primer arranque contra base de datos vacía
- **GIVEN** una base de datos PostgreSQL sin migrar
- **WHEN** `domain` arranca y Flyway aplica sus migraciones
- **THEN** las extensiones `postgis` y `pg_partman` quedan habilitadas

### Requirement: Validación fail-fast de configuración por perfil
El sistema SHALL impedir el arranque de `api` y `processor`, en cualquier perfil (`local`, `test`, `prod`), cuando falte una variable de entorno obligatoria de ese perfil.

#### Scenario: Variable obligatoria ausente en producción
- **GIVEN** el perfil `prod` activo sin una variable obligatoria definida
- **WHEN** el proceso arranca
- **THEN** el arranque falla de inmediato señalando la variable faltante

#### Scenario: Configuración completa permite el arranque
- **GIVEN** todas las variables obligatorias del perfil activo definidas
- **WHEN** el proceso arranca
- **THEN** el arranque se completa correctamente

### Requirement: Endpoint de salud extendido de api
El sistema SHALL exponer en `/actuator/health` de `api` la conectividad con la base de datos, la conectividad con el broker, el SHA del commit desplegado, y la frescura del latido de `processor`.

#### Scenario: Todos los componentes saludables
- **GIVEN** base de datos accesible, broker accesible y latido reciente de `processor`
- **WHEN** se consulta `/actuator/health`
- **THEN** reporta conectividad activa de base de datos y broker, e incluye el SHA del commit

#### Scenario: Latido de processor obsoleto
- **GIVEN** que `processor` dejó de publicar su latido más allá del umbral de frescura
- **WHEN** se consulta `/actuator/health`
- **THEN** el indicador de latido reporta estado no saludable

### Requirement: Cliente TypeScript sin desincronización con el contrato OpenAPI
El sistema SHALL regenerar `libs/api-client` desde el OpenAPI de `api` como paso de CI, y SHALL hacer fallar el pipeline si el cliente regenerado difiere del commiteado.

#### Scenario: Cliente sincronizado con el contrato
- **GIVEN** un commit donde `libs/api-client` coincide con el contrato OpenAPI vigente
- **WHEN** se ejecuta el paso de regeneración en CI
- **THEN** no se detecta diferencia y el pipeline continúa

#### Scenario: DTO modificado sin regenerar el cliente
- **GIVEN** un cambio que modifica un DTO del contrato OpenAPI sin regenerar `libs/api-client`
- **WHEN** se ejecuta el paso de regeneración y comparación
- **THEN** el pipeline falla señalando la diferencia con el cliente commiteado

### Requirement: Pipeline de CI cubre Gradle y Angular con nx affected
El sistema SHALL ejecutar en CI las tareas afectadas de Gradle y de Angular vía `nx affected`, con PostGIS y Mosquitto como servicios del pipeline.

#### Scenario: Tareas afectadas con servicios disponibles
- **GIVEN** un pipeline con PostGIS y Mosquitto configurados como servicios
- **WHEN** `nx affected` determina y ejecuta las tareas de backend y frontend
- **THEN** dichas tareas se ejecutan contra instancias de servicio accesibles

### Requirement: Imagen Docker de despliegue multi-stage
El sistema SHALL construir una imagen Docker multi-stage con los jars de `api` y `processor`, y SHALL ejecutar el proceso contenedorizado con un usuario no root.

#### Scenario: Imagen con ambos jars y ejecución no root
- **GIVEN** el `Dockerfile` del repositorio
- **WHEN** se construye la imagen y se arranca un contenedor a partir de ella
- **THEN** la imagen contiene el jar de `api` y el de `processor`
- **AND** el proceso en ejecución tiene un identificador de usuario distinto de root (uid 0)
