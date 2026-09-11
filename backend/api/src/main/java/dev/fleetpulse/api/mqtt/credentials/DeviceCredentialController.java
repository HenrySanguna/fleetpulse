package dev.fleetpulse.api.mqtt.credentials;

import dev.fleetpulse.api.security.AuthenticatedDispatcher;
import dev.fleetpulse.api.security.CurrentDispatcher;
import dev.fleetpulse.domain.Device;
import dev.fleetpulse.domain.DeviceRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// Tasks 4.1/4.2/4.3: fleet-admin-only device credential lifecycle, gated the
// same way as DispatcherSessionController#findDispatcher (task 2.4) -- a
// device belonging to another organization is hidden as 404, not exposed as
// 403, matching that same design intent (an ordinary HTTP endpoint must not
// leak cross-org existence any more than the broker ACL does).
//
// DeviceRepository is resolved through an ObjectProvider, not injected
// directly, for the same reason as DispatcherSessionController: this is an
// ordinary eagerly instantiated singleton, and some deployment profiles boot
// the api module with no DataSource at all.
@RestController
@RequestMapping("/api/devices/{deviceId}/mqtt-credentials")
public class DeviceCredentialController {

    private final CurrentDispatcher currentDispatcher;
    private final ObjectProvider<DeviceRepository> devices;
    private final DeviceCredentialService credentialService;

    public DeviceCredentialController(
            CurrentDispatcher currentDispatcher,
            ObjectProvider<DeviceRepository> devices,
            DeviceCredentialService credentialService) {
        this.currentDispatcher = currentDispatcher;
        this.devices = devices;
        this.credentialService = credentialService;
    }

    // Transactional (matches DispatcherUserDetailsService's precedent for
    // the same class of issue): Device -> Vehicle -> Organization is two
    // hops of FetchType.LAZY, and unlike a single hop where only the
    // identifier is ever read, resolving deviceInOwnOrgOrThrow's ORGANIZATION
    // check on a not-yet-loaded VEHICLE proxy requires initializing the
    // Vehicle itself, which needs an open persistence context -- and
    // credentialService.provision/revoke/rotate below read that same
    // association again. @Transactional is placed HERE, not on the private
    // helper: Spring's proxy-based @Transactional has no effect on a
    // same-class self-invocation (deviceInOwnOrgOrThrow(deviceId) called as
    // a plain internal call below never goes through the transactional
    // proxy), so the boundary has to start at the externally-invoked method.
    @PostMapping
    @PreAuthorize("hasRole('FLEET_ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public DeviceCredentialResponse provision(@PathVariable UUID deviceId) {
        return credentialService.provision(deviceInOwnOrgOrThrow(deviceId));
    }

    @DeleteMapping
    @PreAuthorize("hasRole('FLEET_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void revoke(@PathVariable UUID deviceId) {
        credentialService.revoke(deviceInOwnOrgOrThrow(deviceId));
    }

    @PostMapping("/rotate")
    @PreAuthorize("hasRole('FLEET_ADMIN')")
    @Transactional
    public DeviceCredentialResponse rotate(@PathVariable UUID deviceId) {
        return credentialService.rotate(deviceInOwnOrgOrThrow(deviceId));
    }

    private Device deviceInOwnOrgOrThrow(UUID deviceId) {
        AuthenticatedDispatcher requester = currentDispatcher.require();
        Device device = devices.getObject().findById(deviceId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!device.getVehicle().getOrganization().getId().equals(requester.organizationId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return device;
    }
}
