# Design: Bootstrap Monorepo

## Nx sobre un build políglota

```
nx.json                     ← plugin @nx/gradle registrado
apps/console/               ← proyecto Angular, inferido por @nx/angular
backend/
  settings.gradle.kts       ← declara los cuatro módulos
  build.gradle.kts          ← aplica dev.nx.gradle.project-graph a allprojects
  api/ processor/ domain/ geo-core/
```

El plugin `dev.nx.gradle.project-graph` en el build de Gradle es lo que permite a Nx extraer las dependencias reales entre módulos Java. Sin él, Nx vería `backend` como una caja negra y `nx affected` reconstruiría todo el backend ante cualquier cambio.

**Verificación obligatoria de que esto funciona**: un commit que solo toca `apps/console` no debe disparar tareas de Gradle. Si las dispara, la integración está mal configurada y el resto del proyecto arrastrará CI lentos.

## Dos procesos, un artefacto

`api` y `processor` son módulos Gradle separados que producen dos jars, pero comparten `domain` y `geo-core`. Se empaquetan en una sola imagen Docker con ambos jars; el comando de arranque decide cuál corre.

Motivo: garantizar que ambos procesos ejecutan exactamente la misma versión del esquema y de la lógica compartida. Dos imágenes independientes podrían desplegarse desincronizadas, y un `processor` con una versión de `domain` distinta a la de `api` produce fallos difíciles de diagnosticar.

## Contrato Java → TypeScript

```
Spring (springdoc-openapi) publica /v3/api-docs
        ↓  tarea de Gradle / paso de CI
openapi-generator → libs/api-client (TypeScript)
        ↓
apps/console importa libs/api-client
```

**Regla:** `libs/api-client` es generado y nunca se edita a mano. Un paso de CI regenera y comprueba que no hay diferencias con lo commiteado; si las hay, el pipeline falla indicando que hay que regenerar. Eso convierte una desincronización de contrato en un fallo de CI en lugar de en un error de ejecución en producción.

## Salud

`/actuator/health` se extiende con indicadores propios:
- Conectividad con la base de datos (viene de serie).
- Conectividad con el broker MQTT (indicador propio).
- `commit`: el SHA inyectado en el build, para verificar qué versión está desplegada.
- Latido de `processor`: el proceso publica un mensaje retenido periódicamente y `api` lo lee.

El latido importa porque `processor` no expone HTTP: si muere, ninguna página se rompe, simplemente deja de procesarse telemetría en silencio. El despliegue debe fallar si el latido está obsoleto.

## Riesgo documentado

Hay reportes de tareas Gradle que fallan de forma intermitente bajo Nx en CI. Mitigación: fijar la versión del plugin de Gradle y del wrapper, y **no configurar reintentos automáticos** que enmascaren el problema. Si aparece intermitencia, hay que diagnosticarla, no ocultarla.
