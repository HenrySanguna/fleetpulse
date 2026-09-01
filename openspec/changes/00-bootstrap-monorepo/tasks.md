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
- [ ] 4.1 `springdoc-openapi` en `api`, exponiendo el documento OpenAPI
- [ ] 4.2 Tarea que genera `libs/api-client` con `openapi-generator`
- [ ] 4.3 Paso de CI que regenera y falla si hay diferencias con lo commiteado

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
