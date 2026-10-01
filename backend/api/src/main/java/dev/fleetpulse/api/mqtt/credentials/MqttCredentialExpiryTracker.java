package dev.fleetpulse.api.mqtt.credentials;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

// In-memory lower bound on the next expiry of any browser MQTT credential, so
// ExpiredMqttCredentialPurgeTask can tell -- without querying the database --
// that a run would be a no-op (the database is Neon, which only scales compute
// to zero after ~5 minutes without any activity).
//
// Until a purge run has recomputed the bound from the database (the mandatory
// first run, or the one after a failed run) the state is unknown and the task
// must query. Issuances are tracked even while unknown so that one arriving
// during a purge run is merged into the recomputed bound rather than lost.
@Component
public class MqttCredentialExpiryTracker {

    private boolean known = false;
    private Instant earliest;
    private Instant issuedSincePurgeStart;

    // Called after the credential row has been saved: a purge run that began
    // earlier either sees the row in its recompute query or sees this call.
    public synchronized void recordIssued(Instant expiresAt) {
        earliest = min(earliest, expiresAt);
        issuedSincePurgeStart = min(issuedSincePurgeStart, expiresAt);
    }

    public synchronized boolean canSkip(Instant now) {
        return known && (earliest == null || now.isBefore(earliest));
    }

    // Leaves the state unknown until finishPurge(), so a run that throws
    // forces a query on the next tick.
    public synchronized void startPurge() {
        known = false;
        issuedSincePurgeStart = null;
    }

    public synchronized void finishPurge(Optional<Instant> remainingEarliest) {
        earliest = min(remainingEarliest.orElse(null), issuedSincePurgeStart);
        known = true;
    }

    private static Instant min(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }
}
