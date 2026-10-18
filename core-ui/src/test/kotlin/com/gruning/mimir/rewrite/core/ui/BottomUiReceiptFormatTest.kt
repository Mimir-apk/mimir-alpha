package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the exact text a host driver greps. A grammar change must be a deliberate edit of these strings. */
class BottomUiReceiptFormatTest {
    @Test
    fun surfaceEnterNamesTheSurfaceDisplayAndTheWiredTransition() {
        assertEquals(
            "ev=surface.enter surface=utility display=4 from=lattice transition=panel.glass",
            BottomUiReceiptFormat.surfaceEnter("utility", 4, from = "lattice", transition = "panel.glass"),
        )
    }

    @Test
    fun anEdgeWithNoWiredTransitionSaysNoneRatherThanOmittingTheKey() {
        assertEquals(
            "ev=surface.enter surface=tome display=4 from=none transition=none",
            BottomUiReceiptFormat.surfaceEnter("tome", 4),
        )
        assertEquals(
            "ev=surface.exit surface=tome display=4 to=none transition=none",
            BottomUiReceiptFormat.surfaceExit("tome", 4),
        )
    }

    @Test
    fun aSurfaceEdgeThatNamesNoTransitionMaySayWhy() {
        assertEquals(
            "ev=surface.enter surface=dock display=4 from=none transition=none reason=reduced_motion",
            BottomUiReceiptFormat.surfaceEnter("dock", 4, reason = "reduced_motion"),
        )
        assertEquals(
            "ev=surface.exit surface=dock display=4 to=none transition=none reason=initial",
            BottomUiReceiptFormat.surfaceExit("dock", 4, reason = "initial"),
        )
    }

    @Test
    fun aSurfaceComposedInsideAnotherWindowNamesThatHostRightAfterTheDisplay() {
        assertEquals(
            "ev=surface.enter surface=picker display=4 host=tabs from=lattice transition=none",
            BottomUiReceiptFormat.surfaceEnter("picker", 4, from = "lattice", host = "tabs"),
        )
        assertEquals(
            "ev=surface.exit surface=picker display=4 host=m-menu to=none transition=none reason=initial",
            BottomUiReceiptFormat.surfaceExit("picker", 4, reason = "initial", host = "m-menu"),
        )
        // No host, no field: the lines the Presentation's own surfaces write are unchanged.
        assertFalse(BottomUiReceiptFormat.surfaceEnter("picker", 4).contains("host="))
        assertFalse(BottomUiReceiptFormat.surfaceExit("picker", 4).contains("host="))
    }

    @Test
    fun transitionLinesCarryMeasuredMillisecondsAndAResult() {
        assertEquals("ev=transition.begin name=scene.crossfade display=4", BottomUiReceiptFormat.transitionBegin("scene.crossfade", 4))
        assertEquals(
            "ev=transition.end name=scene.crossfade display=4 ms=140 result=done",
            BottomUiReceiptFormat.transitionEnd("scene.crossfade", 4, 140, "done"),
        )
        assertEquals(
            "ev=transition.missing name=panel.glass surface=utility display=4 waitedMs=700",
            BottomUiReceiptFormat.transitionMissing("panel.glass", "utility", 4, 700),
        )
    }

    @Test
    fun presentationLinesCarryTheReasonAndTheOperation() {
        assertEquals(
            "ev=presentation.create display=4 reason=render op=1a2b3c4d",
            BottomUiReceiptFormat.presentation("create", 4, "render", "1a2b3c4d"),
        )
        assertEquals(
            "ev=presentation.reclaim display=4 reason=tab_close_reclaim op=none",
            BottomUiReceiptFormat.presentation("reclaim", 4, "tab_close_reclaim"),
        )
    }

    @Test
    fun aControlIsNamedAndNeverLocated() {
        val line = BottomUiReceiptFormat.control("lattice.tile.platform", 4, "lattice")
        assertEquals("ev=control.activate id=lattice.tile.platform display=4 surface=lattice", line)
        assertFalse(line.contains(" x="))
        assertFalse(line.contains(" y="))
    }

    @Test
    fun aControlNamesWhatItActsOnByStableIdNeverByTitle() {
        assertEquals(
            "ev=control.activate id=lattice.tile.platform display=4 surface=lattice target=nes",
            BottomUiReceiptFormat.control("lattice.tile.platform", 4, "lattice", target = "nes"),
        )
    }

    @Test
    fun aSuppressedCountRidesOnTheNextControlLine() {
        assertEquals(
            "ev=control.activate id=drawer.close display=4 surface=drawer suppressed=3",
            BottomUiReceiptFormat.control("drawer.close", 4, "drawer", suppressed = 3),
        )
    }

    @Test
    fun anArtLineCarriesTheStateAndStatesABreachByItself() {
        assertEquals(
            "ev=art.plate tile=game:0f9cf50c1a display=4 hasArt=true state=loaded plate=hidden",
            BottomUiReceiptFormat.artPlate("game:0f9cf50c1a", 4, hasArt = true, state = BottomUiArtState.LOADED, plateVisible = false),
        )
        assertEquals(
            "ev=art.plate tile=platform.nes display=4 hasArt=false state=none plate=visible",
            BottomUiReceiptFormat.artPlate("platform.nes", 4, hasArt = false, state = BottomUiArtState.LOADING, plateVisible = true),
        )
        // A plate over wired art is a breach in BOTH loading (R3) and failed (R8).
        assertEquals(
            "ev=art.plate tile=app.org.example.app display=4 hasArt=true state=loading plate=visible breach=true",
            BottomUiReceiptFormat.artPlate("app.org.example.app", 4, hasArt = true, state = BottomUiArtState.LOADING, plateVisible = true),
        )
        assertEquals(
            "ev=art.plate tile=app.org.example.app display=4 hasArt=true state=failed plate=visible breach=true",
            BottomUiReceiptFormat.artPlate("app.org.example.app", 4, hasArt = true, state = BottomUiArtState.FAILED, plateVisible = true),
        )
        assertEquals(
            "ev=art.plate tile=app.org.example.app display=4 hasArt=true state=failed plate=hidden suppressed=7",
            BottomUiReceiptFormat.artPlate("app.org.example.app", 4, hasArt = true, state = BottomUiArtState.FAILED, plateVisible = false, suppressed = 7),
        )
        assertTrue(BottomUiReceiptFormat.isArtPlateBreach(hasArt = true, plateVisible = true))
        assertFalse(BottomUiReceiptFormat.isArtPlateBreach(hasArt = false, plateVisible = true))
        assertFalse(BottomUiReceiptFormat.isArtPlateBreach(hasArt = true, plateVisible = false))
    }

    @Test
    fun theArtStateFollowsWhatTheTileKnows() {
        assertEquals(BottomUiArtState.NONE, BottomUiArtState.of(hasArt = false, loaded = false, failed = false))
        assertEquals(BottomUiArtState.NONE, BottomUiArtState.of(hasArt = false, loaded = true, failed = true))
        assertEquals(BottomUiArtState.LOADING, BottomUiArtState.of(hasArt = true, loaded = false, failed = false))
        assertEquals(BottomUiArtState.LOADED, BottomUiArtState.of(hasArt = true, loaded = true, failed = false))
        assertEquals(BottomUiArtState.FAILED, BottomUiArtState.of(hasArt = true, loaded = false, failed = true))
    }

    @Test
    fun focusThemeSessionAndDisplayLines() {
        assertEquals(
            "ev=focus.owner display=4 owner=menu.item.utility prev=none",
            BottomUiReceiptFormat.focusOwner("menu.item.utility", null, 4),
        )
        assertEquals(
            "ev=theme.change display=4 theme=NIFLHEIM prev=MUSPELHEIM transition=theme-switch.crossfade",
            BottomUiReceiptFormat.theme("NIFLHEIM", 4, "MUSPELHEIM", "theme-switch.crossfade"),
        )
        assertEquals("ev=theme.change display=4 theme=NIFLHEIM prev=none transition=none", BottomUiReceiptFormat.theme("NIFLHEIM", 4))
        assertEquals(
            "ev=theme.change display=4 theme=NIFLHEIM prev=MUSPELHEIM transition=none reason=reduced_motion",
            BottomUiReceiptFormat.theme("NIFLHEIM", 4, "MUSPELHEIM", reason = "reduced_motion"),
        )
        assertEquals(
            "ev=session.park session=op-91 display=4 reason=return_to_shelf pkg=org.example.emu",
            BottomUiReceiptFormat.session("park", "op-91", 4, "return_to_shelf", "org.example.emu"),
        )
        assertEquals(
            "ev=session.park_failed session=op-91 display=4 reason=main_game_front_timeout pkg=org.example.emu",
            BottomUiReceiptFormat.session("park_failed", "op-91", 4, "main_game_front_timeout", "org.example.emu"),
        )
        assertEquals(
            "ev=session.exit session=library:12 display=none reason=tab_close pkg=org.example.emu",
            BottomUiReceiptFormat.session("exit", "library:12", null, "tab_close", "org.example.emu"),
        )
        assertEquals(
            "ev=tabs.close outcome=completed display=4 target=org.example.app op=0f1e2d3c",
            BottomUiReceiptFormat.tabClose("completed", 4, "org.example.app", "0f1e2d3c"),
        )
        assertEquals("ev=display.select selected=4 eligible=2+4", BottomUiReceiptFormat.displaySelect(4, listOf(2, 4)))
        assertEquals("ev=display.select selected=none eligible=none", BottomUiReceiptFormat.displaySelect(null, emptyList()))
    }

    @Test
    fun aUrlIsRedactedBecauseScreenScraperUrlsCarryCredentials() {
        val url = "https://api.screenscraper.fr/api2/jeuInfos.php?devid=x&devpassword=secret&ssid=me&sspassword=pw"
        assertEquals("redacted", BottomUiReceiptFormat.token(url))
        val line = BottomUiReceiptFormat.line("art.plate", "tile" to url)
        assertEquals("ev=art.plate tile=redacted", line)
        assertFalse(line.contains("secret"))
    }

    @Test
    fun aFilePathIsRedacted() {
        assertEquals("redacted", BottomUiReceiptFormat.token("/storage/emulated/0/ROMs/nes/Game.nes"))
        assertEquals("redacted", BottomUiReceiptFormat.token("/sdcard/media_art/x.png"))
        assertEquals("redacted", BottomUiReceiptFormat.token("C:\\Users\\a\\x.png"))
        assertEquals("redacted", BottomUiReceiptFormat.token("C:/Users/a/x.png"))
        assertEquals("redacted", BottomUiReceiptFormat.token("content://media/external/images/1"))
        assertEquals("redacted", BottomUiReceiptFormat.token("owner@example.com"))
    }

    @Test
    fun aTitleAFileStemAndACatalogSlugNeverSurviveSanitising() {
        // The old test pinned `Super Mario World` -> `Super_Mario_World`, which is a title surviving. A game id is
        // `catalog:<slug>` or `title:<platform>:<stem>` (GameIdentityBuilder), so these are the real shapes a shelf carries.
        val catalog = BottomUiReceiptFormat.token("catalog:nes:legend-of-zelda")
        assertEquals("game:0f9cf50c1a", catalog)
        assertFalse(catalog.contains("zelda"))
        val stem = BottomUiReceiptFormat.token("title:snes:Super Mario World (USA)")
        assertEquals("game:f1bef2c89f", stem)
        assertFalse(stem.contains("Mario"))
        assertFalse(stem.contains("USA"))
        // Free text (a bare title) is not cleaned into a lookalike, it is hashed.
        val title = BottomUiReceiptFormat.token("Super Mario World")
        assertEquals("text:e64142f04b", title)
        assertFalse(title.contains("Mario"))
        // The other id shapes the resolver writes.
        assertTrue(BottomUiReceiptFormat.token("serial:psx:slus-00594").startsWith("game:"))
        assertTrue(BottomUiReceiptFormat.token("hash:9f86d081884c7d65").startsWith("game:"))
        // Packages, platform ids and the surface vocabulary are stable ids and stay readable.
        assertEquals("org.example.emu", BottomUiReceiptFormat.token("org.example.emu"))
        assertEquals("nes", BottomUiReceiptFormat.token("nes"))
        assertEquals("launching:org.example.player", BottomUiReceiptFormat.token("launching:org.example.player"))
    }

    @Test
    fun anOpaqueIdIsStableShortAndTheSameWhicheverWayItIsWritten() {
        val viaApi = BottomUiReceiptFormat.opaque("game", "catalog:nes:legend-of-zelda")
        assertEquals("game:0f9cf50c1a", viaApi)
        assertEquals(viaApi, BottomUiReceiptFormat.opaque("game", "catalog:nes:legend-of-zelda"))
        // A call site that writes the raw id lands on the same opaque id, so tile= and target= still correlate.
        assertEquals(viaApi, BottomUiReceiptFormat.token("catalog:nes:legend-of-zelda"))
        assertEquals(viaApi, BottomUiReceiptFormat.token(viaApi))
        assertEquals("game:".length + BottomUiReceiptFormat.OPAQUE_HEX_CHARS, viaApi.length)
        assertTrue(BottomUiReceiptFormat.opaque("game", "catalog:nes:a") != BottomUiReceiptFormat.opaque("game", "catalog:nes:b"))
        assertEquals("none", BottomUiReceiptFormat.opaque("game", null))
    }

    @Test
    fun aGameTitleNeverReachesALineThroughAnyField() {
        val id = "catalog:snes:chrono-trigger"
        val lines = listOf(
            BottomUiReceiptFormat.control("platform-shelf.tile", 4, "platform-shelf", target = id),
            BottomUiReceiptFormat.line("art.plate", "tile" to id),
            BottomUiReceiptFormat.line("art.plate", "tile" to "game.$id"),
            BottomUiReceiptFormat.tabClose("completed", 4, id, "op"),
            BottomUiReceiptFormat.session("exit", id, 4, "tab_close", id),
        )
        for (line in lines) {
            assertFalse(line, line.contains("chrono"))
            assertFalse(line, line.contains("trigger"))
            assertFalse(line, line.contains("snes"))
        }
    }

    @Test
    fun valuesNeverBreakTheKeyValueShape() {
        assertEquals(BottomUiReceiptFormat.opaque("text", "a=b"), BottomUiReceiptFormat.token("a=b"))
        assertEquals("text:42144f3939", BottomUiReceiptFormat.token("a=b"))
        assertEquals("none", BottomUiReceiptFormat.token(null))
        assertEquals("none", BottomUiReceiptFormat.token("   "))
        assertEquals("true", BottomUiReceiptFormat.token(true))
        assertEquals("140", BottomUiReceiptFormat.token(140L))
        assertEquals("home.rail.library", BottomUiReceiptFormat.token("home.rail.library"))
        val line = BottomUiReceiptFormat.line("x", "k" to "two words", "n" to 3)
        assertEquals("ev=x k=text:a03f1d6116 n=3", line)
    }

    @Test
    fun aVeryLongValueIsCapped() {
        val value = BottomUiReceiptFormat.token("a".repeat(500))
        assertEquals(BottomUiReceiptFormat.MAX_VALUE_LENGTH, value.length)
    }

    @Test
    fun everyLineIsASingleLine() {
        val line = BottomUiReceiptFormat.line("x", "k" to "a\nb\r\nc")
        assertFalse(line.contains('\n'))
        assertFalse(line.contains('\r'))
    }

    @Test
    fun surfaceStateIsOneLineWithTheStackBottomToTopAndNeverAnOpaqueId() {
        assertEquals(
            "ev=surface.state display=4 stack=lattice,dock,picker reason=change",
            BottomUiReceiptFormat.surfaceState(4, listOf("lattice", "dock", "picker"), "change"),
        )
        assertEquals(
            "an empty stack is none, in the house spelling",
            "ev=surface.state display=4 stack=none reason=heartbeat",
            BottomUiReceiptFormat.surfaceState(4, emptyList(), "heartbeat"),
        )
        assertEquals(
            "ev=surface.state display=5 stack=platform-shelf,tome-open,tome-move-out reason=ready",
            BottomUiReceiptFormat.surfaceState(5, listOf("platform-shelf", "tome-open", "tome-move-out"), "ready"),
        )
    }

    @Test
    fun theMusicPanelIsAPresentationOwnedSurfaceDrawnWithTheMediaTileOverTheActivityPage() {
        assertEquals("music-panel", BottomSurface.MUSIC_PANEL)
        assertTrue("the Presentation's composition reports it, so a new Presentation may reset it", BottomSurface.MUSIC_PANEL in BottomSurface.PRESENTATION_OWNED)
        assertEquals(
            "it is a scene's own overlay layer, with the media tile and an open Tome",
            BottomSurface.drawingLayer(BottomSurface.ACTIVITY_MEDIA, raised = false),
            BottomSurface.drawingLayer(BottomSurface.MUSIC_PANEL, raised = false),
        )
        assertTrue(BottomSurface.drawingLayer(BottomSurface.MUSIC_PANEL, raised = false) > BottomSurface.drawingLayer(BottomSurface.ACTIVITY, raised = false))
        assertTrue(BottomSurface.drawingLayer(BottomSurface.MUSIC_PANEL, raised = false) < BottomSurface.drawingLayer(BottomSurface.DOCK, raised = false))
        assertTrue("a surface the layer table knows is not drawn above everything", BottomSurface.drawingLayer(BottomSurface.MUSIC_PANEL, raised = false) < BottomSurface.UNKNOWN_LAYER)
    }
}
