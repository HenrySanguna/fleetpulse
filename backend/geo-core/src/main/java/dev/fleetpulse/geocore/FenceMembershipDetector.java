package dev.fleetpulse.geocore;

import java.time.Duration;
import java.time.Instant;

public final class FenceMembershipDetector {

    private FenceMembershipDetector() {
    }

    public static FenceMembershipState next(FenceMembershipState prev, FenceMembershipSample sample, FenceMembershipConfig cfg) {
        boolean effectiveInside = prev.inside() ? sample.insideBufferedBoundary() : sample.insideStrictBoundary();

        if (effectiveInside == prev.inside()) {
            return prev.pendingSince() == null ? prev : FenceMembershipState.confirmed(prev.inside(), prev.since());
        }

        Instant pendingSince = prev.pendingSince() != null ? prev.pendingSince() : sample.observedAt();
        int streak = prev.pendingReadingCount() + 1;

        boolean confirmedByReadings = streak >= cfg.confirmationReadings();
        boolean confirmedByDuration = Duration.between(pendingSince, sample.observedAt())
            .compareTo(cfg.confirmationDuration()) >= 0;

        if (confirmedByReadings || confirmedByDuration) {
            return FenceMembershipState.confirmed(effectiveInside, sample.observedAt());
        }

        return new FenceMembershipState(prev.inside(), prev.since(), pendingSince, streak);
    }
}
