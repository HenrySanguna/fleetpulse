import dev.fleetpulse.buildlogic.VerifyBackendDependencyDirectionTask
import org.gradle.api.artifacts.ProjectDependency

plugins {
    id("dev.nx.gradle.project-graph") version ("0.1.20")
    id("org.springframework.boot") version "4.1.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    base
}

allprojects {
    apply {
        plugin("dev.nx.gradle.project-graph")
    }

    group = "dev.fleetpulse"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

extra["springBootVersion"] = "4.1.1"

subprojects {
    apply(plugin = "java")

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

val verifyBackendDependencyDirection = tasks.register<VerifyBackendDependencyDirectionTask>("verifyBackendDependencyDirection") {
    projectDependencyEdges.set(
        provider {
            subprojects.flatMap { sub ->
                sub.configurations.flatMap { configuration ->
                    configuration.dependencies
                        .withType<ProjectDependency>()
                        .map { dependency -> "${sub.name}->${dependency.path.removePrefix(":")}" }
                }
            }
        }
    )
}

tasks.named("check") {
    dependsOn(verifyBackendDependencyDirection)
}
