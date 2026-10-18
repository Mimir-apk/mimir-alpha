package com.gruning.mimir.rewrite

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import com.gruning.mimir.rewrite.core.ui.MimirMessage
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.tweaker.TweakerFanControlReadout
import com.gruning.mimir.rewrite.core.tweaker.TweakerFanControlStrategy
import com.gruning.mimir.rewrite.core.tweaker.TweakerFanMode
import com.gruning.mimir.rewrite.core.tweaker.TweakerManualFanCapability
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityAvailability
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Declaration site 1 of 5: the render, and its POSITION relative to the focus order.
 *
 * A row nested in the
 * wrong `let {}` compiles clean, passes every test, and is either drawn but unreachable or reachable
 * but never drawn. `brightnessControls` and the render are two independent lists that no compiler
 * relates, and no source assertion can relate either, because the render is a tree and the focus
 * order is a sequence.
 *
 * The frame-cap row shipped exactly this way (`6afcf227`): declared in the focus order unconditionally
 * but drawn inside `tweakerManualFan?.let {}`, so on a device without a manual fan it was focusable
 * and invisible. That is what this gate exists to catch, and it needed a Compose harness to do it.
 *
 * What is asserted: every control the focus order admits is actually rendered, and in the same
 * relative ORDER, top to bottom. The rail reveals by index, so a row listed early and drawn late
 * swallows the press meant for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TweakerRailRenderOrderTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The rows whose position this gate can see. A control appears here only if its render carries a
     * stable `contentDescription`; anything without one is invisible to semantics and therefore
     * outside what this test can claim. Keep the map honest rather than complete — a wrong entry is
     * worse than a missing one.
     */
    private val describedRows = mapOf(
        BrightnessControlKey.TWEAKER_PROFILE to "Tweaker profile",
        // Reachable since UtilityChoiceRow names itself; every row built on it is now locatable.
        BrightnessControlKey.PERFORMANCE_OVERLAY to "Performance overlay",
        BrightnessControlKey.TWEAKER_FAN_MODE to "Fan mode",
        BrightnessControlKey.TWEAKER_FAN_CURVE to "Fan response",
        BrightnessControlKey.TWEAKER_USAGE_ACCESS to "Tweaker usage access",
    )

    /**
     * MANUAL, not AUTO: the fan curve row renders only in MANUAL, and `brightnessControls` admits
     * TWEAKER_FAN_CURVE on the same condition. AUTO would leave both out of the comparison and the
     * gate would pass while covering one row fewer, which is the quiet way a gate stops earning its
     * keep.
     */
    /**
     * A fan readout node says "Fan 25%" or "Fan 25% · GPU 62°C".
     * A bare "Fan" substring would also match the Fan mode and Fan response rows, so the readout's
     * whole shape is matched instead.
     */
    private val fanReadoutNode = androidx.compose.ui.test.SemanticsMatcher("is a fan readout") { node ->
        val key = androidx.compose.ui.semantics.SemanticsProperties.ContentDescription
        val descriptions = if (key in node.config) node.config[key] else emptyList()
        descriptions.any { Regex("Fan \\d+%( · GPU \\d+°C)?").matches(it) }
    }

    private val manualFan = TweakerManualFanCapability(
        modes = setOf(TweakerFanMode.AUTO, TweakerFanMode.MANUAL),
        curveBiasRange = -3..3,
        curveBiasStep = 1,
        curveSensorType = "package",
        currentMode = TweakerFanMode.MANUAL,
        currentCurveBias = 0,
        ownerRequestId = "test-owner",
        generation = 1L,
        transactionId = "test-transaction",
        profileId = "balanced",
    )

    @Test
    fun `every described control in the focus order renders, in the same order`() {
        val state = tweakerPanelState()

        compose.setContent {
            MimirTheme {
                MimirBrightnessOverlay(
                    state = state,
                    focusedControl = BrightnessControlKey.TWEAKER_PROFILE,
                    onSetFraction = { _, _ -> },
                    onSetThemeAwareJoystickLight = {},
                    onRequestWriteAccess = {},
                    onRequestPrivilegedAccess = {},
                    onBack = {},
                    onClose = {},
                    surface = UtilitySurface.TWEAKER,
                    tweakerRow = readyTweakerRow(),
                    tweakerManualFan = manualFan,
                    tweakerUsageAccessState = TweakerUsageAccessState.SET_UP,
                )
            }
        }

        val declared = brightnessControls(
            state,
            surface = UtilitySurface.TWEAKER,
            tweakerAvailability = TweakerUtilityAvailability.READY,
            tweakerManualFan = manualFan,
            tweakerUsageAccessState = TweakerUsageAccessState.SET_UP,
        ).filter { it in describedRows }

        val renderedTopToBottom = declared
            .mapNotNull { key ->
                val description = describedRows.getValue(key)
                val nodes = compose.onAllNodesWithContentDescription(description)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false)
                nodes.firstOrNull()?.let { key to it.positionInRoot.y }
            }
            .sortedBy { it.second }
            .map { it.first }

        // Coverage is asserted, not assumed. Every row this gate claims to watch must actually be
        // admitted by THIS fixture -- otherwise a capability change silently drops a row from
        // `declared`, it is never looked for, and the gate keeps passing while covering less.
        assertEquals(
            "the fixture must admit every row describedRows claims to cover",
            describedRows.keys,
            declared.toSet(),
        )
        assertEquals(
            "every control the focus order admits must render, and in the same order — the rail " +
                "reveals by index, so a row listed early and drawn late swallows its own press",
            declared,
            renderedTopToBottom,
        )
    }

    /**
     * The profile row composes BOTH its subtext and the fan readout when a
     * readout exists, rather than the readout replacing the subtext. This does not assert the exact
     * subtext string, which also depends on `TweakerBridge.pairState()` (live singleton state this
     * fixture does not control) -- it asserts the fan line's own presence/absence, which is what the
     * row-composition change actually governs.
     */
    @Test
    fun `profile row appends the fan readout beneath the subtext when a readout exists`() {
        val state = tweakerPanelState()

        compose.setContent {
            MimirTheme {
                MimirBrightnessOverlay(
                    state = state,
                    focusedControl = BrightnessControlKey.TWEAKER_PROFILE,
                    onSetFraction = { _, _ -> },
                    onSetThemeAwareJoystickLight = {},
                    onRequestWriteAccess = {},
                    onRequestPrivilegedAccess = {},
                    onBack = {},
                    onClose = {},
                    surface = UtilitySurface.TWEAKER,
                    tweakerRow = readyTweakerRow().copy(fanControl = fanReadout),
                )
            }
        }

        assertEquals(
            "the fan readout must render as its own node beneath the profile subtext",
            1,
            compose.onAllNodesWithContentDescription("Fan 25%")
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .size,
        )
        assertEquals(
            "the fan-readout matcher the absence test below relies on must see this readout",
            1,
            compose.onAllNodes(fanReadoutNode)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .size,
        )
        assertEquals(
            "the row itself stays one row, not split into two",
            1,
            compose.onAllNodesWithContentDescription("Tweaker profile")
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .size,
        )
    }

    @Test
    fun `profile row shows no fan readout line when the row carries none`() {
        val state = tweakerPanelState()

        compose.setContent {
            MimirTheme {
                MimirBrightnessOverlay(
                    state = state,
                    focusedControl = BrightnessControlKey.TWEAKER_PROFILE,
                    onSetFraction = { _, _ -> },
                    onSetThemeAwareJoystickLight = {},
                    onRequestWriteAccess = {},
                    onRequestPrivilegedAccess = {},
                    onBack = {},
                    onClose = {},
                    surface = UtilitySurface.TWEAKER,
                    tweakerRow = readyTweakerRow(),
                )
            }
        }

        assertEquals(
            "no fan line should render when the row carries no fan readout",
            0,
            compose.onAllNodes(fanReadoutNode)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .size,
        )
    }

    /**
     * Fixed-footer hint placement: the TWEAKER hint/toast sits outside the scrollable
     * rail entirely and into the FIXED footer beside Close (see
     * `close does not move when a Tweaker message arrives or expires` below for the assertion that
     * now carries the real weight). This test is kept rather than deleted because the property it
     * names -- the profile row never moves when a message arrives -- still matters; it is simply
     * trivially true now that `tweakerMessage` has no rendering path inside the rail at all.
     */
    @Test
    fun `toast overlay does not move the profile row when a message arrives`() {
        val state = tweakerPanelState()
        val messageState = mutableStateOf<MimirMessage?>(null)

        compose.setContent {
            MimirTheme {
                MimirBrightnessOverlay(
                    state = state,
                    focusedControl = BrightnessControlKey.TWEAKER_PROFILE,
                    onSetFraction = { _, _ -> },
                    onSetThemeAwareJoystickLight = {},
                    onRequestWriteAccess = {},
                    onRequestPrivilegedAccess = {},
                    onBack = {},
                    onClose = {},
                    surface = UtilitySurface.TWEAKER,
                    tweakerRow = readyTweakerRow(),
                    tweakerMessage = messageState.value,
                )
            }
        }

        fun profileRowY(): Float =
            compose.onAllNodesWithContentDescription("Tweaker profile")
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .first()
                .positionInRoot.y

        val idleY = profileRowY()

        messageState.value = MimirMessage(
            eventId = 1L,
            text = "Profile applied",
            messageClass = MimirMessageClass.Confirmation,
        )
        compose.waitForIdle()

        val activeY = profileRowY()

        assertEquals(
            "an active toast overlays the profile row -- it must not push it down (or up)",
            idleY,
            activeY,
            0.01f,
        )
    }

    /**
     * Fixed-footer hint placement: the Tweaker hint/toast renders in the FIXED footer,
     * in the same weighted slot as UTILITY's main-display-access message, mounted unconditionally so
     * it claims a constant share of the Row's width whether idle or active. Close is documented
     * ("can never scroll away or collide with a control") as fixed and always reachable, so an
     * arriving or expiring message in that slot must not move it -- neither when the message appears
     * nor when it clears back to idle.
     */
    @Test
    fun `close does not move when a Tweaker message arrives or expires`() {
        val state = tweakerPanelState()
        val messageState = mutableStateOf<MimirMessage?>(null)

        compose.setContent {
            MimirTheme {
                MimirBrightnessOverlay(
                    state = state,
                    focusedControl = BrightnessControlKey.TWEAKER_PROFILE,
                    onSetFraction = { _, _ -> },
                    onSetThemeAwareJoystickLight = {},
                    onRequestWriteAccess = {},
                    onRequestPrivilegedAccess = {},
                    onBack = {},
                    onClose = {},
                    surface = UtilitySurface.TWEAKER,
                    tweakerRow = readyTweakerRow(),
                    tweakerMessage = messageState.value,
                )
            }
        }

        fun closePosition(): Pair<Float, Float> {
            val node = compose.onAllNodesWithContentDescription("Close")
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .first()
            return node.positionInRoot.x to node.positionInRoot.y
        }

        val idlePosition = closePosition()

        messageState.value = MimirMessage(
            eventId = 1L,
            text = "Profile applied",
            messageClass = MimirMessageClass.Confirmation,
        )
        compose.waitForIdle()

        val activePosition = closePosition()

        assertEquals(
            "Close's x position must not shift when a footer message appears",
            idlePosition.first,
            activePosition.first,
            0.01f,
        )
        assertEquals(
            "Close's y position must not shift when a footer message appears",
            idlePosition.second,
            activePosition.second,
            0.01f,
        )

        messageState.value = null
        compose.waitForIdle()

        val expiredPosition = closePosition()

        assertEquals(
            "Close's x position must return to idle once the message clears",
            idlePosition.first,
            expiredPosition.first,
            0.01f,
        )
        assertEquals(
            "Close's y position must return to idle once the message clears",
            idlePosition.second,
            expiredPosition.second,
            0.01f,
        )
    }

    private val fanReadout = TweakerFanControlReadout(
        profileId = "balanced",
        strategy = TweakerFanControlStrategy.HOLD_TARGET,
        targetTempC = 65,
        calibrationPowerReferenceMw = 11_250,
        dutyPercent = 25,
        rpm = 0,
    )

    private fun readyTweakerRow() =
        DefaultTweakerUtilityRow.copy(availability = TweakerUtilityAvailability.READY)

    private fun tweakerPanelState(): BrightnessState = BrightnessState(
        capabilities = BrightnessCapabilities(
            canWriteSystemSettings = true,
            mainSystemBrightnessActive = true,
            secondaryWindowAvailable = false,
            secondarySupport = SecondaryBrightnessSupport.UNAVAILABLE,
            joystickLightSupported = false,
            joystickLightColorSupported = false,
            mimirHapticsSupported = false,
            mimirHapticAmplitudeControl = false,
            mediaVolumeSupported = false,
            mediaVolumeFixed = false,
            mainWriteAccessUseful = true,
            privilegedUtilityPosture = PrivilegedUtilityPosture.Unsupported,
            navSoundSupported = false,
            ambientSoundSupported = false,
        ),
        primaryFraction = 0.5f,
        primaryWindowFallback = null,
        secondaryFraction = null,
        secondaryWindowFallback = null,
        joystickLightFraction = null,
        joystickColorFraction = null,
        joystickColorArgb = null,
        themeAwareJoystickLight = false,
        mimirHapticFraction = null,
        mediaVolumeFraction = null,
        themeAwareJoystickLightRequested = false,
        themeAwareJoystickLightRestorePending = false,
        ambientSoundFraction = null,
    )
}
