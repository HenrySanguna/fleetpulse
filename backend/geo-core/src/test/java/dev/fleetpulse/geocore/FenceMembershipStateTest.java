package dev.fleetpulse.geocore;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FenceMembershipStateTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void confirmedFactoryCreatesAStateWithNoPendingTransition() {
        FenceMembershipState state = FenceMembershipState.confirmed(true, T0);

        assertThat(state.inside()).isTrue();
        assertThat(state.since()).isEqualTo(T0);
        assertThat(state.pendingSince()).isNull();
        assertThat(state.pendingReadingCount()).isZero();
    }

    @Test
    void acceptsAStateWithAnActivePendingTransition() {
        FenceMembershipState state = new FenceMembershipState(false, T0, T0.plusSeconds(10), 2);

        assertThat(state.pendingSince()).isEqualTo(T0.plusSeconds(10));
        assertThat(state.pendingReadingCount()).isEqualTo(2);
    }

    @Test
    void rejectsAMissingPendingSinceWithANonZeroPendingReadingCount() {
        assertThatThrownBy(() -> new FenceMembershipState(false, T0, null, 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAPresentPendingSinceWithAZeroPendingReadingCount() {
        assertThatThrownBy(() -> new FenceMembershipState(false, T0, T0.plusSeconds(10), 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANegativePendingReadingCountEvenWithAPendingSincePresent() {
        assertThatThrownBy(() -> new FenceMembershipState(false, T0, T0.plusSeconds(10), -1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
