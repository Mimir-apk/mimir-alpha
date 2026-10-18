package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.FocusKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfModeMemoryTest {

    @Test
    fun destinationReentrySeedsOnlyTheRetainedCurrentModeAndKey() {
        val retained = FocusKey("library/shelf/ps2")

        assertEquals(
            mapOf(LibraryShelfMode.EMULATION to retained),
            initialShelfModeFocusMemory(LibraryShelfMode.EMULATION, retained),
        )
        assertTrue(
            initialShelfModeFocusMemory(LibraryShelfMode.ANDROID_GAMES, centeredKey = null).isEmpty(),
        )
    }

    @Test
    fun reentrySeedRestoresAValidTileButCannotOverrideLiveOrRemovedFocus() {
        val stops = listOf(
            FocusKey("library/shelf/ps1"),
            FocusKey("library/shelf/ps2"),
            FocusKey("library/shelf/ps3"),
        )
        val retained = initialShelfModeFocusMemory(
            LibraryShelfMode.EMULATION,
            stops[1],
        ).getValue(LibraryShelfMode.EMULATION)

        assertEquals(
            stops[1],
            shelfModeLandingTarget(
                focusedKey = null,
                rememberedKey = retained,
                orderedStopKeys = stops,
                emptyStopKey = AddKey,
            ),
        )
        assertNull(
            shelfModeLandingTarget(
                focusedKey = stops[2],
                rememberedKey = retained,
                orderedStopKeys = stops,
                emptyStopKey = AddKey,
            ),
        )
        assertEquals(
            stops.first(),
            shelfModeLandingTarget(
                focusedKey = null,
                rememberedKey = FocusKey("library/native/cross-mode"),
                orderedStopKeys = stops,
                emptyStopKey = AddKey,
            ),
        )
    }

    @Test
    fun eachShelfModeRestoresItsOwnLastValidKey() {
        val emulationStops = listOf(
            FocusKey("library/shelf/ps1"),
            FocusKey("library/shelf/ps2"),
        )
        val gameStops = nativeStops("game", 3)
        val memory = mapOf(
            LibraryShelfMode.EMULATION to emulationStops[0],
            LibraryShelfMode.ANDROID_GAMES to gameStops[1],
        )

        assertEquals(
            emulationStops[0],
            shelfModeLandingTarget(
                focusedKey = null,
                rememberedKey = memory[LibraryShelfMode.EMULATION],
                orderedStopKeys = emulationStops,
                emptyStopKey = AddKey,
            ),
        )
        assertEquals(
            gameStops[1],
            shelfModeLandingTarget(
                focusedKey = null,
                rememberedKey = memory[LibraryShelfMode.ANDROID_GAMES],
                orderedStopKeys = gameStops,
                emptyStopKey = AddKey,
            ),
        )
    }

    @Test
    fun firstStopIsUsedOnlyWhenModeHasNoValidMemory() {
        val stops = nativeStops("game", 3)

        assertEquals(
            stops.first(),
            shelfModeLandingTarget(
                focusedKey = null,
                rememberedKey = null,
                orderedStopKeys = stops,
                emptyStopKey = AddKey,
            ),
        )
        assertEquals(
            stops.first(),
            shelfModeLandingTarget(
                focusedKey = null,
                rememberedKey = FocusKey("library/native/removed"),
                orderedStopKeys = stops,
                emptyStopKey = AddKey,
            ),
        )
    }

    @Test
    fun asyncNativeFocusCannotBeOverriddenByOlderModeMemory() {
        val stops = nativeStops("app", 3)

        assertNull(
            shelfModeLandingTarget(
                focusedKey = stops[2],
                rememberedKey = stops[1],
                orderedStopKeys = stops,
                emptyStopKey = AddKey,
            ),
        )
    }

    @Test
    fun emptyModeFallsBackToAddWithoutRequestingItTwice() {
        assertEquals(
            AddKey,
            shelfModeLandingTarget(
                focusedKey = null,
                rememberedKey = FocusKey("library/native/not-loaded"),
                orderedStopKeys = emptyList(),
                emptyStopKey = AddKey,
            ),
        )
        assertNull(
            shelfModeLandingTarget(
                focusedKey = AddKey,
                rememberedKey = null,
                orderedStopKeys = emptyList(),
                emptyStopKey = AddKey,
            ),
        )
    }

    // ── Policy 2026-09-02 ("MEMORY WINS", Thor certification 6/6) ──

    @Test
    fun modeSwitchLandingPrefersRenderMemoryOverDispatchStamp() {
        val stops = listOf(
            FocusKey("library/shelf/ps1"),
            FocusKey("library/shelf/ps2"),
            FocusKey("library/shelf/ps3"),
        )
        // Render-level memory (a past visit via onCenter) says ps3; the mode-switch reducer stamp says ps1.
        // Library gives remembered render focus precedence over a racing ring-first stamp.
        // Home uses ring-first precedence, but Library preserves the remembered tile.
        assertEquals(
            "render memory must win over the dispatch reducer's ring-first stamp",
            stops[2],
            shelfModeRememberedLandingKey(
                renderMemory = stops[2],
                dispatchStamp = stops[0],
                orderedStopKeys = stops,
            ),
        )
    }

    @Test
    fun modeSwitchLandingFallsBackToDispatchStampOnAModesFirstEverVisit() {
        val stops = listOf(
            FocusKey("library/shelf/ps1"),
            FocusKey("library/shelf/ps2"),
        )
        // No render-level memory yet (rememberedKeysByMode has nothing for this mode this session) --
        // the dispatch reducer's ring-first stamp is not dead, it is exactly this fallback.
        assertEquals(
            stops[0],
            shelfModeRememberedLandingKey(
                renderMemory = null,
                dispatchStamp = stops[0],
                orderedStopKeys = stops,
            ),
        )
    }

    @Test
    fun modeSwitchLandingRejectsAStaleCrossModeDispatchStamp() {
        val stops = listOf(FocusKey("library/shelf/ps1"), FocusKey("library/shelf/ps2"))
        // A stamp left over from a DIFFERENT mode (or a since-removed tile) must not leak in as this
        // mode's fallback.
        assertNull(
            shelfModeRememberedLandingKey(
                renderMemory = null,
                dispatchStamp = FocusKey("library/native/cross-mode"),
                orderedStopKeys = stops,
            ),
        )
    }

    @Test
    fun nativeOwnerCopyUsesAddGame() {
        // ANDROID_APPS retired 2026-08-21: LibraryShelfMode has no app-owned copy, and there is no Add app pill on any
        // shelf. The hide prompt used to name one ("Restore it later from Add app") through nativeRecoveryAddLabel; walk-32h
        // retired that function, and the prompt and receipt name the pill that exists (LibraryHideCopy.NativeReturnPlace).
        val games = LibraryShelfMode.ANDROID_GAMES.nativeAddOwnerCopy()

        assertEquals(NativeAddOwnerCopy("Add game", "game", "games"), games)
    }

    private fun nativeStops(owner: String, count: Int): List<FocusKey> =
        (0 until count).map { nativeShelfKey("com.example.$owner$it") }

    private companion object {
        val AddKey = FocusKey("library/shelf/add")
    }
}
