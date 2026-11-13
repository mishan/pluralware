package me.pluralware.shared.api

import kotlinx.serialization.json.JsonObject
import me.pluralware.shared.api.dto.CreateSwitchRequest
import me.pluralware.shared.api.dto.MemberDto
import me.pluralware.shared.api.dto.MoveSwitchRequest
import me.pluralware.shared.api.dto.SwitchFullDto
import me.pluralware.shared.api.dto.SwitchRefsDto
import me.pluralware.shared.api.dto.SystemDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit-facing slice of the PluralKit v2 API.
 *
 * Scope is deliberately narrow — only the endpoints the watch / phone MVP
 * actually need. Add more as features land.
 *
 * Base URL is configured at the Retrofit level (`https://api.pluralkit.me/v2/`),
 * so paths here are relative and have no leading slash. Auth is injected via
 * the OkHttp `AuthInterceptor` configured in [PluralKitClientFactory].
 */
internal interface PluralKitApi {

    /** Sanity-check the auth token / system. */
    @GET("systems/@me")
    suspend fun getOwnSystem(): SystemDto

    /** Full member list. Cached at the repository layer; tens of KB at most. */
    @GET("systems/@me/members")
    suspend fun getOwnMembers(): List<MemberDto>

    /**
     * Current fronters.
     * Returns 204 No Content if the system has never registered a switch —
     * wrap in [Response] so we can detect that without an exception path.
     */
    @GET("systems/@me/fronters")
    suspend fun getCurrentFronters(): Response<SwitchFullDto>

    /**
     * Recent switches. The API caps `limit` at 100; the client clamps before calling.
     * Members are returned as short ID strings (not UUIDs) — resolved against the
     * member list in [RetrofitPluralKitClient].
     */
    @GET("systems/@me/switches")
    suspend fun getRecentSwitches(@Query("limit") limit: Int): List<SwitchRefsDto>

    /**
     * Log a new switch. Empty `members` list registers a switch-out.
     * Returns the created switch with full member objects.
     */
    @POST("systems/@me/switches")
    suspend fun createSwitch(@Body body: CreateSwitchRequest): SwitchFullDto

    /** A partial member: a key it leaves out stays as it is; one set to null is cleared. */
    @PATCH("members/{uuid}")
    suspend fun updateMember(@Path("uuid") uuid: String, @Body changes: JsonObject): MemberDto

    @PATCH("systems/@me/switches/{uuid}")
    suspend fun moveSwitch(@Path("uuid") uuid: String, @Body body: MoveSwitchRequest): SwitchFullDto

    @PATCH("systems/@me/switches/{uuid}/members")
    suspend fun setSwitchMembers(@Path("uuid") uuid: String, @Body memberUuids: List<String>): SwitchFullDto

    @DELETE("systems/@me/switches/{uuid}")
    suspend fun deleteSwitch(@Path("uuid") uuid: String): Response<Unit>
}
