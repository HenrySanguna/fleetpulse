package dev.fleetpulse.processor.telemetry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Tasks 3.1 (dual size/time flush trigger) and 3.3 (flush on shutdown),
// proven against a plain in-memory FakeTelemetryPositionWriter so this stays
// a fast, deterministic unit test of the buffer's own trigger/lifecycle
// logic. TelemetryBatchWriteTest separately proves the real
// batchUpdate/ON CONFLICT behavior (task 3.2) against a real database, and
// wires the filter+buffer+writer together for tests 6.5/6.10.
class TelemetryPositionBufferTest {

    private static final UUID VEHICLE_ID = UUID.randomUUID();

    private FakeTelemetryPositionWriter writer;
    private TelemetryPositionBuffer buffer;

    @AfterEach
    void stopBuffer() {
        if (buffer != null) {
            buffer.stop();
        }
    }

    @Test
    void flushesAsSoonAsTheBufferReachesMaxSize() {
        writer = new FakeTelemetryPositionWriter();
        buffer = new TelemetryPositionBuffer(3, Duration.ofMinutes(10), writer);
        buffer.start();

        buffer.add(message(1));
        buffer.add(message(2));
        assertThat(writer.flushedBatches()).isEmpty();

        buffer.add(message(3));

        assertThat(writer.flushedBatches()).hasSize(1);
        assertThat(writer.flushedBatches().get(0)).hasSize(3);
    }

    @Test
    void flushesOnceTheFlushIntervalElapsesEvenBelowMaxSize() {
        writer = new FakeTelemetryPositionWriter();
        buffer = new TelemetryPositionBuffer(1000, Duration.ofMillis(50), writer);
        buffer.start();

        buffer.add(message(1));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(writer.flushedBatches()).hasSize(1));
        assertThat(writer.flushedBatches().get(0)).hasSize(1);
    }

    @Test
    void bufferIsClearedAfterEachFlush() {
        writer = new FakeTelemetryPositionWriter();
        buffer = new TelemetryPositionBuffer(1, Duration.ofMinutes(10), writer);
        buffer.start();

        buffer.add(message(1));
        buffer.add(message(2));

        assertThat(writer.flushedBatches()).hasSize(2);
        assertThat(writer.flushedBatches()).allSatisfy(batch -> assertThat(batch).hasSize(1));
    }

    @Test
    void orderlyShutdownFlushesAnyPendingBufferedPositions() {
        writer = new FakeTelemetryPositionWriter();
        buffer = new TelemetryPositionBuffer(1000, Duration.ofMinutes(10), writer);
        buffer.start();

        buffer.add(message(1));
        buffer.add(message(2));
        assertThat(writer.flushedBatches()).isEmpty();

        buffer.stop();

        assertThat(writer.flushedBatches()).hasSize(1);
        assertThat(writer.flushedBatches().get(0)).hasSize(2);
    }

    @Test
    void stoppingAnAlreadyEmptyBufferFlushesNothing() {
        writer = new FakeTelemetryPositionWriter();
        buffer = new TelemetryPositionBuffer(1000, Duration.ofMinutes(10), writer);
        buffer.start();

        buffer.stop();

        assertThat(writer.flushedBatches()).isEmpty();
    }

    private static TelemetryMessage message(int sequence) {
        return new TelemetryMessage(
            VEHICLE_ID, Instant.parse("2026-09-11T10:00:00Z").plusSeconds(sequence), 40.4, -3.7, null, null, null
        );
    }

    private static final class FakeTelemetryPositionWriter implements TelemetryPositionWriter {
        private final List<List<TelemetryMessage>> flushedBatches = new CopyOnWriteArrayList<>();

        @Override
        public void writeBatch(List<TelemetryMessage> messages) {
            flushedBatches.add(new ArrayList<>(messages));
        }

        List<List<TelemetryMessage>> flushedBatches() {
            return flushedBatches;
        }
    }
}
