package dev.fleetpulse.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class VerifyBackendDependencyDirectionTask : DefaultTask() {

    @get:Input
    abstract val projectDependencyEdges: ListProperty<String>

    @TaskAction
    fun verify() {
        val edges = projectDependencyEdges.get().map { edge ->
            val (from, to) = edge.split("->")
            from to to
        }
        val violations = DependencyDirectionGuard.findViolations(edges)
        if (violations.isNotEmpty()) {
            val rendered = violations.joinToString(", ") { (from, to) -> "$from -> $to" }
            throw GradleException(
                "api and processor must never depend on each other. Forbidden dependency edge(s) found: $rendered"
            )
        }
    }
}
