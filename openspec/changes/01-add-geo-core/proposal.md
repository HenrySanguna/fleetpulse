# Proposal: Add Geo Core

## Intent
Toda la inteligencia de FleetPulse descansa sobre cálculos geoespaciales: a qué velocidad va un vehículo entre dos puntos, si ha entrado o salido de una geocerca, si está parado o en movimiento. Esa lógica tiene muchos más casos borde de los que aparenta (antimeridiano, deriva del GPS, marcas de tiempo desordenadas) y es la más difícil de depurar si aparece enterrada bajo MQTT, Hibernate y PostGIS.

Se construye primero como **librería Java pura**, sin Spring, sin JPA y sin I/O.

## Scope

**In scope**
- `backend/geo-core`: distancia entre coordenadas, rumbo, velocidad derivada de dos posiciones con marca de tiempo.
- Detección de estado de movimiento (en marcha, ralentí, parado) con histéresis para no oscilar por deriva del GPS.
- Decisión de transición de geocerca a partir de la pertenencia anterior y la actual.
- Filtrado de posiciones implausibles por velocidad imposible.
- Simplificación de trazas para reducir puntos antes de enviarlas al navegador.

**Out of scope**
- Consultas espaciales sobre PostGIS (change 05). Aquí solo se resuelve la contención en geocercas circulares; las poligonales se delegan a PostGIS.
- Cálculo de ETA (change 06).
- Cualquier I/O, acceso a base de datos o dependencia de Spring.

## Approach
Clases y métodos estáticos Java sin dependencias más allá de la biblioteca estándar. Las entradas son tipos inmutables (`record`), lo que permite testear con trazas sintéticas sin levantar contexto de Spring: los tests corren en milisegundos.

La decisión clave es que `geo-core` **no conoce el concepto de vehículo ni de base de datos**: opera sobre puntos y sobre estados previos que le pasa el llamante. Eso la hace reutilizable desde `processor`, desde `api` y desde los tests sin ninguna adaptación.
