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

    fun reasons(requiredSkills: List<String>, userSkills: List<String>): List<String> {
        val required = requiredSkills.map(::normalize).filter(String::isNotBlank).distinct()
        val available = userSkills.map(::normalize).filter(String::isNotBlank).toSet()
        if (required.isEmpty()) return listOf("No specific skills requested yet")
        val matched = required.filter { it in available }
        val missing = required.filterNot { it in available }
        return buildList {
            if (matched.isNotEmpty()) add("Matches: " + matched.take(4).joinToString(", "))
            if (missing.isNotEmpty()) add("Still needed: " + missing.take(4).joinToString(", "))
            if (matched.isEmpty() && available.isNotEmpty()) add("Your listed skills don't overlap yet; you can still connect if you bring another contribution")
            if (available.isEmpty()) add("Add your skills to your profile for more relevant matches")
        }
    }

    private fun normalize(skill: String): String = skill.trim().lowercase()
}
