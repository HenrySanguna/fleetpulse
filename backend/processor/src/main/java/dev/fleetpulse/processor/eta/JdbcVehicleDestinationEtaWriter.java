package dev.fleetpulse.processor.eta;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

// Task 2.4: the live write path's own half of vehicle_destinations (V11) --
// updates ONLY the eta_* columns this writer owns (see V11's own migration
// comment for the "two different writers, same row" shape this mirrors from
// vehicle_state.online). Never touches destination/assigned_at, which belong
// exclusively to VehicleDestinationController/Service (api module).
@Component
public class JdbcVehicleDestinationEtaWriter {

    private static final String UPDATE_ETA_SQL = """
        UPDATE vehicle_destinations
        SET eta_seconds = ?, eta_margin_seconds = ?, eta_calculated_at = ?
        WHERE vehicle_id = ?
        """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcVehicleDestinationEtaWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void updateEta(UUID vehicleId, EtaEstimate estimate, Instant calculatedAt) {
        jdbcTemplate.update(
            UPDATE_ETA_SQL,
            (int) estimate.etaSeconds(), (int) estimate.marginSeconds(), Timestamp.from(calculatedAt), vehicleId
        );
    }
}
