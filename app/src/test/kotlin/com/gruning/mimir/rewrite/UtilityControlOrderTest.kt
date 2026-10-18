package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityAvailability
import com.gruning.mimir.rewrite.core.tweaker.TweakerFanMode
import com.gruning.mimir.rewrite.core.tweaker.TweakerAdvancedCapabilities
import com.gruning.mimir.rewrite.core.tweaker.TweakerManualFanCapability
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityRowState
import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityStepOutcome
import com.gruning.mimir.rewrite.core.tweaker.TWEAKER_UTILITY_INSTALL_CAPS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilityControlOrderTest {
    @Test
    fun mButtonPolicyBoundaryInputDoesNotEmitAWrite() {
        val current = state(canWrite = true)
        val authority = DefaultFocusAuthority().apply {
            val key = brightnessFocusKey(BrightnessControlKey.M_BUTTON_VISIBILITY)
            register(key, BrightnessScope, FocusBounds(0f, 0f, 100f, 50f))
            pushScope(BrightnessScope)
            requestFocus(key)
        }
        val steps = mutableListOf<Int>()

        fun dispatch(policy: MButtonVisibilityPolicy, action: ControllerAction) = dispatchBrightnessOverlay(
            authority = authority,
            state = current,
            action = action,
            onStep = { _, _ -> },
            onSetThemeAwareJoystickLight = {},
            onRequestWriteAccess = {},
            onRequestPrivilegedAccess = {},
            primaryMButtonControlsAvailable = true,
            mButtonVisibilityPolicy = policy,
            onStepMButtonVisibilityPolicy = steps::add,
            onDismiss = {},
        )

        assertTrue(dispatch(MButtonVisibilityPolicy.DISABLED, ControllerAction.NAV_LEFT))
        assertTrue(dispatch(MButtonVisibilityPolicy.ALWAYS_VISIBLE, ControllerAction.NAV_RIGHT))
        assertTrue(dispatch(MButtonVisibilityPolicy.ALWAYS_VISIBLE, ControllerAction.CONFIRM))
        assertTrue(steps.isEmpty())

        assertTrue(dispatch(MButtonVisibilityPolicy.DISABLED, ControllerAction.NAV_RIGHT))
        assertEquals(listOf(1), steps)
    }

    @Test
    fun navigationHintBoundaryInputIsSilentAndWriteFree() {
        val current = state(canWrite = true)
        val authority = DefaultFocusAuthority().apply {
            val key = brightnessFocusKey(BrightnessControlKey.NAVIGATION_HINTS)
            register(key, BrightnessScope, FocusBounds(0f, 0f, 100f, 50f))
            pushScope(BrightnessScope)
            requestFocus(key)
        }
        val steps = mutableListOf<Int>()

        fun dispatch(visibility: NavigationHintVisibility, action: ControllerAction) =
            dispatchBrightnessOverlay(
                authority = authority,
                state = current,
                action = action,
                onStep = { _, _ -> },
                onSetThemeAwareJoystickLight = {},
                onRequestWriteAccess = {},
                onRequestPrivilegedAccess = {},
                navigationHintVisibility = visibility,
                onStepNavigationHintVisibility = steps::add,
                onDismiss = {},
            )

        assertTrue(dispatch(NavigationHintVisibility.OFF, ControllerAction.NAV_LEFT))
        assertTrue(dispatch(NavigationHintVisibility.ON, ControllerAction.NAV_RIGHT))
        assertTrue(dispatch(NavigationHintVisibility.ON, ControllerAction.CONFIRM))
        assertTrue(steps.isEmpty())

        assertTrue(dispatch(NavigationHintVisibility.OFF, ControllerAction.NAV_RIGHT))
        assertEquals(listOf(1), steps)
    }


    @Test
    fun singleDisplayUtilityAddsOneMButtonPolicyRowButDualDisplayProjectionDoesNot() {
        val current = state(canWrite = true)

        assertEquals(
            listOf(
                // Unconditional in Utility, and now leading it: the file manager's destination is
                // Mimir's own browser, which every device has. Unlike OPEN_TWEAKER there is no
                // "device without one" state, so nothing above it here.
                BrightnessControlKey.OPEN_FILES,
                BrightnessControlKey.PRIMARY,
                BrightnessControlKey.SCREEN_TIMEOUT,
                BrightnessControlKey.M_BUTTON_VISIBILITY,
                BrightnessControlKey.NAVIGATION_HINTS,
                BrightnessControlKey.CALM_SHINE,
                // TEMPORARY row — the one-build scene comparison . It leaves this
            // list when the losing scene painters are deleted.
                BrightnessControlKey.CLOSE,
            ),
            brightnessControls(current, primaryMButtonControlsAvailable = true),
        )
        assertFalse(BrightnessControlKey.M_BUTTON_VISIBILITY in brightnessControls(current))
        assertTrue(BrightnessControlKey.NAVIGATION_HINTS in brightnessControls(current))
    }

    @Test
    fun tweakerRouteEntersTheUtilityRailOnlyWhenThereIsATweakerToReach() {
        val current = state(canWrite = true)

        // No Tweaker: no dead row. A generic handheld must not carry a control that can only ever
        // say UNAVAILABLE.
        assertFalse(
            BrightnessControlKey.OPEN_TWEAKER in brightnessControls(
                current,
                tweakerAvailability = TweakerUtilityAvailability.NOT_INSTALLED,
            ),
        )
        assertFalse(
            BrightnessControlKey.OPEN_TWEAKER in brightnessControls(
                current,
                tweakerAvailability = TweakerUtilityAvailability.UNAVAILABLE,
            ),
        )

        // A Tweaker that answers is reachable, even with nothing certified yet -- that surface is
        // where the player finds out why. Being in this list is what makes the row focusable at all;
        // rendering it without this entry would draw a row no controller could ever reach.
        listOf(
            TweakerUtilityAvailability.READY,
            TweakerUtilityAvailability.RESPONDING_STOCK_ONLY,
        ).forEach { availability ->
            assertTrue(
                "availability $availability must put the Tweaker route in the rail",
                BrightnessControlKey.OPEN_TWEAKER in brightnessControls(
                    current,
                    tweakerAvailability = availability,
                ),
            )
        }
    }

    /**
     * Utility is the ONLY door to `FileBrowserMode.MANAGE`, so the row
     * being present is a prerequisite for everything destructive that lands behind it. Unconditional in
     * Utility, unlike the Tweaker route: Mimir's own browser is the destination and every device has one.
     */
    @Test
    fun theFilesRouteIsOfferedOnEveryDeviceProfileUtilityCanDraw() {
        listOf(
            state(canWrite = true),
            state(canWrite = false),
            state(canWrite = true, light = true, color = true, haptics = true, ambient = true, media = true),
            state(canWrite = true, privileged = PrivilegedUtilityPosture.Unsupported),
        ).forEach { profile ->
            assertTrue(
                "Utility must always offer the Files route; the file manager has no hardware " +
                    "prerequisite and no 'device without one' state",
                BrightnessControlKey.OPEN_FILES in brightnessControls(profile),
            )
        }
        // No Tweaker anywhere in reach must not take Files with it -- they are unrelated destinations.
        assertTrue(
            BrightnessControlKey.OPEN_FILES in brightnessControls(
                state(canWrite = true),
                tweakerAvailability = TweakerUtilityAvailability.NOT_INSTALLED,
            ),
        )
    }

    /**
     * The row is the rail's terminal item, which is also what makes its "Storage" heading survive END
     * reveal. Asserted as a position, not merely as membership: this rail's own comments record that a
     * row listed at one index and drawn at another swallows the press meant for it.
     */
    @Test
    fun theTwoRoutesLeadTheRailAheadOfEverySetting() {
        //  They are the only
        // rows that leave Utility for somewhere else; everything below adjusts something in place.
        listOf(
            state(canWrite = true),
            state(canWrite = false, haptics = true, media = true),
            state(canWrite = true, light = true, color = true, haptics = true, ambient = true, media = true),
        ).forEach { profile ->
            val rail = brightnessRailControls(profile, tweakerAvailability = TweakerUtilityAvailability.READY)
            assertEquals(
                "the two routes must lead the rail, in this order, matching where BrightnessOverlay " +
                    "draws them -- the rail reveals BY INDEX, so a row listed early and drawn late " +
                    "swallows the press that should have reached it",
                listOf(BrightnessControlKey.OPEN_TWEAKER, BrightnessControlKey.OPEN_FILES),
                rail.take(2),
            )
        }
    }

    @Test
    fun theFilesRouteLeadsWhenThereIsNoTweakerToReachAtAll() {
        // The routes are independently conditional, so "top of the rail" must not quietly mean "second"
        // on a device that carries no Tweaker -- that device has no first row to hold the seat.
        val rail = brightnessRailControls(
            state(canWrite = true, media = true),
            tweakerAvailability = TweakerUtilityAvailability.NOT_INSTALLED,
        )
        assertEquals(
            "with no Tweaker paired, Files takes the lead rather than trailing a row that is not drawn",
            BrightnessControlKey.OPEN_FILES,
            rail.first(),
        )
        assertEquals(
            "and START reveal must follow it, so focusing the first row shows the rail's top edge",
            UtilityRailReveal.START,
            utilityRailReveal(BrightnessControlKey.OPEN_FILES, rail),
        )
    }

    @Test
    fun theFilesRouteBelongsToUtilityAndNotToTheTweakerPanel() {
        assertFalse(
            "the Tweaker panel is a device-control surface; the file manager is not one of its tools",
            BrightnessControlKey.OPEN_FILES in brightnessControls(
                state(canWrite = true),
                surface = UtilitySurface.TWEAKER,
                tweakerAvailability = TweakerUtilityAvailability.READY,
            ),
        )
    }

    /**
     * The native M-button panel draws this same Utility over the foreground app in its own window, with
     * its own FocusAuthority and no MainActivity behind it -- so `FileBrowserSurface` is not composed
     * and there is nothing to open. Withheld rather than offered-and-refused, on the same "no dead row"
     * rule the Tweaker route applies to a device with no Tweaker.
     *
     * The Tweaker route in the same call is the control: it stays, because a surface swap inside this
     * window IS reachable. Without that half, a bug that emptied the whole rail would pass this test.
     */
    @Test
    fun theFilesRouteIsWithheldFromTheNativePanelWhoseWindowHasNoBrowserToOpen() {
        val onPanel = brightnessControls(
            state(canWrite = true),
            tweakerAvailability = TweakerUtilityAvailability.READY,
            filesRouteAvailable = false,
        )
        assertFalse(
            "a panel with no FileBrowserSurface behind it must not carry a row that would publish a " +
                "controller bound to its own dead authority",
            BrightnessControlKey.OPEN_FILES in onPanel,
        )
        assertTrue(
            "control: the Tweaker route IS reachable from that window and must survive",
            BrightnessControlKey.OPEN_TWEAKER in onPanel,
        )
    }

    /** The eligibility rule itself, independent of any rail. */
    @Test
    fun theFilesRouteOpensOnlyWithAllFilesAccessAndOtherwiseAsksForIt() {
        assertEquals(
            FilesRouteDecision.OPEN,
            filesRouteDecision(hostsInAppSurfaces = true, allFilesAccessGranted = true),
        )
        // Not disabled, not hidden: pressing raises the grant screen. This row is the only regrant path
        // a player has, because the design's Permissions screen lives inside the manager the missing
        // permission is blocking.
        assertEquals(
            FilesRouteDecision.REQUEST_ALL_FILES_ACCESS,
            filesRouteDecision(hostsInAppSurfaces = true, allFilesAccessGranted = false),
        )
        // The host gate outranks the permission in both directions -- holding all-files access does not
        // make a browser exist in a window that has none.
        assertEquals(
            FilesRouteDecision.UNAVAILABLE,
            filesRouteDecision(hostsInAppSurfaces = false, allFilesAccessGranted = true),
        )
        assertEquals(
            FilesRouteDecision.UNAVAILABLE,
            filesRouteDecision(hostsInAppSurfaces = false, allFilesAccessGranted = false),
        )
    }

    /** Every state says something different, or the row cannot be telling the truth in all three. */
    @Test
    fun eachFilesRouteStateCarriesItsOwnPlayerFacingCopy() {
        val values = FilesRouteDecision.entries.map(::filesRouteValueLabel)
        val supporting = FilesRouteDecision.entries.map(::filesRouteSupportingText)
        assertEquals(values.toSet().size, values.size)
        assertEquals(supporting.toSet().size, supporting.size)
        // Utility also holds the Tweaker and diagnostics. The file-browser entry presents a player tool
        // without diagnostic chrome.
        val diagnosticVocabulary = listOf(
            "posture", "telemetry", "privileged", "daemon", "adb", "debug", "diagnostic",
        )
        supporting.forEach { line ->
            diagnosticVocabulary.forEach { word ->
                assertFalse(
                    "Files must read as a player tool, but its copy says '$word': $line",
                    line.lowercase().contains(word),
                )
            }
        }
        // Opening documentsui was reported to spike the fan; this row must not offer it as
        // an alternative shortcut.
        supporting.forEach { line ->
            assertFalse(line.lowercase().contains("system picker"))
            assertFalse(line.lowercase().contains("files by google"))
        }
    }

    @Test
    fun theTweakerRouteBelongsToUtilityAndNotToTheTweakerSurfaceItOpens() {
        val current = state(canWrite = true)
        assertFalse(
            "the Tweaker panel must not offer a route back into itself",
            BrightnessControlKey.OPEN_TWEAKER in brightnessControls(
                current,
                surface = UtilitySurface.TWEAKER,
                tweakerAvailability = TweakerUtilityAvailability.READY,
            ),
        )
    }

    @Test
    fun railTerminalFocusRevealsTheCompleteContentEdge() {
        val rail = listOf(
            BrightnessControlKey.PRIMARY,
            BrightnessControlKey.SCREEN_TIMEOUT,
            BrightnessControlKey.MEDIA_VOLUME,
        )

        assertEquals(UtilityRailReveal.START, utilityRailReveal(BrightnessControlKey.PRIMARY, rail))
        assertEquals(UtilityRailReveal.ROW, utilityRailReveal(BrightnessControlKey.SCREEN_TIMEOUT, rail))
        assertEquals(UtilityRailReveal.END, utilityRailReveal(BrightnessControlKey.MEDIA_VOLUME, rail))
        assertEquals(UtilityRailReveal.NONE, utilityRailReveal(BrightnessControlKey.CLOSE, rail))
        assertEquals(UtilityRailReveal.NONE, utilityRailReveal(null, rail))
    }

    @Test
    fun middleUtilityRowsCarryThePanelToAStableReadingSeat() {
        assertEquals(
            350,
            utilityRailSettlementTarget(
                reveal = UtilityRailReveal.ROW,
                item = UtilityRailItemGeometry(topPx = 500, heightPx = 100),
                viewportHeightPx = 400,
                maxScrollPx = 900,
            ),
        )
        assertEquals(
            0,
            utilityRailSettlementTarget(
                reveal = UtilityRailReveal.START,
                item = null,
                viewportHeightPx = 400,
                maxScrollPx = 900,
            ),
        )
        assertEquals(
            900,
            utilityRailSettlementTarget(
                reveal = UtilityRailReveal.END,
                item = null,
                viewportHeightPx = 400,
                maxScrollPx = 900,
            ),
        )
        assertNull(
            utilityRailSettlementTarget(
                reveal = UtilityRailReveal.NONE,
                item = null,
                viewportHeightPx = 400,
                maxScrollPx = 900,
            ),
        )
    }

    @Test
    fun fixedFooterControlsAreNeverIncludedInTheScrollingRail() {
        val current = state(canWrite = false, haptics = true, media = true)

        assertEquals(
            listOf(
                BrightnessControlKey.OPEN_FILES,
                BrightnessControlKey.PRIMARY,
                BrightnessControlKey.SCREEN_TIMEOUT,
                BrightnessControlKey.NAVIGATION_HINTS,
                BrightnessControlKey.CALM_SHINE,
                // TEMPORARY row — the one-build scene comparison . It leaves this
            // list when the losing scene painters are deleted.
                BrightnessControlKey.MIMIR_HAPTIC,
                BrightnessControlKey.MEDIA_VOLUME,
            ),
            brightnessRailControls(current),
        )
    }

    @Test
    fun readOnlyRenderedRowsRemainInTheRailAndItsVisualLastRowOwnsEndReveal() {
        val readOnly = state(
            secondary = SecondaryBrightnessSupport.AYN_SYSTEM,
            light = true,
            color = true,
            haptics = true,
            media = true,
            mediaFixed = true,
            privileged = PrivilegedUtilityPosture.Unsupported,
            secondaryWindow = false,
        )

        assertFalse(readOnly.capabilities.canAdjustSecondary)
        assertFalse(readOnly.capabilities.canAdjustJoystickLight)
        assertFalse(readOnly.capabilities.canAdjustJoystickColor)
        assertFalse(readOnly.capabilities.canAdjustMediaVolume)
        assertEquals(
            listOf(
                BrightnessControlKey.OPEN_FILES,
                BrightnessControlKey.PRIMARY,
                BrightnessControlKey.SECONDARY,
                BrightnessControlKey.SCREEN_TIMEOUT,
                BrightnessControlKey.NAVIGATION_HINTS,
                BrightnessControlKey.CALM_SHINE,
                // TEMPORARY row — the one-build scene comparison . It leaves this
            // list when the losing scene painters are deleted.
                BrightnessControlKey.JOYSTICK_LIGHT,
                BrightnessControlKey.JOYSTICK_COLOR,
                BrightnessControlKey.MIMIR_HAPTIC,
                BrightnessControlKey.MEDIA_VOLUME,
            ),
            brightnessRailControls(readOnly),
        )
        // END reveal follows the VISUAL last row, which is this test's actual subject. Device media
        // volume has that seat BACK: the Files route took it when Files was added at the rail tail, and
        // the 2026-08-15 move to the top returned it. The pairing below is what proves the render and
        // the projection moved together -- a rail whose computed terminal differs from its drawn
        // terminal is how END reveal scrolled the focused row off-screen before.
        assertEquals(
            UtilityRailReveal.END,
            utilityRailReveal(BrightnessControlKey.MEDIA_VOLUME, brightnessRailControls(readOnly)),
        )
        assertEquals(
            UtilityRailReveal.START,
            utilityRailReveal(BrightnessControlKey.OPEN_FILES, brightnessRailControls(readOnly)),
        )
    }

    @Test
    fun genericDeviceExposesOnlyPortableRowsAndWriteAccessAction() {
        val controls = brightnessControls(
            state(
                canWrite = false,
                haptics = true,
                media = true,
            ),
        )

        assertEquals(
            listOf(
                // A generic handheld carries no vendor controls and no Tweaker, and still gets the file
                // manager -- it needs no hardware to work. With no Tweaker route drawn, it leads alone.
                BrightnessControlKey.OPEN_FILES,
                BrightnessControlKey.PRIMARY,
                BrightnessControlKey.SCREEN_TIMEOUT,
                BrightnessControlKey.NAVIGATION_HINTS,
                BrightnessControlKey.CALM_SHINE,
                // TEMPORARY row — the one-build scene comparison . It leaves this
            // list when the losing scene painters are deleted.
                BrightnessControlKey.MIMIR_HAPTIC,
                BrightnessControlKey.MEDIA_VOLUME,
                BrightnessControlKey.ALLOW_SYSTEM_CHANGES,
                BrightnessControlKey.CLOSE,
            ),
            controls,
        )
    }

    @Test
    fun ambientSoundLivesInFeedbackBeforeDeviceVolume() {
        val controls = brightnessControls(
            state(
                ambient = true,
                media = true,
            ),
        )

        assertTrue(BrightnessControlKey.AMBIENT_SOUND in controls)
        assertTrue(
            controls.indexOf(BrightnessControlKey.AMBIENT_SOUND) <
                controls.indexOf(BrightnessControlKey.MEDIA_VOLUME),
        )
    }

    @Test
    fun thorAndRetroidHaveDeterministicIndependentVendorRows() {
        val thor = brightnessControls(
            state(
                secondary = SecondaryBrightnessSupport.AYN_SYSTEM,
                light = true,
                color = true,
                haptics = true,
                media = true,
                privileged = PrivilegedUtilityPosture(
                    status = PrivilegedUtilityStatus.READY,
                    canWriteVendorSettings = true,
                    canControlSecondaryBrightness = true,
                ),
                secondaryWindow = true,
            ),
        )
        val retroid = brightnessControls(
            state(
                light = true,
                color = true,
                haptics = true,
                media = true,
                privileged = PrivilegedUtilityPosture(
                    status = PrivilegedUtilityStatus.READY,
                    canWriteVendorSettings = true,
                ),
            ),
        )

        // The second display's row sits immediately after the primary one, which is the fact worth
        // pinning -- stated as a relationship rather than as `thor[1]`, because the absolute index moved
        // when the two routes took the head of the rail on 2026-08-15 and would move again next time.
        assertEquals(
            BrightnessControlKey.SECONDARY,
            thor[thor.indexOf(BrightnessControlKey.PRIMARY) + 1],
        )
        assertFalse(BrightnessControlKey.SECONDARY in retroid)
        assertEquals(
            listOf(BrightnessControlKey.JOYSTICK_LIGHT, BrightnessControlKey.JOYSTICK_COLOR),
            retroid.filter { it == BrightnessControlKey.JOYSTICK_LIGHT || it == BrightnessControlKey.JOYSTICK_COLOR },
        )
        // CF01: the joystick theme-follow feature was discarded — the row never appears.
        assertFalse(BrightnessControlKey.THEME_AWARE_LIGHT in thor)
        assertFalse(BrightnessControlKey.THEME_AWARE_LIGHT in retroid)
        assertFalse("Tweaker WIP is display-only and never focusable", thor.size > thor.distinct().size)
    }

    @Test
    fun tweakerProfileRowIsPermanentOnTweakerSurfaceAndAbsentFromUtility() {
        val controls = brightnessControls(
            state(canWrite = true, media = true),
            surface = UtilitySurface.TWEAKER,
        )
        assertTrue(BrightnessControlKey.TWEAKER_PROFILE in controls)
        assertTrue(
            BrightnessControlKey.TWEAKER_PROFILE in brightnessRailControls(
                state(canWrite = false),
                surface = UtilitySurface.TWEAKER,
            ),
        )
        assertFalse(BrightnessControlKey.TWEAKER_PROFILE in brightnessControls(state(canWrite = true)))
        val bare = brightnessControls(
            state(canWrite = false, media = false),
            surface = UtilitySurface.TWEAKER,
        )
        assertEquals(1, bare.count { it == BrightnessControlKey.TWEAKER_PROFILE })
    }

    @Test
    fun tweakerUsageAccessIsFocusableOnlyForAnExplicitDenial() {
        val current = state(canWrite = true, media = true)

        assertTrue(
            BrightnessControlKey.TWEAKER_USAGE_ACCESS in brightnessControls(
                current,
                tweakerUsageAccessState = TweakerUsageAccessState.SET_UP,
                surface = UtilitySurface.TWEAKER,
            ),
        )
        assertFalse(
            BrightnessControlKey.TWEAKER_USAGE_ACCESS in brightnessControls(
                current,
                tweakerUsageAccessState = TweakerUsageAccessState.READY,
                surface = UtilitySurface.TWEAKER,
            ),
        )
        assertFalse(
            BrightnessControlKey.TWEAKER_USAGE_ACCESS in brightnessControls(
                current,
                surface = UtilitySurface.TWEAKER,
            ),
        )
    }

    @Test
    fun manualFanRowsExistOnlyForTypedCapabilityAndManualAddsCurveFocus() {
        val current = state(canWrite = false)
        val auto = manualFan(TweakerFanMode.AUTO)
        val autoControls = brightnessControls(
            current,
            surface = UtilitySurface.TWEAKER,
            tweakerManualFan = auto,
        )
        assertTrue(BrightnessControlKey.TWEAKER_FAN_MODE in autoControls)
        assertFalse(BrightnessControlKey.TWEAKER_FAN_CURVE in autoControls)

        val manualControls = brightnessControls(
            current,
            surface = UtilitySurface.TWEAKER,
            tweakerManualFan = manualFan(TweakerFanMode.MANUAL),
        )
        assertTrue(BrightnessControlKey.TWEAKER_FAN_MODE in manualControls)
        assertTrue(BrightnessControlKey.TWEAKER_FAN_CURVE in manualControls)
        assertFalse(
            BrightnessControlKey.TWEAKER_FAN_MODE in brightnessControls(
                current,
                surface = UtilitySurface.TWEAKER,
            ),
        )
    }

    /**
     * No new BrightnessControlKey is needed. The rail's stop count and order stay unchanged;
     * TWEAKER_FAN_CURVE remains one stop. The temperature readout is inert text within that row,
     * not a control of its own. This pins the full enum (a future readout-as-its-own-stop regression would
     * add or reorder an entry) and the rail adjacency under MANUAL (a single TWEAKER_FAN_CURVE stop,
     * immediately after TWEAKER_FAN_MODE, exactly as before this ticket).
     */
    @Test
    fun fanTemperatureReadoutAddsNoNewRailControlKeyAndRailOrderIsUnchanged() {
        assertEquals(
            listOf(
                BrightnessControlKey.PRIMARY,
                BrightnessControlKey.SECONDARY,
                BrightnessControlKey.SCREEN_TIMEOUT,
                BrightnessControlKey.M_BUTTON_VISIBILITY,
                BrightnessControlKey.NAVIGATION_HINTS,
                // Calm shine : a switch row of its own, right after Navigation hints; the fan
                // curve is still exactly one stop, and no readout became a key.
                BrightnessControlKey.CALM_SHINE,
                // TEMPORARY row — the one-build scene comparison . It leaves this
            // list when the losing scene painters are deleted.
                BrightnessControlKey.JOYSTICK_LIGHT,
                BrightnessControlKey.JOYSTICK_COLOR,
                BrightnessControlKey.THEME_AWARE_LIGHT,
                BrightnessControlKey.MIMIR_HAPTIC,
                BrightnessControlKey.NAV_SOUND,
                BrightnessControlKey.AMBIENT_SOUND,
                BrightnessControlKey.MEDIA_VOLUME,
                BrightnessControlKey.TWEAKER_PROFILE,
                BrightnessControlKey.TWEAKER_FPS_CAP,
                BrightnessControlKey.TWEAKER_FAN_MODE,
                BrightnessControlKey.TWEAKER_FAN_CURVE,
                BrightnessControlKey.PERFORMANCE_OVERLAY,
                BrightnessControlKey.OPEN_TWEAKER,
                BrightnessControlKey.OPEN_FILES,
                BrightnessControlKey.TWEAKER_USAGE_ACCESS,
                BrightnessControlKey.PRIVILEGED_ACCESS,
                BrightnessControlKey.ALLOW_SYSTEM_CHANGES,
                BrightnessControlKey.CLOSE,
            ),
            BrightnessControlKey.entries,
        )

        val current = state(canWrite = false)
        val rail = brightnessRailControls(
            current,
            surface = UtilitySurface.TWEAKER,
            tweakerManualFan = manualFan(TweakerFanMode.MANUAL),
        )
        assertEquals(
            "TWEAKER_FAN_CURVE must remain exactly one rail stop, immediately after TWEAKER_FAN_MODE",
            1,
            rail.count { it == BrightnessControlKey.TWEAKER_FAN_CURVE },
        )
        val modeIndex = rail.indexOf(BrightnessControlKey.TWEAKER_FAN_MODE)
        val curveIndex = rail.indexOf(BrightnessControlKey.TWEAKER_FAN_CURVE)
        assertEquals(modeIndex + 1, curveIndex)
    }

    @Test
    fun manualFanProjectionRejectsStockAndStaleProfileCapabilities() {
        val advanced = TweakerAdvancedCapabilities(
            manualFan = manualFan(TweakerFanMode.AUTO),
            frameLimitsHz = emptyList(),
            perGameOverridesAvailable = false,
        )
        val stock = TweakerUtilityRowState(
            availability = TweakerUtilityAvailability.READY,
            selectedProfileId = "stock",
            selectableProfileIds = listOf("stock", "quiet"),
        )
        val quiet = stock.copy(selectedProfileId = "quiet")

        assertNull(matchedTweakerAdvancedCapabilities(stock, advanced)?.manualFan)
        assertEquals(
            advanced.manualFan,
            matchedTweakerAdvancedCapabilities(quiet, advanced)?.manualFan,
        )
        assertNull(
            matchedTweakerAdvancedCapabilities(
                quiet,
                advanced.copy(manualFan = manualFan(TweakerFanMode.AUTO, profileId = "balanced")),
            )?.manualFan,
        )
    }

    @Test
    fun fanCurveGeometrySnapsSymmetricallyToBoundedServiceSteps() {
        assertEquals(-100, tweakerFanCurveBiasFromFraction(0f))
        assertEquals(0, tweakerFanCurveBiasFromFraction(0.5f))
        assertEquals(100, tweakerFanCurveBiasFromFraction(1f))
        assertEquals("Cooler · 4", tweakerFanCurveLabel(-40))
        assertEquals("Standard", tweakerFanCurveLabel(0))
        assertEquals("Quieter · 4", tweakerFanCurveLabel(40))
        assertEquals(
            "Fan speed rises earlier. Lower temperatures, more fan noise.",
            tweakerFanCurveSupportingText(-40),
        )
        assertEquals(
            "Mimir automatically balances temperature and fan noise for this profile.",
            tweakerFanCurveSupportingText(0),
        )
        assertEquals(
            "Fan speed rises later. Less fan noise, higher temperatures.",
            tweakerFanCurveSupportingText(40),
        )
        assertEquals("0.8", tweakerFanBiasDegrees(10))
        assertEquals("8.0", tweakerFanBiasDegrees(100))
        assertEquals("Cooler, level 4 of 10", tweakerFanCurveAccessibilityLabel(-40))
        assertEquals("Standard", tweakerFanCurveAccessibilityLabel(0))
        assertEquals("Quieter, level 4 of 10", tweakerFanCurveAccessibilityLabel(40))

        val typedRange = -40..60
        assertEquals(-40, tweakerFanCurveBiasFromFraction(0f, typedRange, 20))
        assertEquals(20, tweakerFanCurveBiasFromFraction(0.5f, typedRange, 20))
        assertEquals(60, tweakerFanCurveBiasFromFraction(1f, typedRange, 20))
        assertEquals(0.6f, tweakerFanCurveFraction(20, typedRange), 0.001f)
    }

    @Test
    fun fanCurveDraftUsesTypedStepsAndSurvivesOnlyEquivalentCapabilityRefresh() {
        val fan = manualFan(
            mode = TweakerFanMode.MANUAL,
            currentCurveBias = 0,
            curveBiasRange = -40..60,
            curveBiasStep = 20,
        )
        val first = steppedTweakerFanCurveDraft(null, fan, 1)
        assertEquals(20, first?.previewBias)
        val second = steppedTweakerFanCurveDraft(first, fan, 1)
        assertEquals(40, second?.previewBias)
        val bounded = steppedTweakerFanCurveDraft(second, fan, 1)
        assertEquals(60, bounded?.previewBias)
        assertEquals(bounded, steppedTweakerFanCurveDraft(bounded, fan, 1))

        // Periodic readback metadata may advance without invalidating an otherwise identical preview.
        assertTrue(second?.matches(fan.copy(generation = 2, transactionId = "tx-2")) == true)
        assertFalse(second?.matches(fan.copy(currentCurveBias = 20)) == true)
        assertFalse(second?.matches(fan.copy(profileId = "balanced")) == true)
        assertFalse(second?.matches(fan.copy(currentMode = TweakerFanMode.AUTO)) == true)
        assertFalse(second?.matches(fan.copy(curveBiasRange = -20..60)) == true)
        assertFalse(second?.matches(fan.copy(curveBiasStep = 10)) == true)
        assertFalse(second?.matches(fan.copy(curveSensorType = "CPU")) == true)
        assertFalse(second?.matches(fan.copy(ownerRequestId = "another-owner")) == true)

        assertNull(updatedTweakerFanCurveDraft(second, fan, 0))
        assertEquals(60, updatedTweakerFanCurveDraft(second, fan, 57)?.previewBias)
    }

    @Test
    fun fanCurveControllerPreviewsThenAppliesOrCancelsWithoutMovingFocus() {
        val current = state(canWrite = true)
        val fan = manualFan(TweakerFanMode.MANUAL)
        val curveKey = brightnessFocusKey(BrightnessControlKey.TWEAKER_FAN_CURVE)
        val modeKey = brightnessFocusKey(BrightnessControlKey.TWEAKER_FAN_MODE)
        val authority = DefaultFocusAuthority().apply {
            register(modeKey, BrightnessScope, FocusBounds(0f, 0f, 100f, 50f))
            register(curveKey, BrightnessScope, FocusBounds(0f, 0f, 100f, 50f))
            pushScope(BrightnessScope)
            requestFocus(curveKey)
        }
        val previewSteps = mutableListOf<Int>()
        var applies = 0
        var cancels = 0
        var dismissals = 0

        fun dispatch(action: ControllerAction, dirty: Boolean) = dispatchBrightnessOverlay(
            authority = authority,
            state = current,
            action = action,
            onStep = { _, _ -> },
            onSetThemeAwareJoystickLight = {},
            onRequestWriteAccess = {},
            onRequestPrivilegedAccess = {},
            tweakerManualFan = fan,
            onStepTweakerFanCurve = { direction ->
                previewSteps += direction
                true
            },
            tweakerFanCurveDraftPending = dirty,
            onApplyTweakerFanCurve = { applies += 1; true },
            onCancelTweakerFanCurve = { cancels += 1; true },
            surface = UtilitySurface.TWEAKER,
            onDismiss = { dismissals += 1 },
        )

        // A clean confirm is intentionally inert; only a visible draft may mutate the helper.
        assertTrue(dispatch(ControllerAction.CONFIRM, dirty = false))
        assertEquals(0, applies)

        assertTrue(dispatch(ControllerAction.NAV_RIGHT, dirty = false))
        assertTrue(dispatch(ControllerAction.NAV_RIGHT, dirty = true))
        assertEquals(listOf(1, 1), previewSteps)
        assertEquals(0, applies)
        assertEquals(curveKey, authority.state.value.focused)

        assertTrue(dispatch(ControllerAction.CONFIRM, dirty = true))
        assertEquals(1, applies)
        assertEquals(0, cancels)

        assertTrue(dispatch(ControllerAction.NAV_UP, dirty = true))
        assertEquals(curveKey, authority.state.value.focused)
        assertTrue(dispatch(ControllerAction.CANCEL, dirty = true))
        assertEquals(1, cancels)
        assertEquals(0, dismissals)

        assertTrue(dispatch(ControllerAction.SYSTEM_BACK, dirty = false))
        assertEquals(1, dismissals)
    }

    private fun manualFan(
        mode: TweakerFanMode,
        profileId: String = "quiet",
        currentCurveBias: Int = 0,
        curveBiasRange: IntRange = -100..100,
        curveBiasStep: Int = 10,
    ) = TweakerManualFanCapability(
        modes = TweakerFanMode.entries.toSet(),
        curveBiasRange = curveBiasRange,
        curveBiasStep = curveBiasStep,
        curveSensorType = "GPU",
        currentMode = mode,
        currentCurveBias = currentCurveBias,
        ownerRequestId = "mimir.system.1",
        generation = 1,
        transactionId = "tx-1",
        profileId = profileId,
    )

    @Test
    fun vendorRowsMoveOnlyThroughAReadyPrivilegedBridge() {
        val state = state(
            canWrite = true,
            secondary = SecondaryBrightnessSupport.AYN_SYSTEM,
            light = true,
            color = true,
            media = true,
            privileged = PrivilegedUtilityPosture(
                status = PrivilegedUtilityStatus.READY,
                canWriteVendorSettings = true,
                canControlSecondaryBrightness = true,
            ),
            secondaryWindow = true,
        )

        assertTrue(state.canAdjustUtilityTarget(BrightnessTarget.SECONDARY))
        assertTrue(state.canAdjustUtilityTarget(BrightnessTarget.JOYSTICK_LIGHT))
        assertTrue(state.canAdjustUtilityTarget(BrightnessTarget.JOYSTICK_COLOR))
        assertTrue(state.canAdjustUtilityTarget(BrightnessTarget.MEDIA_VOLUME))
    }

    @Test
    fun unavailableVendorStatesDoNotOfferTheRemovedAlternateSetup() {
        fun message(status: PrivilegedUtilityStatus): String? = privilegedUtilityMessage(
            state(
                light = true,
                color = true,
                privileged = PrivilegedUtilityPosture(status),
            ),
        )

        assertEquals("These device controls need special access. Set it up below.", message(PrivilegedUtilityStatus.MANAGER_MISSING))
        assertEquals("These device controls are unavailable on this device.", message(PrivilegedUtilityStatus.SERVICE_STOPPED))
        assertEquals(
            "Allow Mimir in special access to enable these device controls.",
            message(PrivilegedUtilityStatus.PERMISSION_REQUIRED),
        )
        assertEquals("Mimir isn’t allowed to use special access.", message(PrivilegedUtilityStatus.PERMISSION_DENIED))
        assertEquals(
            "These device controls aren’t supported on this Android version.",
            message(PrivilegedUtilityStatus.API_UNSUPPORTED),
        )
        assertEquals(
            "Special access is connected, but Mimir can’t verify these controls on this device.",
            message(PrivilegedUtilityStatus.READBACK_UNSATISFIABLE),
        )
        assertEquals("Unavailable", privilegedUtilityActionLabel(PrivilegedUtilityStatus.MANAGER_MISSING))
        assertEquals("Allow", privilegedUtilityActionLabel(PrivilegedUtilityStatus.PERMISSION_REQUIRED))
        assertEquals("Unavailable", privilegedUtilityActionLabel(PrivilegedUtilityStatus.CONNECTION_FAILED))
        assertEquals("Unavailable", privilegedUtilityActionLabel(PrivilegedUtilityStatus.SERVICE_STOPPED))
        assertEquals("Unavailable", privilegedUtilityActionLabel(PrivilegedUtilityStatus.PERMISSION_DENIED))

        assertNull(
            privilegedUtilityMessage(
                state(
                    light = true,
                    color = true,
                    privileged = PrivilegedUtilityPosture(
                        status = PrivilegedUtilityStatus.READY,
                        canWriteVendorSettings = true,
                    ),
                ),
            ),
        )
    }

    /**
     * QA-11 . Two states used to share "This device doesn’t offer this
     * control.", and on the Thor that sentence was false in both — the same run logged
     * `MimirPrivilege: vendor route admitted`, and the controls worked either side of the message.
     * Worse, one of them was reached by pressing the "Allow" the supplemental offer puts on screen.
     *
     * This pins the two remaining reasons apart and pins what they may NOT say. The device-capability
     * claim belongs to UNSUPPORTED_DEVICE alone, which is the only one that is actually about the device.
     */
    @Test
    fun capabilityCopyBlamesTheRightActorAndNeverTheDevice() {
        fun message(posture: PrivilegedUtilityPosture) = privilegedUtilityMessage(
            state(light = true, color = true, privileged = posture),
        )

        // The former supplemental route is absent, so this posture reports an unavailable route.
        val remoteMissing = message(
            PrivilegedUtilityPosture(PrivilegedUtilityStatus.REMOTE_PERMISSION_MISSING),
        )
        assertEquals(
            "These device controls are unavailable on this device.",
            remoteMissing,
        )

        // READY on an access that can read the vendor control but not write it
        // (BrightnessCapability:135-139) — readable, not absent.
        val readOnly = message(
            PrivilegedUtilityPosture(
                status = PrivilegedUtilityStatus.READY,
                canControlSecondaryBrightness = true,
            ),
        )
        assertEquals(
            "Some of these controls can be read but not changed with the access Mimir currently holds.",
            readOnly,
        )

        // A ready vendor route with no secondary capability has no supplemental authority to offer.
        val supplemental = message(
            PrivilegedUtilityPosture(
                status = PrivilegedUtilityStatus.READY,
                canWriteVendorSettings = true,
                authority = PrivilegedUtilityAuthority.CERTIFIED_VENDOR,
                supplementalAccessStatus = PrivilegedUtilityStatus.PERMISSION_REQUIRED,
            ),
        )
        assertEquals(remoteMissing, supplemental)

        // The read-only case remains distinct from an unavailable route.
        assertEquals(2, setOf(remoteMissing, readOnly).size)

        // None of the three may tell the player the hardware cannot do it. Only UNSUPPORTED_DEVICE may,
        // and it still does.
        listOf(remoteMissing, readOnly).forEach {
            assertFalse(it.orEmpty().contains("offer this control"))
        }
        assertEquals(
            "These device controls are read-only on this device.",
            message(PrivilegedUtilityPosture(PrivilegedUtilityStatus.UNSUPPORTED_DEVICE)),
        )
    }

    @Test
    fun requestedOrPendingThemeRestoreNeverResurfacesTheDiscardedToggle() {
        // CF01: even a device stuck with the legacy pref requested/pending gets NO toggle row —
        // the startup migration restores the original LED color; the UI never offers follow again.
        val requested = brightnessControls(state(themeRequested = true))
        val pending = brightnessControls(state(themeRestorePending = true))

        assertFalse(BrightnessControlKey.THEME_AWARE_LIGHT in requested)
        assertFalse(BrightnessControlKey.THEME_AWARE_LIGHT in pending)
    }

    @Test
    fun secondaryLabelDistinguishesVendorReadFromMimirWindowFallback() {
        assertEquals(
            "Bottom screen",
            secondaryBrightnessLabel(state(secondary = SecondaryBrightnessSupport.AYN_SYSTEM)),
        )
        assertEquals(
            "Bottom screen",
            secondaryBrightnessLabel(
                state(
                    canWrite = false,
                    secondary = SecondaryBrightnessSupport.AYN_SYSTEM,
                ),
            ),
        )
        assertEquals(
            "Bottom screen",
            secondaryBrightnessLabel(
                state(
                    secondary = SecondaryBrightnessSupport.AYN_SYSTEM,
                    secondaryWindowFallback = 0.8f,
                ),
            ),
        )
        assertEquals(
            "Bottom screen (Mimir only)",
            secondaryBrightnessLabel(state(secondary = SecondaryBrightnessSupport.WINDOW_ONLY)),
        )
    }

    @Test
    fun allowActionAppearsOnlyWhenMainSystemAccessCanChangeBehavior() {
        assertTrue(
            BrightnessControlKey.ALLOW_SYSTEM_CHANGES in state(
                canWrite = false,
                mainWriteAccessUseful = true,
            ).let(::brightnessControls),
        )
        assertFalse(
            BrightnessControlKey.ALLOW_SYSTEM_CHANGES in state(
                canWrite = false,
                mainWriteAccessUseful = false,
            ).let(::brightnessControls),
        )
        assertFalse(
            BrightnessControlKey.ALLOW_SYSTEM_CHANGES in state(
                canWrite = true,
                mainWriteAccessUseful = true,
            ).let(::brightnessControls),
        )
    }

    @Test
    fun tweakerProfileStepsADraftAndConfirmAppliesItLikeTheRowsBeside() {
        val current = state(canWrite = true)
        val profileKey = brightnessFocusKey(BrightnessControlKey.TWEAKER_PROFILE)
        val overlayKey = brightnessFocusKey(BrightnessControlKey.PERFORMANCE_OVERLAY)
        val authority = DefaultFocusAuthority().apply {
            register(profileKey, BrightnessScope, FocusBounds(0f, 0f, 100f, 50f))
            register(overlayKey, BrightnessScope, FocusBounds(0f, 60f, 100f, 110f))
            pushScope(BrightnessScope)
            requestFocus(profileKey)
        }
        val steps = mutableListOf<Int>()
        var applies = 0
        var cancels = 0
        var dismissals = 0

        fun dispatch(action: ControllerAction, draftPending: Boolean) = dispatchBrightnessOverlay(
            authority = authority,
            state = current,
            action = action,
            onStep = { _, _ -> },
            onSetThemeAwareJoystickLight = {},
            onRequestWriteAccess = {},
            onRequestPrivilegedAccess = {},
            onStepTweakerProfile = { direction -> steps += direction; true },
            tweakerProfileDraftPending = draftPending,
            onApplyTweakerProfile = { applies += 1; true },
            onCancelTweakerProfile = { cancels += 1; true },
            surface = UtilitySurface.TWEAKER,
            onDismiss = { dismissals += 1 },
        )

        // Every press reaches the coordinator — including the ones that will turn out to be no-ops. The
        // dispatcher must NOT swallow them: an unreachable Tweaker or a stock-only device still owes the
        // player an honest line, and only the coordinator knows which line that is.
        assertTrue(dispatch(ControllerAction.NAV_LEFT, draftPending = false))
        assertTrue(dispatch(ControllerAction.NAV_RIGHT, draftPending = true))
        assertEquals(listOf(-1, 1), steps)
        assertEquals(0, applies)

        // A clean confirm is inert; only a visible draft may open a settlement.
        assertTrue(dispatch(ControllerAction.CONFIRM, draftPending = false))
        assertEquals(0, applies)

        assertTrue(dispatch(ControllerAction.CONFIRM, draftPending = true))
        assertEquals(1, applies)

        // The drafted row owns vertical nav and Back until the player decides.
        assertTrue(dispatch(ControllerAction.NAV_DOWN, draftPending = true))
        assertEquals(profileKey, authority.state.value.focused)
        assertTrue(dispatch(ControllerAction.CANCEL, draftPending = true))
        assertEquals(1, cancels)
        assertEquals(0, dismissals)

        // Undrafted, the row releases both again.
        assertTrue(dispatch(ControllerAction.NAV_DOWN, draftPending = false))
        assertEquals(overlayKey, authority.state.value.focused)
        assertTrue(dispatch(ControllerAction.SYSTEM_BACK, draftPending = false))
        assertEquals(1, dismissals)
    }

    @Test
    fun profileDraftDecisionOpensATransactionOnlyForARealDestination() {
        val row = TweakerUtilityRowState(
            availability = TweakerUtilityAvailability.READY,
            selectedProfileId = "stock",
            selectableProfileIds = listOf("stock", "quiet", "balanced"),
        )

        assertEquals(
            TweakerProfileApplyDecision.NoDraft,
            tweakerProfileApplyDecision(row, null),
        )
        assertEquals(
            TweakerProfileApplyDecision.Apply("quiet"),
            tweakerProfileApplyDecision(row, TweakerProfileDraft("quiet")),
        )
        assertEquals(
            TweakerProfileApplyDecision.AlreadyActive("stock"),
            tweakerProfileApplyDecision(row, TweakerProfileDraft("stock")),
        )
        // A profile the row no longer admits is refused, never quietly swapped for a neighbour.
        assertEquals(
            TweakerProfileApplyDecision.Unavailable("performance"),
            tweakerProfileApplyDecision(row, TweakerProfileDraft("performance")),
        )
        assertEquals(
            TweakerProfileApplyDecision.Unavailable("quiet"),
            tweakerProfileApplyDecision(
                row.copy(availability = TweakerUtilityAvailability.UNAVAILABLE),
                TweakerProfileDraft("quiet"),
            ),
        )
    }

    @Test
    fun profileDraftSteppingWalksThePreviewAndKeepsEveryBoundaryHonest() {
        val row = TweakerUtilityRowState(
            availability = TweakerUtilityAvailability.READY,
            selectedProfileId = "stock",
            selectableProfileIds = listOf("stock", "quiet", "balanced"),
        )

        // Steps compound off the PREVIEW, not off the live profile — that is what makes travelling free.
        val first = steppedTweakerProfileDraftOutcome(null, row, 1)
        assertEquals(TweakerUtilityStepOutcome.Changed("quiet"), first)
        assertEquals(
            TweakerUtilityStepOutcome.Changed("balanced"),
            steppedTweakerProfileDraftOutcome(TweakerProfileDraft("quiet"), row, 1),
        )
        // Wrapping back onto the running profile leaves nothing to decide.
        assertNull(tweakerProfileDraftFor(row, "stock"))
        assertEquals(TweakerProfileDraft("quiet"), tweakerProfileDraftFor(row, "quiet"))

        // A stale preview the row no longer admits does not become the step origin.
        assertEquals(
            TweakerUtilityStepOutcome.Changed("quiet"),
            steppedTweakerProfileDraftOutcome(TweakerProfileDraft("performance"), row, 1),
        )

        // Availability boundaries keep answering with their own line instead of being swallowed.
        assertEquals(
            TweakerUtilityStepOutcome.NotInstalled,
            steppedTweakerProfileDraftOutcome(
                null,
                TweakerUtilityRowState(availability = TweakerUtilityAvailability.NOT_INSTALLED),
                1,
            ),
        )
        assertEquals(
            TweakerUtilityStepOutcome.NoOtherProfiles,
            steppedTweakerProfileDraftOutcome(
                null,
                TweakerUtilityRowState(availability = TweakerUtilityAvailability.READY),
                1,
            ),
        )
    }

    @Test
    fun profileRowCopyNamesTheTargetAndFallsBackToAvailabilityTruth() {
        val ready = TweakerUtilityRowState(
            availability = TweakerUtilityAvailability.READY,
            selectedProfileId = "stock",
            selectableProfileIds = listOf("stock", "quiet"),
        )

        assertEquals("STOCK", tweakerProfileValueLabel(ready, null))
        assertEquals("EFFICIENCY", tweakerProfileValueLabel(ready, "quiet"))
        assertEquals(
            TWEAKER_UTILITY_INSTALL_CAPS,
            tweakerProfileValueLabel(
                TweakerUtilityRowState(availability = TweakerUtilityAvailability.NOT_INSTALLED),
                null,
            ),
        )
        assertEquals(
            "Apply to switch profile to EFFICIENCY.",
            tweakerProfileDraftSupportingText("quiet"),
        )
    }

    @Test
    fun tweakerSettlementPromptUsesProductCopyForEveryPendingPhase() {
        val applying = TweakerProfileChangeState(
            operationId = 1L,
            priorProfileId = "stock",
            requestedProfileId = "quiet",
            phase = TweakerProfileChangePhase.APPLYING,
        )
        val restoring = applying.copy(phase = TweakerProfileChangePhase.CANCELLING)

        assertEquals(
            TweakerSettlementPrompt("APPLYING EFFICIENCY…", "PLEASE WAIT", canCancel = true),
            tweakerSettlementPrompt(applying, null),
        )
        assertEquals(
            TweakerSettlementPrompt("RESTORING STOCK…", "PLEASE WAIT"),
            tweakerSettlementPrompt(restoring, null),
        )
        assertEquals(
            TweakerSettlementPrompt("APPLYING MANUAL FAN…", "PLEASE WAIT"),
            tweakerSettlementPrompt(
                null,
                TweakerFanControlChangeState(2L, TweakerFanMode.AUTO, TweakerFanMode.MANUAL, 0, 0),
            ),
        )
        assertEquals(
            TweakerSettlementPrompt("APPLYING AUTO FAN…", "PLEASE WAIT"),
            tweakerSettlementPrompt(
                null,
                TweakerFanControlChangeState(3L, TweakerFanMode.MANUAL, TweakerFanMode.AUTO, 20, 0),
            ),
        )
        assertEquals(
            TweakerSettlementPrompt("APPLYING FAN RESPONSE…", "PLEASE WAIT"),
            tweakerSettlementPrompt(
                null,
                TweakerFanControlChangeState(4L, TweakerFanMode.MANUAL, TweakerFanMode.MANUAL, 0, 20),
            ),
        )
        assertNull(tweakerSettlementPrompt(null, null))
    }

    @Test
    fun tweakerProfileSettlementConsumesEveryActionWithoutCallbacksOrFocusMovement() {
        val current = state(canWrite = true)
        val initialFocus = brightnessFocusKey(BrightnessControlKey.TWEAKER_PROFILE)
        val authority = DefaultFocusAuthority().apply {
            register(initialFocus, BrightnessScope, FocusBounds(0f, 0f, 100f, 50f))
            pushScope(BrightnessScope)
            requestFocus(initialFocus)
        }
        var steps = 0
        var dismissals = 0

        fun dispatch(action: ControllerAction) = dispatchBrightnessOverlay(
            authority = authority,
            state = current,
            action = action,
            onStep = { _, _ -> },
            onSetThemeAwareJoystickLight = {},
            onRequestWriteAccess = {},
            onRequestPrivilegedAccess = {},
            onStepTweakerProfile = { steps += 1; true },
            tweakerProfileChangeInProgress = true,
            surface = UtilitySurface.TWEAKER,
            onDismiss = { dismissals += 1 },
        )

        ControllerAction.entries.forEach { action ->
            assertTrue(action.name, dispatch(action))
        }
        assertEquals(0, steps)
        assertEquals(0, dismissals)
        assertEquals(initialFocus, authority.state.value.focused)
    }

    @Test
    fun fanSettlementConsumesEveryActionWithoutDismissalOrFocusMovement() {
        val current = state(canWrite = true)
        val fan = manualFan(TweakerFanMode.AUTO)
        val initialFocus = brightnessFocusKey(BrightnessControlKey.TWEAKER_FAN_MODE)
        val authority = DefaultFocusAuthority().apply {
            register(initialFocus, BrightnessScope, FocusBounds(0f, 0f, 100f, 50f))
            pushScope(BrightnessScope)
            requestFocus(initialFocus)
        }
        var fanSteps = 0
        var dismissals = 0

        fun dispatch(action: ControllerAction) = dispatchBrightnessOverlay(
            authority = authority,
            state = current,
            action = action,
            onStep = { _, _ -> },
            onSetThemeAwareJoystickLight = {},
            onRequestWriteAccess = {},
            onRequestPrivilegedAccess = {},
            tweakerManualFan = fan,
            onStepTweakerFanMode = { fanSteps += 1; true },
            tweakerFanControlChangeInProgress = true,
            surface = UtilitySurface.TWEAKER,
            onDismiss = { dismissals += 1 },
        )

        ControllerAction.entries.forEach { action ->
            assertTrue(action.name, dispatch(action))
        }
        assertEquals(0, fanSteps)
        assertEquals(0, dismissals)
        assertEquals(initialFocus, authority.state.value.focused)
    }

    // ── Rail contract ────────────────────────────────────────────────────────────────────────────
    // A row here must be declared in several places that can silently disagree, and every one of
    // those disagreements shipped at least once while wiring the Tweaker route on 2026-08-07:
    // rendered but absent from the focus order (drawn, permanently unfocusable); present but at a
    // position that does not match where it renders (the rail reveals by index, so the press is
    // swallowed); handed to brightnessRailControls with different inputs (vanishes from reveal
    // only); and lacking a CONFIRM branch (focusable and inert). Compilation catches none of them.

    @Test
    fun railRevealAndFocusOrderNeverDisagreeForTheSameInputs() {
        val footer = setOf(BrightnessControlKey.ALLOW_SYSTEM_CHANGES, BrightnessControlKey.CLOSE)
        val current = state(canWrite = true, light = true, color = true, haptics = true, ambient = true, media = true)
        TweakerUtilityAvailability.entries.forEach { availability ->
            listOf(UtilitySurface.UTILITY, UtilitySurface.TWEAKER).forEach { surface ->
                val full = brightnessControls(
                    current,
                    primaryMButtonControlsAvailable = true,
                    surface = surface,
                    tweakerAvailability = availability,
                )
                val rail = brightnessRailControls(
                    current,
                    primaryMButtonControlsAvailable = true,
                    surface = surface,
                    tweakerAvailability = availability,
                )
                assertEquals(
                    "surface=$surface availability=$availability: rail is the full order minus the footer",
                    full.filterNot(footer::contains),
                    rail,
                )
            }
        }
    }

    /**
     * A tool panel opens at its first option, so whatever `visibleControls.first()` is IS the landing
     * row. This pins which one that is per surface: reorder `brightnessControls` and the panel
     * silently starts opening somewhere else, with nothing else to catch it.
     */
    @Test
    fun eachSurfaceLandsOnItsOwnFirstControl() {
        val current = state(canWrite = true)
        assertEquals(
            "Tweaker must open on the profile row",
            BrightnessControlKey.TWEAKER_PROFILE,
            brightnessControls(
                current,
                surface = UtilitySurface.TWEAKER,
                tweakerAvailability = TweakerUtilityAvailability.READY,
            ).first(),
        )
        val utility = brightnessControls(
            current,
            surface = UtilitySurface.UTILITY,
            tweakerAvailability = TweakerUtilityAvailability.READY,
        )
        assertEquals(
            "Utility opens on its first row, not the brightness slider, so a stray " +
                "Left or Right on arrival cannot change brightness",
            utility.first(),
            brightnessLandingControl(utility, UtilitySurface.UTILITY),
        )
        assertEquals(
            "and that first row is the Tweaker route, not the slider",
            BrightnessControlKey.OPEN_TWEAKER,
            utility.first(),
        )
        assertEquals(
            "and the Tweaker surface still lands on its own first control",
            BrightnessControlKey.TWEAKER_PROFILE,
            brightnessLandingControl(
                brightnessControls(
                    current,
                    surface = UtilitySurface.TWEAKER,
                    tweakerAvailability = TweakerUtilityAvailability.READY,
                ),
                UtilitySurface.TWEAKER,
            ),
        )
    }

    /**
     * F15 (product review 2026-09-28; rg-rotate-7, eu-nova-12): B from the Tweaker returns focus to the OPEN
     * TWEAKER row that opened it. A plain open lands on the first row too (OR-6), which is the same row today.
     */
    @Test
    fun `returning from the Tweaker gives focus back to the Tweaker row`() {
        val current = state(canWrite = true)
        val utility = brightnessControls(
            current,
            surface = UtilitySurface.UTILITY,
            tweakerAvailability = TweakerUtilityAvailability.READY,
        )
        assertEquals(
            "B from the Tweaker lands on the row the player left from",
            BrightnessControlKey.OPEN_TWEAKER,
            brightnessReturnLandingControl(utility, UtilitySurface.UTILITY, UtilitySurface.TWEAKER),
        )
        assertNull(
            "a plain open is not a return",
            brightnessReturnLandingControl(utility, UtilitySurface.UTILITY, null),
        )
        assertEquals(
            "and a plain open lands on the first row (OR-6), never the slider",
            utility.first(),
            brightnessLandingControl(utility, UtilitySurface.UTILITY),
        )
        assertNull(
            "entering the Tweaker from Utility is not a return and keeps the Tweaker landing",
            brightnessReturnLandingControl(
                brightnessControls(
                    current,
                    surface = UtilitySurface.TWEAKER,
                    tweakerAvailability = TweakerUtilityAvailability.READY,
                ),
                UtilitySurface.TWEAKER,
                UtilitySurface.UTILITY,
            ),
        )
        val noTweakerRow = brightnessControls(current, surface = UtilitySurface.UTILITY)
        assertFalse(
            "sanity: with no Tweaker installed there is no route row to return to",
            BrightnessControlKey.OPEN_TWEAKER in noTweakerRow,
        )
        assertNull(
            "and then the return falls back to the ordinary landing",
            brightnessReturnLandingControl(noTweakerRow, UtilitySurface.UTILITY, UtilitySurface.TWEAKER),
        )
    }

    @Test
    fun frameCapRowEntersTheRailOnlyWhenItHasAGameToName() {
        val current = state(canWrite = true)
        fun rail(row: TweakerFpsCapRow) = brightnessControls(
            current,
            surface = UtilitySurface.TWEAKER,
            tweakerAvailability = TweakerUtilityAvailability.READY,
            tweakerFpsCapRow = row,
        )

        // No subject, or no offered limits: no row. A frame cap that names no game is the app-wide
        // slot TweakerBridge retired -- it would be written against whatever launches next.
        assertFalse(BrightnessControlKey.TWEAKER_FPS_CAP in rail(TweakerFpsCapRow.NoSubject))
        assertFalse(
            BrightnessControlKey.TWEAKER_FPS_CAP in rail(TweakerFpsCapRow.Unavailable("com.example.game")),
        )

        // Named subject: admitted to the focus order only while the feature is not withheld. Asserted
        // against the gate rather than a literal so this test states the rule and does not have to be
        // rewritten when the gate flips post-Alpha.
        assertEquals(
            TweakerFrameCapGate.enabled,
            BrightnessControlKey.TWEAKER_FPS_CAP in rail(
                TweakerFpsCapRow.Available("com.example.game", null, listOf(30, 60)),
            ),
        )
        // It is a Tweaker-panel control; Utility must not carry a per-game cap.
        assertFalse(
            BrightnessControlKey.TWEAKER_FPS_CAP in brightnessControls(
                current,
                surface = UtilitySurface.UTILITY,
                tweakerAvailability = TweakerUtilityAvailability.READY,
                tweakerFpsCapRow = TweakerFpsCapRow.Available("com.example.game", 60, listOf(30, 60)),
            ),
        )
    }

    /**
     * The gate has to reach BOTH declaration sites. Focus order is asserted above; the render is a
     * source assertion because there is no Compose harness in this module, and gating one without the
     * other is the render-vs-focus-position defect that shipped twice on this rail already.
     */
    @Test
    fun frameCapRowIsWithheldFromTheRenderAndTheFocusOrderTogether() {
        val source = listOf(
            java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"),
            java.io.File("app/src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"),
            java.io.File("../app/src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"),
        ).firstOrNull { it.isFile }
        assertTrue("BrightnessOverlay.kt not reachable from the test working directory", source != null)

        val render = source!!.readText()
            .lineSequence()
            .first { it.contains("as? TweakerFpsCapRow.Available") }
        assertTrue(
            "the frame-cap render must consult TweakerFrameCapGate, or the row draws while the focus " +
                "order withholds it: $render",
            render.contains("TweakerFrameCapGate.enabled"),
        )
    }

    @Test
    fun everyRailRowDoesSomethingWhenConfirmed() {
        // Focusable-but-inert is how OPEN_TWEAKER shipped: in the rail, reachable, and silent on
        // press. Nothing else here catches it -- the compiler cannot, and the order tests only prove
        // a row is present. Press CONFIRM on each row and require that something fires.
        val current = state(canWrite = true, light = true, color = true, haptics = true, ambient = true, media = true)
        val rail = brightnessRailControls(
            current,
            primaryMButtonControlsAvailable = true,
            surface = UtilitySurface.UTILITY,
            tweakerAvailability = TweakerUtilityAvailability.READY,
        )
        assertTrue(rail.isNotEmpty())

        // Sliders act on LEFT/RIGHT; CONFIRM on them is deliberately inert and that is not a defect.
        val actsOnStepOnly = setOf(
            BrightnessControlKey.PRIMARY,
            BrightnessControlKey.SECONDARY,
            BrightnessControlKey.SCREEN_TIMEOUT,
            BrightnessControlKey.JOYSTICK_LIGHT,
            BrightnessControlKey.JOYSTICK_COLOR,
            BrightnessControlKey.MIMIR_HAPTIC,
            BrightnessControlKey.NAV_SOUND,
            BrightnessControlKey.AMBIENT_SOUND,
            BrightnessControlKey.MEDIA_VOLUME,
        )

        rail.filterNot(actsOnStepOnly::contains).forEach { control ->
            var fired = false
            val authority = DefaultFocusAuthority()
            authority.pushScope(BrightnessScope)
            // Registration is what makes requestFocus take. Without it the dispatcher reads a null
            // focus and EVERY row looks inert -- a green run would then mean nothing.
            rail.forEach { key ->
                authority.register(brightnessFocusKey(key), BrightnessScope, FocusBounds(0f, 0f, 100f, 40f))
            }
            authority.requestFocus(brightnessFocusKey(control))
            assertEquals(
                "focus must actually land on $control or this test proves nothing",
                brightnessFocusKey(control),
                authority.state.value.focused,
            )

            dispatchBrightnessOverlay(
                authority = authority,
                state = current,
                action = ControllerAction.CONFIRM,
                onStep = { _, _ -> fired = true },
                onSetThemeAwareJoystickLight = { fired = true },
                onRequestWriteAccess = { fired = true },
                onRequestPrivilegedAccess = { fired = true },
                onDismiss = { fired = true },
                onOpenTweaker = { fired = true },
                onOpenFiles = { fired = true },
                tweakerAvailability = TweakerUtilityAvailability.READY,
                onStepMButtonVisibilityPolicy = { fired = true },
                onStepNavigationHintVisibility = { fired = true },
                onSetCalmShine = { fired = true },
                onStepPerformanceOverlay = { fired = true },
                onRequestTweakerUsageAccess = { fired = true },
                primaryMButtonControlsAvailable = true,
                mButtonVisibilityPolicy = MButtonVisibilityPolicy.entries.first(),
                navigationHintVisibility = NavigationHintVisibility.entries.first(),
            )
            assertTrue("$control is in the rail but CONFIRM does nothing -- focusable and inert", fired)
        }
    }

    private fun state(
        canWrite: Boolean = true,
        secondary: SecondaryBrightnessSupport = SecondaryBrightnessSupport.UNAVAILABLE,
        light: Boolean = false,
        color: Boolean = false,
        haptics: Boolean = false,
        ambient: Boolean = false,
        media: Boolean = false,
        mediaFixed: Boolean = false,
        secondaryWindowFallback: Float? = null,
        mainWriteAccessUseful: Boolean = true,
        privileged: PrivilegedUtilityPosture = PrivilegedUtilityPosture.Unsupported,
        secondaryWindow: Boolean = false,
        themeRequested: Boolean = false,
        themeRestorePending: Boolean = false,
    ) = BrightnessState(
        capabilities = BrightnessCapabilities(
            canWriteSystemSettings = canWrite,
            mainSystemBrightnessActive = canWrite,
            secondaryWindowAvailable = secondaryWindow,
            secondarySupport = secondary,
            joystickLightSupported = light,
            joystickLightColorSupported = color,
            mimirHapticsSupported = haptics,
            mimirHapticAmplitudeControl = haptics,
            mediaVolumeSupported = media,
            mediaVolumeFixed = mediaFixed,
            mainWriteAccessUseful = mainWriteAccessUseful,
            privilegedUtilityPosture = privileged,
            ambientSoundSupported = ambient,
        ),
        primaryFraction = 0.5f,
        primaryWindowFallback = null,
        secondaryFraction = if (secondary == SecondaryBrightnessSupport.UNAVAILABLE) null else 0.5f,
        secondaryWindowFallback = secondaryWindowFallback,
        joystickLightFraction = if (light) 0.5f else null,
        joystickColorFraction = if (color) 0f else null,
        joystickColorArgb = if (color) 0xffffffff.toInt() else null,
        themeAwareJoystickLight = false,
        mimirHapticFraction = if (haptics) 0.4f else null,
        mediaVolumeFraction = if (media) 0.4f else null,
        themeAwareJoystickLightRequested = themeRequested,
        themeAwareJoystickLightRestorePending = themeRestorePending,
        ambientSoundFraction = if (ambient) 0.22f else null,
    )
}
