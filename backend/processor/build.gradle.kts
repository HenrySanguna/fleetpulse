plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":geo-core"))
    implementation("org.springframework.boot:spring-boot-starter")
}
