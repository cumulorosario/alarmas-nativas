package com.cumulo.vigia.data

import android.util.Log
import com.cumulo.vigia.data.api.ApiException
import com.cumulo.vigia.data.api.ThingsBoardApi
import com.cumulo.vigia.data.api.refreshToken
import com.cumulo.vigia.data.local.SessionStore
import com.cumulo.vigia.model.*
import com.cumulo.vigia.util.ErrorTranslator
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VigiaRepository(private val sessionStore: SessionStore) {

    private val TAG = "VigiaRepository"
    private val tokenRefreshMutex = Mutex()

    private fun api(baseUrl: String, token: String? = null) = ThingsBoardApi(baseUrl, token)

    /**
     * Lee la sesión fresca, ejecuta [block] con el token y baseUrl actuales.
     * Si recibe 401, renueva el token y reintenta UNA sola vez con sesión actualizada.
     * NUNCA llama clearSession() — las credenciales siempre se conservan.
     */
    private suspend fun <T> withAutoRefresh(block: suspend (token: String, baseUrl: String, session: SessionStore.Session) -> T): T {
        val session = sessionStore.getSession()

        if (session.token.isEmpty()) {
            throw ApiException("Sin sesión activa — iniciá sesión", 401)
        }

        return try {
            block(session.token, session.baseUrl, session)
        } catch (e: ApiException) {
            if (e.code == 401 || e.message?.contains("token", true) == true || e.message?.contains("jwt", true) == true) {
                Log.w(TAG, "401 recibido — renovando token")
                val newToken = renewToken(session)
                if (newToken != null) {
                    val updated = sessionStore.getSession()
                    Log.i(TAG, "Token renovado — reintentando operación")
                    block(newToken, updated.baseUrl, updated)
                } else {
                    Log.e(TAG, "No se pudo renovar el token — reintentará en el próximo ciclo")
                    throw e
                }
            } else {
                throw e
            }
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            val shouldRetryAuth = listOf("401", "unauthorized", "token", "jwt", "expired").any {
                msg.contains(it, ignoreCase = true)
            }

            if (shouldRetryAuth) {
                Log.w(TAG, "Posible token expirado detectado por excepción genérica — renovando")
                val newToken = renewToken(session)
                if (newToken != null) {
                    val updated = sessionStore.getSession()
                    return block(newToken, updated.baseUrl, updated)
                }
            }

            throw e
        }
    }

private suspend fun renewToken(session: SessionStore.Session): String? {
        return tokenRefreshMutex.withLock {
            // Si otro coroutine ya renovó mientras esperábamos, usar ese token
            val fresh = sessionStore.getSession()
            if (fresh.token.isNotEmpty() && fresh.token != session.token) {
                Log.d(TAG, "Token ya renovado por otro coroutine")
                return@withLock fresh.token
            }

            // Estrategia 1: refreshToken
            if (session.refreshToken.isNotEmpty()) {
                try {
                    Log.i(TAG, "Renovando con refreshToken...")
                    val auth = api(session.baseUrl).refreshToken(session.refreshToken, session.baseUrl)
                    sessionStore.updateAccessToken(auth.token, auth.refreshToken)
                    Log.i(TAG, "Token renovado exitosamente con refreshToken")
                    return@withLock auth.token
                } catch (e: Exception) {
                    Log.w(TAG, "refreshToken falló (${e.message}) — intentando re-login")
                }
            }

            // Estrategia 2: login completo con credenciales guardadas
            if (session.username.isNotEmpty() && session.password.isNotEmpty()) {
                try {
                    Log.i(TAG, "Re-login con credenciales guardadas (${session.username})")
                    val auth = api(session.baseUrl).login(session.username, session.password)
                    val userInfo = try { api(session.baseUrl, auth.token).getUserInfo() }
                                   catch (e: Exception) { null }
                    val NULL_CID = "13814000-1dd2-11b2-8080-808080808080"
                    val customerId = userInfo?.customerId?.id?.takeIf { it != NULL_CID } ?: ""
                    sessionStore.saveSession(
                        token        = auth.token,
                        refreshToken = auth.refreshToken,
                        baseUrl      = session.baseUrl,
                        tenantId     = userInfo?.tenantId?.id ?: session.tenantId,
                        customerId   = customerId.ifEmpty { session.customerId },
                        authority    = userInfo?.authority ?: session.authority
                    )
                    Log.i(TAG, "Re-login exitoso")
                    return@withLock auth.token
                } catch (e: Exception) {
                    Log.e(TAG, "Re-login falló: ${e.message}")
                }
            } else {
                Log.e(TAG, "No hay credenciales guardadas para re-login")
            }

            null
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    suspend fun login(username: String, password: String, rememberMe: Boolean): Result<Unit> {
        return try {
            val baseUrl = sessionStore.getSession().baseUrl.ifEmpty { SessionStore.DEFAULT_BASE_URL }
            val auth = api(baseUrl).login(username, password)
            val userInfo = try { api(baseUrl, auth.token).getUserInfo() } catch (e: Exception) { null }
            val NULL_CID = "13814000-1dd2-11b2-8080-808080808080"
            val customerId = userInfo?.customerId?.id?.takeIf { it != NULL_CID } ?: ""
            sessionStore.saveSession(
                token        = auth.token,
                refreshToken = auth.refreshToken,
                baseUrl      = baseUrl,
                tenantId     = userInfo?.tenantId?.id ?: "",
                customerId   = customerId,
                authority    = userInfo?.authority ?: ""
            )
            sessionStore.saveCredentials(username, password, rememberMe)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(ErrorTranslator.translate(e.message) ?: "Error al iniciar sesión")
        }
    }

    suspend fun logout() = sessionStore.clearSession()

    suspend fun getAlarms(): Result<List<Alarm>> {
        return try {
            val alarms = withAutoRefresh { token, baseUrl, session ->
                // IMPORTANTE: usar el token y session del parámetro, NO releer sessionStore aquí
                // Esto garantiza consistencia si el token fue renovado en el reintento
                val tbApi = api(baseUrl, token)
                // Restaurado a estrategia simple y compatible con customer users
                // El merge multi-endpoint generaba listas vacías en algunos tenants.

                val result = when {
                    session.customerId.isNotEmpty() -> {
                        tbApi.getAlarmsByCustomer(session.customerId, 100)
                    }
                    session.tenantId.isNotEmpty() -> {
                        tbApi.getAlarmsByTenant(session.tenantId, 100)
                    }
                    else -> {
                        tbApi.getAlarms(100)
                    }
                }

                result.sortedByDescending { it.createdTime }
            }
            Result.Success(alarms)
        } catch (e: Exception) {
            Result.Error(ErrorTranslator.translate(e.message) ?: "Error al cargar alarmas")
        }
    }

    suspend fun acknowledgeAlarm(alarmId: String): Result<Unit> {
        return try {
            withAutoRefresh { token, baseUrl, _ -> api(baseUrl, token).acknowledgeAlarm(alarmId) }
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(ErrorTranslator.translate(e.message) ?: "Error al reconocer alarma")
        }
    }

    suspend fun clearAlarm(alarmId: String): Result<Unit> {
        return try {
            withAutoRefresh { token, baseUrl, _ ->
                api(baseUrl, token).clearAlarm(alarmId)
                api(baseUrl, token).acknowledgeAlarm(alarmId)
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(ErrorTranslator.translate(e.message) ?: "Error al resolver alarma")
        }
    }

    suspend fun getDevices(): Result<List<Device>> {
        return try {
            val devices = withAutoRefresh { token, baseUrl, session ->
                val tbApi = api(baseUrl, token)
                val raw = if (session.customerId.isNotEmpty())
                    tbApi.getCustomerDevices(session.customerId)
                else
                    tbApi.getDevices()
                coroutineScope {
                    raw.map { device ->
                        async {
                            val online = try { tbApi.getDeviceAttributes(device.id.id) }
                                         catch (e: Exception) { false }
                            device.copy(online = online)
                        }
                    }.map { it.await() }
                }
            }
            Result.Success(devices)
        } catch (e: Exception) {
            Result.Error(ErrorTranslator.translate(e.message) ?: "Error al cargar dispositivos")
        }
    }
}
