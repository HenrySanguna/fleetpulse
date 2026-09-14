package dev.fleetpulse.processor.telemetry;

import dev.fleetpulse.processor.config.FleetpulseTelemetryBufferProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// Tasks 3.1/3.3: accumulates plausible positions and flushes them to
// `writer` on whichever of two triggers happens first -- the buffer reaches
// `maxSize` rows (checked synchronously inside add(), so that flush happens
// on the same call that filled the buffer) or `flushInterval` elapses (a
// dedicated single-thread scheduler, so a quiet period still drains a
// partially-filled buffer). SmartLifecycle.stop() flushes whatever is still
// pending and stops the scheduler -- the "apagado ordenado descarga el
// buffer pendiente" contract (design.md, test 6.10). SmartLifecycle rather
// than a bare @PreDestroy because this component also owns a background
// scheduled task that must stop cleanly alongside the flush, and
// SmartLifecycle is Spring's own idiom for a component with real start/stop
// state -- the same lifecycle contract Spring Integration's own channel
// adapters use elsewhere in this module.
@Component
public class TelemetryPositionBuffer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TelemetryPositionBuffer.class);

    private final int maxSize;
    private final Duration flushInterval;
    private final TelemetryPositionWriter writer;
    private final List<TelemetryMessage> pending = new ArrayList<>();
    private final Object lock = new Object();

    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    @Autowired
    public TelemetryPositionBuffer(FleetpulseTelemetryBufferProperties properties, TelemetryPositionWriter writer) {
        this(properties.maxSize(), properties.flushInterval(), writer);
    }

    TelemetryPositionBuffer(int maxSize, Duration flushInterval, TelemetryPositionWriter writer) {
        this.maxSize = maxSize;
        this.flushInterval = flushInterval;
        this.writer = writer;
    }

    public void add(TelemetryMessage message) {
        List<TelemetryMessage> toFlush = null;
        synchronized (lock) {
            pending.add(message);
            if (pending.size() >= maxSize) {
                toFlush = drainLocked();
            }
        }
        if (toFlush != null) {
            flush(toFlush);
        }
    }

    @Override
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "telemetry-position-buffer-flush");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = flushInterval.toMillis();
        scheduler.scheduleAtFixedRate(this::flushIfNonEmpty, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        running = true;
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        flushIfNonEmpty();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void flushIfNonEmpty() {
        List<TelemetryMessage> toFlush;
        synchronized (lock) {
            if (pending.isEmpty()) {
                return;
            }
            toFlush = drainLocked();
        }
        flush(toFlush);
    }

    private List<TelemetryMessage> drainLocked() {
        List<TelemetryMessage> drained = List.copyOf(pending);
        pending.clear();
        return drained;
    }

    private void flush(List<TelemetryMessage> batch) {
        log.debug("Flushing {} buffered telemetry positions", batch.size());
        writer.writeBatch(batch);
    }
}
