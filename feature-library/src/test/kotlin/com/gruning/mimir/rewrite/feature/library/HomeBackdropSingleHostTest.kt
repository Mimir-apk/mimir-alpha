package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *
 *
 * Compose keys composition groups by **call site**. The same composable written in two branches is two
 * groups, so flipping the branch disposes one and mounts the other, and every animation the disposed one
 * owned is gone. `HomeShelfBackdrop.kt` spells out why that is a hard cut here specifically:
 * `synchronizedPresence` is `remember { Animatable(if (synchronizedTomeHandoff && desiredUri != null) 1f
 * else 0f) }` — **no keys** — so a freshly mounted host starts fully present, with no animation to soften
 * whatever caused the mount.
 *
 * RC-34 already fixed one instance of this (shelf lead art versus opened-Folio member) and left the outer
 * tome/shelf boundary split, which is the instance the regression report described. Measured on the Odin: backdrop-only frame
 * difference of **30.6 in a single frame** on X-into-Tome, against a 5.7 peak for everything else in the
 * same 18-second path.
 *
 * This test is source-level because composition-group identity is not observable from a JVM unit test.
 * It is a weak instrument and says so — but it pins the one property that actually regressed twice.
 */
class HomeBackdropSingleHostTest {

    private val screen = File(
        "src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeShelfScreen.kt",
    )

    private fun source(): String {
        assertTrue("HomeShelfScreen.kt not found at ${screen.absolutePath}", screen.isFile)
        return screen.readText()
    }

    @Test
    fun `the still backdrop host has exactly one call site`() {
        val text = source()
        val calls = Regex("""HomeShelfStaticBackdrop\(""").findAll(text).count()
        // Guard against a vacuous pass: if the composable is renamed away, this test must fail loudly
        // rather than silently assert 0 == 0.
        assertTrue("HomeShelfStaticBackdrop is not called here at all — this test has gone vacuous", calls > 0)
        assertEquals(
            "Two call sites means the tome/shelf flip disposes and remounts the host, which is a HARD CUT. " +
                "Express the difference as PARAMETERS on one host instead.",
            1,
            calls,
        )
    }

    @Test
    fun `the video host is the only other backdrop mount, and is known to be a genuine swap`() {
        val text = source()
        val video = Regex("""HomeShelfBackdrop\(""").findAll(text).count()
        assertTrue("HomeShelfBackdrop is not called here at all — vacuous", video > 0)
        assertEquals("the clip host should also be reached from one place", 1, video)
        // Video <-> Static remains a real dispose/mount: different composables, no shared layer to fade
        // between. Named here so the next reader does not mistake this test for a claim that every
        // backdrop transition is now seamless.
        assertTrue(
            "the Video<->Static swap must stay documented as owed, not silently forgotten",
            text.contains("Video <-> Static"),
        )
    }
}
