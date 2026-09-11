package dev.fleetpulse.processor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Task 2.4: the speed threshold above which a position implies a physically
// impossible jump (Geo.isImplausible, geo-core). Neither design.md nor
// proposal.md states a required number; 300 km/h is a documented default
// generous enough to never reject a genuine fast highway sample or ordinary
// GPS jitter, while still catching the "cientos de kilómetros en pocos
// segundos" scenario design.md describes -- adjustable per deployment
// without a code change.
@ConfigurationProperties(prefix = "fleetpulse.telemetry.implausibility")
public record FleetpulseTelemetryImplausibilityProperties(
    @DefaultValue("300") double maxSpeedKmh
) {
}
