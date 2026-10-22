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
