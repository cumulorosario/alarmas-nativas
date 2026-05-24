package com.cumulo.vigia.data

import android.util.Log
import com.cumulo.vigia.data.api.ApiException
import com.cumulo.vigia.data.api.ThingsBoardApi
import com.cumulo.vigia.data.api.refreshToken
import com.cumulo.vigia.util.ErrorTranslator
import com.cumulo.vigia.data.local.SessionStore
import com.cumulo.vigia.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VigiaRepository(private val sessionStore: SessionStore) {

    private val TAG = "VigiaRepository"

    // Mutex para evitar múltiples renovaciones simultáneas de token
    private val tokenRefreshMutex = Mutex()

    private fun api(baseUrl: String, token: String? = null) = ThingsBoardApi(baseUrl, token)

    // ─── Token management ────────────────────────────────────────────────────

    /**
     * Ejecuta [block] con el token actual. Si recibe 401, intenta renovar el token
     * (primero con refreshToken, luego con usuario/contraseña) y reintenta una vez.
     */
    private suspend fun <T> withAutoRefresh(block: suspend (token: String, baseUrl: String) -> T): T {
        val session = sessionStore.getSession()
        return try {
            block(session.token, session.baseUrl)
        } catch (e: ApiException) {
            if (e.code == 401) {
                Log.w(TAG, "Token expired (401) — attempting refresh")
                val newToken = renewToken(session)
                if (newToken != null) {
                    Log.i(TAG, "Token renewed successfully — retrying request")
                    block(newToken, session.baseUrl)
                } else {
                    Log.e(TAG, "Token renewal failed — clearing session")
                    sessionStore.clearSession()
                    throw e
                }
            } else throw e
        }
    }

    /**
     * Renueva el token. Estrategia:
     * 1. Intentar con refreshToken (rápido, no requiere credenciales)
     * 2. Si falla, hacer login completo con usuario/contraseña guardados
     * Retorna el nuevo accessToken, o null si todo falla.
     */
    private suspend fun renewToken(session: SessionStore.Session): String? {
        return tokenRefreshMutex.withLock {
            // Verificar si otro coroutine ya renovó el token mientras esperábamos el mutex
            val freshSession = sessionStore.getSession()
            if (freshSession.token != session.token && freshSession.token.isNotEmpty()) {
                Log.d(TAG, "Token already renewed by another coroutine")
                return@withLock freshSession.token
            }

            // Estrategia 1: refreshToken
            if (session.refreshToken.isNotEmpty()) {
                try {
                    Log.i(TAG, "Trying refreshToken...")
                    val dummyApi = api(session.baseUrl)
                    val auth = dummyApi.refreshToken(session.refreshToken, session.baseUrl)
                    sessionStore.updateAccessToken(auth.token, auth.refreshToken)
                    Log.i(TAG, "Token refreshed via refreshToken")
                    return@withLock auth.token
                } catch (e: Exception) {
                    Log.w(TAG, "refreshToken failed: ${e.message} — falling back to login")
                }
            }

            // Estrategia 2: login completo con credenciales guardadas
            if (session.username.isNotEmpty() && session.password.isNotEmpty()) {
                try {
                    Log.i(TAG, "Re-login with saved credentials...")
                    val authApi = api(session.baseUrl)
                    val auth = authApi.login(session.username, session.password)
                    val userApi = api(session.baseUrl, auth.token)
                    val userInfo = try { userApi.getUserInfo() } catch (e: Exception) { null }
                    val NULL_CUSTOMER_ID = "13814000-1dd2-11b2-8080-808080808080"
                    val customerId = userInfo?.customerId?.id?.takeIf { it != NULL_CUSTOMER_ID } ?: ""
                    sessionStore.saveSession(
                        token        = auth.token,
                        refreshToken = auth.refreshToken,
                        baseUrl      = session.baseUrl,
                        tenantId     = userInfo?.tenantId?.id ?: session.tenantId,
                        customerId   = customerId.ifEmpty { session.customerId },
                        authority    = userInfo?.authority ?: session.authority
                    )
                    Log.i(TAG, "Re-login successful")
                    return@withLock auth.token
                } catch (e: Exception) {
                    Log.e(TAG, "Re-login failed: ${e.message}")
                }
            }

            null
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    suspend fun login(username: String, password: String, rememberMe: Boolean): Result<Unit> {
        return try {
            val session = sessionStore.getSession()
            val baseUrl = session.baseUrl.ifEmpty { SessionStore.DEFAULT_BASE_URL }
            val authApi = api(baseUrl)
            val auth = authApi.login(username, password)
            val userApi = api(baseUrl, auth.token)
            val userInfo = try { userApi.getUserInfo() } catch (e: Exception) { null }
            val NULL_CUSTOMER_ID = "13814000-1dd2-11b2-8080-808080808080"
            val customerId = userInfo?.customerId?.id?.takeIf { it != NULL_CUSTOMER_ID } ?: ""
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
            Result.Error(ErrorTranslator.translate(e.message) ?: "Error al iniciar sesión", e as? Exception)
        }
    }

    suspend fun logout() = sessionStore.clearSession()

    suspend fun getAlarms(): Result<List<Alarm>> {
        return try {
            val allAlarms = mutableListOf<Alarm>()
            withAutoRefresh { token, baseUrl ->
                val session = sessionStore.getSession()
                val tbApi = api(baseUrl, token)
                val general = tbApi.getAlarms(100)
                allAlarms.addAll(general)
                if (session.tenantId.isNotEmpty()) {
                    tbApi.getAlarmsByTenant(session.tenantId).forEach { a ->
                        if (allAlarms.none { it.id.id == a.id.id }) allAlarms.add(a)
                    }
                }
                if (session.customerId.isNotEmpty()) {
                    tbApi.getAlarmsByCustomer(session.customerId).forEach { a ->
                        if (allAlarms.none { it.id.id == a.id.id }) allAlarms.add(a)
                    }
                }
                if (allAlarms.size < 5) {
                    val devices = tbApi.getDevices(30)
                    coroutineScope {
                        devices.map { device -> async { tbApi.getAlarmsByDevice(device.id.id) } }
                            .map { it.await() }
                    }.forEach { deviceAlarms ->
                        deviceAlarms.forEach { a ->
                            if (allAlarms.none { it.id.id == a.id.id }) allAlarms.add(a)
                        }
                    }
                }
            }
            Result.Success(allAlarms.sortedByDescending { it.createdTime })
        } catch (e: Exception) {
            Result.Error(ErrorTranslator.translate(e.message) ?: "Error al cargar alarmas", e as? Exception)
        }
    }

    suspend fun acknowledgeAlarm(alarmId: String): Result<Unit> {
        return try {
            withAutoRefresh { token, baseUrl -> api(baseUrl, token).acknowledgeAlarm(alarmId) }
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(ErrorTranslator.translate(e.message) ?: "Error al reconocer alarma", e as? Exception)
        }
    }

    suspend fun clearAlarm(alarmId: String): Result<Unit> {
        return try {
            withAutoRefresh { token, baseUrl ->
                val tbApi = api(baseUrl, token)
                tbApi.clearAlarm(alarmId)
                tbApi.acknowledgeAlarm(alarmId)
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(ErrorTranslator.translate(e.message) ?: "Error al resolver alarma", e as? Exception)
        }
    }

    suspend fun getDevices(): Result<List<Device>> {
        return try {
            val session = sessionStore.getSession()
            withAutoRefresh { token, baseUrl ->
                val tbApi = api(baseUrl, token)
                val rawDevices = if (session.customerId.isNotEmpty())
                    tbApi.getCustomerDevices(session.customerId)
                else
                    tbApi.getDevices()
                coroutineScope {
                    rawDevices.map { device ->
                        async {
                            val online = try { tbApi.getDeviceAttributes(device.id.id) }
                                         catch (e: Exception) { false }
                            device.copy(online = online)
                        }
                    }.map { it.await() }
                }
            }.let { Result.Success(it) }
        } catch (e: Exception) {
            Result.Error(ErrorTranslator.translate(e.message) ?: "Error al cargar dispositivos", e as? Exception)
        }
    }
}
