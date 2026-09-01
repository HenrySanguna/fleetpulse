package dev.fleetpulse.buildlogic

object DependencyDirectionGuard {

    private val forbiddenEdges = setOf(
        "api" to "processor",
        "processor" to "api",
    )

    fun violates(fromModule: String, toModule: String): Boolean =
        (fromModule to toModule) in forbiddenEdges

    fun findViolations(projectDependencyEdges: Collection<Pair<String, String>>): List<Pair<String, String>> =
        projectDependencyEdges.filter { (from, to) -> violates(from, to) }
}
