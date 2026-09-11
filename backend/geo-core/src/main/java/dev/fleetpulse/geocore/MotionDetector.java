package dev.fleetpulse.geocore;

public final class MotionDetector {

    private MotionDetector() {
    }

    public static MotionState next(MotionState prev, MotionSample sample, MotionConfig cfg) {
        if (prev == MotionState.MOVING) {
            if (isStableBelowStopThreshold(sample, cfg)) {
                return sample.engineOn() ? MotionState.IDLING : MotionState.STOPPED;
            }
            return MotionState.MOVING;
        }

        if (isStableAboveStartThreshold(sample, cfg)) {
            return MotionState.MOVING;
        }
        if (prev == MotionState.STOPPED && sample.engineOn()) {
            return MotionState.IDLING;
        }
        if (prev == MotionState.IDLING && !sample.engineOn()) {
            return MotionState.STOPPED;
        }

        return prev;
    }

    private static boolean isStableBelowStopThreshold(MotionSample sample, MotionConfig cfg) {
        return sample.speedKmh() < cfg.stopThresholdKmh()
            && sample.lowSpeedStreak().compareTo(cfg.minStableDuration()) >= 0;
    }

    private static boolean isStableAboveStartThreshold(MotionSample sample, MotionConfig cfg) {
        return sample.speedKmh() >= cfg.startThresholdKmh()
            && sample.highSpeedStreak().compareTo(cfg.minStableDuration()) >= 0;
    }
}
