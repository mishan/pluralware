package me.pluralware.shared.model

import java.time.Instant

/**
 * A PluralKit system member, reduced to the fields the watch UI actually uses.
 *
 * Intentionally narrower than the full API model — adding fields later is cheap,
 * but every field here is one we promise to keep stable for the UI.
 */
data class Member(
    val id: String,           // 5–6 char short ID (the user-facing one).
    val uuid: String,         // Stable UUID; what we send to the API.
    val name: String,
    val displayName: String?, // Falls back to [name] when absent.
    val pronouns: String?,
    val color: String?,       // Hex without leading '#'.
    val avatarUrl: String?,
) {
    /** What the watch should actually render. */
    val displayLabel: String get() = displayName ?: name
}

/**
 * A switch — who is fronting and when it started.
 * Empty [members] means a switch-out (nobody fronting).
 */
data class Switch(
    val uuid: String,
    val timestamp: Instant,
    val members: List<Member>,
) {
    val isSwitchOut: Boolean get() = members.isEmpty()
}

/** Minimal system info, enough to confirm "yes the token works" on the auth screen. */
data class SystemInfo(
    val id: String,
    val uuid: String,
    val name: String?,
    val tag: String?,
)
