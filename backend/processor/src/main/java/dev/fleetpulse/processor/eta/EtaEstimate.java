package dev.fleetpulse.processor.eta;

// Task 2.2/2.3: the output of any EtaCalculator implementation -- always a
// duration plus its own margin, never a bare duration alone. Keeping the
// margin as a required field of this type (not an optional add-on computed
// elsewhere) is what makes spec.md's "nunca como hora exacta sin margen"
// structurally impossible to violate by omission: there is no code path
// that can construct an EtaEstimate without one.
public record EtaEstimate(long etaSeconds, long marginSeconds) {

    public EtaEstimate {
        if (etaSeconds < 0) {
            throw new IllegalArgumentException("etaSeconds must not be negative: " + etaSeconds);
        }
        if (marginSeconds < 0) {
            throw new IllegalArgumentException("marginSeconds must not be negative: " + marginSeconds);
        }
    }
}
