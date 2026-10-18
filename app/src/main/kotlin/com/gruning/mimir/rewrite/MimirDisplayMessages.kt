package com.gruning.mimir.rewrite

import android.content.Context
import android.view.Display
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.ui.MimirHintToastRail
import com.gruning.mimir.rewrite.core.ui.MimirHintToastRailDefaults
import com.gruning.mimir.rewrite.core.ui.MimirMessage
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.feature.library.homeShelfQuickRailClearanceDp
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Mimir's own message for the screen the player is looking at (ledger D12): the in-app answer to what an Android toast used to be, drawn by the shared message rail
 * ([MimirHintToastRail]) on the display it belongs to. A toast is placed by the display of the context it was made with and is drawn by the system, outside Mimir's themes,
 * motion and accessibility; this is drawn by the screen itself, in the house message language.
 *
 * Each display has ONE rail: Main's is the Activity's root while it is resumed, the Bottom's is the Presentation's root while its window is up and lit
 * ([MimirDisplayMessageHost]). Every message for Main goes through it, including the Settings connection feedback and the Library's parked-launch refusal
 * ([MimirForwardMessageToHost]), so two message cards never share the bottom centre.
 *
 * A message is posted to a display and that display's host shows it; one posted to a display with no live host is NOT swallowed: [showMimirMessage] hands it to the system toast,
 * as before, so a message made while Mimir is behind a game (or from a window that has no host) is still seen.
 *
 * "Live" is asked at the moment of the post, from the window's own state ([MimirHostLiveness.isLiveNow]), never from what the host's composition last saw: a window that is parked
 * behind an app stops its frame clock, so a composition-driven flag would still say "live" until the window came back, and a failure message made meanwhile would be shown late, on
 * the reveal, as news. Nothing is queued for a window that is not live.
 */
internal object MimirDisplayMessages {
    private class Registration(val isLiveNow: () -> Boolean)

    private val nextEventId = AtomicLong(0L)
    private val registrations = MutableStateFlow<Map<Int, List<Registration>>>(emptyMap())
    private val posted = MutableStateFlow<Map<Int, MimirMessage>>(emptyMap())

    /** Whether [displayId] has a host that is composed AND whose window is up and drawing right now. */
    fun hasLiveHost(displayId: Int): Boolean =
        registrations.value[displayId].orEmpty().any { registration -> runCatching { registration.isLiveNow() }.getOrDefault(false) }

    /**
     * Posts [text] to [displayId]'s host. False (and nothing posted, nothing kept) when that display has no live host, so the caller can fall back. Safe from any thread.
     */
    fun post(
        displayId: Int,
        text: String,
        messageClass: MimirMessageClass = MimirMessageClass.Warning,
        supportingText: String? = null,
        holdMs: Long? = null,
    ): Boolean {
        if (text.isBlank()) return false
        return postMessage(
            displayId,
            MimirMessage(
                eventId = 0L,
                text = text,
                messageClass = messageClass,
                supportingText = supportingText?.takeIf(String::isNotBlank),
                holdMs = holdMs,
            ),
        )
    }

    /**
     * Posts a whole [message] (another producer's, with its own class and supporting line) to [displayId]'s host under a fresh event id of this registry's own: producers number
     * their events independently and the rail keys on the id. False, and nothing kept, when the display has no live host.
     */
    fun postMessage(displayId: Int, message: MimirMessage): Boolean {
        if (!hasLiveHost(displayId)) {
            discard(displayId)
            return false
        }
        val stamped = message.copy(eventId = nextEventId.incrementAndGet())
        posted.update { it + (displayId to stamped) }
        // The window can have gone dark between the check and the write; a message must not wait for it to come back.
        if (!hasLiveHost(displayId)) {
            posted.update { if (it[displayId]?.eventId == stamped.eventId) it - displayId else it }
            return false
        }
        return true
    }

    /** What [displayId]'s host shows next: the last message posted to it that its host has not yet taken in. */
    fun messages(displayId: Int): Flow<MimirMessage?> = posted.map { it[displayId] }.distinctUntilChanged()

    /** The host has taken [eventId] in and now owns its display window; a later remount of the host must not replay it. */
    fun delivered(displayId: Int, eventId: Long) {
        posted.update { if (it[displayId]?.eventId == eventId) it - displayId else it }
    }

    /** Drops whatever is waiting for [displayId]'s host: a message is for the screen it was made on, now, and is never kept for a later look. */
    fun discard(displayId: Int) {
        posted.update { it - displayId }
    }

    /**
     * A host for [displayId] is composed; [isLiveNow] is its window's own answer to "is anyone looking", asked at every post. The returned function withdraws it.
     */
    fun attach(displayId: Int, isLiveNow: () -> Boolean = { true }): () -> Unit {
        val registration = Registration(isLiveNow)
        registrations.update { it + (displayId to (it[displayId].orEmpty() + registration)) }
        var attached = true
        return {
            if (attached) {
                attached = false
                registrations.update { hosts ->
                    val left = hosts[displayId].orEmpty().filter { it !== registration }
                    if (left.isNotEmpty()) hosts + (displayId to left) else hosts - displayId
                }
                // Nobody left to show what was waiting: it is not kept for a later host.
                if (registrations.value[displayId].isNullOrEmpty()) discard(displayId)
            }
        }
    }

    /** Test seam: a clean slate. */
    fun resetForTest() {
        registrations.value = emptyMap()
        posted.value = emptyMap()
    }
}

/**
 * How a host's window answers "is anyone looking": [isLiveNow] is read at every post, from the window's own state, with no recomposition in between; [changes] is each change of
 * that answer, collected by the host's effect (a coroutine, which still runs when the window's frame clock is stopped) to drop what was waiting and reset the rail.
 */
internal class MimirHostLiveness(
    val isLiveNow: () -> Boolean,
    val changes: Flow<Boolean>,
) {
    companion object {
        /** A window whose liveness is a plain flag. */
        fun of(live: StateFlow<Boolean>): MimirHostLiveness = MimirHostLiveness(isLiveNow = { live.value }, changes = live)

        /** An Activity (Main): live while its lifecycle is RESUMED; a stopped Activity draws nothing a message could be seen on. */
        fun ofLifecycle(lifecycle: Lifecycle): MimirHostLiveness = MimirHostLiveness(
            isLiveNow = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) },
            changes = callbackFlow {
                val observer = LifecycleEventObserver { _, _ -> trySend(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
                lifecycle.addObserver(observer)
                awaitClose { lifecycle.removeObserver(observer) }
            }.distinctUntilChanged(),
        )
    }
}

/**
 * The display a message made with [context] belongs to: the display of a visual or display context (an Activity, the Bottom's Presentation, a
 * [companionFeedbackContext]), else the default display (an application or service context has none).
 */
internal fun mimirMessageDisplayId(context: Context): Int =
    runCatching { context.display?.displayId }.getOrNull() ?: Display.DEFAULT_DISPLAY

/**
 * Shows [text] as a Mimir message on the display [context] belongs to (or [displayId] when given), in place of `Toast.makeText(context, text, ...).show()`. With no live host on that
 * display it is the system toast it replaced, with the same context, text and length, so no message is lost.
 *
 * [messageClass] is the intention the rail dresses it in: a refusal or failure is a [MimirMessageClass.Warning] (the default; announced assertively), a thing that worked a
 * [MimirMessageClass.Confirmation], an explanation of what will happen a [MimirMessageClass.Guidance]. [long] is the toast's LENGTH_LONG; [holdMs] (when given) is how long the rail keeps the message instead of its usual window, and the fallback system toast takes its long length for any hold past the rail's usual window.
 */
internal fun showMimirMessage(
    context: Context,
    text: CharSequence,
    messageClass: MimirMessageClass = MimirMessageClass.Warning,
    supportingText: String? = null,
    long: Boolean = false,
    displayId: Int = mimirMessageDisplayId(context),
    holdMs: Long? = null,
) {
    if (MimirDisplayMessages.post(displayId, text.toString(), messageClass, supportingText, holdMs)) return
    Toast.makeText(context, text, if (long || (holdMs ?: 0L) > MimirHintToastRailDefaults.ToastWindowMs) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

/** Where the Bottom's message rail stands: clear of the dock's handle and the page diamonds that share its bottom edge (the lattice frame ends 10 dp above the scene's bottom). */
internal val MimirBottomMessageRailBottomInset: Dp = 28.dp

/** The share of the screen's width the rail takes, as Main's own rails do. */
internal const val MimirMessageRailWidthFraction = 0.42f

/**
 * The share of the Bottom's width its rail takes. 0.42 of the Bottom's 537.7 dp is 226 dp, where Main's is 349 dp (0.42 of its 832): the Bottom's longest notice ("Android app switching
 * couldn't open. Review access in Settings > Controls.") ran to three lines at font 1.0 and past three at 1.3, and its tail was cut. 0.65 of the Bottom is Main's 349 dp, so the same
 * copy takes the lines it takes on Main.
 */
internal const val MimirBottomMessageRailWidthFraction = 0.65f

/**
 * The host of [displayId]'s messages: the shared rail, bounded to [MimirMessageRailWidthFraction] of the width and held [bottomInset] above the bottom edge, registered for as long
 * as it is composed with [liveness] as its window's own answer. Place it last in the display's root so it is drawn over the screen; it takes no touch and no focus.
 *
 * When the window stops being live, the effect below (a coroutine: it runs without a frame, which a parked window does not have) drops what was waiting and bumps [epoch], which
 * remounts the rail on the next composition, so a card that was up when the window went dark, or a message it never got to draw, is not on screen when the window is revealed.
 */
@Composable
internal fun MimirDisplayMessageHost(
    displayId: Int,
    liveness: MimirHostLiveness,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    growsToFit: Boolean = false,
    railWidthFraction: Float = MimirMessageRailWidthFraction,
) {
    DisposableEffect(displayId, liveness) {
        val detach = MimirDisplayMessages.attach(displayId, liveness.isLiveNow)
        onDispose { detach() }
    }
    var epoch by remember { mutableIntStateOf(0) }
    LaunchedEffect(displayId, liveness) {
        liveness.changes.collect { live ->
            if (!live) {
                MimirDisplayMessages.discard(displayId)
                epoch++
            }
        }
    }
    val message by remember(displayId) { MimirDisplayMessages.messages(displayId) }.collectAsState(null)
    key(epoch) {
        MimirHintToastRail(
            hint = null,
            toast = message,
            onMessageDelivered = { eventId -> MimirDisplayMessages.delivered(displayId, eventId) },
            growsToFit = growsToFit,
            modifier = modifier
                .fillMaxWidth(railWidthFraction)
                .padding(bottom = bottomInset),
        )
    }
}

/** The display a host's composition draws on, read from the context it is composed with. */
@Composable
internal fun rememberHostDisplayId(): Int {
    val context = LocalContext.current
    return remember(context) { mimirMessageDisplayId(context) }
}

/**
 * How far above Main's bottom edge the rail's bottom edge stands on a screen whose dock is the quick rail (Home, and the Library's platform shelf):
 * the dock's top edge ([homeShelfQuickRailClearanceDp], the one number both screens place it from) plus [MimirMainMessageRailDockGap]. The rail
 * had stood [MimirSpacing.xl] up everywhere, which on those two screens is on top of the dock's coins ; this puts it in the lane Home
 * keeps clear above the dock for its own hints and toasts. [heightDp] is the host's own height, which is the screens' (both fill the window).
 */
internal fun mimirMainMessageRailInsetAboveDock(heightDp: Float): Dp = (homeShelfQuickRailClearanceDp(heightDp) + MimirMainMessageRailDockGap.value).dp

/** The room between the rail and the dock's top edge: the dock's coins stand a little proud of its band, and a pill 4 dp off them read as touching them. */
internal val MimirMainMessageRailDockGap: Dp = 8.dp

/**
 * Whether Main's rail stands above the dock: on Home, and on the Library's platform SHELF (no platform open), the two screens whose dock is the quick rail.
 * An open gamelist has no dock (its only chrome is the hint button in the corner), and every other screen keeps the rail's old seat.
 */
internal fun mainMessageRailClearsDock(destination: MimirDestination, libraryOpenPlatform: PlatformId?): Boolean =
    destination == MimirDestination.HOME || (destination == MimirDestination.LIBRARY && libraryOpenPlatform == null)

/**
 * Main's host, with the window's answer to "is anyone looking" given ([MimirActivityMessageHost] asks the Activity's lifecycle): the rail at the
 * bottom centre, [MimirSpacing.xl] up, or, when [clearsDock] (Home and the Library), above the quick rail's top edge. The band grows to fit
 * a larger font's extra lines ([MimirHintToastRail] `growsToFit`), upward from that edge.
 */
@Composable
internal fun MimirMainMessageHost(liveness: MimirHostLiveness, clearsDock: Boolean) {
    val displayId = rememberHostDisplayId()
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val inset = if (clearsDock) mimirMainMessageRailInsetAboveDock(maxHeight.value) else MimirSpacing.xl
        MimirDisplayMessageHost(displayId = displayId, liveness = liveness, bottomInset = inset, growsToFit = true)
    }
}

/**
 * Main's host: the Activity's root draws the rail for the display the Activity is on, at the bottom centre where Main's other rails stood ([MimirSpacing.xl] up)
 * or above the dock on [clearsDock] screens, and is live only while the Activity is resumed (asked of its lifecycle at every post).
 */
@Composable
internal fun MimirActivityMessageHost(clearsDock: Boolean = false) {
    val owner = LocalLifecycleOwner.current
    val liveness = remember(owner) { MimirHostLiveness.ofLifecycle(owner.lifecycle) }
    MimirMainMessageHost(liveness = liveness, clearsDock = clearsDock)
}

/**
 * Hands another producer's [message] to this display's one rail and tells its owner it has been taken ([onForwarded], with the producer's own event id), in place of that producer
 * drawing a rail of its own: Settings' connection feedback and the Library's parked-launch refusal used to each mount a rail at the very spot Main's host stands. A message the host
 * cannot take (no live host) is dropped, as it would have been unseen; it is not kept for later.
 */
@Composable
internal fun MimirForwardMessageToHost(message: MimirMessage?, onForwarded: (eventId: Long) -> Unit) {
    val displayId = rememberHostDisplayId()
    val currentOnForwarded by rememberUpdatedState(onForwarded)
    LaunchedEffect(message?.eventId, displayId) {
        val taken = message ?: return@LaunchedEffect
        MimirDisplayMessages.postMessage(displayId, taken)
        currentOnForwarded(taken.eventId)
    }
}
