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
}
