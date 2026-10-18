package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.After
import org.robolectric.shadows.ShadowLooper

/**
 * Each permission screen is a title, a picture, one line on what the permission does and
 * one line of small print. These pin those words and the invariants that survived every earlier rewrite: the M in
 * exactly one string, no route jargon, no copywriter's note, and no claim a two-screen device makes false.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OnboardingAccessScreenCopyTest {
    /**
     * This class runs in the shared sdk 34 Robolectric sandbox, and the copy it reads can write Compose snapshot state outside any
     * composition. Apply those writes and run what they posted, or a later Compose test in the sandbox fails "Compose did not get
     * idle" (CompanionThemeSeedTest, MimirTrackpadPointerStyleTest).
     */
    @After
    fun applyPendingSnapshotWrites() {
        Snapshot.sendApplyNotifications()
        ShadowLooper.idleMainLooper()
    }

    private val res = onboardingTestResources()

    private val pendingAccess = OnboardingAccessUi(
        usageAvailable = true,
        usageGranted = false,
        homeRoleAvailable = true,
        homeRoleHeld = false,
        filesGranted = false,
    )

    private val everyScreen: List<OnboardingAccessScreenCopy>
        get() = listOf(
            onboardingAccessFilesCopy(res),
            onboardingAccessUsageCopy(res, pendingAccess),
            onboardingAccessOverlayCopy(res),
            onboardingAccessHomeRoleCopy(res, pendingAccess),
        )

    @Test
    fun `every screen that can be granted shows a picture, says one short thing and one line of small print`() {
        everyScreen.forEach { copy ->
            assertNotNull(copy.plainName, copy.scene)
            assertTrue(copy.why, copy.why.split(" ").size <= 12)
            assertTrue(copy.refusal, copy.refusal.isNotBlank() && copy.refusal.split(" ").size <= 10)
            // The how-to-find-it-in-Android sentences went; ORIENTATION's cards teach that round trip.
            assertFalse(copy.why + copy.refusal, (copy.why + copy.refusal).contains("Allow opens"))
            assertFalse(copy.why + copy.refusal, (copy.why + copy.refusal).contains("route"))
        }
        // One example per permission.
        assertEquals(4, everyScreen.map { it.scene }.toSet().size)
    }

    @Test
    fun `files names the folders, keeps the art beside the games, and says where Not now sends you`() {
        val copy = onboardingAccessFilesCopy(res)
        // The confirmed difference is where folders are chosen, so the title names folders and
        // the supporting line locates artwork beside games.
        assertEquals("Let Mimir use your game folders", copy.plainName)
        assertEquals("For Mimir's own file manager, and to show and save your artwork.", copy.why)
        // Mimir's browser needs this access, and Android's picker is the fallback without it.
        assertEquals("Not now: you'll choose folders in Android's picker.", copy.refusal)
    }

    /**
     * Worded by screen count. Ground truth: selectMButtonOverlayTarget
     * (MButtonRuntime.kt) returns the second-screen companion BEFORE it consults usage access, so only a one-screen
     * handheld needs this grant for the Mimir button, and only its words may say so. The claim is absence, never lag.
     */
    @Test
    fun `usage on one screen is one cause and its effect, naming the Mimir button`() {
        val copy = onboardingAccessUsageCopy(res, pendingAccess)
        assertEquals("Let Mimir see when a game is running", copy.plainName)
        assertEquals("So the Mimir button can show over your games.", copy.why)
        assertEquals("Not now: no Mimir button on this handheld.", copy.refusal)
        assertEquals(OnboardingHintScene.ACCESS_USAGE, copy.scene)
        assertFalse(copy.refusal.contains("lag"))
        assertFalse(copy.refusal.contains("works fully without it"))
    }

    @Test
    fun `usage on two screens makes no claim about the button and has no small print`() {
        val copy = onboardingAccessUsageCopy(res, pendingAccess.copy(hasSecondaryDisplay = true))
        assertEquals("Let Mimir see when a game is running", copy.plainName)
        assertEquals("Mimir sees when you leave a game.", copy.why)
        assertEquals("", copy.refusal)
        assertEquals(OnboardingHintScene.ACCESS_USAGE, copy.scene)
        assertFalse(copy.why.contains("button"))
    }

    @Test
    fun `where there is nothing to grant, the screen says so and shows no picture`() {
        val usage = onboardingAccessUsageCopy(res, pendingAccess.copy(usageAvailable = false))
        assertEquals("This handheld doesn’t offer Usage access.", usage.why)
        assertNull(usage.scene)
        assertEquals("", usage.refusal)
        val home = onboardingAccessHomeRoleCopy(res, pendingAccess.copy(homeRoleAvailable = false))
        assertEquals("This handheld doesn’t offer a Home app choice.", home.why)
        assertEquals("Make Mimir your Home app", home.plainName)
        assertNull(home.scene)
    }

    @Test
    fun `home role keeps the notification clause, without the permanence claim`() {
        // The notification clause: never "never offers this again", no provenance tags.
        val copy = onboardingAccessHomeRoleCopy(res, pendingAccess)
        // Android's own name for the role ; FINISH's row says the same.
        assertEquals("Make Mimir your Home app", copy.plainName)
        assertEquals("Not now: open Mimir like any other app.", copy.refusal)
        assertFalse(copy.refusal.contains("never offers this again"))
        assertFalse(copy.refusal.contains("[V]"))
        assertFalse(copy.refusal.contains("UNKNOWN"))
    }

    // The Tweaker page's instruction moved to the Tweaker's usage row with OR-12 (2026-09-29); it is pinned there
    // ([OnboardingOptionalAsksMovedTest]).

    // The four built-in controls copy tests that stood here were deleted with their screen .
    // Settings and Utility still word the vendor's status for themselves.
    //
    // The two keyboard-copy tests that stood here were deleted with their screen (policy
    // 2026-09-17: the keyboard step is retired from onboarding). They pinned
    // `onboardingAccessKeyboardCopy`, which no longer exists.
    //
    // What they pinned that still matters is NOT lost: QA-07's finding -- on a two-screen handheld
    // Android's IME picker opens on the BOTTOM screen, which is why a working button was reported as unresponsive -- lives in `mimirKeyboardPickerMayOpenOnAnotherDisplay` and its own
    // `MimirKeyboardPickerDisplayTest`. That predicate still guards `showMimirKeyboardPicker`, which the
    // Settings row (`controls/keyboard`) presses. Only the onboarding wording went away.
}
