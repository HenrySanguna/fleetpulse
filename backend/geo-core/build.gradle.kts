import dev.fleetpulse.buildlogic.VerifyNoForbiddenDependenciesTask

plugins {
    `java-library`
    jacoco
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val verifyNoSpringOrJpaDependencies = tasks.register<VerifyNoForbiddenDependenciesTask>("verifyNoSpringOrJpaDependencies") {
    resolvedGroups.set(
        provider {
            val compileClasspath = configurations.getByName("compileClasspath")
            val runtimeClasspath = configurations.getByName("runtimeClasspath")
            (compileClasspath.incoming.resolutionResult.allComponents +
                runtimeClasspath.incoming.resolutionResult.allComponents)
                .mapNotNull { it.moduleVersion?.group }
        }
    )
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "1.0".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyNoSpringOrJpaDependencies, tasks.jacocoTestCoverageVerification)
}
