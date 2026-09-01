package dev.fleetpulse.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DependencyDirectionGuardTest {

    @Test
    fun `flags api depending on processor`() {
        assertTrue(DependencyDirectionGuard.violates("api", "processor"))
    }

    @Test
    fun `flags processor depending on api`() {
        assertTrue(DependencyDirectionGuard.violates("processor", "api"))
    }

    @Test
    fun `allows api depending on domain`() {
        assertFalse(DependencyDirectionGuard.violates("api", "domain"))
    }

    @Test
    fun `allows api depending on geo-core`() {
        assertFalse(DependencyDirectionGuard.violates("api", "geo-core"))
    }

    @Test
    fun `allows processor depending on domain and geo-core`() {
        assertFalse(DependencyDirectionGuard.violates("processor", "domain"))
        assertFalse(DependencyDirectionGuard.violates("processor", "geo-core"))
    }

    @Test
    fun `collects violations from a mixed edge set`() {
        val violations = DependencyDirectionGuard.findViolations(
            listOf(
                "api" to "domain",
                "api" to "geo-core",
                "processor" to "api",
            )
        )
        assertEquals(listOf("processor" to "api"), violations)
    }
}
