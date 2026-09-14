# Proposal: Add Fleet Auth

## Intent
FleetPulse tiene tres identidades que se autentican contra dos sistemas distintos. Su particularidad: el backend no solo autentica usuarios contra sí mismo, sino que **emite credenciales para que terceros hablen con un broker que el backend no controla en el momento de la conexión**.

- Un **despachador** usa la consola web (HTTP) y además se conecta al broker MQTT desde el navegador.
- Un **dispositivo** a bordo de un vehículo se conecta al broker directamente, sin pasar nunca por la API.
- Los **procesos internos** (`ingest`, `worker`) también son clientes del broker.

Si la autorización viviera solo en la API, un despachador con credenciales de broker podría suscribirse a los tópicos de cualquier vehículo de cualquier organización. La ACL tiene que aplicarse **en el broker**.

## Scope

**In scope**
- Autenticación de despachadores por sesión (HTTP), con organización asociada.
- Emisión de credenciales MQTT efímeras para el navegador, con ACL acotada a los tópicos de su organización.
- Credenciales por dispositivo, con ACL acotada a los tópicos de su propio vehículo.
- Integración de la ACL con Mosquitto para que el broker aplique las restricciones.
- Rotación y revocación de credenciales de dispositivo.
- Roles: `dispatcher`, `fleet_admin`.

**Out of scope**
- Aprovisionamiento físico de dispositivos (fuera del software).
- mTLS con certificados por dispositivo — se usa usuario/contraseña con ACL; el diseño deja la puerta abierta pero no se implementa en el MVP.
- SSO corporativo.

## Approach
La API actúa como **autoridad emisora de credenciales** para un sistema externo. Para el navegador, emite credenciales MQTT de vida corta ligadas a la sesión del despachador; cuando la sesión caduca o se revoca, las credenciales dejan de renovarse. Para los dispositivos, emite credenciales de larga duración pero individualmente revocables.

Mosquitto valida esas credenciales y aplica la ACL por tópico. El patrón de tópicos es lo que hace la ACL expresable: `fleet/{orgId}/vehicle/{vehicleId}/...`, de modo que una regla con comodín (`fleet/acme/#`) acota a una organización sin enumerar vehículos.
