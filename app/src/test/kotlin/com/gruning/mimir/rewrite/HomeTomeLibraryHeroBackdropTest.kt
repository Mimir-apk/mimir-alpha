package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.HomeTomePreview
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Tome collection background uses the selected game's HERO art, excluding its Home cover and icon. The slot is [HomeTomePreview.heroArtUri] -- the hero picked in Customize media, then the downloaded `-hero` image,
 * the two rungs `HomeShelfScreen` hands the shelf's own hero backdrop (`chosenHeroUri`, then `selectedRow.heroArtUri`) --
 * and a game without either shows the theme backdrop (the null [VideoBackdrop] paints as its floor).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeTomeLibraryHeroBackdropTest {

    @get:Rule
    val compose = createComposeRule()

    private val cover = "file:///media/Zelda.png"
    private val icon = "file:///files/tile-icons/zelda.png"
    private val downloadedHero = "file:///roms/gba/media_art/zelda-hero.png#mv=1"
    private val chosenHero = "file:///files/enrich/hero/zelda.png"

    private fun row(
        tileIconUri: String? = null,
        chosen: String? = null,
        downloaded: String? = null,
    ) = HomeShelfRow(
        itemId = HomeShelfItemId.Emulated(GameId("gba/zelda")),
        rank = 0L,
        addedAtMillis = 0L,
        title = "The Legend of Zelda",
        platformId = PlatformId.GBA,
        artworkUri = cover,
        tileIconUri = tileIconUri,
        chosenHeroUri = chosen,
        heroArtUri = downloaded,
    )

    @Test
    fun `the preview carries the chosen hero, then the downloaded one, in both projections`() {
        val both = row(chosen = chosenHero, downloaded = downloadedHero)
        assertEquals(chosenHero, both.toHomeTomePreviewOrNull(null)?.heroArtUri)
        assertEquals(chosenHero, both.toHomeTomePreviewOrNull()?.heroArtUri)

        val downloadedOnly = row(downloaded = downloadedHero)
        assertEquals(downloadedHero, downloadedOnly.toHomeTomePreviewOrNull(null)?.heroArtUri)
        assertEquals(downloadedHero, downloadedOnly.toHomeTomePreviewOrNull()?.heroArtUri)
    }

    @Test
    fun `a game with no hero has none, whatever its cover or tile icon`() {
        val bare = row(tileIconUri = icon)
        assertNull(bare.toHomeTomePreviewOrNull(null)?.heroArtUri)
        assertNull(bare.toHomeTomePreviewOrNull()?.heroArtUri)
        // The cover/icon stay the plate's art and the shelf stage's own rung: only the hero slot moved.
        assertEquals(icon, bare.toHomeTomePreviewOrNull(null)?.artUri)
        assertEquals(icon, bare.toHomeTomePreviewOrNull(null)?.backdropArtUri)
        assertNull(row(chosen = "  ", downloaded = "").toHomeTomePreviewOrNull(null)?.heroArtUri)
    }

    @Test
    fun `a picked cartridge face on the plate does not change the hero`() {
        val picked = row(downloaded = downloadedHero).toHomeTomePreviewOrNull("file:///files/tome-cartridge-art/zelda.png")
        assertTrue(picked?.isPhysicalMedia == true)
        assertEquals(downloadedHero, picked?.heroArtUri)
    }

    @Test
    fun `the library paints the hero and reports the same art upward`() {
        val painted = mutableListOf<String?>()
        val reported = mutableListOf<String?>()
        var preview by mutableStateOf<HomePreviewHolder>(HomePreviewHolder(null))
        compose.setContent {
            MimirTheme {
                HomeTomeLibraryBackdrop(
                    presentedPreview = preview.value,
                    onBackdropArt = { reported += it },
                    paint = { art ->
                        painted += art
                        Box(Modifier.testTag("painted-art"))
                    },
                )
            }
        }
        compose.waitForIdle()
        assertNull("nothing presented yet: the theme backdrop", painted.last())

        fun present(value: HomeTomePreview?) {
            Snapshot.withMutableSnapshot { preview = HomePreviewHolder(value) }
            compose.waitForIdle()
        }

        present(row(chosen = chosenHero, downloaded = downloadedHero).toHomeTomePreviewOrNull(null))
        assertEquals(chosenHero, painted.last())
        assertEquals("what it paints is what it reports, for the return", chosenHero, reported.last())

        // A cover-only game and a physical-media plate fall to the theme backdrop, never to their cover or icon.
        present(row(tileIconUri = icon).toHomeTomePreviewOrNull(null))
        assertNull(painted.last())
        assertNull(reported.last())
        present(row().toHomeTomePreviewOrNull("file:///files/tome-cartridge-art/zelda.png"))
        assertNull(painted.last())
        assertNull(reported.last())
        assertTrue(painted.filterNotNull().all { it == chosenHero })
    }

    @Test
    fun `the background is visual only and hidden from accessibility`() {
        compose.setContent {
            MimirTheme {
                HomeTomeLibraryBackdrop(
                    presentedPreview = row(downloaded = downloadedHero).toHomeTomePreviewOrNull(null),
                    onBackdropArt = {},
                    paint = { Box(Modifier.testTag("painted-art")) },
                )
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag("painted-art", useUnmergedTree = false).assertCountEquals(0)
    }

    @Test
    fun `the overlay takes its background from the one helper and nothing reads the cover there`() {
        val source = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/HomeTomeLibraryOverlay.kt"),
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/HomeTomeLibraryOverlay.kt"),
        ).first(File::isFile).readText()
        assertTrue("the overlay mounts the backdrop helper", source.contains("HomeTomeLibraryBackdrop(\n            presentedPreview = presentedPreview,"))
        assertFalse("no cover/icon rung in the overlay", source.contains("preview.backdropArtUri"))
        assertFalse("no plate-art rung in the overlay", source.contains("preview.artUri.takeUnless"))
        assertFalse("no direct VideoBackdrop with the plate's art", source.contains("presentedPreview?.let { preview ->"))
    }

    /** A State holder for a nullable preview, so the test can null it without a sentinel. */
    private class HomePreviewHolder(val value: HomeTomePreview?)
}
