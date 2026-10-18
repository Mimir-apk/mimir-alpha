package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.PhysicalControl
import com.gruning.mimir.rewrite.core.input.defaultControllerKeymap
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.After
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OnboardingCopyTest {
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

    // Content review 2026-08-21 (A1/A4): three onboarding navigation strings named "Settings › Utility",
    // a Settings category that does not exist (SettingsIa.kt:26-33 lists Profile/Library/Emulators/
    // Appearance/Controls/About) -- each the sole recovery route offered after a decline. Source-text sweep
    // (pattern per GameTitleEditOverlayCopyTest) rather than per-function assertions, so this holds against
    // any future onboarding string, not just the three found this pass. "Settings › Controls › Utility" is
    // a real, TRUE route (SettingsDetail.kt:809-819) and does not match this substring.
    @Test
    fun noOnboardingStringNamesTheNonexistentSettingsUtilityCategory() {
        assertFalse(onboardingFlowSource().contains("Settings › Utility"))
        assertFalse(onboardingCoordinatorSource().contains("Settings › Utility"))
        // The words live in the string resources now (localization step 1): the sweep reaches every language of them too.
        listOf("values", "values-b+es+419").forEach { folder ->
            val strings = listOf(
                File("app/src/main/res/$folder/strings.xml"),
                File("src/main/res/$folder/strings.xml"),
            ).firstOrNull(File::isFile) ?: error("$folder/strings.xml not found from ${File(".").absolutePath}")
            assertFalse(strings.readText().contains("Settings › Utility"))
            assertFalse(strings.readText().contains("Configuración › Utilidad"))
        }
    }

    private fun onboardingFlowSource(): String = onboardingSourceFile("OnboardingFlow.kt").readText()

    private fun onboardingCoordinatorSource(): String =
        onboardingSourceFile("MimirOnboardingCoordinator.kt").readText()

    private fun onboardingSourceFile(name: String): File {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    @Test
    fun `name step controller hint stays A Select wherever focus is`() {
        // "A Select" stays stable across the field and other controls. The hint bar draws token
        // and verb separately; the stored verb uses sentence case.
        assertEquals("Select", res.getString(OnboardingProfileHintAction))
    }

    @Test
    fun `static states are stored in sentence case, and the finish eyebrow keeps its check`() {
        // Interface text uses sentence case without changing case in code.
        assertEquals("Setup complete", res.getString(R.string.app_onboarding_finish_eyebrow))
        assertEquals("✓ Setup complete", res.getString(R.string.app_onboarding_finish_eyebrow_visual))
        assertEquals("ᛗ · Choose a theme", res.getString(R.string.app_onboarding_theme_title))
        assertEquals("ᛗ · Your Home shelf", res.getString(R.string.app_onboarding_favorites_eyebrow))
        assertEquals("Setup paused", res.getString(R.string.app_onboarding_settling_phase_failed))
    }

    @Test
    fun `SteamGridDB onboarding copy reflects existing key state`() {
        val missing = onboardingSteamGridDbCopy(res, keyPresent = false)
        assertEquals(null, missing.status)
        assertEquals("Add key", missing.action)
        // Content review 2026-08-21 (A9): was the same sentence as the METADATA screen subtitle, recycled
        // verbatim, and never said what a key actually gets a player.
        // 2026-09-28: "later" is said once per topic, and it is not said here.
        assertEquals("Free box art, logos and backgrounds. Needs a free key from steamgriddb.com.", missing.detail)

        val present = onboardingSteamGridDbCopy(res, keyPresent = true)
        assertEquals("Added", present.status)
        assertEquals("Update key", present.action)
        // Row 21: a player whose key reads "Added" is no longer told to add one later.
        assertEquals("Free box art, logos and backgrounds, with your key.", present.detail)
    }

    @Test
    fun `paired onboarding actions stack on compact square rails`() {
        assertEquals(true, onboardingActionsStack(296f))
        assertEquals(true, onboardingActionsStack(372f))
        assertEquals(false, onboardingActionsStack(420f))
        assertEquals(false, onboardingActionsStack(776f))
    }

    @Test
    fun `compact square keyboard collapses the duplicate preview`() {
        assertEquals(true, onboardingKeyboardPreviewCompact(360f))
        assertEquals(true, onboardingKeyboardPreviewCompact(420f))
        assertEquals(false, onboardingKeyboardPreviewCompact(421f))
        assertEquals(false, onboardingKeyboardPreviewCompact(480f))
    }

    // completionPrimerTeachesOptInPathsIncludingTomes pinned the retired 5-cell primer grid, and the four taught
    // tail screens that replaced it  folded into one FINISH page on
    // 2026-09-28 (policy Q3). These pin FINISH: its words, its legend, and the bindings the legend names.

    @Test
    fun finishSaysWhereThePlayerGoesAndOffersTheHomeRowOnlyToAPlayerWhoSkippedIt() {
        // The title names the destination to make the final step specific.
        val copy = onboardingFinishCopy(res)
        assertEquals("Next: your Home shelf", copy.title)
        assertTrue(copy.homeRow.contains("Default apps"))
        // 2026-09-28: it names the Home app as that permission screen's title does.
        assertTrue(copy.homeRow.startsWith("Make Mimir your Home app"))
        assertFalse("one line, not two", copy.homeRow.contains(" or "))

        fun access(available: Boolean, held: Boolean) = OnboardingAccessUi(
            usageAvailable = true,
            usageGranted = true,
            homeRoleAvailable = available,
            homeRoleHeld = held,
        )
        assertTrue(onboardingFinishOffersHomeRow(access(available = true, held = false)))
        assertFalse("a player who made Mimir home is not told how", onboardingFinishOffersHomeRow(access(true, true)))
        assertFalse("a device without the role is not sent to look for it", onboardingFinishOffersHomeRow(access(false, false)))
    }

    /** 2026-09-28: the header strip's runes count the setup, one per stage. */
    @Test
    fun `the header runes light one per stage, all five on FINISH, and only their color changes`() {
        assertEquals(
            listOf(1, 2, 3, 4, 4, 5, 5),
            listOf(
                OnboardingStep.PROFILE,
                OnboardingStep.ACCESS,
                OnboardingStep.LIBRARY,
                OnboardingStep.METADATA,
                OnboardingStep.SETTLING,
                OnboardingStep.FAVORITES,
                OnboardingStep.DONE,
            ).map(::onboardingSetupRunesLit),
        )
        val red = androidx.compose.ui.graphics.Color.Red
        val two = onboardingRuneEyebrow(lit = 2, litColor = red)
        assertEquals("the words never change, so the strip's measure never does", MimirRuneEyebrow, two.text)
        assertEquals(listOf(0 until 2), two.spanStyles.map { it.start until it.end })
        assertTrue(onboardingRuneEyebrow(lit = 0, litColor = red).spanStyles.isEmpty())
        val all = onboardingRuneEyebrow(lit = 9, litColor = red)
        assertEquals("never past the fifth rune", 5, all.spanStyles.single().end)
    }

    @Test
    fun finishLegendNamesTheMButtonExceptOnAOneScreenChassisWithoutUsageAccess() {
        // Ground truth is selectMButtonOverlayTarget (MButtonRuntime.kt:206-220): a presentation display
        // yields SECONDARY_COMPANION before usage access is consulted, and the one-screen path needs usage
        // access. So there is exactly ONE state in which the button cannot exist, and it is the one the
        // USAGE screen's refusal copy already describes -- naming it there contradicts what was just said.
        assertFalse(onboardingTeachesMButton(hasSecondaryDisplay = false, usageGranted = false))
        assertTrue(onboardingTeachesMButton(hasSecondaryDisplay = true, usageGranted = false))
        assertTrue(onboardingTeachesMButton(hasSecondaryDisplay = false, usageGranted = true))
        assertTrue(onboardingTeachesMButton(hasSecondaryDisplay = true, usageGranted = true))

        assertEquals(listOf("X", "Y", "M", "L3"), onboardingFinishLegend(res, teachMButton = true).map { it.key })
        assertEquals(listOf("X", "Y", "L3"), onboardingFinishLegend(res, teachMButton = false).map { it.key })
    }

    /**
     * QA-14 , carried from the tail into the legend. The tail taught "press Y" for
     * adding a game to the Home shelf for three weeks after the binding moved to SELECT on 2026-08-25
     * (`LibraryDispatch.kt` :241-243, :739-745), because its test pinned wording rather than the binding.
     * Every entry is asserted here against the dispatch that implements it, so a future key move fails this
     * test rather than shipping silently again.
     */
    @Test
    fun everyOnboardingControlTokenMatchesTheDispatchThatImplementsIt() {
        val legend = onboardingFinishLegend(res, teachMButton = true).associate { it.key to it.action }

        // SELECT adds to the Home shelf in a gamelist but opens achievements on Home, where FINISH leads, so
        // FINISH does not teach it.
        assertFalse("SELECT" in legend)
        assertFalse("Y has not added a game to the Home shelf since 2026-08-25", legend.getValue("Y").contains("shel"))
        // X on Home opens the Tome library (HomeShelfModels SECONDARY -> onTomes).
        assertEquals("Open Tomes", legend["X"])
        // Y on a Home game opens its tile menu (TERTIARY): art, Tomes, achievements -- Home's own hint says EDIT.
        assertEquals("Edit a game", legend["Y"])
        assertEquals("Hold for menu", legend["M"])
        // L3 is SHOW_HINTS out of the box. It toggles the Hints legend on the Library shelf and in the gamelist
        // (LibraryDispatch's two SHOW_HINTS arms) and nothing on Home, so the row says where it works (policy
        // 2026-09-30, after walk-32e).
        assertEquals(ControllerAction.SHOW_HINTS, defaultControllerKeymap()[PhysicalControl.L3])
        assertEquals(res.getString(R.string.app_onboarding_hint_show_hints), legend["L3"])
        assertTrue(legend.getValue("L3").endsWith("in the Library"))
    }

    /**
     * QA-25 in the legend (2026-09-28): "shelf" means only the Home surface, so a
     * Tome is never glossed as one. QA-13's provider sentence left with the MEDIA page; the offline promise it
     * carried is still said on ORIENTATION ("scanning and launching your games never use the internet").
     */
    /** the list follows the player's own pad names, as Home's hints do; the floating M never does. */
    @Test
    fun finishListUsesThePadsOwnNamesAndDropsAKeyThePadHides() {
        val remapped = com.gruning.mimir.rewrite.core.ui.MimirControllerHintLabels(
            byFactoryToken = mapOf("X" to "Y", "Y" to "", "M" to "Menu", "L3" to "R3"),
        )
        val list = onboardingFinishLegend(res, teachMButton = true, labels = remapped)
        assertEquals(listOf("Y", "M", "R3"), list.map { it.key })
        assertEquals(listOf("Open Tomes", "Hold for menu", res.getString(R.string.app_onboarding_hint_show_hints)), list.map { it.action })
        // Nothing bound to SHOW_HINTS: no hints row, rather than a row naming a button that does nothing.
        val unbound = com.gruning.mimir.rewrite.core.ui.MimirControllerHintLabels(byFactoryToken = mapOf("L3" to ""))
        val unboundList = onboardingFinishLegend(res, teachMButton = true, labels = unbound)
        assertFalse(unboundList.any { it.action == res.getString(R.string.app_onboarding_hint_show_hints) })
    }

    @Test
    fun finishLegendNeverCallsATomeAShelf() {
        val tomes = onboardingFinishLegend(res, teachMButton = true).single { it.key == "X" }
        assertFalse(tomes.action.contains("shel"))
    }

    /**
     * QA-15: accent the action token, never the sentence. The rule's whole job is to be un-fooled by
     * ordinary English, so the false positives are what this pins -- an accented "A" that means "a" is
     * worse than no accent at all, and onboarding copy contains exactly that word.
     */
    @Test
    fun controlTokenAccentFindsControlsAndNotOrdinaryWords() {
        fun tokensIn(text: String) = onboardingControlTokenSpans(text).map { text.substring(it.first, it.last + 1) }

        assertEquals(listOf("D-pad", "A"), tokensIn("Use the D-pad to move and press A to choose. Touch works too."))
        assertEquals(
            listOf("SELECT", "SELECT"),
            tokensIn("Open any platform, focus a game, and press SELECT. It goes straight to the Home shelf. Press SELECT again to take it off."),
        )
        assertEquals(listOf("X", "Y"), tokensIn("Press X on Home to open your Tomes, then choose Create Tome. Press Y on a game for Add to Tome."))

        // The article, the sentence-initial capital, and a capital inside a word are all left alone.
        assertEquals(emptyList<String>(), tokensIn("A Tome is a group you name yourself: a series, a genre."))
        assertEquals(emptyList<String>(), tokensIn("Art and Ambient sound live in Settings."))
        assertEquals(emptyList<String>(), tokensIn("Mimir adds more artwork."))
        // ...and a control named without a verb is simply not accented, rather than guessed at.
        assertEquals(emptyList<String>(), tokensIn("The Y button does something else here."))
    }
}
