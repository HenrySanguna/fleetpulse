package dev.fleetpulse.processor.telemetry;

import dev.fleetpulse.geocore.Geo;
import dev.fleetpulse.geocore.GeoPoint;
import dev.fleetpulse.processor.config.FleetpulseTelemetryImplausibilityProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Task 2.4: discards a position whose implied speed from the last known
// good position is physically impossible (Geo.isImplausible, geo-core --
// zero new geo-core code needed). design.md never says where that "last
// known position" reference comes from. WU6's future monotonic guard
// (`UPDATE vehicle_state ... WHERE recorded_at < ?`) protects vehicle_state
// writes and does not exist yet in this work unit; even once it does, it
// answers a different question (has vehicle_state moved backward) than this
// filter's (is this jump physically possible). Reading vehicle_state.location
// here would also not work today: nothing in this work unit writes
// vehicle_state yet, so it would stay NULL for every vehicle and this filter
// could never reject anything. Instead this filter keeps its own in-memory
// last-accepted-position-per-vehicle map, advanced monotonically by
// recordedAt (never regressed by a late-arriving resend) so implausibility
// is always judged against the most recent known-good fix, independent of
// and without duplicating WU6's vehicle_state guard.
@Component
public class TelemetryImplausibilityFilter {

    private final double maxSpeedKmh;
    private final Counter implausibleMessageCounter;
    private final Map<UUID, GeoPoint> lastAcceptedPositions = new ConcurrentHashMap<>();

    public TelemetryImplausibilityFilter(FleetpulseTelemetryImplausibilityProperties properties, MeterRegistry meterRegistry) {
        this.maxSpeedKmh = properties.maxSpeedKmh();
        this.implausibleMessageCounter = Counter.builder("fleetpulse.telemetry.messages.implausible")
            .description("Telemetry positions discarded because they implied a physically impossible speed")
            .register(meterRegistry);
    }

    public boolean isPlausible(TelemetryMessage message) {
        GeoPoint next = toGeoPoint(message);
        GeoPoint previous = lastAcceptedPositions.get(message.vehicleId());

        if (previous != null && Geo.isImplausible(previous, next, maxSpeedKmh)) {
            implausibleMessageCounter.increment();
            return false;
        }

        lastAcceptedPositions.merge(message.vehicleId(), next, TelemetryImplausibilityFilter::mostRecent);
        return true;
    }

    private static GeoPoint mostRecent(GeoPoint existing, GeoPoint candidate) {
        return candidate.at().isAfter(existing.at()) ? candidate : existing;
    }

    private static GeoPoint toGeoPoint(TelemetryMessage message) {
        return new GeoPoint(message.lat(), message.lon(), message.recordedAt());
    }
}
