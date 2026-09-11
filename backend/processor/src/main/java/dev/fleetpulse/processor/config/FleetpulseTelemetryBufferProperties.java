package dev.fleetpulse.processor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

// Task 3.1: the in-memory buffer flushes on whichever of two triggers
// happens first (design.md) -- maxSize rows accumulated, or flushInterval
// elapsed since the last flush. Neither design.md nor proposal.md states
// required numbers, same precedent as WU2's 90-day retention default: 500
// rows keeps the worst-case lost-buffer window (design.md's accepted
// at-most-one-batch loss on crash) small, and 5 seconds keeps a quiet,
// low-traffic fleet's positions from sitting unwritten for long. Both are
// deployment-adjustable without a code change.
@ConfigurationProperties(prefix = "fleetpulse.telemetry.buffer")
public record FleetpulseTelemetryBufferProperties(
    @DefaultValue("500") int maxSize,
    @DefaultValue("5s") Duration flushInterval
) {
}
