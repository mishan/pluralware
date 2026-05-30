package me.pluralware.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records a baseline profile for the wear app's hot paths: cold-start of
 * MainActivity, FrontersScreen render + scroll, and HistoryScreen render +
 * scroll. ART then AOT-compiles those classes/methods on first launch, which
 * is what removes the JIT-driven jank from ScalingLazyColumn scrolling.
 *
 * Requires a Wear OS device or emulator connected to ADB. Run with:
 *
 *   ./gradlew :wear:generateReleaseBaselineProfile
 *
 * The producer runs against `nonMinifiedRelease`, which the consumer plugin
 * configures with `BuildConfig.BENCHMARK = true` so MainActivity seeds an
 * in-memory token + MockPluralKitClient instead of waiting for phone pairing.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()

        // Wait for FrontersScreen content. The mock client is zero-latency so
        // the loading spinner gives way to the header almost immediately, but
        // we give it a generous window for slower watch hardware.
        device.wait(
            Until.hasObject(By.textContains("fronting")),
            SCREEN_TIMEOUT_MS,
        )

        scrollVertically(repetitions = 3)

        // Navigate into the history list and scroll that too — same
        // ScalingLazyColumn paths, but the row layout differs (FlowRow of
        // MemberBadge per switch), and we want both compiled ahead of time.
        device.findObject(By.text("Recent switches"))?.click()
        device.wait(
            Until.hasObject(By.text("Recent switches")),
            SCREEN_TIMEOUT_MS,
        )
        scrollVertically(repetitions = 3)
    }

    /**
     * Raw swipe gestures — more reliable than UiObject2.fling() on Wear, where
     * the accessibility tree doesn't always expose ScalingLazyColumn as a
     * single scrollable node UiAutomator can pick up.
     */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.scrollVertically(repetitions: Int) {
        val centerX = device.displayWidth / 2
        val topY = (device.displayHeight * 0.20).toInt()
        val bottomY = (device.displayHeight * 0.80).toInt()
        repeat(repetitions) {
            device.swipe(centerX, bottomY, centerX, topY, SWIPE_STEPS)
            device.waitForIdle()
        }
        repeat(repetitions) {
            device.swipe(centerX, topY, centerX, bottomY, SWIPE_STEPS)
            device.waitForIdle()
        }
    }

    private companion object {
        // Matches :wear `defaultConfig.applicationId` for the release variant.
        const val TARGET_PACKAGE = "me.pluralware"
        const val SCREEN_TIMEOUT_MS = 5_000L
        // 20 steps ~= ~330ms swipe, enough to read as a real fling on Wear.
        const val SWIPE_STEPS = 20
    }
}
