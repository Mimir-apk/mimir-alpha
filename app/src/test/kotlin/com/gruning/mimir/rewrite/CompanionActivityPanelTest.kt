package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.metadata.online.RaAwardKind
import com.gruning.mimir.rewrite.core.metadata.online.RaProgressOutcome
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsIcons
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.ui.LocalMimirMotionLive
import com.gruning.mimir.rewrite.core.ui.MimirAchievementCopy
import com.gruning.mimir.rewrite.core.ui.MimirEtchedProgressArrivalMs
import com.gruning.mimir.rewrite.core.ui.MimirEtchedProgressBar
import com.gruning.mimir.rewrite.core.ui.MimirEtchedProgressShownSemantics
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.mimirEtchedProgressDurationMs
import java.io.File
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val panelPng = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)

private fun ach(
    id: Int,
    title: String,
    points: Int,
    earnedAt: String? = null,
    hardcoreAt: String? = null,
    badge: String? = "0004$id",
) = RetroAchievementsAchievement(
    id = id,
    title = title,
    description = "Description $id",
    points = points,
    unlocked = earnedAt != null || hardcoreAt != null,
    unlockedHardcore = hardcoreAt != null,
    badgeName = badge,
    earnedAt = earnedAt ?: hardcoreAt,
    earnedHardcoreAt = hardcoreAt,
)

private fun detailProgress(
    gameId: Int,
    title: String = "Game $gameId",
    achievements: List<RetroAchievementsAchievement> = listOf(
        ach(1, "Green Hill Hero", 5, hardcoreAt = "2023-05-01 12:34:56"),
        ach(2, "Ring Collector", 10, earnedAt = "2023-05-02 10:00:00"),
        ach(3, "Locked One", 25),
    ),
) = RetroAchievementsProgress(gameId, title, achievements)

private fun panelSnapshot(n: Int, title: String = "Game $n") = CompanionActivityAchievementSnapshot(
    gameId = "g$n",
    account = "me",
    unlocked = 2,
    total = 3,
    gameTitle = title,
    rowsKnown = false,
    raGameId = n,
)

/**
 * The Activity card's pure rules and its per-game detail loader: badge scale and order, earned vs locked badge paths, the labels a screen reader says, the
 * detail line, the hero fallback order, and the lazy, throttled, epoch-fenced detail read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompanionActivityPanelRulesTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a badge is drawn at a whole-number scale that makes it a 48 dp target at least`() {
        assertEquals(1, activityBadgeScale(40f))
        assertEquals(1, activityBadgeScale(64f))
        assertEquals(2, activityBadgeScale(65f))
        assertEquals(2, activityBadgeScale(48f * 2.306f)) // the Bottom: 369 dpi
        assertEquals(64, RA_BADGE_SOURCE_PX)
    }

    @Test
    fun `an earned badge and a locked one point at different media paths and a bad badge name at none`() {
        assertEquals("/Badge/00042.png", activityBadgePath(ach(1, "A", 5, earnedAt = "2023-01-01 00:00:00", badge = "00042")))
        assertEquals("/Badge/00042_lock.png", activityBadgePath(ach(1, "A", 5, badge = "00042")))
        assertEquals(
            "https://media.retroachievements.org/Badge/00042_lock.png",
            RetroAchievementsIcons.url(activityBadgePath(ach(1, "A", 5, badge = "00042"))),
        )
        assertNull(activityBadgePath(ach(1, "A", 5, badge = "../evil")))
        assertNull(activityBadgePath(ach(1, "A", 5, badge = null)))
    }

    @Test
    fun `badges read as progress made, the newest unlock first and the locked ones after in set order`() {
        val ordered = activityBadgeOrder(
            listOf(
                ach(4, "L2", 5),
                ach(1, "Old", 5, earnedAt = "2022-01-01 00:00:00"),
                ach(3, "L1", 5),
                ach(2, "New", 5, earnedAt = "2023-01-01 00:00:00"),
            ),
        )
        assertEquals(listOf(2, 1, 3, 4), ordered.map { it.id })
    }

    @Test
    fun `the labels a screen reader says`() {
        assertEquals("Sonic, 12 of 40 achievements, 235 points", activityPanelLabel("Sonic", 12, 40, 235))
        assertEquals("Sonic, 12 of 40 achievements", activityPanelLabel("Sonic", 12, 40, null))
        assertEquals("the award is said last", "Sonic, 40 of 40 achievements, 235 points, Mastered", activityPanelLabel("Sonic", 40, 40, 235, award = "Mastered"))
        assertEquals("Sonic, 12 of 40 achievements, Beaten", activityPanelLabel("Sonic", 12, 40, null, award = "Beaten"))
        assertEquals("Green Hill Hero, earned", activityBadgeLabel("Green Hill Hero", earned = true))
        assertEquals("Locked One, locked", activityBadgeLabel("Locked One", earned = false))
    }

    @Test
    fun `a badge's detail line gives its title, description, points and when it was earned`() {
        val hardcore = activityBadgeDetail(ach(1, "Green Hill Hero", 5, hardcoreAt = "2023-05-01 12:34:56"), ZoneOffset.UTC)
        assertEquals("Green Hill Hero", hardcore.title)
        assertEquals("Description 1", hardcore.description)
        assertEquals("5 points · Earned in hardcore 2023-05-01 12:34:56", hardcore.meta)
        assertEquals("10 points · Earned 2023-05-02 10:00:00", activityBadgeDetail(ach(2, "R", 10, earnedAt = "2023-05-02 10:00:00"), ZoneOffset.UTC).meta)
        assertEquals("25 points · Locked", activityBadgeDetail(ach(3, "L", 25)).meta)
        assertEquals("15 of 40 points", activityPanelPointsText(detailProgress(1, achievements = listOf(ach(1, "a", 15, earnedAt = "x"), ach(2, "b", 25)))))
        assertNull(activityPanelPointsText(null))
    }

    // ── the hero fallback order ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the art order is the library hero, then RA's title or in-game image, then RA's icon, then the theme`() {
        val file = File("x.png")
        assertEquals(ActivityAchievementArtKind.HERO, activityAchievementArtKind(heroWired = true, iconFile = file, artFile = file))
        assertEquals(ActivityAchievementArtKind.ART, activityAchievementArtKind(heroWired = false, iconFile = file, artFile = file))
        assertEquals(ActivityAchievementArtKind.ICON, activityAchievementArtKind(heroWired = false, iconFile = file, artFile = null))
        assertEquals(ActivityAchievementArtKind.THEME, activityAchievementArtKind(heroWired = false, iconFile = null, artFile = null))
    }

    private fun artStore(stages: MutableList<String>) = ActivityIconStore(
        dir = File(temp.root, "ra_icons"),
        stage = { path, target -> stages += path; target.parentFile?.mkdirs(); target.writeBytes(panelPng); true },
    )

    @Test
    fun `the resolver takes the hero, else RA's art, else its icon, and never fetches what it does not need`() = runBlocking {
        val hero = temp.newFile("hero.img").apply { writeBytes(ByteArray(8)) }
        val stages = mutableListOf<String>()
        val withHero = LibraryActivityAchievementArtResolver({ hero.toURI().toString() }, artStore(stages))
            .sources(panelSnapshot(1).copy(artPath = "/Images/000010.png", iconPath = "/Images/000012.png"))
        assertEquals(hero.toURI().toString(), withHero.heroUri)
        assertEquals(emptyList<String>(), stages)

        val noHero = LibraryActivityAchievementArtResolver({ null }, artStore(stages))
        val art = noHero.sources(panelSnapshot(1).copy(artPath = "/Images/000010.png", iconPath = "/Images/000012.png"))
        assertNull(art.heroUri)
        assertEquals("000010.png", art.artFile!!.name)
        assertNull("the icon is not fetched when the art is there", art.iconFile)
        assertEquals(listOf("/Images/000010.png"), stages)

        val iconOnly = noHero.sources(panelSnapshot(2).copy(iconPath = "/Images/000012.png"))
        assertNull(iconOnly.artFile)
        assertEquals("000012.png", iconOnly.iconFile!!.name)

        val nothing = noHero.sources(panelSnapshot(3))
        assertNull(nothing.artFile)
        assertNull(nothing.iconFile)
    }

    // ── the progress bar's arrival ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the fill's arrival is bounded and is a snap under reduced motion or while parked`() {
        assertEquals(MimirEtchedProgressArrivalMs, mimirEtchedProgressDurationMs(reducedMotion = false, live = true))
        assertTrue("bounded", MimirEtchedProgressArrivalMs in 1..500)
        assertEquals(0, mimirEtchedProgressDurationMs(reducedMotion = true, live = true))
        assertEquals(0, mimirEtchedProgressDurationMs(reducedMotion = false, live = false))
        assertEquals(0, mimirEtchedProgressDurationMs(reducedMotion = true, live = false))
    }

    // ── the per-game detail: lazy, throttled, fenced ─────────────────────────────────────────────────────────────────────────────

    private class Fixture(var now: Long = 1_000_000L) {
        val fetched = mutableListOf<Int>()
        val sink = MutableStateFlow<Map<Int, ActivityCardDetail>>(emptyMap())
        val book = RetroAchievementsLaneBook()
        var account: String? = "me"
        var answer: (Int) -> RaProgressOutcome = { RaProgressOutcome.Read(detailProgress(it)) }
        var gate: CompletableDeferred<Unit>? = null
        /** True while a Retry-After is in force. */
        var held = false
        /** One per read, taken in order: completing one is that read's deadline arriving (a read with none never reaches it). */
        val stalls = ArrayDeque<CompletableDeferred<Unit>>()
        val loader = ActivityCardDetailLoader(
            scope = CoroutineScope(Dispatchers.Unconfined),
            book = book,
            account = { account },
            fetch = { id -> fetched += id; gate?.await(); answer(id) },
            sink = sink,
            clock = { now },
            workContext = EmptyCoroutineContext,
            publishContext = EmptyCoroutineContext,
            held = { held },
            stallDelay = { stalls.removeFirstOrNull()?.await() ?: awaitCancellation() },
        )
    }

    @Test
    fun `a game nobody asked for is never fetched, and one asked for is fetched once`() {
        val f = Fixture()
        assertEquals(emptyList<Int>(), f.fetched)
        f.loader.request(7)
        f.loader.request(7)
        f.loader.request(7)
        assertEquals(listOf(7), f.fetched)
        assertTrue(f.sink.value[7] is ActivityCardDetail.Loaded)
        assertNull(f.sink.value[8])
    }

    @Test
    fun `a game is asked about at most once in five minutes, and a failure counts`() {
        val f = Fixture()
        f.answer = { RaProgressOutcome.Unavailable }
        f.loader.request(7)
        assertEquals(ActivityCardDetail.Unavailable, f.sink.value[7])
        f.now += ACTIVITY_CARD_DETAIL_REFRESH_MS - 1
        f.loader.request(7)
        assertEquals(listOf(7), f.fetched)
        f.now += 2
        f.answer = { RaProgressOutcome.Read(detailProgress(it)) }
        f.loader.request(7)
        assertEquals(listOf(7, 7), f.fetched)
        assertTrue(f.sink.value[7] is ActivityCardDetail.Loaded)
        // A later refresh that fails does not erase what the card already shows.
        f.now += ACTIVITY_CARD_DETAIL_REFRESH_MS + 1
        f.answer = { RaProgressOutcome.Unavailable }
        f.loader.request(7)
        assertEquals(3, f.fetched.size)
        assertTrue(f.sink.value[7] is ActivityCardDetail.Loaded)
    }

    @Test
    fun `a failed read is not retried on its own, and one tap reads once more outside the window`() {
        val f = Fixture()
        f.answer = { RaProgressOutcome.Unavailable }
        f.loader.request(7)
        assertEquals(ActivityCardDetail.Unavailable, f.sink.value[7])
        repeat(5) { f.loader.request(7) }
        f.now += ACTIVITY_CARD_DETAIL_REFRESH_MS - 1
        repeat(5) { f.loader.request(7) }
        assertEquals("opening the card again and again reads nothing", listOf(7), f.fetched)
        f.answer = { RaProgressOutcome.Read(detailProgress(it)) }
        f.loader.retry(7)
        assertEquals("the tap reads once, inside the window", listOf(7, 7), f.fetched)
        assertTrue(f.sink.value[7] is ActivityCardDetail.Loaded)
        // The tap stamped the game, so the window now runs from it.
        f.now += ACTIVITY_CARD_DETAIL_REFRESH_MS - 1
        repeat(3) { f.loader.request(7) }
        assertEquals(listOf(7, 7), f.fetched)
    }

    @Test
    fun `a tap while a read is under way starts nothing, and the card says it is loading meanwhile`() {
        val f = Fixture()
        f.answer = { RaProgressOutcome.Unavailable }
        f.loader.request(7)
        f.gate = CompletableDeferred()
        f.answer = { RaProgressOutcome.Read(detailProgress(it)) }
        f.loader.retry(7)
        assertEquals("the tap is answered at once", ActivityCardDetail.Loading, f.sink.value[7])
        f.loader.retry(7)
        f.loader.retry(7)
        f.loader.request(7)
        assertEquals("four asks, one read", listOf(7, 7), f.fetched)
        f.gate!!.complete(Unit)
        assertTrue(f.sink.value[7] is ActivityCardDetail.Loaded)
    }

    @Test
    fun `a tap with no account reads nothing, and a tap on a card that shows its badges only refreshes them`() {
        val f = Fixture()
        f.account = null
        f.loader.retry(7)
        assertEquals(emptyList<Int>(), f.fetched)
        f.account = "me"
        f.loader.request(7)
        val before = f.sink.value[7]
        assertTrue(before is ActivityCardDetail.Loaded)
        f.gate = CompletableDeferred()
        f.loader.retry(7)
        assertEquals("a card with badges is not blanked to Loading by a tap", before, f.sink.value[7])
        f.gate!!.complete(Unit)
        assertEquals(listOf(7, 7), f.fetched)
    }

    @Test
    fun `a refused key is its own state, and a read that throws is a failure and never an endless loading`() {
        val f = Fixture()
        f.answer = { RaProgressOutcome.CredentialsRejected }
        f.loader.request(7)
        assertEquals(ActivityCardDetail.Rejected, f.sink.value[7])
        val g = Fixture()
        g.answer = { error("the network fell over") }
        g.loader.request(8)
        assertEquals(ActivityCardDetail.Unavailable, g.sink.value[8])
        g.answer = { RaProgressOutcome.Read(detailProgress(it)) }
        g.loader.retry(8)
        assertEquals("the failed read released its claim, so the tap reads", listOf(8, 8), g.fetched)
        assertTrue(g.sink.value[8] is ActivityCardDetail.Loaded)
    }

    @Test
    fun `a read still out past its deadline is a stalled card, never a failure, and a slow success still lands`() {
        val f = Fixture()
        val stall = CompletableDeferred<Unit>()
        val slow = CompletableDeferred<Unit>()
        f.stalls += stall
        f.gate = slow
        f.loader.request(7)
        assertEquals(ActivityCardDetail.Loading, f.sink.value[7])
        f.now += ACTIVITY_CARD_READ_DEADLINE_MS - 1
        assertEquals("inside the deadline it is plain loading", ActivityCardDetail.Loading, f.sink.value[7])
        f.now += 2
        stall.complete(Unit)
        assertEquals("past the deadline the card says it is still loading", ActivityCardDetail.Stalled, f.sink.value[7])
        repeat(3) { f.loader.request(7) }
        assertEquals("asking again never turns a read that may still land into a failure", ActivityCardDetail.Stalled, f.sink.value[7])
        assertEquals(listOf(7), f.fetched)
        slow.complete(Unit)
        assertTrue("the slow read was not cut short", f.sink.value[7] is ActivityCardDetail.Loaded)
        assertEquals(listOf(7), f.fetched)
    }

    @Test
    fun `a tap on a stalled card asks again once, and the card is loading meanwhile`() {
        val f = Fixture()
        val stall = CompletableDeferred<Unit>()
        f.stalls += stall
        f.gate = CompletableDeferred()
        f.loader.request(7)
        f.now += ACTIVITY_CARD_READ_DEADLINE_MS + 1
        stall.complete(Unit)
        assertEquals(ActivityCardDetail.Stalled, f.sink.value[7])
        val second = CompletableDeferred<Unit>()
        f.gate = second
        f.loader.retry(7)
        assertEquals(ActivityCardDetail.Loading, f.sink.value[7])
        f.loader.retry(7)
        f.loader.retry(7)
        assertEquals("three taps, one new read", listOf(7, 7), f.fetched)
        second.complete(Unit)
        assertTrue(f.sink.value[7] is ActivityCardDetail.Loaded)
    }

    @Test
    fun `a first read that is still queued and a tap on its card make one request`() {
        val f = Fixture()
        f.gate = CompletableDeferred()
        f.loader.request(7)
        repeat(3) { f.loader.retry(7) }
        f.now += ACTIVITY_CARD_READ_DEADLINE_MS - 1
        f.loader.retry(7)
        f.loader.request(7)
        assertEquals("the read is out, queued or not: nothing else is asked", listOf(7), f.fetched)
        f.gate!!.complete(Unit)
        assertTrue(f.sink.value[7] is ActivityCardDetail.Loaded)
        assertEquals(listOf(7), f.fetched)
    }

    @Test
    fun `while a Retry-After is in force a tap says so, neither sends nor flashes loading, and stamps nothing`() {
        val f = Fixture()
        val seen = mutableListOf<ActivityCardDetail?>()
        val watcher = CoroutineScope(Dispatchers.Unconfined).launch { f.sink.collect { seen += it[7] } }
        f.held = true
        f.answer = { RaProgressOutcome.Unavailable }
        f.loader.request(7)
        repeat(3) { f.loader.retry(7) }
        assertEquals(ActivityCardDetail.Throttled, f.sink.value[7])
        assertEquals("nothing was sent", emptyList<Int>(), f.fetched)
        assertFalse("and the card never flashed loading: $seen", ActivityCardDetail.Loading in seen)
        // The wait passes: a tap reads at once (the held asks stamped nothing).
        f.held = false
        f.answer = { RaProgressOutcome.Read(detailProgress(it)) }
        f.loader.retry(7)
        assertEquals(listOf(7), f.fetched)
        assertTrue(f.sink.value[7] is ActivityCardDetail.Loaded)
        // A card that already shows its badges keeps them through a hold.
        f.held = true
        f.loader.retry(7)
        assertTrue(f.sink.value[7] is ActivityCardDetail.Loaded)
        assertEquals(listOf(7), f.fetched)
        watcher.cancel()
    }

    @Test
    fun `a game whose answer was evicted inside its window is not left bare, and a tap reads it`() {
        val f = Fixture()
        (1..9).forEach { f.loader.request(it) }
        assertNull("game 1's answer went when the ninth came in", f.sink.value[1])
        f.loader.request(1)
        assertEquals("nothing saved to show: it reads as a failure the player can tap", ActivityCardDetail.Unavailable, f.sink.value[1])
        assertEquals("and nothing was asked", 9, f.fetched.size)
        f.loader.retry(1)
        assertEquals(10, f.fetched.size)
        assertTrue(f.sink.value[1] is ActivityCardDetail.Loaded)
    }

    @Test
    fun `opening the same cards again and again reads nothing while their detail is held`() {
        val f = Fixture()
        (1..3).forEach { f.loader.request(it) }
        repeat(20) { (1..3).forEach { id -> f.loader.request(id) } }
        f.now += ACTIVITY_CARD_DETAIL_REFRESH_MS - 1
        repeat(20) { (1..3).forEach { id -> f.loader.request(id) } }
        assertEquals("six hub openings and then sixty more: three reads in all", listOf(1, 2, 3), f.fetched)
    }

    @Test
    fun `no production source says the bare progress-count sentence`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        assertNotNull("the repository root", dir)
        val offenders = dir!!.listFiles().orEmpty()
            .map { File(it, "src/main") }
            .filter { it.isDirectory }
            .flatMap { main -> main.walkTopDown().filter { it.isFile && it.extension == "kt" && "Progress count only" in it.readText() }.toList() }
            .map { it.name }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `nothing is asked without an account, and an account change forgets every detail`() {
        val f = Fixture()
        f.account = null
        f.loader.request(7)
        assertEquals(emptyList<Int>(), f.fetched)
        f.account = "me"
        f.loader.request(7)
        assertEquals(1, f.sink.value.size)
        f.loader.reset()
        assertEquals(emptyMap<Int, ActivityCardDetail>(), f.sink.value)
        // The throttle is the old account's too: the new one may ask at once.
        f.loader.request(7)
        assertEquals(listOf(7, 7), f.fetched)
    }

    @Test
    fun `an answer that began under one account and finished after a switch publishes nothing`() {
        val f = Fixture()
        f.gate = CompletableDeferred()
        f.loader.request(7)
        assertEquals(listOf(7), f.fetched)
        assertEquals(ActivityCardDetail.Loading, f.sink.value[7])
        f.book.accountChanged()
        f.account = "someone-else"
        f.gate!!.complete(Unit)
        assertFalse("the old account's answer did not land: ${f.sink.value}", f.sink.value[7] is ActivityCardDetail.Loaded)
    }

    @Test
    fun `the lane reads no detail at all until a card asks`() {
        val f = Fixture()
        // Constructing the loader and an account being present asks nothing: only request() does.
        assertEquals(emptyList<Int>(), f.fetched)
        assertEquals(emptyMap<Int, ActivityCardDetail>(), f.sink.value)
        assertNotNull(f.loader)
    }
}

/**
 * The Activity card at the Bottom's 537.7 x 468.3 dp, at font scales 1.0 and 1.3: every card the same size, the detail asked for only by a card that is
 * composed, a tapped badge shows its details, the earned and locked badge art is asked for by its own path, and the accessibility labels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionActivityPanelRenderTest {

    @get:Rule
    val utc = UtcDefaultTimeZoneRule()

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369

    private class RecordingBadges(private val dir: File) : ActivityBadgeSource {
        val asked = mutableListOf<String>()
        val released = mutableListOf<String>()
        /** Runs right after a lease is created, before it is handed back: a test uses it to make the card leave at the worst moment. */
        @Volatile var onAcquired: () -> Unit = {}
        override suspend fun acquire(path: String): ActivityIconLease? {
            synchronized(asked) { asked += path }
            val file = File(dir, RetroAchievementsIcons.fileName(path)!!).apply { parentFile?.mkdirs(); writeBytes(panelPng) }
            val lease = ActivityIconLease(file) { synchronized(released) { released += path } }
            onAcquired()
            return lease
        }
    }

    private fun mount(fontScale: Float, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, fontScale)) {
                MimirTheme { Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp)) { content() } }
            }
        }
        compose.waitForIdle()
    }

    private fun texts(): List<String> {
        val out = ArrayList<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.children.forEach(::walk)
        }
        compose.onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out
    }

    private fun descriptions(): List<String> {
        val out = ArrayList<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { out += it }
            node.children.forEach(::walk)
        }
        compose.onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return out
    }

    private fun loadedDetails(vararg games: Int, progress: (Int) -> RetroAchievementsProgress = { detailProgress(it) }) =
        MutableStateFlow<Map<Int, ActivityCardDetail>>(games.associateWith { ActivityCardDetail.Loaded(progress(it), 0L) })

    private fun hub(
        snapshots: List<CompanionActivityAchievementSnapshot>,
        details: MutableStateFlow<Map<Int, ActivityCardDetail>>,
        badges: ActivityBadgeSource = NoActivityBadgeArt,
        request: (Int) -> Unit = {},
        retry: (Int) -> Unit = {},
    ): @androidx.compose.runtime.Composable () -> Unit = {
        CompanionActivityAchievementHub(
            snapshots = snapshots,
            unknownState = com.gruning.mimir.rewrite.core.ui.MimirAchievementCardState.Unknown(MimirAchievementCopy.NoAnswerYet),
            navigationEnabled = true,
            onBack = {},
            badgeSource = badges,
            details = details,
            requestDetail = request,
            retryDetail = retry,
        )
    }

    private fun turnTo(index: Int) {
        compose.onNodeWithTag(AchievementHubListTag, useUnmergedTree = true).performScrollToIndex(index)
        compose.waitForIdle()
    }

    private fun panelSizes(): List<Pair<Float, Float>> =
        compose.onAllNodesWithTag(AchievementHubCardTag, useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .filter { it.width > 0f && it.height > 0f }
            .map { (it.width / density369) to (it.height / density369) }

    private fun checkUniform(scale: Float) {
        val snapshots = listOf(
            panelSnapshot(1, title = "Sonic the Hedgehog 2 Special Limited Collectors Edition of the Chaos Emerald Hunters Revisited and Remastered"),
            panelSnapshot(2, title = "Tetris"),
            panelSnapshot(3, title = "A Middling Title"),
        )
        // Three different cards: a long title with its detail, a short one whose detail is unavailable, and one whose detail has not landed.
        val details = MutableStateFlow<Map<Int, ActivityCardDetail>>(
            mapOf(1 to ActivityCardDetail.Loaded(detailProgress(1), 0L), 2 to ActivityCardDetail.Unavailable),
        )
        mount(scale, hub(snapshots, details))
        val seen = ArrayList<Pair<Float, Float>>()
        seen += panelSizes()
        // Three games: the filter header stands above the pager, so the card starts below it (and ends 24 dp above the bottom, as before).
        val cardTop = compose.onAllNodesWithTag(AchievementHubCardTag, useUnmergedTree = true).fetchSemanticsNodes()
            .first { it.boundsInRoot.width > 0f }.boundsInRoot.top / density369
        for (i in 1..2) {
            turnTo(i)
            seen += panelSizes()
        }
        println("PANEL-GEO fontScale=$scale sizes=$seen host=${hostWidthDp}x${hostHeightDp}")
        assertTrue("a card was measured", seen.isNotEmpty())
        val w = seen.map { it.first }
        val h = seen.map { it.second }
        assertTrue("every card is the same width: $w", w.max() - w.min() < 0.5f)
        assertTrue("every card is the same height: $h", h.max() - h.min() < 0.5f)
        assertEquals("a card fills the hub's margins", hostWidthDp - 40f, w.first(), 1.0f)
        assertTrue("the filter header is above the card: top=$cardTop", cardTop > 80f)
        assertEquals("a card fills the page the hub gives it, from below the header to 24 dp above the bottom", hostHeightDp - cardTop - 24f, h.first(), 1.0f)
    }

    @Test
    fun `every card is the same size at font scale 1_0`() = checkUniform(1.0f)

    @Test
    fun `every card is the same size at font scale 1_3`() = checkUniform(1.3f)

    @Test
    fun `a bulk count newer than the card's detail is the count the header shows, and an older one is not`() {
        // The detail (2 of 3 earned, 15 of 40 points) was read at t=1000; the bulk read at t=2000 counted 3 of 3.
        val bulkNewer = panelSnapshot(1).copy(unlocked = 3, checkedAtEpochMillis = 2_000L)
        val detail = MutableStateFlow<Map<Int, ActivityCardDetail>>(mapOf(1 to ActivityCardDetail.Loaded(detailProgress(1), 1_000L)))
        mount(1.0f, hub(listOf(bulkNewer), detail))
        assertTrue("the newer bulk count: ${texts()}", "3 of 3 achievements · 15 of 40 points" in texts())
        assertFalse("not the older detail count: ${texts()}", "2 of 3 achievements · 15 of 40 points" in texts())
    }

    @Test
    fun `a card whose detail is newer than the bulk count keeps the detail's count`() {
        val bulkOlder = panelSnapshot(1).copy(unlocked = 1, checkedAtEpochMillis = 500L)
        val detail = MutableStateFlow<Map<Int, ActivityCardDetail>>(mapOf(1 to ActivityCardDetail.Loaded(detailProgress(1), 1_000L)))
        mount(1.0f, hub(listOf(bulkOlder), detail))
        assertTrue("the detail's own count: ${texts()}", "2 of 3 achievements · 15 of 40 points" in texts())
    }

    @Test
    fun `only a card that is composed asks for its game's detail, once`() {
        val fetched = mutableListOf<Int>()
        val sink = MutableStateFlow<Map<Int, ActivityCardDetail>>(emptyMap())
        val loader = ActivityCardDetailLoader(
            scope = CoroutineScope(Dispatchers.Unconfined),
            book = RetroAchievementsLaneBook(),
            account = { "me" },
            fetch = { id -> fetched += id; RaProgressOutcome.Read(detailProgress(id)) },
            sink = sink,
            workContext = EmptyCoroutineContext,
            publishContext = EmptyCoroutineContext,
        )
        mount(1.0f, hub((1..3).map { panelSnapshot(it) }, sink, request = loader::request))
        assertTrue("the card on screen asked", 1 in fetched)
        assertFalse("a card nobody has turned to asked nothing: $fetched", 3 in fetched)
        turnTo(1)
        turnTo(2)
        assertEquals("each composed card asked once: $fetched", listOf(1, 2, 3), fetched.sorted())
        // Back to the first card: it is composed again but its game is inside the five-minute window, so nothing more is read.
        turnTo(0)
        assertEquals(listOf(1, 2, 3), fetched.sorted())
    }

    @Test
    fun `the strip asks for each badge's own earned or locked art and a tap shows the badge's details`() {
        val badges = RecordingBadges(temp.newFolder("badges"))
        mount(1.0f, hub(listOf(panelSnapshot(1)), loadedDetails(1), badges))
        compose.waitUntil(5_000L) { synchronized(badges.asked) { badges.asked.size >= 3 } }
        val asked = synchronized(badges.asked) { badges.asked.toList() }
        // Earned: colour art. Locked: the _lock art. Never the other way round.
        assertTrue("earned badge 41 in colour: $asked", "/Badge/00041.png" in asked)
        assertTrue("earned badge 42 in colour: $asked", "/Badge/00042.png" in asked)
        assertTrue("locked badge 43 as _lock: $asked", "/Badge/00043_lock.png" in asked)
        assertFalse(asked.any { it == "/Badge/00043.png" || it == "/Badge/00041_lock.png" })

        assertTrue("a hint before any tap: ${texts()}", "Tap a badge for its details." in texts())
        compose.onNodeWithTag(achievementPanelBadgeTag(2), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        var all = texts()
        assertTrue("its title: $all", "Ring Collector" in all)
        assertTrue("its description: $all", "Description 2" in all)
        assertTrue("its points and earned date: $all", "10 points · Earned 2023-05-02 10:00:00" in all)
        compose.onNodeWithTag(achievementPanelBadgeTag(3), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        all = texts()
        assertTrue("a locked badge says so: $all", "25 points · Locked" in all)
        assertFalse("the earlier badge's line is gone", "10 points · Earned 2023-05-02 10:00:00" in all)
        compose.onAllNodesWithTag(AchievementPanelHardcoreTag, useUnmergedTree = true).assertCountEquals(1)
        assertTrue("the hardcore count: ${texts()}", "HC 1" in texts())
    }

    private fun awardMarks(snapshot: CompanionActivityAchievementSnapshot, detail: ActivityCardDetail = ActivityCardDetail.Loaded(detailProgress(1), 1_000L)): List<String> {
        mount(1.0f, hub(listOf(snapshot), MutableStateFlow<Map<Int, ActivityCardDetail>>(mapOf(1 to detail))))
        compose.waitForIdle()
        val tagged = compose.onAllNodesWithTag(AchievementPanelAwardTag, useUnmergedTree = true).fetchSemanticsNodes()
        return tagged.mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }
    }

    @Test
    fun `a card whose every achievement is earned in hardcore says Mastered beside its count`() {
        // The newer bulk read counted 3 of 3, all 3 in hardcore (RA's own figure), and the older detail rows knew only one hardcore unlock: the larger count stands.
        val mastered = panelSnapshot(1).copy(unlocked = 3, checkedAtEpochMillis = 2_000L, earnedHardcore = 3, awardKind = RaAwardKind.MASTERED)
        assertEquals(listOf("Mastered"), awardMarks(mastered))
        assertTrue("the card announces it: ${descriptions()}", descriptions().any { it.endsWith("3 of 3 achievements, 15 points, Mastered") })
        assertTrue("a hardcore mark still shows how many: ${texts()}", "HC 3" in texts())
    }

    @Test
    fun `a card whose every achievement is earned but not all in hardcore says Completed`() {
        val completed = panelSnapshot(1).copy(unlocked = 3, checkedAtEpochMillis = 2_000L, earnedHardcore = 1, awardKind = RaAwardKind.COMPLETED)
        assertEquals(listOf("Completed"), awardMarks(completed))
        assertTrue("one achievement earned in both modes is one: ${texts()}", "3 of 3 achievements · 15 of 40 points" in texts())
    }

    @Test
    fun `a card says Beaten when RA reports a beaten award, in hardcore or not, and nothing otherwise`() {
        val beatenHard = panelSnapshot(1).copy(awardKind = RaAwardKind.BEATEN_HARDCORE)
        assertEquals(listOf("Beaten"), awardMarks(beatenHard))
    }

    @Test
    fun `a card with no award says no award, and the word alone never makes a finished game`() {
        assertEquals(emptyList<String>(), awardMarks(panelSnapshot(1)))
    }

    @Test
    fun `a softcore beaten award says Beaten`() {
        val beatenSoft = panelSnapshot(1).copy(awardKind = RaAwardKind.BEATEN_SOFTCORE)
        assertEquals(listOf("Beaten (softcore)"), awardMarks(beatenSoft))
    }

    @Test
    fun `a card announces its game, count and points and each badge announces earned or locked`() {
        mount(1.0f, hub(listOf(panelSnapshot(1)), loadedDetails(1)))
        compose.waitForIdle()
        val said = descriptions()
        assertTrue("the card: $said", "Game 1, 2 of 3 achievements, 15 points" in said)
        assertTrue("an earned badge: $said", "Green Hill Hero, earned" in said)
        assertTrue("another earned badge: $said", "Ring Collector, earned" in said)
        assertTrue("a locked badge: $said", "Locked One, locked" in said)
    }

    private fun layoutOf(text: String): androidx.compose.ui.text.TextLayoutResult {
        val node = compose.onAllNodes(androidx.compose.ui.test.hasText(text), useUnmergedTree = true).fetchSemanticsNodes().first()
        val results = ArrayList<androidx.compose.ui.text.TextLayoutResult>()
        node.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.first()
    }

    private fun bareSentence(said: List<String>) = said.filter { "Progress count only" in it }

    @Test
    fun `while its read is due or under way a card shows its count and a quiet loading hint`() {
        val details = MutableStateFlow<Map<Int, ActivityCardDetail>>(emptyMap())
        mount(1.0f, hub(listOf(panelSnapshot(1)), details))
        assertTrue("due: ${texts()}", MimirAchievementCopy.BadgesLoading in texts())
        assertTrue("the bulk count: ${texts()}", texts().any { it.startsWith("2 of 3 achievements") })
        compose.runOnIdle { details.value = mapOf(1 to ActivityCardDetail.Loading) }
        compose.waitForIdle()
        assertTrue("under way: ${texts()}", MimirAchievementCopy.BadgesLoading in texts())
        assertTrue("the bulk count: ${texts()}", texts().any { it.startsWith("2 of 3 achievements") })
        compose.onAllNodesWithTag(AchievementCardLoadingTag, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithTag(AchievementCardRetryTag, useUnmergedTree = true).assertCountEquals(0)
    }

    private fun checkFailedStrip(
        scale: Float,
        state: ActivityCardDetail = ActivityCardDetail.Unavailable,
        copy: String = MimirAchievementCopy.BadgesFailed,
    ) {
        val details = MutableStateFlow<Map<Int, ActivityCardDetail>>(mapOf(1 to state))
        val retried = mutableListOf<Int>()
        mount(scale, hub(listOf(panelSnapshot(1)), details, retry = { retried += it }))
        assertTrue("it says why and how to go on: ${texts()}", copy in texts())
        assertTrue("the bulk count: ${texts()}", texts().any { it.startsWith("2 of 3 achievements") })
        assertTrue("a screen reader hears it too: ${descriptions()}", copy in descriptions())
        compose.onAllNodesWithTag(AchievementCardLoadingTag, useUnmergedTree = true).assertCountEquals(0)
        val target = compose.onNodeWithTag(AchievementCardRetryTag, useUnmergedTree = true)
        assertTrue("the whole strip is a target of at least 48 dp", target.fetchSemanticsNode().size.height >= (48f * density369 - 1f).toInt())
        val layout = layoutOf(copy)
        assertFalse("the sentence is cut at $scale", layout.multiParagraph.didExceedMaxLines || layout.didOverflowHeight)
        target.performClick()
        compose.waitForIdle()
        assertEquals("one tap, one retry, for that card's RetroAchievements game", listOf(1), retried)
    }

    @Test
    fun `a card whose read failed says why in plain words and a tap on it tries again once at font scale 1_0`() = checkFailedStrip(1.0f)

    @Test
    fun `a card whose read failed says why in plain words and a tap on it tries again once at font scale 1_3`() = checkFailedStrip(1.3f)

    private fun checkRejectedStrip(scale: Float) {
        val details = MutableStateFlow<Map<Int, ActivityCardDetail>>(mapOf(1 to ActivityCardDetail.Rejected))
        mount(scale, hub(listOf(panelSnapshot(1)), details))
        assertTrue("the key's own words: ${texts()}", MimirAchievementCopy.CredentialsRejected in texts())
        compose.onAllNodesWithTag(AchievementCardRetryTag, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag(AchievementCardLoadingTag, useUnmergedTree = true).assertCountEquals(0)
        val layout = layoutOf(MimirAchievementCopy.CredentialsRejected)
        assertFalse("the sentence is cut at $scale (${layout.lineCount} lines)", layout.multiParagraph.didExceedMaxLines || layout.didOverflowHeight)
    }

    @Test
    fun `a card whose key is refused says so in the key's own words, whole, and offers no retry at font scale 1_0`() = checkRejectedStrip(1.0f)

    @Test
    fun `a card whose key is refused says so in the key's own words, whole, and offers no retry at font scale 1_3`() = checkRejectedStrip(1.3f)

    @Test
    fun `a stalled card says it is still loading and a tap asks again at font scale 1_0`() =
        checkFailedStrip(1.0f, ActivityCardDetail.Stalled, MimirAchievementCopy.BadgesStalled)

    @Test
    fun `a stalled card says it is still loading and a tap asks again at font scale 1_3`() =
        checkFailedStrip(1.3f, ActivityCardDetail.Stalled, MimirAchievementCopy.BadgesStalled)

    @Test
    fun `a card told to wait says RetroAchievements asked for it and a tap tries again at font scale 1_0`() =
        checkFailedStrip(1.0f, ActivityCardDetail.Throttled, MimirAchievementCopy.BadgesHeld)

    @Test
    fun `a card told to wait says RetroAchievements asked for it and a tap tries again at font scale 1_3`() =
        checkFailedStrip(1.3f, ActivityCardDetail.Throttled, MimirAchievementCopy.BadgesHeld)

    private fun advance(ms: Long) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    @Test
    fun `a flick past many cards asks for none of them, and the card it settles on asks once after the pause`() {
        compose.mainClock.autoAdvance = false
        val asked = mutableListOf<Int>()
        mount(1.0f, hub((1..12).map { panelSnapshot(it) }, MutableStateFlow(emptyMap()), request = { asked += it }))
        advance(100)
        (1..10).forEach { index ->
            turnTo(index)
            advance(100)
        }
        assertEquals("eleven cards were each shown for 100 ms and none asked: $asked", emptyList<Int>(), asked)
        advance(ACTIVITY_CARD_ASK_DEBOUNCE_MS)
        assertEquals("only the card it settled on asked: $asked", 1, asked.size)
        advance(2_000)
        assertEquals("and only once: $asked", 1, asked.size)
    }

    @Test
    fun `a card for a game with no RetroAchievements id says its badges are not loaded, and never that they are loading`() {
        val details = MutableStateFlow<Map<Int, ActivityCardDetail>>(emptyMap())
        mount(1.0f, hub(listOf(panelSnapshot(1).copy(raGameId = null)), details))
        assertTrue("it says so: ${texts()}", MimirAchievementCopy.BadgesNotLoaded in texts())
        assertFalse(MimirAchievementCopy.BadgesLoading in texts())
        compose.onAllNodesWithTag(AchievementCardRetryTag, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `no state of a card says the bare progress-count sentence anywhere`() {
        val details = MutableStateFlow<Map<Int, ActivityCardDetail>>(emptyMap())
        mount(1.0f, hub(listOf(panelSnapshot(1), panelSnapshot(2).copy(raGameId = null)), details))
        val said = ArrayList<String>()
        val states = listOf<ActivityCardDetail?>(
            null,
            ActivityCardDetail.Loading,
            ActivityCardDetail.Unavailable,
            ActivityCardDetail.Rejected,
            ActivityCardDetail.Stalled,
            ActivityCardDetail.Throttled,
            ActivityCardDetail.Loaded(detailProgress(1, achievements = emptyList()), 0L),
            ActivityCardDetail.Loaded(detailProgress(1), 0L),
        )
        states.forEach { state ->
            compose.runOnIdle { details.value = if (state == null) emptyMap() else mapOf(1 to state) }
            compose.waitForIdle()
            said += texts()
            said += descriptions()
        }
        turnTo(1)
        said += texts()
        said += descriptions()
        assertEquals("every state said something: ${said.size}", emptyList<String>(), bareSentence(said))
        assertTrue("and the states did render", listOf(MimirAchievementCopy.BadgesFailed, MimirAchievementCopy.BadgesStalled, MimirAchievementCopy.BadgesHeld, MimirAchievementCopy.BadgesNotLoaded).all { it in said })
    }

    @Test
    fun `a badge hold is released when its card leaves composition`() {
        val badges = RecordingBadges(temp.newFolder("held"))
        mount(1.0f, hub((1..2).map { panelSnapshot(it) }, loadedDetails(1, 2), badges))
        compose.waitUntil(5_000L) { synchronized(badges.asked) { badges.asked.size >= 3 } }
        assertEquals("nothing is released while the card is up", 0, synchronized(badges.released) { badges.released.size })
        turnTo(1)
        compose.waitUntil(5_000L) { synchronized(badges.released) { badges.released.size >= 3 } }
        assertTrue(synchronized(badges.released) { badges.released.size } >= 3)
    }

    @Test
    fun `a card that leaves the moment its badge holds are taken still gives every hold back`() {
        val badges = RecordingBadges(temp.newFolder("leaving"))
        val show = mutableStateOf(true)
        // The worst moment: the hold is taken, and the card is removed before the producer has handed it over or the next recomposition could install a cleanup.
        badges.onAcquired = { show.value = false }
        mount(1.0f) {
            if (show.value) {
                ActivityAchievementPanel(
                    snapshot = panelSnapshot(1),
                    detail = ActivityCardDetail.Loaded(detailProgress(1), 0L),
                    artResolver = NoActivityAchievementArt,
                    badgeSource = badges,
                    reducedMotion = true,
                    modifier = Modifier.size(300.dp, 300.dp),
                )
            } else {
                Unit
            }
        }
        compose.waitUntil(5_000L) { synchronized(badges.asked) { badges.asked.isNotEmpty() } }
        compose.waitForIdle()
        // Every hold that was taken is given back, exactly once: the outstanding count returns to 0.
        compose.waitUntil(5_000L) { synchronized(badges.asked) { synchronized(badges.released) { badges.released.size == badges.asked.size } } }
        val asked = synchronized(badges.asked) { badges.asked.toList() }
        val released = synchronized(badges.released) { badges.released.toList() }
        assertEquals("every hold released once: asked=$asked released=$released", asked.sorted(), released.sorted())
    }

    // ── the progress bar, rendered ────────────────────────────────────────────────────────────────────────────────────────────────

    private fun shown(): Float =
        compose.onNodeWithTag("bar").fetchSemanticsNode().config[MimirEtchedProgressShownSemantics]

    private var barLive = mutableStateOf(true)

    private fun mountBar(reduced: Boolean, live: Boolean = true) {
        compose.mainClock.autoAdvance = false
        barLive = mutableStateOf(live)
        compose.setContent {
            CompositionLocalProvider(LocalMimirMotionLive provides barLive) {
                MimirTheme { MimirEtchedProgressBar(fraction = 0.5f, reducedMotion = reduced, modifier = Modifier.size(200.dp, 14.dp).testTag("bar")) }
            }
        }
    }

    @Test
    fun `the fill arrives once over a bounded time and then stands still with no idle frames`() {
        mountBar(reduced = false)
        compose.mainClock.advanceTimeBy(32L)
        val early = shown()
        assertTrue("on its way: $early", early < 0.5f)
        compose.mainClock.advanceTimeBy(MimirEtchedProgressArrivalMs + 200L)
        assertEquals(0.5f, shown(), 0.001f)
        compose.mainClock.advanceTimeBy(2_000L)
        assertEquals("static afterwards", 0.5f, shown(), 0.0001f)
    }

    @Test
    fun `under reduced motion the fill is there at once`() {
        mountBar(reduced = true)
        compose.mainClock.advanceTimeBy(16L)
        assertEquals(0.5f, shown(), 0.0001f)
    }

    @Test
    fun `parking in the middle of the fill cancels it and the fill is at its target at once`() {
        mountBar(reduced = false)
        compose.mainClock.advanceTimeBy(32L)
        val mid = shown()
        assertTrue("mid-fill: $mid", mid > 0f && mid < 0.5f)
        compose.runOnIdle { barLive.value = false }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(16L)
        // Had the fill kept running it would still be short of 0.5 one frame later; parked, it snapped to the target.
        assertEquals(0.5f, shown(), 0.0001f)
        compose.mainClock.advanceTimeBy(MimirEtchedProgressArrivalMs + 200L)
        assertEquals("and stays there", 0.5f, shown(), 0.0001f)
    }

    @Test
    fun `a parked pane snaps its fill and animates nothing`() {
        mountBar(reduced = false, live = false)
        compose.mainClock.advanceTimeBy(16L)
        assertEquals(0.5f, shown(), 0.0001f)
    }
}
