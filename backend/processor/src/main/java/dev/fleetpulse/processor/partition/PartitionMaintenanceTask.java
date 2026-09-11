package dev.fleetpulse.processor.partition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Task 1.5: keeps pg_partman's partition horizon moving forward for
// `positions` for as long as `processor` keeps running. Migration V6's
// create_parent() call only pre-creates partitions once, at migration
// time; without this recurring call the DoD invariant ("las particiones de
// la semana siguiente existen antes de que empiece esa semana") would
// eventually go stale as real weeks keep passing after that one-time setup.
//
// Invoked explicitly, never left to pg_partman's own background worker:
// Neon suspends compute on idle and that worker does not run during the
// suspension (design.md, project.md "Riesgos conocidos del stack").
@Component
public class PartitionMaintenanceTask {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceTask.class);

    private final JdbcTemplate jdbcTemplate;

    public PartitionMaintenanceTask(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void maintainPartitions() {
        log.info("Running pg_partman maintenance for positions partitions");
        jdbcTemplate.execute("CALL partman.run_maintenance_proc()");
    }
}
