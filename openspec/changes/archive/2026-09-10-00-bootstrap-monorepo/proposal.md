# Proposal: Bootstrap Monorepo

## Intent
FleetPulse es una aplicación políglota: backend Java con Spring Boot, frontend Angular, ambos en un monorepo Nx. Antes de escribir lógica de dominio hay que dejar funcionando el andamiaje completo, incluidas dos piezas que este proyecto tiene y una aplicación web corriente no: un **broker MQTT** en el ciclo de desarrollo y de CI, y un **grafo de tareas que abarca proyectos Gradle y TypeScript a la vez**.

## Scope

**In scope**
- Workspace Nx con `apps/console` (Angular) y el build Gradle multi-módulo registrado vía `@nx/gradle`.
- Módulos Gradle: `backend/api`, `backend/processor`, `backend/domain`, `backend/geo-core`.
- Configuración de entorno validada: ningún proceso arranca sin sus variables obligatorias.
- Docker Compose local con PostgreSQL + PostGIS y Mosquitto.
- Flyway con migración inicial que habilita `postgis` y `pg_partman`.
- Generación del cliente TypeScript desde el OpenAPI del backend hacia `libs/api-client`.
- `/actuator/health` extendido: estado de base de datos, estado del broker, `commit` (SHA) y latido de `processor`.
- Pipeline CI con `nx affected` cubriendo tanto proyectos Java como TypeScript.
- Dockerfile e imagen desplegable; despliegue de los dos procesos de backend y de la consola.

**Out of scope**
- Cualquier entidad de dominio (vehículos, posiciones, geocercas).
- Autenticación real (change 02).
- Consumo real de MQTT más allá de una comprobación de conectividad.

## Approach
Nx orquesta ambos mundos: `@nx/gradle` registra los módulos Gradle en el grafo, de modo que `nx affected` sabe que tocar `backend/domain` obliga a reconstruir `api` y `processor`, y que tocar `apps/console` no obliga a reconstruir nada de Java.

El contrato entre backend y frontend se materializa generando el cliente TypeScript desde el OpenAPI que publica Spring: es la única forma de que un cambio de contrato en Java rompa la compilación de Angular en lugar de descubrirse en ejecución.

Se cierra el ciclo completo de CI/CD contra aplicaciones triviales. El objetivo no es tener funcionalidad, sino que el siguiente change aterrice sobre infraestructura ya probada, broker incluido.
