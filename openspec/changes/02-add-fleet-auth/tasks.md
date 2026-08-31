# Tasks: Add Fleet Auth

## 1. Modelo
- [ ] 1.1 Entidades JPA `Organization`, `User`, `Device`, `MqttCredential`; migración Flyway
- [ ] 1.2 Relación `Device` → `Vehicle` → `Organization`
- [ ] 1.3 Spring Session con JDBC para persistir sesiones de despachador

## 2. Sesión de despachador
- [ ] 2.1 Spring Security con autenticación por formulario y hash de contraseña con Argon2 o BCrypt
- [ ] 2.2 Cookie de sesión `HttpOnly`, `Secure`, `SameSite=Strict`
- [ ] 2.3 Resolución del `orgId` del usuario autenticado en cada petición
- [ ] 2.4 Autorización por rol (`DISPATCHER`, `FLEET_ADMIN`) con anotaciones de método
- [ ] 2.5 Invalidación de sesión al desactivar un usuario

## 3. Credenciales MQTT de navegador
- [ ] 3.1 `GET /api/mqtt/credentials`: genera credenciales efímeras ligadas a la sesión
- [ ] 3.2 Registro de ACL de solo lectura sobre `fleet/{orgId}/#`
- [ ] 3.3 Tarea `@Scheduled` que purga credenciales expiradas

## 4. Credenciales de dispositivo
- [ ] 4.1 Alta de dispositivo con ACL acotada a su propio vehículo
- [ ] 4.2 Revocación: elimina credencial y fuerza desconexión de la sesión activa en el broker
- [ ] 4.3 Rotación de credencial sin dar de baja el dispositivo

## 5. Integración con Mosquitto
- [ ] 5.1 Backend de autenticación y ACL de Mosquitto apoyado en el almacén de credenciales
- [ ] 5.2 Configuración que prohíbe conexiones anónimas en **ambos** listeners (TCP y WebSocket)

## 6. Tests (Testcontainers con Mosquitto real)
- [ ] 6.1 Un despachador de la organización A NO puede suscribirse a tópicos de la organización B
- [ ] 6.2 Un dispositivo NO puede publicar en el tópico de telemetría de otro vehículo
- [ ] 6.3 Un dispositivo NO puede suscribirse al tópico de alertas de su organización
- [ ] 6.4 Credenciales de navegador expiradas no permiten conexión
- [ ] 6.5 Revocar un dispositivo corta su conexión activa
- [ ] 6.6 Conexión anónima rechazada en ambos listeners
- [ ] 6.7 Desactivar un despachador invalida su sesión HTTP en la petición siguiente

## Definición de terminado
- [ ] Ningún test consigue una suscripción cruzada entre organizaciones
- [ ] El broker rechaza conexiones anónimas también en el listener de WebSocket
