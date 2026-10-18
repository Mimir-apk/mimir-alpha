package com.gruning.mimir.rewrite

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerShortcutMappingTest {
    @Test
    fun `optional shortcut surface exposes the three global Mimir tools`() {
        assertEquals(
            listOf(
                ControllerShortcutAction.QUICK_LAUNCH,
                ControllerShortcutAction.UTILITY,
                ControllerShortcutAction.TWEAKER,
            ),
            ControllerShortcutAction.entries,
        )
        assertEquals(
            listOf("Quick Launch", "Utility", "Tweaker"),
            ControllerShortcutSnapshot().toSettingsRows().map { it.title },
        )
    }

    @Test
    fun `zero key and scan cannot become a wildcard binding`() {
        val invalid = binding(
            action = ControllerShortcutAction.UTILITY,
            keyCode = KeyEvent.KEYCODE_UNKNOWN,
            scanCode = 0,
        )

        assertFalse(isDispatchableControllerShortcutBinding(invalid))
        assertNull(ControllerShortcutBindings(mapOf(invalid.action to invalid)).resolve(key(invalid)))
    }

    @Test
    fun `ordinary controller and Android system buttons remain reserved`() {
        listOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_VOLUME_UP,
        ).forEach { keyCode ->
            assertTrue("keyCode=$keyCode", isReservedControllerShortcutKey(keyCode))
        }
        assertFalse(isReservedControllerShortcutKey(KeyEvent.KEYCODE_F9))
    }

    @Test
    fun `vendor macro buttons remain available to the isolated shortcut harness`() {
        listOf(KeyEvent.KEYCODE_BUTTON_1, KeyEvent.KEYCODE_BUTTON_11, KeyEvent.KEYCODE_BUTTON_16)
            .forEach { keyCode ->
                assertFalse("keyCode=$keyCode", isReservedControllerShortcutKey(keyCode))
                assertTrue(
                    isDispatchableControllerShortcutBinding(
                        binding(ControllerShortcutAction.UTILITY, keyCode = keyCode),
                    ),
                )
            }
    }

    @Test
    fun `latest assignment owns both the action and physical key atomically`() {
        val first = binding(ControllerShortcutAction.QUICK_LAUNCH, keyCode = KeyEvent.KEYCODE_F9)
        val sameKeyNewAction = first.copy(action = ControllerShortcutAction.UTILITY)
        val replacement = binding(
            ControllerShortcutAction.UTILITY,
            keyCode = KeyEvent.KEYCODE_F10,
            scanCode = 91,
        )

        val moved = ControllerShortcutBindings().assign(first).assign(sameKeyNewAction)
        assertNull(moved.byAction[ControllerShortcutAction.QUICK_LAUNCH])
        assertEquals(sameKeyNewAction, moved.byAction[ControllerShortcutAction.UTILITY])

        val replaced = moved.assign(replacement)
        assertEquals(1, replaced.byAction.size)
        assertEquals(replacement, replaced.byAction[ControllerShortcutAction.UTILITY])
    }

    @Test
    fun `descriptor drift falls back only through exact family and key then requests re-pin`() {
        val original = binding(
            action = ControllerShortcutAction.UTILITY,
            keyCode = KeyEvent.KEYCODE_F11,
            scanCode = 92,
        )
        val drifted = key(original).copy(descriptorHash = DescriptorB)

        val resolution = ControllerShortcutBindings(mapOf(original.action to original)).resolve(drifted)

        assertEquals(ControllerShortcutAction.UTILITY, resolution?.action)
        assertEquals(DescriptorB, resolution?.rePinned?.descriptorHash)
        assertNull(
            ControllerShortcutBindings(mapOf(original.action to original)).resolve(
                drifted.copy(productId = original.productId + 1),
            ),
        )
    }

    @Test
    fun `codec is strict and reserved persisted rows are rejected at dispatch`() {
        val source = binding(
            ControllerShortcutAction.QUICK_LAUNCH,
            keyCode = KeyEvent.KEYCODE_F12,
            scanCode = 93,
        )
        val encoded = ControllerShortcutStore.encodeControllerShortcutBinding(source)
        assertEquals(
            source,
            ControllerShortcutStore.decodeControllerShortcutBinding(
                encoded,
                ControllerShortcutAction.QUICK_LAUNCH,
            ),
        )
        assertNull(
            ControllerShortcutStore.decodeControllerShortcutBinding(
                encoded.replace("v1|", "v2|"),
                ControllerShortcutAction.QUICK_LAUNCH,
            ),
        )
        assertFalse(
            isDispatchableControllerShortcutBinding(
                source.copy(keyCode = KeyEvent.KEYCODE_BUTTON_X),
            ),
        )
    }

    @Test
    fun `capture down owns its release even after capture closes`() {
        var now = 100L
        val gate = ControllerShortcutInputGate(clock = { now })
        val physical = key(
            binding(
                ControllerShortcutAction.UTILITY,
                keyCode = KeyEvent.KEYCODE_F9,
                scanCode = 90,
            ),
        )
        var captured = 0

        assertEquals(
            ControllerShortcutInputDecision.Consume,
            gate.handle(
                key = physical,
                down = true,
                repeatCount = 0,
                longPress = false,
                navigationMode = true,
                captureActive = true,
                capture = {
                    captured += 1
                    true
                },
                resolve = { null },
            ),
        )
        assertEquals(1, captured)
        assertEquals(
            ControllerShortcutInputDecision.Consume,
            gate.handle(
                key = physical,
                down = false,
                repeatCount = 0,
                longPress = false,
                navigationMode = true,
                captureActive = false,
                capture = { false },
                resolve = { null },
            ),
        )
        now += 121L
        assertEquals(
            ControllerShortcutInputDecision.Continue,
            gate.handle(
                key = physical,
                down = false,
                repeatCount = 0,
                longPress = false,
                navigationMode = true,
                captureActive = false,
                capture = { false },
                resolve = { null },
            ),
        )

        now += 2_001L
        assertEquals(
            ControllerShortcutInputDecision.Dispatch(ControllerShortcutAction.UTILITY),
            gate.handle(
                key = physical,
                down = true,
                repeatCount = 0,
                longPress = false,
                navigationMode = true,
                captureActive = false,
                capture = { false },
                resolve = { ControllerShortcutAction.UTILITY },
            ),
        )
    }

    @Test
    fun `unmappable capture events remain owned by the listening harness`() {
        var now = 10L
        val gate = ControllerShortcutInputGate(clock = { now })
        val unknown = ControllerPhysicalKey(
            deviceId = 7,
            descriptorHash = DescriptorA,
            vendorId = 1_234,
            productId = 5_678,
            keyCode = KeyEvent.KEYCODE_UNKNOWN,
            scanCode = 0,
        )
        var captureCalls = 0

        assertEquals(
            ControllerShortcutInputDecision.Consume,
            gate.handle(
                key = null,
                down = true,
                repeatCount = 0,
                longPress = false,
                navigationMode = true,
                captureActive = true,
                capture = { error("null identity must not be offered for persistence") },
                resolve = { null },
            ),
        )
        assertEquals(
            ControllerShortcutInputDecision.Consume,
            gate.handle(
                key = unknown,
                down = true,
                repeatCount = 0,
                longPress = false,
                navigationMode = true,
                captureActive = true,
                // The real store rejects this identity but reports the event consumed while closing.
                capture = {
                    captureCalls += 1
                    true
                },
                resolve = { null },
            ),
        )
        assertEquals(1, captureCalls)
        now += 20L
        assertEquals(
            ControllerShortcutInputDecision.Consume,
            gate.handle(
                key = unknown,
                down = false,
                repeatCount = 0,
                longPress = false,
                navigationMode = true,
                captureActive = false,
                capture = { false },
                resolve = { null },
            ),
        )
    }

    @Test
    fun `multi key and motion tails stay quarantined through release and neutral with a hard bound`() {
        var now = 100L
        val quarantine = ControllerShortcutCaptureQuarantine { now }
        val captured = key(binding(ControllerShortcutAction.UTILITY, KeyEvent.KEYCODE_F9, 90))
        val bundled = key(binding(ControllerShortcutAction.UTILITY, KeyEvent.KEYCODE_F10, 91))
        // Complete axis maps, as InputRouter.motionAxes always builds them. These were partial, and the
        // rest test no longer reads an axis it was never told about as a centred one, so a partial
        // "neutral" says nothing about the seven axes it omits. The barrier behaviour under test is
        // unchanged; only the sample shape now matches what the device actually sends.
        fun sample(vararg overrides: Pair<Int, Float>): Map<Int, Float> = buildMap {
            listOf(
                MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
                MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_BRAKE,
                MotionEvent.AXIS_GAS,
            ).forEach { put(it, 0f) }
            overrides.forEach { (axis, value) -> put(axis, value) }
        }
        val heldHat = listOf(sample(MotionEvent.AXIS_HAT_X to 1f))
        val neutralHat = listOf(sample())

        quarantine.arm(captured)
        assertTrue(quarantine.consumeKey(bundled, down = true))
        assertTrue(quarantine.consumeMotion(heldHat))
        now += 50L
        assertTrue(quarantine.consumeKey(captured, down = false))
        assertTrue(quarantine.consumeKey(bundled, down = false))

        // Motion still owns the barrier after every bundled key is up, until a neutral sample lands.
        now += 300L
        assertTrue(quarantine.consumeMotion(neutralHat))
        now += 121L
        assertFalse(quarantine.consumeKey(bundled, down = true))

        // A missing neutral cannot trap app input indefinitely.
        quarantine.arm(captured)
        assertTrue(quarantine.consumeMotion(heldHat))
        now += 2_001L
        assertFalse(quarantine.consumeMotion(neutralHat))
    }

    @Test
    fun `mapped macro is inert outside navigation mode`() {
        val gate = ControllerShortcutInputGate(clock = { 0L })
        val physical = key(
            binding(ControllerShortcutAction.QUICK_LAUNCH, KeyEvent.KEYCODE_F10, 91),
        )

        assertEquals(
            ControllerShortcutInputDecision.Continue,
            gate.handle(
                key = physical,
                down = true,
                repeatCount = 0,
                longPress = false,
                navigationMode = false,
                captureActive = false,
                capture = { false },
                resolve = { ControllerShortcutAction.QUICK_LAUNCH },
            ),
        )
    }

    private fun binding(
        action: ControllerShortcutAction,
        keyCode: Int,
        scanCode: Int = 90,
    ) = ControllerShortcutBinding(
        descriptorHash = DescriptorA,
        vendorId = 1_234,
        productId = 5_678,
        keyCode = keyCode,
        scanCode = scanCode,
        action = action,
    )

    private fun key(binding: ControllerShortcutBinding) = ControllerPhysicalKey(
        deviceId = 7,
        descriptorHash = binding.descriptorHash,
        vendorId = binding.vendorId,
        productId = binding.productId,
        keyCode = binding.keyCode,
        scanCode = binding.scanCode,
    )

    private companion object {
        const val DescriptorA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DescriptorB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
