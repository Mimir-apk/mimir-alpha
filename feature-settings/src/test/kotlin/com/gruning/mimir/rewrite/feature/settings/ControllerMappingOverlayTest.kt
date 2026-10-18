package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControllerMappingOverlayTest {

    private val ids = listOf("first", "middle", "last")

    @Test
    fun shortcutDeepLinkWinsOverTheHeaderAndRetainedRow() {
        val rows = listOf(
            ControllerMappingRow("first", "NAV_UP", "Up", "DPAD UP"),
            ControllerMappingRow("utility", "GLOBAL_UTILITY", "Utility", "UNBOUND"),
        )

        assertEquals(
            "utility",
            controllerMappingInitialFocusId(
                rows = rows,
                requestedActionName = "GLOBAL_UTILITY",
                retainedFocusId = "first",
                focusIds = listOf("first", "utility"),
            ),
        )
    }

    @Test
    fun exitDecisionOwnsBackAndConfirmWithoutDismissingTheMapper() {
        val authority = DefaultFocusAuthority()
        authority.register(
            FocusKey(ControllerMappingExitApplyRowId),
            ControllerMappingExitScope,
            FocusBounds(0f, 0f, 100f, 50f),
        )
        authority.pushScope(ControllerMappingExitScope)
        authority.requestFocus(FocusKey(ControllerMappingExitApplyRowId))
        var applied = 0
        var continued = 0
        var dismissed = 0

        fun dispatch(action: ControllerAction) = dispatchControllerMapping(
            authority = authority,
            rows = emptyList(),
            action = action,
            exitConfirmationVisible = true,
            onBeginCapture = {},
            onReset = {},
            onApply = { applied += 1 },
            onDiscard = {},
            onContinueEditing = { continued += 1 },
            onDismiss = { dismissed += 1 },
        )

        dispatch(ControllerAction.CONFIRM)
        dispatch(ControllerAction.CANCEL)

        assertEquals(1, applied)
        assertEquals(1, continued)
        assertEquals(0, dismissed)
    }

    @Test
    fun terminalRowsRevealTheCompleteRailEdges() {
        assertEquals(
            0,
            controllerMappingSettlementTarget(
                focusedId = "first",
                focusIds = ids,
                geometry = ControllerMappingRowGeometry(topPx = 40, heightPx = 80),
                viewportHeightPx = 300,
                maxScrollPx = 900,
            ),
        )
        assertEquals(
            900,
            controllerMappingSettlementTarget(
                focusedId = "last",
                focusIds = ids,
                geometry = ControllerMappingRowGeometry(topPx = 1_100, heightPx = 80),
                viewportHeightPx = 300,
                maxScrollPx = 900,
            ),
        )
    }

    @Test
    fun middleRowsSettleFromMeasuredPixelsInsteadOfADeviceDensityGuess() {
        assertEquals(
            330,
            controllerMappingSettlementTarget(
                focusedId = "middle",
                focusIds = ids,
                geometry = ControllerMappingRowGeometry(topPx = 420, heightPx = 120),
                viewportHeightPx = 300,
                maxScrollPx = 900,
            ),
        )
    }

    @Test
    fun missingGeometryDoesNotIssueACompetingScroll() {
        assertNull(
            controllerMappingSettlementTarget(
                focusedId = "middle",
                focusIds = ids,
                geometry = null,
                viewportHeightPx = 300,
                maxScrollPx = 900,
            ),
        )
        assertNull(
            controllerMappingSettlementTarget(
                focusedId = "foreign",
                focusIds = ids,
                geometry = ControllerMappingRowGeometry(topPx = 0, heightPx = 80),
                viewportHeightPx = 300,
                maxScrollPx = 900,
            ),
        )
    }
}
