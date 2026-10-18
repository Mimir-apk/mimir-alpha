package com.gruning.mimir.rewrite

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.LocalMimirMotionLive
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirIndeterminateBar
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.feature.library.LibraryLaunchReturnTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The return cover's words.
 *
 * After about seventy minutes in Azahar, Main came back to the return cover (`MimirPrimaryReturnCover`): the themed
 * surface with the Mannaz badge and no text, held until the Home shelf has re-landed on the game's tile. The wait is
 * bounded, not short: the cover rewarms the visible covers for up to 400 ms, then waits up to
 * [HomeLaunchReturnRenderableGraceMs] (4 s) for the shelf to take focus, and a player looking at a wordless rune for
 * five seconds can look like a hang.
 *
 * This is the cover telling the player what it is doing, and nothing else about the cover changes:
 *  - it appears only after [ReturnWaitStatusDelayMs] of the cover being up, so a return that settles in a beat never
 *    flashes a sentence;
 *  - it names the game only when the session ledger holds the very session that armed this return ("Back from <game> —
 *    restoring your library…"; see [returnWaitSubject]), and greets plainly in every other case;
 *  - an indeterminate sweep stands for the work. The restore does have steps (cover rewarm, focus landing), but they
 *    live inside `rememberHomeShelfCoordinator`, which ART already refuses to compile ("Method exceeds compiler
 *    instruction limit: 17488" in the Thor log) and which `MimirPrimaryReturnCover`'s own comment records as having once
 *    broken the dex verifier when it grew, so no step line is invented that the cover cannot observe;
 *  - it is one polite live region, so a screen reader says it once when it arrives;
 *  - reduced motion (and a parked pane) turns the fade and the sweep into a plain appearance and a still bar.
 */
internal const val ReturnWaitStatusDelayMs = 600L

/** The cover's badge, so the status can hang below it without moving it. */
internal val ReturnCoverBadgeSize: Dp = 56.dp

internal const val ReturnWaitLogTag = "MimirReturnWait"
internal const val ReturnWaitStatusTag = "return-wait-status"
internal const val ReturnWaitSweepTag = "return-wait-sweep"
internal const val ReturnWaitStillTag = "return-wait-still"

private val ReturnWaitMaxWidth = 320.dp
private val ReturnWaitIndicatorWidth = 160.dp

/** Which arm of [primaryReturnRestoring] is holding the cover: the vocabulary of the one log line. */
internal enum class ReturnWaitCause(val logName: String) {
    /** Main's Home is landing back on an anchored tile (an exact launch return, or a stored surface to restore). */
    HOME_RETURN("home-return"),

    /** Home's favourite truth has not arrived yet (a re-created Activity, or a Folio return waiting on rows). */
    HOME_TRUTH("home-truth"),
    LIBRARY_RETURN("library-return"),
    NATIVE_RETURN("native-shelf-return"),
}

/** [primaryReturnRestoring]'s three arms, named: the first that is true wins, Library and native before Home. */
internal fun returnWaitCause(native: Boolean, library: Boolean, homeAnchored: Boolean): ReturnWaitCause = when {
    library -> ReturnWaitCause.LIBRARY_RETURN
    native -> ReturnWaitCause.NATIVE_RETURN
    homeAnchored -> ReturnWaitCause.HOME_RETURN
    else -> ReturnWaitCause.HOME_TRUTH
}

/** The game (or app) the player just came back from, as far as the session ledger can say. */
internal data class ReturnWaitSubject(val title: String?, val packageName: String?) {
    companion object {
        val None = ReturnWaitSubject(title = null, packageName = null)
    }
}

/** A session in one of these phases has just been left; any other phase is a launch that has not settled or is still out. */
private val ReturnedPhases = setOf(
    MimirSessionPhase.RETURNING,
    MimirSessionPhase.ENDED,
    MimirSessionPhase.FAILED,
)

/**
 * The identity of the return that armed the cover, read from the record that return left behind: the Home record's
 * token, the Library pending target, the native shelf record's package. Absent for a cover held by nothing that was
 * armed (a plain Home surface restore, a Home waiting on its truth): then there is no outing to name.
 */
internal data class ReturnWaitArmed(
    val homeToken: String? = null,
    val libraryTarget: LibraryLaunchReturnTarget? = null,
    val nativePackage: String? = null,
) {
    companion object {
        val None = ReturnWaitArmed()
    }
}

/**
 * Whom the cover may name: the session that IS the armed return, and no other.
 *
 * The ledger keeps the last session of each kind after it ends, and a newer one of another kind (a Bottom game that
 * ended after the Main app being returned from) or an earlier one (a game played before a plain Home restore) is
 * equally "ended". Phase and recency say nothing about which outing this cover is for, so each arm matches the record:
 *  - Home: a Main game whose own return handle carries the armed record's token;
 *  - Library: a Main game whose return anchor is the pending Library target, game for game;
 *  - native shelf: the Main app whose package is the armed record's package.
 * Anything else, and every cover with no armed record, names nobody (the generic line).
 */
internal fun returnWaitSubject(
    snapshot: MimirSessionSnapshot,
    cause: ReturnWaitCause,
    armed: ReturnWaitArmed,
): ReturnWaitSubject {
    val game = snapshot.gameSession
        ?.takeIf { it.phase in ReturnedPhases && it.placement == MimirGamePlacement.MAIN }
    return when (cause) {
        ReturnWaitCause.HOME_TRUTH -> null
        ReturnWaitCause.HOME_RETURN -> game?.takeIf { armed.homeToken != null && it.homeReturnHandle?.token == armed.homeToken }
            ?.gameSubject()
        ReturnWaitCause.LIBRARY_RETURN -> game?.takeIf {
            val target = armed.libraryTarget
            target?.gameId != null &&
                it.gameId == target.gameId &&
                (it.returnAnchor as? MimirGameReturnAnchor.Library)?.target == target
        }?.gameSubject()
        ReturnWaitCause.NATIVE_RETURN -> snapshot.primaryAppSession
            ?.takeIf { it.phase in ReturnedPhases && armed.nativePackage != null && it.packageName == armed.nativePackage }
            ?.let { ReturnWaitSubject(it.label.appLabelOrNull(it.packageName), it.packageName) }
    } ?: ReturnWaitSubject.None
}

private fun MimirGameSession.gameSubject() = ReturnWaitSubject(title.trim().takeIf(String::isNotEmpty), targetPackageName)

/** `Foo.Bar.baz`: dot-separated identifiers, no spaces. What the ledger falls back to when an app has no label. */
private val PackageShapedLabel = Regex("""[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+""")

/**
 * An app's label, or null when it is not one. `appPreparing` stores the package name when the launching surface had no
 * label (`app.label.ifBlank { cleanPackage }`), so a label that is the package, or merely shaped like one, is the
 * ledger talking to itself and is never read to the player.
 */
internal fun String.appLabelOrNull(packageName: String): String? {
    val label = trim()
    if (label.isEmpty() || label.equals(packageName.trim(), ignoreCase = true)) return null
    if (PackageShapedLabel.matches(label)) return null
    return label
}

/** The sentence. The title is the player's own library data and is never case-changed. */
internal fun returnWaitHeadline(title: String?): String =
    title?.trim()?.takeIf(String::isNotEmpty)
        ?.let { "Back from $it — restoring your library…" }
        ?: "Welcome back — restoring your library…"

/** The one log line, written when a wait that showed the status ends: `waitedMs` is the cover's whole time up. */
internal fun returnWaitLogLine(
    subject: ReturnWaitSubject,
    waitedMs: Long,
    cause: ReturnWaitCause,
    detail: String = "",
): String =
    "return-wait shown game=${subject.packageName?.takeIf(String::isNotBlank) ?: "none"} " +
        "waitedMs=$waitedMs cause=${cause.logName}" + if (detail.isEmpty()) "" else " $detail"

/**
 * Whether the cover's wait is running: it is requested AND the host window is resumed. A cover that is requested while the host
 * is stopped (a launch raises it before the game takes the screen) is covering nothing the player can see.
 */
internal fun returnWaitIsRunning(waiting: Boolean, resumed: Boolean): Boolean = waiting && resumed

/** The fade's length: none under reduced motion, none while the pane is parked (D24), otherwise the house overlay clock. */
internal fun returnWaitFadeMs(reducedMotion: Boolean, live: Boolean): Int =
    if (reducedMotion || !live) 0 else MimirMotion.OverlayMs

/** Time spent on one wait: written once, and only if the status was actually put in front of the player. */
private class ReturnWaitLedger {
    private var startedAtMs = 0L
    private var shown = false
    private var detail = ""

    fun begin(nowMs: Long) {
        startedAtMs = nowMs
        shown = false
        detail = ""
    }

    fun markShown(detail: String) {
        shown = true
        this.detail = detail
    }

    fun finish(nowMs: Long, subject: ReturnWaitSubject, cause: ReturnWaitCause) {
        if (!shown) return
        shown = false
        // runCatching: android.util.Log is unmocked on the plain-JVM test path.
        runCatching {
            Log.i(ReturnWaitLogTag, returnWaitLogLine(subject, nowMs - startedAtMs, cause, detail))
        }
    }
}

/**
 * The status block: [headline] and an indeterminate sweep, after [delayMs] of [waiting].
 *
 * Seat it inside the return cover. It draws nothing until the delay has run, fades in on the overlay clock, fades out
 * the moment [waiting] ends (the cover's own dissolve carries the rest), and leaves with the cover when the cover leaves.
 * [logDetail] is read once, when the status shows, and goes into the log line: the cover's wait outlasts the delay when
 * the shelf does not yet own the focus scope, and the log is where that becomes readable.
 *
 * **The wait is the time the player can SEE the cover, so it runs only while the host is [resumed]**.
 * On Thor, the status was reported to flash on the first return frames and disappear about 170 ms later.
 * A Library or Home launch raises the cover
 * BEFORE the game takes the screen and the cover stays requested all the while Mimir is stopped; the delay runs on wall-clock
 * time, so it used to be spent behind the game, and the first resumed frame found the status already due: it faded in and the
 * restore lifted the cover a moment after. Now the ledger arms, and the delay starts, on resume. A pause ends the wait (the log
 * line is written then if the status had shown), and the next resume starts a new one, so `waitedMs` is what the player saw.
 */
@Composable
internal fun ReturnWaitStatus(
    waiting: Boolean,
    subject: ReturnWaitSubject,
    cause: ReturnWaitCause,
    modifier: Modifier = Modifier,
    delayMs: Long = ReturnWaitStatusDelayMs,
    reducedMotion: Boolean = mimirReducedMotion(),
    logDetail: () -> String = { "" },
    nowMs: () -> Long = SystemClock::elapsedRealtime,
    resumed: Boolean = true,
) {
    val palette = LocalMimirPalette.current
    val live = LocalMimirMotionLive.current
    val fadeMs = returnWaitFadeMs(reducedMotion, live.value)
    val ledger = remember { ReturnWaitLedger() }
    val latestSubject by rememberUpdatedState(subject)
    val latestCause by rememberUpdatedState(cause)
    val latestDetail by rememberUpdatedState(logDetail)
    val latestNow by rememberUpdatedState(nowMs)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(waiting, resumed, delayMs) {
        if (returnWaitIsRunning(waiting, resumed)) {
            ledger.begin(latestNow())
            delay(delayMs)
            ledger.markShown(runCatching { latestDetail() }.getOrDefault(""))
            shown = true
        } else {
            // Not waiting, or waiting behind a stopped window: nothing is in front of the player, so the status goes and a
            // wait that had shown is written (once; `finish` is a no-op for one that had not).
            shown = false
            ledger.finish(latestNow(), latestSubject, latestCause)
        }
    }
    // The cover can leave composition without `waiting` ever reading false (the overlay stack moves on): the wait is over then too.
    DisposableEffect(ledger) {
        onDispose { ledger.finish(latestNow(), latestSubject, latestCause) }
    }
    val headline = returnWaitHeadline(subject.title)
    AnimatedVisibility(
        visible = shown,
        modifier = modifier,
        enter = if (fadeMs == 0) EnterTransition.None else fadeIn(tween(fadeMs, easing = MimirMotion.Easing)),
        exit = if (fadeMs == 0) ExitTransition.None else fadeOut(tween(fadeMs, easing = MimirMotion.Easing)),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = ReturnWaitMaxWidth)
                .padding(horizontal = MimirSpacing.lg)
                .testTag(ReturnWaitStatusTag)
                // One phrase, announced politely when it arrives: the words and the sweep are a single message.
                .clearAndSetSemantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = headline
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
        ) {
            Text(
                text = headline,
                color = palette.textSecondary,
                style = MimirTypeScale.Support,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            ReturnWaitIndicator(animated = !reducedMotion && live.value)
        }
    }
}

/** The sweep, or the same bar standing still when motion is reduced or parked: never an empty track (see [MimirIndeterminateBar]). */
@Composable
private fun ReturnWaitIndicator(animated: Boolean) {
    val palette = LocalMimirPalette.current
    val bar = Modifier
        .width(ReturnWaitIndicatorWidth)
        .height(MimirSpacing.xs)
        .clip(RoundedCornerShape(MimirSpacing.xs / 2))
    if (animated) {
        MimirIndeterminateBar(
            modifier = bar.testTag(ReturnWaitSweepTag),
            trackColor = palette.emberSoft,
            sweepColor = palette.accent,
            reducedMotion = false,
        )
    } else {
        Box(bar.testTag(ReturnWaitStillTag).background(palette.emberSoft)) {
            Box(Modifier.fillMaxWidth(0.24f).fillMaxHeight().background(palette.accent))
        }
    }
}

/**
 * Hangs a child below the centre of its parent, [gap] under a [badge]-sized mark that sits dead-centre: the mark does not
 * move when the child arrives, and the child grows downward whatever its height.
 */
internal fun Modifier.hangBelowCenteredBadge(badge: Dp, gap: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val dy = (badge.roundToPx() + placeable.height) / 2 + gap.roundToPx()
    layout(placeable.width, placeable.height) { placeable.place(0, dy) }
}

/**
 * Reads the record the armed return left, off the main thread, for [cause]'s arm only. A failed read is the generic line,
 * never a crash on the return path.
 */
private suspend fun readArmedReturn(
    context: Context,
    cause: ReturnWaitCause,
    libraryTarget: LibraryLaunchReturnTarget?,
): ReturnWaitArmed = try {
    when (cause) {
        ReturnWaitCause.HOME_RETURN -> ReturnWaitArmed(homeToken = HomeLaunchReturnStore.armedToken(context))
        ReturnWaitCause.NATIVE_RETURN -> ReturnWaitArmed(
            nativePackage = withContext(Dispatchers.IO) { readMimirNativeLaunchReturn(context)?.packageName },
        )
        ReturnWaitCause.LIBRARY_RETURN -> ReturnWaitArmed(libraryTarget = libraryTarget)
        ReturnWaitCause.HOME_TRUTH -> ReturnWaitArmed.None
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    ReturnWaitArmed.None
}

/**
 * The cover's seat for the status: reads the three arms that can hold the cover, the record the armed return left and
 * the session ledger, and hands [ReturnWaitStatus] plain values. [requested] is the cover's own flag. The record is read
 * once the cover is up, long before the status's delay has run, so the line never changes its words after it shows.
 */
@Composable
internal fun MimirPrimaryReturnWaitStatus(bindings: MimirAppBindings, requested: Boolean) {
    val session by MimirSessionAuthority.state.collectAsState()
    val cause = returnWaitCause(
        native = bindings.nativeShelf.launchReturnRestoring,
        library = bindings.navigation.libraryLaunchReturnRestoring,
        homeAnchored = bindings.homeShelf.launchReturnFocusKey != null,
    )
    val context = bindings.core.context
    val libraryTarget = bindings.library.pendingLaunchReturnTarget
    // Runtime 1.7.3 lint misses the inherited value setter below.
    @SuppressLint("ProduceStateDoesNotAssignValue")
    val armed by produceState(ReturnWaitArmed.None, requested, cause, libraryTarget) {
        value = if (requested) readArmedReturn(context, cause, libraryTarget) else ReturnWaitArmed.None
    }
    val authority = bindings.core.focusAuthority
    // The cover now outlives its request by its exit fade. The three arms and the armed record all clear the moment the
    // request ends, so read live they would turn "Back from <game>" into the generic line (and the log's cause into
    // home-truth) while the sentence is fading out. What the wait was is frozen at the last frame it was requested.
    val frozen = remember { arrayOfNulls<Pair<ReturnWaitSubject, ReturnWaitCause>>(1) }
    if (requested || frozen[0] == null) frozen[0] = returnWaitSubject(session, cause, armed) to cause
    val (subject, shownCause) = requireNotNull(frozen[0])
    ReturnWaitStatus(
        waiting = requested,
        subject = subject,
        cause = shownCause,
        modifier = Modifier.hangBelowCenteredBadge(ReturnCoverBadgeSize, MimirSpacing.xl),
        // What the Library was showing when the sentence appeared, beside what the return will restore: a return that finds the Library
        // somewhere other than where it left it (the 10-14 crash-exit return found the shelf) is diagnosable from one line.
        logDetail = {
            val library = bindings.library
            "scope=${authority.state.value.scope.value} splash=${WelcomeHold.active} " +
                "library=${if (library.openPlatform == null) "shelf" else "gamelist"}${if (library.detailSession != null) "+card" else ""} " +
                "target=${libraryTarget?.surface?.name?.lowercase() ?: "none"}${if (libraryTarget?.restoreLaunchCard == true) "+card" else ""}"
        },
        // The host's own lifecycle truth (the Activity observer), not the cover's request: the cover is raised at launch.
        resumed = bindings.navigation.libraryHostResumed,
    )
}
