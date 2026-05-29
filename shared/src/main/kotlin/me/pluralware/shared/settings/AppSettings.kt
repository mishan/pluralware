package me.pluralware.shared.settings

/**
 * How often the watch home screen polls PluralKit for fronter changes while
 * it's visible. Configured on the phone and synced to the watch.
 *
 * [seconds] == 0 ([OFF]) disables polling; the home screen still refreshes on
 * resume and via the manual refresh button.
 */
enum class RefreshInterval(val seconds: Int) {
    OFF(0),
    SEC_30(30),
    MIN_1(60),
    MIN_5(300);

    companion object {
        val DEFAULT = OFF

        /** Map a stored seconds value back to a preset, falling back to [DEFAULT]. */
        fun fromSeconds(seconds: Int): RefreshInterval =
            entries.firstOrNull { it.seconds == seconds } ?: DEFAULT
    }
}

/** User-configurable app settings shared between phone and watch. */
data class AppSettings(
    val refreshInterval: RefreshInterval = RefreshInterval.DEFAULT,
)
