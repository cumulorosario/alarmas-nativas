package com.cumulo.vigia.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vigia_session")

class SessionStore(private val context: Context) {

    companion object {
        val KEY_TOKEN         = stringPreferencesKey("tb_token")
        val KEY_REFRESH_TOKEN = stringPreferencesKey("tb_refresh_token")  // nuevo
        val KEY_BASE_URL      = stringPreferencesKey("tb_base_url")
        val KEY_TENANT_ID     = stringPreferencesKey("tb_tenant_id")
        val KEY_CUSTOMER_ID   = stringPreferencesKey("tb_customer_id")
        val KEY_AUTHORITY     = stringPreferencesKey("tb_authority")
        val KEY_USERNAME      = stringPreferencesKey("saved_username")
        val KEY_PASSWORD      = stringPreferencesKey("saved_password")
        val KEY_REMEMBER_ME   = booleanPreferencesKey("remember_me")
        val KEY_VIBRATE       = booleanPreferencesKey("alarm_vibrate")
        val KEY_SOUND         = booleanPreferencesKey("alarm_sound")
        val KEY_WAKE          = booleanPreferencesKey("alarm_wake")

        const val DEFAULT_BASE_URL = "http://cumuloingenieria.duckdns.org:9090"
    }

    data class Session(
        val token: String = "",
        val refreshToken: String = "",
        val baseUrl: String = DEFAULT_BASE_URL,
        val tenantId: String = "",
        val customerId: String = "",
        val authority: String = "",
        val username: String = "",
        val password: String = "",
        val rememberMe: Boolean = false
    ) {
        val isLoggedIn: Boolean get() = token.isNotEmpty()
    }

    data class AlarmSettings(
        val vibrate: Boolean = true,
        val sound: Boolean = true,
        val wake: Boolean = true
    )

    val sessionFlow: Flow<Session> = context.dataStore.data.map { prefs ->
        Session(
            token        = prefs[KEY_TOKEN] ?: "",
            refreshToken = prefs[KEY_REFRESH_TOKEN] ?: "",
            baseUrl      = prefs[KEY_BASE_URL] ?: DEFAULT_BASE_URL,
            tenantId     = prefs[KEY_TENANT_ID] ?: "",
            customerId   = prefs[KEY_CUSTOMER_ID] ?: "",
            authority    = prefs[KEY_AUTHORITY] ?: "",
            username     = prefs[KEY_USERNAME] ?: "",
            password     = prefs[KEY_PASSWORD] ?: "",
            rememberMe   = prefs[KEY_REMEMBER_ME] ?: false
        )
    }

    val alarmSettingsFlow: Flow<AlarmSettings> = context.dataStore.data.map { prefs ->
        AlarmSettings(
            vibrate = prefs[KEY_VIBRATE] ?: true,
            sound   = prefs[KEY_SOUND] ?: true,
            wake    = prefs[KEY_WAKE] ?: true
        )
    }

    suspend fun getSession(): Session = sessionFlow.first()

    suspend fun saveSession(
        token: String,
        refreshToken: String = "",
        baseUrl: String,
        tenantId: String = "",
        customerId: String = "",
        authority: String = ""
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TOKEN]         = token
            if (refreshToken.isNotEmpty()) prefs[KEY_REFRESH_TOKEN] = refreshToken
            prefs[KEY_BASE_URL]      = baseUrl
            prefs[KEY_TENANT_ID]     = tenantId
            prefs[KEY_CUSTOMER_ID]   = customerId
            prefs[KEY_AUTHORITY]     = authority
        }
    }

    /** Actualiza solo el token de acceso (tras renovación con refreshToken) */
    suspend fun updateAccessToken(newToken: String, newRefreshToken: String = "") {
        context.dataStore.edit { prefs ->
            prefs[KEY_TOKEN] = newToken
            if (newRefreshToken.isNotEmpty()) prefs[KEY_REFRESH_TOKEN] = newRefreshToken
        }
    }

    suspend fun saveCredentials(username: String, password: String, remember: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_REMEMBER_ME] = remember
            if (remember) {
                prefs[KEY_USERNAME] = username
                prefs[KEY_PASSWORD] = password
            } else {
                prefs.remove(KEY_USERNAME)
                prefs.remove(KEY_PASSWORD)
            }
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_TOKEN)
            prefs.remove(KEY_REFRESH_TOKEN)
            prefs.remove(KEY_TENANT_ID)
            prefs.remove(KEY_CUSTOMER_ID)
            prefs.remove(KEY_AUTHORITY)
        }
    }

    suspend fun saveAlarmSettings(vibrate: Boolean, sound: Boolean, wake: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_VIBRATE] = vibrate
            prefs[KEY_SOUND]   = sound
            prefs[KEY_WAKE]    = wake
        }
    }
}
