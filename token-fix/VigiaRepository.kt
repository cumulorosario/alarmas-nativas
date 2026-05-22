fun refreshTokenBlocking(): Boolean {
    val refresh = sessionStore.getRefreshToken() ?: return false

    return try {
        val response = api.refreshToken(mapOf("refreshToken" to refresh)).execute()

        if (response.isSuccessful) {
            val body = response.body()
            if (body != null) {
                sessionStore.saveTokens(body.token, body.refreshToken)
                true
            } else false
        } else false

    } catch (e: Exception) {
        false
    }
}