package dev.fleetpulse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

// Owned by exactly one of device or user (never both, never neither): device
// credentials are long-lived and vehicle-scoped, dispatcher-session
// credentials are short-lived and org-scoped (design.md). The two named
// factories are the only way to build one, so an invalid dual/no owner state
// cannot be constructed through this class; the mqtt_credentials CHECK
// constraint enforces the same invariant at the schema level.
@Entity
@Table(name = "mqtt_credentials")
public class MqttCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    private Device device;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MqttCredential() {
    }

    private MqttCredential(String username, String passwordHash, Device device, User user, Instant expiresAt) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.device = device;
        this.user = user;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public static MqttCredential forDevice(String username, String passwordHash, Device device) {
        return new MqttCredential(username, passwordHash, device, null, null);
    }

    public static MqttCredential forDispatcherSession(
        String username,
        String passwordHash,
        User user,
        Instant expiresAt
    ) {
        return new MqttCredential(username, passwordHash, null, user, expiresAt);
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Device getDevice() {
        return device;
    }

    public User getUser() {
        return user;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void revoke() {
        this.revokedAt = Instant.now();
    }
}
