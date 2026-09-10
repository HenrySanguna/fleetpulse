package dev.fleetpulse.geocore;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MotionStateTest {

    @Test
    void definesMovingIdlingAndStoppedAsDistinctValues() {
        assertThat(MotionState.values())
            .containsExactly(MotionState.MOVING, MotionState.IDLING, MotionState.STOPPED);
    }
}
