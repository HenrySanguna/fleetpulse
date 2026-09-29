package dev.fleetpulse.processor.telemetry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

// In-memory record of positions persisted by this process, so the scheduled
// trip and rollup tasks can tell -- without querying the database -- whether
// anything could have changed since their last run (the database is Neon,
// which only scales compute to zero after ~5 minutes without any activity).
//
// `pendingBound` is an upper bound on the recorded_at of every position that
// may exist in the database. Until a task seeds it from the database (its
// mandatory first run), the bound is the process start instant, i.e. the
// caller must assume rows written by a previous incarnation may be as recent
// as startup. Positions this process persists raise it monotonically.
@Component
public class TelemetryActivity {

    public record Snapshot(long version, Instant pendingBound) {
    }

    private long version = 0;
    private Instant liveLatest = Instant.EPOCH;
    private Instant preexistingBound;
    private boolean seeded = false;

    @Autowired
    public TelemetryActivity() {
        this(Clock.systemUTC());
    }

    public TelemetryActivity(Clock clock) {
        this.preexistingBound = clock.instant();
    }

    // Called before the rows are written: over-reporting only causes an extra
    // run, whereas a failed or partially applied batch must never be missed.
    public synchronized void recordPersisted(Instant latestRecordedAt) {
        version++;
        if (latestRecordedAt.isAfter(liveLatest)) {
            liveLatest = latestRecordedAt;
        }
    }

    // Replaces the startup assumption with the real latest recorded_at found
    // in the database (Instant.EPOCH when there are none). Only the first
    // seed counts; positions written afterwards are tracked by
    // recordPersisted().
    public synchronized void seedFromDatabase(Instant latestPersistedRecordedAt) {
        if (seeded) {
            return;
        }
        seeded = true;
        preexistingBound = latestPersistedRecordedAt;
    }

    public synchronized Snapshot snapshot() {
        Instant bound = liveLatest.isAfter(preexistingBound) ? liveLatest : preexistingBound;
        return new Snapshot(version, bound);
    }
}
