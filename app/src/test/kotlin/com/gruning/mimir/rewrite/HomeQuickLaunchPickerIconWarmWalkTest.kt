package com.gruning.mimir.rewrite

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The dock and picker shared one icon cache, but the dock only renders its <=3
 * PINNED slots, so only those were ever warmed. Every UNPINNED app in the picker's grid still paid a cold
 * `BitmapFactory.decodeFile` in its own cell, each landing on its own frame — the letter-glyph-then-icon
 * wave the regression report described for `quick launch > all apps`.
 *
 * [warmHomeQuickLaunchPickerIcons] closes it from the dock, which is guaranteed to be open first
 * (`openHomeQuickLaunchGeneral` requires `homeQuickLaunchOpen`).
 *
 * WHAT THIS HARNESS CANNOT SHOW, stated up front and inherited from [HomeQuickLaunchPickerIconWarmStartTest]:
 * Robolectric's `ShadowBitmapFactory.decodeFile` returns a synthetic non-null `Bitmap` for ANY path, so
 * "the glyph never appears on screen" is not writable here. **Whether the wave actually disappears is
 * device-only.** These tests pin the MECHANISM through the cache's observable state.
 *
 * DIFFERENTIAL: this file does not compile against pre-change source — [warmHomeQuickLaunchPickerIcons]
 * and `HomeQuickLaunchPickerIconCache.isAtBudget()` do not exist there — so none of it can pass vacuously.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HomeQuickLaunchPickerIconWarmWalkTest {

    @Before
    fun clearCacheBefore() = HomeQuickLaunchPickerIconCache.evictAll()

    @After
    fun clearCacheAfter() = HomeQuickLaunchPickerIconCache.evictAll()

    @Test
    fun `the walk warms every catalog icon, not only the pinned ones`() = runBlocking {
        val entries = (1..12).map(::entry)
        entries.forEach { assertNull(HomeQuickLaunchPickerIconCache.get(it.iconPath!!)) }

        warmHomeQuickLaunchPickerIcons(entries)

        entries.forEach {
            assertNotNull(
                "every entry the picker will render must be warm before it mounts",
                HomeQuickLaunchPickerIconCache.get(it.iconPath!!),
            )
        }
    }

    @Test
    fun `an entry with no icon path is skipped rather than failing the walk`() = runBlocking {
        val withIcon = entry(1)
        val withoutIcon = CompanionAppEntry("pkg.noicon", "No Icon", iconPath = null)

        // The null entry sits FIRST: a walk that threw or returned on it would leave the rest cold.
        warmHomeQuickLaunchPickerIcons(listOf(withoutIcon, withIcon))

        assertNotNull(HomeQuickLaunchPickerIconCache.get(withIcon.iconPath!!))
    }

    @Test
    fun `an already-warm entry is not re-decoded`() = runBlocking {
        val target = entry(1)
        val warmed = newIcon()
        HomeQuickLaunchPickerIconCache.put(target.iconPath!!, warmed)

        warmHomeQuickLaunchPickerIcons(listOf(target))

        // A re-decode would have `put` a different instance over this one — the same identity differential
        // HomeQuickLaunchPickerIconWarmStartTest uses for the per-cell path.
        assertSame(warmed, HomeQuickLaunchPickerIconCache.get(target.iconPath!!))
    }

    @Test
    fun `the walk stops at the byte budget instead of evicting its own first page`() = runBlocking {
        // The property under test: the walk runs in the picker's own alphabetical order, so an unbounded
        // walk on a large library would evict the entries the picker OPENS on to make room for ones it may
        // never scroll to — re-creating the very strobe this change removes, only for large libraries.
        //
        // HARNESS NOTE, measured not assumed: Robolectric's `ShadowBitmapFactory.decodeFile` returns a
        // non-null bitmap whose `byteCount` is ZERO, so no number of decoded icons can ever reach the
        // budget under this runner. An earlier revision of this test tried to fill it by decoding and
        // failed its own vacuity guard (`probe > 0`). The budget is therefore reached with real
        // `Bitmap.createBitmap` instances of known cost, which makes the stop condition deterministic
        // rather than dependent on the shadow's dimensions.
        fillCacheToBudget()
        val entries = (1..8).map(::entry)

        warmHomeQuickLaunchPickerIcons(entries)

        entries.forEach {
            assertNull(
                "the walk must STOP at the budget rather than churn entries through a full cache",
                HomeQuickLaunchPickerIconCache.get(it.iconPath!!),
            )
        }
    }

    @Test
    fun `a walk below the budget is not stopped`() = runBlocking {
        // Differential guard for the stop condition: `isAtBudget()` must gate on the real committed bytes,
        // not be true by construction. Without this, the test above would pass against a walk that never
        // warms anything at all.
        val entries = (1..8).map(::entry)

        warmHomeQuickLaunchPickerIcons(entries)

        entries.forEach { assertNotNull(HomeQuickLaunchPickerIconCache.get(it.iconPath!!)) }
    }

    /** Commits the cache's whole byte budget using bitmaps whose cost is known, not shadow-dependent. */
    private fun fillCacheToBudget() {
        val side = 1024
        val bytesEach = side * side * 4 // ARGB_8888
        repeat(BudgetBytes / bytesEach) { index ->
            HomeQuickLaunchPickerIconCache.put(
                "/mimir-test/ballast-$index-e1-ffff.png",
                Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).asImageBitmap(),
            )
        }
        assertTrue("the ballast must actually commit the budget", HomeQuickLaunchPickerIconCache.isAtBudget())
    }

    private fun entry(index: Int) = CompanionAppEntry(
        packageName = "pkg.app$index",
        label = "App $index",
        // Shaped like a real content-addressed icon path (`<stem>-e<epoch>-<digest>.png`).
        iconPath = "/mimir-test/pkg-app$index-e1-abcd$index.png",
    )

    private fun newIcon(): ImageBitmap =
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImageBitmap()

    private companion object {
        /** Mirrors `HomeQuickLaunchPickerIconCache`'s own budget — this test may not invent a new one. */
        const val BudgetBytes = 16 * 1024 * 1024
    }
}
