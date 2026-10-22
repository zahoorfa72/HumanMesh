package com.humanmesh.android.data

/**
 * Transparent baseline matching: exact case-insensitive skill overlap.
 * This is intentionally not marketed as AI and never uploads profile skills
 * to a third-party model. A server-side matcher can replace it later.
 */
object SkillMatcher {
    fun score(requiredSkills: List<String>, userSkills: List<String>): Int? {
        val required = requiredSkills.map(::normalize).filter(String::isNotBlank).toSet()
        if (required.isEmpty()) return null
        val available = userSkills.map(::normalize).filter(String::isNotBlank).toSet()
        if (available.isEmpty()) return 0
        val overlap = required.count { it in available }
        return (overlap * 100.0 / required.size).toInt()
    }

    private fun normalize(skill: String): String = skill.trim().lowercase()
}
