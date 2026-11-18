package io.github.jqssun.gpssetter.access

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

// ---------- Request / response models ----------

data class RequestAccessBody(
    val name: String,
    val email: String,
    val mobile: String,
    val city: String,
    val country: String,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("real_address") val realAddress: String
)

data class RequestAccessResponse(
    val success: Boolean? = null,
    @SerializedName("request_id") val requestId: Int? = null,
    val error: String? = null,
    val message: String? = null
)

data class CheckStatusBody(
    @SerializedName("request_id") val requestId: Int,
    @SerializedName("device_id") val deviceId: String
)

data class CheckStatusResponse(
    val status: String? = null,
    val token: String? = null
)

data class VerifyBody(
    val token: String,
    @SerializedName("device_id") val deviceId: String
)

data class VerifyResponse(
    val success: Boolean? = null
)

data class RestoreSessionBody(
    @SerializedName("device_id") val deviceId: String
)

data class RestoreSessionResponse(
    val success: Boolean? = null,
    val token: String? = null
)

// ---------- Retrofit service ----------

interface AccessApi {
    @POST("api/request")
    suspend fun requestAccess(@Body body: RequestAccessBody): Response<RequestAccessResponse>

    @POST("api/check-status")
    suspend fun checkStatus(@Body body: CheckStatusBody): Response<CheckStatusResponse>

    @POST("api/verify")
    suspend fun verify(@Body body: VerifyBody): Response<VerifyResponse>

    @POST("api/restore-session")
    suspend fun restoreSession(@Body body: RestoreSessionBody): Response<RestoreSessionResponse>
}

object AccessClient {
    private const val BASE_URL = "https://khpalgps-access-api.developerzb-test.workers.dev/"

    val api: AccessApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AccessApi::class.java)
    }
}

/** Returns the JSON body even when the server answered with an error code (401, 403, ...). */
inline fun <reified T> Response<T>.parsedBody(): T? =
    body() ?: try {
        Gson().fromJson(errorBody()?.string(), T::class.java)
    } catch (e: Exception) {
        null
    }
