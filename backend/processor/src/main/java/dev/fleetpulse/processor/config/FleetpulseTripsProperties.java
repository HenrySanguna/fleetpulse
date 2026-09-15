package dev.fleetpulse.processor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

// Task 1.2 (design.md "correr con retraso deliberado"): how far behind
// "now" TripSegmentationTask's processing horizon sits. Neither
// proposal.md nor design.md states an exact number; 10 minutes matches
// design.md's own worked example ("procesar posiciones de hace mas de 10
// minutos"), same undocumented-tuning-value precedent
// FleetpulseGeofencingProperties/FleetpulseMotionDetectionProperties
// already established.
//
// The stop threshold ITSELF (task 1.3, "umbral de parada configurable por
// organizacion") is deliberately NOT here: it is genuinely per-organization,
// unlike this globally-applicable delay, so it lives as
// organizations.trip_stop_threshold_secs (V10) instead -- see that
// migration's own comment for why a DB column was chosen over a second
// per-org configuration mechanism.
@ConfigurationProperties(prefix = "fleetpulse.trips")
public record FleetpulseTripsProperties(
    @DefaultValue("10m") Duration processingDelay
) {
}
