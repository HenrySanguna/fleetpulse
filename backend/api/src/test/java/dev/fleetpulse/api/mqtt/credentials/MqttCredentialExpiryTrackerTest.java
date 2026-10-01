package dev.fleetpulse.api.mqtt.credentials;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MqttCredentialExpiryTrackerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private final MqttCredentialExpiryTracker tracker = new MqttCredentialExpiryTracker();

    @Test
    void neverSkipsBeforeTheFirstPurgeRun() {
        assertThat(tracker.canSkip(NOW)).isFalse();
    }

    @Test
    void skipsWhenNothingRemainsAfterAPurge() {
        tracker.startPurge();
        tracker.finishPurge(Optional.empty());

        assertThat(tracker.canSkip(NOW)).isTrue();
    }

    @Test
    void skipsOnlyUntilTheEarliestKnownExpiry() {
        tracker.startPurge();
        tracker.finishPurge(Optional.of(NOW.plusSeconds(100)));

        assertThat(tracker.canSkip(NOW.plusSeconds(99))).isTrue();
        assertThat(tracker.canSkip(NOW.plusSeconds(100))).isFalse();
    }

    @Test
    void issuanceMergesAsTheMinimumAfterAPurge() {
        tracker.startPurge();
        tracker.finishPurge(Optional.of(NOW.plusSeconds(500)));

        tracker.recordIssued(NOW.plusSeconds(900));
        assertThat(tracker.canSkip(NOW.plusSeconds(499))).isTrue();
        assertThat(tracker.canSkip(NOW.plusSeconds(500))).isFalse();

        tracker.recordIssued(NOW.plusSeconds(60));
        assertThat(tracker.canSkip(NOW.plusSeconds(61))).isFalse();
    }

    @Test
    void issuanceAfterAnIdleStateMakesTheTrackerQueryOnceItExpires() {
        tracker.startPurge();
        tracker.finishPurge(Optional.empty());

        tracker.recordIssued(NOW.plusSeconds(300));

        assertThat(tracker.canSkip(NOW.plusSeconds(299))).isTrue();
        assertThat(tracker.canSkip(NOW.plusSeconds(300))).isFalse();
    }

    @Test
    void issuanceDuringAPurgeRunIsNotOverwrittenByTheRecomputedBound() {
        tracker.startPurge();
        tracker.recordIssued(NOW.plusSeconds(300));
        tracker.finishPurge(Optional.empty());

        assertThat(tracker.canSkip(NOW.plusSeconds(299))).isTrue();
        assertThat(tracker.canSkip(NOW.plusSeconds(300))).isFalse();
    }

    @Test
    void issuanceDuringAPurgeRunMergesWithTheRecomputedBound() {
        tracker.startPurge();
        tracker.recordIssued(NOW.plusSeconds(300));
        tracker.finishPurge(Optional.of(NOW.plusSeconds(100)));

        assertThat(tracker.canSkip(NOW.plusSeconds(99))).isTrue();
        assertThat(tracker.canSkip(NOW.plusSeconds(100))).isFalse();
    }

    @Test
    void aPurgeRunThatNeverFinishesForcesAQueryNextTick() {
        tracker.startPurge();
        tracker.finishPurge(Optional.empty());

        tracker.startPurge();

        assertThat(tracker.canSkip(NOW)).isFalse();
    }
}
