package com.gruning.mimir.rewrite.core.companionnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The on-demand "who is in front" reduction behind Your music's game exemption. */
class ForegroundPackageReducerTest {
    private fun reduce(vararg edges: Pair<String, ForegroundUsageKind>): String? =
        ForegroundPackageReducer().also { reducer -> edges.forEach { (pkg, kind) -> reducer.accept(pkg, kind) } }.current

    @Test
    fun `the last package to come forward is in front`() {
        assertEquals(
            "org.ppsspp.ppsspp",
            reduce(
                "com.gruning.mimir.rewrite" to ForegroundUsageKind.FOREGROUND,
                "com.gruning.mimir.rewrite" to ForegroundUsageKind.BACKGROUND,
                "org.ppsspp.ppsspp" to ForegroundUsageKind.FOREGROUND,
            ),
        )
    }

    @Test
    fun `a package that has gone to the background is no longer in front`() {
        assertNull(
            reduce(
                "org.ppsspp.ppsspp" to ForegroundUsageKind.FOREGROUND,
                "org.ppsspp.ppsspp" to ForegroundUsageKind.BACKGROUND,
            ),
        )
    }

    @Test
    fun `another package's background edge says nothing about the one in front`() {
        // A late pause of the previous app arrives after the new one resumed; the new one stays in front.
        assertEquals(
            "org.ppsspp.ppsspp",
            reduce(
                "com.gruning.mimir.rewrite" to ForegroundUsageKind.FOREGROUND,
                "org.ppsspp.ppsspp" to ForegroundUsageKind.FOREGROUND,
                "com.gruning.mimir.rewrite" to ForegroundUsageKind.BACKGROUND,
            ),
        )
    }

    @Test
    fun `an in-package activity switch keeps the package in front`() {
        // Game activity -> its menu: the menu resumes, then the game activity stops. The package is still in front.
        val reducer = ForegroundPackageReducer()
        reducer.accept("org.ppsspp.ppsspp", ForegroundUsageKind.FOREGROUND, "org.ppsspp.ppsspp.PpssppActivity")
        reducer.accept("org.ppsspp.ppsspp", ForegroundUsageKind.FOREGROUND, "org.ppsspp.ppsspp.MenuActivity")
        reducer.accept("org.ppsspp.ppsspp", ForegroundUsageKind.BACKGROUND, "org.ppsspp.ppsspp.PpssppActivity")
        assertEquals("org.ppsspp.ppsspp", reducer.current)
        reducer.accept("org.ppsspp.ppsspp", ForegroundUsageKind.BACKGROUND, "org.ppsspp.ppsspp.MenuActivity")
        assertNull("the resumed activity going away does clear it", reducer.current)
    }

    @Test
    fun `a background edge with no class still clears its package`() {
        assertNull(
            reduce(
                "org.ppsspp.ppsspp" to ForegroundUsageKind.FOREGROUND,
                "org.ppsspp.ppsspp" to ForegroundUsageKind.BACKGROUND,
            ),
        )
    }

    @Test
    fun `no events means nothing can be said`() {
        assertNull(reduce())
    }
}
