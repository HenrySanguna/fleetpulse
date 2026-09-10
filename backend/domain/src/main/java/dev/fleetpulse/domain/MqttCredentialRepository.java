package dev.fleetpulse.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MqttCredentialRepository extends JpaRepository<MqttCredential, UUID> {

    Optional<MqttCredential> findByUsername(String username);

    // Task 3.3: only dispatcher-session credentials have a non-null
    // expiresAt (MqttCredential.forDevice leaves it null); SQL's NULL
    // comparison semantics exclude those rows automatically, no extra
    // predicate needed.
    List<MqttCredential> findByExpiresAtBefore(Instant instant);

    // Tasks 4.2/4.3: revoke() marks revokedAt but never deletes the row (kept
    // for audit history), so a device's CURRENT credential is the one row
    // with a null revokedAt -- at most one at a time by construction (revoke
    // and rotate both retire the old row before a new one is issued).
    Optional<MqttCredential> findByDeviceAndRevokedAtIsNull(Device device);
}
