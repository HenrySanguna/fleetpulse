package dev.fleetpulse.processor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

// Tasks 3.1/3.3 (WU3) + WU4 wiring: feeds geo-core's FenceMembershipConfig
// (confirmationReadings/confirmationDuration) and the exit buffer distance
// GeofenceEvaluator's buffered-containment query (WU4) uses. Neither
// design.md nor proposal.md states required numbers, the same precedent as
// FleetpulseMotionDetectionProperties. Defaults: 3 consecutive readings OR
// 30s of sustained membership (mirrors MotionConfig's own minStableDuration
// fixture) confirms a transition, whichever comes first; a 15m exit buffer
// is comfortably wider than consumer-grade GPS's typical few-meters drift
// without being so large that a genuine short trip outside the geofence
// barely clears it.
@ConfigurationProperties(prefix = "fleetpulse.geofencing")
public record FleetpulseGeofencingProperties(
    @DefaultValue("3") int confirmationReadings,
    @DefaultValue("30s") Duration confirmationDuration,
    @DefaultValue("15") double exitBufferMeters,
    // T8 (prod-qa-findings): GeofenceRuleDispatcher's own silence window, the
    // same AlertSilenceEngine pattern FleetpulseAlertingProperties.silenceWindow
    // already applies to speeding/excessive-idle -- see GeofenceRuleDispatcher's
    // class comment for why a geofence alert (an event, not a continuous
    // condition) uses that engine differently. A separate property, not a
    // shared one: geofence oscillation and sustained-condition re-notification
    // are different tuning concerns with no reason to share a default.
    @DefaultValue("10m") Duration silenceWindow
) {
}
