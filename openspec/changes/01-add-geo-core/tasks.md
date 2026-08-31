# Tasks: Add Geo Core

## 1. Módulo
- [ ] 1.1 Módulo Gradle `backend/geo-core` sin dependencias de Spring ni JPA
- [ ] 1.2 Comprobación en el build que falla si alguien añade una dependencia de Spring
- [ ] 1.3 Tipos inmutables: `GeoPoint`, `MotionSample`, `MotionConfig` como `record`

## 2. Cálculos
- [ ] 2.1 `distanceMeters` con haversine completo, correcto en el antimeridiano
- [ ] 2.2 `bearingDegrees` normalizado a 0–360
- [ ] 2.3 `speedKmh` devolviendo `OptionalDouble`, vacío si el tiempo no avanza
- [ ] 2.4 `isImplausible` con velocidad máxima configurable
- [ ] 2.5 `simplifyTrack` con tolerancia en metros, conservando primer y último punto

## 3. Estado de movimiento y geocercas
- [ ] 3.1 `MotionConfig` con umbral de parada, umbral de arranque (mayor) y duración mínima
- [ ] 3.2 `MotionDetector.next` con histéresis asimétrica
- [ ] 3.3 Estado `IDLING` a partir de la señal de motor encendido
- [ ] 3.4 Derivación de `FenceTransition` desde pertenencia anterior y actual
- [ ] 3.5 Contención en geocerca circular vía `distanceMeters`

## 4. Tests (cobertura de ramas 100% — ver `project.md`)
- [ ] 4.1 Distancia entre dos ciudades conocidas, contrastada con valor de referencia y tolerancia explícita
- [ ] 4.2 Distancia cruzando el antimeridiano
- [ ] 4.3 Distancia cero entre un punto y sí mismo
- [ ] 4.4 `speedKmh` con marcas de tiempo iguales → `OptionalDouble` vacío
- [ ] 4.5 `speedKmh` con marcas de tiempo invertidas → `OptionalDouble` vacío
- [ ] 4.6 Histéresis: una traza de vehículo aparcado con deriva realista NO produce ninguna transición
- [ ] 4.7 Histéresis: un arranque real SÍ produce transición a `MOVING`
- [ ] 4.8 Las cuatro combinaciones de `FenceTransition`
- [ ] 4.9 Salto implausible detectado; desplazamiento rápido pero plausible NO marcado
- [ ] 4.10 `simplifyTrack` conserva siempre primer y último punto

## Definición de terminado
- [ ] `backend/geo-core` no tiene ninguna dependencia de Spring, JPA ni acceso a red
- [ ] Cobertura de ramas 100% verificada en el build
- [ ] El test 4.6 usa una traza con ruido realista, no dos puntos idénticos
- [ ] La suite completa de `geo-core` corre en menos de un segundo
