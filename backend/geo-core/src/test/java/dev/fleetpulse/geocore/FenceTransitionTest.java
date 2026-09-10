package dev.fleetpulse.geocore;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FenceTransitionTest {

    @Test
    void reportsEnteredWhenVehicleWasOutsideAndIsNowInside() {
        assertThat(FenceTransition.from(false, true)).isEqualTo(FenceTransition.ENTERED);
    }

    @Test
    void reportsExitedWhenVehicleWasInsideAndIsNowOutside() {
        assertThat(FenceTransition.from(true, false)).isEqualTo(FenceTransition.EXITED);
    }

    @Test
    void reportsNoneWhenVehicleStaysInside() {
        assertThat(FenceTransition.from(true, true)).isEqualTo(FenceTransition.NONE);
    }

    @Test
    void reportsNoneWhenVehicleStaysOutside() {
        assertThat(FenceTransition.from(false, false)).isEqualTo(FenceTransition.NONE);
    }
}
