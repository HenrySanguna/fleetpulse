package dev.fleetpulse.api.fleet;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

// vehicle_state.online is only written from MQTT status/Last Will messages, so
// a device that goes silent without a Last Will stays "online" forever.
// The snapshot therefore derives the flag at read time: online only while the
// last report is younger than this threshold (no scheduled job, no extra polling).
@ConfigurationProperties(prefix = "fleetpulse.fleet.state")
@Validated
public record FleetpulseFleetStateProperties(@NotNull @DefaultValue("5m") Duration onlineStalenessThreshold) {
}
