class SessionStore {

    private var accessToken: String? = null
    private var refreshToken: String? = null

    fun getAccessToken() = accessToken
    fun getRefreshToken() = refreshToken

    fun saveTokens(token: String, refresh: String) {
        accessToken = token
        refreshToken = refresh
    }
}