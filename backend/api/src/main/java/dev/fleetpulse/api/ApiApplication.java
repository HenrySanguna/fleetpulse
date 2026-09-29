package dev.fleetpulse.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

// @SpringBootApplication's implicit component scan only covers dev.fleetpulse.api
// and below; JPA entities live in the domain module's dev.fleetpulse.domain
// package (02-add-fleet-auth, task 2.1: the first time this module needs
// them directly). @EntityScan is unconditional metadata collection, safe
// even when no DataSource exists. Repository scanning (UserRepository etc.)
// is registered separately in dev.fleetpulse.api.config.DomainRepositoriesAutoConfiguration,
// conditionally on a DataSource actually being present -- see that class for why.
// @EnableScheduling backs ExpiredMqttCredentialPurgeTask (task 3.3), api
// module's first @Scheduled component.
@SpringBootApplication
@ConfigurationPropertiesScan
@EntityScan("dev.fleetpulse.domain")
@EnableScheduling
public class ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }

    // QA follow-up F6: injectable so ActivityReportService's "is the
    // requested day today (UTC)" gate can be pinned to a fixed instant in
    // tests instead of depending on the real wall clock. systemUTC(), not
    // the platform default zone, matches the UTC calendar day
    // vehicle_daily.day/ActivityReportService already use everywhere else.
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
