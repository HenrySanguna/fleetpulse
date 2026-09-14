package dev.fleetpulse.processor.telemetry;

public class MalformedTelemetryPayloadException extends RuntimeException {

    public MalformedTelemetryPayloadException(String message) {
        super(message);
    }

    public MalformedTelemetryPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
