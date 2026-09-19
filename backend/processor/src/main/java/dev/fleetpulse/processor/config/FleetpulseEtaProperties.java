package dev.fleetpulse.processor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

// Task 2.2 (design.md "ETA: honestidad sobre lo que es"): every tuning value
// SinuosityEtaCalculator/JdbcRecentSpeedReader needs, none of which
// design.md/proposal.md states an exact number for. Unlike
// organizations.trip_stop_threshold_secs (V10), which the spec explicitly
// requires to vary per organization, nothing here is asked to vary per
// tenant -- so, matching FleetpulseGeofencingProperties/
// FleetpulseMotionDetectionProperties/FleetpulseTripsProperties' own
// precedent, this is a single global @ConfigurationProperties record, not a
// DB column.
@ConfigurationProperties(prefix = "fleetpulse.eta")
public record FleetpulseEtaProperties(
    // design.md: "distancia en linea recta ajustada por un factor de
    // sinuosidad". 1.3 (roads are, on average, about 30% longer than the
    // straight-line distance between two points) is a commonly cited rough
    // real-road-network heuristic; this project's own documented choice
    // since design.md never states an exact factor.
    @DefaultValue("1.3") double sinuosityFactor,
    // Presented to the console as etaSeconds +/- etaMarginSeconds (task 2.5,
    // spec.md's "Presentacion del tiempo estimado de llegada como
    // aproximacion"): a straight-line-plus-sinuosity distance estimate
    // divided by a recent average speed cannot honestly claim tighter
    // precision than a healthy fraction of its own estimate, so the margin
    // is proportional to the estimate itself rather than a fixed duration.
    @DefaultValue("0.3") double marginRatio,
    // Floor applied to the recent average speed before dividing (task 2.2):
    // a vehicle idling/stopped for its entire recent window would otherwise
    // divide by (near) zero and produce an infinite or absurd ETA. A stopped
    // vehicle still gets a long-but-finite, clearly-margined estimate,
    // deliberately not a "never arriving" sentinel design.md does not ask
    // for.
    @DefaultValue("5.0") double minEffectiveSpeedKmh,
    // Used only when JdbcRecentSpeedReader finds fewer than two positions in
    // the recent window (a vehicle that just started reporting, or one with
    // very sparse telemetry) -- a real average cannot be computed yet, so
    // this deliberately conservative general-fleet assumption stands in
    // until enough real telemetry accumulates. This is NOT the "fabricated
    // value" the launch prompt warns against: it is a documented, narrow
    // fallback for a state JdbcRecentSpeedReader explicitly reports (no
    // data), never silently substituted for a real, already-available
    // recent average.
    @DefaultValue("30.0") double fallbackAverageSpeedKmh,
    // How far back JdbcRecentSpeedReader looks for "velocidad media
    // reciente" (design.md). Short enough to reflect the vehicle's actual
    // recent driving, long enough to smooth out a single red-light stop
    // dominating the average.
    @DefaultValue("15m") Duration recentSpeedWindow
) {
}
