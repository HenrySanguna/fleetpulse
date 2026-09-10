package dev.fleetpulse.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// @SpringBootApplication's implicit component scan only covers dev.fleetpulse.api
// and below; JPA entities live in the domain module's dev.fleetpulse.domain
// package (02-add-fleet-auth, task 2.1: the first time this module needs
// them directly). @EntityScan is unconditional metadata collection, safe
// even when no DataSource exists. Repository scanning (UserRepository etc.)
// is registered separately in dev.fleetpulse.api.config.DomainRepositoriesAutoConfiguration,
// conditionally on a DataSource actually being present -- see that class for why.
@SpringBootApplication
@ConfigurationPropertiesScan
@EntityScan("dev.fleetpulse.domain")
public class ApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
