package dev.fleetpulse.processor.presence;

// Own exception type, not a reuse of telemetry's MalformedTelemetryPayloadException:
// presence is a logically independent topic (tasks.md, WU8), and
// PresenceMessageListener must be able to catch presence-specific parse
// failures without accidentally also catching an unrelated telemetry failure
// that leaked in from a shared type.
public class MalformedPresencePayloadException extends RuntimeException {

    public MalformedPresencePayloadException(String message) {
        super(message);
    }

    public MalformedPresencePayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
