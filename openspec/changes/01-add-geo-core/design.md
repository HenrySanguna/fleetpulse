# Design: Geo Core

## API pública

```java
public record GeoPoint(double lat, double lon, Instant at) {}

public final class Geo {
    public static double distanceMeters(GeoPoint a, GeoPoint b);
    public static double bearingDegrees(GeoPoint a, GeoPoint b);
    public static OptionalDouble speedKmh(GeoPoint a, GeoPoint b);   // vacío si el tiempo no avanza
    public static boolean isImplausible(GeoPoint prev, GeoPoint next, double maxKmh);
    public static List<GeoPoint> simplifyTrack(List<GeoPoint> points, double toleranceMeters);
}

public enum MotionState { MOVING, IDLING, STOPPED }
public enum FenceTransition { ENTERED, EXITED, NONE }

public final class MotionDetector {
    public static MotionState next(MotionState prev, MotionSample sample, MotionConfig cfg);
}
```

`speedKmh` devuelve `OptionalDouble` en lugar de lanzar excepción o devolver un centinela: hace imposible que el llamante ignore por accidente el caso de marcas de tiempo no monótonas, que ocurre de forma rutinaria cuando un dispositivo reenvía datos acumulados.

## Decisión: histéresis asimétrica en la detección de parada

Un GPS parado sigue reportando micro-movimientos de varios metros por deriva. Sin histéresis, un vehículo aparcado alternaría entre en marcha y parado decenas de veces por minuto, generando eventos y escrituras basura.

Umbral doble: se pasa a `STOPPED` solo tras N segundos consecutivos por debajo de la velocidad mínima, y se vuelve a `MOVING` solo al superar un umbral **más alto** que el de parada. Los dos umbrales asimétricos son exactamente lo que impide la oscilación.

`IDLING` es el estado intermedio: motor encendido según el dispositivo, pero sin desplazamiento.

## Decisión: la transición de geocerca es una función de dos booleanos

`FenceTransition` se deriva de `wasInside` e `isInside`. La función es deliberadamente trivial; lo valioso es que **el estado anterior sea un parámetro explícito** en lugar de una consulta escondida. Eso la hace testeable de forma exhaustiva con cuatro casos y deja al llamante (change 05) la responsabilidad de recuperar y persistir ese estado.

Quién calcula `isInside` depende del tipo de geocerca: circular se resuelve aquí con `distanceMeters`; poligonal se delega a PostGIS.

## Decisión: filtrar posiciones implausibles, no corregirlas

Un salto de 300 km en 2 segundos es un error de GPS, no un teletransporte. La librería lo marca como implausible pero no interpola una posición intermedia: descartar es honesto, inventar sería fabricar datos.

## Riesgos
- **Antimeridiano**: una traza que cruza el meridiano 180 produce distancias absurdas con aritmética ingenua de longitudes. Haversine con la fórmula completa lo maneja bien; una aproximación plana no.
- **Precisión en coma flotante**: las comparaciones de distancia en los tests usan tolerancia explícita, nunca igualdad exacta.
