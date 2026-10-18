package com.gruning.mimir.rewrite.core.companionnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeOverlayInteractionSessionTest {
    @Test
    fun everyNativeSourcePairsDownWithOneUpOrCancel() {
        NativeOverlayInteractionSource.entries.forEach { source ->
            val events = mutableListOf<String>()
            val observer = recordingObserver(events)
            val session = NativeOverlayInteractionSession(source) { observer }

            session.begin()
            assertTrue(session.finish())
            assertFalse(session.finish())

            assertEquals(listOf("$source:true", "$source:false"), events)
        }
    }

    @Test
    fun terminalEventReturnsToObserverThatReceivedDown() {
        val firstEvents = mutableListOf<String>()
        val secondEvents = mutableListOf<String>()
        val first = recordingObserver(firstEvents)
        val second = recordingObserver(secondEvents)
        var current: NativeOverlayInteractionObserver? = first
        val session = NativeOverlayInteractionSession(NativeOverlayInteractionSource.MENU) { current }

        session.begin()
        current = second
        session.finish()

        assertEquals(listOf("MENU:true", "MENU:false"), firstEvents)
        assertEquals(emptyList<String>(), secondEvents)
    }

    @Test
    fun repeatedDownClosesPriorLifetimeBeforeOpeningReplacement() {
        val events = mutableListOf<String>()
        val observer = recordingObserver(events)
        val session = NativeOverlayInteractionSession(NativeOverlayInteractionSource.EDGE_BACK) { observer }

        session.begin()
        session.begin()
        session.finish()

        assertEquals(
            listOf("EDGE_BACK:true", "EDGE_BACK:false", "EDGE_BACK:true", "EDGE_BACK:false"),
            events,
        )
    }

    private fun recordingObserver(events: MutableList<String>) =
        NativeOverlayInteractionObserver { source, active -> events += "$source:$active" }
}
