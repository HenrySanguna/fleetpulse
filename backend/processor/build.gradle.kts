plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.gorylenko.gradle-git-properties")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":geo-core"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Broker connectivity health indicator (5.1) and the periodic retained
    // heartbeat publish (5.3) both go through Spring Integration's Paho client factory.
    implementation("org.springframework.integration:spring-integration-mqtt")
    // Optional dependency of spring-integration-mqtt; Gradle does not resolve
    // Maven "optional" dependencies transitively, so it is declared explicitly.
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4.1.1 targets testcontainers.version=2.0.5 via a nested BOM
    // import that Gradle's plain platform() import does not propagate here.
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    // PartitionMaintenanceTaskTest (task 1.5) needs a real PostGIS+pg_partman
    // container, same as domain's own Testcontainers-based schema tests;
    // domain declares this as testImplementation, which never propagates.
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

springBoot {
    buildInfo()
}

// Task 5.3: the dev-only multi-vehicle device simulator has its own main()
// (DeviceSimulatorMain), deliberately NOT the Spring Boot application --
// this task points JavaExec at it directly instead of going through
// bootRun/ProcessorApplication, keeping it out of the production processor
// runtime path. Configure via SIMULATOR_* environment variables.
tasks.register<JavaExec>("runDeviceSimulator") {
    group = "application"
    description = "Runs the dev-only multi-vehicle MQTT device simulator (task 5.3)."
    mainClass.set("dev.fleetpulse.processor.simulator.DeviceSimulatorMain")
    classpath = sourceSets["main"].runtimeClasspath
}
