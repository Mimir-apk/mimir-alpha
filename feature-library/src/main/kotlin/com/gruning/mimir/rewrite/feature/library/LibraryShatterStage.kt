package com.gruning.mimir.rewrite.feature.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirBoxArtCorner
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirShatterMotion
import com.gruning.mimir.rewrite.core.ui.MimirTransitionInputGate
import com.gruning.mimir.rewrite.core.ui.mimirHold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * "Every hide shatters the card" : the seam between the removal foundation (walk-32g) and the
 * destruction (LibraryShatterOverlay).
 *
 * The removal foundation gives a hide two seams. [LibraryViewModel.departureSlot] is awaited BEFORE the game is marked or
 * written, while it is still listed, focused and drawn: that is where the card can still be captured. The centred card's
 * last bounds are the other. This stage is what the screen puts in that slot. It waits out the hide prompt's closing beat
 * ([MimirMotion.OverlayDissolveCoverMs]) so the card is never destroyed under the prompt, takes a picture of the card as it
 * is drawn, publishes it as a [LibraryShatterJob] for the overlay, and returns: the pending mark, the survivor hand-off
 * and the ONE write then run exactly as walk-32g built them, and the departure they seat (kind [GamelistDepartureKind.Shatter])
 * paints no card of its own, closes its gap from [MimirShatterMotion.GapLeadMs] and lets the hero go at
 * [MimirShatterMotion.HeroHoldMs]. Nothing here reopens the write, the pending set or the focus hand-off.
 *
 * It can decline, and then the hide plays walk-32g's dissolve (or cuts, in the views that cannot play one yet): the
 * animators are off (reduced motion), the list is the carousel of a platform and holds another game to close over, the card
 * is not the one the player is hiding, or there is no picture to take. A declined hide still tells the player where the game
 * went, at once.
 */

/** One card being broken: its picture, where it was (window pixels), whose it is, and the one clock the overlay plays it on. */
internal class LibraryShatterJob(
    val bitmap: ImageBitmap,
    /** The card's art panel, in window coordinates: [LibraryShatterOverlay] places the shards over it. */
    val bounds: Rect,
    /** The hidden game's stable id: it seeds the shards. */
    val stableId: String,
    val clock: Animatable<Float, AnimationVector1D> = Animatable(0f),
    /** The card's own corner, for the strike's flash to sit inside it: a tile, a row and box art are rounded differently. */
    val cornerRadius: Dp = MimirBoxArtCorner,
)

/** Whether a hide is one the shatter can play: everything the departure it seats will need is there. */
internal fun libraryShatterEligible(
    enabled: Boolean,
    gameCount: Int,
    centreGameId: String?,
    hidingGameId: String,
    hasCapture: Boolean,
    hasBounds: Boolean,
): Boolean = enabled && gameCount >= 2 && centreGameId == hidingGameId && hasCapture && hasBounds

@Stable
internal class LibraryShatterStage {
    /** The card being broken, or null. Read in composition by whatever hosts the overlay, cleared when it has finished. */
    var job by mutableStateOf<LibraryShatterJob?>(null)
        internal set

    /** Written by the screen every composition: the animators are on and the list is one a departure can be seated in. */
    @Volatile
    var enabled: Boolean = false

    /**
     * Written by the screen every composition: whether the view a hide happens in seats a departure (the carousel and, from
     * walk-32i, the lists of games, a platform's LIST view and the Favorites list: they close the gap, hold the hero and
     * release the pad themselves). The Favorites lists of Android packages and the native shelf do not, so the card or row
     * leaves at the write and the stage holds the pad for the length of the shards and lets go.
     */
    @Volatile
    var seatsDeparture: Boolean = false

    /**
     * Written by the screen every composition: how many cards or rows the surface shows (games, or the native shelf's or
     * Favorites' packages). A hide needs one that stays to hand focus to and close over.
     */
    @Volatile
    var gameCount: Int = 0

    /** Written by the centred card: what it draws through, where its art panel is (window pixels), and whose it is. */
    @Volatile
    var centreCapture: LibraryShatterCapture? = null

    @Volatile
    var centreBounds: Rect? = null

    /**
     * Where the centred card is, live. [centreBounds] is what the card last reported when it was PLACED; a card whose face is
     * scaled by a draw-only layer (the native shelf's tile grows into focus after it is placed) has moved since, and
     * placement does not fire again for that. So the place the shards start from is asked of these when the picture is
     * taken, and [centreBounds] is the fallback.
     */
    @Volatile
    var centreCoordinates: LayoutCoordinates? = null

    @Volatile
    var centreGameId: String? = null

    /** Written by the centred card with the rest: its own corner (see [LibraryShatterJob.cornerRadius]). */
    var centreCorner: Dp = MimirBoxArtCorner

    /** Whether [game] is the one being broken: asked by the departure tracker as the departure is seated. */
    fun shatters(game: GameRow): Boolean = job?.stableId == game.gameId.value

    /** The overlay has finished [finished]; a newer job is not disturbed. */
    fun finish(finished: LibraryShatterJob) {
        if (job === finished) job = null
    }

    /**
     * What the hide slot does for [game]. [uiScope] is the screen's own scope: the view model's has no frame clock, and the
     * wait, the capture and the receipt run on the animation clock (so a slowed animator slows them with the shards, and a
     * stopped one makes them instant). [receipt] posts the confirmation. It returns when the card may go: at once when it
     * declines (after posting the receipt), and otherwise the moment the picture is taken and the overlay's job is
     * published, with the receipt scheduled for [MimirShatterMotion.ReceiptAtMs] later.
     */
    suspend fun play(game: GameRow, uiScope: CoroutineScope, receipt: () -> Unit) =
        play(game.gameId.value, uiScope, receipt)

    /** The same, for whatever the surface calls the card it breaks: a game's id, or an Android package name (walk-32h). */
    suspend fun play(id: String, uiScope: CoroutineScope, receipt: () -> Unit) {
        val eligible = libraryShatterEligible(
            enabled = enabled,
            gameCount = gameCount,
            centreGameId = centreGameId,
            hidingGameId = id,
            hasCapture = centreCapture != null,
            hasBounds = centreBounds != null,
        )
        if (!eligible) {
            receipt()
            return
        }
        val started = try {
            withContext(uiScope.coroutineContext) { start(id, uiScope) }
        } catch (cancelled: CancellationException) {
            // The screen's scope ended (the Library left) while the prompt was closing: the hide still goes ahead, as a
            // cut. The hide's own coroutine being cancelled is the other case, and that one goes on.
            currentCoroutineContext().ensureActive()
            false
        }
        if (started) {
            uiScope.launch {
                mimirHold(MimirShatterMotion.ReceiptAtMs)
                receipt()
            }
        } else {
            receipt()
        }
    }

    /** The beat, the picture and the job. The pad is held from the press: a press now would move focus off the card. */
    private suspend fun start(id: String, uiScope: CoroutineScope): Boolean {
        val gate = Any()
        MimirTransitionInputGate.hold(
            gate,
            MimirMotion.OverlayDissolveCoverMs + MimirShatterMotion.InputHoldMs + MimirMotion.OverlayMs,
        )
        try {
            mimirHold(MimirMotion.OverlayDissolveCoverMs)
            val capture = centreCapture
            val picture = if (capture != null && centreBounds != null && centreGameId == id) {
                capture.captureOrNull()
            } else {
                null
            }
            // Asked for now, with the picture: where the card is at this moment, not where it was when it was last placed.
            val bounds = centreCoordinates?.takeIf { it.isAttached }?.boundsInWindow() ?: centreBounds
            if (picture == null || bounds == null) {
                MimirTransitionInputGate.release(gate)
                return false
            }
            job = LibraryShatterJob(picture, bounds, id, cornerRadius = centreCorner)
            if (seatsDeparture) {
                // The gate is not released here: the departure that is about to be seated takes it over and lets go at the
                // last frame of the gap, and if none is seated it lapses on its own.
            } else {
                // No departure will be seated (the row leaves at the write), so nothing else lets go of the pad: it waits
                // for the shards and is released as the last one fades.
                uiScope.launch {
                    mimirHold(MimirShatterMotion.EndMs)
                    MimirTransitionInputGate.release(gate)
                }
            }
            return true
        } catch (t: Throwable) {
            MimirTransitionInputGate.release(gate)
            throw t
        }
    }
}

/**
 * What the card or row that can be hidden puts in its modifier chain, outermost, outside everything the shatter should
 * carry (frame, ring, favourite pin). While [active] (it is the centred card, or the focused row) it draws through a layer
 * ([libraryShatterCapture]), reports where it is in the window, and tells [stage] whose it is and what to read the picture
 * from; the moment it stops being active, or leaves, it withdraws all of that. Inactive, or with no [stage], it is
 * `Modifier` and nothing else, so only the one card or row pays.
 */
@Composable
internal fun rememberLibraryShatterPanel(
    stage: LibraryShatterStage?,
    active: Boolean,
    gameId: String,
    /** The corner of what the card or row draws, so the strike's flash sits inside it. Box art's, unless told otherwise. */
    corner: Dp = MimirBoxArtCorner,
): Modifier {
    val capture = if (stage != null && active) rememberLibraryShatterCapture() else null
    if (stage != null && capture != null) {
        DisposableEffect(capture, gameId, corner) {
            stage.centreCapture = capture
            stage.centreGameId = gameId
            stage.centreCorner = corner
            onDispose {
                if (stage.centreCapture === capture) {
                    stage.centreCapture = null
                    stage.centreBounds = null
                    stage.centreCoordinates = null
                    stage.centreGameId = null
                    stage.centreCorner = MimirBoxArtCorner
                }
            }
        }
    }
    return if (stage != null && capture != null) {
        Modifier
            .libraryShatterCapture(capture)
            .onGloballyPositioned {
                stage.centreCoordinates = it
                stage.centreBounds = it.boundsInWindow()
            }
    } else {
        Modifier
    }
}

/**
 * Where the stage's job is played: put this in a box that fills the area the card or row is in, after what it breaks over
 * and before what must stay above it. Nothing is composed while there is no job. One job at a time; the overlay ends it.
 */
@Composable
internal fun LibraryShatterHost(stage: LibraryShatterStage?) {
    val job = stage?.job
    if (stage != null && job != null) {
        key(job) {
            // A host that leaves the screen mid-shatter (the surface closed under it) takes its job with it, so a later
            // host does not find one that was never finished and play it again from wherever its clock stopped.
            DisposableEffect(job) { onDispose { stage.finish(job) } }
            LibraryShatterOverlay(
                snapshot = job.bitmap,
                bounds = job.bounds,
                stableId = job.stableId,
                reducedMotion = false,
                onFinished = { stage.finish(job) },
                palette = LocalMimirPalette.current,
                clock = job.clock,
                cornerRadius = job.cornerRadius,
            )
        }
    }
}
