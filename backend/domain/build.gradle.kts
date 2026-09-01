plugins {
    `java-library`
}

val springBootVersion = rootProject.extra["springBootVersion"] as String

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4.1.1 targets testcontainers.version=2.0.5 via a nested BOM
    // import that Gradle's plain platform() import does not propagate here.
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
