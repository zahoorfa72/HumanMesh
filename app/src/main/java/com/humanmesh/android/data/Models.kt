package com.humanmesh.android.data

import org.json.JSONArray
import org.json.JSONObject

data class Goal(
    val id: String,
    val ownerId: String,
    val title: String,
    val description: String,
    val category: String,
    val requiredSkills: List<String>,
    val status: String
) {
    companion object {
        fun from(json: JSONObject) = Goal(
            id = json.optString("id"),
            ownerId = json.optString("owner_id"),
            title = json.optString("title", "Untitled goal"),
            description = json.optString("description"),
            category = json.optString("category", "General"),
            requiredSkills = json.optJSONArray("required_skills").toStringList(),
            status = json.optString("status", "active")
        )
    }
}

data class OpportunityRoom(
    val id: String,
    val ownerId: String,
    val title: String,
    val description: String,
    val topic: String
) {
    companion object {
        fun from(json: JSONObject) = OpportunityRoom(
            id = json.optString("id"),
            ownerId = json.optString("owner_id"),
            title = json.optString("title", "Opportunity room"),
            description = json.optString("description"),
            topic = json.optString("topic", "Collaboration")
        )
    }
}

data class RoomMessage(
    val id: String,
    val senderId: String,
    val body: String,
    val createdAt: String
) {
    companion object {
        fun from(json: JSONObject) = RoomMessage(
            id = json.optString("id"),
            senderId = json.optString("sender_id"),
            body = json.optString("body"),
            createdAt = json.optString("created_at")
        )
    }
}

data class UserProfile(
    val id: String,
    val displayName: String,
    val username: String,
    val bio: String,
    val skills: List<String>
) {
    companion object {
        fun from(json: JSONObject) = UserProfile(
            id = json.optString("id"),
            displayName = json.optString("display_name"),
            username = json.optString("username"),
            bio = json.optString("bio"),
            skills = json.optJSONArray("skills").toStringList()
        )
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> optString(i).takeIf(String::isNotBlank) }
}

data class MatchRequest(
    val id: String,
    val senderId: String,
    val receiverId: String,
    val goalId: String,
    val message: String,
    val status: String
) {
    companion object {
        fun from(json: JSONObject) = MatchRequest(
            id = json.optString("id"),
            senderId = json.optString("sender_id"),
            receiverId = json.optString("receiver_id"),
            goalId = json.optString("goal_id"),
            message = json.optString("message"),
            status = json.optString("status", "pending")
        )
    }
}
