package com.cumulo.vigia.data.api

import com.cumulo.vigia.model.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ThingsBoardApi(
    private val baseUrl: String,
    private val token: String? = null
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun buildRequest(path: String): Request.Builder {
        val url = baseUrl.trimEnd('/') + path
        val builder = Request.Builder().url(url)
            .header("Content-Type", "application/json")
        token?.let { builder.header("X-Authorization", "Bearer $it") }
        return builder
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val request = buildRequest(path).get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            val msg = try { JSONObject(body).optString("message", "Error ${response.code}") }
                      catch (e: Exception) { "Error ${response.code}" }
            throw ApiException(msg, response.code)
        }
        body
    }

    private suspend fun post(path: String, body: String = ""): String = withContext(Dispatchers.IO) {
        val request = buildRequest(path)
            .post(body.toRequestBody(JSON))
            .build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            val msg = try { JSONObject(responseBody).optString("message", "Error ${response.code}") }
                      catch (e: Exception) { "Error ${response.code}" }
            throw ApiException(msg, response.code)
        }
        responseBody
    }

    suspend fun login(username: String, password: String): AuthResponse {
        val body = """{"username":"$username","password":"$password"}"""
        val raw = post("/api/auth/login", body)
        return gson.fromJson(raw, AuthResponse::class.java)
    }

    suspend fun getUserInfo(): UserInfo {
        val raw = get("/api/auth/user")
        return gson.fromJson(raw, UserInfo::class.java)
    }

    suspend fun getAlarms(pageSize: Int = 50, status: String? = null): List<Alarm> {
        val query = if (status != null) "&status=$status" else ""
        return try {
            val raw = get("/api/alarms?pageSize=$pageSize&page=0$query")
            parseAlarmList(raw)
        } catch (e: ApiException) {
            if (e.code == 403 || e.code == 404) emptyList() else throw e
        }
    }

    suspend fun getAlarmsByTenant(tenantId: String, pageSize: Int = 50): List<Alarm> {
        return try {
            val raw = get("/api/alarm/TENANT/$tenantId?pageSize=$pageSize&page=0")
            parseAlarmList(raw)
        } catch (e: ApiException) {
            emptyList()
        }
    }

    suspend fun getAlarmsByCustomer(customerId: String, pageSize: Int = 50): List<Alarm> {
        return try {
            val raw = get("/api/alarm/CUSTOMER/$customerId?pageSize=$pageSize&page=0")
            parseAlarmList(raw)
        } catch (e: ApiException) {
            emptyList()
        }
    }

    suspend fun getAlarmsByDevice(deviceId: String): List<Alarm> {
        return try {
            val raw = get("/api/alarm/DEVICE/$deviceId?pageSize=10&page=0")
            parseAlarmList(raw)
        } catch (e: ApiException) {
            emptyList()
        }
    }

    suspend fun acknowledgeAlarm(alarmId: String) {
        try { post("/api/alarm/$alarmId/ack") } catch (e: ApiException) {
            if (e.message?.contains("already", ignoreCase = true) != true) throw e
        }
    }

    suspend fun clearAlarm(alarmId: String) {
        try { post("/api/alarm/$alarmId/clear") } catch (e: ApiException) {
            if (e.message?.contains("already", ignoreCase = true) != true) throw e
        }
    }

    suspend fun getDevices(pageSize: Int = 50): List<Device> {
        return try {
            val raw = get("/api/tenant/devices?pageSize=$pageSize&page=0")
            parseDeviceList(raw)
        } catch (e: ApiException) {
            emptyList()
        }
    }

    suspend fun getCustomerDevices(customerId: String, pageSize: Int = 50): List<Device> {
        return try {
            val raw = get("/api/customer/$customerId/devices?pageSize=$pageSize&page=0")
            parseDeviceList(raw)
        } catch (e: ApiException) {
            emptyList()
        }
    }

    suspend fun getDeviceAttributes(deviceId: String): Boolean {
        return try {
            val raw = get("/api/plugins/telemetry/DEVICE/$deviceId/values/attributes")
            val arr = gson.fromJson<List<Map<String, Any>>>(raw, object : TypeToken<List<Map<String, Any>>>() {}.type)
            val activeAttr = arr.find { it["key"] == "active" }
            val lastConnect = arr.find { it["key"] == "lastConnectTime" }?.get("value")?.toString()?.toLongOrNull() ?: 0L
            val lastDisconnect = arr.find { it["key"] == "lastDisconnectTime" }?.get("value")?.toString()?.toLongOrNull() ?: 0L
            val isActive = activeAttr?.get("value").toString() == "true"
            isActive && (lastConnect > lastDisconnect || lastDisconnect == 0L)
        } catch (e: Exception) {
            false
        }
    }

    private fun parseAlarmList(raw: String): List<Alarm> {
        val obj = JSONObject(raw)
        val arr = obj.optJSONArray("data") ?: return emptyList()
        val list = mutableListOf<Alarm>()
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            list.add(Alarm(
                id = AlarmId(a.getJSONObject("id").getString("id")),
                createdTime = a.getLong("createdTime"),
                type = a.optString("type", "UNKNOWN"),
                severity = a.optString("severity", "INDETERMINATE"),
                status = a.optString("status", "ACTIVE_UNACK"),
                originatorName = a.optString("originatorName", "Desconocido")
            ))
        }
        return list.sortedByDescending { it.createdTime }
    }

    private fun parseDeviceList(raw: String): List<Device> {
        val obj = JSONObject(raw)
        val arr = obj.optJSONArray("data") ?: return emptyList()
        val list = mutableListOf<Device>()
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            val idObj = d.getJSONObject("id")
            list.add(Device(
                id = DeviceId(idObj.getString("id"), idObj.optString("entityType", "DEVICE")),
                name = d.optString("name", "Dispositivo"),
                type = d.optString("type", "default")
            ))
        }
        return list
    }
}

class ApiException(message: String, val code: Int = 0) : Exception(message)

// Extension: renovar token con refreshToken
suspend fun ThingsBoardApi.refreshToken(refreshToken: String, baseUrl: String): AuthResponse {
    val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    val JSON = "application/json; charset=utf-8".toMediaType()
    val body = """{"refreshToken":"$refreshToken"}"""
    val request = okhttp3.Request.Builder()
        .url(baseUrl.trimEnd('/') + "/api/auth/token")
        .post(body.toRequestBody(JSON))
        .header("Content-Type", "application/json")
        .build()
    val response = client.newCall(request).execute()
    val raw = response.body?.string() ?: ""
    if (!response.isSuccessful) throw ApiException("Refresh failed: ${response.code}", response.code)
    return com.google.gson.Gson().fromJson(raw, AuthResponse::class.java)
}
