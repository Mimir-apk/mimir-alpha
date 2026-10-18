package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.metadata.online.RaAwardKind
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsAchievement
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsProgress
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Update: every state of the award and hardcore marks the card header draws as etched chips (never filled pills), on the card's own ground. Three pictures:
 *  - the header alone in every state (no award, Beaten (softcore), Beaten, Completed, Mastered, each with its hardcore count) at font 1.0 and 2.0;
 *  - Main's achievements overlay page showing an award word (Beaten, with its hardcore count), at Thor Main's 832 x 468 dp;
 *  - the Bottom's card with the etched marks (Mastered, all earned in hardcore), at Thor Bottom's 538 x 468 dp.
 * The header is the one Main's page and the Bottom's card share. Drawn the way this repo rasterises (the view hierarchy into a software bitmap; see [EtchedButtonCaptureTest]),
 * as API 30. The PNGs land in `app/build/rap3-award/` and are copied to `build/test-captures` when it exists. Pixel values are the JVM's; the pixels on a handheld are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w853dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AchievementAwardMarksCaptureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shoot(font: Float, fileName: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 2f, fontScale = font)) {
                MimirTheme { content() }
            }
        }
        repeat(3) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(400L)
            compose.waitForIdle()
        }
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val out = File(File("build/rap3-award").apply { mkdirs() }, fileName)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val shared = File("build/test-captures")
        if (shared.isDirectory) out.copyTo(File(shared, fileName), overwrite = true)
        println("RAP3 wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    private fun headerStates(font: Float, fileName: String) = shoot(font, fileName) {
        Box(Modifier.requiredSize(853.dp, 480.dp).background(LocalMimirPalette.current.surfaceBase)) {
            Column(Modifier.padding(MimirSpacing.lg), verticalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
                // At font 2.0 five two-line headers do not fit the 480 dp window the capture draws in, so the sheet leaves out the one with no mark (the font 1.0 sheet has it).
                listOf(
                    Triple("No award, no hardcore", null as String?, 0),
                    Triple("Beaten (softcore) game", "Beaten (softcore)", 0),
                    Triple("Beaten game", "Beaten", 5),
                    Triple("Completed game", "Completed", 3),
                    Triple("Mastered game", "Mastered", 12),
                ).filter { font <= 1f || it.second != null }.forEach { (title, award, hardcore) ->
                    ActivityAchievementHeader(
                        title = title,
                        unlocked = 12,
                        total = 12,
                        progress = null,
                        hardcore = hardcore,
                        award = award,
                        titleMinLines = 1,
                        titleMaxLines = 2,
                        statsMaxLines = Int.MAX_VALUE,
                        statsFillWidth = true,
                    )
                }
            }
        }
    }

    /** A set of 12 badges: the first [earned] earned, the first [hardcore] of those in hardcore. */
    private fun progress(earned: Int, hardcore: Int) = RetroAchievementsProgress(
        gameId = 77,
        title = "Super Metroid",
        achievements = (1..12).map { id ->
            RetroAchievementsAchievement(
                id = id,
                title = "Badge $id",
                description = "Do thing $id",
                points = 5,
                unlocked = id <= earned,
                unlockedHardcore = id <= hardcore,
                badgeName = "%05d".format(id),
                earnedAt = if (id <= earned) "2023-05-01 10:00:00" else null,
                earnedHardcoreAt = if (id <= hardcore) "2023-05-01 12:34:56" else null,
            )
        },
    )

    @Test
    fun `the award and hardcore marks in every state at font 1_0`() = headerStates(1.0f, "rap3-award-marks-font1.png")

    @Test
    fun `the award and hardcore marks in every state at font 2_0`() = headerStates(2.0f, "rap3-award-marks-font2.png")

    @Test
    fun `Main's achievements page shows the award word beside the count and the hardcore mark`() {
        val progress = progress(earned = 6, hardcore = 5)
        val row = CompanionActivityAchievementSnapshot(
            gameId = "g77", account = "alice", unlocked = 6, total = 12, raGameId = 77, earnedHardcore = 5, awardKind = RaAwardKind.BEATEN_HARDCORE,
        )
        val authority = DefaultFocusAuthority()
        shoot(1.0f, "ra239-main-1.0.png") {
            Box(Modifier.requiredSize(853.dp, 480.dp).background(LocalMimirPalette.current.surfaceBase), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Box(Modifier.requiredSize(832.dp, 468.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    MimirMainAchievementPage(
                        progress = progress,
                        artSnapshot = mainAchievementArtSnapshot(progress, "g77", row),
                        artResolver = ActivityAchievementArtResolver { ActivityAchievementArtSources(null, null) },
                        badgeSource = NoActivityBadgeArt,
                        reducedMotion = true,
                        authority = authority,
                        onBack = {},
                        modifier = Modifier.padding(MimirSpacing.xl).widthIn(max = MainAchievementPageMaxWidth).fillMaxSize(),
                        artContext = EmptyCoroutineContext,
                    )
                }
            }
        }
    }

    @Test
    fun `the Bottom's card shows the etched award and hardcore marks`() {
        val progress = progress(earned = 12, hardcore = 12)
        val snapshot = CompanionActivityAchievementSnapshot(
            gameId = "g77", account = "alice", unlocked = 12, total = 12, gameTitle = "Super Metroid", raGameId = 77, earnedHardcore = 12, awardKind = RaAwardKind.MASTERED,
        )
        shoot(1.0f, "ra239-bottom-1.0.png") {
            Box(Modifier.requiredSize(853.dp, 480.dp).background(LocalMimirPalette.current.surfaceBase), contentAlignment = androidx.compose.ui.Alignment.Center) {
                ActivityAchievementPanel(
                    snapshot = snapshot,
                    detail = ActivityCardDetail.Loaded(progress, 1_000L),
                    artResolver = ActivityAchievementArtResolver { ActivityAchievementArtSources(null, null) },
                    badgeSource = NoActivityBadgeArt,
                    reducedMotion = true,
                    modifier = Modifier.requiredSize(538.dp, 468.dp),
                    artContext = EmptyCoroutineContext,
                )
            }
        }
    }
}
