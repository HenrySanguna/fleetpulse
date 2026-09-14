package dev.fleetpulse.processor.geofencing;

import dev.fleetpulse.geocore.FenceTransition;

import java.util.UUID;

// One confirmed-relevant outcome of GeofenceEvaluator.evaluate()'s set
// comparison. FenceTransition.NONE (membership unchanged) is filtered out
// before construction, so an empty list from evaluate() already means
// "nothing to act on" -- a caller never has to re-check a NONE entry.
public record GeofenceTransitionResult(UUID geofenceId, FenceTransition transition) {
}
