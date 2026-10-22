package com.humanmesh.android

import org.junit.Assert.assertFalse
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
}

    @Test
    fun skillMatcherIsCaseInsensitiveAndIgnoresDuplicates() {
        val score = com.humanmesh.android.data.SkillMatcher.score(
            requiredSkills = listOf("Kotlin", "Android", "Kotlin"),
            userSkills = listOf("kotlin", "ANDROID", "Kotlin")
        )
        assertTrue(score == 100)
    }

    @Test
    fun skillMatcherReturnsZeroForNoOverlapAndNullForNoRequirements() {
        assertTrue(com.humanmesh.android.data.SkillMatcher.score(listOf("Design"), listOf("Kotlin")) == 0)
        assertTrue(com.humanmesh.android.data.SkillMatcher.score(emptyList(), listOf("Kotlin")) == null)
    }
}
