@POST("api/auth/token")
fun refreshToken(@Body body: Map<String, String>): Call<AuthResponse>

data class AuthResponse(
    val token: String,
    val refreshToken: String
)