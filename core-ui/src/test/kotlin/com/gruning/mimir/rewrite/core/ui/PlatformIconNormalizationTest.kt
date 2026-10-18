package com.gruning.mimir.rewrite.core.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate whose ABSENCE let a docs commit revert the whole platform icon set for six weeks.
 *
 * `2c2eb177` (2026-07-11 01:40) normalized all 102 platform drawables to a square 48dp viewport and was
 * device-verified on Thor. `4a9d54b1` (2026-07-11 02:03) — titled *"docs(companion): pending-fixes verify
 * checklist"* — changed 102 files: **one markdown and 101 of those drawables**, reverting them to their raw
 * per-icon aspects. That is the signature of a directory-wide `git add` over a stale checkout, and nothing
 * in the suite noticed, because nothing asserted the property the normalization existed to create.
 *
 * [PlatformConsoleArt] renders these with no `contentScale`, so Compose defaults to `ContentScale.Fit` into
 * a fixed box. With square assets every icon fits to the same painted area. With raw aspects each
 * letterboxes on its own axis: the reverted set spanned 0.4466 (arcade) to 3.1683 (amstrad_cpc), a 7.1x
 * aspect spread and a 3.28x painted-area spread between two tiles sitting in the same row.
 *
 * This regression test protects the asset-shape invariant the previous suite missed.
 */
class PlatformIconNormalizationTest {

    @Test
    fun everyPlatformIconDeclaresTheNormalizedSquareViewport() {
        val icons = drawableDir().listFiles { file -> file.name.matches(IconName) }.orEmpty().sortedBy { it.name }
        assertTrue("no platform icon drawables found from ${File(".").absolutePath}", icons.isNotEmpty())

        val offenders = icons.mapNotNull { icon ->
            val text = icon.readText()
            val width = Attribute("width").find(text)?.groupValues?.get(1)
            val height = Attribute("height").find(text)?.groupValues?.get(1)
            if (width == Normalized && height == Normalized) null else "${icon.name} ($width x $height)"
        }

        assertEquals(
            "Platform icons must declare a uniform ${Normalized} square viewport so ContentScale.Fit " +
                "paints every one at the same area. Off-spec: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    private fun drawableDir(): File = listOf(
        File("core-ui/src/main/res/drawable"),
        File("src/main/res/drawable"),
        File("../core-ui/src/main/res/drawable"),
    ).firstOrNull { it.isDirectory } ?: error("drawable dir not found from ${File(".").absolutePath}")

    private companion object {
        const val Normalized = "48dp"
        val IconName = Regex("""mimir_platform_icon_.*\.xml""")
        fun Attribute(name: String) = Regex("""android:$name="([^"]+)"""")
    }
}
