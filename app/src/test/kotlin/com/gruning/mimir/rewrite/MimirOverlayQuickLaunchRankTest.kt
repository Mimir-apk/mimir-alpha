package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate finding 2 (2026-08-11): [mimirOverlayOutranksQuickLaunch] hand-mirrors
 * [resolveMimirOverlayStack]'s build order; the exhaustive `when` forces a DECISION on a new
 * overlay kind but not a CONSISTENT one. This pins the equivalence: with every flag raised, a kind
 * outranks the panel exactly when the stack builds it earlier than HOME_QUICK_LAUNCH.
 */
class MimirOverlayQuickLaunchRankTest {

    @Test
    fun `outranks mirrors the stack build order for every overlay kind`() {
        val everything = resolveMimirOverlayStack(
            MimirOverlaySnapshot(
                returnCover = true,
                companionPermission = true,
                migration = true,
                artworkGet = true,
                artworkFetch = true,
                artworkConnect = true,
                profileName = true,
                steamGridDbKey = true,
                retroAchievementsKey = true,
                gameTitleEdit = true,
                controllerMapping = true,
                controllerShortcutCapture = true,
                folderBrowser = true,
                homeQuickLaunchPicker = true,
                homeQuickLaunch = true,
                homeTileMenu = true,
                homeTomeLibrary = true,
                platformTileMenu = true,
                gamelistAchievements = true,
                homeOpenWith = true,
                // Home's disc / version menu . Added to the all-flags fixture
                // deliberately: this test is the guard that a new overlay kind gets a stack position.
                homeLaunchChoice = true,
                homeLaunchCard = true,
                platformPicker = true,
                backdropPicker = true,
                themePicker = true,
                // The two appearance-image pickers (concept A, 2026-09-30): the guard that each gets a slot.
                backgroundPicker = true,
                mimirButtonPicker = true,
                accentPicker = true,
                focusRingDial = true,
                // Settings › Music's two overlays (2026-09-30), in the all-flags fixture for the same reason.
                musicSourcePicker = true,
                musicVolumeDial = true,
                equalizerEditor = true,
                brightness = true,
            ),
        ).entries

        assertEquals(
            "the all-flags stack must place every overlay kind exactly once",
            MimirOverlayKind.entries.toSet(),
            everything.toSet(),
        )
        val panelIndex = everything.indexOf(MimirOverlayKind.HOME_QUICK_LAUNCH)
        assertTrue(panelIndex >= 0)
        MimirOverlayKind.entries.forEach { kind ->
            assertEquals(
                "outranks(${kind.name}) must equal its stack position relative to the panel",
                everything.indexOf(kind) < panelIndex,
                mimirOverlayOutranksQuickLaunch(kind),
            )
        }
    }

    private val kept = setOf(
        MimirOverlayKind.FOLDER_BROWSER,
        MimirOverlayKind.ARTWORK_GET,
        MimirOverlayKind.ARTWORK_FETCH,
    )

    private fun everythingStack(): List<MimirOverlayKind> = resolveMimirOverlayStack(
        MimirOverlayKind.entries.fold(MimirOverlaySnapshot()) { snapshot, kind -> snapshot.withOverlay(kind) },
    ).entries

    /**
     * walk-32k: the panel opens over these, and only these, without dismissing them. The ScreenScraper sign-in is
     * deliberately NOT among them (lead, 2026-09-30): it is credential entry on the in-app keyboard.
     */
    @Test
    fun `the kept surfaces are the folder browser and the Get art and fetch cards, and no kind is both kept and outranking`() {
        assertEquals(kept, MimirOverlayKind.entries.filter(::mimirOverlayStaysUnderQuickLaunch).toSet())
        MimirOverlayKind.entries.forEach { kind ->
            assertTrue(
                "${kind.name} cannot both outrank the panel and sit under it",
                !(mimirOverlayOutranksQuickLaunch(kind) && mimirOverlayStaysUnderQuickLaunch(kind)),
            )
        }
    }

    @Test
    fun `the kept surfaces sit directly below the panel, so the panel takes the controller and Back`() {
        val everything = everythingStack()
        val panelIndex = everything.indexOf(MimirOverlayKind.HOME_QUICK_LAUNCH)

        assertEquals(
            "the three kept kinds follow the panel with nothing between, in their existing relative order",
            listOf(
                MimirOverlayKind.ARTWORK_GET,
                MimirOverlayKind.ARTWORK_FETCH,
                MimirOverlayKind.FOLDER_BROWSER,
            ),
            everything.subList(panelIndex + 1, panelIndex + 1 + kept.size),
        )
    }

    /**
     * The sign-in ranks above the panel with the text-entry modals, and above the fetch card. That reverses the order
     * NewGameEnrichPromptState.deferForConnect was written under (FETCH above CONNECT); the card is still stepped aside
     * there, so nothing relies on the old order.
     */
    @Test
    fun `the ScreenScraper sign-in ranks above the panel and the fetch card, with the text-entry modals`() {
        val everything = everythingStack()
        val panelIndex = everything.indexOf(MimirOverlayKind.HOME_QUICK_LAUNCH)
        val connect = everything.indexOf(MimirOverlayKind.ARTWORK_CONNECT)

        assertTrue("above the panel", connect in 0 until panelIndex)
        assertTrue("above the fetch card", connect < everything.indexOf(MimirOverlayKind.ARTWORK_FETCH))
        assertTrue("above the text-entry modals it always outranked", connect < everything.indexOf(MimirOverlayKind.PROFILE_NAME))
        assertTrue(mimirOverlayOutranksQuickLaunch(MimirOverlayKind.ARTWORK_CONNECT))
        assertTrue(!mimirOverlayStaysUnderQuickLaunch(MimirOverlayKind.ARTWORK_CONNECT))
    }

    @Test
    fun `everything that still refuses ranks above the panel, the tile menu and the rest below the kept surfaces`() {
        val everything = everythingStack()
        val panelIndex = everything.indexOf(MimirOverlayKind.HOME_QUICK_LAUNCH)
        val lastKeptIndex = kept.maxOf { everything.indexOf(it) }

        assertEquals(
            setOf(
                MimirOverlayKind.RETURN_COVER,
                MimirOverlayKind.COMPANION_PERMISSION,
                MimirOverlayKind.MIGRATION,
                MimirOverlayKind.ARTWORK_CONNECT,
                MimirOverlayKind.PROFILE_NAME,
                MimirOverlayKind.STEAMGRIDDB_KEY,
                MimirOverlayKind.RETROACHIEVEMENTS_KEY,
                MimirOverlayKind.GAME_TITLE_EDIT,
                MimirOverlayKind.CONTROLLER_SHORTCUT_CAPTURE,
                MimirOverlayKind.CONTROLLER_MAPPING,
                MimirOverlayKind.HOME_QUICK_LAUNCH_PICKER,
            ),
            everything.subList(0, panelIndex).toSet(),
        )
        assertTrue(
            "the tile menu and everything else the sweep dismisses stays below the kept surfaces",
            everything.indexOf(MimirOverlayKind.HOME_TILE_MENU) > lastKeptIndex,
        )
    }

    @Test
    fun `presented is the top, except that a kept surface stays presented beneath the panel and its picker`() {
        fun presented(vararg open: MimirOverlayKind) = resolveMimirOverlayStack(snapshotOf(*open)).presented

        assertNull(presented())
        assertEquals(MimirOverlayKind.FOLDER_BROWSER, presented(MimirOverlayKind.FOLDER_BROWSER))
        assertEquals(
            MimirOverlayKind.FOLDER_BROWSER,
            presented(MimirOverlayKind.HOME_QUICK_LAUNCH, MimirOverlayKind.FOLDER_BROWSER),
        )
        assertEquals(
            "the picker is the panel's child",
            MimirOverlayKind.ARTWORK_FETCH,
            presented(
                MimirOverlayKind.HOME_QUICK_LAUNCH_PICKER,
                MimirOverlayKind.HOME_QUICK_LAUNCH,
                MimirOverlayKind.ARTWORK_FETCH,
                MimirOverlayKind.BRIGHTNESS,
            ),
        )
        assertEquals(
            "the panel over a surface that is not kept is presented as itself, exactly as before",
            MimirOverlayKind.HOME_QUICK_LAUNCH,
            presented(MimirOverlayKind.HOME_QUICK_LAUNCH, MimirOverlayKind.HOME_TILE_MENU),
        )
        assertEquals(
            "a modal that outranks the panel is presented as itself, kept surface or not",
            MimirOverlayKind.MIGRATION,
            presented(
                MimirOverlayKind.MIGRATION,
                MimirOverlayKind.HOME_QUICK_LAUNCH,
                MimirOverlayKind.FOLDER_BROWSER,
            ),
        )
    }

    @Test
    fun `the new order changes no relative rank the tile menu and the settings pickers relied on`() {
        // FilesRoutePolicy / AppBrightnessCoordinator / HomeCustomEnrichPicker all cite "FOLDER_BROWSER above
        // BRIGHTNESS" and "ARTWORK_* above HOME_TILE_MENU"; moving the kept block up past the panel must not break them.
        val everything = everythingStack()
        val below = listOf(
            MimirOverlayKind.HOME_TILE_MENU,
            MimirOverlayKind.BRIGHTNESS,
            MimirOverlayKind.PLATFORM_PICKER,
            MimirOverlayKind.THEME_PICKER,
        )
        kept.forEach { keptKind ->
            below.forEach { lower ->
                assertTrue(
                    "${keptKind.name} must still rank above ${lower.name}",
                    everything.indexOf(keptKind) < everything.indexOf(lower),
                )
            }
        }
    }
}
