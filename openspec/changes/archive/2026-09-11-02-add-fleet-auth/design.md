# Design: Fleet Auth

## Jerarquía de tópicos (es la que hace posible la ACL)

```
fleet/{orgId}/vehicle/{vehicleId}/telemetry     dispositivo → broker   (QoS 0)
fleet/{orgId}/vehicle/{vehicleId}/status        dispositivo → broker   (retenido + LWT)
fleet/{orgId}/vehicle/{vehicleId}/command       broker → dispositivo   (QoS 1)
fleet/{orgId}/alerts                            processor → broker     (QoS 2)
fleet/{orgId}/presence/{processId}              procesos internos      (retenido)
```

El `orgId` como segundo nivel no es cosmético: permite que la ACL de un despachador sea **una sola regla con comodín** (`fleet/acme/#`) en lugar de una lista de vehículos que habría que reescribir cada vez que la flota cambia.

## Despachadores: sesión de servidor con Spring Security

Spring Security con sesión de servidor y cookie `HttpOnly`, `Secure`, `SameSite=Strict`. La sesión se persiste en base de datos (Spring Session con JDBC) para que sobreviva a reinicios y para poder revocarla de forma inmediata.

Se elige sesión de servidor en lugar de un token autocontenido por una razón concreta del dominio: un despachador dado de baja debe perder el acceso **en la petición siguiente**, no cuando expire un token.

## Credenciales MQTT del navegador: efímeras y derivadas de la sesión

```
GET /api/mqtt/credentials     (requiere sesión de despachador)
  → genera usuario/contraseña de vida corta
  → registra ACL de solo lectura sobre fleet/{suOrgId}/#
  → responde { username, password, wsUrl, expiresAt }
```

El navegador se conecta al broker con esas credenciales por MQTT sobre WebSocket y las renueva antes de que expiren. Si la sesión del despachador se revoca, la renovación falla y la conexión MQTT muere al expirar las credenciales vigentes.

**Consecuencia aceptada y documentada:** la revocación no es instantánea en el canal MQTT, sino que tarda como máximo el TTL de las credenciales. Es el precio de que el navegador hable directamente con el broker en lugar de a través del backend. Se mitiga con un TTL corto.

## Dispositivos: credenciales permanentes pero revocables

Un dispositivo puede pasar días sin cobertura, así que no puede depender de renovar credenciales cada hora. Sus credenciales son de larga duración con ACL acotada a **su propio vehículo**:

```
publicar en:   fleet/{orgId}/vehicle/{suVehicleId}/telemetry
publicar en:   fleet/{orgId}/vehicle/{suVehicleId}/status
suscribir a:   fleet/{orgId}/vehicle/{suVehicleId}/command
```

Un dispositivo comprometido no puede publicar telemetría falsa en nombre de otro vehículo ni escuchar comandos ajenos. La revocación sí es inmediata: se elimina la credencial y se corta la conexión activa.

## Por qué la ACL vive en el broker y no en la aplicación

Es la decisión central de este change. Si el navegador se conecta directamente al broker, cualquier filtrado que hiciera el backend sería irrelevante: el cliente podría suscribirse a `fleet/#` y recibirlo todo. La única barrera real es la ACL que aplica el propio broker en el momento de la suscripción.

Mosquitto valida credenciales y ACL contra el almacén gestionado por el backend, de modo que el backend sigue siendo la autoridad emisora aunque no esté en el camino de los datos.

## Riesgo
Una ACL con comodín demasiado amplio (`fleet/#` en lugar de `fleet/acme/#`) filtraría datos entre organizaciones **sin producir ningún error visible**. Se cubre con un test de integración contra un Mosquitto real que intenta activamente la suscripción cruzada y verifica que el broker la rechaza. Es el test más importante de este change.
