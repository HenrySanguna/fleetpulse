package dev.fleetpulse.processor.telemetry;

import java.util.List;

// Small port task 3.1's buffer flushes through, so the buffer's own
// size/time trigger-timing tests (TelemetryPositionBufferTest) can
// substitute a plain in-memory fake instead of needing a real database just
// to prove flush logic. JdbcTelemetryPositionWriter (task 3.2) is the only
// production implementation.
public interface TelemetryPositionWriter {

    void writeBatch(List<TelemetryMessage> messages);
}
