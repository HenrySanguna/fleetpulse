plugins {
    `java-library`
}

val springBootVersion = rootProject.extra["springBootVersion"] as String

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    // Spring Boot 4.1 split Flyway autoconfiguration into its own module,
    // separate from the raw org.flywaydb:flyway-core library it still brings
    // transitively; api() because consumers (api, processor) need their own
    // Spring context to auto-run these migrations on startup, not just this
    // module's own manual Flyway.configure() calls in its tests.
    api("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4.1 split @DataJpaTest into its own module (spring-boot-data-jpa-test),
    // which transitively brings spring-boot-jpa-test (AutoConfigureTestEntityManager) and
    // spring-boot-jdbc-test (AutoConfigureTestDatabase) via its own api() dependencies.
    testImplementation("org.springframework.boot:spring-boot-data-jpa-test")
    // Spring Boot 4.1.1 targets testcontainers.version=2.0.5 via a nested BOM
    // import that Gradle's plain platform() import does not propagate here.
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
