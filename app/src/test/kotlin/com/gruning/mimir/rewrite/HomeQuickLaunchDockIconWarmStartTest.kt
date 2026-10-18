package com.gruning.mimir.rewrite

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2 item 1 — pins the dock's slot row into the SAME [HomeQuickLaunchPickerIconCache] the "quick launch >
 * all apps" picker reads, mirroring [HomeQuickLaunchPickerIconWarmStartTest]'s mechanism-level approach.
 *
 * Two differences from that file, both because this composable is a different surface with a different
 * observable seam:
 *
 * 1. The dock's `Image` carries `contentDescription = null` (`HomeQuickLaunchOverlay.kt`'s
 *    `HomeQuickLaunchSlotRow`) — there is no content-description/Role.Image node to match here the way the
 *    picker test does. The dock's own fallback text (`"Q${index + 1}"`) is the observable proxy instead: it
 *    is the glyph shown only while `iconState.value == null`, so its presence/absence tracks the same
 *    seed-vs-decode distinction the picker test pins via the icon node.
 * 2. This surface's cold decode is masked by the dock's own 180ms `fadeIn`
 *    (`MimirAppHost.kt`'s `HomeQuickLaunchOverlayHost`) — that is precisely WHY the strobe never showed up
 *    here and this item was left as a deliberate follow-up rather than folded into the earlier update. Whether the mask
 *    is enough on real hardware is device-only; this file only pins that the cache is actually shared.
 *
 * Same harness limit as the picker file, restated: `ShadowBitmapFactory.decodeFile` returns a synthetic
 * non-null `Bitmap` for any path under Robolectric, so glyph-vs-icon RENDERING is unprovable here. These
 * tests pin the mechanism (cache reuse, write-back ordering) through observable state, not pixels.
 *
 * `waitForIdle()` does NOT await `withContext(Dispatchers.IO)` (documented and hit as a real full-suite
 * flake on this lane already — see [HomeQuickLaunchPickerIconWarmStartTest]'s kdoc). The cold-slot
 * assertion below waits for the cache write-back itself, bounded, rather than assuming idleness implies the
 * decode landed.
 *
 * DIFFERENTIAL REPLAY, and an honest limit of the warm-slot test specifically. Replaying both tests against
 * the pre-T2 `HomeQuickLaunchOverlay.kt` (before this item wired the row into the shared cache): the
 * COLD-slot test fails deterministically (`HomeQuickLaunchPickerIconCache.get(IconPath)` stays `null`
 * forever — old code never calls `.put`), which is real teeth. The WARM-slot test's two assertions,
 * however, ALSO pass against that old code — not because the fix is unproven, but because old code never
 * reads OR writes this cache at all, so "no Q1 frame" can win a real (if fast) decode race by luck and
 * "cache still holds `warmed`" is trivially true when nothing ever touches the cache. Kept anyway: it is a
 * real forward regression guard for THIS mechanism (a future removal of the seed's short-circuit — the
 * `if (iconState.value != null) return@LaunchedEffect` guard — decodes unconditionally again and its `put`
 * WOULD overwrite `warmed`, deterministically failing `assertSame`), it just cannot retroactively prove
 * superiority over a fundamentally cache-blind prior implementation the way the picker file's equivalent
 * test can (there, old AND new code both call `.put` on every decode, which is what makes instance identity
 * a clean differentiator).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HomeQuickLaunchDockIconWarmStartTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun clearCacheBefore() = HomeQuickLaunchPickerIconCache.evictAll()

    @After
    fun clearCacheAfter() = HomeQuickLaunchPickerIconCache.evictAll()

    @Test
    fun `a cold pinned slot decodes and populates the shared cache, and its Q-fallback clears`() {
        assertNull(HomeQuickLaunchPickerIconCache.get(IconPath))

        setDockContent()
        // "Q1" is the fallback painted only while iconState.value == null (HomeQuickLaunchOverlay.kt's
        // HomeQuickLaunchSlotRow). Its disappearance proves the async decode's `iconState.value = decoded`
        // assignment ran, which -- in production ordering -- happens strictly AFTER the cache `put`.
        compose.waitUntil(IconWaitMs) {
            compose.onAllNodesWithText(QFallback).fetchSemanticsNodes().isEmpty()
        }

        // The miss really did decode and write back -- this is what gives the warm-slot test its teeth.
        assertNotNull(HomeQuickLaunchPickerIconCache.get(IconPath))
    }

    @Test
    fun `a warm pinned slot reuses the cached icon instead of redecoding, with no Q-fallback frame`() {
        val warmed = newIcon()
        HomeQuickLaunchPickerIconCache.put(IconPath, warmed)

        setDockContent()

        // The seed is synchronous inside `remember` (`mutableStateOf(iconPath?.let(cache::get))`), so the
        // FIRST frame already has the icon -- the "Q1" glyph must never appear, not even transiently.
        compose.onNodeWithText(QFallback).assertDoesNotExist()
        // A re-decode would have `put` a fresh instance over this one (see the cold-slot test, which
        // proves that write-back path runs on a miss).
        assertSame(warmed, HomeQuickLaunchPickerIconCache.get(IconPath))
    }

    private fun setDockContent() {
        val authority = DefaultFocusAuthority()
        val slots = listOf(
            HomeQuickLaunchSlotUi(index = 0, packageName = "pkg.zephyr", label = Label, iconPath = IconPath),
            HomeQuickLaunchSlotUi(index = 1, packageName = null, label = null),
            HomeQuickLaunchSlotUi(index = 2, packageName = null, label = null),
            HomeQuickLaunchSlotUi(index = 3, packageName = null, label = "All apps", general = true),
        )
        compose.setContent {
            MimirTheme {
                HomeQuickLaunchOverlay(
                    authority = authority,
                    slots = slots,
                    onLaunch = {},
                    onOpenGeneral = {},
                    onEdit = {},
                    onDismiss = {},
                )
            }
        }
    }

    private fun newIcon(): ImageBitmap =
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImageBitmap()

    private companion object {
        /** Shaped like a real content-addressed icon path (`<stem>-e<epoch>-<digest>.png`). */
        const val IconPath = "/mimir-test/pkg-zephyr-dock-e1-deadbeef.png"
        const val Label = "Zephyr"
        const val QFallback = "Q1"

        /** Generous: this bounds a real background decode, and a slow CI box must not turn it into a flake. */
        const val IconWaitMs = 10_000L
    }
}
