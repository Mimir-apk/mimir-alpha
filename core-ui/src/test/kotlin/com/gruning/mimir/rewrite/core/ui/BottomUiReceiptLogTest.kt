package com.gruning.mimir.rewrite.core.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The edge discipline: one line per change, never per frame, and an absent transition is itself a line. */
class BottomUiReceiptLogTest {
    private class Harness(stack: Boolean = false) {
        val lines = ArrayList<String>()
        val warns = ArrayList<String>()
        var now = 1_000L

        private class Job(val at: Long, val block: () -> Unit) {
            var cancelled = false
        }

        private val jobs = ArrayList<Job>()
        val log = BottomUiReceiptLog(
            sink = { line, warn ->
                lines += line
                if (warn) warns += line
            },
            nowMs = { now },
            timer = { delay, block ->
                val job = Job(now + delay, block)
                jobs += job
                ({ job.cancelled = true })
            },
            stackSnapshots = stack,
        )

        fun advance(ms: Long) {
            now += ms
            jobs.filter { !it.cancelled && it.at <= now }.forEach {
                it.cancelled = true
                it.block()
            }
        }
    }

    @Test
    fun aSurfaceWritesOnlyWhenItsVisibilityChanges() {
        val h = Harness()
        h.log.surface("utility", 4, visible = true)
        h.log.surface("utility", 4, visible = true)
        h.log.surface("utility", 4, visible = true)
        h.log.surface("utility", 4, visible = false)
        h.log.surface("utility", 4, visible = false)
        assertEquals(
            listOf(
                "ev=surface.enter surface=utility display=4 from=none transition=none",
                "ev=surface.exit surface=utility display=4 to=none transition=none",
            ),
            h.lines,
        )
    }

    @Test
    fun aSurfaceNeverSeenAndReportedHiddenWritesNoExit() {
        val h = Harness()
        h.log.surface("tome", 4, visible = false)
        assertTrue(h.lines.isEmpty())
    }

    @Test
    fun nothingIsWrittenForACompositionNotHostedOnABottom() {
        val h = Harness()
        h.log.surface("utility", BottomUiReceiptFormat.NO_DISPLAY, visible = true)
        h.log.transitionBegin("x", BottomUiReceiptFormat.NO_DISPLAY)
        h.log.control("x", BottomUiReceiptFormat.NO_DISPLAY)
        h.log.artPlate("t", BottomUiReceiptFormat.NO_DISPLAY, hasArt = true, state = BottomUiArtState.LOADED, plateVisible = false)
        h.log.focusOwner("a", BottomUiReceiptFormat.NO_DISPLAY)
        h.log.theme("T", BottomUiReceiptFormat.NO_DISPLAY)
        assertTrue(h.lines.isEmpty())
    }

    @Test
    fun theSameSurfaceOnTwoDisplaysIsTwoSurfaces() {
        val h = Harness()
        h.log.surface("menu", 4, true)
        h.log.surface("menu", 5, true)
        assertEquals(2, h.lines.size)
    }

    @Test
    fun aNamedTransitionThatBeginsIsNotMissing() {
        val h = Harness()
        h.log.surface("drawer", 4, true, transition = "drawer.fade")
        h.advance(50)
        h.log.transitionBegin("drawer.fade", 4)
        h.advance(160)
        h.log.transitionEnd("drawer.fade", 4)
        h.advance(5_000)
        assertEquals(
            listOf(
                "ev=surface.enter surface=drawer display=4 from=none transition=drawer.fade",
                "ev=transition.begin name=drawer.fade display=4",
                "ev=transition.end name=drawer.fade display=4 ms=160 result=done",
            ),
            h.lines,
        )
        assertTrue(h.warns.isEmpty())
    }

    @Test
    fun aCutEdgeNamesItsReasonAndArmsNoMissingWatch() {
        val h = Harness()
        h.log.surface("dock", 4, true, transition = null, reason = BottomUiReceiptLog.REASON_INITIAL)
        h.log.surface("dock", 4, false, transition = null, reason = BottomUiReceiptLog.REASON_REDUCED_MOTION)
        h.advance(BottomUiReceiptLog.DEFAULT_MISSING_AFTER_MS + 10_000)
        assertEquals(
            listOf(
                "ev=surface.enter surface=dock display=4 from=none transition=none reason=initial",
                "ev=surface.exit surface=dock display=4 to=none transition=none reason=reduced_motion",
            ),
            h.lines,
        )
        assertTrue(h.warns.isEmpty())
    }

    @Test
    fun aNamedTransitionThatNeverBeginsIsReportedMissing() {
        val h = Harness()
        h.log.surface("allapps", 4, true, transition = "picker.glass")
        h.advance(BottomUiReceiptLog.DEFAULT_MISSING_AFTER_MS + 1)
        assertEquals(
            "ev=transition.missing name=picker.glass surface=allapps display=4 waitedMs=1501",
            h.lines.last(),
        )
        assertEquals(1, h.warns.size)
    }

    @Test
    fun aBeginThatArrivedJustBeforeItsSurfaceEdgeIsNotMissing() {
        val h = Harness()
        h.log.transitionBegin("scene.glide", 4)
        h.advance(20)
        h.log.surface("activity", 4, true, transition = "scene.glide")
        h.advance(10_000)
        assertTrue(h.lines.none { it.startsWith("ev=transition.missing") })
    }

    @Test
    fun aMissingReportIsWrittenOnce() {
        val h = Harness()
        h.log.surface("allapps", 4, true, transition = "picker.glass")
        h.advance(10_000)
        h.advance(10_000)
        assertEquals(1, h.lines.count { it.startsWith("ev=transition.missing") })
    }

    @Test
    fun aBeginWithoutAnEndIsReportedUnfinished() {
        val h = Harness()
        h.log.transitionBegin("scene.glide", 4)
        h.advance(BottomUiReceiptLog.DEFAULT_UNFINISHED_AFTER_MS + 1)
        assertEquals(
            "ev=transition.unfinished name=scene.glide display=4 runningMs=4001",
            h.lines.last(),
        )
    }

    @Test
    fun anEndWithoutABeginIsAnOrphanNotSilence() {
        val h = Harness()
        h.log.transitionEnd("scene.glide", 4)
        assertEquals("ev=transition.end name=scene.glide display=4 ms=0 result=orphan", h.lines.single())
    }

    @Test
    fun aRestartedTransitionEndsTheFirstRunAsCancelled() {
        val h = Harness()
        h.log.transitionBegin("scene.crossfade", 4)
        h.advance(150)
        h.log.transitionBegin("scene.crossfade", 4)
        h.advance(140)
        h.log.transitionEnd("scene.crossfade", 4)
        assertEquals(
            listOf(
                "ev=transition.begin name=scene.crossfade display=4",
                "ev=transition.end name=scene.crossfade display=4 ms=150 result=cancelled",
                "ev=transition.begin name=scene.crossfade display=4",
                "ev=transition.end name=scene.crossfade display=4 ms=140 result=done",
            ),
            h.lines,
        )
    }

    @Test
    fun theTransitionHelperBracketsABlockAndRecordsCancellation() {
        val h = Harness()
        runBlocking {
            h.log.transition("tome.reveal", 4) { h.advance(200) }
            try {
                h.log.transition("tome.reveal", 4) {
                    h.advance(30)
                    throw CancellationException("x")
                }
            } catch (_: CancellationException) {
            }
        }
        assertEquals(
            listOf(
                "ev=transition.begin name=tome.reveal display=4",
                "ev=transition.end name=tome.reveal display=4 ms=200 result=done",
                "ev=transition.begin name=tome.reveal display=4",
                "ev=transition.end name=tome.reveal display=4 ms=30 result=cancelled",
            ),
            h.lines,
        )
    }

    @Test
    fun aHammeredControlIsRateLimitedAndTheSuppressedCountIsNotLost() {
        val h = Harness()
        repeat(10) { h.log.control("lattice.tile.platform", 4, "lattice") }
        assertEquals(BottomUiReceiptLog.DEFAULT_CONTROL_MAX_PER_WINDOW, h.lines.size)
        h.advance(BottomUiReceiptLog.DEFAULT_CONTROL_WINDOW_MS)
        h.log.control("lattice.tile.platform", 4, "lattice")
        assertEquals(
            "ev=control.activate id=lattice.tile.platform display=4 surface=lattice suppressed=4",
            h.lines.last(),
        )
    }

    @Test
    fun differentControlsDoNotShareARateLimit() {
        val h = Harness()
        repeat(BottomUiReceiptLog.DEFAULT_CONTROL_MAX_PER_WINDOW) { h.log.control("a", 4) }
        h.log.control("b", 4)
        assertEquals(BottomUiReceiptLog.DEFAULT_CONTROL_MAX_PER_WINDOW + 1, h.lines.size)
    }

    @Test
    fun anArtPlateIsWrittenWhenItChangesNotOnEveryRecomposition() {
        val h = Harness()
        repeat(5) { h.log.artPlate("game-1", 4, hasArt = false, state = BottomUiArtState.NONE, plateVisible = true) }
        h.log.artPlate("game-1", 4, hasArt = true, state = BottomUiArtState.LOADED, plateVisible = false)
        repeat(5) { h.log.artPlate("game-1", 4, hasArt = true, state = BottomUiArtState.LOADED, plateVisible = false) }
        assertEquals(
            listOf(
                "ev=art.plate tile=game-1 display=4 hasArt=false state=none plate=visible",
                "ev=art.plate tile=game-1 display=4 hasArt=true state=loaded plate=hidden",
            ),
            h.lines,
        )
        assertTrue(h.warns.isEmpty())
    }

    @Test
    fun aPlateOverWiredArtIsABreachWhetherTheArtIsLoadingOrFailed() {
        val h = Harness()
        h.log.artPlate("icon-a", 4, hasArt = true, state = BottomUiArtState.LOADING, plateVisible = true)
        h.log.artPlate("icon-a", 4, hasArt = true, state = BottomUiArtState.FAILED, plateVisible = true)
        h.log.artPlate("icon-a", 4, hasArt = true, state = BottomUiArtState.FAILED, plateVisible = false)
        assertEquals(
            listOf(
                "ev=art.plate tile=icon-a display=4 hasArt=true state=loading plate=visible breach=true",
                "ev=art.plate tile=icon-a display=4 hasArt=true state=failed plate=visible breach=true",
                "ev=art.plate tile=icon-a display=4 hasArt=true state=failed plate=hidden",
            ),
            h.lines,
        )
        assertEquals("only the two breaches warn", 2, h.warns.size)
    }

    @Test
    fun aTileWithNoWiredArtHasNoStateAndNoBreach() {
        val h = Harness()
        h.log.artPlate("book", 4, hasArt = false, state = BottomUiArtState.LOADING, plateVisible = true)
        assertEquals("ev=art.plate tile=book display=4 hasArt=false state=none plate=visible", h.lines.single())
        assertTrue(h.warns.isEmpty())
    }

    @Test
    fun aListScrolledAwayAndBackCannotBurstAnUnchangedTilesReceipts() {
        val h = Harness()
        // First mount: loading with a letter, then loaded. Then fifty remounts as a lazy list scrolls the tile away and back.
        repeat(50) {
            h.log.artPlate("icon-b", 4, hasArt = true, state = BottomUiArtState.LOADING, plateVisible = true)
            h.log.artPlate("icon-b", 4, hasArt = true, state = BottomUiArtState.LOADED, plateVisible = false)
        }
        assertEquals(
            "at most ${BottomUiReceiptLog.DEFAULT_ART_MAX_PER_WINDOW} lines per window, however many remounts: ${h.lines}",
            BottomUiReceiptLog.DEFAULT_ART_MAX_PER_WINDOW,
            h.lines.size,
        )
        // The window ends: the tile's latest state differs from the last line written, so it is flushed, with the count.
        h.advance(BottomUiReceiptLog.DEFAULT_ART_WINDOW_MS)
        assertEquals(BottomUiReceiptLog.DEFAULT_ART_MAX_PER_WINDOW + 1, h.lines.size)
        assertTrue(h.lines.toString(), h.lines.last().startsWith("ev=art.plate tile=icon-b display=4 hasArt=true state=loaded plate=hidden suppressed="))
    }

    @Test
    fun aHeldBackLatestStateIsFlushedWithItsCountWhenTheWindowEnds() {
        val h = Harness()
        h.log.artPlate("icon-c", 4, hasArt = true, state = BottomUiArtState.LOADING, plateVisible = true)
        h.log.artPlate("icon-c", 4, hasArt = true, state = BottomUiArtState.LOADED, plateVisible = false)
        h.log.artPlate("icon-c", 4, hasArt = true, state = BottomUiArtState.LOADING, plateVisible = true)
        h.log.artPlate("icon-c", 4, hasArt = true, state = BottomUiArtState.LOADED, plateVisible = false)
        val written = h.lines.size
        assertEquals("the fourth change is held back", 3, written)
        h.advance(BottomUiReceiptLog.DEFAULT_ART_WINDOW_MS)
        assertEquals(written + 1, h.lines.size)
        assertTrue(h.lines.last().contains("state=loaded") && h.lines.last().contains("suppressed=1"))
    }

    @Test
    fun aWindowThatEndsOnTheStateAlreadyWrittenFlushesNothing() {
        val h = Harness()
        h.log.artPlate("icon-e", 4, hasArt = true, state = BottomUiArtState.LOADING, plateVisible = true)
        h.log.artPlate("icon-e", 4, hasArt = true, state = BottomUiArtState.LOADED, plateVisible = false)
        h.log.artPlate("icon-e", 4, hasArt = true, state = BottomUiArtState.LOADING, plateVisible = true)
        h.log.artPlate("icon-e", 4, hasArt = true, state = BottomUiArtState.LOADED, plateVisible = false)
        h.log.artPlate("icon-e", 4, hasArt = true, state = BottomUiArtState.LOADING, plateVisible = true)
        h.advance(BottomUiReceiptLog.DEFAULT_ART_WINDOW_MS)
        assertEquals("the log already ends on loading, which is where the tile is: ${h.lines}", 3, h.lines.size)
    }

    @Test
    fun anArtLineIsWrittenAgainOnceItsWindowHasPassed() {
        val h = Harness()
        repeat(2) {
            h.log.artPlate("icon-d", 4, hasArt = true, state = BottomUiArtState.LOADING, plateVisible = true)
            h.log.artPlate("icon-d", 4, hasArt = true, state = BottomUiArtState.LOADED, plateVisible = false)
        }
        h.advance(BottomUiReceiptLog.DEFAULT_ART_WINDOW_MS + 1)
        val before = h.lines.size
        h.log.artPlate("icon-d", 4, hasArt = true, state = BottomUiArtState.LOADING, plateVisible = true)
        assertEquals(before + 1, h.lines.size)
    }

    @Test
    fun differentTilesDoNotShareAnArtWindow() {
        val h = Harness()
        repeat(10) { i -> h.log.artPlate("tile-$i", 4, hasArt = true, state = BottomUiArtState.LOADED, plateVisible = false) }
        assertEquals(10, h.lines.size)
    }

    @Test
    fun focusAndThemeWriteOnlyOnAChange() {
        val h = Harness()
        h.log.focusOwner("menu.item.a", 4)
        h.log.focusOwner("menu.item.a", 4)
        h.log.focusOwner("menu.item.b", 4)
        h.log.theme("NIFLHEIM", 4)
        h.log.theme("NIFLHEIM", 4)
        h.log.theme("MUSPELHEIM", 4)
        assertEquals(
            listOf(
                "ev=focus.owner display=4 owner=menu.item.a prev=none",
                "ev=focus.owner display=4 owner=menu.item.b prev=menu.item.a",
                "ev=theme.change display=4 theme=NIFLHEIM prev=none transition=none",
                "ev=theme.change display=4 theme=MUSPELHEIM prev=NIFLHEIM transition=none",
            ),
            h.lines,
        )
    }

    @Test
    fun aThemeSwitchNamesItsCrossfadeAndAMissingOneIsReported() {
        val h = Harness()
        val missingAfter = BottomUiReceiptLog.DEFAULT_MISSING_AFTER_MS
        h.log.theme("NIFLHEIM", 4, transition = "theme-switch.crossfade")
        h.advance(10_000)
        assertTrue(h.lines.none { it.startsWith("ev=transition.missing") })
        h.log.theme("MUSPELHEIM", 4, transition = "theme-switch.crossfade")
        h.advance(missingAfter + 1)
        assertEquals(
            "ev=transition.missing name=theme-switch.crossfade surface=theme-switch display=4 waitedMs=${missingAfter + 1}",
            h.lines.last(),
        )
    }


    @Test
    fun aThemeSwitchUnderReducedMotionIsADeliberateCutNotAMissingTransition() {
        val h = Harness()
        h.log.theme("NIFLHEIM", 4, transition = "theme-switch.crossfade", reducedMotion = true)
        h.log.theme("MUSPELHEIM", 4, transition = "theme-switch.crossfade", reducedMotion = true)
        assertEquals(
            "ev=theme.change display=4 theme=MUSPELHEIM prev=NIFLHEIM transition=none reason=reduced_motion",
            h.lines.last(),
        )
        h.advance(10_000)
        assertTrue("no begin, no end and no missing: ${h.lines}", h.lines.none { it.startsWith("ev=transition.") })
    }


    @Test
    fun aThemeSwitchFollowsTheMotionTheMountedBackdropRememberedNotTheLiveSetting() {
        val h = Harness()
        val backdrop = Any()
        // The backdrop remembered "cut" at attach; the system setting has since been turned back on (live: not reduced).
        h.log.registerBackdropMotion(4, backdrop, reducedMotion = true)
        val live = false
        h.log.theme("NIFLHEIM", 4, transition = "theme-switch.crossfade", reducedMotion = h.log.themeSwitchIsCut(4, live))
        h.log.theme("MUSPELHEIM", 4, transition = "theme-switch.crossfade", reducedMotion = h.log.themeSwitchIsCut(4, live))
        assertEquals(
            "ev=theme.change display=4 theme=MUSPELHEIM prev=NIFLHEIM transition=none reason=reduced_motion",
            h.lines.last(),
        )
        h.advance(10_000)
        assertTrue("the backdrop cuts, so nothing is missing: ${h.lines}", h.lines.none { it.startsWith("ev=transition.") })
    }

    @Test
    fun aBackdropThatRememberedAFadeIsReportedAsAFadeEvenIfTheLiveSettingIsNowReduced() {
        val h = Harness()
        val missingAfter = BottomUiReceiptLog.DEFAULT_MISSING_AFTER_MS
        h.log.registerBackdropMotion(4, Any(), reducedMotion = false)
        val live = true
        h.log.theme("NIFLHEIM", 4, transition = "theme-switch.crossfade", reducedMotion = h.log.themeSwitchIsCut(4, live))
        h.log.theme("MUSPELHEIM", 4, transition = "theme-switch.crossfade", reducedMotion = h.log.themeSwitchIsCut(4, live))
        assertEquals("ev=theme.change display=4 theme=MUSPELHEIM prev=NIFLHEIM transition=theme-switch.crossfade", h.lines.last())
        h.advance(missingAfter + 1)
        assertTrue("the fade really runs on that backdrop, so a missing one is reported", h.lines.last().startsWith("ev=transition.missing"))
    }

    @Test
    fun theLiveSettingAnswersOnlyWhenNoBackdropIsMountedAndOneCrossfadingBackdropMakesItAFade() {
        val h = Harness()
        assertTrue(h.log.themeSwitchIsCut(4, live = true))
        assertEquals(false, h.log.themeSwitchIsCut(4, live = false))
        val cutting = Any()
        val fading = Any()
        h.log.registerBackdropMotion(4, cutting, reducedMotion = true)
        assertTrue(h.log.themeSwitchIsCut(4, live = false))
        h.log.registerBackdropMotion(4, fading, reducedMotion = false)
        assertEquals("one backdrop still fades", false, h.log.themeSwitchIsCut(4, live = true))
        h.log.unregisterBackdropMotion(4, fading)
        assertTrue(h.log.themeSwitchIsCut(4, live = false))
        h.log.unregisterBackdropMotion(4, cutting)
        assertEquals("unmounted: back to the live setting", false, h.log.themeSwitchIsCut(4, live = false))
        assertEquals("another display's backdrop is not this one's", false, run {
            h.log.registerBackdropMotion(5, Any(), reducedMotion = true)
            h.log.themeSwitchIsCut(4, live = false)
        })
    }
    @Test
    fun theFirstThemeADisplayShowsIsNotACutEvenUnderReducedMotion() {
        val h = Harness()
        h.log.theme("NIFLHEIM", 4, transition = "theme-switch.crossfade", reducedMotion = true)
        assertEquals("ev=theme.change display=4 theme=NIFLHEIM prev=none transition=none", h.lines.single())
    }
    @Test
    fun aThemeSwitchWhoseCrossfadeRunsIsNotMissing() {
        val h = Harness()
        h.log.theme("NIFLHEIM", 4, transition = "theme-switch.crossfade")
        h.log.theme("MUSPELHEIM", 4, transition = "theme-switch.crossfade")
        h.log.transitionBegin("theme-switch.crossfade", 4)
        h.advance(160)
        h.log.transitionEnd("theme-switch.crossfade", 4)
        h.advance(10_000)
        assertTrue(h.lines.none { it.startsWith("ev=transition.missing") })
        assertTrue(h.lines.contains("ev=transition.end name=theme-switch.crossfade display=4 ms=160 result=done"))
    }

    @Test
    fun endIfRunningIsSilentWhenNothingRuns() {
        val h = Harness()
        h.log.transitionEndIfRunning("m-menu.swap", 4)
        assertTrue(h.lines.isEmpty())
        h.log.transitionBegin("m-menu.swap", 4)
        h.advance(90)
        h.log.transitionEndIfRunning("m-menu.swap", 4)
        assertEquals("ev=transition.end name=m-menu.swap display=4 ms=90 result=done", h.lines.last())
    }

    @Test
    fun surfaceReportsWhetherItWroteAnEdge() {
        val h = Harness()
        assertTrue(h.log.surface("m-menu", 4, true))
        assertTrue(!h.log.surface("m-menu", 4, true))
        assertTrue(h.log.surface("m-menu", 4, false))
        assertTrue(!h.log.surface("tabs", 4, false))
    }

    @Test
    fun twoWatchersOfOneAnimationAreOneRun() {
        val h = Harness()
        h.log.transitionBegin("theme-switch.crossfade", 4)
        h.advance(5)
        h.log.transitionBegin("theme-switch.crossfade", 4)
        h.advance(155)
        h.log.transitionEnd("theme-switch.crossfade", 4)
        h.log.transitionEnd("theme-switch.crossfade", 4)
        assertEquals(
            listOf(
                "ev=transition.begin name=theme-switch.crossfade display=4",
                "ev=transition.end name=theme-switch.crossfade display=4 ms=160 result=done",
            ),
            h.lines,
        )
    }


    @Test
    fun aFailedParkWarnsAndAParkIsAnOrdinaryLine() {
        val h = Harness()
        h.log.session("park_failed", "op-1", 4, "main_game_front_timeout", "org.example.emu")
        h.log.session("park", "op-1", 4, "bottom_app_launch", "org.example.emu")
        assertEquals(
            listOf(
                "ev=session.park_failed session=op-1 display=4 reason=main_game_front_timeout pkg=org.example.emu",
                "ev=session.park session=op-1 display=4 reason=bottom_app_launch pkg=org.example.emu",
            ),
            h.lines,
        )
        assertEquals(1, h.warns.size)
    }
    @Test
    fun aSinkThatThrowsNeverReachesTheCaller() {
        val log = BottomUiReceiptLog(
            sink = { _, _ -> error("logcat is gone") },
            nowMs = { 0L },
            timer = { _, _ -> {} },
        )
        log.surface("lattice", 4, true)
        log.control("x", 4)
        log.presentation("create", 4, "render")
    }

    @Test
    fun aNewPresentationClosesItsOwnVisibleSurfacesWithAnExitAndLetsThemEnterAgain() {
        val h = Harness()
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.resetWindow(4, BottomSurface.PRESENTATION_OWNED)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        assertEquals(
            listOf(
                "ev=surface.enter surface=lattice display=4 from=none transition=none",
                "ev=surface.exit surface=lattice display=4 to=none transition=none",
                "ev=surface.enter surface=lattice display=4 from=none transition=none",
            ),
            h.lines,
        )
    }

    @Test
    fun aNewPresentationDoesNotWipeAnotherWindowsSurfaceState() {
        val h = Harness()
        // The secondary-home floor entered its cover; the Tabs drawer window and the return guard are also on display 4.
        h.log.surface(BottomSurface.COLD_START, 4, true)
        h.log.surface(BottomSurface.TABS, 4, true)
        h.log.surface(BottomSurface.RETURN_GUARD, 4, true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.lines.clear()

        h.log.resetWindow(4, BottomSurface.PRESENTATION_OWNED)

        assertEquals("only the Presentation's own surface is closed: ${h.lines}", listOf("ev=surface.exit surface=lattice display=4 to=none transition=none"), h.lines)
        assertTrue(h.log.isSurfaceVisible(BottomSurface.COLD_START, 4))
        assertTrue(h.log.isSurfaceVisible(BottomSurface.TABS, 4))
        assertTrue(h.log.isSurfaceVisible(BottomSurface.RETURN_GUARD, 4))
        // So the floor's own exit, when it goes, is still written (it was suppressed when the whole display was wiped).
        h.log.surface(BottomSurface.COLD_START, 4, false)
        assertEquals("ev=surface.exit surface=cold-start display=4 to=none transition=none", h.lines.last())
    }

    @Test
    fun aResetOnOneDisplayLeavesAnotherDisplayAlone() {
        val h = Harness()
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.surface(BottomSurface.LATTICE, 5, true)
        h.lines.clear()
        h.log.resetWindow(4, BottomSurface.PRESENTATION_OWNED)
        assertTrue(h.log.isSurfaceVisible(BottomSurface.LATTICE, 5))
        assertEquals(1, h.lines.size)
    }

    @Test
    fun aResetCancelsTheMissingWatchOfAnOwnedSurfaceOnly() {
        val h = Harness()
        h.log.surface(BottomSurface.LATTICE, 4, true, transition = "scene.glide")
        h.log.surface(BottomSurface.COLD_START, 4, true, transition = "cold-start.enter")
        h.log.resetWindow(4, BottomSurface.PRESENTATION_OWNED)
        h.advance(BottomUiReceiptLog.DEFAULT_MISSING_AFTER_MS + 1)
        assertTrue(h.lines.toString(), h.lines.none { it.contains("transition.missing name=scene.glide") })
        assertTrue(h.lines.toString(), h.lines.any { it.contains("transition.missing name=cold-start.enter") })
    }

    @Test
    fun theThemeStartsFromNotSeenAfterANewPresentation() {
        val h = Harness()
        h.log.theme("MUSPELHEIM", 4, transition = "theme-switch.crossfade")
        h.log.resetWindow(4, BottomSurface.PRESENTATION_OWNED)
        h.lines.clear()
        h.log.theme("NIFLHEIM", 4, transition = "theme-switch.crossfade")
        assertEquals(listOf("ev=theme.change display=4 theme=NIFLHEIM prev=none transition=none"), h.lines)
    }

    @Test
    fun theTomeMoveOutConfirmNamesItsSwapOnBothEdgesWhileTomeOpenStaysEntered() {
        val h = Harness()
        h.log.surface(BottomSurface.TOME_OPEN, 4, true, transition = "tome-open.enter")
        h.log.transitionBegin("tome-open.enter", 4)
        h.advance(300)
        h.log.transitionEnd("tome-open.enter", 4)
        h.lines.clear()

        // Long-press a child: forward. Both pages of the swap watch it, so two begins in one frame are one run, and the page
        // left (180 ms) settles before the page arriving (130 ms lead + 180 ms).
        h.log.surface(BottomSurface.TOME_MOVE_OUT, 4, true, transition = "tome-move-out.enter")
        h.log.transitionBegin("tome-move-out.enter", 4)
        h.log.transitionBegin("tome-move-out.enter", 4)
        h.advance(180)
        h.log.transitionEnd("tome-move-out.enter", 4)
        h.advance(130)
        h.log.transitionEnd("tome-move-out.enter", 4)
        // Outside tap: a return, the page left dissolving over the one returned to on ReturnMs (160 ms).
        h.log.surface(BottomSurface.TOME_MOVE_OUT, 4, false, transition = "tome-move-out.exit")
        h.log.transitionBegin("tome-move-out.exit", 4)
        h.log.transitionBegin("tome-move-out.exit", 4)
        h.advance(16)
        h.log.transitionEnd("tome-move-out.exit", 4)
        h.advance(144)
        h.log.transitionEnd("tome-move-out.exit", 4)
        h.advance(BottomUiReceiptLog.DEFAULT_MISSING_AFTER_MS + 1)

        assertEquals(
            listOf(
                "ev=surface.enter surface=tome-move-out display=4 from=none transition=tome-move-out.enter",
                "ev=transition.begin name=tome-move-out.enter display=4",
                "ev=transition.end name=tome-move-out.enter display=4 ms=310 result=done",
                "ev=surface.exit surface=tome-move-out display=4 to=none transition=tome-move-out.exit",
                "ev=transition.begin name=tome-move-out.exit display=4",
                "ev=transition.end name=tome-move-out.exit display=4 ms=160 result=done",
            ),
            h.lines,
        )
        assertTrue("tome-open stays entered throughout", h.log.isSurfaceVisible(BottomSurface.TOME_OPEN, 4))
    }

    @Test
    fun theTomeMoveOutConfirmUnderReducedMotionIsANamedCutWithNoWatch() {
        val h = Harness()
        h.log.surface(BottomSurface.TOME_OPEN, 4, true)
        h.lines.clear()
        h.log.surface(BottomSurface.TOME_MOVE_OUT, 4, true, reason = BottomUiReceiptLog.REASON_REDUCED_MOTION)
        h.log.surface(BottomSurface.TOME_MOVE_OUT, 4, false, reason = BottomUiReceiptLog.REASON_REDUCED_MOTION)
        h.advance(BottomUiReceiptLog.DEFAULT_MISSING_AFTER_MS + 1)
        assertEquals(
            listOf(
                "ev=surface.enter surface=tome-move-out display=4 from=none transition=none reason=reduced_motion",
                "ev=surface.exit surface=tome-move-out display=4 to=none transition=none reason=reduced_motion",
            ),
            h.lines,
        )
    }

    @Test
    fun aNewPresentationClosesTheTomeMoveOutConfirmItOwned() {
        val h = Harness()
        h.log.surface(BottomSurface.TOME_MOVE_OUT, 4, true, transition = "tome-move-out.enter")
        h.lines.clear()
        h.log.resetWindow(4, BottomSurface.PRESENTATION_OWNED)
        assertEquals(listOf("ev=surface.exit surface=tome-move-out display=4 to=none transition=none"), h.lines)
    }

    // ---- A5 fold-in 1: the startup cover's exit ---------------------------------------------------------------------------

    @Test
    fun theStartupCoverExitsOnceNamingTheSceneThatStandsOverIt() {
        val h = Harness()
        h.log.surface(BottomSurface.COLD_START, 4, true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.lines.clear()
        assertTrue(h.log.coldStartCoverGone(4))
        assertEquals(listOf("ev=surface.exit surface=cold-start display=4 to=lattice transition=none"), h.lines)
        assertFalse("the floor is not re-entered: the log believes it gone", h.log.isSurfaceVisible(BottomSurface.COLD_START, 4))
        // A reclaim that recreates the window finds the cover already gone and writes nothing more.
        h.lines.clear()
        assertFalse(h.log.coldStartCoverGone(4))
        assertTrue(h.lines.toString(), h.lines.isEmpty())
    }

    @Test
    fun theStartupCoverNamesActivityOrNoneWhenThatIsWhatStandsOverIt() {
        val h = Harness()
        h.log.surface(BottomSurface.COLD_START, 4, true)
        h.log.surface(BottomSurface.ACTIVITY, 4, true)
        h.log.surface(BottomSurface.DOCK, 4, true)
        h.lines.clear()
        h.log.coldStartCoverGone(4)
        assertEquals(
            "a dock over the scene is not what replaced the cover",
            listOf("ev=surface.exit surface=cold-start display=4 to=activity transition=none"),
            h.lines,
        )

        val bare = Harness()
        bare.log.surface(BottomSurface.COLD_START, 4, true)
        bare.lines.clear()
        bare.log.coldStartCoverGone(4)
        assertEquals(listOf("ev=surface.exit surface=cold-start display=4 to=none transition=none"), bare.lines)
    }

    @Test
    fun theStartupCoverOfAnotherDisplayAndOneNeverSeenAreLeftAlone() {
        val h = Harness()
        h.log.surface(BottomSurface.COLD_START, 5, true)
        h.lines.clear()
        assertFalse("nothing was entered on display 4", h.log.coldStartCoverGone(4))
        assertTrue(h.log.isSurfaceVisible(BottomSurface.COLD_START, 5))
        assertTrue(h.lines.isEmpty())
        assertFalse(h.log.coldStartCoverGone(BottomUiReceiptFormat.NO_DISPLAY))
    }

    // ---- A5 fold-in 4: the surface stack ----------------------------------------------------------------------------------

    @Test
    fun everySurfaceEdgeIsFollowedByTheStackBottomToTop() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.surface(BottomSurface.DOCK, 4, true)
        h.log.surface(BottomSurface.PICKER, 4, true)
        h.log.surface(BottomSurface.DOCK, 4, false)
        h.log.surface(BottomSurface.PICKER, 4, false)
        h.log.surface(BottomSurface.LATTICE, 4, false)
        assertEquals(
            listOf(
                "ev=surface.enter surface=lattice display=4 from=none transition=none",
                "ev=surface.state display=4 stack=lattice reason=change",
                "ev=surface.enter surface=dock display=4 from=none transition=none",
                "ev=surface.state display=4 stack=lattice,dock reason=change",
                "ev=surface.enter surface=picker display=4 from=none transition=none",
                "ev=surface.state display=4 stack=lattice,dock,picker reason=change",
                "ev=surface.exit surface=dock display=4 to=none transition=none",
                "ev=surface.state display=4 stack=lattice,picker reason=change",
                "ev=surface.exit surface=picker display=4 to=none transition=none",
                "ev=surface.state display=4 stack=lattice reason=change",
                "ev=surface.exit surface=lattice display=4 to=none transition=none",
                "ev=surface.state display=4 stack=none reason=change",
            ),
            h.lines,
        )
    }

    @Test
    fun aSceneSwapWritesOneStackLineAfterItCompletesAndNeverABaselessOne() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.surface(BottomSurface.DOCK, 4, true)
        h.lines.clear()
        // The scene host swaps Home for Activity at one call site: the old surface exits, then the new one enters.
        h.log.surface(BottomSurface.LATTICE, 4, false, transition = "scene.glide", other = BottomSurface.ACTIVITY)
        assertTrue("the exit alone writes no stack: ${h.lines}", h.lines.none { it.startsWith("ev=surface.state") })
        h.log.surface(BottomSurface.ACTIVITY, 4, true, transition = "scene.glide", other = BottomSurface.LATTICE)
        h.advance(BottomUiReceiptLog.STACK_SETTLE_MS * 4)
        assertEquals(
            listOf("ev=surface.state display=4 stack=activity,dock reason=change"),
            h.lines.filter { it.startsWith("ev=surface.state") },
        )
        assertTrue(
            "no line anywhere names a floorless stack: ${h.lines}",
            h.lines.none { it.contains("stack=dock ") },
        )
        assertEquals(listOf(BottomSurface.ACTIVITY, BottomSurface.DOCK), h.log.surfaceStack(4))
    }

    @Test
    fun aDisplayWithNoSceneStatesItsFloorlessStackOnceTheHoldRunsOut() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.DOCK, 4, true)
        assertTrue("held, not written: ${h.lines}", h.lines.none { it.startsWith("ev=surface.state") })
        h.advance(BottomUiReceiptLog.STACK_SETTLE_MS - 1)
        assertTrue(h.lines.none { it.startsWith("ev=surface.state") })
        h.advance(1)
        assertEquals(listOf("ev=surface.state display=4 stack=dock reason=change"), h.lines.filter { it.startsWith("ev=surface.state") })
        h.advance(BottomUiReceiptLog.STACK_SETTLE_MS * 4)
        assertEquals("written once", 1, h.lines.count { it.startsWith("ev=surface.state") })
    }

    @Test
    fun theStackIsInDrawingOrderNotEntryOrder_aSceneEnteredAfterTheDockIsUnderIt() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.surface(BottomSurface.DOCK, 4, true)
        // Activity replaces the lattice as the scene while the dock stays open: the scene is drawn UNDER the dock.
        h.log.surface(BottomSurface.LATTICE, 4, false)
        h.log.surface(BottomSurface.ACTIVITY, 4, true)
        assertEquals(listOf(BottomSurface.ACTIVITY, BottomSurface.DOCK), h.log.surfaceStack(4))
        assertEquals(BottomSurface.DOCK, h.log.surfaceStack(4).last())
        h.advance(BottomUiReceiptLog.STACK_SETTLE_MS * 4)
        assertEquals("ev=surface.state display=4 stack=activity,dock reason=change", h.lines.last())
    }

    @Test
    fun theStackIsInDrawingOrderNotEntryOrder_activityMediaUnderTheDock() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.ACTIVITY, 4, true)
        h.log.surface(BottomSurface.DOCK, 4, true)
        h.log.surface(BottomSurface.ACTIVITY_MEDIA, 4, true)
        assertEquals(
            "the media panel is composed inside the scene, so it is under the dock that opened first",
            listOf(BottomSurface.ACTIVITY, BottomSurface.ACTIVITY_MEDIA, BottomSurface.DOCK),
            h.log.surfaceStack(4),
        )
        assertEquals("ev=surface.state display=4 stack=activity,activity-media,dock reason=change", h.lines.last())
    }

    @Test
    fun anOpenTomeAndItsMoveOutConfirmAreOverTheLatticeAndUnderTheDock() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.surface(BottomSurface.DOCK, 4, true)
        h.log.surface(BottomSurface.TOME_MOVE_OUT, 4, true)
        h.log.surface(BottomSurface.TOME_OPEN, 4, true)
        assertEquals(
            listOf(BottomSurface.LATTICE, BottomSurface.TOME_OPEN, BottomSurface.TOME_MOVE_OUT, BottomSurface.DOCK),
            h.log.surfaceStack(4),
        )
    }

    @Test
    fun anOpenPickerRaisesTheWholeSurfaceGroupAboveTheShelfAndTheInstrumentsButNotAboveTabs() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.surface(BottomSurface.PLATFORM_SHELF, 4, true)
        h.log.surface(BottomSurface.UTILITY, 4, true)
        assertEquals(
            listOf(BottomSurface.LATTICE, BottomSurface.PLATFORM_SHELF, BottomSurface.UTILITY),
            h.log.surfaceStack(4),
        )
        h.log.surface(BottomSurface.PICKER, 4, true)
        h.log.surface(BottomSurface.TABS, 4, true)
        h.log.surface(BottomSurface.DISPLAY_CHOOSER, 4, true)
        assertEquals(
            listOf(
                BottomSurface.PLATFORM_SHELF, BottomSurface.UTILITY, BottomSurface.LATTICE, BottomSurface.PICKER,
                BottomSurface.TABS, BottomSurface.DISPLAY_CHOOSER,
            ),
            h.log.surfaceStack(4),
        )
    }

    @Test
    fun aSurfaceTheTableDoesNotKnowIsTheTopSoNobodyTapsUnderIt() {
        val h = Harness(stack = true)
        h.log.surface("mystery", 4, true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.surface(BottomSurface.TABS, 4, true)
        assertEquals("mystery", h.log.surfaceStack(4).last())
    }

    @Test
    fun theStartupCoverIsInTheStackUntilItExitsAndThenNeverAgain() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.COLD_START, 4, true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        assertEquals(listOf("cold-start", "lattice"), h.log.surfaceStack(4))
        h.log.coldStartCoverGone(4)
        assertEquals(listOf("lattice"), h.log.surfaceStack(4))
        assertEquals("ev=surface.state display=4 stack=lattice reason=change", h.lines.last())
    }

    @Test
    fun readyAndNewPresentationStateTheStackAndAnotherDisplayHasItsOwn() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.surface(BottomSurface.PLATFORM_SHELF, 5, true)
        h.lines.clear()
        h.log.stackSnapshot(4, BottomUiReceiptLog.REASON_READY)
        assertEquals(listOf("ev=surface.state display=4 stack=lattice reason=ready"), h.lines)
        h.lines.clear()
        h.log.resetWindow(4, BottomSurface.PRESENTATION_OWNED)
        assertEquals(
            listOf(
                "ev=surface.exit surface=lattice display=4 to=none transition=none",
                "ev=surface.state display=4 stack=none reason=change",
            ),
            h.lines,
        )
        h.log.stackSnapshot(BottomUiReceiptFormat.NO_DISPLAY, BottomUiReceiptLog.REASON_READY)
        assertEquals(2, h.lines.size)
    }

    @Test
    fun aLogBuiltWithoutSnapshotsWritesOnlyTheEdges() {
        val h = Harness()
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.stackSnapshot(4, BottomUiReceiptLog.REASON_READY)
        assertEquals(listOf("ev=surface.enter surface=lattice display=4 from=none transition=none"), h.lines)
    }

    @Test
    fun theHeartbeatRestatesTheStackOnItsIntervalAndStopsWhenItsCoroutineDoes() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.surface(BottomSurface.DOCK, 4, true)
        h.lines.clear()
        val waits = ArrayList<Long>()
        runBlocking {
            val gate = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.RENDEZVOUS)
            val job = launch {
                h.log.runStackHeartbeat(4, wait = { ms ->
                    waits += ms
                    gate.receive()
                })
            }
            yield()
            assertEquals("nothing is written before the first interval has passed", emptyList<String>(), h.lines)
            repeat(3) {
                gate.send(Unit)
                yield()
            }
            job.cancel()
            job.join()
            gate.trySend(Unit)
            yield()
        }
        assertEquals("one timer, every five minutes", List(4) { 300_000L }, waits)
        assertEquals(
            "three intervals passed, so three restatements, and none after the coroutine was cancelled",
            List(3) { "ev=surface.state display=4 stack=lattice,dock reason=heartbeat" },
            h.lines,
        )
        assertEquals(5 * 60 * 1_000L, BottomUiReceiptLog.STACK_HEARTBEAT_MS)
    }

    // ---- A5 re-check P3 fold-ins: where a snapshot sorts a surface depends on the window that composes it ------------------

    @Test
    fun quickLaunchOpenedInsideTabsIsOverTabsNotUnderIt() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.surface(BottomSurface.TABS, 4, true)
        // All apps, then its launch prompt, then a pin-actions sheet, all from inside the Tabs drawer's own quick-launch layer.
        h.log.surface(BottomSurface.PICKER, 4, true, host = BottomSurface.TABS)
        assertEquals(listOf(BottomSurface.LATTICE, BottomSurface.TABS, BottomSurface.PICKER), h.log.surfaceStack(4))
        h.log.surface(BottomSurface.LAUNCH_PROMPT, 4, true, host = BottomSurface.TABS)
        h.log.surface(BottomSurface.PIN_ACTIONS, 4, true, host = BottomSurface.TABS)
        assertEquals(
            listOf(BottomSurface.LATTICE, BottomSurface.TABS, BottomSurface.PICKER, BottomSurface.LAUNCH_PROMPT, BottomSurface.PIN_ACTIONS),
            h.log.surfaceStack(4),
        )
        assertEquals("ev=surface.state display=4 stack=lattice,tabs,picker,launch-prompt,pin-actions reason=change", h.lines.last())
        // The game display chooser is still above everything the drawer shows.
        h.log.surface(BottomSurface.DISPLAY_CHOOSER, 4, true)
        assertEquals(BottomSurface.DISPLAY_CHOOSER, h.log.surfaceStack(4).last())
    }

    @Test
    fun quickLaunchInTheTabsWindowDoesNotRaiseTheSurfaceGroupOverTheShelf() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.surface(BottomSurface.PLATFORM_SHELF, 4, true)
        h.log.surface(BottomSurface.TABS, 4, true)
        h.log.surface(BottomSurface.PICKER, 4, true, host = BottomSurface.TABS)
        // The Presentation is never told a picker opened in the drawer, so its surface group stays at z 0, under the shelf.
        assertEquals(
            listOf(BottomSurface.LATTICE, BottomSurface.PLATFORM_SHELF, BottomSurface.TABS, BottomSurface.PICKER),
            h.log.surfaceStack(4),
        )
    }

    @Test
    fun theSamePickerInThePresentationStillSortsBelowTabsAndRaisesTheGroup() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.surface(BottomSurface.PLATFORM_SHELF, 4, true)
        h.log.surface(BottomSurface.TABS, 4, true)
        h.log.surface(BottomSurface.PICKER, 4, true)
        assertEquals(
            listOf(BottomSurface.PLATFORM_SHELF, BottomSurface.LATTICE, BottomSurface.PICKER, BottomSurface.TABS),
            h.log.surfaceStack(4),
        )
    }

    @Test
    fun aSurfaceForgetsItsHostWhenItLeavesSoANewHomeIsSortedByItsOwnHost() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.surface(BottomSurface.TABS, 4, true)
        h.log.surface(BottomSurface.PICKER, 4, true, host = BottomSurface.TABS)
        // The host names the entry, so its exit names it too: an exit that named none would be another entry's.
        h.log.surface(BottomSurface.PICKER, 4, false, host = BottomSurface.TABS)
        h.log.surface(BottomSurface.PICKER, 4, true)
        assertEquals(listOf(BottomSurface.LATTICE, BottomSurface.PICKER, BottomSurface.TABS), h.log.surfaceStack(4))
    }

    @Test
    fun theReceiptTextTellsAPickerInTabsFromOneInTheQuickMenuAndFromOneInThePresentation() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.surface(BottomSurface.PICKER, 4, true)
        h.log.surface(BottomSurface.TABS, 4, true)
        h.log.surface(BottomSurface.PICKER, 4, true, host = BottomSurface.TABS)
        h.log.surface(BottomSurface.M_MENU, 4, true)
        h.log.surface(BottomSurface.PICKER, 4, true, host = BottomSurface.M_MENU)
        val enters = h.lines.filter { it.startsWith("ev=surface.enter surface=picker") }
        assertEquals(
            listOf(
                "ev=surface.enter surface=picker display=4 from=none transition=none",
                "ev=surface.enter surface=picker display=4 host=tabs from=none transition=none",
                "ev=surface.enter surface=picker display=4 host=m-menu from=none transition=none",
            ),
            enters,
        )
        h.log.surface(BottomSurface.PICKER, 4, false, host = BottomSurface.TABS)
        assertTrue(h.lines.toString(), h.lines.contains("ev=surface.exit surface=picker display=4 host=tabs to=none transition=none"))
    }

    @Test
    fun dismissingThePromptOverABaselessPickerIsStatedAtOnceNotAfterTheHold() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.PICKER, 4, true)
        h.log.surface(BottomSurface.LAUNCH_PROMPT, 4, true)
        h.advance(BottomUiReceiptLog.STACK_SETTLE_MS * 2)
        assertEquals("ev=surface.state display=4 stack=picker,launch-prompt reason=change", h.lines.last { it.startsWith("ev=surface.state") })
        h.lines.clear()

        h.log.surface(BottomSurface.LAUNCH_PROMPT, 4, false)
        // No clock has moved: the exit is already followed by the stack that no longer names the prompt.
        assertEquals(
            listOf(
                "ev=surface.exit surface=launch-prompt display=4 to=none transition=none",
                "ev=surface.state display=4 stack=picker reason=change",
            ),
            h.lines,
        )
        h.advance(BottomUiReceiptLog.STACK_SETTLE_MS * 4)
        assertEquals("nothing is replayed once the hold would have run out", 2, h.lines.size)
    }

    @Test
    fun anExitDropsAHeldSnapshotInsteadOfLeavingItToBeWrittenLate() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.PICKER, 4, true)
        h.log.surface(BottomSurface.DOCK, 4, true)
        assertTrue("both enters are baseless and held: ${h.lines}", h.lines.none { it.startsWith("ev=surface.state") })
        h.log.surface(BottomSurface.PICKER, 4, false)
        assertEquals("ev=surface.state display=4 stack=dock reason=change", h.lines.last())
        val written = h.lines.size
        h.advance(BottomUiReceiptLog.STACK_SETTLE_MS * 4)
        assertEquals("the held line was cancelled, not replayed after the exit", written, h.lines.size)
    }

    @Test
    fun aSceneExitIsStillHeldSoASwapNeverWritesAFloorlessStack() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.surface(BottomSurface.PICKER, 4, true)
        h.lines.clear()
        h.log.surface(BottomSurface.LATTICE, 4, false, transition = "scene.glide", other = BottomSurface.ACTIVITY)
        assertTrue("the scene's exit alone writes no stack: ${h.lines}", h.lines.none { it.startsWith("ev=surface.state") })
        h.log.surface(BottomSurface.ACTIVITY, 4, true, transition = "scene.glide", other = BottomSurface.LATTICE)
        h.advance(BottomUiReceiptLog.STACK_SETTLE_MS * 4)
        assertEquals(
            listOf("ev=surface.state display=4 stack=activity,picker reason=change"),
            h.lines.filter { it.startsWith("ev=surface.state") },
        )
    }

    @Test
    fun theReturnGuardIsAnActivityBelowEverythingThePresentationShows() {
        val h = Harness(stack = true)
        // A reclaim: the guard entered first and is still entered when the Presentation's surfaces come back over it.
        h.log.surface(BottomSurface.RETURN_GUARD, 4, true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.surface(BottomSurface.DOCK, 4, true)
        assertEquals(listOf(BottomSurface.RETURN_GUARD, BottomSurface.LATTICE, BottomSurface.DOCK), h.log.surfaceStack(4))
        // Whatever the order they entered in, and with the group raised by a picker too.
        val late = Harness(stack = true)
        late.log.surface(BottomSurface.LATTICE, 4, true)
        late.log.surface(BottomSurface.PLATFORM_SHELF, 4, true)
        late.log.surface(BottomSurface.PICKER, 4, true)
        late.log.surface(BottomSurface.RETURN_GUARD, 4, true)
        assertEquals(BottomSurface.RETURN_GUARD, late.log.surfaceStack(4).first())
        assertEquals(BottomSurface.PICKER, late.log.surfaceStack(4).last())
        assertTrue(BottomSurface.drawingLayer(BottomSurface.RETURN_GUARD, raised = false) < BottomSurface.drawingLayer(BottomSurface.LATTICE, raised = false))
    }

    @Test
    fun theQuickMenuIsAnApplicationOverlayAboveBothThePresentationAndTheReturnGuard() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.M_MENU, 4, true)
        h.log.surface(BottomSurface.RETURN_GUARD, 4, true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.surface(BottomSurface.TABS, 4, true)
        h.log.surface(BottomSurface.DISPLAY_CHOOSER, 4, true)
        assertEquals(
            listOf(BottomSurface.RETURN_GUARD, BottomSurface.LATTICE, BottomSurface.TABS, BottomSurface.DISPLAY_CHOOSER, BottomSurface.M_MENU),
            h.log.surfaceStack(4),
        )
        assertTrue(
            BottomSurface.drawingLayer(BottomSurface.M_MENU, raised = false) > BottomSurface.drawingLayer(BottomSurface.RETURN_GUARD, raised = false),
        )
    }

    // ---- V2 fold-ins: the quick menu's window, and an entry keyed by the window that composes it ----------------------------------

    @Test
    fun quickLaunchOpenedInsideTheQuickMenuWindowIsOverTheMenuNotUnderIt() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.surface(BottomSurface.M_MENU, 4, true)
        h.log.surface(BottomSurface.PICKER, 4, true, host = BottomSurface.M_MENU)
        assertEquals(listOf(BottomSurface.LATTICE, BottomSurface.M_MENU, BottomSurface.PICKER), h.log.surfaceStack(4))
        h.log.surface(BottomSurface.LAUNCH_PROMPT, 4, true, host = BottomSurface.M_MENU)
        h.log.surface(BottomSurface.PIN_ACTIONS, 4, true, host = BottomSurface.M_MENU)
        assertEquals(
            listOf(BottomSurface.LATTICE, BottomSurface.M_MENU, BottomSurface.PICKER, BottomSurface.LAUNCH_PROMPT, BottomSurface.PIN_ACTIONS),
            h.log.surfaceStack(4),
        )
        assertEquals("ev=surface.state display=4 stack=lattice,m-menu,picker,launch-prompt,pin-actions reason=change", h.lines.last())
        // 721, 722, 723: over the menu layer (700) and, being a window of their own, over the game display chooser (600) as well.
        assertEquals(721, BottomSurface.drawingLayer(BottomSurface.PICKER, raised = false, host = BottomSurface.M_MENU))
        assertEquals(722, BottomSurface.drawingLayer(BottomSurface.LAUNCH_PROMPT, raised = false, host = BottomSurface.M_MENU))
        assertEquals(723, BottomSurface.drawingLayer(BottomSurface.PIN_ACTIONS, raised = false, host = BottomSurface.M_MENU))
        h.log.surface(BottomSurface.DISPLAY_CHOOSER, 4, true)
        assertEquals(BottomSurface.PIN_ACTIONS, h.log.surfaceStack(4).last())
    }

    @Test
    fun quickLaunchInTheQuickMenuWindowDoesNotRaiseTheSurfaceGroupOverTheShelf() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.surface(BottomSurface.PLATFORM_SHELF, 4, true)
        h.log.surface(BottomSurface.M_MENU, 4, true)
        h.log.surface(BottomSurface.PICKER, 4, true, host = BottomSurface.M_MENU)
        assertEquals(
            listOf(BottomSurface.LATTICE, BottomSurface.PLATFORM_SHELF, BottomSurface.M_MENU, BottomSurface.PICKER),
            h.log.surfaceStack(4),
        )
    }

    @Test
    fun thePresentationsPickerAndATabsPickerAreTwoEntriesThatDoNotShareAKey() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.surface(BottomSurface.TABS, 4, true)
        assertTrue(h.log.surface(BottomSurface.PICKER, 4, true))
        assertTrue("the second window's enter is its own edge, not a duplicate of the first", h.log.surface(BottomSurface.PICKER, 4, true, host = BottomSurface.TABS))
        assertEquals(1, h.lines.count { it == "ev=surface.enter surface=picker display=4 from=none transition=none" })
        // The Tabs window's picker is a different line to read, and says whose window it is.
        assertEquals(1, h.lines.count { it == "ev=surface.enter surface=picker display=4 host=tabs from=none transition=none" })
        assertTrue(h.log.isSurfaceVisible(BottomSurface.PICKER, 4))
        assertTrue(h.log.isSurfaceVisible(BottomSurface.PICKER, 4, BottomSurface.TABS))
        assertFalse(h.log.isSurfaceVisible(BottomSurface.PICKER, 4, BottomSurface.M_MENU))

        // The Presentation's picker leaves; the Tabs picker is still open, and still sorts over Tabs.
        assertTrue(h.log.surface(BottomSurface.PICKER, 4, false))
        assertFalse(h.log.isSurfaceVisible(BottomSurface.PICKER, 4))
        assertTrue(h.log.isSurfaceVisible(BottomSurface.PICKER, 4, BottomSurface.TABS))
        assertEquals(listOf(BottomSurface.LATTICE, BottomSurface.TABS, BottomSurface.PICKER), h.log.surfaceStack(4))
    }

    @Test
    fun aHiddenSiblingsExitDoesNotCloseThePickerThatIsStillOpenInAnotherWindow() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.surface(BottomSurface.PICKER, 4, true, host = BottomSurface.TABS)
        h.lines.clear()
        // The Presentation's own (never shown) picker reports hidden: it has exited nothing, and it is not the Tabs picker.
        assertFalse(h.log.surface(BottomSurface.PICKER, 4, false))
        assertTrue(h.lines.toString(), h.lines.isEmpty())
        assertTrue(h.log.isSurfaceVisible(BottomSurface.PICKER, 4, BottomSurface.TABS))
        assertEquals(listOf(BottomSurface.LATTICE, BottomSurface.PICKER), h.log.surfaceStack(4))
        // Its own exit, naming its window, does close it.
        assertTrue(h.log.surface(BottomSurface.PICKER, 4, false, host = BottomSurface.TABS))
        assertEquals(listOf(BottomSurface.LATTICE), h.log.surfaceStack(4))
    }

    @Test
    fun aSecondEnterIsNotDeduplicatedBeforeItsHostIsKnown() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.surface(BottomSurface.TABS, 4, true)
        // The picker was already entered by the Presentation, with no host; the same call from the menu window names its host.
        h.log.surface(BottomSurface.PICKER, 4, true)
        h.log.surface(BottomSurface.PICKER, 4, true, host = BottomSurface.M_MENU)
        // Both are visible, and the one with a host sorts by it: over m-menu's layer, so last.
        h.log.surface(BottomSurface.M_MENU, 4, true)
        assertEquals(
            listOf(BottomSurface.LATTICE, BottomSurface.PICKER, BottomSurface.TABS, BottomSurface.M_MENU, BottomSurface.PICKER),
            h.log.surfaceStack(4),
        )
    }

    @Test
    fun aNewPresentationClosesOnlyTheUnhostedEntryOfAnOwnedSurface() {
        val h = Harness(stack = true)
        h.log.surface(BottomSurface.LATTICE, 4, true)
        h.log.surface(BottomSurface.PICKER, 4, true)
        h.log.surface(BottomSurface.PICKER, 4, true, host = BottomSurface.TABS)
        h.lines.clear()
        h.log.resetWindow(4, setOf(BottomSurface.LATTICE, BottomSurface.PICKER))
        assertEquals(
            listOf(
                "ev=surface.exit surface=lattice display=4 to=none transition=none",
                "ev=surface.exit surface=picker display=4 to=none transition=none",
                "ev=surface.state display=4 stack=picker reason=change",
            ),
            h.lines,
        )
        assertTrue("the Tabs window is still there, and its picker is its own", h.log.isSurfaceVisible(BottomSurface.PICKER, 4, BottomSurface.TABS))
    }

    @Test
    fun aNewPresentationStatesTheStackThatIsLeftAtOnceNotAfterTheHold() {
        val h = Harness(stack = true)
        // The quick menu is a window of its own; the Presentation's Tome and instruments panel are open under it.
        h.log.surface(BottomSurface.M_MENU, 4, true)
        h.log.surface(BottomSurface.TOME_OPEN, 4, true)
        h.log.surface(BottomSurface.UTILITY, 4, true)
        h.advance(BottomUiReceiptLog.STACK_SETTLE_MS * 4)
        h.lines.clear()

        h.log.resetWindow(4, BottomSurface.PRESENTATION_OWNED)

        // No clock has moved: the exits are already followed by the stack that names only the menu. Held, it would have stood on the
        // previous snapshot (which still named the Tome and the instruments) for the whole settle interval.
        assertEquals(
            listOf(
                "ev=surface.exit surface=tome-open display=4 to=none transition=none",
                "ev=surface.exit surface=utility display=4 to=none transition=none",
                "ev=surface.state display=4 stack=m-menu reason=change",
            ),
            h.lines,
        )
        h.advance(BottomUiReceiptLog.STACK_SETTLE_MS * 4)
        assertEquals("nothing is replayed once the hold would have run out", 3, h.lines.size)
    }
}
