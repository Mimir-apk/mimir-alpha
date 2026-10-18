package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the pure parts of the RA surface: the hero-title label, the points line, and the medal tinting. */
class MimirAchievementSurfaceTest {

    /**
     * The hero-title count . The critical case is the null: an unresolved lookup must
     * render NOTHING, not "0/0" — showing a confident zero would claim the user has earned nothing when
     * Mimir simply has not looked yet.
     */
    @Test
    fun progressLabelDistinguishesUnknownFromZero() {
        assertEquals("12/40", mimirAchievementProgressLabel(12, 40))
        assertEquals("zero of a KNOWN set is real progress", "0/40", mimirAchievementProgressLabel(0, 40))
        assertEquals("40/40", mimirAchievementProgressLabel(40, 40))

        assertNull("unresolved total renders nothing", mimirAchievementProgressLabel(3, null))
        assertNull("unresolved unlocked renders nothing", mimirAchievementProgressLabel(null, 40))
        assertNull("a set with no achievements is not a count", mimirAchievementProgressLabel(0, 0))
    }

    /** A malformed upstream count must not produce a label claiming more unlocks than the set holds. */
    @Test
    fun progressLabelClampsNonsenseCounts() {
        assertEquals("40/40", mimirAchievementProgressLabel(99, 40))
        assertEquals("0/40", mimirAchievementProgressLabel(-5, 40))
    }

    @Test
    fun pointsLabelReadsAsProse() {
        assertEquals("310 of 1,200 points", mimirAchievementPointsLabel(310, 1200))
        assertEquals("0 of 0 points", mimirAchievementPointsLabel(-3, 0))
    }

    /** Medals share the coverless-book rule: one platform, one leather family; different platforms differ. */
    @Test
    fun leatherTintIsDeterministicPerPlatform() {
        assertEquals(mimirPlatformLeatherTint("snes"), mimirPlatformLeatherTint("snes"))
        assertEquals("case must not fork the family", mimirPlatformLeatherTint("SNES"), mimirPlatformLeatherTint("snes"))
        assertNotEquals(mimirPlatformLeatherTint("snes"), mimirPlatformLeatherTint("genesis"))
    }

    /**
     * The load-bearing honesty string. Mimir is a launcher and cannot unlock anything; if this line ever
     * stops saying so, the feature starts implying Mimir awards achievements.
     */
    @Test
    fun copyNeverClaimsMimirUnlocksAchievements() {
        val line = MimirAchievementCopy.EarnedInEmulator
        assertTrue("must name the emulator as where unlocks happen", line.contains("emulator"))
        assertTrue("must say Mimir only shows progress", line.contains("Mimir shows the progress"))

        val everyString = listOf(
            MimirAchievementCopy.EarnedInEmulator,
            MimirAchievementCopy.CardEyebrow,
            MimirAchievementCopy.NoSetMatchedHeadline,
            MimirAchievementCopy.SetListedNothing,
            MimirAchievementCopy.StatusChecking,
            MimirAchievementCopy.StatusUnavailable,
            MimirAchievementCopy.StatusNeedsSetup,
            MimirAchievementCopy.StatusUnsupported,
            MimirAchievementCopy.StatusFormatNotReady,
            MimirAchievementCopy.StatusFileTooLarge,
            MimirAchievementCopy.StatusSystemUnmapped,
            MimirAchievementCopy.StatusNoSet,
            MimirAchievementCopy.Checking,
            MimirAchievementCopy.NoAnswerYet,
            MimirAchievementCopy.NothingCheckedYet,
            MimirAchievementCopy.CheckGamesToLoad,
            MimirAchievementCopy.NoCredentials,
            MimirAchievementCopy.OptedOut,
            MimirAchievementCopy.CredentialsRejected,
            MimirAchievementCopy.Unreachable,
            MimirAchievementCopy.ProgressUnavailable,
            MimirAchievementCopy.TooLarge,
            MimirAchievementCopy.FileUnavailable,
            MimirAchievementCopy.SystemUnsupported,
            MimirAchievementCopy.IdentificationUnavailable,
            MimirAchievementCopy.ConsoleUnmapped,
            MimirAchievementCopy.ProviderDataIncomplete,
            MimirAchievementCopy.NoSetForGame,
            MimirAchievementCopy.RecognizedNoSet,
            MimirAchievementCopy.ConnectValidating,
            MimirAchievementCopy.ConnectAcceptedTitle,
            MimirAchievementCopy.ConnectAcceptedBodyPrefix,
            MimirAchievementCopy.ConnectUsernameRejected,
            MimirAchievementCopy.ConnectKeyRejected,
            MimirAchievementCopy.ConnectUnreachable,
            MimirAchievementCopy.ConnectSaveFailed,
            MimirAchievementCopy.ConnectRecoveryUncertain,
            MimirAchievementCopy.SettingsNeverChecked,
            MimirAchievementCopy.OpenProfileSettings,
        )
        everyString.forEach { copy ->
            listOf("Mimir unlocks", "Mimir awards", "Mimir tracks", "earn achievements in Mimir").forEach {
                assertFalse("copy must not claim \"$it\": $copy", copy.contains(it, ignoreCase = true))
            }
        }
    }

    /** Unknown is a distinct state from an empty Loaded — the card can never fake "0 of 0". */
    @Test
    fun unknownIsNotAnEmptyLoadedState() {
        val unknown: MimirAchievementCardState = MimirAchievementCardState.Unknown(MimirAchievementCopy.NoAnswerYet)
        assertTrue(unknown is MimirAchievementCardState.Unknown)
        assertFalse(unknown is MimirAchievementCardState.Loaded)
    }

    /**
     * The orphan guard for [mimirAchievementUnknownTone].
     *
     * The app collapses ten TYPED unresolved reasons into one string before the card sees them
     * (`RetroAchievementsProgressLane.mimirAchievementCardState`), so the card recovers the tone by
     * matching the canonical constants. That recovery is only safe while every constant the app can send
     * still lands on the tone it was written for — edit one of these strings without revisiting the
     * projection and it silently degrades to UNAVAILABLE. This test is what makes that a build failure.
     */
    @Test
    fun everyUnresolvedReasonKeepsItsTone() {
        val expected = mapOf(
            MimirAchievementCopy.Checking to MimirAchievementUnknownTone.PENDING,
            MimirAchievementCopy.ConnectValidating to MimirAchievementUnknownTone.PENDING,
            MimirAchievementCopy.NoCredentials to MimirAchievementUnknownTone.ACTION_NEEDED,
            MimirAchievementCopy.CredentialsRejected to MimirAchievementUnknownTone.ACTION_NEEDED,
            MimirAchievementCopy.OptedOut to MimirAchievementUnknownTone.ACTION_NEEDED,
            MimirAchievementCopy.SystemUnsupported to MimirAchievementUnknownTone.UNSUPPORTED,
            MimirAchievementCopy.IdentificationUnavailable to MimirAchievementUnknownTone.UNSUPPORTED,
            MimirAchievementCopy.TooLarge to MimirAchievementUnknownTone.UNSUPPORTED,
            MimirAchievementCopy.ConsoleUnmapped to MimirAchievementUnknownTone.UNSUPPORTED,
            MimirAchievementCopy.ProviderDataIncomplete to MimirAchievementUnknownTone.UNAVAILABLE,
            MimirAchievementCopy.NoAnswerYet to MimirAchievementUnknownTone.UNAVAILABLE,
            MimirAchievementCopy.NothingCheckedYet to MimirAchievementUnknownTone.UNAVAILABLE,
            MimirAchievementCopy.Unreachable to MimirAchievementUnknownTone.UNAVAILABLE,
            MimirAchievementCopy.ProgressUnavailable to MimirAchievementUnknownTone.UNAVAILABLE,
            MimirAchievementCopy.FileUnavailable to MimirAchievementUnknownTone.UNAVAILABLE,
        )
        expected.forEach { (copy, tone) ->
            assertEquals("tone drifted for: $copy", tone, mimirAchievementUnknownTone(copy))
        }
    }

    /**
     * An unrecognised string must never animate. PENDING is the only tone that puts a live "checking"
     * sweep on the card, so guessing it wrong would claim Mimir is working when it has already given up.
     */
    @Test
    fun unrecognisedReasonNeverReadsAsPending() {
        listOf("", "   ", "something a future lane added", "Checking") .forEach {
            assertEquals(
                "unrecognised copy must fall back to UNAVAILABLE: \"$it\"",
                MimirAchievementUnknownTone.UNAVAILABLE,
                mimirAchievementUnknownTone(it),
            )
        }
    }

    /**
     * The two engine reasons exist precisely so the app never has to borrow a FALSE nearby string.
     *
     * An unmapped cartridge system must not borrow generic system-support copy, and an incomplete provider
     * payload must not reuse [MimirAchievementCopy.Unreachable] or a no-set line, which would blame the
     * network or the file when neither is at fault.
     */
    @Test
    fun engineReasonCopyBorrowsNoFalseDiscOrNetworkClaim() {
        val unmapped = MimirAchievementCopy.ConsoleUnmapped
        assertTrue("must name the affected feature", unmapped.contains("achievements"))
        assertTrue("must scope the gap to this platform", unmapped.contains("platform"))
        assertFalse("an unmapped CARTRIDGE system is not a disc problem", unmapped.contains("disc", ignoreCase = true))
        assertFalse("nor a reachability problem", unmapped.contains("reach", ignoreCase = true))
        assertNotEquals("must not collapse back into generic support copy", MimirAchievementCopy.SystemUnsupported, unmapped)
        assertFalse(
            "generic unsupported copy must not falsely reduce every unsupported platform to discs",
            MimirAchievementCopy.SystemUnsupported.contains("disc", ignoreCase = true),
        )

        val incomplete = MimirAchievementCopy.ProviderDataIncomplete
        assertTrue("must say what is wrong with the answer", incomplete.contains("incomplete"))
        assertFalse("an incomplete answer is not a failure to reach", incomplete.contains("reach", ignoreCase = true))
        assertFalse("and is not a claim that no set exists", incomplete.contains("didn’t match", ignoreCase = true))
        assertNotEquals(MimirAchievementCopy.Unreachable, incomplete)
        assertNotEquals(MimirAchievementCopy.NoSetForGame, incomplete)
    }

    /**
     * Two reasons sharing a string would silently collapse into one tone, since the projection matches on
     * the constants themselves. Distinctness is what makes that projection safe.
     */
    @Test
    fun everyUnresolvedReasonStringIsDistinct() {
        val all = listOf(
            MimirAchievementCopy.Checking,
            MimirAchievementCopy.ConnectValidating,
            MimirAchievementCopy.NoAnswerYet,
            MimirAchievementCopy.NothingCheckedYet,
            MimirAchievementCopy.NoCredentials,
            MimirAchievementCopy.OptedOut,
            MimirAchievementCopy.CredentialsRejected,
            MimirAchievementCopy.Unreachable,
            MimirAchievementCopy.ProgressUnavailable,
            MimirAchievementCopy.TooLarge,
            MimirAchievementCopy.FileUnavailable,
            MimirAchievementCopy.SystemUnsupported,
            MimirAchievementCopy.IdentificationUnavailable,
            MimirAchievementCopy.ConsoleUnmapped,
            MimirAchievementCopy.ProviderDataIncomplete,
        )
        assertEquals("two reasons share a string and would fold into one tone", all.size, all.toSet().size)
    }

    /** V10: a caller with no points total says so; every caller that says nothing (Main's) keeps the points line, because the default is true. */
    @Test
    fun pointsAreKnownByDefaultSoEveryExistingCallerIsUnchanged() {
        val loaded = MimirAchievementCardState.Loaded(
            gameTitle = "Ace Combat Advance",
            unlocked = 1,
            total = 43,
            earnedPoints = 5,
            totalPoints = 235,
            rows = emptyList(),
        )
        assertTrue(loaded.pointsKnown)
        assertEquals(loaded, loaded.copy(pointsKnown = true))
        assertNotEquals(loaded, loaded.copy(pointsKnown = false))
        assertEquals("5 of 235 points", mimirAchievementPointsLabel(loaded.earnedPoints, loaded.totalPoints))
    }

    /** One head-band slot, one truth: a real count, the right status word, or nothing at all. */
    @Test
    fun statusSlotCarriesTheCountOrTheReasonThereIsntOne() {
        val loaded = MimirAchievementCardState.Loaded(
            gameTitle = "Ace Combat Advance",
            unlocked = 0,
            total = 43,
            earnedPoints = 0,
            totalPoints = 235,
            rows = emptyList(),
        )
        val count = mimirAchievementStatusLabel(loaded)
        assertEquals("0/43", count?.text)
        assertTrue("a resolved set shows a COUNT, not a word", count!!.isCount)

        // A Loaded whose counts cannot form an honest label shows nothing rather than "0/0".
        assertNull(mimirAchievementStatusLabel(loaded.copy(total = 0)))

        assertEquals(
            MimirAchievementCopy.StatusNeedsSetup,
            mimirAchievementStatusLabel(
                MimirAchievementCardState.Unknown(MimirAchievementCopy.NoCredentials),
            )?.text,
        )
        assertTrue(
            "only the states the reader can fix are flagged actionable",
            mimirAchievementStatusLabel(
                MimirAchievementCardState.Unknown(MimirAchievementCopy.NoCredentials),
            )!!.isActionable,
        )
        assertFalse(
            "an unreachable service is not something the reader can act on",
            mimirAchievementStatusLabel(
                MimirAchievementCardState.Unknown(MimirAchievementCopy.Unreachable),
            )!!.isActionable,
        )
        assertEquals(
            MimirAchievementCopy.StatusUnsupported,
            mimirAchievementStatusLabel(
                MimirAchievementCardState.Unknown(MimirAchievementCopy.SystemUnsupported),
            )?.text,
        )
        assertEquals(
            "an RA-supported platform must never wear the UNSUPPORTED badge",
            MimirAchievementCopy.StatusFormatNotReady,
            mimirAchievementStatusLabel(
                MimirAchievementCardState.Unknown(MimirAchievementCopy.IdentificationUnavailable),
            )?.text,
        )
        assertEquals(
            MimirAchievementCopy.StatusFileTooLarge,
            mimirAchievementStatusLabel(
                MimirAchievementCardState.Unknown(MimirAchievementCopy.TooLarge),
            )?.text,
        )
        assertEquals(
            MimirAchievementCopy.StatusSystemUnmapped,
            mimirAchievementStatusLabel(
                MimirAchievementCardState.Unknown(MimirAchievementCopy.ConsoleUnmapped),
            )?.text,
        )
        assertEquals(
            MimirAchievementCopy.StatusNoSet,
            mimirAchievementStatusLabel(
                MimirAchievementCardState.NotMatched(MimirAchievementCopy.NoSetForGame),
            )?.text,
        )
    }

    /**
     * the Activity-level page with a connected account and nothing checked is not "UNAVAILABLE" (nothing is unavailable) and not a per-game "no answer": its slot
     * says nothing, and its words are the Activity card's own.
     */
    @Test
    fun theActivityLevelEmptyStateWearsNoStatusWordAndNamesNoGame() {
        assertNull(mimirAchievementStatusLabel(MimirAchievementCardState.Unknown(MimirAchievementCopy.NothingCheckedYet)))
        // The game card's own "no answer yet" keeps its word: only the Activity-level state drops it.
        assertEquals(
            MimirAchievementCopy.StatusUnavailable,
            mimirAchievementStatusLabel(MimirAchievementCardState.Unknown(MimirAchievementCopy.NoAnswerYet))?.text,
        )
        assertEquals("No achievements checked yet", MimirAchievementCopy.NothingCheckedYet)
        assertEquals("Check games in the library to load achievements", MimirAchievementCopy.CheckGamesToLoad)
        listOf(MimirAchievementCopy.NothingCheckedYet, MimirAchievementCopy.CheckGamesToLoad).forEach {
            assertFalse("the Activity-level page has no game: $it", it.contains("this game", ignoreCase = true))
        }
    }

    /**
     * The edge index. The load-bearing case is the NULL: a page whose rows all fit must draw no rail,
     * because a rail on a non-scrolling page is a promise of content that does not exist.
     */
    @Test
    fun edgeIndexOnlyExistsWhenThePageActuallyScrolls() {
        assertNull("everything visible = no rail", mimirLedgerRail(totalItems = 6, firstVisibleIndex = 0, visibleItemCount = 6))
        assertNull("more visible than exist = no rail", mimirLedgerRail(totalItems = 3, firstVisibleIndex = 0, visibleItemCount = 8))
        assertNull("an empty page has no rail", mimirLedgerRail(totalItems = 0, firstVisibleIndex = 0, visibleItemCount = 0))
        assertNull("nothing measured yet = no rail", mimirLedgerRail(totalItems = 40, firstVisibleIndex = 0, visibleItemCount = 0))

        val top = mimirLedgerRail(totalItems = 40, firstVisibleIndex = 0, visibleItemCount = 8)!!
        assertEquals("thumb is the visible share", 0.2f, top.thumbFraction, 0.0001f)
        assertEquals("at the top the thumb sits at the top", 0.0f, top.offsetFraction, 0.0001f)
    }

    /** The thumb must reach the bottom of its track exactly at the end of travel — not near it. */
    @Test
    fun edgeIndexReachesBothEndsExactly() {
        val end = mimirLedgerRail(totalItems = 40, firstVisibleIndex = 32, visibleItemCount = 8)!!
        assertEquals(
            "thumb bottom must land exactly on the track bottom",
            1.0f,
            end.offsetFraction + end.thumbFraction,
            0.0001f,
        )

        // A scroll position past the last valid start clamps instead of running off the end.
        val overshoot = mimirLedgerRail(totalItems = 40, firstVisibleIndex = 99, visibleItemCount = 8)!!
        assertEquals(1.0f, overshoot.offsetFraction + overshoot.thumbFraction, 0.0001f)
    }

    /** A huge set must still leave a thumb big enough to see, or the rail says nothing at all. */
    @Test
    fun edgeIndexThumbStaysVisibleOnAVeryLongSet() {
        val huge = mimirLedgerRail(totalItems = 400, firstVisibleIndex = 0, visibleItemCount = 6)!!
        assertTrue("thumb would be 1.5% of the track without a floor", huge.thumbFraction >= 0.12f)
        assertTrue("floored thumb must still fit its track", huge.offsetFraction + huge.thumbFraction <= 1.0001f)
    }

    /**
     * One D-pad press moves most of a page, keeping an overlap band. A full-viewport step would teleport
     * the reader past the row they were reading; a tiny step would make 43 achievements a chore.
     */
    @Test
    fun scrollStepMovesMostOfAPageButKeepsAnOverlap() {
        val viewport = 600
        val step = mimirAchievementScrollStep(viewport)
        assertTrue("step must advance meaningfully: $step", step > viewport / 2)
        assertTrue("step must leave an overlap band: $step", step < viewport)
        assertTrue(
            "the step is proportional to the viewport",
            kotlin.math.abs(mimirAchievementScrollStep(viewport * 2) - step * 2) <= 2,
        )
    }

    /** A viewport that has not been measured yet must not produce a zero-or-negative scroll. */
    @Test
    fun scrollStepNeverStalls() {
        assertTrue(mimirAchievementScrollStep(0) >= 1)
        assertTrue(mimirAchievementScrollStep(1) >= 1)
        assertTrue(mimirAchievementScrollStep(-100) >= 1)
    }

    @Test
    fun rejectedAndConnectFailureCopyNamesTheRightBoundary() {
        assertFalse(MimirAchievementCopy.CredentialsRejected.contains("just now", ignoreCase = true))
        assertFalse(MimirAchievementCopy.CredentialsRejected.contains("couldn’t reach", ignoreCase = true))
        assertTrue(MimirAchievementCopy.CredentialsRejected.contains("Web API key"))

        val connectFailures = listOf(
            MimirAchievementCopy.ConnectUsernameRejected,
            MimirAchievementCopy.ConnectKeyRejected,
            MimirAchievementCopy.ConnectUnreachable,
        )
        connectFailures.forEach { assertTrue(it.endsWith("Nothing was saved.")) }
        assertTrue(MimirAchievementCopy.ConnectSaveFailed.contains("No new connection is active"))
        assertFalse(MimirAchievementCopy.ConnectSaveFailed.contains("Nothing was saved"))
        assertTrue(MimirAchievementCopy.ConnectRecoveryUncertain.contains("off for this session"))
        assertTrue(MimirAchievementCopy.ConnectRecoveryUncertain.contains("before restarting"))

        assertTrue(MimirAchievementCopy.NoCredentials.startsWith("Add your"))
        listOf(
            MimirAchievementCopy.CredentialsRejected,
            MimirAchievementCopy.Unreachable,
            MimirAchievementCopy.ProgressUnavailable,
            MimirAchievementCopy.RecognizedNoSet,
        ).forEach { assertFalse(it.startsWith("Add your")) }
    }
}
