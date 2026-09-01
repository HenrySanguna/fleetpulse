package dev.fleetpulse.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class VerifyNoForbiddenDependenciesTask : DefaultTask() {

    @get:Input
    abstract val resolvedGroups: ListProperty<String>

    @TaskAction
    fun verify() {
        val forbidden = DependencyGuards.findForbidden(resolvedGroups.get())
        if (forbidden.isNotEmpty()) {
            throw GradleException(
                "geo-core must never depend on Spring or JPA/Hibernate. " +
                    "Forbidden dependency group(s) found on the classpath: ${forbidden.joinToString(", ")}"
            )
        }
    }
}
