# Tasks: Bootstrap Monorepo

## 1. Workspace Nx
- [x] 1.1 Crear workspace Nx
- [x] 1.2 Generar `apps/console` (Angular 21, standalone, zoneless)
- [x] 1.3 Instalar y configurar el plugin `@nx/gradle`
- [x] 1.4 Verificar que `nx graph` muestra los módulos Gradle junto al proyecto Angular (parcial, sin cambios respecto al cierre de la sección 1: el lado Gradle está probado directamente — `./gradlew.bat nxProjectGraph` genera `build/nx/*.json` por módulo con las aristas de dependencia reales, p. ej. `api -> domain`, `api -> geo-core` — pero `npx nx show projects`/`nx graph` todavía fallan en esta máquina porque `@nx/gradle` invoca `gradlew.bat` con `child_process.execFile(..., { shell: true })` sin comillas alrededor de una ruta con espacios (`C:\Henry\Mis proyectos\Fleetpulse`); reproducido de forma aislada con Node puro, ver informe de aplicación de la sección 2. No es un problema de configuración de este repo, es una limitación de `@nx/gradle` con rutas de workspace que contienen espacios en Windows)

## 2. Build Gradle
- [x] 2.1 `backend/settings.gradle.kts` con los módulos `api`, `processor`, `domain`, `geo-core`
- [x] 2.2 Spring Boot 4.1 y Java 21 en el build raíz; `dev.nx.gradle.project-graph` aplicado a `allprojects`
- [x] 2.3 `geo-core` sin dependencia de Spring ni de JPA (verificado por una comprobación del build)
- [x] 2.4 `api` y `processor` dependen de `domain` y `geo-core`, nunca entre sí

## 3. Datos
- [x] 3.1 `docker-compose.yml` con PostGIS y Mosquitto, ambos con healthcheck
- [x] 3.2 `mosquitto.conf` con listener MQTT y listener WebSocket
- [x] 3.3 Flyway configurado en `domain`; migración `V1__init.sql` con `CREATE EXTENSION postgis` y `pg_partman`
- [x] 3.4 Perfiles de Spring: `local`, `test`, `prod`; validación de configuración que impide arrancar sin variables obligatorias

## 4. Contrato Java → TypeScript
- [x] 4.1 `springdoc-openapi` en `api`, exponiendo el documento OpenAPI (`org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0` — la línea 2.x de springdoc no soporta Spring Boot 4/Spring Framework 7; verificado contra Maven Central que 3.1.0 hereda de `spring-boot-starter-parent:4.1.0`. Test `OpenApiDocumentPublicationTest` con `@SpringBootTest(webEnvironment = RANDOM_PORT)` real: arranca el contexto completo con `spring.autoconfigure.exclude` sobre `DataSourceAutoConfiguration`/`HibernateJpaAutoConfiguration`/`FlywayAutoConfiguration` — en Spring Boot 4.1 estas clases viven en paquetes nuevos (`org.springframework.boot.jdbc.autoconfigure.*`, `org.springframework.boot.hibernate.autoconfigure.*`, `org.springframework.boot.flyway.autoconfigure.*`) — para no depender de una base de datos real solo para publicar el documento OpenAPI. RED confirmado antes de añadir la dependencia (404 en `/v3/api-docs`), GREEN después)
- [x] 4.2 Tarea que genera `libs/api-client` con `openapi-generator` (`scripts/api-client/generate.mjs`, expuesto como `npm run generate:api-client` y como target Nx `api-client:generate` en `libs/api-client/project.json`. El script compila `api:bootJar`, arranca el jar con las mismas autoconfiguraciones excluidas que en 4.1, pide `/v3/api-docs`, y genera con `openapi-generator` (`typescript-angular`) hacia `libs/api-client/src`. Salida generada commiteada, no en `.gitignore`; marcada como generada en `.gitattributes` (`linguist-generated=true`) y excluida de ESLint/Prettier. Como no hay endpoints reales todavía, el cliente generado es un scaffold casi vacío (`export const APIS = [];`) — es lo esperado. Añadido mapeo de path `@fleetpulse/api-client` en `tsconfig.base.json` para cuando `apps/console` empiece a consumirlo. Nota Windows: `openapi-generator-cli` (el wrapper npm) corta mal argumentos con espacios — p. ej. `-o "C:\...\Mis proyectos\..."` — al reenviarlos a su proceso Java interno; mismo tipo de bug ya documentado para `@nx/gradle` en la tarea 1.4 pero en el paquete `@openapitools/openapi-generator-cli`, no en Nx. Mitigado generando siempre en un directorio temporal sin espacios y copiando el resultado con `fs.cp` de Node en vez de pasarle a la CLI una ruta de salida dentro del workspace)
- [x] 4.3 Paso de CI que regenera y falla si hay diferencias con lo commiteado (mismo script con `--check`, expuesto como `npm run generate:api-client:check` y como target Nx `api-client:check-drift`. Regenera en un directorio temporal y compara archivo por archivo contra `libs/api-client/src`; sin diferencias, exit 0; con diferencias, exit 1 y lista los archivos afectados. Verificado en local: contra una generación limpia pasa (GREEN); tras editar a mano `libs/api-client/src/variables.ts` falla señalando exactamente ese archivo (RED); revertido el cambio manual vuelve a pasar (GREEN). Esta tarea entrega el script ejecutable y verificado — el cableado real en `.github/workflows/ci.yml` (paso de CI, servicios, caché) es de la sección 6, batch 6)

## 5. Salud y observabilidad
- [ ] 5.1 `/actuator/health` con indicadores de base de datos y de broker
- [ ] 5.2 Exponer `commit` (SHA inyectado en el build) en el endpoint de información
- [ ] 5.3 `processor` publica latido retenido periódicamente; `api` lo expone en su salud

## 6. CI/CD
- [ ] 6.1 `ci.yml` con `nx affected` cubriendo tareas Gradle y de Angular
- [ ] 6.2 Servicios de CI: PostGIS y Mosquitto con healthcheck
- [ ] 6.3 Caché de dependencias de Gradle y de node_modules
- [ ] 6.4 Dockerfile multi-stage: build de Gradle, imagen final con ambos jars y usuario no root
- [ ] 6.5 Despliegue de los dos procesos de backend y de la consola
- [ ] 6.6 Verificación post-despliegue: SHA correcto y latido de `processor` reciente

## Definición de terminado
- [ ] `docker compose up` levanta PostGIS y Mosquitto; ambos procesos arrancan y se conectan al broker
- [ ] Un commit que solo toca `apps/console` NO dispara tareas de Gradle (verificar en logs de `nx affected`)
- [ ] Un commit que toca `backend/domain` SÍ reconstruye `api` y `processor`
- [ ] Cambiar un DTO del backend sin regenerar el cliente hace fallar el pipeline
