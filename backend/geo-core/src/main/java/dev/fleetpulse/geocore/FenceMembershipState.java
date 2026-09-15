package dev.fleetpulse.geocore;

import java.time.Instant;

public record FenceMembershipState(boolean inside, Instant since, Instant pendingSince, int pendingReadingCount) {

    public FenceMembershipState {
        if ((pendingSince == null) != (pendingReadingCount == 0)) {
            throw new IllegalArgumentException(
                "pendingSince and pendingReadingCount must both be unset or both be set: "
                    + "pendingSince=" + pendingSince + ", pendingReadingCount=" + pendingReadingCount
            );
        }
        if (pendingReadingCount < 0) {
            throw new IllegalArgumentException(
                "pendingReadingCount must not be negative: " + pendingReadingCount
            );
        }
    }

    public static FenceMembershipState confirmed(boolean inside, Instant since) {
        return new FenceMembershipState(inside, since, null, 0);
    }
}
