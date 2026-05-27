package me.pluralware.shared.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-format DTOs for PluralKit API v2. Kept distinct from the domain
 * [me.pluralware.shared.model.Member] / [me.pluralware.shared.model.Switch]
 * for three reasons:
 *
 *  1. The API returns far more fields than the watch UI cares about. Mapping at
 *     the boundary keeps the domain model intentionally narrow.
 *  2. The API uses snake_case (`display_name`, `avatar_url`); idiomatic Kotlin
 *     uses camelCase. `@SerialName` keeps the JSON contract and the Kotlin name
 *     diverged on purpose.
 *  3. `Switch.members` is polymorphic on the wire — full Member objects in
 *     `/fronters` and `POST /switches` responses, but short-ID strings in
 *     `GET /switches`. Modelling that as two DTOs ([SwitchFullDto] /
 *     [SwitchRefsDto]) is clearer than a single union type.
 */

@Serializable
internal data class SystemDto(
    val id: String,
    val uuid: String,
    val name: String? = null,
    val tag: String? = null,
)

@Serializable
internal data class MemberDto(
    val id: String,
    val uuid: String,
    val name: String,
    @SerialName("display_name") val displayName: String? = null,
    val pronouns: String? = null,
    val color: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

/**
 * Switch with fully-expanded member objects.
 * Returned by `GET /systems/{ref}/fronters` and `POST /systems/{ref}/switches`.
 *
 * Note: `id` on the wire is the switch's UUID despite the field name — the
 * Switch model in the docs lists `id: uuid`. We name our domain field [Switch.uuid]
 * to make that explicit.
 */
@Serializable
internal data class SwitchFullDto(
    val id: String,
    val timestamp: String,
    val members: List<MemberDto>,
)

/**
 * Switch with member references only (short IDs).
 * Returned by `GET /systems/{ref}/switches` (the history endpoint).
 * The client resolves these against the member list before handing back a
 * domain [me.pluralware.shared.model.Switch].
 */
@Serializable
internal data class SwitchRefsDto(
    val id: String,
    val timestamp: String,
    val members: List<String>,
)

/**
 * Body for `POST /systems/@me/switches`.
 *  - `members` accepts either short IDs or UUIDs; we use UUIDs for stability.
 *  - Empty `members` list registers a switch-out.
 *  - `timestamp` is optional; omitting it (we always do) defaults to "now"
 *    server-side, which avoids client/server clock-skew bugs.
 */
@Serializable
internal data class CreateSwitchRequest(
    val members: List<String>,
    val timestamp: String? = null,
)
