package me.pluralware.shared.api

import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

private const val BASE_URL = "https://api.pluralkit.me/v2/"

/**
 * Sent as `User-Agent` on every request. PluralKit asks API consumers to set
 * a contactable UA so maintainers can reach out about misbehaving clients.
 *
 * TODO: replace the placeholder URL once we have a public issue tracker.
 */
private const val USER_AGENT = "PluralWare/0.1.0 (+https://github.com/TODO/pluralware)"

/**
 * Builds the production [PluralKitClient].
 *
 * Lives next to the client so the wiring is in one place — the apps just call
 * [PluralKitClientFactory.create] and get a ready-to-use client back.
 */
object PluralKitClientFactory {

    /**
     * Build a client for the given token. Each created client owns its own OkHttp
     * connection pool — for the watch app's traffic pattern (single user, sparse
     * requests) sharing one pool gives no meaningful benefit and complicates DI.
     *
     * @param enableLogging when true, attaches an [HttpLoggingInterceptor] at
     *   BODY level. Wire to BuildConfig.DEBUG at the call site — never enable
     *   in release builds (logs would include the bearer token).
     */
    fun create(
        token: PluralKitToken,
        enableLogging: Boolean = false,
    ): PluralKitClient {
        val okHttp = buildOkHttpClient(token, enableLogging)
        val retrofit = buildRetrofit(okHttp)
        return RetrofitPluralKitClient(retrofit.create(PluralKitApi::class.java))
    }

    private fun buildOkHttpClient(
        token: PluralKitToken,
        enableLogging: Boolean,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthAndUaInterceptor(token))
        .apply {
            if (enableLogging) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                    // Redact the token even in debug logs — convenient when
                    // sharing a logcat snippet with someone for help.
                    redactHeader("Authorization")
                })
            }
        }
        // Watch radios can be slow; PluralKit's API is on the wider internet.
        // These are generous but bounded to avoid hanging the UI indefinitely.
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun buildRetrofit(okHttp: OkHttpClient): Retrofit {
        // ignoreUnknownKeys: future PluralKit API additions don't break us.
        // coerceInputValues: a single unexpected null in a non-null field is
        //                    treated as the default, not a crash.
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttp)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }
}

/**
 * Adds the auth header and User-Agent to every request.
 *
 * PluralKit accepts the raw token in `Authorization` (no `Bearer ` prefix).
 * Per the API docs, that's the canonical form; prefixing with `Bearer` happens
 * to work today but isn't a documented contract.
 */
private class AuthAndUaInterceptor(
    private val token: PluralKitToken,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request().newBuilder()
            .header("Authorization", token.raw)
            .header("User-Agent", USER_AGENT)
            .build()
        return chain.proceed(request)
    }
}
