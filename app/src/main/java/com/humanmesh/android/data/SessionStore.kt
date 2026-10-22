package com.humanmesh.android.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class UserSession(
    val userId: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String
)

class SessionStore(context: Context) {
    private val appContext = context.applicationContext

    private val preferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "humanmesh_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun read(): UserSession? = runCatching {
        val userId = preferences.getString("user_id", null).orEmpty()
        val accessToken = preferences.getString("access_token", null).orEmpty()
        if (userId.isBlank() || accessToken.isBlank()) return null
        UserSession(
            userId = userId,
            email = preferences.getString("email", "").orEmpty(),
            accessToken = accessToken,
            refreshToken = preferences.getString("refresh_token", "").orEmpty()
        )
    }.getOrNull()

    fun save(session: UserSession) {
        preferences.edit()
            .putString("user_id", session.userId)
            .putString("email", session.email)
            .putString("access_token", session.accessToken)
            .putString("refresh_token", session.refreshToken)
            .apply()
    }

    fun clear() {
        runCatching { preferences.edit().clear().apply() }
    }
}
