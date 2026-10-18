package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionQuickLaunchPrimaryRouteTest {
    @Test
    fun `primary route answers not handled when the Activity has no live coordinator`() {
        val handler: (String, Boolean, Boolean) -> QuickLaunchPrimaryRouteResult = { _, _, _ ->
            QuickLaunchPrimaryRouteResult.Refused("test: refused")
        }
        CompanionQuickLaunchPrimaryRoute.install(handler)
        assertEquals(
            QuickLaunchPrimaryRouteResult.Refused("test: refused"),
            CompanionQuickLaunchPrimaryRoute.dispatch("com.example.missing-primary", fromGeneral = false),
        )
        CompanionQuickLaunchPrimaryRoute.clear(handler)
        assertEquals(
            "with no handler installed nothing took the request, which is not a refusal with a reason",
            QuickLaunchPrimaryRouteResult.NotHandled,
            CompanionQuickLaunchPrimaryRoute.dispatch("com.example.missing-primary", fromGeneral = true),
        )
    }

    @Test
    fun `primary route acknowledges only its installed handler`() {
        val forwarded = mutableListOf<Triple<String, Boolean, Boolean>>()
        val handler: (String, Boolean, Boolean) -> QuickLaunchPrimaryRouteResult = { packageName, fromGeneral, noAnimation ->
            forwarded += Triple(packageName, fromGeneral, noAnimation)
            if (packageName == "com.example.accepted") {
                QuickLaunchPrimaryRouteResult.Accepted
            } else {
                QuickLaunchPrimaryRouteResult.Refused("test: the route's own reason")
            }
        }
        CompanionQuickLaunchPrimaryRoute.install(handler)
        assertEquals(
            QuickLaunchPrimaryRouteResult.Accepted,
            CompanionQuickLaunchPrimaryRoute.dispatch("com.example.accepted", fromGeneral = false),
        )
        assertEquals(
            "a refusal reaches the caller with the route's own reason (round 5b)",
            QuickLaunchPrimaryRouteResult.Refused("test: the route's own reason"),
            CompanionQuickLaunchPrimaryRoute.dispatch(" com.example.refused ", fromGeneral = true),
        )
        // only SWAP asks the route for a start without animation; a plain dispatch never does.
        assertEquals(
            QuickLaunchPrimaryRouteResult.Accepted,
            CompanionQuickLaunchPrimaryRoute.dispatch("com.example.accepted", fromGeneral = false, noAnimation = true),
        )
        assertEquals(
            listOf(
                Triple("com.example.accepted", false, false),
                Triple("com.example.refused", true, false),
                Triple("com.example.accepted", false, true),
            ),
            forwarded,
        )
        CompanionQuickLaunchPrimaryRoute.clear(handler)
    }
}
