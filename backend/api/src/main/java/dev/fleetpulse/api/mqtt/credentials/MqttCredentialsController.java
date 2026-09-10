package dev.fleetpulse.api.mqtt.credentials;

import dev.fleetpulse.api.security.AuthenticatedDispatcher;
import dev.fleetpulse.api.security.CurrentDispatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Task 3.1: requires an authenticated dispatcher session (SecurityConfig's
// default anyRequest().authenticated() already covers this path, no new
// permitAll needed).
@RestController
@RequestMapping("/api/mqtt")
public class MqttCredentialsController {

    private final CurrentDispatcher currentDispatcher;
    private final BrowserMqttCredentialService credentialService;

    public MqttCredentialsController(CurrentDispatcher currentDispatcher, BrowserMqttCredentialService credentialService) {
        this.currentDispatcher = currentDispatcher;
        this.credentialService = credentialService;
    }

    @GetMapping("/credentials")
    public MqttCredentialsResponse credentials() {
        AuthenticatedDispatcher dispatcher = currentDispatcher.require();
        return credentialService.issueCredentials(dispatcher);
    }
}
