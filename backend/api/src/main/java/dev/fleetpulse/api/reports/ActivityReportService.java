package dev.fleetpulse.api.reports;

import dev.fleetpulse.api.config.FleetpulseMotionDetectionProperties;
import dev.fleetpulse.domain.Vehicle;
import dev.fleetpulse.domain.VehicleRepository;
import dev.fleetpulse.geocore.MotionConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

// Task 4.3: org-scoping (matching VehicleTrackService/VehicleDestinationService's
// own precedent) before ever touching vehicle_daily/trips. A vehicle in
// another organization is hidden as 404, same cross-org-leak precedent every
// other org-scoped endpoint in this codebase already follows.
@Service
public class ActivityReportService {

    private final ObjectProvider<VehicleRepository> vehicles;
    private final JdbcActivityReportRepository repository;
    private final InProgressTripJdbcReader inProgressTripReader;
    private final MotionConfig motionConfig;

    ActivityReportService(
            ObjectProvider<VehicleRepository> vehicles,
            JdbcActivityReportRepository repository,
            InProgressTripJdbcReader inProgressTripReader,
            FleetpulseMotionDetectionProperties motionProperties) {
        this.vehicles = vehicles;
        this.repository = repository;
        this.inProgressTripReader = inProgressTripReader;
        this.motionConfig = new MotionConfig(motionProperties.stopThresholdKmh(), motionProperties.startThresholdKmh(), motionProperties.minStableDuration());
    }

    public ActivityReportResponse report(UUID vehicleId, UUID organizationId, Instant from, Instant to) {
        Vehicle vehicle = vehicleInOwnOrgOrThrow(vehicleId, organizationId);

        // vehicle_daily.day is a plain UTC calendar date (V13) -- converting
        // the requested instant range to UTC LocalDates here, once, keeps
        // that same "no per-organization timezone concept" simplification
        // ETA/rollups already established, rather than inventing a
        // per-request timezone parameter design.md never asked for.
        LocalDate fromDay = LocalDate.ofInstant(from, ZoneOffset.UTC);
        LocalDate toDay = LocalDate.ofInstant(to, ZoneOffset.UTC);

        List<JdbcActivityReportRepository.DailyRollupRow> daily = repository.findDaily(organizationId, vehicle.getId(), fromDay, toDay);
        List<ActivityTripResponse> trips = repository.findTrips(organizationId, vehicle.getId(), from, to);
        ActivityInProgressTripResponse inProgressTrip = computeInProgressTrip(vehicle.getId(), organizationId, from, toDay);

        return new ActivityReportResponse(vehicle.getId(), summarize(daily), dailyDistances(daily), trips, inProgressTrip);
    }

    // Task 10: only when the requested range's own `to` day reaches today
    // (UTC, same calendar the rest of this report already uses) -- a range
    // that ends before today had its trailing span resolved long ago, so it
    // is either already a row in `trips` or genuinely never happened.
    // Comparing calendar days rather than the raw `to` instant matters in
    // practice: the console's own "last 7 days" range sends `to = now()`
    // captured client-side, which is always a little earlier than the
    // server's own Instant.now() by request time -- an exact instant
    // comparison would spuriously treat every "today" request as already
    // past.
    private ActivityInProgressTripResponse computeInProgressTrip(UUID vehicleId, UUID organizationId, Instant from, LocalDate toDay) {
        if (toDay.isBefore(LocalDate.now(ZoneOffset.UTC))) {
            return null;
        }

        Instant now = Instant.now();
        Instant lastClosedTripEndedAt = inProgressTripReader.lastClosedTripEndedAt(vehicleId);
        boolean clippedToWindowStart = from.isAfter(lastClosedTripEndedAt);
        Instant windowStart = clippedToWindowStart ? from : lastClosedTripEndedAt;

        List<InProgressTripJdbcReader.MotionPositionSample> positions = inProgressTripReader.positionsSince(vehicleId, windowStart, now);
        Duration stopThreshold = inProgressTripReader.stopThreshold(organizationId);

        return InProgressTripCalculator
            .calculate(positions, windowStart, clippedToWindowStart, stopThreshold, motionConfig)
            .orElse(null);
    }

    private static ActivityReportSummaryResponse summarize(List<JdbcActivityReportRepository.DailyRollupRow> daily) {
        double totalDistanceKm = 0.0;
        int totalMovingSecs = 0;
        int totalIdleSecs = 0;
        Double maxSpeedKmh = null;
        for (JdbcActivityReportRepository.DailyRollupRow row : daily) {
            totalDistanceKm += row.distanceKm();
            totalMovingSecs += row.movingSecs();
            totalIdleSecs += row.idleSecs();
            if (row.maxSpeedKmh() != null && (maxSpeedKmh == null || row.maxSpeedKmh() > maxSpeedKmh)) {
                maxSpeedKmh = row.maxSpeedKmh().doubleValue();
            }
        }
        int accountedSecs = totalMovingSecs + totalIdleSecs;
        // Same distance/accounted-time formula TripSegmenter.addTrip() uses
        // for a single trip's own avg_speed_kmh, applied here across every
        // daily row in the range instead of one trip's span.
        double avgSpeedKmh = accountedSecs > 0 ? totalDistanceKm / (accountedSecs / 3600.0) : 0.0;
        return new ActivityReportSummaryResponse(totalDistanceKm, totalMovingSecs / 60, totalIdleSecs / 60, avgSpeedKmh, maxSpeedKmh);
    }

    private static List<DailyDistancePointResponse> dailyDistances(List<JdbcActivityReportRepository.DailyRollupRow> daily) {
        return daily.stream().map(row -> new DailyDistancePointResponse(row.day(), row.distanceKm())).toList();
    }

    private Vehicle vehicleInOwnOrgOrThrow(UUID vehicleId, UUID organizationId) {
        Vehicle vehicle = vehicles.getObject().findById(vehicleId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!vehicle.getOrganization().getId().equals(organizationId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return vehicle;
    }
}
