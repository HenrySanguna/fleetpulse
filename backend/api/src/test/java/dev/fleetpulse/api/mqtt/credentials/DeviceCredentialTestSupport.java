package dev.fleetpulse.api.mqtt.credentials;

import dev.fleetpulse.domain.Device;
import dev.fleetpulse.domain.DeviceRepository;
import dev.fleetpulse.domain.Organization;
import dev.fleetpulse.domain.Vehicle;
import dev.fleetpulse.domain.VehicleRepository;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpStatus.CREATED;

// Task 4.1: shared GIVEN-step helper for every device-credential test --
// creates a vehicle+device fixture and provisions real credentials through
// the actual POST /api/devices/{deviceId}/mqtt-credentials endpoint (no
// shortcuts calling the service directly), mirroring
// DispatcherLoginTestSupport's reuse pattern from WU3.
public final class DeviceCredentialTestSupport {

    private DeviceCredentialTestSupport() {
    }

    public static Device createDeviceFixture(VehicleRepository vehicles, DeviceRepository devices, Organization organization, String vehicleLabel, String deviceIdentifier) {
        Vehicle vehicle = vehicles.save(new Vehicle(organization, vehicleLabel));
        return devices.save(new Device(vehicle, deviceIdentifier + "-" + UUID.randomUUID()));
    }

    public static DeviceCredentialResponse provision(TestRestTemplate restTemplate, String baseUrl, HttpHeaders adminSession, UUID deviceId) {
        ResponseEntity<DeviceCredentialResponse> response = restTemplate.exchange(
            baseUrl + "/api/devices/" + deviceId + "/mqtt-credentials",
            HttpMethod.POST,
            new HttpEntity<>(adminSession),
            DeviceCredentialResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(CREATED);
        DeviceCredentialResponse credentials = response.getBody();
        assertThat(credentials).isNotNull();
        return credentials;
    }
}
