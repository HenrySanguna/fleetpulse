package dev.fleetpulse.domain;

import org.springframework.boot.autoconfigure.SpringBootApplication;

// Test-only Spring Boot bootstrap: the domain module is a library (JPA
// entities, repositories, Flyway migrations) with no main application class
// of its own. @DataJpaTest needs one @SpringBootConfiguration on the test
// classpath to anchor component scanning and auto-configuration.
@SpringBootApplication
class DomainTestApplication {
}
