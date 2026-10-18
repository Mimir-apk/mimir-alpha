package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Permission-card handovers briefly clipped the footer focus ring. Seven asks across eight screens
 * ([onboardingAccessScreens]), which is exactly the set this measures.
 *
 * ## What is measured, and why a paused clock is the only instrument that can see it
 *
 * [OnboardingScreenFitTest] already proves every ACCESS screen FITS its viewport at rest, and it is right:
 * the defect is not a resting one. It lives entirely inside the [MimirMotion.OverlayMs] screen handover,
 * and a settled bounds walk cannot see a frame that is gone before it looks.
 *
 * The geometry, stated before it is measured so the numbers can refute it: [OnboardingScaffold]'s STANDARD
 * column is `verticalArrangement = Arrangement.Center`, and the last thing in it is the screen-handover
 * `AnimatedContent`, whose height ANIMATES from the departing screen's to the arriving one's (the house
 * grammar's shared `SizeTransform`, see [onboardingGlide]). Its `contentAlignment` is `TopCenter`, so while
 * that box is still short the arriving screen is laid out at its own full height and placed from the box's
 * TOP — hanging out of the bottom. Meanwhile the column centres the stack on the SHORT animating height,
 * lifting it by half the difference. Net: at the first frame the arriving screen's bottom sits
 * `(arrivingHeight - departingHeight) / 2` LOWER than where it settles, and eases up over the handover.
 *
 * That delta is largest on exactly the hop the player takes seven times: a card he has just GRANTED (which
 * collapses to "Allowed" + Continue) handing over to the next card, which is not granted yet and therefore
 * shows its full beats plus two pills. So this walks the sub-sequence the way he does — grant the card,
 * then advance — and samples EVERY frame of every handover.
 *
 * Two probes, because the complaint names two things:
 *
 * - **the footer**: the lowest-sitting element of the arriving card, whatever it is (the `refusal` line, on
 *   every not-granted card). Taken as the maximum bottom over every semantics node, so nothing has to be
 *   matched by string and a layout change cannot silently move the probe off the real bottom.
 * - **the focus ring**: not in the semantics tree at all — [mimirFocusTileRing] is a `drawBehind` that
 *   paints OUTSIDE its element's bounds. Its extent is known and fixed: the faint outer halo reaches
 *   `10dp + MimirFilledControlRingGap` out plus half of its 7dp stroke = **16.5dp**, and the bright inner
 *   ring `2dp + 3dp` plus half of 3dp = **6.5dp**. So the ring is measured as the focused pill's own bounds
 *   grown by those numbers.
 *
 * `positionInRoot + size` is used rather than `boundsInRoot` deliberately: the latter is clipped to ancestor
 * bounds, and what leaves the viewport is the whole question.
 *
 * ## What it found, and what the gate is
 *
 * Pre-fix, every hop displaced the card: 11px, and 34px on the four biggest, IDENTICALLY on all three
 * probes — because the displacement is `(A_new - A_old) / 2`, a property of the two cards, not of the
 * screen. At 833x444dp that left the worst card's last line 7px from the bottom edge mid-handover against
 * 41px at rest, with the focus ring reaching 16.5dp beyond its pill. One hop was exempt and says the same
 * thing from the other side: a hop onto a shorter card arrives SHORTER than it departs, and measured 0.
 *
 * So the gate here is the **overshoot** — how much lower anything sits during the handover than after it —
 * and not the absolute overflow. Resting fit is [OnboardingScreenFitTest]'s subject and is gated there; a
 * handover that displaces nothing cannot push a card that fits off the bottom of ANY screen, including the
 * ones this tree has never measured. The absolute rest/peak numbers are printed as evidence, not asserted.
 * [OnboardingHandoverAlignment] is the fix, and the algebra is in its doc.
 *
 * ## The three probes
 *
 * Thor first: [MimirCertifiedViewports.AynThorMain], measured 2026-09-21 from
 * logcat (`densityDpi=369, displayWidth=1920, displayHeight=1080, smallestScreenWidthDp=468,
 * DisplayMetrics{density=2.30625}`, displayId=0) — 832.5 x 468.3dp, which is the Odin's full viewport to
 * the decimal, the same panel. Then the Retroid Pocket Nova, 11.7dp taller but 192.5dp narrower — and
 * narrower wraps text onto more lines, which makes a card taller and its delta bigger. Then this chassis's
 * certified POST-INSET box (833x444dp): a handover that fits the whole panel says nothing about one that
 * has to fit the box a window actually receives, and 24.3dp is most of the margin these cards have.
 *
 * Whether Mimir's own window on the Thor gets the full panel or the post-inset box is NOT established by
 * that log line — the 55px inset on it belongs to another app's window — which is why both are probed
 * rather than one being chosen.
 *
 * Robolectric's text metrics approximate a real rasterizer, so every number here is [D]. NATIVE graphics is
 * mandatory — without it text does not wrap at all and every height in this file would be fiction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingAccessGlideOverflowTest {

    @get:Rule
    val compose = createComposeRule()

    private data class Probe(val label: String, val widthDp: Float, val heightDp: Float)

    private val probes = listOf(
        // The measured device, by name rather than using Odin as its stand-in: the run-13 logcat
        // reading below is what let this row be certified, and this is the suite it was read for.
        Probe(
            "AYN Thor main (832.5x468.3dp)",
            MimirCertifiedViewports.AynThorMain.fullViewportDp!!.widthDp,
            MimirCertifiedViewports.AynThorMain.fullViewportDp.heightDp,
        ),
        Probe(
            "Retroid Pocket Nova (640x480dp)",
            MimirCertifiedViewports.RetroidPocketNova.fullViewportDp!!.widthDp,
            MimirCertifiedViewports.RetroidPocketNova.fullViewportDp.heightDp,
        ),
        // The SAME chassis as the Odin row, minus chrome: this tree's own certified post-inset box
        // (833x444dp). Probed because a handover that fits the full panel says nothing about one that has
        // to fit the box a window actually receives, and 24dp is most of the margin these cards have.
        Probe(
            "Odin/Thor post-inset (833x444dp)",
            MimirCertifiedViewports.Odin.postInsetShelfDp!!.widthDp,
            MimirCertifiedViewports.Odin.postInsetShelfDp.heightDp,
        ),
    )

    /** The faint outer halo's reach past the pill's own edge: 10dp outset + 3dp gap + half of a 7dp stroke. */
    private val ringHaloDp = 16.5f

    /** Nothing may leave the viewport by more than this — the slack [OnboardingScreenFitTest] allows. */
    private val slackPx = 2f

    private val pendingAccess = OnboardingAccessUi(
        usageAvailable = true,
        usageGranted = false,
        homeRoleAvailable = true,
        homeRoleHeld = false,
        filesGranted = false,
        overlayGranted = false,
    )

    private fun granting(
        access: OnboardingAccessUi,
        id: OnboardingAccessScreenId,
    ): OnboardingAccessUi = when (id) {
        // Not a capability — it resolves by being left behind, which is what the advance itself does.
        OnboardingAccessScreenId.ORIENTATION -> access
        OnboardingAccessScreenId.FILES -> access.copy(filesGranted = true)
        OnboardingAccessScreenId.USAGE -> access.copy(usageGranted = true)
        OnboardingAccessScreenId.OVERLAY -> access.copy(overlayGranted = true)
        OnboardingAccessScreenId.HOME_ROLE -> access.copy(homeRoleHeld = true)
    }

    private class Harness(
        val authority: DefaultFocusAuthority,
        val accessReview: OnboardingAccessReviewState,
        val focusIntent: OnboardingFocusIntentState,
    )

    private var access by mutableStateOf(pendingAccess)
    private var widthDp by mutableStateOf(0f)
    private var heightDp by mutableStateOf(0f)

    private fun setUp(): Harness {
        val harness = Harness(DefaultFocusAuthority(), OnboardingAccessReviewState(), OnboardingFocusIntentState())
        val profile = OnboardingProfileState()
        val library = OnboardingLibraryState()
        widthDp = probes.first().widthDp
        heightDp = probes.first().heightDp
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                MimirTheme {
                    Box(Modifier.requiredWidth(widthDp.dp).requiredHeight(heightDp.dp)) {
                        OnboardingFlow(
                            authority = harness.authority,
                            step = OnboardingStep.ACCESS,
                            profile = profile,
                            accessReview = harness.accessReview,
                            focusIntent = harness.focusIntent,
                            access = access,
                            library = library,
                            libraryGameCount = 0,
                            libraryPlatformCount = 0,
                            artworkAccount = null,
                            steamGridDbKeyPresent = false,
                            settlement = OnboardingSettlementUi(
                                phase = OnboardingSettlementPhase.IDENTIFYING,
                                title = "Finishing your library",
                                detail = "Mimir is finishing up.",
                            ),
                            currentThemeId = MimirThemeId.DEFAULT,
                            onAdvance = {},
                            onCommitName = {},
                            onRequestFilesAccess = {},
                            onRequestUsageAccess = {},
                            onRequestHomeRole = {},
                            onChooseFolder = {},
                            onOpenArtworkSetup = {},
                            onOpenSteamGridDbKey = {},
                            onRetrySettlement = {},
                            onContinueLocally = {},
                            onApplyTheme = {},
                        )
                    }
                }
            }
        }
        return harness
    }

    /** A plain write from the test thread is not seen by a paused-clock recomposer until something idles. */
    private fun write(block: () -> Unit) {
        Snapshot.withMutableSnapshot(block)
        compose.mainClock.advanceTimeByFrame()
    }

    private fun settle() = compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs * 8L)

    private fun SemanticsNode.words(): List<String> =
        config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun SemanticsNode.bottom(): Float = positionInRoot.y + size.height

    /**
     * The lowest-sitting INK in the tree — both cards', the departing one sitting higher so it cannot win.
     * Restricted to nodes that actually carry words: the harness's own container spans the whole viewport
     * and would otherwise win every sample and report the viewport back to itself.
     */
    private fun lowestNode(): SemanticsNode? =
        compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .filter { it.words().any { word -> word.isNotBlank() } }
            .maxByOrNull { it.bottom() }

    /**
     * The arriving card's primary (gold) action pill. A departing pill is `interactive = false`, which
     * strips its semantics outright ([OnboardingPill]), so a click action is the discriminator — and the
     * only other clickable is the "Not now" defer pill, named rather than the action labels because those
     * differ per card.
     */
    private fun actionPill(): SemanticsNode? =
        compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().firstOrNull { node ->
            node.words().none { it.trim().equals("Not now", ignoreCase = true) }
        }

    @Test
    fun `handing over between permission cards never seats one lower than where it settles`() {
        assertTrue(
            "animators must be enabled or the handover tween is 0ms and there is nothing to sample",
            ValueAnimator.areAnimatorsEnabled(),
        )
        val harness = setUp()
        val screens = onboardingAccessScreens(pendingAccess)
        val report = StringBuilder()
        val failures = mutableListOf<String>()

        for (probe in probes) {
            write {
                access = pendingAccess
                widthDp = probe.widthDp
                heightDp = probe.heightDp
                harness.accessReview.goToScreen(OnboardingAccessScreenId.ORIENTATION)
            }
            settle()

            for (index in 0 until screens.lastIndex) {
                val from = screens[index]
                val to = screens[index + 1]

                // The player's own order: grant the card he is on (it collapses to "Allowed" + Continue),
                // let that settle, then press Continue.
                write { access = granting(access, from) }
                settle()

                write {
                    onboardingAccessAdvance(
                        screens = screens,
                        current = from,
                        review = harness.accessReview,
                        focusIntent = harness.focusIntent,
                        access = access,
                        onAdvance = {},
                    )
                }

                val footerBottoms = mutableListOf<Float>()
                val pillBottoms = mutableListOf<Float>()
                var worstNote = ""
                var focusLanded = false

                // Frame 0 is the first frame after the state change — the handover's t=0 and, by the
                // geometry above, its maximum displacement. 13 frames covers the 180ms tween with margin.
                for (frame in 0..12) {
                    val lowest = lowestNode()
                    val pill = actionPill()
                    // The ARRIVING card's own control, not merely "something is focused": the departing
                    // card's Continue pill still holds focus at the first frames, and a handover that never
                    // moved focus would draw no ring at all, leaving the ring probe measuring nothing.
                    val focused = harness.authority.state.value.focused?.value
                    focusLanded = focusLanded || (
                        focused != null &&
                            focused.startsWith("onboarding/access/") &&
                            focused != "onboarding/access/continue"
                        )
                    footerBottoms += lowest?.bottom() ?: Float.NEGATIVE_INFINITY
                    pillBottoms += pill?.bottom() ?: Float.NEGATIVE_INFINITY
                    if (frame == 0) worstNote = lowest?.words()?.joinToString(" ")?.take(26).orEmpty()
                    compose.mainClock.advanceTimeByFrame()
                }
                settle()

                // Three numbers per hop, because they answer different questions. `rest` is where the
                // arriving card settles (negative = clearance below it) and belongs to
                // [OnboardingScreenFitTest]'s resting fit, not to this file. `peak` is the lowest anything
                // reaches at any frame of the handover. `overshoot` is the difference — the handover's OWN
                // contribution, and the only one of the three that is a property of the motion rather than
                // of one viewport. It is therefore the one gated here: a handover that displaces nothing
                // cannot push a card that fits off the bottom of ANY screen, including the ones this tree
                // has never measured.
                val rest = footerBottoms.last() - probe.heightDp
                val peak = footerBottoms.max() - probe.heightDp
                val overshoot = peak - rest
                val ringPeak = pillBottoms.max() + ringHaloDp - probe.heightDp
                val ringOvershoot = pillBottoms.max() - pillBottoms.last()
                val line = ("%-38s | %-32s | rest=%7.1f peak=%7.1f overshoot=%6.1f | ring peak=%7.1f " +
                    "overshoot=%5.1f | %s")
                    .format(
                        "$from -> $to", probe.label, rest, peak, overshoot, ringPeak, ringOvershoot,
                        worstNote,
                    )
                report.appendLine(line)
                if (overshoot > slackPx || ringOvershoot > slackPx) failures += line
                assertTrue(
                    "the arriving card must take focus during the handover ($from -> $to, ${probe.label})",
                    focusLanded,
                )
            }
        }

        println("=== ACCESS handover: px past the viewport bottom (negative = clearance) ===")
        println(report.toString())
        assertTrue(
            "${failures.size} handover(s) dropped the card below where it settles, so a card that fits at " +
                "rest can still be cut off while the sequence hands over:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }
}
