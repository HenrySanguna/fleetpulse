package dev.fleetpulse.buildlogic

object DependencyGuards {

    private val forbiddenGroupPrefixes = listOf(
        "org.springframework",
        "org.hibernate",
        "jakarta.persistence",
    )

    fun isForbidden(group: String?): Boolean =
        group != null && forbiddenGroupPrefixes.any { group == it || group.startsWith("$it.") }

    fun findForbidden(groups: Collection<String?>): List<String> =
        groups.filterNotNull().filter(::isForbidden).distinct().sorted()
}
