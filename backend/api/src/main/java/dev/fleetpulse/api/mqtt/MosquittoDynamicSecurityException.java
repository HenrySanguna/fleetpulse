package dev.fleetpulse.api.mqtt;

// Raised when a $CONTROL/dynamic-security/v1 command fails: either the
// broker itself rejected it (its "error" response field), or the round trip
// could not complete (connect/publish failure, response timeout).
public class MosquittoDynamicSecurityException extends RuntimeException {

    public MosquittoDynamicSecurityException(String command, String message) {
        super("Mosquitto dynamic-security command '" + command + "' failed: " + message);
    }

    public MosquittoDynamicSecurityException(String command, String message, Throwable cause) {
        super("Mosquitto dynamic-security command '" + command + "' failed: " + message, cause);
    }
}
