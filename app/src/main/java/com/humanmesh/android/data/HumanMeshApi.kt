package com.humanmesh.android.data

import com.humanmesh.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

class HumanMeshApi(private val sessions: SessionStore) {
    private val baseUrl = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY.trim()
    val isConfigured: Boolean
        get() = baseUrl.startsWith("https://") && anonKey.isNotBlank()

    fun hasVerifiedEmailMfa(accessToken: String): Boolean = runCatching {
        val tokenParts = accessToken.split(".")
        if (tokenParts.size < 2) {
            false
        } else {
            var payload = tokenParts[1].replace('-', '+').replace('_', '/')
            payload += "=".repeat((4 - payload.length % 4) % 4)
            val claims = JSONObject(String(Base64.getDecoder().decode(payload), Charsets.UTF_8))
            claims.optBoolean("humanmesh_email_mfa_verified", false)
        }
    }.getOrDefault(false)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun validatePassword(password: String) {
        require(password.length >= 6) { "Password must contain at least 6 characters." }
    }

    suspend fun signIn(email: String, password: String): UserSession {
        val result = request(
            "POST",
            "/auth/v1/token?grant_type=password",
            JSONObject().put("email", email.trim()).put("password", password),
            authenticated = false
        )
        return saveAuthResult(JSONObject(result), email, persistSession = true)
    }

    suspend fun signUp(email: String, password: String): UserSession? {
        validatePassword(password)
        val result = request(
            "POST",
            "/auth/v1/signup?redirect_to=humanmesh%3A%2F%2Fauth%2Fverify",
            JSONObject().put("email", email.trim()).put("password", password),
            authenticated = false
        )
        val json = JSONObject(result)
        val user = json.optJSONObject("user")
        val identities = user?.optJSONArray("identities")
        if (user != null && identities != null && identities.length() == 0) {
            throw IOException("This email already has a HumanMesh account. Choose Sign in or Forgot password.")
        }
        if (json.optString("access_token").isBlank()) return null
        return saveAuthResult(json, email, persistSession = true)
    }

    private fun saveAuthResult(
        json: JSONObject,
        fallbackEmail: String,
        persistSession: Boolean = true
    ): UserSession {
        val user = json.optJSONObject("user") ?: json
        val userId = user.optString("id").ifBlank { user.optString("sub") }
        val access = json.optString("access_token")
        if (userId.isBlank() || access.isBlank()) {
            throw IOException("Authentication response was incomplete. Verify the Supabase Auth settings.")
        }
        val session = UserSession(
            userId = userId,
            email = user.optString("email").ifBlank { fallbackEmail },
            accessToken = access,
            refreshToken = json.optString("refresh_token")
        )
        if (persistSession) sessions.save(session)
        return session
    }

    suspend fun sendEmailMfaCode(session: UserSession) = withContext(Dispatchers.IO) {
        postEmailMfaAction(session, JSONObject().put("action", "send"))
    }

    suspend fun verifyEmailMfaCode(session: UserSession, code: String) = withContext(Dispatchers.IO) {
        require(code.matches(Regex("^[0-9]{6}$"))) { "Enter the six-digit code from your email." }
        postEmailMfaAction(session, JSONObject().put("action", "verify").put("code", code))
    }

    suspend fun completeEmailMfa(session: UserSession): UserSession = withContext(Dispatchers.IO) {
        require(session.refreshToken.isNotBlank()) { "Sign-in session expired. Please sign in again." }
        val request = Request.Builder()
            .url("$baseUrl/auth/v1/token?grant_type=refresh_token")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .post(JSONObject().put("refresh_token", session.refreshToken).toString().toRequestBody(jsonType))
            .build()
        val result = http.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(userMessage(responseText, response.code))
            JSONObject(responseText)
        }
        val verified = saveAuthResult(result, session.email, persistSession = true)
        if (!hasVerifiedEmailMfa(verified.accessToken)) {
            sessions.clear()
            throw IOException("Email two-step verification is not enabled on the Supabase backend yet. Complete the HumanMesh email MFA setup first.")
        }
        verified
    }

    private fun postEmailMfaAction(session: UserSession, body: JSONObject) {
        if (!isConfigured) throw IOException("Backend is not configured.")
        val request = Request.Builder()
            .url("$baseUrl/functions/v1/email-mfa")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer " + session.accessToken)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        http.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(userMessage(responseText, response.code))
        }
    }

    suspend fun requestPasswordReset(email: String) {
        require(email.trim().contains("@")) { "Enter a valid email address." }
        request(
            "POST",
            "/auth/v1/recover?redirect_to=humanmesh%3A%2F%2Fauth%2Freset",
            JSONObject().put("email", email.trim()),
            authenticated = false
        )
    }

    suspend fun updatePasswordFromRecovery(newPassword: String, recoveryAccessToken: String) =
        withContext(Dispatchers.IO) {
            validatePassword(newPassword)
            require(recoveryAccessToken.isNotBlank()) { "The reset link is invalid or expired. Request a new one." }
            if (!isConfigured) throw IOException("Backend is not configured.")
            val request = Request.Builder()
                .url("$baseUrl/auth/v1/user")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $recoveryAccessToken")
                .put(JSONObject().put("password", newPassword).toString().toRequestBody(jsonType))
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException(userMessage(body, response.code))
            }
            sessions.clear()
        }

    suspend fun signOut() {
        runCatching { request("POST", "/auth/v1/logout", JSONObject(), authenticated = true) }
        sessions.clear()
    }

    suspend fun ensureProfile(session: UserSession, suggestedName: String) {
        val existing = getProfile(session.userId)
        if (existing != null) return
        val baseUsername = session.email.substringBefore("@")
            .lowercase()
            .replace(Regex("[^a-z0-9_]"), "")
            .take(18)
            .ifBlank { "member" }
        val username = (baseUsername + "_" + session.userId.takeLast(5)).take(30)
        val body = JSONObject()
            .put("id", session.userId)
            .put("display_name", suggestedName.trim().ifBlank { "New member" }.take(60))
            .put("username", username)
            .put("bio", "")
            .put("skills", JSONArray())
        request(
            "POST",
            "/rest/v1/profiles?on_conflict=id",
            body,
            authenticated = true,
            extraHeaders = mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")
        )
    }

    suspend fun getProfile(userId: String): UserProfile? {
        requireValidId(userId)
        val response = request(
            "GET",
            "/rest/v1/profiles?select=id,display_name,username,bio,skills&id=eq.$userId&limit=1"
        )
        val rows = JSONArray(response)
        return if (rows.length() == 0) null else UserProfile.from(rows.getJSONObject(0))
    }

    suspend fun getProfileByUsername(username: String): UserProfile? {
        val normalized = username.trim().removePrefix("@").lowercase()
        if (!USERNAME_PATTERN.matches(normalized)) return null
        val response = request(
            "GET",
            "/rest/v1/profiles?select=id,display_name,username,bio,skills&username=eq.$normalized&limit=1"
        )
        val rows = JSONArray(response)
        return if (rows.length() == 0) null else UserProfile.from(rows.getJSONObject(0))
    }

    suspend fun getDirectoryProfiles(): List<UserProfile> {
        val response = request(
            "GET",
            "/rest/v1/profiles?select=id,display_name,username,bio,skills&order=display_name.asc&limit=100"
        )
        val rows = JSONArray(response)
        return (0 until rows.length()).map { UserProfile.from(rows.getJSONObject(it)) }
    }

    suspend fun getProfilesByIds(userIds: List<String>): Map<String, UserProfile> {
        val ids = userIds.distinct().take(100)
        if (ids.isEmpty()) return emptyMap()
        ids.forEach(::requireValidId)
        val filter = ids.joinToString(",")
        val response = request(
            "GET",
            "/rest/v1/profiles?select=id,display_name,username,bio,skills&id=in.($filter)&limit=100"
        )
        val rows = JSONArray(response)
        return (0 until rows.length())
            .map { UserProfile.from(rows.getJSONObject(it)) }
            .associateBy { it.id }
    }

    suspend fun updateProfile(profile: UserProfile) {
        requireValidId(profile.id)
        val body = JSONObject()
            .put("id", profile.id)
            .put("display_name", profile.displayName.trim().take(60))
            .put("username", profile.username.trim().lowercase().take(30))
            .put("bio", profile.bio.trim().take(400))
            .put("skills", JSONArray(profile.skills.map { it.trim().take(40) }.filter(String::isNotBlank).take(20)))
        request(
            "POST",
            "/rest/v1/profiles?on_conflict=id",
            body,
            extraHeaders = mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")
        )
    }

    suspend fun getGoals(ownOnly: Boolean = false, ownerId: String = ""): List<Goal> {
        val query = if (ownOnly) {
            requireValidId(ownerId)
            "owner_id=eq.$ownerId"
        } else {
            "visibility=eq.public&status=eq.active"
        }
        val response = request(
            "GET",
            "/rest/v1/goals?select=id,owner_id,title,description,category,required_skills,status,can_offer,target_timeline&$query&order=created_at.desc&limit=60"
        )
        val rows = JSONArray(response)
        return (0 until rows.length()).map { Goal.from(rows.getJSONObject(it)) }
    }

    suspend fun createGoal(
        ownerId: String,
        title: String,
        description: String,
        category: String,
        skills: List<String>,
        canOffer: String = "",
        targetTimeline: String = ""
    ) {
        requireValidId(ownerId)
        val body = JSONObject()
            .put("owner_id", ownerId)
            .put("title", title.trim().take(100))
            .put("description", description.trim().take(2000))
            .put("category", category.trim().ifBlank { "General" }.take(50))
            .put("required_skills", JSONArray(skills.map { it.trim().take(40) }.filter(String::isNotBlank).take(20)))
            .put("can_offer", canOffer.trim().take(1000))
            .put("target_timeline", targetTimeline.trim().take(100))
            .put("status", "active")
            .put("visibility", "public")
        request(
            "POST",
            "/rest/v1/goals",
            body,
            extraHeaders = mapOf("Prefer" to "return=minimal")
        )
    }

    suspend fun updateGoal(goal: Goal) {
        requireValidId(goal.id)
        requireValidId(goal.ownerId)
        val body = JSONObject()
            .put("title", goal.title.trim().take(100))
            .put("description", goal.description.trim().take(2000))
            .put("category", goal.category.trim().ifBlank { "General" }.take(50))
            .put("required_skills", JSONArray(goal.requiredSkills.map { it.trim().take(40) }.filter(String::isNotBlank).take(20)))
            .put("can_offer", goal.canOffer.trim().take(1000))
            .put("target_timeline", goal.targetTimeline.trim().take(100))
            .put("status", goal.status)
        val result = request(
            "PATCH",
            "/rest/v1/goals?id=eq.${goal.id}&owner_id=eq.${goal.ownerId}",
            body,
            extraHeaders = mapOf("Prefer" to "return=representation")
        )
        if (JSONArray(result).length() == 0) throw IOException("Goal was not updated. It may have been deleted or you may no longer own it.")
    }

    suspend fun deleteGoal(goalId: String, ownerId: String) {
        requireValidId(goalId)
        requireValidId(ownerId)
        val result = request(
            "DELETE",
            "/rest/v1/goals?id=eq.$goalId&owner_id=eq.$ownerId",
            extraHeaders = mapOf("Prefer" to "return=representation")
        )
        if (JSONArray(result).length() == 0) throw IOException("Goal was not deleted. It may already be gone or you may not own it.")
    }

    suspend fun sendConnection(receiverId: String, goalId: String, senderId: String) {
        requireValidId(receiverId)
        requireValidId(goalId)
        requireValidId(senderId)
        try {
            request(
                "POST",
                "/rest/v1/match_requests",
                JSONObject()
                    .put("sender_id", senderId)
                    .put("receiver_id", receiverId)
                    .put("goal_id", goalId)
                    .put("message", "I'd like to explore collaborating on this goal.")
                    .put("status", "pending"),
                extraHeaders = mapOf("Prefer" to "return=minimal")
            )
        } catch (error: IOException) {
            // The database's unique constraint prevents duplicate requests. Turn that
            // expected retry/tap-again case into a clear message instead of exposing SQL.
            if (error.message.orEmpty().contains("match_requests_sender_id_receiver_id_goal_id_key")) {
                throw IOException("You have already sent a request for this goal.")
            }
            throw error
        }
    }

    suspend fun getConnections(userId: String): Pair<List<MatchRequest>, List<MatchRequest>> {
        requireValidId(userId)
        val incomingJson = request(
            "GET",
            "/rest/v1/match_requests?select=id,sender_id,receiver_id,goal_id,message,status&receiver_id=eq.$userId&order=created_at.desc&limit=50"
        )
        val outgoingJson = request(
            "GET",
            "/rest/v1/match_requests?select=id,sender_id,receiver_id,goal_id,message,status&sender_id=eq.$userId&order=created_at.desc&limit=50"
        )
        val incomingRows = JSONArray(incomingJson)
        val outgoingRows = JSONArray(outgoingJson)
        val incoming = (0 until incomingRows.length()).map { MatchRequest.from(incomingRows.getJSONObject(it)) }
        val outgoing = (0 until outgoingRows.length()).map { MatchRequest.from(outgoingRows.getJSONObject(it)) }
        return incoming to outgoing
    }

    suspend fun respondToConnection(requestId: String, status: String) {
        requireValidId(requestId)
        require(status == "accepted" || status == "rejected")
        request(
            "PATCH",
            "/rest/v1/match_requests?id=eq.$requestId",
            JSONObject().put("status", status),
            extraHeaders = mapOf("Prefer" to "return=minimal")
        )
    }

    suspend fun getRooms(): List<OpportunityRoom> {
        val response = request(
            "GET",
            "/rest/v1/opportunity_rooms?select=id,owner_id,title,description,topic&is_public=eq.true&order=created_at.desc&limit=40"
        )
        val rows = JSONArray(response)
        return (0 until rows.length()).map { OpportunityRoom.from(rows.getJSONObject(it)) }
    }

    suspend fun createRoom(ownerId: String, title: String, description: String, topic: String, goalId: String? = null) {
        requireValidId(ownerId)
        request(
            "POST",
            "/rest/v1/opportunity_rooms",
            JSONObject()
                .put("owner_id", ownerId)
                .put("title", title.trim().take(100))
                .put("description", description.trim().take(1500))
                .put("topic", topic.trim().ifBlank { "Collaboration" }.take(50))
                .put("is_public", true)
                .put("goal_id", goalId?.takeIf { it.isNotBlank() }?.let { JSONObject.wrap(it) } ?: JSONObject.NULL),
            extraHeaders = mapOf("Prefer" to "return=minimal")
        )
    }

    suspend fun joinRoom(roomId: String, userId: String) {
        requireValidId(roomId)
        requireValidId(userId)
        request(
            "POST",
            "/rest/v1/room_members?on_conflict=room_id,user_id",
            JSONObject().put("room_id", roomId).put("user_id", userId).put("role", "member"),
            extraHeaders = mapOf("Prefer" to "resolution=ignore-duplicates,return=minimal")
        )
    }

    suspend fun getMessages(roomId: String): List<RoomMessage> {
        requireValidId(roomId)
        val response = request(
            "GET",
            "/rest/v1/messages?select=id,sender_id,body,created_at&room_id=eq.$roomId&order=created_at.asc&limit=100"
        )
        val rows = JSONArray(response)
        return (0 until rows.length()).map { RoomMessage.from(rows.getJSONObject(it)) }
    }

    suspend fun sendMessage(roomId: String, senderId: String, body: String) {
        requireValidId(roomId)
        requireValidId(senderId)
        request(
            "POST",
            "/rest/v1/messages",
            JSONObject().put("room_id", roomId).put("sender_id", senderId).put("body", body.trim().take(2000)),
            extraHeaders = mapOf("Prefer" to "return=minimal")
        )
    }

    suspend fun blockUser(blockedId: String, blockerId: String) {
        requireValidId(blockedId)
        requireValidId(blockerId)
        request(
            "POST",
            "/rest/v1/user_blocks?on_conflict=blocker_id,blocked_id",
            JSONObject().put("blocker_id", blockerId).put("blocked_id", blockedId),
            extraHeaders = mapOf("Prefer" to "resolution=ignore-duplicates,return=minimal")
        )
    }

    suspend fun reportUser(reportedId: String, reporterId: String, reason: String) {
        requireValidId(reportedId)
        requireValidId(reporterId)
        request(
            "POST",
            "/rest/v1/user_reports",
            JSONObject()
                .put("reporter_id", reporterId)
                .put("reported_id", reportedId)
                .put("reason", reason.trim().take(1000)),
            extraHeaders = mapOf("Prefer" to "return=minimal")
        )
    }

    suspend fun deleteAccount() {
        request(
            "POST",
            "/functions/v1/delete-account",
            JSONObject(),
            authenticated = true
        )
        sessions.clear()
    }



    suspend fun getRoomInvitations(userId: String): List<RoomInvitation> {
        requireValidId(userId)
        val response = request(
            "GET",
            "/rest/v1/room_invitations?select=id,room_id,inviter_id,invitee_id,status,created_at&or=(invitee_id.eq.$userId,inviter_id.eq.$userId)&order=created_at.desc&limit=100"
        )
        val rows = JSONArray(response)
        return (0 until rows.length()).map { RoomInvitation.from(rows.getJSONObject(it)) }
    }

    suspend fun inviteRoomMember(roomId: String, inviterId: String, inviteeId: String) {
        requireValidId(roomId)
        requireValidId(inviterId)
        requireValidId(inviteeId)
        require(inviterId != inviteeId) { "You cannot invite yourself." }
        request(
            "POST",
            "/rest/v1/room_invitations",
            JSONObject().put("room_id", roomId).put("inviter_id", inviterId).put("invitee_id", inviteeId).put("status", "pending"),
            extraHeaders = mapOf("Prefer" to "return=minimal")
        )
    }

    suspend fun respondToRoomInvitation(invitationId: String, status: String) {
        requireValidId(invitationId)
        require(status == "accepted" || status == "rejected")
        request(
            "PATCH",
            "/rest/v1/room_invitations?id=eq.$invitationId",
            JSONObject().put("status", status),
            extraHeaders = mapOf("Prefer" to "return=minimal")
        )
    }

    suspend fun getProjectItems(roomId: String): List<ProjectItem> {
        requireValidId(roomId)
        val response = request(
            "GET",
            "/rest/v1/project_items?select=id,room_id,created_by,title,description,item_type,status,created_at,completed_at&room_id=eq.$roomId&order=created_at.asc&limit=200"
        )
        val rows = JSONArray(response)
        return (0 until rows.length()).map { ProjectItem.from(rows.getJSONObject(it)) }
    }

    suspend fun createProjectItem(roomId: String, creatorId: String, title: String, description: String, itemType: String) {
        requireValidId(roomId)
        requireValidId(creatorId)
        require(itemType == "task" || itemType == "milestone")
        require(title.trim().length in 3..120) { "Title must be 3–120 characters." }
        request(
            "POST",
            "/rest/v1/project_items",
            JSONObject()
                .put("room_id", roomId)
                .put("created_by", creatorId)
                .put("title", title.trim().take(120))
                .put("description", description.trim().take(1000))
                .put("item_type", itemType)
                .put("status", "open"),
            extraHeaders = mapOf("Prefer" to "return=minimal")
        )
    }

    suspend fun setProjectItemStatus(itemId: String, status: String) {
        requireValidId(itemId)
        require(status == "open" || status == "completed")
        request(
            "PATCH",
            "/rest/v1/project_items?id=eq.$itemId",
            JSONObject().put("status", status),
            extraHeaders = mapOf("Prefer" to "return=minimal")
        )
    }

    suspend fun getGoalOutcomes(roomId: String): List<GoalOutcome> {
        requireValidId(roomId)
        val response = request(
            "GET",
            "/rest/v1/goal_outcomes?select=id,room_id,author_id,title,summary,created_at,outcome_confirmations(user_id)&room_id=eq.$roomId&order=created_at.desc&limit=100"
        )
        val rows = JSONArray(response)
        return (0 until rows.length()).map { GoalOutcome.from(rows.getJSONObject(it)) }
    }

    suspend fun createGoalOutcome(roomId: String, authorId: String, title: String, summary: String) {
        requireValidId(roomId)
        requireValidId(authorId)
        require(title.trim().length in 3..120) { "Result title must be 3–120 characters." }
        require(summary.trim().length in 10..1500) { "Describe the result in at least 10 characters." }
        request(
            "POST",
            "/rest/v1/goal_outcomes",
            JSONObject()
                .put("room_id", roomId)
                .put("author_id", authorId)
                .put("title", title.trim().take(120))
                .put("summary", summary.trim().take(1500)),
            extraHeaders = mapOf("Prefer" to "return=minimal")
        )
    }

    suspend fun confirmGoalOutcome(outcomeId: String, userId: String) {
        requireValidId(outcomeId)
        requireValidId(userId)
        request(
            "POST",
            "/rest/v1/outcome_confirmations?on_conflict=outcome_id,user_id",
            JSONObject().put("outcome_id", outcomeId).put("user_id", userId),
            extraHeaders = mapOf("Prefer" to "resolution=ignore-duplicates,return=minimal")
        )
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        authenticated: Boolean = true,
        extraHeaders: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        if (!isConfigured) throw IOException("Backend is not configured yet. Use demo mode or configure Supabase.")
        val originalSession = sessions.read()
        val first = execute(method, path, body, authenticated, originalSession, extraHeaders)
        if (first.code == 401 && authenticated && originalSession?.refreshToken?.isNotBlank() == true) {
            val refreshed = refreshSession(originalSession.refreshToken)
            val second = execute(method, path, body, authenticated, refreshed, extraHeaders)
            if (second.code !in 200..299) throw IOException(userMessage(second.body, second.code))
            second.body
        } else {
            if (first.code !in 200..299) throw IOException(userMessage(first.body, first.code))
            first.body
        }
    }

    private data class HttpResult(val code: Int, val body: String)

    private fun execute(
        method: String,
        path: String,
        body: JSONObject?,
        authenticated: Boolean,
        session: UserSession?,
        extraHeaders: Map<String, String>
    ): HttpResult {
        val token = if (authenticated) session?.accessToken?.takeIf(String::isNotBlank) ?: anonKey else anonKey
        val builder = Request.Builder()
            .url(baseUrl + path)
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
        extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        val requestBody = body?.toString()?.toRequestBody(jsonType)
        when (method.uppercase()) {
            "POST" -> builder.post(requestBody ?: "{}".toRequestBody(jsonType))
            "PATCH" -> builder.patch(requestBody ?: "{}".toRequestBody(jsonType))
            "PUT" -> builder.put(requestBody ?: "{}".toRequestBody(jsonType))
            "DELETE" -> builder.delete(requestBody)
            else -> builder.get()
        }
        return http.newCall(builder.build()).execute().use { response ->
            HttpResult(response.code, response.body?.string().orEmpty())
        }
    }

    private fun refreshSession(refreshToken: String): UserSession {
        val body = JSONObject().put("refresh_token", refreshToken)
        val request = Request.Builder()
            .url("$baseUrl/auth/v1/token?grant_type=refresh_token")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        val result = http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(userMessage(text, response.code))
            JSONObject(text)
        }
        return saveAuthResult(result, sessions.read()?.email.orEmpty())
    }

    private fun userMessage(body: String, code: Int): String {
        return runCatching {
            val json = JSONObject(body)
            when {
                json.has("msg") -> json.optString("msg")
                json.has("message") -> json.optString("message")
                json.has("error_description") -> json.optString("error_description")
                json.has("error") -> json.optString("error")
                else -> "Request failed (HTTP $code). Check the backend setup and try again."
            }
        }.getOrDefault("Request failed (HTTP $code). Check your connection and try again.")
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .take(220)
    }

    private fun requireValidId(value: String) {
        require(UUID_PATTERN.matches(value)) { "This record has an invalid ID." }
    }

    companion object {
        private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val USERNAME_PATTERN = Regex("^[a-z0-9_]{3,30}$")
    }
}
