package com.humanmesh.android

import com.humanmesh.android.data.SkillMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BasicValidationTest {
    @Test
    fun uuidValidationAcceptsOnlyUuidShape() {
        val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        assertTrue(uuid.matches("123e4567-e89b-12d3-a456-426614174000"))
        assertFalse(uuid.matches("me"))
        assertFalse(uuid.matches("123e4567-e89b-12d3-a456-42661417400"))
    }

    @Test
    fun skillMatcherIsCaseInsensitiveAndIgnoresDuplicates() {
        assertEquals(
            100,
            SkillMatcher.score(
                requiredSkills = listOf("Kotlin", "Android", "Kotlin"),
                userSkills = listOf("kotlin", "ANDROID", "Kotlin")
            )
        )
    }

    @Test
    fun skillMatcherReturnsZeroForNoOverlapAndNullForNoRequirements() {
        assertEquals(0, SkillMatcher.score(listOf("Design"), listOf("Kotlin")))
        assertNull(SkillMatcher.score(emptyList(), listOf("Kotlin")))
    }

    @Test
    fun skillMatcherCalculatesPartialOverlap() {
        assertEquals(50, SkillMatcher.score(listOf("Kotlin", "Android"), listOf("Kotlin", "UX")))
    }
}
