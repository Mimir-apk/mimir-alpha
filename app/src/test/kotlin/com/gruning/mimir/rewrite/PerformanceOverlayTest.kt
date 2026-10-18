package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.tweaker.TweakerTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceOverlayTest {
    @Test
    fun `missing telemetry stays a dash and is never fabricated as zero`() {
        val telemetry = telemetry()

        assertEquals("—", performanceOverlayFpsLabel(PerformanceOverlayEnvironment()))
        assertEquals("—", performanceOverlayDeviceLabel(null, null))
        assertEquals("—", performanceOverlayClockLabel(null))
        assertEquals("—", performanceOverlayCpuClockLabel(emptyList()))
        assertEquals("—", performanceOverlayUsageLabel(null))
        assertEquals("—·—·—", performanceOverlayProcessorLabel(null, "—", "—"))
        assertEquals("—", performanceOverlayDrawLabel(telemetry))
    }

    @Test
    fun `processor labels keep temperature usage and clocks on one compact line`() {
        assertEquals("3.1 GHz", performanceOverlayClockLabel(3_072_000_000L))
        assertEquals("0.9 GHz", performanceOverlayClockLabel(900_000_000L))
        assertEquals(
            "2.2 GHz",
            performanceOverlayCpuClockLabel(listOf(1_996_800_000L, 2_246_400_000L)),
        )
        assertEquals(
            "56°C·42%·2.2 GHz",
            performanceOverlayProcessorLabel(56, performanceOverlayUsageLabel(42), "2.2 GHz"),
        )
        assertEquals(
            "52°C·80%·0.9 GHz",
            performanceOverlayProcessorLabel(52, performanceOverlayUsageLabel(80), "0.9 GHz"),
        )
    }

    @Test
    fun `battery metric is level only in every mode`() {
        assertEquals("73%", performanceOverlayBatteryLevelLabel(73))
        assertEquals("—", performanceOverlayBatteryLevelLabel(null))
        PerformanceOverlayMode.entries
            .filter { PerformanceOverlayCell.BATT in performanceOverlayCells(it) }
            .forEach { mode ->
                assertEquals(
                    "73%",
                    performanceOverlayCellValue(
                        PerformanceOverlayCell.BATT,
                        mode,
                        telemetry(),
                        PerformanceOverlayEnvironment(batteryPercent = 73),
                    ),
                )
                assertTrue(
                    performanceOverlayDescription(
                        telemetry(),
                        PerformanceOverlayEnvironment(batteryPercent = 73),
                        mode,
                    ).contains("Battery level 73%."),
                )
            }
    }

    @Test
    fun `GameNative renderer FPS remains independent of panel refresh rate`() {
        val aboveRefresh = PerformanceOverlayEnvironment(
            displayRefreshRateHz = 120f,
            foregroundFrameRate = 144,
        )
        assertEquals(144, performanceOverlayFpsValue(aboveRefresh))
        assertEquals(
            "144",
            performanceOverlayFpsLabel(aboveRefresh.copy(displayRefreshRateHz = 119.96f)),
        )
        assertEquals(
            "60",
            performanceOverlayFpsLabel(
                PerformanceOverlayEnvironment(displayRefreshRateHz = 120f, foregroundFrameRate = 60),
            ),
        )
        assertEquals(null, performanceOverlayFpsValue(aboveRefresh.copy(foregroundFrameRate = 1_001)))
    }

    /**
     * The FPS cell uses GameNative's renderer rate, not the panel's present rate.
     *
     * The daemon frame field is a whole-CRTC rate, so a telemetry object full of that data renders
     * "—" unless the ENVIRONMENT carries a fresh renderer sample.
     */
    @Test
    fun `the FPS cell reads the foreground renderer and never the panel's present rate`() {
        val panelSaysSixty = telemetry(frameAverageFpsMilli = 59_940)

        assertEquals(
            "—",
            performanceOverlayCellValue(
                PerformanceOverlayCell.FPS,
                PerformanceOverlayMode.CLASSIC,
                panelSaysSixty,
                PerformanceOverlayEnvironment(displayRefreshRateHz = 60f),
            ),
        )
        assertEquals(
            "19",
            performanceOverlayCellValue(
                PerformanceOverlayCell.FPS,
                PerformanceOverlayMode.CLASSIC,
                panelSaysSixty,
                PerformanceOverlayEnvironment(displayRefreshRateHz = 60f, foregroundFrameRate = 19),
            ),
        )
        // Zero is a MEASUREMENT -- Mimir on screen with nothing moving -- and is not rounded up or
        // hidden. Null is the absence, and only null renders the dash.
        assertEquals(
            "0",
            performanceOverlayCellValue(
                PerformanceOverlayCell.FPS,
                PerformanceOverlayMode.CLASSIC,
                panelSaysSixty,
                PerformanceOverlayEnvironment(displayRefreshRateHz = 60f, foregroundFrameRate = 0),
            ),
        )
    }

    /**
     * CPU and GPU percentages both represent total-device utilization.
     *
     * The temperatures and clocks either side of the percentage remain the corresponding device
     * readings, while the middle component comes from the validated utilization snapshot.
     */
    @Test
    fun `the CPU and GPU cells both use total-device utilization`() {
        val device = telemetry(cpuTempC = 63, cpuPolicyClocksHz = listOf(2_246_400_000L))
            .copy(gpuTempC = 50, gpuBusyPercent = 37, gpuClockHz = 680_000_000L, cpuBusyPercent = 6)

        assertEquals(
            "63°C·6%·2.2 GHz",
            performanceOverlayCellValue(
                PerformanceOverlayCell.CPU,
                PerformanceOverlayMode.CLASSIC,
                device,
                PerformanceOverlayEnvironment(deviceCpuPercent = 6, deviceGpuPercent = 37),
            ),
        )
        assertEquals(6, device.cpuBusyPercent)
        assertEquals(
            "50°C·37%·0.7 GHz",
            performanceOverlayCellValue(
                PerformanceOverlayCell.GPU,
                PerformanceOverlayMode.CLASSIC,
                device,
                PerformanceOverlayEnvironment(deviceCpuPercent = 6, deviceGpuPercent = 37),
            ),
        )
        assertEquals("37%", performanceOverlayUsageLabel(37))
        assertEquals("—", performanceOverlayUsageLabel(400))
        assertEquals("—", performanceOverlayUsageLabel(null))
    }

    @Test
    fun `discharge is positive watts while charging remains unavailable`() {
        assertEquals(
            "2.1 W",
            performanceOverlayDrawLabel(
                telemetry(batteryPowerMw = -2_100, batteryStatus = "Discharging"),
            ),
        )
        assertEquals(
            "—",
            performanceOverlayDrawLabel(
                telemetry(batteryPowerMw = 2_100, batteryStatus = "Charging"),
            ),
        )
        assertEquals(
            "2.1 W",
            performanceOverlayDrawLabel(
                telemetry(batteryPowerMw = -2_100),
                batteryStatus = "Discharging",
            ),
        )
        assertEquals(
            "—",
            performanceOverlayDrawLabel(
                telemetry(batteryPowerMw = -2_100, batteryStatus = "Discharging"),
                batteryStatus = "Charging",
            ),
        )
    }

    @Test
    fun `CPU overlay displays hottest core temperature and never substitutes the package mean`() {
        // the overlay's CPU source moved from the package mean to the
        // hottest core, in the same change that gave the fan-control readout a hottest-core CPU
        // reading -- see performanceOverlayCpuTemperature's KDoc. This inverts the pre-ruling pin.
        assertEquals(
            82,
            performanceOverlayCpuTemperature(telemetry(cpuTempC = 82, cpuPackageTempC = 61)),
        )
        assertEquals(null, performanceOverlayCpuTemperature(telemetry(cpuPackageTempC = 61)))
    }

    @Test
    fun `mode cycle wraps exactly and corrupt storage falls back to classic`() {
        assertEquals(PerformanceOverlayMode.CLASSIC, PerformanceOverlayMode.MINIMAL.next())
        assertEquals(PerformanceOverlayMode.DETAILED, PerformanceOverlayMode.CLASSIC.next())
        assertEquals(PerformanceOverlayMode.MINIMAL, PerformanceOverlayMode.DETAILED.next())
        assertEquals(PerformanceOverlayMode.CLASSIC, performanceOverlayModeFromStoredValue(null))
        assertEquals(PerformanceOverlayMode.CLASSIC, performanceOverlayModeFromStoredValue("BROKEN"))
        assertEquals(PerformanceOverlayMode.MINIMAL, performanceOverlayModeFromStoredValue("MINIMAL"))
    }

    @Test
    fun `tweaker control steps scope only and never rewrites the chosen view`() {
        // the row settles WHEN the overlay shows -- off, only games, always on --
        // and the view is cycled by touching the overlay. Reaching "always on" must therefore cost
        // exactly two presses, not the six the old scope-times-mode walk demanded.
        val onlyGames = stepPerformanceOverlayControl(
            PerformanceOverlayVisibility.OFF,
            PerformanceOverlayMode.CLASSIC,
            direction = 1,
        )
        assertEquals(
            PerformanceOverlayControlState(PerformanceOverlayVisibility.GAMES_ONLY, PerformanceOverlayMode.CLASSIC),
            onlyGames,
        )
        val alwaysOn = stepPerformanceOverlayControl(onlyGames.visibility, onlyGames.mode, direction = 1)
        assertEquals(
            PerformanceOverlayControlState(PerformanceOverlayVisibility.ALWAYS, PerformanceOverlayMode.CLASSIC),
            alwaysOn,
        )

        // The value names the scope alone. Naming the view too would claim this row still changes it.
        assertEquals(
            "GAMES ONLY",
            performanceOverlayControlLabel(
                onlyGames.visibility,
                onlyGames.mode,
                PerformanceOverlayRowValue.ON,
            ),
        )
        assertEquals(
            "ALWAYS ON",
            performanceOverlayControlLabel(
                alwaysOn.visibility,
                alwaysOn.mode,
                PerformanceOverlayRowValue.ON,
            ),
        )

        // Both ends clamp, so the row never falls off its own list.
        assertEquals(alwaysOn, stepPerformanceOverlayControl(alwaysOn.visibility, alwaysOn.mode, direction = 1))
        assertEquals(
            PerformanceOverlayControlState(PerformanceOverlayVisibility.OFF, PerformanceOverlayMode.CLASSIC),
            stepPerformanceOverlayControl(onlyGames.visibility, onlyGames.mode, direction = -1),
        )
    }

    @Test
    fun `stepping scope carries every view through untouched`() {
        // A scope change must never silently rewrite the player's view. The old list forced MINIMAL on
        // entry to each scope, so choosing "always on" quietly discarded a DETAILED preference.
        PerformanceOverlayMode.entries.forEach { mode ->
            assertEquals(
                mode,
                stepPerformanceOverlayControl(PerformanceOverlayVisibility.OFF, mode, direction = 1).mode,
            )
            assertEquals(
                mode,
                stepPerformanceOverlayControl(PerformanceOverlayVisibility.ALWAYS, mode, direction = -1).mode,
            )
        }
    }

    @Test
    fun `visibility step cycles OFF through GAMES_ONLY to ALWAYS and clamps at both ends`() {
        assertEquals(PerformanceOverlayVisibility.GAMES_ONLY, PerformanceOverlayVisibility.OFF.step(1))
        assertEquals(PerformanceOverlayVisibility.ALWAYS, PerformanceOverlayVisibility.GAMES_ONLY.step(1))
        assertEquals(PerformanceOverlayVisibility.ALWAYS, PerformanceOverlayVisibility.ALWAYS.step(1))

        assertEquals(PerformanceOverlayVisibility.GAMES_ONLY, PerformanceOverlayVisibility.ALWAYS.step(-1))
        assertEquals(PerformanceOverlayVisibility.OFF, PerformanceOverlayVisibility.GAMES_ONLY.step(-1))
        assertEquals(PerformanceOverlayVisibility.OFF, PerformanceOverlayVisibility.OFF.step(-1))

        assertEquals(PerformanceOverlayVisibility.GAMES_ONLY, PerformanceOverlayVisibility.GAMES_ONLY.step(0))
    }

    @Test
    fun `a stored legacy ON migrates to ALWAYS and a stored legacy OFF stays OFF`() {
        assertEquals(PerformanceOverlayVisibility.ALWAYS, performanceOverlayVisibilityFromStoredValue("ON"))
        assertEquals(PerformanceOverlayVisibility.OFF, performanceOverlayVisibilityFromStoredValue("OFF"))
        assertEquals(
            PerformanceOverlayVisibility.GAMES_ONLY,
            performanceOverlayVisibilityFromStoredValue("GAMES_ONLY"),
        )
        assertEquals(PerformanceOverlayVisibility.ALWAYS, performanceOverlayVisibilityFromStoredValue("ALWAYS"))
        assertEquals(PerformanceOverlayVisibility.OFF, performanceOverlayVisibilityFromStoredValue(null))
        assertEquals(PerformanceOverlayVisibility.OFF, performanceOverlayVisibilityFromStoredValue("corrupt"))
    }

    @Test
    fun `games only reads Mimir's own dispatched library game, never a stale or inactive one`() {
        assertEquals(
            "com.example.game",
            performanceOverlayDispatchedGamePackageName(dispatchedGameSession("com.example.game")),
        )
        assertEquals(
            "com.example.game",
            performanceOverlayDispatchedGamePackageName(
                dispatchedGameSession("com.example.game", phase = MimirSessionPhase.YIELDED_TO_SECONDARY_TASK),
            ),
        )
        assertNull(performanceOverlayDispatchedGamePackageName(null))
        assertNull(
            performanceOverlayDispatchedGamePackageName(
                dispatchedGameSession("com.example.game", phase = MimirSessionPhase.PREPARING),
            ),
        )
        assertNull(
            performanceOverlayDispatchedGamePackageName(
                dispatchedGameSession("com.example.game", phase = MimirSessionPhase.RETURNING),
            ),
        )
        assertNull(
            performanceOverlayDispatchedGamePackageName(
                dispatchedGameSession("com.example.game", phase = MimirSessionPhase.ENDED),
            ),
        )
    }

    @Test
    fun `games only shows while the dispatched game agrees with the observed foreground`() {
        assertTrue(
            performanceOverlayShouldPresentCard(
                PerformanceOverlayVisibility.GAMES_ONLY,
                dispatchedGamePackageName = "com.example.game",
                foregroundSignal = PerformanceOverlayForegroundSignal.ForeignForeground("com.example.game"),
            ),
        )
        // No settled foreground yet (still warming, or a genuine gap): trust dispatch truth alone rather
        // than flicker the card off every time the foreground signal stutters.
        assertTrue(
            performanceOverlayShouldPresentCard(
                PerformanceOverlayVisibility.GAMES_ONLY,
                dispatchedGamePackageName = "com.example.game",
                foregroundSignal = PerformanceOverlayForegroundSignal.AwaitingObservation,
            ),
        )
        assertTrue(
            performanceOverlayGamesOnlyVisible(
                "com.example.game",
                PerformanceOverlayForegroundSignal.AwaitingObservation,
            ),
        )
    }

    @Test
    fun `games only hides once the foreground is confirmed to be something else`() {
        assertFalse(
            performanceOverlayShouldPresentCard(
                PerformanceOverlayVisibility.GAMES_ONLY,
                dispatchedGamePackageName = "com.example.game",
                foregroundSignal = PerformanceOverlayForegroundSignal.ForeignForeground("com.other.app"),
            ),
        )
        // Idle in Mimir itself: nothing was ever dispatched, so there is nothing to confirm.
        assertFalse(
            performanceOverlayShouldPresentCard(
                PerformanceOverlayVisibility.GAMES_ONLY,
                dispatchedGamePackageName = null,
                foregroundSignal = PerformanceOverlayForegroundSignal.AwaitingObservation,
            ),
        )
        assertFalse(
            performanceOverlayShouldPresentCard(
                PerformanceOverlayVisibility.GAMES_ONLY,
                dispatchedGamePackageName = null,
                foregroundSignal = PerformanceOverlayForegroundSignal.ForeignForeground("com.other.app"),
            ),
        )
    }

    @Test
    fun `ALWAYS is unconditional and OFF never presents regardless of dispatch state`() {
        assertTrue(
            performanceOverlayShouldPresentCard(
                PerformanceOverlayVisibility.ALWAYS,
                dispatchedGamePackageName = null,
                foregroundSignal = PerformanceOverlayForegroundSignal.ForeignForeground("com.other.app"),
            ),
        )
        assertFalse(
            performanceOverlayShouldPresentCard(
                PerformanceOverlayVisibility.OFF,
                dispatchedGamePackageName = "com.example.game",
                foregroundSignal = PerformanceOverlayForegroundSignal.ForeignForeground("com.example.game"),
            ),
        )
    }

    @Test
    fun `neutral foreground hides the card whether it is Mimir itself, the launcher, or SystemUI`() {
        // The pure gate only ever sees NeutralForeground — it does not need to know which neutral surface
        // produced it. Mimir itself is the one this file can positively confirm live, via
        // PrimaryActivityObservationState (see performanceOverlayForegroundSignal); the launcher and
        // SystemUI are proven by AndroidExternalForegroundMonitor's own upstream exclusion never emitting
        // a foreign candidate for them in the first place.
        assertFalse(
            performanceOverlayGamesOnlyVisible(
                "com.example.game",
                PerformanceOverlayForegroundSignal.NeutralForeground,
            ),
        )
        assertFalse(
            performanceOverlayShouldPresentCard(
                PerformanceOverlayVisibility.GAMES_ONLY,
                dispatchedGamePackageName = "com.example.game",
                foregroundSignal = PerformanceOverlayForegroundSignal.NeutralForeground,
            ),
        )
    }

    @Test
    fun `missing usage access is a distinct honest state that never silently degrades to OFF or ALWAYS`() {
        val signal = performanceOverlayForegroundSignal(
            usageAccessGranted = false,
            mimirPrimaryResumed = false,
            settledForeignPackageName = "com.example.game",
        )
        // Distinct from every other case, even though a foreign package was otherwise settled: Usage
        // Access missing must never be silently read as a confirmed neutral or foreign foreground.
        assertEquals(PerformanceOverlayForegroundSignal.UsageAccessMissing, signal)
        assertNotEquals(PerformanceOverlayForegroundSignal.NeutralForeground, signal)
        assertNotEquals(PerformanceOverlayForegroundSignal.AwaitingObservation, signal)

        // Not OFF: GAMES_ONLY still shows while a game is dispatched, exactly like the transient
        // "awaiting observation" case — missing Usage Access must not silently turn the option inert.
        assertTrue(performanceOverlayGamesOnlyVisible("com.example.game", signal))
        // Not ALWAYS: with nothing dispatched, GAMES_ONLY still shows nothing.
        assertFalse(performanceOverlayGamesOnlyVisible(null, signal))
    }

    @Test
    fun `awaiting observation is distinct from missing usage access though both trust dispatch truth`() {
        val awaiting = performanceOverlayForegroundSignal(
            usageAccessGranted = true,
            mimirPrimaryResumed = false,
            settledForeignPackageName = null,
        )
        assertEquals(PerformanceOverlayForegroundSignal.AwaitingObservation, awaiting)
        assertNotEquals(PerformanceOverlayForegroundSignal.UsageAccessMissing, awaiting)
        assertTrue(performanceOverlayGamesOnlyVisible("com.example.game", awaiting))
    }

    @Test
    fun `foreground signal combination prioritizes usage access then Mimir itself then a settled package`() {
        assertEquals(
            PerformanceOverlayForegroundSignal.UsageAccessMissing,
            performanceOverlayForegroundSignal(
                usageAccessGranted = false,
                mimirPrimaryResumed = true,
                settledForeignPackageName = "com.example.game",
            ),
        )
        // Mimir's own Activity lifecycle is exact and wins over a stale settled package the debouncer had
        // not yet cleared — see the tracker's own reset-on-visibility-change plus this ordering.
        assertEquals(
            PerformanceOverlayForegroundSignal.NeutralForeground,
            performanceOverlayForegroundSignal(
                usageAccessGranted = true,
                mimirPrimaryResumed = true,
                settledForeignPackageName = "com.example.game",
            ),
        )
        assertEquals(
            PerformanceOverlayForegroundSignal.ForeignForeground("com.example.game"),
            performanceOverlayForegroundSignal(
                usageAccessGranted = true,
                mimirPrimaryResumed = false,
                settledForeignPackageName = "com.example.game",
            ),
        )
        assertEquals(
            PerformanceOverlayForegroundSignal.AwaitingObservation,
            performanceOverlayForegroundSignal(
                usageAccessGranted = true,
                mimirPrimaryResumed = false,
                settledForeignPackageName = null,
            ),
        )
    }

    @Test
    fun `a single foreground blip never settles and never toggles the card`() {
        val tracker = PerformanceOverlayGamesOnlyForegroundTracker()

        tracker.observeForeign("com.other.app")
        assertNull(tracker.settledForeignPackageName)

        // The differential proof for this test: lowering
        // PerformanceOverlayForegroundDebounceObservations to 1 makes this single blip settle immediately,
        // which is exactly the flicker this debounce exists to prevent.
        tracker.observeForeign("com.example.game")
        assertNull(tracker.settledForeignPackageName)
    }

    @Test
    fun `two consecutive observations settle the foreground and a third differing package requires its own pair`() {
        val tracker = PerformanceOverlayGamesOnlyForegroundTracker()

        tracker.observeForeign("com.example.game")
        tracker.observeForeign("com.example.game")
        assertEquals("com.example.game", tracker.settledForeignPackageName)

        // A single blip to a different package must not displace an already-settled one.
        tracker.observeForeign("com.other.app")
        assertEquals("com.example.game", tracker.settledForeignPackageName)

        // Re-observing the already-settled package re-confirms instantly rather than re-debouncing.
        tracker.observeForeign("com.example.game")
        assertEquals("com.example.game", tracker.settledForeignPackageName)

        // The different package still needs its own two consecutive observations to take over.
        tracker.observeForeign("com.other.app")
        assertEquals("com.example.game", tracker.settledForeignPackageName)
        tracker.observeForeign("com.other.app")
        assertEquals("com.other.app", tracker.settledForeignPackageName)

        tracker.reset()
        assertNull(tracker.settledForeignPackageName)
    }

    @Test
    fun `missing usage access fails GAMES ONLY closed honestly instead of pretending ALWAYS or OFF`() {
        val setUp = performanceOverlayRowValue(
            PerformanceOverlayVisibility.GAMES_ONLY,
            TweakerUsageAccessState.SET_UP,
            canDrawOverlays = true,
        )
        assertEquals(PerformanceOverlayRowValue.ACCESS, setUp)
        val setUpLabel = performanceOverlayControlLabel(PerformanceOverlayVisibility.GAMES_ONLY, PerformanceOverlayMode.CLASSIC, setUp)
        assertEquals("ACCESS", setUpLabel)
        assertNotEquals("OFF", setUpLabel)
        assertNotEquals(
            performanceOverlayControlLabel(
                PerformanceOverlayVisibility.GAMES_ONLY,
                PerformanceOverlayMode.CLASSIC,
                PerformanceOverlayRowValue.ON,
            ),
            setUpLabel,
        )

        val uninstalled = performanceOverlayRowValue(
            PerformanceOverlayVisibility.GAMES_ONLY,
            TweakerUsageAccessState.INSTALL_TWEAKER,
            canDrawOverlays = true,
        )
        assertEquals(PerformanceOverlayRowValue.UNAVAILABLE, uninstalled)
        assertEquals(
            "UNAVAILABLE",
            performanceOverlayControlLabel(PerformanceOverlayVisibility.GAMES_ONLY, PerformanceOverlayMode.CLASSIC, uninstalled),
        )

        // The persisted choice is never silently discarded — it is still GAMES_ONLY underneath, just not
        // representable as "working" while access is missing.
        assertEquals(
            PerformanceOverlayRowValue.ACCESS,
            performanceOverlayRowValue(
                PerformanceOverlayVisibility.ALWAYS,
                TweakerUsageAccessState.SET_UP,
                canDrawOverlays = true,
            ),
        )
    }

    @Test
    fun `gesture arbiter keeps taps drags and cancellations mutually exclusive`() {
        assertFalse(performanceOverlayShouldStartDrag(distancePx = 8f, touchSlopPx = 8f))
        assertFalse(performanceOverlayShouldStartDrag(distancePx = 7.99f, touchSlopPx = 8f))
        assertTrue(performanceOverlayShouldStartDrag(distancePx = 8.01f, touchSlopPx = 8f))

        val tap = PerformanceOverlayGestureArbiter(8f)
        assertFalse(tap.update(7f, pointerCount = 1))
        assertEquals(PerformanceOverlayGestureOutcome.TAP, tap.finish())

        val drag = PerformanceOverlayGestureArbiter(8f)
        assertTrue(drag.update(12f, pointerCount = 1))
        assertFalse(drag.update(0f, pointerCount = 1))
        assertEquals(PerformanceOverlayGestureOutcome.DRAG, drag.finish())

        val multiTouch = PerformanceOverlayGestureArbiter(8f)
        assertFalse(multiTouch.update(2f, pointerCount = 2))
        assertEquals(PerformanceOverlayGestureOutcome.NONE, multiTouch.finish())
    }

    @Test
    fun `mode geometry reserves each plate without exceeding the safe display`() {
        assertEquals(
            PerformanceOverlayDimensionsDp(224f, 30f),
            performanceOverlayDimensionsDp(
                PerformanceOverlayMode.MINIMAL,
                808f,
                444f,
                1f,
                minimalContentWidthDp = 224f,
            ),
        )
        assertEquals(
            PerformanceOverlayDimensionsDp(344f, 32f),
            performanceOverlayDimensionsDp(
                PerformanceOverlayMode.CLASSIC,
                808f,
                444f,
                1f,
                contentWidthDp = 344f,
            ),
        )
        assertEquals(
            PerformanceOverlayDimensionsDp(352f, 32f),
            performanceOverlayDimensionsDp(
                PerformanceOverlayMode.DETAILED,
                808f,
                444f,
                1f,
                contentWidthDp = 352f,
            ),
        )
        val compactScaled = performanceOverlayDimensionsDp(
            PerformanceOverlayMode.DETAILED,
            336f,
            336f,
            1.3f,
        )
        assertEquals(156f, compactScaled.width, 0.01f)
        assertEquals(39f, compactScaled.height, 0.01f)
        assertFalse(compactScaled.renderable)

        val largeText = performanceOverlayDimensionsDp(
            PerformanceOverlayMode.MINIMAL,
            availableWidthDp = 808f,
            availableHeightDp = 444f,
            fontScale = 1.5f,
            minimalContentWidthDp = 280f,
        )
        assertEquals(280f, largeText.width, 0.01f)
        assertEquals(45f, largeText.height, 0.01f)

        val narrow = performanceOverlayDimensionsDp(
            PerformanceOverlayMode.MINIMAL,
            availableWidthDp = 160f,
            availableHeightDp = 40f,
            fontScale = 1.5f,
            minimalContentWidthDp = 280f,
        )
        assertEquals(160f, narrow.width, 0.01f)
        assertEquals(40f, narrow.height, 0.01f)
        assertFalse(narrow.renderable)
    }

    @Test
    fun `minimal width is derived from measured tracked text and grows with font scale`() {
        val normal = performanceOverlayMinimalWidthDp(fontScale = 1f) { text, fontSizeSp ->
            text.length * fontSizeSp * 0.5f
        }
        val large = performanceOverlayMinimalWidthDp(fontScale = 1.6f) { text, fontSizeSp ->
            text.length * fontSizeSp * 0.5f * 1.6f
        }

        assertTrue(normal > 0f)
        assertTrue(large > normal)
    }

    @Test
    fun `classic and extended widths are content measured rather than fixed plates`() {
        val classic = performanceOverlayContentWidthDp(PerformanceOverlayMode.CLASSIC, 1f) { text, size ->
            text.length * size * 0.5f
        }
        val extended = performanceOverlayContentWidthDp(PerformanceOverlayMode.DETAILED, 1f) { text, size ->
            text.length * size * 0.5f
        }

        assertTrue(classic > 0f && classic < 400f)
        assertTrue(extended > classic && extended < 808f)
    }

    @Test
    fun `one line modes never mount narrower than their measured content`() {
        val measure: (String, Float) -> Float = { text, size -> text.length * size * 0.5f }
        val classic = performanceOverlayContentWidthDp(PerformanceOverlayMode.CLASSIC, 1f, measure)
        val extended = performanceOverlayContentWidthDp(PerformanceOverlayMode.DETAILED, 1f, measure)

        assertTrue(
            performanceOverlayDimensionsDp(
                mode = PerformanceOverlayMode.CLASSIC,
                availableWidthDp = 808f,
                availableHeightDp = 444f,
                fontScale = 1f,
                contentWidthDp = classic,
            ).renderable,
        )
        assertTrue(
            performanceOverlayDimensionsDp(
                mode = PerformanceOverlayMode.DETAILED,
                availableWidthDp = 808f,
                availableHeightDp = 444f,
                fontScale = 1f,
                contentWidthDp = extended,
            ).renderable,
        )
        assertFalse(
            performanceOverlayDimensionsDp(
                mode = PerformanceOverlayMode.DETAILED,
                availableWidthDp = 444f,
                availableHeightDp = 444f,
                fontScale = 1.3f,
                contentWidthDp = extended * 1.3f,
            ).renderable,
        )
        assertFalse(
            performanceOverlayDimensionsDp(
                mode = PerformanceOverlayMode.MINIMAL,
                availableWidthDp = 160f,
                availableHeightDp = 160f,
                fontScale = 1f,
                minimalContentWidthDp = 280f,
            ).renderable,
        )
    }

    @Test
    fun `profile marks are stable and accessibility keeps their full names`() {
        assertEquals("ᛗ", performanceOverlayProfileMark("stock"))
        assertEquals("ᛁ", performanceOverlayProfileMark("quiet"))
        assertEquals("ᛃ", performanceOverlayProfileMark("balanced"))
        assertEquals("ᛏ", performanceOverlayProfileMark("performance"))
        assertEquals("Tweaker profile: EFFICIENCY", performanceOverlayProfileDescription("quiet"))
    }

    @Test
    fun `telemetry slots keep fixed maxima and symmetric terminal padding`() {
        assertEquals(
            "1000",
            performanceOverlayMetricMaximum(PerformanceOverlayMode.MINIMAL, PerformanceOverlayCell.FPS),
        )
        assertEquals(
            "100%",
            performanceOverlayMetricMaximum(PerformanceOverlayMode.MINIMAL, PerformanceOverlayCell.BATT),
        )
        assertEquals(
            "100°C·100%·4.0 GHz",
            performanceOverlayMetricMaximum(PerformanceOverlayMode.CLASSIC, PerformanceOverlayCell.CPU),
        )
        assertEquals(
            "100°C·100%·4.0 GHz",
            performanceOverlayMetricMaximum(PerformanceOverlayMode.CLASSIC, PerformanceOverlayCell.GPU),
        )
        assertEquals(
            "72H 00M",
            performanceOverlayMetricMaximum(PerformanceOverlayMode.DETAILED, PerformanceOverlayCell.LEFT),
        )
        // Extended's BATT slot shrank with the label: it is a level, never a level plus a state.
        assertEquals(
            "100%",
            performanceOverlayMetricMaximum(PerformanceOverlayMode.DETAILED, PerformanceOverlayCell.BATT),
        )
        assertEquals(6f, PerformanceOverlayTerminalPaddingDp, 0f)
        assertEquals(14f, PerformanceOverlayIdentityTerminalWidthDp, 0f)
    }

    /**
     * **Geometry, measured rather than reasoned about — policy 2026-08-31.**
     *
     * CPU and GPU share the same bounded whole-device percentage format. These are the plate's own
     * measurement function driven by the same 0.62-per-character estimator production falls back to,
     * so the numbers are the ones the window is actually sized with.
     *
     * Pinned so the next edit has to state its own cost: **MINIMAL 232 dp, CLASSIC 374 dp, DETAILED
     * 643 dp.** The fourth FPS digit is the smallest geometry cost of accepting the provider's
     * validated 0..1000 range.
     */
    @Test
    fun `the plate pays one bounded percentage format for CPU and GPU`() {
        val estimate: (String, Float) -> Float = { text, size -> text.length * size * 0.62f }
        fun width(mode: PerformanceOverlayMode) =
            performanceOverlayContentWidthDp(mode, fontScale = 1f, measureTextWidthDp = estimate)

        assertEquals(232f, width(PerformanceOverlayMode.MINIMAL), 0.5f)
        assertEquals(374f, width(PerformanceOverlayMode.CLASSIC), 0.5f)
        assertEquals(643f, width(PerformanceOverlayMode.DETAILED), 0.5f)

        // Compact is untouched because it never carried a busy percentage: CPU and GPU are a
        // temperature alone there, which is what holds that plate narrow.
        assertEquals(
            "100°C",
            performanceOverlayMetricMaximum(PerformanceOverlayMode.MINIMAL, PerformanceOverlayCell.CPU),
        )
    }

    @Test
    fun `detailed labels preserve honest bounded values`() {
        val gib = 1024L * 1024L * 1024L
        assertEquals("5.5/12.0G", performanceOverlayRamLabel(11L * gib / 2L, 12L * gib))
        assertEquals("—", performanceOverlayRamLabel(13L * gib, 12L * gib))
        assertEquals("2H 05M", performanceOverlayRemainingLabel(125L * 60L * 1_000L))
        assertEquals("—", performanceOverlayRemainingLabel(null))
    }

    @Test
    fun `FPS history remains bounded and preserves missing sample gaps`() {
        val history = listOf<Int?>(58, 59, null, 60)
        assertEquals(
            listOf<Int?>(null, 60, 61),
            appendPerformanceOverlayFpsHistory(history, 61, limit = 3),
        )
    }

    /**
     * The graph follows the newest renderer sample and does not use the host window state as a proxy
     * for game availability.
     */
    @Test
    fun `the graph follows renderer availability even when Mimir is backgrounded`() {
        assertEquals(
            "FPS UNAVAILABLE",
            performanceOverlayFrameStateLabel(primaryForeground = false, samples = listOf(null, null)),
        )
        assertEquals(
            null,
            performanceOverlayFrameStateLabel(primaryForeground = false, samples = listOf(58, 60)),
        )
        // It renders at 5 sp inside the graph's fixed 72 dp rail with overflow = Clip, so its length
        // is a geometry constraint and not only a copy one -- see the function's KDoc for the numbers.
        assertTrue(
            "the graph's state label must stay inside the 72 dp rail",
            "FPS UNAVAILABLE".length * 5f * 0.62f + 17f * 0.1f < 72f,
        )
        assertEquals(
            "FPS UNAVAILABLE",
            performanceOverlayFrameStateLabel(primaryForeground = true, samples = emptyList()),
        )
        assertEquals(
            null,
            performanceOverlayFrameStateLabel(primaryForeground = true, samples = listOf(null, 60)),
        )
        assertEquals(
            listOf<Int?>(58, 60),
            performanceOverlayFrameHistoryForDrawing(primaryForeground = false, samples = listOf(58, 60)),
        )
        assertEquals(
            listOf<Int?>(58, 60),
            performanceOverlayFrameHistoryForDrawing(primaryForeground = true, samples = listOf(58, 60)),
        )
        assertEquals(
            emptyList<Int?>(),
            performanceOverlayFrameHistoryForDrawing(primaryForeground = false, samples = listOf(58, 60, null)),
        )
    }

    @Test
    fun `recovery status plate measures its one-line content at accessible font scale`() {
        val normal = performanceOverlayStatusWidthDp("TELEMETRY WARMING", 1f) { text, size ->
            text.length * size * 0.5f
        }
        val accessible = performanceOverlayStatusWidthDp("TELEMETRY WARMING", 1.3f) { text, size ->
            text.length * size * 0.5f * 1.3f
        }

        assertTrue(normal > 0f)
        assertTrue(accessible > normal)
        assertTrue(accessible < 180f)
    }

    @Test
    fun `FPS graph occupies its complete rail without reserved empty history slots`() {
        assertEquals(0f, performanceOverlayGraphX(0, sampleCount = 3, widthPx = 120f), 0.01f)
        assertEquals(60f, performanceOverlayGraphX(1, sampleCount = 3, widthPx = 120f), 0.01f)
        assertEquals(120f, performanceOverlayGraphX(2, sampleCount = 3, widthPx = 120f), 0.01f)
    }

    @Test
    fun `battery status conflicts fail closed and stable discharge yields ETA`() {
        assertEquals(
            null,
            coherentPerformanceOverlayBatteryStatus("Discharging", "Charging"),
        )
        assertEquals(
            "Discharging",
            coherentPerformanceOverlayBatteryStatus(null, "Discharging"),
        )

        val estimator = PerformanceOverlayBatteryRuntimeEstimator()
        assertEquals(null, estimator.estimateRemainingMs("Discharging", 20_000_000_000L, -5_000))
        assertEquals(null, estimator.estimateRemainingMs("Discharging", 20_000_000_000L, -5_000))
        assertEquals(
            4L * 60L * 60L * 1_000L,
            estimator.estimateRemainingMs("Discharging", 20_000_000_000L, -5_000),
        )
        assertEquals(null, estimator.estimateRemainingMs("Charging", 20_000_000_000L, 5_000))
    }

    @Test
    fun `freshness boundary expires stale samples without inventing a new one`() {
        val telemetry = telemetry(sampledAtElapsedRealtimeMs = 1_000L)

        assertTrue(telemetry.isFreshAt(3_500L))
        assertFalse(telemetry.isFreshAt(3_501L))
        assertFalse(telemetry.isFreshAt(999L))
    }

    /**
     * A game launch takes the Tweaker token from the overlay's observation; the overlay reopens 500 ms later while the
     * launch still holds it and falls back to kernel reads. Those reads never go null, so unless the fallback yields
     * the overlay stays on "—" for frame rate and CPU busy until it is toggled.
     */
    @Test
    fun `the kernel fallback yields once the Tweaker path can open, and not before its dwell`() {
        var now = 10_000L
        var mayOpen = false
        var kernelReads = 0
        val observation = discoveredTelemetryObservation(
            initial = telemetry(frameAverageFpsMilli = null),
            read = { kernelReads += 1; telemetry(frameAverageFpsMilli = null) },
            elapsedRealtimeMs = { now },
            tweakerMayOpen = { mayOpen },
            retryAfterMs = 5_000L,
        )

        assertFalse(observation.yieldRequested())
        observation.read()
        assertEquals("the first read is the one already taken", 0, kernelReads)
        observation.read()
        assertEquals(1, kernelReads)

        mayOpen = true
        now += 4_999L
        assertFalse("inside the dwell a failing bind is not retried every beat", observation.yieldRequested())
        now += 1L
        assertTrue(observation.yieldRequested())
        assertNull("a yielding observation reads nothing more", observation.read())

        mayOpen = false
        assertFalse("still closed to the Tweaker: keep reading the kernel", observation.yieldRequested())
    }

    @Test
    fun `the Tweaker telemetry path opens only under the conditions openTelemetryObservation admits`() {
        fun may(
            pair: Boolean = true,
            pending: Boolean = false,
            owns: Boolean = false,
            bound: Boolean = false,
            idle: Boolean = true,
        ) = tweakerTelemetryObservationMayOpen(pair, pending, owns, bound, idle)

        assertTrue(may())
        assertFalse("a pending launch ticket keeps observers out", may(pending = true, idle = true))
        assertFalse("a pair that cannot bind cannot open", may(pair = false))
        assertFalse("the token is held (a launch preparing)", may(idle = false))
        assertTrue("an active owner's bound transport is shared", may(owns = true, bound = true, idle = false))
        assertFalse("an owner with nothing bound has nothing to share", may(owns = true, bound = false, idle = false))
    }

    @Test
    fun `telemetry observation releases its process transport exactly once`() {
        var reads = 0
        var releases = 0
        var yieldRequested = false
        val observation = TweakerTelemetryObservation(
            readTelemetry = {
                reads += 1
                telemetry(frameAverageFpsMilli = 60_000)
            },
            shouldYield = { yieldRequested },
            release = { releases += 1 },
        )

        assertEquals(60_000, observation.read()?.frameAverageFpsMilli)
        yieldRequested = true
        assertTrue(observation.yieldRequested())
        assertEquals(null, observation.read())
        observation.close()
        observation.close()

        assertEquals(1, reads)
        assertEquals(1, releases)
        assertEquals(null, observation.read())
    }

    @Test
    fun `overlay lifetime follows its persisted switch instead of a game session`() {
        assertTrue(performanceOverlayShouldRun(PerformanceOverlayVisibility.ALWAYS, canDrawOverlays = true))
        // GAMES_ONLY also keeps the service alive in the background — only the floating card's
        // presentation is gated on a confirmed game; see performanceOverlayShouldPresentCard. Since the
        // 2026-08-21 ruling that background stretch samples the transport at PerformanceTelemetryHiddenPollMs
        // rather than the visible 1s beat; running is not the same as reading at full rate.
        assertTrue(performanceOverlayShouldRun(PerformanceOverlayVisibility.GAMES_ONLY, canDrawOverlays = true))
        assertFalse(performanceOverlayShouldRun(PerformanceOverlayVisibility.OFF, canDrawOverlays = true))
        assertFalse(performanceOverlayShouldRun(PerformanceOverlayVisibility.ALWAYS, canDrawOverlays = false))
    }

    @Test
    fun `a drawn card is read on every beat and a hidden one only when the slow cadence is due`() {
        // Drawing: unconditional, whatever the clock says — this is the 1s arm, unchanged by RC-44.
        assertTrue(
            performanceOverlayTelemetryReadDue(
                telemetryWanted = true,
                nowElapsedRealtimeMs = 10_000L,
                lastReadAtElapsedRealtimeMs = 9_999L,
            ),
        )

        // Hidden: every beat inside the window is skipped. Bounds derived from the constant rather than
        // hardcoded, so this test follows the ruling instead of having to be rewritten alongside it.
        val insideWindow = listOf(1L, 1_000L, PerformanceTelemetryHiddenPollMs / 2, PerformanceTelemetryHiddenPollMs - 1)
        for (elapsed in insideWindow) {
            assertFalse(
                "hidden read at +${elapsed}ms should have been skipped",
                performanceOverlayTelemetryReadDue(
                    telemetryWanted = false,
                    nowElapsedRealtimeMs = 10_000L + elapsed,
                    lastReadAtElapsedRealtimeMs = 10_000L,
                ),
            )
        }

        // ...and the boundary itself reads, so the hidden cadence is exactly the ruled interval and never
        // drifts longer.
        assertTrue(
            performanceOverlayTelemetryReadDue(
                telemetryWanted = false,
                nowElapsedRealtimeMs = 10_000L + PerformanceTelemetryHiddenPollMs,
                lastReadAtElapsedRealtimeMs = 10_000L,
            ),
        )
    }

    @Test
    fun `a freshly opened transport reads immediately even while nothing is drawn`() {
        // Null means this observation has never been read. It must prove itself before earning a skip,
        // otherwise a transport that died during a hidden stretch would go unnoticed for a whole window.
        assertTrue(
            performanceOverlayTelemetryReadDue(
                telemetryWanted = false,
                nowElapsedRealtimeMs = 0L,
                lastReadAtElapsedRealtimeMs = null,
            ),
        )
    }

    @Test
    fun `the hidden cadence sits in the specified band and stays a whole number of visible beats`() {
        // poll every 5-10s while nothing is drawn, 1s while the card is visible.
        // Hidden polling was slowed after device QA measured 5.69% of one core continuously and
        // ~261 ms per compose with no game or card drawn (21 windows, clock pinned, zero void).
        // The VISIBLE 1s beat is NOT superseded and is still pinned below.
        // Still a band, not a single value, so re-tuning inside the hidden cadence band remains valid; still a whole
        // multiple so the hidden arm can never land off the loop's beat and skew the interval it reports.
        assertTrue(
            "hidden cadence $PerformanceTelemetryHiddenPollMs must be at least 20s",
            PerformanceTelemetryHiddenPollMs >= 20_000L,
        )
        assertTrue(
            "hidden cadence $PerformanceTelemetryHiddenPollMs must be at most 60s — past that the " +
                "battery-runtime cell and dead-transport detection get too cold to be honest",
            PerformanceTelemetryHiddenPollMs <= 60_000L,
        )
        assertEquals(1_000L, PerformanceTelemetryPollMs)
        assertEquals(0L, PerformanceTelemetryHiddenPollMs % PerformanceTelemetryPollMs)
        // The visible beat is strictly faster than the hidden one, or the ruling is inverted.
        assertTrue(PerformanceTelemetryPollMs < PerformanceTelemetryHiddenPollMs)
    }

    @Test
    fun `a hidden stretch still outlives the tweaker cache so every read it does make is fresh`() {
        // Out of scope by the same ruling: LiveTelemetryCache's FRESH_FOR_MS = 750ms is load-bearing for
        // the 2500ms staleness gates and that cache window remains unchanged. This pins the one property the
        // cadence change could have broken — a hidden read must still miss the cache and sweep fresh, or
        // "poll" would degrade into "re-serve a stale sample".
        val tweakerCacheFreshForMs = 750L
        assertTrue(PerformanceTelemetryPollMs > tweakerCacheFreshForMs)
        assertTrue(PerformanceTelemetryHiddenPollMs > tweakerCacheFreshForMs)
    }

    @Test
    fun `drag placement clamps to safe bounds and round trips across display sizes`() {
        val odinBounds = PerformanceOverlayDragBounds(
            minX = 12,
            maxX = 820,
            minY = 24,
            maxY = 620,
        )
        val clamped = clampPerformanceOverlayPoint(
            point = PerformanceOverlayPoint(x = 9_999, y = -200),
            bounds = odinBounds,
        )
        assertEquals(PerformanceOverlayPoint(x = 820, y = 24), clamped)

        val placement = performanceOverlayPlacement(
            point = PerformanceOverlayPoint(x = 416, y = 322),
            bounds = odinBounds,
        )
        assertEquals(
            PerformanceOverlayPoint(x = 504, y = 388),
            performanceOverlayPoint(
                placement = placement,
                bounds = PerformanceOverlayDragBounds(
                    minX = 20,
                    maxX = 988,
                    minY = 30,
                    maxY = 746,
                ),
            ),
        )
    }

    @Test
    fun `corner placement reaches every exact safe display limit`() {
        val bounds = PerformanceOverlayDragBounds(
            minX = 0,
            maxX = 1_520,
            minY = 0,
            maxY = 940,
        )

        assertEquals(
            PerformanceOverlayPoint(0, 0),
            clampPerformanceOverlayPoint(PerformanceOverlayPoint(-500, -500), bounds),
        )
        assertEquals(
            PerformanceOverlayPoint(1_520, 940),
            clampPerformanceOverlayPoint(PerformanceOverlayPoint(9_999, 9_999), bounds),
        )
    }

    @Test
    fun `live rotated display bounds own exact overlay limits`() {
        val landscape = performanceOverlayDragBounds(
            viewport = performanceOverlayLocalViewport(0, 0, 1_920, 1_080, density = 2.30625f),
            overlayWidth = 400,
            overlayHeight = 74,
        )
        val portrait = performanceOverlayDragBounds(
            viewport = performanceOverlayLocalViewport(0, 0, 1_080, 1_920, density = 2.30625f),
            overlayWidth = 400,
            overlayHeight = 74,
        )

        assertEquals(PerformanceOverlayDragBounds(0, 1_520, 0, 1_006), landscape)
        assertEquals(PerformanceOverlayDragBounds(0, 680, 0, 1_846), portrait)
    }

    @Test
    fun `only physical cutout insets constrain the painted overlay`() {
        val cutoutViewport = performanceOverlayLocalViewport(
            sourceLeft = 0,
            sourceTop = 0,
            sourceRight = 1_920,
            sourceBottom = 1_080,
            leftInset = 18,
            topInset = 12,
            rightInset = 24,
            bottomInset = 6,
            density = 2.30625f,
        )

        assertEquals(
            PerformanceOverlayDragBounds(18, 1_496, 12, 1_000),
            performanceOverlayDragBounds(cutoutViewport, overlayWidth = 400, overlayHeight = 74),
        )
    }

    @Test
    fun `offset window bounds normalize to local coordinates and insets own every drag corner`() {
        val viewport = performanceOverlayLocalViewport(
            sourceLeft = 40,
            sourceTop = 20,
            sourceRight = 1_960,
            sourceBottom = 1_100,
            leftInset = 12,
            topInset = 24,
            rightInset = 18,
            bottomInset = 30,
            density = 2.30625f,
        )
        assertEquals(PerformanceOverlayViewportPx(0, 0, 1_920, 1_080, 12, 24, 18, 30, 2.30625f), viewport)
        val bounds = performanceOverlayDragBounds(
            viewport = viewport,
            overlayWidth = 706,
            overlayHeight = 74,
        )

        assertEquals(PerformanceOverlayDragBounds(12, 1_196, 24, 976), bounds)
        assertEquals(
            PerformanceOverlayPoint(1_196, 976),
            performanceOverlayPoint(PerformanceOverlayPlacement(1f, 1f), bounds),
        )
    }

    @Test
    fun `persisted placement rejects non finite and out of range fractions`() {
        assertEquals(
            PerformanceOverlayPlacement(horizontalFraction = 0f, verticalFraction = 1f),
            PerformanceOverlayPlacement(
                horizontalFraction = Float.NaN,
                verticalFraction = 4f,
            ).sanitized(),
        )
    }

    @Test
    fun `telemetry refresh cannot snap an active drag back to persisted placement`() {
        val bounds = PerformanceOverlayDragBounds(10, 900, 20, 600)
        val persisted = PerformanceOverlayPlacement(0f, 0f)

        assertEquals(
            PerformanceOverlayPoint(500, 300),
            performanceOverlayRefreshPoint(
                currentPoint = PerformanceOverlayPoint(500, 300),
                persistedPlacement = persisted,
                bounds = bounds,
                dragging = true,
            ),
        )
        assertEquals(
            PerformanceOverlayPoint(10, 20),
            performanceOverlayRefreshPoint(
                currentPoint = PerformanceOverlayPoint(500, 300),
                persistedPlacement = persisted,
                bounds = bounds,
                dragging = false,
            ),
        )
    }

    private fun telemetry(
        sampledAtElapsedRealtimeMs: Long? = null,
        batteryPowerMw: Int? = null,
        batteryStatus: String? = null,
        cpuTempC: Int? = null,
        cpuPackageTempC: Int? = null,
        cpuPolicyClocksHz: List<Long> = emptyList(),
        gpuClockHz: Long? = null,
        frameAverageFpsMilli: Int? = null,
    ) = TweakerTelemetry(
        sampledAtElapsedRealtimeMs = sampledAtElapsedRealtimeMs,
        foregroundPackageName = null,
        frameStatus = null,
        frameAverageFpsMilli = frameAverageFpsMilli,
        cpuTempC = cpuTempC,
        gpuTempC = null,
        cpuBusyPercent = null,
        gpuBusyPercent = null,
        batteryPowerMw = batteryPowerMw,
        batteryStatus = batteryStatus,
        cpuPackageTempC = cpuPackageTempC,
        cpuPolicyClocksHz = cpuPolicyClocksHz,
        gpuClockHz = gpuClockHz,
    )

    private fun dispatchedGameSession(
        packageName: String,
        phase: MimirSessionPhase = MimirSessionPhase.DISPATCHED,
    ): MimirGameSession = MimirGameSession(
        operation = MimirGameSessionOperation("test:games-only"),
        sequence = 1L,
        gameId = com.gruning.mimir.rewrite.core.model.GameId("games-only-game"),
        platformId = com.gruning.mimir.rewrite.core.model.PlatformId("snes"),
        returnAnchor = MimirGameReturnAnchor.Home(
            com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget(
                itemId = com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId.Emulated(
                    com.gruning.mimir.rewrite.core.model.GameId("games-only-game"),
                ),
                rank = 1L,
            ),
        ),
        startedAtEpochMs = 1L,
        dispatchedAtEpochMs = 2L,
        dispatchedAtElapsedMs = 3L,
        targetPackageName = packageName,
        dualScreen = false,
        phase = phase,
    )
}
