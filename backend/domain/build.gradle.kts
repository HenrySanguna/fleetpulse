plugins {
    `java-library`
}

val springBootVersion = rootProject.extra["springBootVersion"] as String

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    api("org.springframework.boot:spring-boot-starter-data-jpa")
}
