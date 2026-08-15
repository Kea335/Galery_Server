package com.kadr.app.data.remote

import com.kadr.app.BuildConfig
import com.kadr.app.data.prefs.SettingsStore
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The server address is entered by the user, so Retrofit cannot be built once at
 * startup. This hands out a client for whatever address is configured now and
 * rebuilds only when that address changes.
 */
@Singleton
class ApiProvider @Inject constructor(
    private val settings: SettingsStore,
    private val json: Json,
) {

    /**
     * Shared with Coil so thumbnails carry the same bearer token — §13 serves
     * no media, thumbnails included, without one.
     */
    val httpClient: OkHttpClient get() = client

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor())
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            // BODY would dump whole chunks into logcat.
                            level = HttpLoggingInterceptor.Level.BASIC
                        },
                    )
                }
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // A phone on weak Wi-Fi can take a while to push an 8 MB chunk.
            .writeTimeout(5, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Volatile
    private var cached: Pair<String, KadrApi>? = null

    /** @throws IllegalStateException when no server address has been set yet. */
    fun api(): KadrApi = apiFor(settings.current.serverUrl)

    fun apiFor(rawUrl: String): KadrApi {
        val baseUrl = normalize(rawUrl)
        cached?.let { (url, api) -> if (url == baseUrl) return api }

        val api = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(KadrApi::class.java)

        cached = baseUrl to api
        return api
    }

    private fun authInterceptor() = Interceptor { chain ->
        val token = settings.current.token
        val request = if (token.isBlank()) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        chain.proceed(request)
    }

    companion object {
        /**
         * Accepts "192.168.1.8:8787", "kadr.lan" or a full URL and returns
         * something Retrofit will take — scheme present, trailing slash.
         */
        fun normalize(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            check(trimmed.isNotEmpty()) { "No server address configured yet." }

            val withScheme = when {
                trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
                else -> "http://$trimmed"
            }
            return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
        }
    }
}
