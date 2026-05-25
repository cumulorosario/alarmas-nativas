package com.cumulo.vigia.util

/**
 * Traduce mensajes de error del servidor ThingsBoard (en inglés) al español.
 * También traduce errores de red y del sistema Android.
 */
object ErrorTranslator {

    fun translate(message: String?): String {
        if (message.isNullOrBlank()) return "Error desconocido"
        val lower = message.lowercase()
        return when {
            // Autenticación y sesión
            lower.contains("token has expired") || lower.contains("token is expired") ||
            lower.contains("jwt expired")
                -> "La sesión expiró. Reconectando..."
            lower.contains("bad credentials") || lower.contains("authentication failed") ||
            lower.contains("invalid credentials") || lower.contains("invalid username or password")
                -> "Usuario o contraseña incorrectos"
            lower.contains("user not found") || lower.contains("account not found")
                -> "Usuario no encontrado"
            lower.contains("account disabled") || lower.contains("user is disabled")
                -> "Cuenta deshabilitada. Contactá al administrador"
            lower.contains("account locked") || lower.contains("user is locked")
                -> "Cuenta bloqueada. Contactá al administrador"
            lower.contains("refresh token") || lower.contains("refresh failed")
                -> "Error al renovar sesión. Iniciá sesión nuevamente"
            lower.contains("unauthorized") || lower.contains("401")
                -> "Sesión inválida. Iniciá sesión nuevamente"
            lower.contains("forbidden") || lower.contains("access denied") ||
            lower.contains("permission denied") || lower.contains("403")
                -> "Sin permisos para esta acción"
            // Recursos
            lower.contains("not found") || lower.contains("404")
                -> "Recurso no encontrado en el servidor"
            lower.contains("already acknowledged") || lower.contains("already cleared") ||
            lower.contains("alarm already")
                -> "La alarma ya fue procesada"
            // Servidor
            lower.contains("internal server error") || lower.contains("500")
                -> "Error interno del servidor. Intentá más tarde"
            lower.contains("service unavailable") || lower.contains("503")
                -> "Servidor no disponible. Intentá más tarde"
            lower.contains("bad gateway") || lower.contains("502")
                -> "Error de conexión con el servidor"
            lower.contains("gateway timeout") || lower.contains("504")
                -> "El servidor tardó demasiado en responder"
            lower.contains("too many requests") || lower.contains("429")
                -> "Demasiadas solicitudes. Esperá unos segundos"
            // Red
            lower.contains("unable to resolve host") || lower.contains("no address associated")
                -> "No se puede alcanzar el servidor. Verificá la conexión"
            lower.contains("connection refused") || lower.contains("econnrefused")
                -> "Conexión rechazada. Verificá que el servidor esté activo"
            lower.contains("timeout") || lower.contains("timed out") ||
            lower.contains("sockettimeout")
                -> "Tiempo de espera agotado. Verificá la conexión"
            lower.contains("connection reset") || lower.contains("econnreset")
                -> "Conexión interrumpida. Intentá nuevamente"
            lower.contains("network is unreachable") || lower.contains("enetunreach")
                -> "Sin acceso a la red"
            lower.contains("failed to connect") || lower.contains("network error")
                -> "Sin conexión a Internet"
            lower.contains("ssl") || lower.contains("certificate") || lower.contains("handshake")
                -> "Error de seguridad en la conexión"
            lower.contains("software caused connection abort")
                -> "La conexión fue interrumpida"
            // Parseo
            lower.contains("json") || lower.contains("parse") || lower.contains("malformed")
                -> "Error al procesar la respuesta del servidor"
            // Código HTTP genérico
            Regex("error \\d{3}").containsMatchIn(lower)
                -> "Error del servidor (${Regex("\\d{3}").find(message)?.value ?: "?"})"
            // Fallback — limpiar jerga técnica
            else -> sanitize(message)
        }
    }

    private fun sanitize(message: String): String {
        val lowerMsg = message.lowercase()
        val spanishWords = listOf("error", "alarma", "dispositivo", "sesión",
            "conexión", "servidor", "usuario", "contraseña", "red")
        if (spanishWords.any { lowerMsg.contains(it) }) return message
        val techTerms = listOf("exception", "stacktrace", "null pointer",
            "java.", "kotlin.", "okhttp", "coroutine")
        if (techTerms.any { lowerMsg.contains(it) }) return "Error inesperado. Intentá nuevamente"
        return message.replaceFirstChar { it.uppercase() }
    }
}
