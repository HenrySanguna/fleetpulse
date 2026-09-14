package dev.fleetpulse.api.fleet;

import dev.fleetpulse.api.security.AuthenticatedDispatcher;
import dev.fleetpulse.api.security.CurrentDispatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Task 2.1: current state of every vehicle in the requesting dispatcher's
// organization -- the HTTP half of design.md's "snapshot + stream" startup
// sequence (the MQTT half is WU1's MqttConnectionService). SecurityConfig's
// default anyRequest().authenticated() already covers this path, matching
// MqttCredentialsController's precedent -- no new permitAll needed.
@RestController
@RequestMapping("/api/fleet")
public class FleetStateController {

    private final CurrentDispatcher currentDispatcher;
    private final FleetStateService fleetStateService;

    public FleetStateController(CurrentDispatcher currentDispatcher, FleetStateService fleetStateService) {
        this.currentDispatcher = currentDispatcher;
        this.fleetStateService = fleetStateService;
    }

    @GetMapping("/state")
    public FleetStateResponse state() {
        AuthenticatedDispatcher dispatcher = currentDispatcher.require();
        return fleetStateService.currentState(dispatcher.organizationId());
    }
}
