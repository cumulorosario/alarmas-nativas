package com.cumulo.vigia.model

data class Alarm(
    val id: AlarmId,
    val createdTime: Long,
    val type: String,
    val severity: String,
    val status: String,
    val originatorName: String
) {
    val isActive: Boolean get() = status.startsWith("ACTIVE")
    val isAcknowledged: Boolean get() = status.contains("ACK") && !status.contains("UNACK")
    val isCleared: Boolean get() = status.startsWith("CLEARED")
    val isCritical: Boolean get() = severity == "CRITICAL" || severity == "MAJOR"

    fun displayStatus(): String = when (status) {
        "ACTIVE_UNACK" -> "ACTIVA"
        "ACTIVE_ACK"   -> "RECONOCIDA"
        "CLEARED_UNACK" -> "DESPEJADA"
        "CLEARED_ACK"   -> "FINALIZADA"
        else -> status
    }

    fun displayType(): String = type.replace("_", " ")
}

data class AlarmId(val id: String)

data class Device(
    val id: DeviceId,
    val name: String,
    val type: String,
    val online: Boolean = false
)

data class DeviceId(val id: String, val entityType: String = "DEVICE")

data class AuthResponse(
    val token: String,
    val refreshToken: String
)

data class UserInfo(
    val authority: String,
    val tenantId: TenantId?,
    val customerId: CustomerId?
)

data class TenantId(val id: String)
data class CustomerId(val id: String)

sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val message: String, val cause: Exception? = null) : Result<Nothing>()
    object Loading : Result<Nothing>()
}
