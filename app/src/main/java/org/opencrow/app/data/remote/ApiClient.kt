package org.opencrow.app.data.remote

import android.util.Log
import com.google.gson.GsonBuilder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.opencrow.app.data.local.ConfigDao
import org.opencrow.app.data.local.entity.AppConfig
import org.opencrow.app.data.remote.dto.RefreshRequest
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class ApiClient(private val configDao: ConfigDao) {

    private var _api: OpenCrowApi? = null
    private var _baseUrl: String? = null
    private var _accessToken: String? = null
    private var _refreshToken: String? = null

    val api: OpenCrowApi get() = _api ?: throw IllegalStateException("API not initialized. Scan QR first.")
    val isConfigured: Boolean get() = _api != null

    /** Emits Unit whenever a token refresh fails permanently (session expired / revoked). */
    private val _authExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val authExpired: SharedFlow<Unit> = _authExpired.asSharedFlow()

    suspend fun initialize() {
        val server = configDao.get("server")
        val access = configDao.get("accessToken")
        val refresh = configDao.get("refreshToken")
        Log.i("ApiClient", "initialize(): server=${server != null}, access=${access != null} (len=${access?.length ?: 0}), refresh=${refresh != null} (len=${refresh?.length ?: 0})")
        if (server == null || access == null || refresh == null) return
        configure(server, access, refresh)
    }

    fun configure(baseUrl: String, accessToken: String, refreshToken: String) {
        _baseUrl = baseUrl.trimEnd('/')
        _accessToken = accessToken
        _refreshToken = refreshToken
        buildClient()
    }

    suspend fun saveTokens(server: String, accessToken: String, refreshToken: String, deviceId: String) {
        configDao.set(AppConfig("server", server))
        configDao.set(AppConfig("accessToken", accessToken))
        configDao.set(AppConfig("refreshToken", refreshToken))
        configDao.set(AppConfig("deviceId", deviceId))
    }

    suspend fun getDeviceId(): String? = configDao.get("deviceId")
    suspend fun getServer(): String? = configDao.get("server")
    suspend fun getPushEndpoint(): String? = configDao.get("pushEndpoint")
    suspend fun savePushEndpoint(endpoint: String) {
        if (endpoint.isEmpty()) {
            configDao.delete("pushEndpoint")
        } else {
            configDao.set(AppConfig("pushEndpoint", endpoint))
        }
    }

    fun getAccessToken(): String? = _accessToken
    fun getBaseUrl(): String? = _baseUrl

    private fun buildClient() {
        val authInterceptor = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Authorization", "Bearer ${_accessToken.orEmpty()}")
                .build()
            val response = chain.proceed(request)

            if (response.code == 401 && _refreshToken != null) {
                response.close()
                when (refreshTokenSync()) {
                    RefreshResult.SUCCESS -> {
                        val retry = chain.request().newBuilder()
                            .addHeader("Authorization", "Bearer ${_accessToken.orEmpty()}")
                            .build()
                        return@Interceptor chain.proceed(retry)
                    }
                    RefreshResult.SERVER_REJECT -> {
                        // Server explicitly rejected the refresh token (revoked/expired).
                        // Notify UI to redirect to QR scan.
                        Log.w("ApiClient", "Refresh token rejected by server, emitting authExpired")
                        _authExpired.tryEmit(Unit)
                        return@Interceptor chain.proceed(request)
                    }
                    RefreshResult.NETWORK_ERROR -> {
                        // Transient network failure - don't log the user out.
                        // Return the original 401 response so the caller can handle it.
                        Log.w("ApiClient", "Refresh failed due to network error, not logging out")
                        return@Interceptor chain.proceed(request)
                    }
                }
            }
            response
        }

        val logging = HttpLoggingInterceptor().apply {
            level = if (org.opencrow.app.BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                    else HttpLoggingInterceptor.Level.NONE
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        val gson = GsonBuilder().setLenient().create()

        _api = Retrofit.Builder()
            .baseUrl("${_baseUrl}/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(OpenCrowApi::class.java)
    }

    /**
     * Attempts to refresh the access token synchronously (called from OkHttp interceptor thread).
     * Returns:
     *   RefreshResult.SUCCESS       - tokens refreshed and persisted
     *   RefreshResult.SERVER_REJECT - server responded with a non-2xx (token revoked / expired)
     *   RefreshResult.NETWORK_ERROR - could not reach server (transient, do NOT log out)
     */
    private enum class RefreshResult { SUCCESS, SERVER_REJECT, NETWORK_ERROR }

    private fun refreshTokenSync(): RefreshResult {
        return try {
            val plainClient = OkHttpClient.Builder().build()
            val gson = GsonBuilder().create()
            val body = gson.toJson(RefreshRequest(_refreshToken!!))
            val request = okhttp3.Request.Builder()
                .url("${_baseUrl}/v1/auth/refresh")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val resp = plainClient.newCall(request).execute()
            if (resp.isSuccessful) {
                val authResp = gson.fromJson(
                    resp.body?.string(),
                    org.opencrow.app.data.remote.dto.AuthResponse::class.java
                )
                _accessToken = authResp.tokens.accessToken
                _refreshToken = authResp.tokens.refreshToken
                // Persist refreshed tokens synchronously so they survive app restarts
                // even if the process is killed immediately after this call returns.
                runBlocking {
                    try {
                        configDao.set(AppConfig("accessToken", authResp.tokens.accessToken))
                        configDao.set(AppConfig("refreshToken", authResp.tokens.refreshToken))
                    } catch (e: Exception) {
                        Log.w("ApiClient", "Failed to persist refreshed tokens: ${e.message}")
                    }
                }
                RefreshResult.SUCCESS
            } else {
                Log.w("ApiClient", "Token refresh rejected by server: HTTP ${resp.code}")
                RefreshResult.SERVER_REJECT
            }
        } catch (e: Exception) {
            Log.w("ApiClient", "Token refresh network error (transient): ${e.message}")
            RefreshResult.NETWORK_ERROR
        }
    }

    suspend fun clearTokens() {
        configDao.delete("server")
        configDao.delete("accessToken")
        configDao.delete("refreshToken")
        configDao.delete("deviceId")
        _api = null
        _baseUrl = null
        _accessToken = null
        _refreshToken = null
    }

    suspend fun isOnboardingDone(): Boolean = configDao.get("onboardingDone") != null
    suspend fun setOnboardingDone() { configDao.set(AppConfig("onboardingDone", "true")) }

    suspend fun persistCurrentTokens() {
        val access = _accessToken ?: return
        val refresh = _refreshToken ?: return
        configDao.set(AppConfig("accessToken", access))
        configDao.set(AppConfig("refreshToken", refresh))
    }
}
