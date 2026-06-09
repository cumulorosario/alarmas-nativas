package com.cumulo.vigia.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persiste las preferencias de silencio/ocultamiento por dispositivo+tipo de alarma.
 * Clave: "deviceId::alarmType"
 * Valor: "muted" | "hidden"
 */
class AlarmFilterStore(private val context: Context) {

    companion object {
        val KEY_FILTERS = stringPreferencesKey("alarm_filters_json")
    }

    data class AlarmFilter(
        val deviceId: String,
        val deviceName: String,
        val alarmType: String,
        val muted: Boolean = false,   // no notifica ni alerta
        val hidden: Boolean = false   // no aparece en la lista
    ) {
        val key get() = "$deviceId::$alarmType"
        val isDisabled get() = muted || hidden
    }

    /** Flow de todos los filtros guardados */
    val filtersFlow: Flow<List<AlarmFilter>> = context.dataStore.data.map { prefs ->
        parseFilters(prefs[KEY_FILTERS] ?: "[]")
    }

    suspend fun getFilters(): List<AlarmFilter> = filtersFlow.first()

    suspend fun setFilter(filter: AlarmFilter) {
        val current = getFilters().toMutableList()
        current.removeAll { it.key == filter.key }
        if (filter.muted || filter.hidden) current.add(filter)
        saveFilters(current)
    }

    suspend fun clearFilter(deviceId: String, alarmType: String) {
        val current = getFilters().toMutableList()
        current.removeAll { it.key == "$deviceId::$alarmType" }
        saveFilters(current)
    }

    suspend fun isBlocked(deviceId: String, alarmType: String): Boolean {
        return getFilters().any { it.key == "$deviceId::$alarmType" && it.isDisabled }
    }

    suspend fun isMuted(deviceId: String, alarmType: String): Boolean {
        return getFilters().any { it.key == "$deviceId::$alarmType" && it.muted }
    }

    suspend fun isHidden(deviceId: String, alarmType: String): Boolean {
        return getFilters().any { it.key == "$deviceId::$alarmType" && it.hidden }
    }

    private suspend fun saveFilters(filters: List<AlarmFilter>) {
        val arr = JSONArray()
        filters.forEach { f ->
            arr.put(JSONObject().apply {
                put("deviceId", f.deviceId)
                put("deviceName", f.deviceName)
                put("alarmType", f.alarmType)
                put("muted", f.muted)
                put("hidden", f.hidden)
            })
        }
        context.dataStore.edit { prefs -> prefs[KEY_FILTERS] = arr.toString() }
    }

    private fun parseFilters(json: String): List<AlarmFilter> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AlarmFilter(
                    deviceId   = o.optString("deviceId"),
                    deviceName = o.optString("deviceName"),
                    alarmType  = o.optString("alarmType"),
                    muted      = o.optBoolean("muted"),
                    hidden     = o.optBoolean("hidden")
                )
            }
        } catch (e: Exception) { emptyList() }
    }
}
