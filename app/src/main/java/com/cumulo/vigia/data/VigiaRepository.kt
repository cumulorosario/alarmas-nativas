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

    // ─── Token management ─────────────────────────────────────────────────────

    /**
     * Obtiene sesión fresca, ejecuta [block]. Si recibe 401 renueva el token
     * y reintenta UNA vez con la sesión actualizada.
     * Nunca llama clearSession() — si falla, lanza la excepción para que el
     * llamador la maneje. El servicio sigue corriendo y reintenta en el próximo ciclo.
     */
    private suspend fun <T> withAutoRefresh(block: suspend (token: String, baseUrl: String) -> T): T {
        // Siempre leer la sesión fresca antes de cada llamada
        val session = sessionStore.getSession()

        if (session.token.isEmpty()) {
            throw ApiException("Sin sesión activa", 401)
        }

        return try {
            block(session.token, session.baseUrl)
        } catch (e: ApiException) {
            if (e.code == 401) {
                Log.w(TAG, "Token expirado (401) — renovando")
                val newToken = renewToken(session)
                if (newToken != null) {
                    // Leer la sesión actualizada tras la renovación
                    val updatedSession = sessionStore.getSession()
                    Log.i(TAG, "Token renovado — reintentando")
                    block(newToken, updatedSession.baseUrl)
                } else {
                    // No pudo renovar — lanzar para que el llamador registre el error
                    // NO limpiar sesión: las credenciales siguen siendo válidas
                    Log.e(TAG, "No se pudo renovar el token")
                    throw e
                }
            } else throw e
        }
    }

    /**
     * Renueva el token:
     * 1. Con refreshToken (rápido)
     * 2. Con login completo (fallback)
     * Retorna el nuevo accessToken o null si ambas estrategias fallan.
     */
    private suspend fun renewToken(session: SessionStore.Session): String? {
        return tokenRefreshMutex.withLock {
            // Otro coroutine puede haber renovado el token mientras esperábamos
            val fresh = sessionStore.getSession()
            if (fresh.token != session.token && fresh.token.isNotEmpty()) {
                Log.d(TAG, "Token ya fue renovado por otro coroutine")
                return@withLock fresh.token
            }

            // Estrategia 1: refreshToken
            if (session.refreshToken.isNotEmpty()) {
                try {
                    Log.i(TAG, "Intentando refreshToken...")
                    val auth = api(session.baseUrl).refreshToken(session.refreshToken, session.baseUrl)
                    sessionStore.updateAccessToken(auth.token, auth.refreshToken)
                    Log.i(TAG, "Token renovado con refreshToken")
                    return@withLock auth.token
                } catch (e: Exception) {
                    Log.w(TAG, "refreshToken falló: ${e.message} — intentando re-login")
                }
            }

            // Estrategia 2: login completo con credenciales guardadas
            if (session.username.isNotEmpty() && session.password.isNotEmpty()) {
                try {
                    Log.i(TAG, "Re-login con credenciales guardadas...")
                    val auth = api(session.baseUrl).login(session.username, session.password)
                    val userInfo = try {
                        api(session.baseUrl, auth.token).getUserInfo()
                    } catch (e: Exception) { null }
                    val NULL_CUSTOMER_ID = "13814000-1dd2-11b2-8080-808080808080"
                    val customerId = userInfo?.customerId?.id
                        ?.takeIf { it != NULL_CUSTOMER_ID } ?: ""
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
            }

            null // Ambas estrategias fallaron — el llamador reintentará en el próximo ciclo
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    suspend fun login(username: String, password: String, rememberMe: Boolean): Result<Unit> {
        return try {
            val baseUrl = sessionStore.getSession().baseUrl
                .ifEmpty { SessionStore.DEFAULT_BASE_URL }
            val auth = api(baseUrl).login(username, password)
            val userInfo = try {
                api(baseUrl, auth.token).getUserInfo()
            } catch (e: Exception) { null }
            val NULL_CUSTOMER_ID = "13814000-1dd2-11b2-8080-808080808080"
            val customerId = userInfo?.customerId?.id
                ?.takeIf { it != NULL_CUSTOMER_ID } ?: ""
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
            // Bug fix: NO usar mutableList externo al lambda — construir dentro del bloque
            // para que el reintento tras renovación de token parta de cero
            val alarms = withAutoRefresh { token, baseUrl ->
                val session = sessionStore.getSession()
                val tbApi   = api(baseUrl, token)
                val result  = mutableListOf<Alarm>()

                // Estrategia 1: endpoint general
                result.addAll(tbApi.getAlarms(100))

                // Estrategia 2: por tenant
                if (session.tenantId.isNotEmpty()) {
                    tbApi.getAlarmsByTenant(session.tenantId).forEach { a ->
                        if (result.none { it.id.id == a.id.id }) result.add(a)
                    }
                }

                // Estrategia 3: por customer
                if (session.customerId.isNotEmpty()) {
                    tbApi.getAlarmsByCustomer(session.customerId).forEach { a ->
                        if (result.none { it.id.id == a.id.id }) result.add(a)
                    }
                }

                // Estrategia 4: fallback por dispositivo si hay pocas alarmas
                if (result.size < 5) {
                    val devices = tbApi.getDevices(30)
                    coroutineScope {
                        devices.map { d -> async { tbApi.getAlarmsByDevice(d.id.id) } }
                            .map { it.await() }
                    }.flatten().forEach { a ->
                        if (result.none { it.id.id == a.id.id }) result.add(a)
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
            withAutoRefresh { token, baseUrl -> api(baseUrl, token).acknowledgeAlarm(alarmId) }
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(ErrorTranslator.translate(e.message) ?: "Error al reconocer alarma")
        }
    }

    suspend fun clearAlarm(alarmId: String): Result<Unit> {
        return try {
            withAutoRefresh { token, baseUrl ->
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
            val devices = withAutoRefresh { token, baseUrl ->
                val session = sessionStore.getSession()
                val tbApi   = api(baseUrl, token)
                val raw = if (session.customerId.isNotEmpty())
                    tbApi.getCustomerDevices(session.customerId)
                else
                    tbApi.getDevices()
                coroutineScope {
                    raw.map { device ->
                        async {
                            val online = try {
                                tbApi.getDeviceAttributes(device.id.id)
                            } catch (e: Exception) { false }
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
