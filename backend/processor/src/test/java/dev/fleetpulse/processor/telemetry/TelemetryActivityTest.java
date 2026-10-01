package dev.fleetpulse.processor.telemetry;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryActivityTest {

    private static final Instant START = Instant.parse("2026-01-01T12:00:00Z");

    private final TelemetryActivity activity = new TelemetryActivity(new MutableClock(START));

    @Test
    void assumesPositionsAsRecentAsStartupUntilSeededFromTheDatabase() {
        assertThat(activity.snapshot().pendingBound()).isEqualTo(START);

        activity.seedFromDatabase(START.minus(Duration.ofDays(3)));

        assertThat(activity.snapshot().pendingBound()).isEqualTo(START.minus(Duration.ofDays(3)));
    }

    @Test
    void onlyTheFirstSeedCounts() {
        activity.seedFromDatabase(START.minus(Duration.ofDays(3)));
        activity.seedFromDatabase(START.plus(Duration.ofDays(1)));

        assertThat(activity.snapshot().pendingBound()).isEqualTo(START.minus(Duration.ofDays(3)));
    }

    @Test
    void persistedPositionsBumpTheVersionAndRaiseTheBoundMonotonically() {
        activity.seedFromDatabase(Instant.EPOCH);
        long initialVersion = activity.snapshot().version();

        activity.recordPersisted(START.minusSeconds(60));
        activity.recordPersisted(START.minusSeconds(600));

        TelemetryActivity.Snapshot snapshot = activity.snapshot();
        assertThat(snapshot.version()).isEqualTo(initialVersion + 2);
        assertThat(snapshot.pendingBound()).isEqualTo(START.minusSeconds(60));
    }
}
