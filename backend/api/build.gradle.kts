plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":geo-core"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
