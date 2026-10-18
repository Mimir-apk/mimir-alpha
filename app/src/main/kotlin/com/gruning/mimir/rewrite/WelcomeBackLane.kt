package com.gruning.mimir.rewrite

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.gruning.mimir.rewrite.core.ui.MimirMannazBadge
import com.gruning.mimir.rewrite.core.ui.MimirTypeStep
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirIntentText
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTextFragment
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.theme.MimirType
import com.gruning.mimir.rewrite.feature.library.WarmupLedger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Mimir Alpha — the cold-start "Welcome back" lane.
 *
 * A return visit opens on a brief branded beat instead of a cold shelf: the greeting, the themed
 * Eoksadberg line, and "Refreshing your library…" hold the screen while the home composes and warms
 * BEHIND it, then the shared Mimir overlay dissolve reveals both together — the mask-reveal keeps
 * a process-cold relaunch (emulator memory pressure, reload) from reading as a hang.
 *
 * Contract carried over 1:1 from shipping:
 *  - EVERY cold start / activity re-create once onboarding is complete. Never on first run — onboarding owns that ceremony. Never on an
 *    in-process game return (the activity survives; nothing recomposes this host).
 *  - Fixed 3.2s hold, timer-only dismissal. [WelcomeHold] swallows key/touch/motion in MainActivity's
 *    dispatchers so a buffered press can't act on the still-masked shelf. (Shipping needed no gate — its
 *    masked DOM had nothing focusable; Compose composes the live shelf underneath, so the gate is ours.)
 *  - The exit uses the canonical Mimir overlay fade onto the already-painted shelf; input remains gated
 *    until the outgoing splash pixels are gone.
 */
object WelcomeHold {
    /** TRUE while the welcome beat holds the screen — MainActivity swallows all input while set. */
    @Volatile
    var active: Boolean = false

    /** Welcome splash surfaces composed right now, a fading one included ([WelcomeBackSplash] registers itself). */
    private val composedSplashSurfaces = java.util.concurrent.atomic.AtomicInteger(0)

    internal val splashSurfacesComposed: Int
        get() = composedSplashSurfaces.get()

    internal fun onSplashSurfaceComposed() {
        composedSplashSurfaces.incrementAndGet()
    }

    internal fun onSplashSurfaceDisposed() {
        composedSplashSurfaces.decrementAndGet()
    }

    /**
     * MainActivity's last-resort release (its `welcomeHoldBackstop`). Returns whether it opened the gate.
     *
     * It does nothing while a splash surface is composed (O11b finding, 2026-09-12). The backstop and the hold do not
     * share a clock: the backstop is posted on the decor view before attach, so `View.postDelayed` starts its
     * [WelcomeHoldBackstopMs] at attach, while the hold measures its beat and cap from the splash layer's
     * LaunchedEffect, after the first frame. With a first frame of about 1 s (O11, Thor), a hold that ran to its cap was
     * still on screen about 0.5 s after the backstop fired, and input could reach the shelf under the splash. While a
     * surface is composed, its own layer opens the gate when it is disposed. With none composed (the splash never
     * composed, or it is already gone) the backstop releases as before. Both run on the main thread, so a backstop
     * cannot slip between a surface's dispose and its layer's release.
     */
    internal fun releaseFromBackstop(): Boolean {
        if (composedSplashSurfaces.get() > 0) return false
        active = false
        return true
    }
}

/** Shipping WELCOME_BACK_COLD_START_MS (app.js:12957) — 3.2s so the beat is actually SEEN. */
internal const val WelcomeBackColdStartMs = 3_200L

/** Static posture ink in the branded cold-start card; dynamic warm-up stages remain sentence case. */
internal const val WelcomeBackPostureBadgeCopy = "ON THIS DEVICE ONLY"

/** After the minimum welcome beat, linger until the WARM LEDGER
 *  settles — rows, SAF art resolution, cover decodes, picked platform art — capped at this WORST case
 *  (typical stays ~3.2–5s; per-task budgets inside the ledger keep the cap theoretical). A genuinely slow /
 *  huge library can only ever land PARTIALLY warm, never hang the splash.
 *
 *  An earlier comment read "MainActivity's input backstop is 9s — this cap must
 *  stay under it." That is the wrong comparison and it shipped a real defect. The cap does not start at
 *  zero: it starts AFTER the 3.2s beat, so the splash's true worst case is beat + cap, and the backstop
 *  must exceed THAT. 8s alone is under 9s; 3.2 + 8 = 11.2s is not, so the input gate opened up to 2.2s
 *  before the splash could come down. The budget is now expressed once, as [WelcomeHoldMaxMs], and
 *  MainActivity derives its backstop from it instead of duplicating a number that can drift again. */
internal const val WelcomeBackReadyCapMs = 8_000L

/**
 * The ONE absolute deadline for the welcome hold, measured from the moment the beat starts.
 *
 * The beat is a floor and the ready-linger a ceiling **on the same clock**, rather than two timers that add.
 * Everything that must outlive the splash — above all MainActivity's input-gate backstop — derives from this
 * single value, so the invariant "the gate never opens under the splash" cannot be broken by editing one
 * constant. `WelcomeHoldContractTest` asserts that relationship.
 */
internal const val WelcomeWarmDeadlineMs = WelcomeBackColdStartMs + WelcomeBackReadyCapMs
internal const val WelcomeHoldMaxMs = WelcomeWarmDeadlineMs + MimirMotion.OverlayMs

/**
 * PROTECTED LORE — verbatim from shipping app.js:3248 (EOK_QUOTES, locked 2026-06-11) and app.js:3265
 * (2026-07-02). English lines render on the splash; the
 * Spanish originals stay with the shipping source. Máni + Yggdrasil carry two lines from Sparda's own
 * poem "El ave que no puede volar". Never centralize into MimirCopy, never tidy (MimirCopy.kt:13).
 */
private val EokQuotes = listOf(
    "and still, within him, a spark of hope was burning",
    "may your soul find the light it lost, and may mine bear the weight of its return",
    "to lose something, it must first have meaning",
)

private val ThemeWelcomeQuotes = mapOf(
    MimirThemeId.FREYR to "to lose something, it must first have meaning",
    MimirThemeId.SURTR to "Rise. Walk. Fight. Hold to hope.",
    MimirThemeId.MANI to "what is this world but a wandering dove flying in the wrong direction?",
    MimirThemeId.HEL to "life slips away each second; regret can last an eternity",
    MimirThemeId.NIFLHEIM to "the closer you draw to the truth, the more your conviction is tried",
    MimirThemeId.YGGDRASIL to "what's so wrong with feeling the earth beneath your feet?",
    MimirThemeId.FOLKVANGR to "may your soul find the light it lost, and may mine bear the weight of its return",
    MimirThemeId.BALDR to "he returns not as a savior, but to restore the broken balance",
)

/** Default-theme rotation state — per-process, never persisted (shipping eokQuoteIndex, app.js:3283). */
private var eokQuoteIndex = 0

/**
 * The 8 named themes each pin ONE fixed line; the Default theme rotates the classic three, one step
 * per resume (shipping activeWelcomeQuote + the rotation at app.js:12992-12996).
 */
internal fun welcomeQuoteFor(themeId: MimirThemeId): String =
    ThemeWelcomeQuotes[themeId] ?: EokQuotes[eokQuoteIndex % EokQuotes.size].also {
        eokQuoteIndex = (eokQuoteIndex + 1) % EokQuotes.size
    }

/**
 * Wraps the app root and holds [WelcomeBackSplash] above it for the 3.2s beat. Mounted at MainActivity.setContent — NOT
 * inside MimirApp.kt (the ART-verifier size edge), so it binds its own
 * [MimirTheme]. Plain `remember`, NOT rememberSaveable: a process-death relaunch must replay the beat — masking that
 * reload is the lane's whole job.
 *
 * **Splash first (O11b, ).** On the splash path
 * ([returningUser]) Main's first frame composes ONLY the splash, and [content] mounts under it once that frame has been
 * drawn and the system can have been told so ([SplashFirstDrawHandshake], SplashFirstContentMount.kt). `Displayed` no
 * longer waits on the app's first composition; the shelf still composes and warms behind the mask, one handshake later.
 * This reverses the earlier "composes [content] from the first frame" on that path only. A first run's cold start
 * ([firstRunColdStart], cold-start policy 2026-09-29) takes the same handshake with no splash: its first
 * frame is empty, so the window is drawn and focused before the app's ~1.7 s first composition. A later Activity in
 * that process, a same-process re-create (returningUser is false for both) and a window with no frame-commit
 * handshake (software rendering) still compose [content] with the first frame. The beat, the R01 ledger linger, its cap
 * and the [WelcomeHold] release are unchanged and keep their clock: the splash layer's own start.
 */
@Composable
fun MimirColdStartWelcome(
    returningUser: Boolean,
    firstRunColdStart: Boolean = false,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val contentMountGate = remember(view) { splashContentMountGateFor(view) }
    MimirColdStartWelcome(
        returningUser = returningUser,
        contentMountGate = contentMountGate,
        firstRunColdStart = firstRunColdStart,
        content = content,
    )
}

/**
 * Claimed by the first MainActivity of a first-run process ([MimirColdStartWelcome]'s `firstRunColdStart`). Process-
 * wide on purpose: only that cold start defers its mount; every later Activity in the process keeps today's mount.
 */
internal val FirstRunColdStartClaim = java.util.concurrent.atomic.AtomicBoolean(false)

/** [MimirColdStartWelcome] with its mount gate injected. A `null` gate composes [content] with the first frame. */
@Composable
internal fun MimirColdStartWelcome(
    returningUser: Boolean,
    contentMountGate: SplashContentMountGate?,
    firstRunColdStart: Boolean = false,
    content: @Composable () -> Unit,
) {
    var hold by remember { mutableStateOf(returningUser) }
    // O11b splash first: the splash path waits, and so does a first run's cold start (no splash).
    // Every other start mounts the content with this first composition.
    var contentMounted by remember { mutableStateOf(!(returningUser || firstRunColdStart) || contentMountGate == null) }
    // Nothing is composed to own OS Back until the content mounts; a first run must not fall out of the gate.
    if (!contentMounted && !returningUser) androidx.activity.compose.BackHandler(enabled = true) { }
    // O11b cold-start timeline (behaviour-neutral, MimirColdStartTrace).
    val view = LocalView.current
    remember {
        MimirColdStartTrace.mark("welcome composed", "returningUser=$returningUser firstRunColdStart=$firstRunColdStart")
        if (contentMounted) MimirColdStartTrace.mark("content mount requested", "reason=first-composition")
        true
    }
    DisposableEffect(view) {
        MimirColdStartTrace.markNextFrameCommit(view, "welcome first frame committed", "splash=$returningUser")
        onDispose { }
    }
    if (!contentMounted && contentMountGate != null) {
        // Armed while the first composition is applied, before that traversal draws, so the handshake's first
        // frame-commit callback belongs to the splash's first frame. Leaves composition with the mount.
        DisposableEffect(contentMountGate) {
            val cancel = contentMountGate.arm { reason ->
                MimirColdStartTrace.mark("content mount requested", "reason=${reason.logName}")
                contentMounted = true
            }
            onDispose { cancel() }
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (contentMounted) MimirColdStartContentProbe(content)
        AnimatedVisibility(
            visible = hold,
            enter = EnterTransition.None,
            exit = fadeOut(tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)),
        ) {
            LaunchedEffect(Unit) {
                // PERFE-01: one absolute budget, measured from here. The beat and the linger are a floor and
                // a ceiling on this same clock — never two timers that add — so the hold can never exceed
                // [WelcomeHoldMaxMs] even if the beat itself overruns under load.
                val startedAtMs = System.nanoTime() / 1_000_000L
                MimirColdStartTrace.mark("splash beat start")
                delay(WelcomeBackColdStartMs) // 3.2s MINIMUM beat
                val settledAtBeatEnd = WarmupLedger.settled.value // read for the timeline only
                // R01 "Land Warm" : then linger until the WARM LEDGER settles (capped) —
                // not the old platforms-query proxy — so the cut lands on already-decoded shelves. The ledger
                // itself awaits LibraryReadyGate inside its rows task, so this strictly supersedes the proxy.
                val remainingMs = WelcomeWarmDeadlineMs - (System.nanoTime() / 1_000_000L - startedAtMs)
                val settledDuringLinger = if (remainingMs > 0L) {
                    withTimeoutOrNull(remainingMs) { WarmupLedger.settled.first { it } } != null
                } else {
                    false
                }
                MimirColdStartTrace.mark(
                    "splash dissolve",
                    "reason=${welcomeDissolveReason(settledAtBeatEnd, settledDuringLinger)} " +
                        "sinceBeatStartMs=${System.nanoTime() / 1_000_000L - startedAtMs}",
                )
                hold = false // dissolve once the shelf underneath is painted AND warm
            }
            // The input gate remains armed through AnimatedVisibility's outgoing pixels and is released
            // only when that layer is actually disposed.
            DisposableEffect(Unit) {
                onDispose {
                    WelcomeHold.active = false
                    MimirColdStartTrace.mark("splash layer disposed", "welcomeHold=false")
                }
            }
            val themeId by ThemeStore.themeId.collectAsState()
            MimirTheme(themeId) { WelcomeBackSplash() }
        }
    }
}

/**
 * What ended the welcome hold, for the cold-start timeline only: `beat` when the ledger had already settled as the 3.2 s
 * beat ended, `ledger` when the hold lingered until it settled, `cap` when [WelcomeWarmDeadlineMs] ran out first.
 */
internal fun welcomeDissolveReason(settledAtBeatEnd: Boolean, settledDuringLinger: Boolean): String = when {
    settledAtBeatEnd -> "beat"
    settledDuringLinger -> "ledger"
    else -> "cap"
}

/**
 * The splash surface — shipping `#authView` in its welcome-back state: the dark auth atmosphere, the
 * glass card with steampunk corner brackets, the ᛗ lockup, the posture chip, and the three-line
 * greeting (name · themed quote · "Refreshing your library…").
 */
@Composable
private fun WelcomeBackSplash() {
    // The input-gate backstop's handover: while this surface is composed, its fade-out included, MainActivity's
    // backstop may not open the gate ([WelcomeHold.releaseFromBackstop]); this layer's own dispose opens it.
    DisposableEffect(Unit) {
        WelcomeHold.onSplashSurfaceComposed()
        onDispose { WelcomeHold.onSplashSurfaceDisposed() }
    }
    val palette = LocalMimirPalette.current
    val name by ProfileNameStore.displayName.collectAsState()
    val quote = remember { welcomeQuoteFor(palette.id) }
    val cardShape = RoundedCornerShape(20.dp)
    // HP-7: when Folkvangr and Baldr were LIGHT themes, the hardcoded navy wash + dark glass card was a
    // jarring gray-black takeover of them — so the splash went palette-driven, and dark themes keep the
    // shipping .eok-auth navy verbatim (parity). Both were later re-authored as dark METALLIC, so no
    // palette sets a bright field today and the branch below is currently inert. Kept, not deleted:
    // `usesBrightField` is still an authored posture a future palette could turn on.
    val cardFill = if (palette.usesBrightField) {
        listOf(palette.surfaceRaised.copy(alpha = 0.95f), palette.surfaceBase.copy(alpha = 0.98f))
    } else {
        listOf(Color(0xA8161A30), Color(0xC7080A16))
    }
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawWelcomeAtmosphere(palette) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 480.dp)
                .clip(cardShape)
                .background(Brush.verticalGradient(cardFill))
                .border(0.5.dp, palette.accent.copy(alpha = 0.28f), cardShape)
                .drawBehind { drawCornerBrackets(palette.accent.copy(alpha = 0.42f)) }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MimirMannazBadge(palette = palette, size = 32.dp)
                Column {
                    Text(
                        text = "M I M I R",
                        color = palette.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.2.em,
                    )
                    Text(
                        text = MimirRuneEyebrow,
                        style = MimirType.RunicEyebrow,
                        color = palette.textMuted,
                        fontSize = 10.sp,
                        letterSpacing = 0.2.em,
                    )
                }
            }
            // Shipping repurposes the profile chip here: the headline already greets, so the chip
            // states posture (app.js:12988).
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(palette.accent.copy(alpha = 0.08f))
                    .border(1.dp, palette.accent.copy(alpha = 0.3f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                MimirChromeText(
                    text = WelcomeBackPostureBadgeCopy,
                    style = MimirType.MicroStatus,
                    color = palette.accent,
                )
            }
            Column(
                Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MimirIntentText(
                    intent = if (name.isBlank()) {
                        MimirTextIntent.StaticChrome("Welcome back.")
                    } else {
                        MimirTextIntent.Mixed(
                            listOf(
                                MimirTextFragment.Chrome("Welcome back, "),
                                MimirTextFragment.Authored(name),
                                MimirTextFragment.Chrome("."),
                            ),
                        )
                    },
                    color = palette.textPrimary,
                    style = MimirType.TitleCaps,
                )
                // Migrated off a raw 15sp onto the capped scale. 15 was never a step, and with the
                // greeting ruled down to Body it would have left the quote LARGER than the greeting it
                // belongs to. Level with its greeting is the title/subtext scale rule: same size,
                // told apart by case, colour and italic rather than scale.
                Text(
                    text = "“ $quote ”",
                    color = palette.textSecondary,
                    fontSize = MimirTypeStep.Body,
                    fontStyle = FontStyle.Italic,
                    lineHeight = 21.sp,
                )
                // The live status line names the warm task (rows → covers → shelves), making the hold explain its work.
                val stage by WarmupLedger.stage.collectAsState()
                Text(
                    text = stage,
                    color = palette.textMuted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/**
 * The shipping `.eok-auth` atmosphere: the deep-navy 165° base wash (dark themes — shipping parity),
 * the palette's own signature wash on any bright-field palette (HP-7 — none today, see below), the
 * themed cool haze top-right, and the
 * warm gold corner + floor glows (gold on every theme in shipping — the house ember).
 */
private fun DrawScope.drawWelcomeAtmosphere(palette: MimirThemePalette) {
    val accent = palette.accent
    val base = if (palette.usesBrightField) {
        listOf(palette.signatureTop, palette.surfaceBase, palette.signatureBottom)
    } else {
        listOf(Color(0xFF05060F), Color(0xFF080A16), Color(0xFF0A0610))
    }
    drawRect(
        Brush.linearGradient(
            colors = base,
            start = Offset(size.width * 0.2f, 0f),
            end = Offset(size.width * 0.8f, size.height),
        ),
    )
    val hazeCenter = Offset(size.width * 0.78f, -size.height * 0.12f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.14f), Color.Transparent),
            center = hazeCenter,
            radius = size.width * 0.45f,
        ),
        radius = size.width * 0.45f,
        center = hazeCenter,
    )
    val gold = Color(0xFFD4AF69)
    val cornerCenter = Offset(size.width * 0.08f, size.height * 1.14f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(gold.copy(alpha = 0.12f), Color.Transparent),
            center = cornerCenter,
            radius = size.width * 0.4f,
        ),
        radius = size.width * 0.4f,
        center = cornerCenter,
    )
    val floorCenter = Offset(size.width * 0.5f, size.height * 1.2f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(gold.copy(alpha = 0.06f), Color.Transparent),
            center = floorCenter,
            radius = size.width * 0.55f,
        ),
        radius = size.width * 0.55f,
        center = floorCenter,
    )
}

/** The card's steampunk L-corners (shipping `.eok-auth .auth-card::before/::after`): top-left + bottom-right. */
private fun DrawScope.drawCornerBrackets(color: Color) {
    val inset = 9.dp.toPx()
    val arm = 16.dp.toPx()
    val stroke = 1.dp.toPx()
    drawLine(color, Offset(inset, inset), Offset(inset + arm, inset), stroke)
    drawLine(color, Offset(inset, inset), Offset(inset, inset + arm), stroke)
    val br = Offset(size.width - inset, size.height - inset)
    drawLine(color, br, Offset(br.x - arm, br.y), stroke)
    drawLine(color, br, Offset(br.x, br.y - arm), stroke)
}
