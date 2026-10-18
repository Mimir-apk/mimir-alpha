package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RC-32 (audit 2026-10-14, input hazard): a confirm dialog opened a second time in a session seeded the DESTRUCTIVE key.
 *
 * A scope remembers the last key focused in it. After a confirmed hide that is the Hide key, and a dialog's keys are not
 * registered when its scope is pushed, so the first key to register inherited the memory: Hide was focused for the one or two
 * frames before the dialog's own landing effect moved focus to Keep, and an A inside that window confirmed the hide. The Add
 * app picker has the same shape with its last-chosen candidate. [pushScopeLandingOn] stamps the safe key before the push.
 *
 * Each case drives the real dispatch to open the dialog, simulates the dialog mounting (danger key registers FIRST, the worst
 * order) and the player confirming, then opens it again, and requires that the destructive key is never the focused one.
 */
class ConfirmScopeSafeKeyLandingTest {

    private val bounds = FocusBounds(0f, 0f, 100f, 100f)

    /** Mounts a dialog: registers [first] then [second] in [scope], recording the focus after each registration. */
    private fun DefaultFocusAuthority.mount(scope: FocusScope, first: FocusKey, second: FocusKey): List<FocusKey?> {
        val seen = mutableListOf<FocusKey?>()
        register(first, scope, bounds)
        seen += state.value.focused
        register(second, scope, FocusBounds(200f, 0f, 300f, 100f))
        seen += state.value.focused
        return seen
    }

    private fun DefaultFocusAuthority.unmount(first: FocusKey, second: FocusKey) {
        unregister(first)
        unregister(second)
    }

    // ================================================================================================== native hide

    private fun nativeHideDispatch(authority: DefaultFocusAuthority, viewModel: LibraryViewModel, key: String) =
        dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.SYSTEM_START,
            onExit = {},
            onRescanFolder = {},
            nativeFavoriteGameRows = listOf(
                NativeShelfRow(
                    packageName = key,
                    label = key,
                    launchActivity = null,
                    isGame = true,
                    isFavorite = true,
                ),
            ),
        )

    @Test
    fun `a second native hide never seeds Hide, only Keep`() {
        val packageName = "com.example.game"
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply { openFavorites(FavoriteSegment.ANDROID_GAMES) }
        val authority = DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            register(nativeShelfKey(packageName), GamelistScope, bounds)
        }

        // First hide: the player moves onto Hide and confirms.
        assertTrue(nativeHideDispatch(authority, viewModel, packageName))
        assertEquals(NativeHideConfirmScope, authority.state.value.scope)
        authority.mount(NativeHideConfirmScope, NativeHideConfirmKey, NativeHideCancelKey)
        authority.requestFocus(NativeHideConfirmKey)
        authority.popScope()
        authority.unmount(NativeHideConfirmKey, NativeHideCancelKey)
        assertEquals("back on the list", GamelistScope, authority.state.value.scope)

        // Second hide: Hide registers first, the worst order.
        assertTrue(nativeHideDispatch(authority, viewModel, packageName))
        val seen = authority.mount(NativeHideConfirmScope, NativeHideConfirmKey, NativeHideCancelKey)

        assertFalse("Hide is never the focused key while the dialog mounts: $seen", NativeHideConfirmKey in seen)
        assertEquals("and Keep is what lands", NativeHideCancelKey, authority.state.value.focused)
    }

    // ================================================================================================== emulated hide

    @Test
    fun `a second emulated hide never seeds Hide, only Keep`() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply { openPlatform(PlatformId.PS1) }
        val game = viewModel.games.first { games -> games.any { it.platformId == PlatformId.PS1 } }
            .first { it.platformId == PlatformId.PS1 }
        val gameKey = FocusKey("library/gamelist/${game.gameId.value}")
        val authority = DefaultFocusAuthority().apply {
            pushScope(GamelistScope)
            register(gameKey, GamelistScope, bounds)
        }
        fun startHide() = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.SYSTEM_START,
            onExit = {},
            onRescanFolder = {},
        )

        assertTrue(startHide())
        assertEquals(HideConfirmScope, authority.state.value.scope)
        authority.mount(HideConfirmScope, HideConfirmKey, HideCancelKey)
        authority.requestFocus(HideConfirmKey)
        authority.popScope()
        authority.unmount(HideConfirmKey, HideCancelKey)
        assertEquals(GamelistScope, authority.state.value.scope)

        assertTrue(startHide())
        assertEquals(HideConfirmScope, authority.state.value.scope)
        val seen = authority.mount(HideConfirmScope, HideConfirmKey, HideCancelKey)

        assertFalse("Hide is never the focused key while the dialog mounts: $seen", HideConfirmKey in seen)
        assertEquals("and Keep is what lands", HideCancelKey, authority.state.value.focused)
    }

    // ================================================================================================== Add app picker

    @Test
    fun `a second Add app picker lands on Search, not the last app chosen`() {
        val addKey = FocusKey("library/shelf/add")
        val chosen = nativeAddPickerKey("com.example.chosen")
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply { cycleShelfMode(1) }
        val authority = DefaultFocusAuthority().apply {
            pushScope(LibraryShelfScope)
            register(addKey, LibraryShelfScope, bounds)
        }
        fun openPicker() = dispatchToLibrary(
            authority = authority,
            viewModel = viewModel,
            action = ControllerAction.CONFIRM,
            onExit = {},
            onRescanFolder = {},
        )

        // First visit: an app is picked, which leaves it as the scope's memory.
        assertTrue(openPicker())
        assertEquals(NativeAddPickerScope, authority.state.value.scope)
        authority.mount(NativeAddPickerScope, NativeAddPickerSearchKey, chosen)
        authority.requestFocus(chosen)
        authority.popScope()
        authority.unmount(NativeAddPickerSearchKey, chosen)
        assertEquals(LibraryShelfScope, authority.state.value.scope)

        // Second visit: the candidate registers first.
        assertTrue(openPicker())
        assertEquals(NativeAddPickerScope, authority.state.value.scope)
        val seen = authority.mount(NativeAddPickerScope, chosen, NativeAddPickerSearchKey)

        assertFalse("the last app chosen is never the focused key while the picker mounts: $seen", chosen in seen)
        assertEquals("Search is what lands", NativeAddPickerSearchKey, authority.state.value.focused)
    }

    // ================================================================================================== the control

    @Test
    fun `control - a raw push after a confirmed Hide does seed Hide, so the cases above are not vacuous`() {
        val authority = DefaultFocusAuthority().apply { pushScope(GamelistScope) }
        authority.pushScope(HideConfirmScope)
        authority.mount(HideConfirmScope, HideConfirmKey, HideCancelKey)
        authority.requestFocus(HideConfirmKey)
        authority.popScope()
        authority.unmount(HideConfirmKey, HideCancelKey)

        authority.pushScope(HideConfirmScope)
        val seen = authority.mount(HideConfirmScope, HideConfirmKey, HideCancelKey)

        assertEquals("without the stamp the destructive key is the first thing focused", HideConfirmKey, seen.first())
    }

    @Test
    fun `no confirm or picker scope is pushed raw from the dispatch`() {
        val source = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryDispatch.kt"),
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryDispatch.kt"),
        ).first(File::isFile).readText()
        for (scope in listOf("HideConfirmScope", "NativeHideConfirmScope", "NativeAddPickerScope")) {
            assertFalse(
                "$scope must be pushed with its safe key stamped (pushScopeLandingOn)",
                source.contains("authority.pushScope($scope)"),
            )
        }
        assertEquals("two native hide sites, one emulated, one picker", 4, Regex("""pushScopeLandingOn\(authority, """).findAll(source).count())
    }
}
