package dev.fleetpulse.processor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

// Task 4.1 (design.md "la ventana de recalculo cubre las ultimas horas, no
// solo la anterior, precisamente para absorber esos reenvios"): design.md
// names the shape of the wide window but not its exact span. Neither
// proposal.md nor design.md states a number; 24 hours is this work unit's
// own documented choice (same "note deviations, don't silently freelance"
// precedent as FleetpulseTripsProperties.processingDelay's own 10m default)
// -- long enough to absorb a device that buffered readings while offline for
// most of a day and catches up once reconnected (the same class of
// late-arrival scenario spec.md's own "Llegada tardia de telemetria de un
// periodo ya agregado" scenario describes), without recomputing a vehicle's
// entire history on every run.
@ConfigurationProperties(prefix = "fleetpulse.rollups")
public record FleetpulseRollupsProperties(
    @DefaultValue("24h") Duration window
) {
}
