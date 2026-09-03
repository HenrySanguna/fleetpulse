plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.gorylenko.gradle-git-properties")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":geo-core"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Broker connectivity health indicator (5.1) and the processor heartbeat
    // read (5.3) both go through Spring Integration's Paho client factory.
    implementation("org.springframework.integration:spring-integration-mqtt")
    // Optional dependency of spring-integration-mqtt; Gradle does not resolve
    // Maven "optional" dependencies transitively, so it is declared explicitly.
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    // Publishes the OpenAPI document at /v3/api-docs; libs/api-client is generated
    // from it (see openspec change 00-bootstrap-monorepo, section 4).
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    // The TestRestTemplate bean is built on a RestTemplateBuilder backed by an
    // httpclient5 request factory; none of these are pulled in transitively by
    // spring-boot-starter-webmvc-test alone.
    testImplementation("org.springframework.boot:spring-boot-restclient")
    testImplementation("org.springframework.boot:spring-boot-http-client")
    testImplementation("org.apache.httpcomponents.client5:httpclient5")
    // Spring Boot 4.1.1 targets testcontainers.version=2.0.5 via a nested BOM
    // import that Gradle's plain platform() import does not propagate here.
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

springBoot {
    buildInfo()
}
