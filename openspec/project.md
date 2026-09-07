# FleetPulse — Project Constitution

> Este archivo se inyecta en cada propuesta, spec, diseño y tarea. Son las restricciones no negociables del proyecto. Si un artefacto contradice algo de aquí, el artefacto está mal, no este archivo.

## Qué es FleetPulse

Plataforma de seguimiento logístico en tiempo real. Los dispositivos a bordo de los vehículos publican telemetría GPS por MQTT; los despachadores ven la flota en un mapa en vivo, reciben alertas por geocercas y consultan ETAs y actividad histórica.

**El backend es Java con Spring Boot. El frontend es Angular.** Es una aplicación políglota en un único monorepo Nx.

## Stack (no negociable sin nueva propuesta)

### Backend — Java

| Pieza | Elección | Notas |
|---|---|---|
| Lenguaje | **Java 21 LTS** | Spring Boot 4 exige Java 17 mínimo; 21 es el LTS de adopción más amplia. Java 25 LTS también es válido |
| Framework | **Spring Boot 4.1** sobre Spring Framework 7 | La línea 3.x salió de soporte OSS en junio de 2026: no usarla |
| Build | **Gradle** (multi-módulo, Kotlin DSL) | Integrado en Nx vía `@nx/gradle` |
| Persistencia | **Spring Data JPA / Hibernate** | Consultas nativas donde haga falta SQL espacial |
| Migraciones | **Flyway** | SQL versionado y revisado a mano |
| MQTT | **Spring Integration MQTT** (Eclipse Paho) | Incluido en el BOM de Spring Boot 4.1 |
| Seguridad | **Spring Security** | Sesiones de servidor para despachadores |
| Tareas programadas | **`@Scheduled`** de Spring | Sin planificador externo |
| Tests | **JUnit 5 + Testcontainers + AssertJ** | Testcontainers es nativo del ecosistema Java |

### Frontend — TypeScript

| Pieza | Elección | Notas |
|---|---|---|
| Framework | **Angular 21 LTS**, standalone, zoneless | Soporte hasta mayo de 2027 |
| Componentes UI | **PrimeNG** | Tablas, filtros y paneles de la consola |
| Mapas | **MapLibre GL JS** (BSD-3) | Teselas de proveedor gratuito, sin token de pago |
| Estado | **NgRx SignalStore** para el flujo en vivo; **`httpResource()`** nativo para datos por petición | Cada herramienta en su terreno |
| Cliente MQTT | **MQTT.js** sobre WebSocket | El navegador se suscribe directo al broker |
| Cliente HTTP | **Generado desde OpenAPI** con `openapi-generator` | El backend Java es la fuente de verdad del contrato |
| Tests | **Vitest** (unitario) + **Playwright** (E2E) | Vitest es el runner por defecto en Angular 21 |

### Infraestructura

| Pieza | Elección | Notas |
|---|---|---|
| Monorepo | **Nx** con `@nx/gradle` | Un solo grafo para proyectos Java y TypeScript |
| Base de datos | **Neon** (PostgreSQL) con **PostGIS** y **`pg_partman`** | Ambas extensiones están disponibles en Neon |
| Series temporales | **Particionado declarativo nativo + `pg_partman` + índices BRIN** | Es lo que la propia documentación de Neon recomienda |
| Broker MQTT | **Mosquitto** (EPL/EDL), autoalojado en contenedor | |
| CI/CD | **GitHub Actions** con `nx affected` | |
| Hosting | **Oracle Cloud "Always Free"** (Ampere A1, una sola VM, `api`+`processor`+Mosquitto vía Docker Compose) + **Cloudflare Pages** (consola) | Fly.io eliminó su tier gratuito permanente en 2024 (ahora requiere tarjeta y factura por uso); Oracle Always Free Ampere A1 (2 OCPU / 12GB RAM en los términos vigentes) es una VM real, gratuita mientras no se exceda la cuota, y no es serverless/scale-to-zero, requisito para `processor` (consumidor MQTT de larga duración + tareas `@Scheduled`) |

**Restricción transversal: coste cero y solo open source.** Toda dependencia debe tener licencia OSI. Toda infraestructura debe usar tiers gratuitos permanentes. Si una propuesta requiere gasto o una licencia no libre, debe decirlo explícitamente y justificarlo.

### Prohibiciones explícitas

| No usar | Motivo | Alternativa |
|---|---|---|
| **TimescaleDB** | Relicenciado bajo Timescale License (TSL), no es OSI. Neon solo ofrece la edición Apache-2, sin compresión ni las funciones que lo justificarían | Particionado nativo + `pg_partman` + BRIN |
| **Redis** | RSALv2/SSPL desde 2024 | **Valkey** (BSD) si hiciera falta caché o pub/sub |
| **Mapbox GL JS** | Licencia propietaria desde v2, requiere token de pago | **MapLibre GL JS** |
| **Spring Boot 3.x** | Fuera de soporte OSS desde junio de 2026 | Spring Boot 4.1 |
| Servicios gestionados adicionales | El proyecto no debe requerir dar de alta más cuentas de las ya previstas (Neon, Oracle Cloud, Cloudflare, GitHub) | Autoalojar en contenedor |

## Estructura del monorepo

```
apps/
  console/              Angular 21 · PrimeNG · MapLibre
backend/                build Gradle multi-módulo, registrado en el grafo de Nx
  api/                  Spring Boot — REST, emisión de credenciales MQTT, consultas
  processor/            Spring Boot — ingesta MQTT + tareas @Scheduled
  domain/               entidades JPA, repositorios, migraciones Flyway
  geo-core/             librería Java pura: cálculos geoespaciales, sin I/O ni Spring
libs/
  api-client/           cliente TypeScript generado desde el OpenAPI del backend
  console-ui/           componentes propios sobre PrimeNG
```

**Dos procesos desplegables**, no tres: `api` (HTTP) y `processor` (MQTT + tareas programadas). Separar la ingesta en un tercer proceso es un cambio de configuración de despliegue, no de código, y se hará solo si el volumen lo exige.

### Reglas de dependencia

- `geo-core` **no depende de Spring ni de JPA**. Es Java puro, sin I/O. Esta regla es la que la hace testeable en milisegundos.
- `api` y `processor` dependen de `domain` y `geo-core`; nunca uno del otro.
- `apps/console` solo consume `libs/api-client` y el broker. Nunca conoce el esquema de base de datos.
- `libs/api-client` es **generado**, nunca se edita a mano.

## Convenciones de código

### Backend
- Sin `null` en fronteras públicas: `Optional` o anotaciones de nulabilidad.
- **Toda escritura de telemetría es por lotes**, nunca un `INSERT` por mensaje recibido. Se usa `JdbcTemplate.batchUpdate` o equivalente; JPA no es la herramienta para escritura masiva.
- **Toda posición entrante puede llegar duplicada y fuera de orden.** Los dispositivos almacenan y reenvían al recuperar cobertura. La deduplicación se apoya en una restricción única de base de datos, no solo en lógica de aplicación.
- **QoS por tópico según criticidad**: telemetría QoS 0, comandos QoS 1, alertas QoS 2.
- Las consultas espaciales usan funciones `ST_*` de PostGIS mediante consulta nativa. Nunca se trae geometría a memoria para calcular contención en Java.
- El mantenimiento de particiones se invoca explícitamente desde una tarea `@Scheduled` que llama a `partman.run_maintenance_proc()`. **No se confía en el background worker de `pg_partman`**: Neon suspende el compute por inactividad y el worker no se ejecuta durante la suspensión.
- Índices BRIN en columnas temporales de tablas append-only; GiST en columnas de geometría.

### Frontend
- TypeScript estricto, sin `any`, sin `@ts-ignore`.
- El cliente HTTP se regenera desde el OpenAPI del backend; un cambio de contrato que rompa el frontend debe romper la compilación, no descubrirse en ejecución.
- **Las credenciales MQTT del navegador son efímeras y con ACL por tópico**, emitidas por el backend. Un despachador nunca recibe credenciales que le permitan suscribirse a vehículos de otra organización. La ACL se aplica en el broker, no en la interfaz.

## Testing — no negociable

- `geo-core`: cobertura de ramas **100%**. Es Java puro sin I/O, no hay excusa.
- Todo consumidor MQTT tiene test de **idempotencia** (el mismo mensaje procesado dos veces produce un solo efecto) y de **desorden** (mensajes con marcas de tiempo no monótonas).
- Los tests de integración usan **Testcontainers con PostGIS y Mosquitto reales**, nunca mocks, para cualquier lógica que involucre consultas espaciales o semántica del broker.
- Toda consulta espacial o de histórico tiene un test que verifica el plan de ejecución: sin recorrido secuencial sobre la tabla de posiciones.

## Riesgos conocidos del stack

| Riesgo | Mitigación |
|---|---|
| Tareas Gradle intermitentes bajo Nx en CI | Fijar versiones de plugin y Gradle; no enmascarar con reintentos automáticos |
| Neon suspende el compute por inactividad y detiene los background workers | El mantenimiento de particiones se invoca desde `@Scheduled`, no desde el BGW de `pg_partman` |
| Arranque en frío de la JVM en tier gratuito | Documentarlo en el README; considerar CDS o AOT solo si molesta de verdad |
| El contrato entre Java y TypeScript no está garantizado por el compilador | Cliente generado desde OpenAPI + verificación en CI de que el cliente está sincronizado |

## Fuente de la verdad

Estos artefactos OpenSpec son la especificación completa del proyecto. Si algo no está especificado en un `spec.md`, es una decisión pendiente, no una omisión a rellenar por inferencia: pregunta antes de implementar.
