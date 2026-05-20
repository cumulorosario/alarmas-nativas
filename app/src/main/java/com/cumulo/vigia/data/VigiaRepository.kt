package com.cumulo.vigia.data

import com.cumulo.vigia.data.api.ThingsBoardApi
import com.cumulo.vigia.data.local.SessionStore
import com.cumulo.vigia.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class VigiaRepository(private val sessionStore: SessionStore) {

    private fun api(baseUrl: String = SessionStore.DEFAULT_BASE_URL, token: String? = null) =
        ThingsBoardApi(baseUrl, token)

    suspend fun login(username: String, password: String, rememberMe: Boolean): Result<Unit> {
        return try {
            val session = sessionStore.getSession()
            val baseUrl = session.baseUrl.ifEmpty { SessionStore.DEFAULT_BASE_URL }
            val authApi = api(baseUrl)
            val auth = authApi.login(username, password)

            // Fetch user context
            val userApi = api(baseUrl, auth.token)
            val userInfo = try { userApi.getUserInfo() } catch (e: Exception) { null }

            val NULL_CUSTOMER_ID = "13814000-1dd2-11b2-8080-808080808080"
            val customerId = userInfo?.customerId?.id
                ?.takeIf { it != NULL_CUSTOMER_ID } ?: ""

            sessionStore.saveSession(
                token = auth.token,
                baseUrl = baseUrl,
                tenantId = userInfo?.tenantId?.id ?: "",
                customerId = customerId,
                authority = userInfo?.authority ?: ""
            )
            sessionStore.saveCredentials(username, password, rememberMe)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Error al iniciar sesión", e as? Exception)
        }
    }

    suspend fun logout() {
        sessionStore.clearSession()
    }

    suspend fun getAlarms(): Result<List<Alarm>> {
        return try {
            val session = sessionStore.getSession()
            val token = session.token
            val baseUrl = session.baseUrl
            val tbApi = api(baseUrl, token)

            val allAlarms = mutableListOf<Alarm>()

            // Strategy 1: General alarms endpoint
            val generalAlarms = tbApi.getAlarms(100)
            allAlarms.addAll(generalAlarms)

            // Strategy 2: By Tenant
            if (session.tenantId.isNotEmpty()) {
                val tenantAlarms = tbApi.getAlarmsByTenant(session.tenantId)
                tenantAlarms.forEach { a ->
                    if (allAlarms.none { it.id.id == a.id.id }) allAlarms.add(a)
                }
            }

            // Strategy 3: By Customer
            if (session.customerId.isNotEmpty()) {
                val customerAlarms = tbApi.getAlarmsByCustomer(session.customerId)
                customerAlarms.forEach { a ->
                    if (allAlarms.none { it.id.id == a.id.id }) allAlarms.add(a)
                }
            }

            // Strategy 4: Fallback per device
            if (allAlarms.size < 5) {
                val devices = tbApi.getDevices(30)
                coroutineScope {
                    devices.map { device ->
                        async {
                            tbApi.getAlarmsByDevice(device.id.id)
                        }
                    }.map { it.await() }
                }.forEach { deviceAlarms ->
                    deviceAlarms.forEach { a ->
                        if (allAlarms.none { it.id.id == a.id.id }) allAlarms.add(a)
                    }
                }
            }

            Result.Success(allAlarms.sortedByDescending { it.createdTime })
        } catch (e: Exception) {
            Result.Error(e.message ?: "Error al cargar alarmas", e as? Exception)
        }
    }

    suspend fun acknowledgeAlarm(alarmId: String): Result<Unit> {
        return try {
            val session = sessionStore.getSession()
            api(session.baseUrl, session.token).acknowledgeAlarm(alarmId)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Error al reconocer alarma", e as? Exception)
        }
    }

    suspend fun clearAlarm(alarmId: String): Result<Unit> {
        return try {
            val session = sessionStore.getSession()
            val tbApi = api(session.baseUrl, session.token)
            tbApi.clearAlarm(alarmId)
            tbApi.acknowledgeAlarm(alarmId) // Also ack after clear
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Error al resolver alarma", e as? Exception)
        }
    }

    suspend fun getDevices(): Result<List<Device>> {
        return try {
            val session = sessionStore.getSession()
            val tbApi = api(session.baseUrl, session.token)

            val rawDevices = if (session.customerId.isNotEmpty()) {
                tbApi.getCustomerDevices(session.customerId)
            } else {
                tbApi.getDevices()
            }

            // Fetch online status in parallel
            val devicesWithStatus = coroutineScope {
                rawDevices.map { device ->
                    async {
                        val online = tbApi.getDeviceAttributes(device.id.id)
                        device.copy(online = online)
                    }
                }.map { it.await() }
            }

            Result.Success(devicesWithStatus)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Error al cargar dispositivos", e as? Exception)
        }
    }
}
