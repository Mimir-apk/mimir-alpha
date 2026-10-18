package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import com.gruning.mimir.rewrite.feature.settings.DetailScope
import com.gruning.mimir.rewrite.feature.settings.GameMusicDownloadReadModel
import com.gruning.mimir.rewrite.feature.settings.GameMusicFailureValue
import com.gruning.mimir.rewrite.feature.settings.MusicGameDownloadRowId
import com.gruning.mimir.rewrite.feature.settings.detailKey
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Settings > Music "Download game music" card in the states the reported video exposed: a lookup in flight with a long
 * game title, Stopping (Cancel pressed), Stopped, and a run that failed with its reason. Drawn on Main's true frame (1920 x 1080 px at
 * 369 dpi, the shell, the rail and the footer legend) at a type scale of 1.0 and 1.3, with the row focused so its selection ring shows.
 * Each state is ASSERTED as well as drawn: the whole copy is on the card (nothing the word ellipsis cut), and the PNGs land in
 * `build/test-captures/gamemusic/` for a look at the ring and the wrap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w833dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameMusicCardCaptureTest : MusicCaptureSupport() {

    private val longTitle = "The Legend of Zelda: A Link to the Past — Hyrule Warriors Special Edition"

    private fun card(scale: Float, state: String, batch: GameMusicDownloadReadModel, copy: String) {
        mount(MainWidthDp, MainHeightDp, scale, playingModel().copy(gameMusic = batch), detailKey(MusicGameDownloadRowId), DetailScope, shell = true)
        compose.onNode(hasText(copy), useUnmergedTree = true).assertIsDisplayed()

        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val out = File((File("build/test-captures").takeIf { it.isDirectory }?.let { File(it, "gamemusic") } ?: File("build/gamemusic")).apply { mkdirs() }, "gamemusic-card-$state-${scaleName(scale)}.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    private fun inFlight(scale: Float) {
        val name = gameMusicDisplayName(longTitle)
        assertTrue("the app cut the long title to one line: $name", name.length <= GameMusicNameMaxChars && name.endsWith("…"))
        card(
            scale,
            "1-inflight-long-title",
            GameMusicDownloadReadModel.Running(done = 1, total = 4, written = 0, currentIndex = 2, current = name),
            "Looking up 2 of 4 · $name\n0 written",
        )
    }

    private fun stopping(scale: Float) = card(
        scale,
        "2-stopping",
        GameMusicDownloadReadModel.Running(
            done = 1,
            total = 4,
            written = 0,
            currentIndex = 2,
            current = gameMusicDisplayName(longTitle),
            stopping = true,
        ),
        "Stopping…\n1 of 4 · 0 written",
    )

    private fun stopped(scale: Float) = card(
        scale,
        "3-stopped",
        GameMusicDownloadReadModel.Finished(written = 1, skipped = 0, failed = 0, stopped = true),
        "Stopped · 1 written",
    )

    private fun failedNoAnswer(scale: Float) = card(
        scale,
        "4-failed-no-answer",
        GameMusicDownloadReadModel.Finished(written = 0, skipped = 0, failed = 4, hint = GameMusicFailureValue.NO_ANSWER),
        "Done · 0 written · 4 failed\nScreenScraper didn’t answer — try again later",
    )

    private fun failedSignIn(scale: Float) = card(
        scale,
        "5-failed-sign-in-longest-hint",
        GameMusicDownloadReadModel.Finished(written = 0, skipped = 0, failed = 4, hint = GameMusicFailureValue.SIGN_IN_REFUSED),
        "Done · 0 written · 4 failed\nScreenScraper sign-in was refused — reconnect in Settings › Appearance › Enrichment & artwork",
    )

    @Test fun inFlightLongTitle_1_0x() = inFlight(1.0f)

    @Test fun inFlightLongTitle_1_3x() = inFlight(1.3f)

    @Test fun stopping_1_0x() = stopping(1.0f)

    @Test fun stopping_1_3x() = stopping(1.3f)

    @Test fun stopped_1_0x() = stopped(1.0f)

    @Test fun stopped_1_3x() = stopped(1.3f)

    @Test fun failedNoAnswer_1_0x() = failedNoAnswer(1.0f)

    @Test fun failedNoAnswer_1_3x() = failedNoAnswer(1.3f)

    @Test fun failedSignInLongestHint_1_0x() = failedSignIn(1.0f)

    @Test fun failedSignInLongestHint_1_3x() = failedSignIn(1.3f)
}
