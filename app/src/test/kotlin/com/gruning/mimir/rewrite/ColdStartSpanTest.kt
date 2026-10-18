package com.gruning.mimir.rewrite

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * First-launch stall instrumentation: the cold-start spans that split Main's first "content composition"
 * (1,731 ms on the Nova, walks 22-24) into named pieces. Pinned here: a span logs once per process and never after the
 * first composition is applied; the spans sit between the app root's coordinators in declaration order; and none sits
 * inside rememberHomeShelfCoordinator, whose size already VerifyError'd a cold start once (CP02 crash README). The
 * durations themselves are device-owed: read the MimirColdStart lines of the next walk.
 */
class ColdStartSpanTest {

    @Before
    fun setUp() = MimirColdStartTrace.resetSpansForTest()

    @After
    fun tearDown() = MimirColdStartTrace.resetSpansForTest()

    @Test
    fun `a span logs once, and nothing logs once the spans close`() {
        assertTrue(MimirColdStartTrace.markOnce("span test a"))
        assertFalse("a span logs once per process", MimirColdStartTrace.markOnce("span test a"))
        MimirColdStartTrace.closeSpans()
        assertFalse("the first composition was applied: no more spans", MimirColdStartTrace.markOnce("span test b"))
    }

    @Test
    fun `the coordinator spans sit in declaration order, once each`() {
        val source = source("MimirAppCoordinator.kt")
        val body = source.substringAfter("internal fun MimirAppCoordinator(")
            .substringBefore("private fun InstallMimirInputHandler(")
        val positions = ORDER.map { name ->
            body.indexOf("MimirColdStartTrace.markOnce(\"$name\")").also { assertTrue("missing span $name", it >= 0) }
        }
        assertEquals("spans follow the coordinators' declaration order", positions.sorted(), positions)
        ORDER.forEach { name ->
            assertEquals("$name appears once", 1, source.split("markOnce(\"$name\")").size - 1)
        }
        assertTrue(
            "the home span closes the Home coordinator call, not its body",
            body.indexOf("markOnce(\"span settings coordinator done\")") <
                body.indexOf("val homeShelf = rememberHomeShelfCoordinator(") &&
                body.indexOf("val homeShelf = rememberHomeShelfCoordinator(") <
                body.indexOf("markOnce(\"span home coordinator done\")"),
        )
    }

    @Test
    fun `the destination span brackets the destination host`() {
        val host = source("MimirAppHost.kt")
        val chrome = host.indexOf("markOnce(\"span host chrome done\")")
        val destination = host.indexOf("MimirDestinationHost(bindings)")
        val done = host.indexOf("markOnce(\"span destination done\")")
        assertTrue(chrome in 0 until destination && destination < done)
        assertTrue(host.indexOf("markOnce(\"span overlays done\")") > done)
    }

    @Test
    fun `no span sits inside the Home coordinator`() {
        assertFalse(source("HomeShelfCoordinator.kt").contains("markOnce("))
    }

    @Test
    fun `the content probe closes the spans after its first apply`() {
        val probe = source("MimirColdStartTrace.kt").substringAfter("internal fun MimirColdStartContentProbe(")
        val applied = probe.indexOf("mark(\"content composition applied\")")
        assertTrue(applied >= 0 && probe.indexOf("closeSpans()") > applied)
    }

    private fun source(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    private companion object {
        val ORDER = listOf(
            "span core coordinator done",
            "span library coordinator done",
            "span navigation coordinator done",
            "span native shelf coordinator done",
            // Artwork before the Settings read model (Settings › Music, 2026-09-30): the read model now takes the
            // artwork state for the "Download game music" row, so the artwork coordinator is remembered first.
            "span artwork coordinator done",
            "span settings read coordinator done",
            "span achievements coordinator done",
            "span onboarding coordinator done",
            "span migration coordinator done",
            "span settings coordinator done",
            "span home coordinator done",
            "span launch card coordinator done",
            "span coordinator body done",
            "span app effects done",
        )
    }
}
