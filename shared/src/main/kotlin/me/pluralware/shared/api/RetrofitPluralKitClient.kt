package me.pluralware.shared.api

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.pluralware.shared.api.dto.CreateSwitchRequest
import me.pluralware.shared.api.dto.MemberDto
import me.pluralware.shared.api.dto.SwitchFullDto
import me.pluralware.shared.api.dto.SwitchRefsDto
import me.pluralware.shared.api.dto.SystemDto
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.Switch
import me.pluralware.shared.model.SystemInfo
import java.time.Instant

/**
 * [PluralKitClient] backed by Retrofit + OkHttp + kotlinx-serialization.
 *
 * Construction goes through [PluralKitClientFactory.create] so that the OkHttp
 * stack (auth interceptor, JSON converter, base URL) is wired consistently.
 * Tests can instantiate this directly with a fake [PluralKitApi].
 *
 * ## History-resolver cache
 *
 * `GET /systems/@me/switches` returns members as UUID strings rather than full
 * Member objects. The domain [Switch] expects expanded [Member]s, so we maintain
 * a short-lived lookup cache here. The repository has its own member cache for
 * the UI; this one exists purely to translate switch-history payloads without
 * coupling to the repository.
 *
 * The cache is populated lazily, then refreshed whenever we encounter an
 * unknown member ID (e.g. a new member added since the cache was warmed). If
 * the refresh still doesn't find them — which can happen if the member was
 * deleted — we drop that ID from the resulting [Switch.members] list rather
 * than surface a half-broken history entry.
 */
class RetrofitPluralKitClient internal constructor(
    private val api: PluralKitApi,
) : PluralKitClient {

    private val cacheMutex = Mutex()
    private var memberCache: Map<String, Member>? = null

    override suspend fun getOwnSystem(): SystemInfo = api.getOwnSystem().toDomain()

    override suspend fun getOwnMembers(): List<Member> {
        val members = api.getOwnMembers().map { it.toDomain() }
        // Opportunistically warm the resolver cache — same network round-trip,
        // saves a future fetch when getRecentSwitches() needs the lookup.
        cacheMutex.withLock { memberCache = buildMemberLookup(members) }
        return members
    }

    override suspend fun getCurrentFronters(): Switch? {
        val response = api.getCurrentFronters()
        // 204 = no switches ever registered for this system. Per the API docs
        // this is a documented success state, not an error.
        if (response.code() == 204) return null
        if (!response.isSuccessful) {
            throw PluralKitHttpException(response.code(), response.message())
        }
        val body = response.body() ?: return null
        return body.toDomain()
    }

    override suspend fun getRecentSwitches(limit: Int): List<Switch> {
        // The API caps at 100; defend against callers that didn't read the doc.
        val safeLimit = limit.coerceIn(1, 100)
        val refs = api.getRecentSwitches(safeLimit)
        if (refs.isEmpty()) return emptyList()

        // Build the lookup. If any switch references members we don't know about,
        // refresh the member cache exactly once before giving up.
        val lookup = ensureMemberLookup()
        val unknownIds = refs.flatMap { it.members }.filter { it !in lookup }
        val resolvedLookup = if (unknownIds.isEmpty()) lookup else refreshMemberLookup()

        return refs.map { it.toDomain(resolvedLookup) }
    }

    override suspend fun registerSwitch(memberUuids: List<String>): Switch {
        // PluralKit's POST accepts either short IDs or UUIDs. We send UUIDs
        // because the pickers track members by UUID — the stable identifier.
        val switch = api.createSwitch(CreateSwitchRequest(members = memberUuids))
            .toDomain()
        // Server returned full Member objects in the response; refresh the cache
        // so any newly-added members from the response are visible to history.
        cacheMutex.withLock {
            val merged = (memberCache ?: emptyMap()).toMutableMap()
            switch.members.forEach { m ->
                merged[m.uuid] = m
                merged[m.id] = m
            }
            memberCache = merged
        }
        return switch
    }

    // --- Member-cache helpers ---

    private suspend fun ensureMemberLookup(): Map<String, Member> {
        cacheMutex.withLock {
            memberCache?.let { return it }
        }
        return refreshMemberLookup()
    }

    private suspend fun refreshMemberLookup(): Map<String, Member> {
        val fresh = buildMemberLookup(api.getOwnMembers().map { it.toDomain() })
        cacheMutex.withLock { memberCache = fresh }
        return fresh
    }

    /**
     * The history endpoint returns member references by short ID (e.g. "abcde"),
     * but other endpoints (and our own picker state) use UUIDs. We key the lookup
     * by both so resolution works regardless of which form the API returned —
     * short IDs and UUIDs don't collide on length, so the combined map is safe.
     */
    private fun buildMemberLookup(members: List<Member>): Map<String, Member> {
        val lookup = HashMap<String, Member>(members.size * 2)
        members.forEach { m ->
            lookup[m.uuid] = m
            lookup[m.id] = m
        }
        return lookup
    }

    // --- DTO → domain mappers ---

    private fun SystemDto.toDomain() = SystemInfo(
        id = id,
        uuid = uuid,
        name = name,
        tag = tag,
    )

    private fun MemberDto.toDomain() = Member(
        id = id,
        uuid = uuid,
        name = name,
        displayName = displayName,
        pronouns = pronouns,
        color = color,
        avatarUrl = avatarUrl,
    )

    private fun SwitchFullDto.toDomain() = Switch(
        // API field `id` is the switch's UUID per the docs ("id: uuid"). Our
        // domain field is named `uuid` to make that explicit.
        uuid = id,
        timestamp = Instant.parse(timestamp),
        members = members.map { it.toDomain() },
    )

    private fun SwitchRefsDto.toDomain(lookup: Map<String, Member>) = Switch(
        uuid = id,
        timestamp = Instant.parse(timestamp),
        // Drop unresolvable IDs rather than render a broken row. A subsequent
        // member-list refresh from the UI will recover them.
        members = members.mapNotNull { lookup[it] },
    )
}

/** Thrown for non-success HTTP responses that aren't part of normal API contract (e.g. 204). */
class PluralKitHttpException(
    val statusCode: Int,
    message: String,
) : RuntimeException("PluralKit API returned HTTP $statusCode: $message")
