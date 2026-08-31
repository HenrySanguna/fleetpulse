# Proposal: Add Geofencing

## Intent
Una geocerca no es una consulta espacial: es una **máquina de estados**. Saber si un vehículo está dentro de un polígono es trivial con PostGIS; lo difícil es detectar de forma fiable el instante en que *entra* o *sale*, sin generar alertas duplicadas cuando el GPS oscila justo sobre el borde, y sin perder transiciones cuando la telemetría llega desordenada o con retraso.

Es la pieza donde está el valor del producto y donde se concentran los casos borde.

## Scope

**In scope**
- Geocercas poligonales y circulares, persistidas como geometría de PostGIS.
- Evaluación de pertenencia con `ST_Contains` / `ST_DWithin` e índice GiST.
- Detección de transiciones (entrada/salida) apoyada en el estado anterior persistido.
- Amortiguación de oscilación en el borde: una transición solo se confirma tras cumplirse una condición de estabilidad.
- Reglas por geocerca: alertar al entrar, al salir, o por permanencia excesiva dentro.
- Editor de geocercas sobre el mapa (dibujo de polígonos y círculos).
- Publicación de alertas en el tópico MQTT correspondiente con QoS 2.

**Out of scope**
- Geocercas dependientes de horario (solo activas en franjas concretas) — se deja para después del MVP.
- Rutas planificadas y desviación respecto a ruta (change 06).
- Notificaciones por correo o push.

## Approach
La evaluación ocurre en `processor`, en el mismo flujo que procesa la telemetría, porque necesita el contexto de la posición anterior y debe reaccionar en segundos. Se apoya en `geo-core` para la decisión pura de transición, y en PostGIS (consulta nativa) para el cálculo de pertenencia poligonal.

El estado de pertenencia de cada par vehículo-geocerca se persiste, porque sin él es imposible distinguir "está dentro" de "acaba de entrar" — y ese estado debe sobrevivir a reinicios de `processor`.
