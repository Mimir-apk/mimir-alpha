package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirPageMotion
import com.gruning.mimir.rewrite.feature.library.HomeShelfEntry
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.HomeShelfTome
import com.gruning.mimir.rewrite.feature.library.HomeTomePreview
import com.gruning.mimir.rewrite.feature.library.TomeId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Motion batch D's deferred prewarm: which covers a Home surface change warms, in what order, from
 * where. Whether the next moves then land warm is device-owed (Nova, perf build, L1/R1 then D-pad at animator scale 5).
 */
class HomeSurfacePrewarmTest {

    @Test
    fun `nearest first from the focused entry, visible plus two either way`() {
        val rows = (0 until 14).map { row(it) }
        val entries = rows.map { HomeShelfEntry.Game(it.itemId) }
        val uris = homeSurfacePrewarmUris(entries, rows, emptyMap(), entries[6].focusKey)
        assertEquals(listOf(6, 7, 5, 8, 4, 9, 3, 10, 2, 11, 1).map(::cover), uris)
    }

    @Test
    fun `clamped at the ends, and the first entry when nothing is focused`() {
        val rows = (0 until 4).map { row(it) }
        val entries = rows.map { HomeShelfEntry.Game(it.itemId) }
        assertEquals(listOf(0, 1, 2, 3).map(::cover), homeSurfacePrewarmUris(entries, rows, emptyMap(), null))
        assertEquals(emptyList<String>(), homeSurfacePrewarmUris(emptyList(), rows, emptyMap(), null))
    }

    @Test
    fun `a game warms what its tile paints - tile icon, else face, else cover`() {
        val icon = row(0).copy(tileIconUri = "file:///icon0.png", faceUri = "file:///face0.png")
        val face = row(1).copy(faceUri = "file:///face1.png")
        val cover = row(2)
        val entries = listOf(icon, face, cover).map { HomeShelfEntry.Game(it.itemId) }
        assertEquals(
            listOf("file:///icon0.png", "file:///face1.png", cover(2)),
            homeSurfacePrewarmUris(entries, listOf(icon, face, cover), emptyMap(), entries[0].focusKey),
        )
    }

    @Test
    fun `a Tome warms its previews, once each`() {
        val tomeId = TomeId.Platform(PlatformId("snes"))
        val tome = HomeShelfTome(
            tomeId = tomeId,
            title = "SNES",
            previews = listOf(HomeTomePreview("file:///a.png"), HomeTomePreview("file:///b.png")),
            memberCount = 2,
        )
        val game = row(0).copy(artworkUri = "file:///a.png")
        val entries = listOf(HomeShelfEntry.Tome(tomeId), HomeShelfEntry.Game(game.itemId))
        assertEquals(
            listOf("file:///a.png", "file:///b.png"),
            homeSurfacePrewarmUris(entries, listOf(game), mapOf(tomeId to tome), entries[0].focusKey),
        )
    }

    @Test
    fun `it runs after the arrival, from beside the Home coordinator, never inside it`() {
        assertEquals(MimirPageMotion.LeadMs + MimirPageMotion.ArriveMs, HomeSurfacePrewarmAfterMs)
        val coordinator = source("MimirAppCoordinator.kt")
        val release = coordinator.indexOf("HomeTomeLibraryReturnReleaseEffect(homeShelf, navigation)")
        val prewarm = coordinator.indexOf("HomeSurfacePrewarmEffect(homeShelf, core.focusAuthority)")
        assertTrue(release >= 0 && prewarm > release)
        assertFalse(source("HomeShelfCoordinator.kt").contains("HomeSurfacePrewarm"))
    }

    private fun cover(index: Int) = "file:///cover$index.png"

    private fun row(index: Int) = HomeShelfRow(
        itemId = HomeShelfItemId.Emulated(GameId("g$index")),
        rank = index.toLong(),
        addedAtMillis = 0L,
        title = "Game $index",
        artworkUri = cover(index),
    )

    private fun source(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
