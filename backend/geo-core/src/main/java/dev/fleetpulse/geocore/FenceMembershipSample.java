package dev.fleetpulse.geocore;

import java.time.Instant;

public record FenceMembershipSample(boolean insideStrictBoundary, boolean insideBufferedBoundary, Instant observedAt) {

    public FenceMembershipSample {
        if (insideStrictBoundary && !insideBufferedBoundary) {
            throw new IllegalArgumentException(
                "insideStrictBoundary cannot be true while insideBufferedBoundary is false: "
                    + "the buffered boundary always contains the strict boundary"
            );
        }
    }
}
