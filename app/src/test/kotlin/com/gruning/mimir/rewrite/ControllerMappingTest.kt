package com.gruning.mimir.rewrite

import android.view.KeyEvent
import android.view.MotionEvent
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultInputAuthority
import com.gruning.mimir.rewrite.core.input.PhysicalControl
import com.gruning.mimir.rewrite.core.input.defaultControllerKeymap
import com.gruning.mimir.rewrite.core.ui.MimirCopy
import com.gruning.mimir.rewrite.feature.settings.ControllerShortcutQuickLaunchRowId
import com.gruning.mimir.rewrite.feature.settings.ControllerShortcutResetRowId
import com.gruning.mimir.rewrite.feature.settings.ControllerShortcutUtilityRowId
import com.gruning.mimir.rewrite.feature.settings.ControllerShortcutTweakerRowId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerMappingTest {

    @Test
    fun settingsShortcutDeepLinkSelectsTheRequestedRowWithoutStartingCapture() {
        ControllerMappingStore.dismiss()
        try {
            ControllerMappingStore.open(initialFocusAction = ControllerAction.GLOBAL_UTILITY)

            val opened = ControllerMappingStore.snapshot.value
            assertTrue(opened.open)
            assertEquals(ControllerAction.GLOBAL_UTILITY, opened.initialFocusAction)
            assertNull(opened.pendingAction)
        } finally {
            ControllerMappingStore.dismiss()
        }
    }

    @Test
    fun dirtyDraftRequiresAnExplicitExitDecision() {
        val applied = defaultControllerKeymap()
        val changed = assignControllerAction(
            draft = applied,
            action = ControllerAction.TERTIARY,
            control = PhysicalControl.FACE_X,
        )

        assertFalse(ControllerMappingSnapshot(applied = applied, draft = applied).isDirty)
        assertTrue(ControllerMappingSnapshot(applied = applied, draft = changed).isDirty)
    }

    @Test
    fun backFromDirtyMappingKeepsDraftUntilUserDiscardsOrContinues() {
        ControllerMappingStore.dismiss()
        try {
            ControllerMappingStore.open()
            ControllerMappingStore.beginCapture(ControllerAction.TERTIARY)
            assertTrue(ControllerMappingStore.capture(PhysicalControl.FACE_X))

            assertFalse(ControllerMappingStore.requestDismiss())
            assertTrue(ControllerMappingStore.snapshot.value.open)
            assertTrue(ControllerMappingStore.snapshot.value.exitConfirmationVisible)

            ControllerMappingStore.continueEditing()
            assertTrue(ControllerMappingStore.snapshot.value.open)
            assertFalse(ControllerMappingStore.snapshot.value.exitConfirmationVisible)
            assertTrue(ControllerMappingStore.snapshot.value.isDirty)

            assertFalse(ControllerMappingStore.requestDismiss())
            ControllerMappingStore.dismiss()
            assertFalse(ControllerMappingStore.snapshot.value.open)
            assertFalse(ControllerMappingStore.snapshot.value.isDirty)
        } finally {
            ControllerMappingStore.dismiss()
        }
    }

    @Test
    fun conflictUnbindsDisplacedActionAndNeverSwaps() {
        val updated = assignControllerAction(
            draft = defaultControllerKeymap(),
            action = ControllerAction.TERTIARY,
            control = PhysicalControl.FACE_X,
        )

        assertEquals(ControllerAction.TERTIARY, updated[PhysicalControl.FACE_X])
        assertNull(updated[PhysicalControl.FACE_Y])
        assertTrue(updated.values.none { it == ControllerAction.SECONDARY })
    }

    @Test
    fun applyValidationRejectsMissingConfirmCancelOrAnyDirection() {
        RequiredReachabilityActions.forEach { required ->
            val hostile = defaultControllerKeymap().mapValues { (_, action) ->
                action?.takeUnless { it == required }
            }
            assertEquals(required, missingRequiredControllerAction(hostile))
        }
        assertNull(missingRequiredControllerAction(defaultControllerKeymap()))
    }

    @Test
    fun keymapCodecRoundTripsUnboundDistinctFromAbsent() {
        val map = defaultControllerKeymap().toMutableMap().apply {
            this[PhysicalControl.FACE_Y] = null
        }
        val encoded = ControllerMappingStore.encodeControllerKeymap(map)
        val decoded = ControllerMappingStore.decodeControllerKeymap(encoded)

        assertTrue(requireNotNull(decoded).containsKey(PhysicalControl.FACE_Y))
        assertNull(decoded[PhysicalControl.FACE_Y])
    }

    @Test
    fun keymapCodecRejectsUnknownEnumNames() {
        val encoded = ControllerMappingStore.encodeControllerKeymap(defaultControllerKeymap())
            .replace("FACE_Y=TERTIARY", "UNKNOWN=TERTIARY")
        assertNull(ControllerMappingStore.decodeControllerKeymap(encoded))
    }

    @Test
    fun captureConsumesMappedDownAndTrailingRelease() {
        var now = 1_000L
        val captured = mutableListOf<List<ControllerPhysicalKey>>()
        val gate = ControllerMappingInputGate { now }
        val key = ControllerPhysicalKey(
            deviceId = 7,
            descriptorHash = "a".repeat(64),
            vendorId = 1,
            productId = 2,
            keyCode = KeyEvent.KEYCODE_BUTTON_X,
            scanCode = 3,
        )

        val down = gate.handleKey(
            key = key,
            keyCode = key.keyCode,
            down = true,
            repeatCount = 0,
            longPress = false,
            captureActive = true,
            capture = { captured += it; true },
            cancel = { false },
        )
        now += 20
        val up = gate.handleKey(
            key = key,
            keyCode = key.keyCode,
            down = false,
            repeatCount = 0,
            longPress = false,
            captureActive = true,
            capture = { captured += it; true },
            cancel = { false },
        )

        assertEquals(ControllerMappingInputDecision.Consume, down)
        assertEquals(ControllerMappingInputDecision.Consume, up)
        assertEquals(listOf(listOf(key)), captured)
    }

    @Test
    fun captureCollectsAChordUntilEveryHeldKeyIsReleased() {
        val captured = mutableListOf<List<ControllerPhysicalKey>>()
        val gate = ControllerMappingInputGate { 1_000L }
        val left = rawKey(KeyEvent.KEYCODE_BUTTON_L1, scanCode = 310)
        val rear = rawKey(KeyEvent.KEYCODE_F9, scanCode = 90, deviceId = 9)

        fun send(key: ControllerPhysicalKey, down: Boolean) = gate.handleKey(
            key = key,
            keyCode = key.keyCode,
            down = down,
            repeatCount = 0,
            longPress = false,
            captureActive = true,
            capture = { captured += it; true },
            cancel = { false },
        )

        assertEquals(ControllerMappingInputDecision.Consume, send(left, true))
        assertEquals(ControllerMappingInputDecision.Consume, send(rear, true))
        assertEquals(ControllerMappingInputDecision.Consume, send(left, false))
        assertTrue(captured.isEmpty())
        assertEquals(ControllerMappingInputDecision.Consume, send(rear, false))
        assertEquals(listOf(left, rear), captured.single())
    }

    @Test
    fun rawBindingCodecAcceptsAnOdinStyleRearKeyAndChord() {
        val rear = rawKey(KeyEvent.KEYCODE_F9, scanCode = 90, deviceId = 9)
        val shoulder = rawKey(KeyEvent.KEYCODE_BUTTON_L1, scanCode = 310)
        val bindings = ControllerRawBindings()
            .assign(
                ControllerAction.GLOBAL_UTILITY,
                requireNotNull(controllerRawBinding(listOf(rear))),
            )
            .assign(
                ControllerAction.GLOBAL_QUICK_LAUNCH,
                requireNotNull(controllerRawBinding(listOf(shoulder, rear))),
            )

        val encoded = ControllerMappingStore.encodeControllerRawBindings(bindings)

        assertEquals(bindings, ControllerMappingStore.decodeControllerRawBindings(encoded))
    }

    @Test
    fun rawRuntimeDispatchesChordAndReplaysAStandardPrefixWhenReleasedAlone() {
        val left = rawKey(KeyEvent.KEYCODE_BUTTON_L1, scanCode = 310)
        val right = rawKey(KeyEvent.KEYCODE_BUTTON_R1, scanCode = 311)
        val binding = requireNotNull(controllerRawBinding(listOf(left, right)))
        val bindings = ControllerRawBindings(
            mapOf(ControllerAction.GLOBAL_UTILITY to binding),
        )
        val gate = ControllerRawInputGate()

        assertEquals(
            ControllerRawInputDecision.Consume,
            gate.handleKey(left, true, 0, false, true, bindings),
        )
        assertEquals(
            ControllerRawInputDecision.Dispatch(ControllerAction.GLOBAL_UTILITY),
            gate.handleKey(right, true, 0, false, true, bindings),
        )
        assertEquals(
            ControllerRawInputDecision.Consume,
            gate.handleKey(left, false, 0, false, true, bindings),
        )
        assertEquals(
            ControllerRawInputDecision.Consume,
            gate.handleKey(right, false, 0, false, true, bindings),
        )

        assertEquals(
            ControllerRawInputDecision.Consume,
            gate.handleKey(left, true, 0, false, true, bindings),
        )
        assertEquals(
            ControllerRawInputDecision.ReplayStandard(KeyEvent.KEYCODE_BUTTON_L1),
            gate.handleKey(left, false, 0, false, true, bindings),
        )
    }

    @Test
    fun rawRuntimeDispatchesARearMacroKeyWithoutAConsoleEnumEntry() {
        val rear = rawKey(KeyEvent.KEYCODE_UNKNOWN, scanCode = 488, deviceId = 9)
        val bindings = ControllerRawBindings(
            mapOf(
                ControllerAction.GLOBAL_QUICK_LAUNCH to
                    requireNotNull(controllerRawBinding(listOf(rear))),
            ),
        )
        val gate = ControllerRawInputGate()

        assertEquals(
            ControllerRawInputDecision.Dispatch(ControllerAction.GLOBAL_QUICK_LAUNCH),
            gate.handleKey(rear, true, 0, false, true, bindings),
        )
        assertEquals(
            ControllerRawInputDecision.Consume,
            gate.handleKey(rear, false, 0, false, true, bindings),
        )
    }

    @Test
    fun motionCaptureRecognizesHatAndTriggersWithoutDispatchingAnAction() {
        assertEquals(
            PhysicalControl.DPAD_LEFT,
            physicalControlForMotionAxes(mapOf(MotionEvent.AXIS_HAT_X to -1f)),
        )
        assertEquals(
            PhysicalControl.R2,
            physicalControlForMotionAxes(mapOf(MotionEvent.AXIS_RTRIGGER to 0.9f)),
        )
        assertEquals(
            PhysicalControl.DPAD_RIGHT,
            physicalControlForMotionAxes(
                mapOf(
                    MotionEvent.AXIS_HAT_X to 0f,
                    MotionEvent.AXIS_X to 0.9f,
                ),
            ),
        )
    }

    @Test
    fun motionCaptureQuarantinesHeldEchoUntilNeutralBeforeOverlayDispatch() {
        var captureActive = true
        val captured = mutableListOf<PhysicalControl>()
        val routed = mutableListOf<ControllerAction>()
        val router = InputRouter(interactionRoot = InteractionRoot.PRIMARY, ).apply {
            controllerMappingOverlayActive = { true }
            controllerMappingCaptureActive = { captureActive }
            captureControllerMapping = { control ->
                captured += control
                captureActive = false
                true
            }
            handler = { action -> routed += action; true }
        }
        // Complete axis maps, as InputRouter.motionAxes always builds them. These were partial, and once
        // the gate stopped reading an unmentioned axis as a centred one (absence is not release), a
        // partial "neutral" no longer said anything about the seven axes it omitted. The quarantine this
        // test is about is unchanged; only the sample shape now matches what the device actually sends.
        fun sample(vararg overrides: Pair<Int, Float>): Map<Int, Float> = buildMap {
            listOf(
                MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
                MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_BRAKE,
                MotionEvent.AXIS_GAS,
            ).forEach { put(it, 0f) }
            overrides.forEach { (axis, value) -> put(axis, value) }
        }
        val held = sample(MotionEvent.AXIS_HAT_X to -1f)
        val neutral = sample()

        assertTrue(router.consumeControllerMappingMotionSamples(listOf(held)))
        assertTrue(router.consumeControllerMappingMotionSamples(listOf(held)))
        assertTrue(router.consumeControllerMappingMotionSamples(listOf(neutral)))
        assertEquals(listOf(PhysicalControl.DPAD_LEFT), captured)
        assertTrue(routed.isEmpty())

        assertEquals(false, router.consumeControllerMappingMotionSamples(listOf(held)))
        router.onRawMotionSample(held, eventTimeMs = 2_000L)
        assertEquals(listOf(ControllerAction.NAV_LEFT), routed)
    }

    @Test
    fun hintLabelsFollowThePhysicalInputThatOwnsTheAction() {
        val remapped = assignControllerAction(
            defaultControllerKeymap(),
            ControllerAction.TERTIARY,
            PhysicalControl.FACE_X,
        )
        val labels = controllerHintLabels(remapped)

        assertEquals("X EDIT", labels.rewrite("Y EDIT"))
        assertEquals("X EDIT · B BACK", labels.rewrite("Y EDIT · B BACK"))
    }

    @Test
    fun globalDestinationsAreFirstClassMappingRowsAndRemainUnboundByDefault() {
        val rows = ControllerMappingSnapshot().toSettingsRows()

        // Sentence case, not ALL CAPS (policy 2026-08-12, Gate C audit §2.4 Finding A) — and
        // "Quick Launch"/"Utility"/"Tweaker" match the sibling Controller Shortcuts row list's own
        // wording for these same three actions (toShortcutSettingsRows, same file) rather than a fourth.
        assertEquals(
            listOf("Quick Launch", "Utility", "Tweaker", "Open settings"),
            rows.takeLast(4).map { it.title },
        )
        assertTrue(rows.takeLast(4).all { it.binding == MimirCopy.NoBindingAssigned })
        assertTrue(
            listOf(
                ControllerAction.GLOBAL_QUICK_LAUNCH,
                ControllerAction.GLOBAL_UTILITY,
                ControllerAction.GLOBAL_TWEAKER,
                ControllerAction.GLOBAL_SETTINGS,
            ).all(RemappableControllerActions::contains),
        )
    }

    /**
     * Differential regression for Gate C audit §2.4 Finding B (2026-08-12): the identical "no binding
     * assigned" state previously read "UNBOUND" in this file's main row list (toSettingsRows,
     * ControllerMapping.kt:519 at the time of the finding) and "Not set" in the sibling Controller
     * Shortcuts row list forty lines later (toShortcutSettingsRows, :560) — same file, same author, same
     * concept, two spellings, only caught by a manual audit. Both call sites now read the single
     * [MimirCopy.NoBindingAssigned] constant; this test compares the two row lists' unbound wording
     * directly so a future edit that hardcodes a literal back into either branch fails here instead of
     * waiting for the next content audit.
     */
    @Test
    fun noBindingWordingCannotDiverge_betweenMainAndShortcutRowLists() {
        val snapshot = ControllerMappingSnapshot()
        // .last() on the main list is GLOBAL_SETTINGS — like the other three GLOBAL_* rows, it carries no
        // default physical binding (unlike NAV_UP/CONFIRM/etc., which defaultControllerKeymap() does
        // bind). .first() on the shortcut list is GLOBAL_QUICK_LAUNCH, unbound for the same reason.
        val mainRowWording = snapshot.toSettingsRows().last().binding
        val shortcutRowWording = snapshot.toShortcutSettingsRows().first().value

        assertEquals(MimirCopy.NoBindingAssigned, mainRowWording)
        assertEquals(MimirCopy.NoBindingAssigned, shortcutRowWording)
        assertEquals(mainRowWording, shortcutRowWording)
    }

    @Test
    fun shortcutSettingsRows_projectTheFullMappingAuthority() {
        val snapshot = ControllerMappingSnapshot(
            applied = assignControllerAction(
                defaultControllerKeymap(),
                ControllerAction.GLOBAL_QUICK_LAUNCH,
                PhysicalControl.SELECT,
            ),
        )

        val rows = snapshot.toShortcutSettingsRows()

        assertEquals(
            ControllerAction.GLOBAL_QUICK_LAUNCH,
            controllerShortcutActionForRowId(ControllerShortcutQuickLaunchRowId),
        )
        assertEquals(
            ControllerAction.GLOBAL_UTILITY,
            controllerShortcutActionForRowId(ControllerShortcutUtilityRowId),
        )
        assertEquals(
            ControllerAction.GLOBAL_TWEAKER,
            controllerShortcutActionForRowId(ControllerShortcutTweakerRowId),
        )
        assertEquals(
            PhysicalControl.SELECT.displayLabel,
            rows.single { it.id == ControllerShortcutQuickLaunchRowId }.value,
        )
        assertEquals(
            MimirCopy.NoBindingAssigned,
            rows.single { it.id == ControllerShortcutUtilityRowId }.value,
        )
        assertEquals(
            MimirCopy.NoBindingAssigned,
            rows.single { it.id == ControllerShortcutTweakerRowId }.value,
        )
        assertTrue(rows.any { it.id == ControllerShortcutResetRowId })
    }

    @Test
    fun mappingAStandardControlToGlobalDestinationDisplacesItsOldAction() {
        val updated = assignControllerAction(
            draft = defaultControllerKeymap(),
            action = ControllerAction.GLOBAL_UTILITY,
            control = PhysicalControl.SELECT,
        )

        assertEquals(ControllerAction.GLOBAL_UTILITY, updated[PhysicalControl.SELECT])
        assertEquals(ControllerAction.GLOBAL_UTILITY, updated.values.single { it == ControllerAction.GLOBAL_UTILITY })
        assertNull(missingRequiredControllerAction(updated))
    }

    @Test
    fun reachabilityAcceptsARequiredActionOwnedByARawChord() {
        val withoutConfirm = defaultControllerKeymap().mapValues { (_, action) ->
            action?.takeUnless { it == ControllerAction.CONFIRM }
        }
        val raw = ControllerRawBindings(
            mapOf(
                ControllerAction.CONFIRM to requireNotNull(
                    controllerRawBinding(
                        listOf(
                            rawKey(KeyEvent.KEYCODE_BUTTON_L1, 310),
                            rawKey(KeyEvent.KEYCODE_BUTTON_R1, 311),
                        ),
                    ),
                ),
            ),
        )

        assertNull(missingRequiredControllerAction(withoutConfirm, raw))
    }

    @Test
    fun homeModeSwitchFollowsLogicalPageActionInsteadOfHardcodedShoulder() {
        val remapped = defaultControllerKeymap().toMutableMap().apply {
            this[PhysicalControl.R1] = null
            this[PhysicalControl.FACE_X] = ControllerAction.PAGE_RIGHT
        }
        val selections = mutableListOf<Boolean>()
        val routed = mutableListOf<ControllerAction>()
        val router = InputRouter(
            interactionRoot = InteractionRoot.PRIMARY,
            inputAuthority = DefaultInputAuthority(keymap = { remapped }),
        ).apply {
            homeViewModeSelectEnabled = { true }
            onHomeViewModeSelect = { right -> selections += right; true }
            handler = { action -> routed += action; true }
        }

        assertTrue(
            router.onRawKeyInput(
                keyCode = KeyEvent.KEYCODE_BUTTON_X,
                down = true,
                eventTimeMs = 1_000L,
            ),
        )
        assertEquals(listOf(true), selections)
        assertTrue(routed.isEmpty())
    }

    private fun rawKey(
        keyCode: Int,
        scanCode: Int,
        deviceId: Int = 7,
    ) = ControllerPhysicalKey(
        deviceId = deviceId,
        descriptorHash = if (deviceId == 7) "a".repeat(64) else "b".repeat(64),
        vendorId = if (deviceId == 7) 0x2020 else 0,
        productId = if (deviceId == 7) 0x0111 else 0,
        keyCode = keyCode,
        scanCode = scanCode,
    )
}
