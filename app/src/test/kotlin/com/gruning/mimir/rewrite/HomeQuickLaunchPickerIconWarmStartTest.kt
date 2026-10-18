package com.gruning.mimir.rewrite

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the stabilised identity behind the `quick launch > all apps` strobe fix: a picker cell whose icon is
 * already decoded must reuse it rather than starting from `null` and re-running an IO decode. That per-cell
 * `null` restart, multiplied across the visible grid and re-fired by every `LazyVerticalGrid` recycle, is
 * the glyph→icon wave the regression report described.
 *
 * WHAT THIS HARNESS CANNOT SHOW, stated up front. The obvious test — "cold cell paints the letter glyph,
 * warm cell paints the icon" — is NOT WRITABLE here. Robolectric's `ShadowBitmapFactory.decodeFile` returns
 * a synthetic non-null `Bitmap` for ANY path, including one with no file behind it, so the in-composable
 * decode always "succeeds" and the glyph branch is unreachable under Robolectric no matter what the
 * production code does. An earlier revision asserted exactly that and its vacuity guard failed (0 glyph
 * nodes where the whole point was to find 1). **Whether the wave actually disappears on screen is
 * device-only.** So these tests pin the MECHANISM, through observable state rather than source text.
 *
 * TWO DEFECTS IN AN EARLIER REVISION OF THIS FILE, both fixed here, both worth keeping written down.
 *
 * 1. It asserted `onAllNodesWithContentDescription(Label).assertCountEquals(2)`. That counted TWO unrelated
 *    composables that merely share the label — the icon `Image` and the tile's `MimirIntentText`, whose
 *    `Authored` intent resolves `contentDescription` to the same string
 *    (`MimirTextIntent.kt:65`). A count over "how many things happen to carry this label" pins nothing the
 *    test's name claims and breaks the moment either composable changes. It now matches the ICON node
 *    specifically — content description AND `Role.Image` — and expects exactly one.
 *
 * 2. It was ORDER-DEPENDENT, and passed filtered while failing in the full suite. The cold case's icon node
 *    exists only once `withContext(Dispatchers.IO) { BitmapFactory.decodeFile(...) }` has completed, and
 *    `waitForIdle()` does NOT await that: it waits for Compose recomposition/layout idleness, not for an
 *    arbitrary background dispatcher. On an idle machine the decode always wins that race; under full-suite
 *    CPU contention it can lose, and the node count drops by one. The warm case never flaked because its
 *    node comes from the synchronous cache seed — which is exactly the asymmetry that was observed. The fix
 *    is [awaitIconRendered]: wait for the icon node itself, bounded, instead of assuming idleness implies
 *    the decode landed.
 *
 * WHY [awaitIconRendered] KEEPS THE DIFFERENTIAL HONEST rather than hiding a regression behind a wait.
 * In production the `put` into the cache happens BEFORE `iconState.value = decoded`, so a rendered icon
 * node PROVES the write-back already ran. Remove the seed (`mutableStateOf(iconPath?.let(...::get))` back to
 * `mutableStateOf(null)`) and [aWarmCellReusesTheCachedIconInsteadOfRedecoding] waits for the decode's own
 * node, by which point the fresh shadow bitmap has definitely overwritten the warmed instance — so
 * `assertSame` fails deterministically instead of racing. Verified by replaying these tests against that
 * reverted source: that one test fails, the other three pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HomeQuickLaunchPickerIconWarmStartTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun clearCacheBefore() = HomeQuickLaunchPickerIconCache.evictAll()

    @After
    fun clearCacheAfter() = HomeQuickLaunchPickerIconCache.evictAll()

    @Test
    fun `a cold cell decodes and populates the cache`() {
        assertNull(HomeQuickLaunchPickerIconCache.get(IconPath))

        setPickerContent()
        awaitIconRendered()

        // The miss really did decode and write back — this is what gives the warm-cell test its teeth.
        assertNotNull(HomeQuickLaunchPickerIconCache.get(IconPath))
    }

    @Test
    fun `a warm cell reuses the cached icon instead of redecoding`() {
        val warmed = newIcon()
        HomeQuickLaunchPickerIconCache.put(IconPath, warmed)

        setPickerContent()
        awaitIconRendered()

        // A re-decode would have `put` a different instance over this one before rendering its own node
        // (see the cold-cell test, which proves that write-back path runs on a miss).
        assertSame(warmed, HomeQuickLaunchPickerIconCache.get(IconPath))
    }

    @Test
    fun `the cache keys on the icon path so two entries never share one icon`() {
        val first = newIcon()
        val second = newIcon()
        assertNotSame(first, second)
        HomeQuickLaunchPickerIconCache.put("/icons/one-e1-aaaa.png", first)
        HomeQuickLaunchPickerIconCache.put("/icons/two-e1-bbbb.png", second)

        assertSame(first, HomeQuickLaunchPickerIconCache.get("/icons/one-e1-aaaa.png"))
        assertSame(second, HomeQuickLaunchPickerIconCache.get("/icons/two-e1-bbbb.png"))
        // `writeVersionedNativeIcon` stems the path with the package-update epoch AND a digest of the PNG
        // bytes, so an updated app arrives under a NEW path and must MISS rather than be served the old
        // bytes. This is the no-stale-icon property the per-cell `null` reset used to provide.
        assertNull(HomeQuickLaunchPickerIconCache.get("/icons/one-e2-cccc.png"))
    }

    @Test
    fun `evictAll drops warmed entries`() {
        HomeQuickLaunchPickerIconCache.put(IconPath, newIcon())
        assertNotNull(HomeQuickLaunchPickerIconCache.get(IconPath))

        HomeQuickLaunchPickerIconCache.evictAll()

        assertNull(HomeQuickLaunchPickerIconCache.get(IconPath))
    }

    /**
     * The tile's icon node ONLY — the label carries the same content description but is not an `Image`, so
     * matching on the role keeps this immune to how many other things happen to share the label.
     */
    private fun iconNodes() = compose.onAllNodes(
        hasContentDescription(Label) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image),
        useUnmergedTree = true,
    )

    /** Waits for the icon itself rather than assuming Compose idleness implies the IO decode landed. */
    private fun awaitIconRendered() {
        compose.waitUntil(IconWaitMs) { iconNodes().fetchSemanticsNodes().isNotEmpty() }
        iconNodes().assertCountEquals(1)
    }

    private fun setPickerContent() {
        val authority = DefaultFocusAuthority()
        compose.setContent {
            MimirTheme {
                HomeQuickLaunchPickerOverlay(
                    authority = authority,
                    mode = HomeQuickLaunchPickerMode.General,
                    items = listOf(HomeQuickLaunchPickerItem.App(Entry)),
                    presentationEpoch = 1L,
                    initialGeneralPackageName = null,
                    onGeneralFocusChanged = {},
                    onPresentationReadyChanged = {},
                    onChoose = {},
                    onDismiss = {},
                )
            }
        }
    }

    private fun newIcon(): ImageBitmap =
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).asImageBitmap()

    private companion object {
        /** Shaped like a real content-addressed icon path (`<stem>-e<epoch>-<digest>.png`). */
        const val IconPath = "/mimir-test/pkg-zephyr-e1-deadbeef.png"
        const val Label = "Zephyr"

        /** Generous: this bounds a real background decode, and a slow CI box must not turn it into a flake. */
        const val IconWaitMs = 10_000L

        val Entry = CompanionAppEntry(
            packageName = "pkg.zephyr",
            label = Label,
            iconPath = IconPath,
        )
    }
}
