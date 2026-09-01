import dev.fleetpulse.buildlogic.VerifyNoForbiddenDependenciesTask

plugins {
    `java-library`
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

tasks.named("check") {
    dependsOn(verifyNoSpringOrJpaDependencies)
}
