package dev.fleetpulse.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MqttCredentialRepository extends JpaRepository<MqttCredential, UUID> {

    Optional<MqttCredential> findByUsername(String username);
}
