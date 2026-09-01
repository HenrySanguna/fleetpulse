package dev.fleetpulse.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DependencyGuardsTest {

    @Test
    fun `flags spring framework group as forbidden`() {
        assertTrue(DependencyGuards.isForbidden("org.springframework.boot"))
    }

    @Test
    fun `flags hibernate group as forbidden`() {
        assertTrue(DependencyGuards.isForbidden("org.hibernate.orm"))
    }

    @Test
    fun `flags jakarta persistence group as forbidden`() {
        assertTrue(DependencyGuards.isForbidden("jakarta.persistence"))
    }

    @Test
    fun `allows unrelated groups`() {
        assertFalse(DependencyGuards.isForbidden("com.google.guava"))
        assertFalse(DependencyGuards.isForbidden(null))
    }

    @Test
    fun `does not flag groups that merely start with the same prefix text`() {
        assertFalse(DependencyGuards.isForbidden("org.springframework2.unrelated"))
    }

    @Test
    fun `collects distinct sorted forbidden groups from a mixed set`() {
        val found = DependencyGuards.findForbidden(
            listOf(
                "com.google.guava",
                "org.springframework.boot",
                "org.hibernate.orm",
                "org.springframework.boot",
            )
        )
        assertEquals(listOf("org.hibernate.orm", "org.springframework.boot"), found)
    }
}
