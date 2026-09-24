package dev.fleetpulse.processor.simulator;

// Task 5.9: the geographic box demo vehicles seed and move within. Field
// order mirrors SIMULATOR_BOUNDS's "minLat,minLon,maxLat,maxLon" env format
// (DeviceSimulatorSettings.parseBounds) -- validated here so a malformed or
// inverted box fails fast with a clear message instead of silently letting
// vehicles seed outside the intended region or never move at all.
public record SimulationBounds(double minLat, double minLon, double maxLat, double maxLon) {

    public static final SimulationBounds GLOBAL = new SimulationBounds(-90.0, -180.0, 90.0, 180.0);

    public SimulationBounds {
        if (minLat < -90.0 || maxLat > 90.0 || minLat >= maxLat) {
            throw new IllegalArgumentException("Invalid latitude bounds: minLat=" + minLat + ", maxLat=" + maxLat);
        }
        if (minLon < -180.0 || maxLon > 180.0 || minLon >= maxLon) {
            throw new IllegalArgumentException("Invalid longitude bounds: minLon=" + minLon + ", maxLon=" + maxLon);
        }
    }
}
