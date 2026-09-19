package dev.fleetpulse.processor.eta;

import dev.fleetpulse.geocore.Geo;
import dev.fleetpulse.geocore.GeoPoint;
import dev.fleetpulse.processor.config.FleetpulseEtaProperties;
import org.springframework.stereotype.Component;

// Task 2.2 (design.md "ETA: honestidad sobre lo que es"): "distancia en
// linea recta ajustada por un factor de sinuosidad, dividida por la
// velocidad media reciente del vehiculo". Reuses Geo.distanceMeters
// (geo-core, already idle since change 01) for the straight-line leg --
// zero new geo-core code, the same "reuse what already exists" shape
// TripSegmenter (WU1) and 05-add-geofencing's WU2 (FenceTransition.from())
// already established for this codebase. No network/traffic data enters
// this calculation at all -- the explicit out-of-scope boundary
// proposal.md draws ("Enrutamiento con red de carreteras real y trafico en
// vivo").
@Component
public class SinuosityEtaCalculator implements EtaCalculator {

    private final FleetpulseEtaProperties properties;

    public SinuosityEtaCalculator(FleetpulseEtaProperties properties) {
        this.properties = properties;
    }

    @Override
    public EtaEstimate calculate(GeoPoint currentPosition, GeoPoint destination, double recentAverageSpeedKmh) {
        double straightLineMeters = Geo.distanceMeters(currentPosition, destination);
        double adjustedKm = (straightLineMeters * properties.sinuosityFactor()) / 1000.0;

        // See FleetpulseEtaProperties.minEffectiveSpeedKmh's own comment: a
        // stopped/idling vehicle's real recent average is legitimately at or
        // near zero, which would otherwise divide into an infinite or
        // nonsensical ETA -- clamped here, at the very last step, not by
        // substituting a different average upstream (the recent average
        // itself is still the REAL one; only the division uses this floor).
        double effectiveSpeedKmh = Math.max(recentAverageSpeedKmh, properties.minEffectiveSpeedKmh());

        double etaHours = adjustedKm / effectiveSpeedKmh;
        long etaSeconds = Math.round(etaHours * 3600.0);
        // spec.md's "Presentacion del tiempo estimado de llegada como
        // aproximacion": the margin is a fixed proportion of the estimate
        // itself (FleetpulseEtaProperties.marginRatio), not a flat number of
        // minutes -- a 5-minute estimate and a 5-hour estimate do not carry
        // the same absolute uncertainty from a straight-line-plus-sinuosity
        // approximation.
        long marginSeconds = Math.round(etaSeconds * properties.marginRatio());

        return new EtaEstimate(etaSeconds, marginSeconds);
    }
}
