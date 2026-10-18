package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFocusTest {

    @Test
    fun `rail endpoints keep one explicit scroll owner while middle categories auto reveal`() {
        assertFalse(settingsRailUsesAutomaticBringIntoView(SettingsCategory.PROFILE))
        assertTrue(settingsRailUsesAutomaticBringIntoView(SettingsCategory.LIBRARY))
        assertTrue(settingsRailUsesAutomaticBringIntoView(SettingsCategory.CONTROLS))
        assertFalse(settingsRailUsesAutomaticBringIntoView(SettingsCategory.ABOUT))
    }

    @Test
    fun `detail edge reveal conflates the same target and reversals supersede it`() {
        assertTrue(settingsDetailRevealShouldLaunch(activeTarget = null, active = false, requestedTarget = 800))
        assertFalse(settingsDetailRevealShouldLaunch(activeTarget = 800, active = true, requestedTarget = 800))
        assertTrue(settingsDetailRevealShouldLaunch(activeTarget = 800, active = true, requestedTarget = 0))
        assertTrue(settingsDetailRevealShouldLaunch(activeTarget = 800, active = false, requestedTarget = 800))
    }

    @Test
    fun staleDetailRecoveryHasAFiniteFrameBudget() {
        assertTrue(SettingsDetailRestoreAttemptLimit in 1..120)
    }

    @Test
    fun railKeysAreStableAndRoundTripToTheirCategories() {
        val expected = mapOf(
            SettingsCategory.PROFILE to "settings/rail/PROFILE",
            SettingsCategory.LIBRARY to "settings/rail/LIBRARY",
            SettingsCategory.EMULATORS to "settings/rail/EMULATORS",
            SettingsCategory.APPEARANCE to "settings/rail/APPEARANCE",
            SettingsCategory.MUSIC to "settings/rail/MUSIC",
            SettingsCategory.CONTROLS to "settings/rail/CONTROLS",
            SettingsCategory.ABOUT to "settings/rail/ABOUT",
        )
        assertEquals("every rail entry has a key in this table", SettingsCategory.rail.toSet(), expected.keys)

        expected.forEach { (category, value) ->
            val key = settingsRailFocusKey(category)
            assertEquals(value, key.value)
            assertEquals(category, settingsRailCategoryForFocusKey(key))
            assertEquals(category, settingsCategoryForFocusKey(key))
            assertEquals(SettingsRailScope, settingsScopeForFocusKey(key))
        }
    }

    @Test
    fun detailKeysRoundTripAndMapDynamicRowsToTheirOwningCategory() {
        val expected = mapOf(
            "profile/name" to SettingsCategory.PROFILE,
            "library/backup/export" to SettingsCategory.LIBRARY,
            "library/hidden/game-42" to SettingsCategory.LIBRARY,
            "emulators/change/ps1" to SettingsCategory.EMULATORS,
            "appearance/backdrop" to SettingsCategory.APPEARANCE,
            // Without the `music/` allowlist entry every one of these canonicalises to null and the pane is dead.
            "music/player/enabled" to SettingsCategory.MUSIC,
            "music/custom/folder" to SettingsCategory.MUSIC,
            "music/game/launchsound/volume" to SettingsCategory.MUSIC,
            "controls/special/overlay" to SettingsCategory.CONTROLS,
            "about/credits/gametdb" to SettingsCategory.ABOUT,
        )

        expected.forEach { (rowId, category) ->
            val key = settingsDetailFocusKey(rowId)
            assertEquals("settings/detail/$rowId", key.value)
            assertEquals(key, detailKey(rowId))
            assertEquals(rowId, settingsDetailRowIdForFocusKey(key))
            assertEquals(category, settingsCategoryForFocusKey(key))
            assertEquals(DetailScope, settingsScopeForFocusKey(key))
        }
    }

    @Test
    fun emulatorsEntryTargetFollowsTheFirstSelectableLivePlatform() {
        val unsupported = EmulatorAssignment(
            platform = "Unknown",
            emulator = null,
            status = SettingsSample.SampleStatus.NEEDS_EMULATOR,
            platformId = "unknown",
            routeState = EmulatorRouteState.UNSUPPORTED,
        )
        val dolphin = EmulatorAssignment(
            platform = "GameCube",
            emulator = "Dolphin",
            status = SettingsSample.SampleStatus.READY,
            platformId = "gamecube",
            routeState = EmulatorRouteState.OPEN_ONLY,
        )
        val rows = listOf(unsupported, dolphin)
        val firstSelectable = firstSelectableEmulatorAssignment(rows)

        assertEquals(dolphin, firstSelectable)
        assertEquals(
            FirstSelectableEmulatorRowId,
            emulatorAssignmentRowId(dolphin, index = 1, firstSelectable = firstSelectable),
        )
        assertEquals(
            "emulators/change/unknown",
            emulatorAssignmentRowId(unsupported, index = 0, firstSelectable = firstSelectable),
        )
        assertEquals(FirstSelectableEmulatorRowId, settingsFirstDetailRowId(SettingsCategory.EMULATORS))
    }

    @Test
    fun removableSourceKeysAreStableOpaqueAndRemainInLibraryScope() {
        val rootUri = "content://com.android.externalstorage.documents/tree/ABCD-1234%3ARoms"
        val rowId = librarySourceRemovalRowId(rootUri)

        assertEquals(rowId, librarySourceRemovalRowId(rootUri))
        assertTrue(rowId.startsWith("library/source/remove/"))
        assertFalse(rowId.contains("content"))
        assertFalse(rowId.contains("ABCD"))
        assertEquals(SettingsCategory.LIBRARY, settingsCategoryForFocusKey(detailKey(rowId)))
        assertEquals(DetailScope, settingsScopeForFocusKey(detailKey(rowId)))
    }

    @Test
    fun retiredTroubleshootingFocusMigratesOrFailsClosedToProfile() {
        assertEquals(
            settingsDetailFocusKey("appearance/theme"),
            canonicalSettingsFocusKey(FocusKey("settings/detail/profile/theme")),
        )
        assertEquals(
            settingsDetailFocusKey("library/rescan"),
            canonicalSettingsFocusKey(FocusKey("settings/detail/library/rescanAll")),
        )
        assertEquals(
            settingsRailFocusKey(SettingsCategory.PROFILE),
            canonicalSettingsFocusKey(FocusKey("settings/rail/TROUBLESHOOTING")),
        )
        assertEquals(
            settingsDetailFocusKey("library/rescan"),
            canonicalSettingsFocusKey(FocusKey("settings/detail/trouble/rescan")),
        )
        assertEquals(
            settingsDetailFocusKey("controls/special/overlay"),
            canonicalSettingsFocusKey(FocusKey("settings/detail/trouble/special/overlay")),
        )
        assertEquals(
            settingsDetailFocusKey("about/credits/gametdb"),
            canonicalSettingsFocusKey(FocusKey("settings/detail/trouble/gametdb")),
        )
        assertEquals(
            settingsDetailFocusKey(FirstSelectableEmulatorRowId),
            canonicalSettingsFocusKey(FocusKey("settings/detail/emulators/bios/grant")),
        )
        assertEquals(
            settingsRailFocusKey(SettingsCategory.PROFILE),
            canonicalSettingsFocusKey(FocusKey("settings/detail/trouble/posture")),
        )
        assertEquals(
            settingsRailFocusKey(SettingsCategory.PROFILE),
            canonicalSettingsFocusKey(FocusKey("settings/detail/trouble/rebind")),
        )
        assertEquals(
            settingsDetailFocusKey("appearance/enrichment/screenscraper"),
            canonicalSettingsFocusKey(
                FocusKey("settings/detail/appearance/enrichment/screenscraper/disconnect"),
            ),
        )
        assertEquals(
            settingsDetailFocusKey(ControllerShortcutQuickLaunchRowId),
            canonicalSettingsFocusKey(FocusKey("settings/detail/controls/controller/settings")),
        )
    }

    @Test
    fun nonSettingsAndMalformedKeysDoNotResolveAsRestorableTargets() {
        assertNull(settingsCategoryForFocusKey(FocusKey("library/shelf/ps1")))
        assertNull(settingsRailCategoryForFocusKey(FocusKey("settings/rail/PROFILE/extra")))
        assertNull(settingsCategoryForFocusKey(FocusKey("settings/detail/unknown/row")))
        assertNull(settingsScopeForFocusKey(FocusKey("settings/detail/unknown/row")))
        assertNull(settingsDetailRowIdForFocusKey(FocusKey("settings/detail/")))
    }

    @Test
    fun initialCategoryInfersScopeAndRejectsExplicitScopeMismatch() {
        val detailKey = settingsDetailFocusKey("appearance/backdrop")
        val railKey = settingsRailFocusKey(SettingsCategory.LIBRARY)

        assertEquals(SettingsCategory.APPEARANCE, settingsInitialFocusCategory(detailKey))
        assertEquals(
            SettingsCategory.APPEARANCE,
            settingsInitialFocusCategory(detailKey, DetailScope),
        )
        assertNull(settingsInitialFocusCategory(detailKey, SettingsRailScope))
        assertEquals(SettingsCategory.LIBRARY, settingsInitialFocusCategory(railKey, SettingsRailScope))
        assertNull(settingsInitialFocusCategory(railKey, DetailScope))
    }

    @Test
    fun detailRestoreWaitsForExactRegistrationThenLandsDirectlyOnTarget() {
        val authority = DefaultFocusAuthority()
        val railKey = settingsRailFocusKey(SettingsCategory.APPEARANCE)
        val firstDetailKey = settingsDetailFocusKey("appearance/theme")
        val targetKey = settingsDetailFocusKey("appearance/backdrop")

        authority.register(railKey, SettingsRailScope, bounds(top = 0f))
        authority.pushScope(SettingsRailScope)
        authority.register(firstDetailKey, DetailScope, bounds(top = 0f))

        assertFalse(restoreSettingsDetailFocusIfReady(authority, targetKey))
        assertEquals(SettingsRailScope, authority.state.value.scope)
        assertEquals(railKey, authority.state.value.focused)

        authority.register(targetKey, DetailScope, bounds(top = 100f))

        assertTrue(restoreSettingsDetailFocusIfReady(authority, targetKey))
        assertEquals(DetailScope, authority.state.value.scope)
        assertEquals(targetKey, authority.state.value.focused)

        // Re-applying the one-shot helper must not push a duplicate detail scope.
        assertTrue(restoreSettingsDetailFocusIfReady(authority, targetKey))
        authority.popScope()
        assertEquals(SettingsRailScope, authority.state.value.scope)
        assertEquals(railKey, authority.state.value.focused)
    }

    @Test
    fun `rail navigation burst waits for and lands on exact new detail row`() {
        val authority = DefaultFocusAuthority()
        SettingsCategory.rail.forEachIndexed { index, category ->
            authority.register(
                settingsRailFocusKey(category),
                SettingsRailScope,
                bounds(top = index * 100f),
            )
        }
        authority.pushScope(SettingsRailScope)
        authority.requestFocus(settingsRailFocusKey(SettingsCategory.PROFILE))
        val staleProfileRow = settingsDetailFocusKey(settingsFirstDetailRowId(SettingsCategory.PROFILE))
        authority.register(staleProfileRow, DetailScope, bounds(top = 0f))
        var opened: SettingsCategory? = null

        assertTrue(
            dispatchToSettings(
                authority = authority,
                action = ControllerAction.NAV_DOWN,
                onRailOpen = { opened = it },
                onRowActivate = {},
                onExit = {},
            ),
        )

        assertEquals(SettingsCategory.LIBRARY, opened)
        assertEquals(
            settingsRailFocusKey(SettingsCategory.LIBRARY),
            authority.state.value.focused,
        )

        assertTrue(
            dispatchToSettings(
                authority = authority,
                action = ControllerAction.NAV_RIGHT,
                onRailOpen = { opened = it },
                onRowActivate = {},
                onExit = {},
            ),
        )
        assertEquals(DetailScope, authority.state.value.scope)
        assertEquals(
            SettingsCategory.LIBRARY,
            settingsRailCategoryForFocusKey(authority.rememberedFocusIn(SettingsRailScope)),
        )
        val libraryTarget = settingsDetailFocusKey(settingsFirstDetailRowId(SettingsCategory.LIBRARY))
        assertNull("stale Profile detail must never flash as focused", authority.state.value.focused)
        assertEquals(libraryTarget, authority.rememberedFocusIn(DetailScope))

        // Model the next composition: the old pane leaves, then the requested category registers.
        authority.unregister(staleProfileRow)
        authority.register(libraryTarget, DetailScope, bounds(top = 0f))

        assertEquals(DetailScope, authority.state.value.scope)
        assertEquals(libraryTarget, authority.state.value.focused)
    }

    // -- The Library pane's side-by-side pairs (update's sweep, 10-09: EXPORT BACKUP and EXPORT RECOVERY could not be reached by pad) --

    private val exportBackup = settingsDetailFocusKey("library/backup/export")
    private val importBackup = settingsDetailFocusKey("library/backup/import")
    private val exportRecovery = settingsDetailFocusKey("library/recovery/export")
    private val restoreRecovery = settingsDetailFocusKey("library/recovery/restore")
    private val addFolders = settingsDetailFocusKey("library/add")
    private val libraryRail = settingsRailFocusKey(SettingsCategory.LIBRARY)

    private fun box(left: Float, top: Float, right: Float, bottom: Float) = FocusBounds(left, top, right, bottom)

    /**
     * The Library pane as it lays out: the full-width "Library folders" button, then the Backup pair and the Recovery pair, each a Row of two equal halves. IMPORT is registered BEFORE EXPORT on
     * purpose: when the row above sits exactly between two halves the nearest-centre pick is a tie that registration order decides, and this is the order that lands on the right half.
     */
    private fun libraryPane(): DefaultFocusAuthority {
        val authority = DefaultFocusAuthority()
        authority.register(libraryRail, SettingsRailScope, box(0f, 0f, 160f, 40f))
        authority.pushScope(SettingsRailScope)
        authority.requestFocus(libraryRail)
        authority.pushScope(DetailScope)
        authority.register(addFolders, DetailScope, box(200f, 0f, 600f, 48f))
        authority.register(importBackup, DetailScope, box(404f, 100f, 600f, 148f))
        authority.register(exportBackup, DetailScope, box(200f, 100f, 396f, 148f))
        authority.register(restoreRecovery, DetailScope, box(404f, 200f, 600f, 248f))
        authority.register(exportRecovery, DetailScope, box(200f, 200f, 396f, 248f))
        authority.requestFocus(addFolders)
        return authority
    }

    private fun press(authority: DefaultFocusAuthority, action: ControllerAction): Boolean =
        dispatchToSettings(authority = authority, action = action, onRailOpen = {}, onRowActivate = {}, onExit = {})

    @Test
    fun `DOWN into a side-by-side pair lands on its left half, from either side of the row above`() {
        val authority = libraryPane()
        assertTrue(press(authority, ControllerAction.NAV_DOWN))
        assertEquals("the left half, though the nearest-centre tie went to the right", exportBackup, authority.state.value.focused)
        // A row above that sits right of centre prefers the right half by distance; the pair still lands left.
        authority.register(addFolders, DetailScope, box(300f, 0f, 700f, 48f))
        authority.requestFocus(addFolders)
        assertTrue(press(authority, ControllerAction.NAV_DOWN))
        assertEquals(exportBackup, authority.state.value.focused)
    }

    @Test
    fun `RIGHT steps from the left half to the right, LEFT from the right half to the left, and LEFT from the left half leaves for the rail`() {
        val authority = libraryPane()
        authority.requestFocus(exportBackup)
        assertTrue(press(authority, ControllerAction.NAV_RIGHT))
        assertEquals(importBackup, authority.state.value.focused)
        assertFalse("RIGHT past the right half is not handled", press(authority, ControllerAction.NAV_RIGHT))
        assertEquals(importBackup, authority.state.value.focused)
        assertTrue(press(authority, ControllerAction.NAV_LEFT))
        assertEquals("LEFT from the right half goes to its sibling, not the sidebar", exportBackup, authority.state.value.focused)
        assertEquals(DetailScope, authority.state.value.scope)
        assertTrue(press(authority, ControllerAction.NAV_LEFT))
        assertEquals("LEFT from the left half is the rail's", SettingsRailScope, authority.state.value.scope)
        assertEquals(libraryRail, authority.state.value.focused)
    }

    @Test
    fun `the same two rules hold for the Recovery pair, and a move between the pairs keeps its column`() {
        val authority = libraryPane()
        authority.requestFocus(importBackup)
        assertTrue(press(authority, ControllerAction.NAV_DOWN))
        assertEquals(restoreRecovery, authority.state.value.focused)
        assertTrue(press(authority, ControllerAction.NAV_LEFT))
        assertEquals(exportRecovery, authority.state.value.focused)
        assertTrue(press(authority, ControllerAction.NAV_UP))
        assertEquals("UP from the left half stays in the left column", exportBackup, authority.state.value.focused)
        assertTrue(press(authority, ControllerAction.NAV_RIGHT))
        assertTrue(press(authority, ControllerAction.NAV_DOWN))
        assertEquals(restoreRecovery, authority.state.value.focused)
        assertTrue(press(authority, ControllerAction.NAV_UP))
        assertEquals(importBackup, authority.state.value.focused)
        // UP out of the pair, back to the full-width row, then DOWN again: the left half every time.
        assertTrue(press(authority, ControllerAction.NAV_UP))
        assertEquals(addFolders, authority.state.value.focused)
        assertTrue(press(authority, ControllerAction.NAV_DOWN))
        assertEquals(exportBackup, authority.state.value.focused)
    }

    @Test
    fun `every button of both pairs is reachable by pad from the row above them`() {
        val authority = libraryPane()
        val reached = linkedSetOf<FocusKey?>(authority.state.value.focused)
        val moves = listOf(
            ControllerAction.NAV_DOWN, ControllerAction.NAV_RIGHT, ControllerAction.NAV_LEFT, ControllerAction.NAV_DOWN,
            ControllerAction.NAV_RIGHT, ControllerAction.NAV_LEFT,
        )
        moves.forEach { move ->
            press(authority, move)
            reached += authority.state.value.focused
        }
        assertTrue("reached $reached", reached.containsAll(listOf(addFolders, exportBackup, importBackup, exportRecovery, restoreRecovery)))
    }

    @Test
    fun `a control in no pair keeps LEFT as the way back to the rail, and the pair table matches the Backup section's rows`() {
        val authority = libraryPane()
        assertTrue(press(authority, ControllerAction.NAV_LEFT))
        assertEquals("LEFT on the full-width row returns to the rail, as it always did", SettingsRailScope, authority.state.value.scope)
        assertNull(settingsDetailPairSibling(addFolders, ControllerAction.NAV_LEFT))
        assertEquals(importBackup, settingsDetailPairSibling(exportBackup, ControllerAction.NAV_RIGHT))
        assertNull("the outer edges belong to the rail and to nothing", settingsDetailPairSibling(exportBackup, ControllerAction.NAV_LEFT))
        assertNull(settingsDetailPairSibling(restoreRecovery, ControllerAction.NAV_RIGHT))
        // BackupSection lays each pair out as one Row of its two controls, left then right, under these ids.
        val section = sourceOf("SettingsDetail.kt").substringAfter("private fun BackupSection(").substringBefore("// -- EMULATORS").substringBefore("// ── EMULATORS")
        SettingsDetailSidePairs.forEach { (left, right) ->
            assertTrue(section.contains("key = detailKey(\"$left\")") && section.contains("key = detailKey(\"$right\")"))
            assertTrue("$left comes before $right", section.indexOf("\"$left\"") < section.indexOf("\"$right\""))
        }
        assertEquals("one Row per pair, each the ring's whole reach below its caption", SettingsDetailSidePairs.size, Regex("""Row\(modifier = Modifier\.padding\(top = MimirFocusTileRingReach\), horizontalArrangement = Arrangement\.spacedBy\(MimirSpacing\.md\)\)""").findAll(section).count())
    }

    private fun sourceOf(name: String): String = listOf(
        java.io.File("feature-settings/src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/$name"),
        java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/$name"),
    ).first(java.io.File::isFile).readText()

    private fun bounds(top: Float) = FocusBounds(
        left = 0f,
        top = top,
        right = 100f,
        bottom = top + 50f,
    )
}
