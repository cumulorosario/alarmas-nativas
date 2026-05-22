var token = sessionStore.getAccessToken()
var response = api.getAlarms("Bearer $token").execute()

if (response.code() == 401) {

    val refreshed = repository.refreshTokenBlocking()

    if (refreshed) {
        token = sessionStore.getAccessToken()
        response = api.getAlarms("Bearer $token").execute()
    } else {
        stopSelf()
    }
}