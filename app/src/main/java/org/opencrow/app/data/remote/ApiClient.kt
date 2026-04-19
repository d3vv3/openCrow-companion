package org.opencrow.app.data.remote

import android.util.Log
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    suspend fun initialize() {
        val server = configDao.get("server") ?: return
        val access = configDao.get("accessToken") ?: return
        val refresh = configDao.get("refreshToken") ?: return
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
                val refreshed = refreshTokenSync()
                if (refreshed) {
                    val retry = chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer ${_accessToken.orEmpty()}")
                        .build()
                    return@Interceptor chain.proceed(retry)
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

    private fun refreshTokenSync(): Boolean {
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
                // Persist refreshed tokens to DB so they survive app restarts
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        configDao.set(AppConfig("accessToken", authResp.tokens.accessToken))
                        configDao.set(AppConfig("refreshToken", authResp.tokens.refreshToken))
                    } catch (e: Exception) {
                        Log.w("ApiClient", "Failed to persist refreshed tokens: ${e.message}")
                    }
                }
                true
            } else false
        } catch (e: Exception) {
            Log.w("ApiClient", "Token refresh failed: ${e.message}")
            false
        }
    }

    suspend fun persistCurrentTokens() {
        val access = _accessToken ?: return
        val refresh = _refreshToken ?: return
        configDao.set(AppConfig("accessToken", access))
        configDao.set(AppConfig("refreshToken", refresh))
    }
}
