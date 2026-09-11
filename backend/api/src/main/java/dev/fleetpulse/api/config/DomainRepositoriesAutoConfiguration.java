package dev.fleetpulse.api.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import javax.sql.DataSource;

// UserRepository/OrganizationRepository/etc. (02-add-fleet-auth) live in the
// domain module's dev.fleetpulse.domain package, outside
// @SpringBootApplication's implicit component scan (dev.fleetpulse.api and
// below). Registered as a real auto-configuration -- not a plain
// @EnableJpaRepositories directly on ApiApplication -- specifically so it
// stays correctly inert, the same way Spring Boot's own
// DataJpaRepositoriesAutoConfiguration does, in deployment profiles that
// exclude the DataSource/Hibernate stack entirely (see
// ActuatorInfoEndpointTest / OpenApiDocumentPublicationTest, pre-existing
// before this change: metadata endpoints must keep working during a DB
// outage). A plain @EnableJpaRepositories on ApiApplication has no such
// condition and would force a real EntityManagerFactory to exist in every
// profile.
@AutoConfiguration(after = HibernateJpaAutoConfiguration.class)
@ConditionalOnBean(DataSource.class)
@EnableJpaRepositories("dev.fleetpulse.domain")
public class DomainRepositoriesAutoConfiguration {
}
