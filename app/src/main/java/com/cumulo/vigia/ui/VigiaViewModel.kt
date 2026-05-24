package com.cumulo.vigia.ui

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cumulo.vigia.data.VigiaRepository
import com.cumulo.vigia.data.local.AlarmFilterStore
import com.cumulo.vigia.data.local.SessionStore
import com.cumulo.vigia.model.Alarm
import com.cumulo.vigia.model.Device
import com.cumulo.vigia.model.Result
import com.cumulo.vigia.service.AlarmNotificationManager
import com.cumulo.vigia.util.ErrorTranslator
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class LoginState(
    val username: String = "",
    val password: String = "",
    val rememberMe: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
)

data class DashboardState(
    val alarms: List<Alarm> = emptyList(),
    val devices: List<Device> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val lastUpdated: Long = 0L
) {
    val activeAlarms  get() = alarms.filter { it.isActive && !it.isCleared }
    val pendingAlarms get() = alarms.filter { !it.isCleared && (it.isActive || !it.isAcknowledged) }
    val onlineDevices get() = devices.count { it.online }
    val criticalCount get() = activeAlarms.count { it.isCritical }
}

private const val MAX_ALARMS = 20

class VigiaViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionStore  = SessionStore(application)
    private val repository    = VigiaRepository(sessionStore)
    private val filterStore   = AlarmFilterStore(application)

    private val _loginState = MutableStateFlow(LoginState())
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    private val _dashboardState = MutableStateFlow(DashboardState())
    val dashboardState: StateFlow<DashboardState> = _dashboardState.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    val alarmSettings = sessionStore.alarmSettingsFlow.stateIn(
        viewModelScope, SharingStarted.Eagerly, SessionStore.AlarmSettings()
    )
    val sessionInfo = sessionStore.sessionFlow.stateIn(
        viewModelScope, SharingStarted.Eagerly, SessionStore.Session()
    )

    init {
        viewModelScope.launch {
            val session = sessionStore.getSession()
            if (session.isLoggedIn) {
                _isAuthenticated.value = true
                _loginState.update {
                    it.copy(username = session.username, password = session.password, rememberMe = session.rememberMe)
                }
                loadData()
            } else if (session.rememberMe && session.username.isNotEmpty()) {
                _loginState.update {
                    it.copy(username = session.username, password = session.password, rememberMe = true)
                }
            }
        }
    }

    fun onUsernameChange(v: String) = _loginState.update { it.copy(username = v, error = null) }
    fun onPasswordChange(v: String) = _loginState.update { it.copy(password = v, error = null) }
    fun onRememberMeChange(v: Boolean) = _loginState.update { it.copy(rememberMe = v) }

    fun login() {
        val state = _loginState.value
        if (state.username.isBlank() || state.password.isBlank()) {
            _loginState.update { it.copy(error = "Ingresá usuario y contraseña") }
            return
        }
        viewModelScope.launch {
            _loginState.update { it.copy(isLoading = true, error = null) }
            when (val r = repository.login(state.username, state.password, state.rememberMe)) {
                is Result.Success -> { _isAuthenticated.value = true; loadData() }
                is Result.Error   -> _loginState.update { it.copy(isLoading = false, error = ErrorTranslator.translate(r.message)) }
                else -> {}
            }
            _loginState.update { it.copy(isLoading = false) }
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            _isAuthenticated.value = false
            _dashboardState.value = DashboardState()
        }
    }

    fun loadData(isRefresh: Boolean = false) {
        viewModelScope.launch {
            _dashboardState.update {
                if (isRefresh) it.copy(isRefreshing = true, error = null)
                else it.copy(isLoading = it.alarms.isEmpty(), error = null)
            }
            // Cargar alarmas
            launch {
                when (val r = repository.getAlarms()) {
                    is Result.Success -> {
                        // Aplicar filtros de ocultamiento globales
                        val filters = filterStore.getFilters()
                        val filtered = r.data
                            .filter { alarm ->
                                // Ocultar si hay un filtro hidden para este tipo
                                // (buscamos por tipo de alarma en cualquier dispositivo con ese nombre)
                                filters.none { f ->
                                    f.hidden && f.alarmType == alarm.type &&
                                    alarm.originatorName.equals(f.deviceName, ignoreCase = true)
                                }
                            }
                            .take(MAX_ALARMS)

                        _dashboardState.update {
                            it.copy(
                                alarms       = filtered,
                                isLoading    = false,
                                isRefreshing = false,
                                lastUpdated  = System.currentTimeMillis()
                            )
                        }
                    }
                    is Result.Error -> _dashboardState.update {
                        it.copy(isLoading = false, isRefreshing = false, error = ErrorTranslator.translate(r.message))
                    }
                    else -> {}
                }
            }
            // Cargar dispositivos
            launch {
                when (val r = repository.getDevices()) {
                    is Result.Success -> _dashboardState.update { it.copy(devices = r.data) }
                    else -> {}
                }
            }
        }
    }

    fun acknowledgeAlarm(alarmId: String) {
        viewModelScope.launch {
            _dashboardState.update { state ->
                state.copy(alarms = state.alarms.map { alarm ->
                    if (alarm.id.id == alarmId) alarm.copy(status = alarm.status.replace("UNACK", "ACK"))
                    else alarm
                })
            }
            repository.acknowledgeAlarm(alarmId)
            cancelNotification(alarmId)
            loadData(isRefresh = true)
        }
    }

    fun clearAlarm(alarmId: String) {
        viewModelScope.launch {
            _dashboardState.update { state ->
                state.copy(alarms = state.alarms.map { alarm ->
                    if (alarm.id.id == alarmId) alarm.copy(status = "CLEARED_ACK") else alarm
                })
            }
            repository.clearAlarm(alarmId)
            cancelNotification(alarmId)
            loadData(isRefresh = true)
        }
    }

    private fun cancelNotification(alarmId: String) {
        val notifId = AlarmNotificationManager.NOTIF_ALARM_BASE_ID +
            alarmId.hashCode().and(0x7FFFFFFF).rem(900)
        (getApplication<Application>()
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(notifId)
    }

    fun updateAlarmSettings(vibrate: Boolean, sound: Boolean, wake: Boolean) {
        viewModelScope.launch { sessionStore.saveAlarmSettings(vibrate, sound, wake) }
    }
}
