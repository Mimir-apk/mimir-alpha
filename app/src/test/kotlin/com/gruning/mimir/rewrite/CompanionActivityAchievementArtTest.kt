package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.metadata.online.RaPointsOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RaUserGamesOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsUserGame
import com.gruning.mimir.rewrite.core.ui.MimirArtAvailability
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val pngBytes = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)

private fun artSnapshot(gameId: String, iconPath: String? = null) = CompanionActivityAchievementSnapshot(
    gameId = gameId,
    account = "art-player",
    unlocked = 3,
    total = 10,
    gameTitle = "Game $gameId",
    rowsKnown = false,
    iconPath = iconPath,
)

/**
 *  an Activity achievement card wears (1) the matched library game's hero (its Customize-media override included), else
 * (2) RA's own icon for the game from the bulk response, else (3) the theme alone. The decision is one pure function; the resolver asks for an icon only
 * when the hero is not on disk; and an icon is a local file staged by a keyless fetch that is NOT a RetroAchievements API call.
 */
class CompanionActivityAchievementArtPolicyTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the fallback order is hero then the RA icon then the theme`() {
        val icon = File("061592.png")
        assertEquals(ActivityAchievementArtKind.HERO, activityAchievementArtKind(heroWired = true, iconFile = icon))
        assertEquals(ActivityAchievementArtKind.HERO, activityAchievementArtKind(heroWired = true, iconFile = null))
        assertEquals(ActivityAchievementArtKind.ICON, activityAchievementArtKind(heroWired = false, iconFile = icon))
        assertEquals(ActivityAchievementArtKind.THEME, activityAchievementArtKind(heroWired = false, iconFile = null))
    }

    @Test
    fun `only a matched library game can have a hero`() {
        assertTrue(activityAchievementIsLibraryGame("a1b2c3"))
        assertFalse(activityAchievementIsLibraryGame("ra:1234"))
    }

    @Test
    fun `a hero uri counts only when its file is there and never when it is remote`() {
        val hero = temp.newFile("hero.img").apply { writeBytes(ByteArray(8)) }
        val empty = temp.newFile("empty.img")
        assertTrue(activityHeroUriOnDisk(hero.toURI().toString()))
        // The cache-busting fragment the custom store adds does not change which file it names.
        assertTrue(activityHeroUriOnDisk(hero.toURI().toString() + "#mv=123"))
        assertFalse(activityHeroUriOnDisk(empty.toURI().toString()))
        assertFalse(activityHeroUriOnDisk(File(temp.root, "gone.img").toURI().toString()))
        assertFalse(activityHeroUriOnDisk("https://example.org/hero.jpg"))
        assertFalse(activityHeroUriOnDisk(""))
        assertFalse(activityHeroUriOnDisk(null))
    }

    @Test
    fun `the icon tile is a whole multiple of its 96 pixel source so it stays crisp`() {
        assertEquals(1, activityIconTileScale(40f))
        assertEquals(1, activityIconTileScale(96f))
        assertEquals(2, activityIconTileScale(250f))
        assertEquals(3, activityIconTileScale(291f))
        assertEquals(96, RA_ICON_SOURCE_PX)
    }

    private fun store(stages: MutableList<String>, ok: Boolean = true, now: () -> Long = { 0L }) = ActivityIconStore(
        dir = File(temp.root, "ra_icons"),
        stage = { path, target ->
            stages += path
            if (ok) {
                target.parentFile?.mkdirs()
                target.writeBytes(pngBytes)
            }
            ok
        },
        clock = now,
    )

    @Test
    fun `a matched game with a hero on disk wears the hero and never asks for an icon`() = runBlocking {
        val hero = temp.newFile("hero.img").apply { writeBytes(ByteArray(8)) }
        val stages = mutableListOf<String>()
        val heroAsked = mutableListOf<String>()
        val resolver = LibraryActivityAchievementArtResolver(
            heroUriFor = { id -> heroAsked += id; hero.toURI().toString() },
            icons = store(stages),
        )
        val sources = resolver.sources(artSnapshot("local-1", iconPath = "/Images/000042.png"))
        assertEquals(hero.toURI().toString(), sources.heroUri)
        assertNull(sources.iconFile)
        assertEquals(listOf("local-1"), heroAsked)
        assertEquals(emptyList<String>(), stages)
    }

    @Test
    fun `a matched game whose hero file is gone falls back to the RA icon`() = runBlocking {
        val stages = mutableListOf<String>()
        val resolver = LibraryActivityAchievementArtResolver(
            heroUriFor = { File(temp.root, "gone.img").toURI().toString() },
            icons = store(stages),
        )
        val sources = resolver.sources(artSnapshot("local-1", iconPath = "/Images/000042.png"))
        assertNull(sources.heroUri)
        assertNotNull(sources.iconFile)
        assertEquals("000042.png", sources.iconFile!!.name)
        assertEquals(listOf("/Images/000042.png"), stages)
    }

    @Test
    fun `an unmatched RA game never asks the library for a hero and uses its icon`() = runBlocking {
        val stages = mutableListOf<String>()
        var heroAsked = 0
        val resolver = LibraryActivityAchievementArtResolver(heroUriFor = { heroAsked += 1; "file:///nope" }, icons = store(stages))
        val sources = resolver.sources(artSnapshot("ra:77", iconPath = "/Images/000077.png"))
        assertEquals(0, heroAsked)
        assertNull(sources.heroUri)
        assertEquals("000077.png", sources.iconFile!!.name)
    }

    @Test
    fun `no hero and no icon path is the theme alone, and a failed fetch is the theme too`() = runBlocking {
        val stages = mutableListOf<String>()
        val resolver = LibraryActivityAchievementArtResolver(heroUriFor = { null }, icons = store(stages))
        val none = resolver.sources(artSnapshot("ra:1", iconPath = null))
        assertNull(none.heroUri)
        assertNull(none.iconFile)
        assertEquals(emptyList<String>(), stages)

        val failing = LibraryActivityAchievementArtResolver(heroUriFor = { null }, icons = store(stages, ok = false))
        val failed = failing.sources(artSnapshot("ra:2", iconPath = "/Images/000002.png"))
        assertNull(failed.iconFile)
        assertEquals(
            ActivityAchievementArtKind.THEME,
            activityAchievementArtKind(heroWired = false, iconFile = failed.iconFile),
        )
    }

    @Test
    fun `an icon is fetched once and kept, and a failure is not retried at once`() = runBlocking {
        var now = 0L
        val stages = mutableListOf<String>()
        val kept = store(stages, now = { now })
        val first = kept.ensure("/Images/000010.png")
        val second = kept.ensure("/Images/000010.png")
        assertEquals(first, second)
        assertEquals(1, stages.size)
        assertEquals(first, kept.staged("/Images/000010.png"))

        val failStages = mutableListOf<String>()
        val failing = ActivityIconStore(File(temp.root, "ra_icons_failing"), { path, _ -> failStages += path; false }, clock = { now })
        assertNull(failing.ensure("/Images/000011.png"))
        assertNull(failing.ensure("/Images/000011.png"))
        assertEquals(1, failStages.size)
        now += 5L * 60L * 1000L + 1L
        assertNull(failing.ensure("/Images/000011.png"))
        assertEquals(2, failStages.size)
    }

    @Test
    fun `the store never writes outside its directory and ignores a path that is not an icon`() = runBlocking {
        val stages = mutableListOf<String>()
        val kept = store(stages)
        assertNull(kept.ensure("/Images/../../etc/passwd.png"))
        assertNull(kept.ensure("https://evil.example/Images/1.png"))
        assertNull(kept.staged("/Images/../x.png"))
        assertEquals(emptyList<String>(), stages)
    }

    @Test
    fun `art for a card is not a RetroAchievements API call`() = runBlocking {
        // One bulk load makes ONE completion read and ONE points read, and carries each game's icon path along; painting every card's art afterwards
        // makes zero further reads: the icon goes through the staging seam (media host), never the API seams.
        CompanionActivityAchievements.resetForTest()
        var gameCalls = 0
        var pointCalls = 0
        val games = listOf(
            RetroAchievementsUserGame(1, "Super Metroid", "Super Nintendo Entertainment System", earned = 12, total = 40, imageIconPath = "/Images/000001.png"),
            RetroAchievementsUserGame(2, "An Obscure Game", "Game Boy", earned = 1, total = 10, imageIconPath = "/Images/000002.png"),
        )
        val loader = ActivityBulkLoader(
            book = RetroAchievementsLaneBook(),
            account = { "art-player" },
            loadGames = { gameCalls += 1; RaUserGamesOutcome.Read(games, games.size) },
            loadPoints = { pointCalls += 1; RaPointsOutcome.Read(100) },
            library = { listOf(LocalGameKey("local-metroid", "snes", "Super Metroid")) },
            platformIdFor = mapOf("Super Nintendo Entertainment System" to "snes", "Game Boy" to "gb")::get,
            publish = { account, snapshots, points -> CompanionActivityAchievements.publishBulk(account, snapshots, points) },
            clock = { 5_000L },
            workContext = EmptyCoroutineContext,
            publishContext = EmptyCoroutineContext,
        )
        assertEquals(ActivityBulkResult.LOADED, loader.load())
        val snapshots = CompanionActivityAchievements.snapshots.value.associateBy { it.gameId }
        assertEquals("/Images/000001.png", snapshots.getValue("local-metroid").iconPath)
        assertEquals("/Images/000002.png", snapshots.getValue("ra:2").iconPath)

        val stages = mutableListOf<String>()
        val resolver = LibraryActivityAchievementArtResolver(heroUriFor = { null }, icons = store(stages))
        snapshots.values.forEach { resolver.sources(it) }
        assertEquals(1, gameCalls)
        assertEquals(1, pointCalls)
        assertEquals(listOf("/Images/000001.png", "/Images/000002.png"), stages.sorted())
    }

    @Test
    fun `a launch-card check keeps the icon the bulk read gave the game`() {
        CompanionActivityAchievements.resetForTest()
        val bulk = artSnapshot("local-1", iconPath = "/Images/000005.png")
        CompanionActivityAchievements.publishBulk("art-player", listOf(bulk), totalPoints = null)
        // A richer snapshot from a launch-card check carries earned rows and no icon; it replaces the entry and inherits the icon.
        val rich = bulk.copy(
            unlocked = 4,
            earned = listOf(CompanionActivityEarnedAchievement(1, "First", "Do it.", 5, hardcore = false)),
            iconPath = null,
            rowsKnown = true,
        )
        CompanionActivityAchievements.publish(
            gameId = "local-1",
            account = "art-player",
            lane = MimirAchievementLaneState.Unresolved(MimirAchievementUnresolvedReason.UNREACHABLE),
            snapshot = rich,
        )
        val held = CompanionActivityAchievements.snapshots.value.single()
        assertEquals("/Images/000005.png", held.iconPath)

        // A bulk re-read keeps the richer rows AND refreshes the icon path from the new read.
        CompanionActivityAchievements.publishBulk("art-player", listOf(bulk.copy(iconPath = "/Images/000006.png")), totalPoints = null)
        assertEquals("/Images/000006.png", CompanionActivityAchievements.snapshots.value.single().iconPath)
    }

    @Test
    fun `the hub paints the art behind the card and hides it from accessibility`() {
        val panel = (File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionActivityAchievementPanel.kt").takeIf { it.isFile }
            ?: File("app/src/main/kotlin/com/gruning/mimir/rewrite/CompanionActivityAchievementPanel.kt")).readText()
        // The card paints the art BEHIND its content, and the hub gives every page the panel.
        assertTrue(panel.indexOf("ActivityAchievementArt(artSnapshot") in 0 until panel.indexOf("MimirEtchedProgressBar("))
        val hubText = (File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionActivityAchievementHub.kt").takeIf { it.isFile }
            ?: File("app/src/main/kotlin/com/gruning/mimir/rewrite/CompanionActivityAchievementHub.kt")).readText()
        assertTrue(hubText.contains("ActivityAchievementPanel("))
        assertFalse("no hex colour literals in the panel", Regex("Color\\(0x[0-9A-Fa-f]+\\)").containsMatchIn(panel))
        assertFalse("never Compose focusable", panel.contains("focusable()") || panel.contains("focusTarget()"))
        assertFalse("nothing is logged", panel.contains("Log."))
        val art = (File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionActivityAchievementArt.kt").takeIf { it.isFile }
            ?: File("app/src/main/kotlin/com/gruning/mimir/rewrite/CompanionActivityAchievementArt.kt")).readText()
        assertTrue(art.contains("clearAndSetSemantics"))
        assertFalse("no hex colour literals in art", Regex("Color\\(0x[0-9A-Fa-f]+\\)").containsMatchIn(art))
        assertFalse("never Compose focusable", art.contains("focusable()") || art.contains("focusTarget()"))
        assertFalse("nothing is logged", art.contains("Log."))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionActivityAchievementArtRenderTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    // The hero's "is the file there" stat (`rememberMimirArtWired`) is the one off-main hop in this surface. It runs inline here, so its
    // verdict lands inside the composition's own idle: no real IO thread to starve under a full run's contention, no wall-clock wait.
    // The dispatcher is process-wide, so it is put back (and the verdicts forgotten) whatever the test did.
    @Before
    fun inlineArtStat() {
        MimirArtAvailability.clearForTest()
        MimirArtAvailability.useDispatcherForTest(Dispatchers.Unconfined)
    }

    @After
    fun ioArtStat() {
        MimirArtAvailability.useDispatcherForTest(null)
        MimirArtAvailability.clearForTest()
    }

    private fun mount(sources: ActivityAchievementArtSources) {
        compose.setContent {
            MimirTheme {
                Box(Modifier.requiredSize(320.dp, 300.dp)) {
                    ActivityAchievementArt(
                        snapshot = artSnapshot("ra:9", iconPath = "/Images/000009.png"),
                        resolver = { sources },
                        workContext = EmptyCoroutineContext,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `with an icon the tile is drawn, and it is hidden from the accessibility tree`() {
        val icon = temp.newFile("000009.png").apply { writeBytes(pngBytes) }
        mount(ActivityAchievementArtSources(heroUri = null, iconFile = icon))
        // The tile exists in the unmerged tree (it is drawn) and is absent from the merged one a screen reader walks.
        compose.onAllNodesWithTag(AchievementHubIconTileTag, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithTag(AchievementHubIconTileTag, useUnmergedTree = false).assertCountEquals(0)
    }

    @Test
    fun `with no hero and no icon nothing is drawn and the theme stands`() {
        mount(ActivityAchievementArtSources(heroUri = null, iconFile = null))
        compose.onAllNodesWithTag(AchievementHubIconTileTag, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `each disposed effect releases only its own hold across null to icon to another icon`() {
        val iconA = temp.newFile("000001.png").apply { writeBytes(pngBytes) }
        val iconB = temp.newFile("000002.png").apply { writeBytes(pngBytes) }
        val released = mutableListOf<String>()
        val snapshot = androidx.compose.runtime.mutableStateOf(artSnapshot("ra:1", iconPath = null))
        val resolver = ActivityAchievementArtResolver { s ->
            // No icon path is the first, empty state; each path then holds its own file with its own release.
            when (s.iconPath) {
                "/Images/000001.png" -> ActivityAchievementArtSources(null, iconA) { released += "A" }
                "/Images/000002.png" -> ActivityAchievementArtSources(null, iconB) { released += "B" }
                else -> ActivityAchievementArtSources(null, null) { released += "none" }
            }
        }
        compose.setContent {
            MimirTheme {
                Box(Modifier.requiredSize(320.dp, 300.dp)) {
                    ActivityAchievementArt(snapshot = snapshot.value, resolver = resolver, workContext = EmptyCoroutineContext)
                }
            }
        }
        compose.waitForIdle()
        assertEquals("the empty first state is still shown", emptyList<String>(), released)

        compose.runOnIdle { snapshot.value = artSnapshot("ra:1", iconPath = "/Images/000001.png") }
        compose.waitForIdle()
        // null -> A: the old (empty) effect is disposed, and it releases its OWN hold, never the new icon's.
        assertEquals(listOf("none"), released)
        compose.onAllNodesWithTag(AchievementHubIconTileTag, useUnmergedTree = true).assertCountEquals(1)

        compose.runOnIdle { snapshot.value = artSnapshot("ra:1", iconPath = "/Images/000002.png") }
        compose.waitForIdle()
        // A -> B: only A is released; B, the live icon, is still held.
        assertEquals(listOf("none", "A"), released)
        assertFalse("B stays held while it is shown", "B" in released)
        compose.onAllNodesWithTag(AchievementHubIconTileTag, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `a hero that is not wired falls through to the icon`() {
        val icon = temp.newFile("000009.png").apply { writeBytes(pngBytes) }
        // The hero names a file that is not there, so it is not wired and the icon is the art.
        mount(ActivityAchievementArtSources(heroUri = File(temp.root, "gone.img").toURI().toString(), iconFile = icon))
        // The hero's file check is pending (so wired) when the surface first composes and says "missing" a moment later; it runs inline
        // (see inlineArtStat), so the icon has replaced the pending hero by the time the composition is idle again. No wall-clock wait.
        compose.waitForIdle()
        compose.onAllNodesWithTag(AchievementHubIconTileTag, useUnmergedTree = true).assertCountEquals(1)
    }
}

/**
 * The icon cache is bounded (at most 512 files and 16 MiB; small bounds here), evicts the least recently used first, never evicts or clears an icon that is
 * being read, and is emptied on sign-out and opt-out.
 */
class ActivityIconCacheBoundTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var now = 1_000L
    private val staged = mutableListOf<String>()

    private fun path(n: Int) = "/Images/%06d.png".format(n)

    private fun dir() = File(temp.root, "ra_icons")

    private fun names() = dir().listFiles().orEmpty().map { it.name }.sorted()

    private fun store(maxFiles: Int = 512, maxBytes: Long = ACTIVITY_ICON_CACHE_MAX_BYTES, size: Int = 12) = ActivityIconStore(
        dir = dir(),
        stage = { p, target ->
            staged += p
            target.parentFile?.mkdirs()
            target.writeBytes(ByteArray(size) { pngBytes[it % pngBytes.size] })
            true
        },
        clock = { now++ },
        maxFiles = maxFiles,
        maxBytes = maxBytes,
    )

    @Test
    fun `the budget is 192 MiB of bytes, with the file count only a safety ceiling`() {
        assertEquals(192L * 1024L * 1024L, ACTIVITY_ICON_CACHE_MAX_BYTES)
        // Two full 500-badge sets (earned and locked art) and a library's worth of game icons fit many times over.
        assertTrue(ACTIVITY_ICON_CACHE_MAX_FILES >= 10_000)
    }

    @Test
    fun `writing past the file bound evicts the oldest first`() = runBlocking {
        val cache = store(maxFiles = 3)
        (1..5).forEach { cache.ensure(path(it)) }
        assertEquals(listOf("000003.png", "000004.png", "000005.png"), names())
    }

    @Test
    fun `writing past the byte bound evicts the oldest first`() = runBlocking {
        val cache = store(maxBytes = 30L, size = 10)
        (1..5).forEach { cache.ensure(path(it)) }
        // Three 10-byte files fit under 30 bytes; the two oldest went.
        assertEquals(listOf("000003.png", "000004.png", "000005.png"), names())
    }

    @Test
    fun `a read keeps an icon young so it outlives older ones`() = runBlocking {
        val cache = store(maxFiles = 3)
        (1..3).forEach { cache.ensure(path(it)) }
        // Icon 1 is the oldest; reading it makes it the newest.
        cache.ensure(path(1))
        cache.ensure(path(4))
        assertEquals(listOf("000001.png", "000003.png", "000004.png"), names())
        // And a read is no fetch.
        assertEquals(4, staged.size)
    }

    @Test
    fun `an icon being read is not deleted mid-read however full the cache gets`() = runBlocking {
        val cache = store(maxFiles = 2)
        val held = cache.acquire(path(1))!!
        (2..6).forEach { cache.ensure(path(it)) }
        assertTrue("the held icon is still on disk", held.file.isFile)
        assertTrue("000001.png" in names())
        // Released, it is an ordinary old file and the next write evicts it.
        held.release()
        cache.ensure(path(7))
        assertFalse("000001.png" in names())
        assertEquals(2, names().size)
    }

    @Test
    fun `a sign-out empties the folder, and a held icon goes when it is released`() = runBlocking {
        val cache = store()
        (1..4).forEach { cache.ensure(path(it)) }
        val held = cache.acquire(path(2))!!
        cache.clear()
        assertEquals(listOf("000002.png"), names())
        assertTrue(held.file.isFile)
        held.release()
        held.release() // idempotent
        assertEquals(emptyList<String>(), names())
        // The cache works again afterwards.
        assertNotNull(cache.ensure(path(9)))
        assertEquals(listOf("000009.png"), names())
    }

    @Test
    fun `clear also forgets a remembered failure`() = runBlocking {
        var ok = false
        val cache = ActivityIconStore(dir(), { _, target -> target.parentFile?.mkdirs(); if (ok) target.writeBytes(pngBytes); ok }, clock = { now })
        assertNull(cache.ensure(path(1)))
        ok = true
        assertNull("still inside the retry window", cache.ensure(path(1)))
        cache.clear()
        assertNotNull(cache.ensure(path(1)))
    }

    @Test
    fun `releasing the art's sources frees the hold`() = runBlocking {
        val cache = store(maxFiles = 1)
        val resolver = LibraryActivityAchievementArtResolver(heroUriFor = { null }, icons = cache)
        val sources = resolver.sources(artSnapshot("ra:1", iconPath = path(1)))
        cache.ensure(path(2))
        assertTrue("held, so kept", File(dir(), "000001.png").isFile)
        sources.release()
        cache.ensure(path(3))
        assertFalse(File(dir(), "000001.png").isFile)
    }
}
