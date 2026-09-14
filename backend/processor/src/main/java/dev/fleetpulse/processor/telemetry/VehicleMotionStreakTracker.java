package dev.fleetpulse.processor.telemetry;

import dev.fleetpulse.geocore.MotionConfig;
import dev.fleetpulse.geocore.MotionDetector;
import dev.fleetpulse.geocore.MotionSample;
import dev.fleetpulse.geocore.MotionState;
import dev.fleetpulse.processor.config.FleetpulseMotionDetectionProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Task 4.2: applies geo-core's MotionDetector.next(prev, sample, cfg) only
// when a message is newer than the last one processed for that vehicle --
// design.md: "MotionDetector solo se aplica cuando el mensaje es mas
// reciente que el ultimo procesado". design.md never says where the
// previous MotionState and streak durations live between MQTT messages;
// resolved here (documented in tasks.md next to 4.2) the same way WU6
// resolved its own open design gap: persisted into
// vehicle_state.motion_state (V5) and two new streak-start timestamp
// columns (V7), written by the SAME guarded upsert task 4.1 already built
// in JdbcTelemetryPositionWriter, not a second write path or a separate
// in-memory map like TelemetryImplausibilityFilter's (that filter's own
// concern -- is this jump physically possible -- does not need to survive
// exactly like motion state does, since a filter reset on restart merely
// re-establishes a baseline; motion state read by the map/API would visibly
// regress to a stale value instead).
//
// This class is pure: no JDBC, no Spring context beyond the properties bean
// it is constructed from. It is deliberately kept separate from
// JdbcTelemetryPositionWriter so the streak-state computation itself --
// picking up where a vehicle's streak left off, resetting it when speed
// leaves the band, defaulting an unseen vehicle to STOPPED -- can be unit
// tested (VehicleMotionStreakTrackerTest) without a Testcontainers database,
// the same way TelemetryPositionBufferTest already unit-tests buffer
// flush-trigger timing against a fake TelemetryPositionWriter.
//
// computeUpdates() mirrors -- in Java, ahead of the write -- the exact same
// "is this message newer than what is currently the vehicle's state"
// comparison the SQL guard applies independently at write time
// (`recorded_at IS NULL OR recorded_at < excluded.recorded_at`). It needs
// to: MotionDetector.next() is a Java pure function, not something SQL can
// evaluate, so if a single flush batch (task 3.1) ever contains two
// messages for the same vehicle, the second one must chain from the first
// one's computed result -- not from the state that was in vehicle_state
// before the whole batch started -- exactly the way the DB's own WHERE
// clause already makes the batch's two INSERT..ON CONFLICT statements chain
// correctly for location/recorded_at (documented in WU6's progress notes).
// If the database ultimately rejects a given statement in the batch (a
// resend reordered relative to a concurrent writer), the update computed
// here for that row is simply discarded by the WHERE clause like
// location/recorded_at already are -- this class does not need to detect
// that case, only compute what would apply if the message turns out to be
// the accepted one.
@Component
public class VehicleMotionStreakTracker {

    private final MotionConfig config;

    public VehicleMotionStreakTracker(FleetpulseMotionDetectionProperties properties) {
        this.config = new MotionConfig(properties.stopThresholdKmh(), properties.startThresholdKmh(), properties.minStableDuration());
    }

    public List<VehicleMotionUpdate> computeUpdates(Map<UUID, VehicleMotionSnapshot> knownStates, List<TelemetryMessage> messages) {
        Map<UUID, VehicleMotionSnapshot> running = new HashMap<>(knownStates);
        List<VehicleMotionUpdate> updates = new ArrayList<>(messages.size());
        for (TelemetryMessage message : messages) {
            VehicleMotionSnapshot previous = running.get(message.vehicleId());
            VehicleMotionUpdate update = nextFor(previous, message);
            updates.add(update);
            if (isNewer(previous, message)) {
                running.put(
                    message.vehicleId(),
                    new VehicleMotionSnapshot(
                        message.recordedAt(), update.motionState(), update.lowSpeedStreakStartedAt(), update.highSpeedStreakStartedAt()
                    )
                );
            }
        }
        return updates;
    }

    // A message with no speedKmh cannot be evaluated by MotionDetector,
    // which requires a primitive double (MotionSample.speedKmh()). Rather
    // than guessing a speed, motion_state and both streaks are simply
    // carried forward unchanged from whatever is already known -- or left
    // NULL if nothing is known yet, since defaulting an unevaluated vehicle
    // to STOPPED would assert a state with zero evidence behind it.
    private VehicleMotionUpdate nextFor(VehicleMotionSnapshot previous, TelemetryMessage message) {
        Double speedKmh = message.speedKmh();
        if (speedKmh == null) {
            return previous == null
                ? new VehicleMotionUpdate(null, null, null)
                : new VehicleMotionUpdate(previous.motionState(), previous.lowSpeedStreakStartedAt(), previous.highSpeedStreakStartedAt());
        }

        MotionState prevState = previous == null || previous.motionState() == null ? MotionState.STOPPED : previous.motionState();
        boolean belowStopThreshold = speedKmh < config.stopThresholdKmh();
        boolean aboveStartThreshold = speedKmh >= config.startThresholdKmh();

        Instant lowStreakStartedAt = belowStopThreshold
            ? continuedOrStarted(previous == null ? null : previous.lowSpeedStreakStartedAt(), message.recordedAt())
            : null;
        Instant highStreakStartedAt = aboveStartThreshold
            ? continuedOrStarted(previous == null ? null : previous.highSpeedStreakStartedAt(), message.recordedAt())
            : null;

        Duration lowSpeedStreak = belowStopThreshold ? Duration.between(lowStreakStartedAt, message.recordedAt()) : Duration.ZERO;
        Duration highSpeedStreak = aboveStartThreshold ? Duration.between(highStreakStartedAt, message.recordedAt()) : Duration.ZERO;
        boolean engineOn = Boolean.TRUE.equals(message.ignition());

        MotionState nextState = MotionDetector.next(prevState, new MotionSample(speedKmh, engineOn, lowSpeedStreak, highSpeedStreak), config);
        return new VehicleMotionUpdate(nextState, lowStreakStartedAt, highStreakStartedAt);
    }

    private static Instant continuedOrStarted(Instant existingStreakStartedAt, Instant recordedAt) {
        return existingStreakStartedAt != null ? existingStreakStartedAt : recordedAt;
    }

    private static boolean isNewer(VehicleMotionSnapshot previous, TelemetryMessage message) {
        return previous == null || previous.recordedAt() == null || message.recordedAt().isAfter(previous.recordedAt());
    }
}
