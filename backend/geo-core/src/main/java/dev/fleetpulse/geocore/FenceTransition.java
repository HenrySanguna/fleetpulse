package dev.fleetpulse.geocore;

public enum FenceTransition {
    ENTERED,
    EXITED,
    NONE;

    public static FenceTransition from(boolean wasInside, boolean isInside) {
        if (!wasInside && isInside) {
            return ENTERED;
        }
        if (wasInside && !isInside) {
            return EXITED;
        }
        return NONE;
    }
}
