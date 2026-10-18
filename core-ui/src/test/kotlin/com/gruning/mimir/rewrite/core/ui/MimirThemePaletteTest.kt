package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirThemePaletteTest {
    @Test
    fun palettesMatchReleaseArgbTokenForToken() {
        assertEquals(EXPECTED.map { it.id }, MimirThemePalette.all.map { it.id })

        EXPECTED.forEach { expected ->
            val actual = MimirThemePalette.forId(expected.id)
            val actualTokens = actual.tokens()

            assertEquals("${expected.id}.finish", expected.finish, actual.presentation.finish)
            assertEquals("${expected.id}.tokenCount", TOKEN_NAMES.size, expected.tokens.size)
            assertEquals("${expected.id}.actualTokenCount", TOKEN_NAMES.size, actualTokens.size)
            TOKEN_NAMES.indices.forEach { index ->
                assertEquals(
                    "${expected.id}.${TOKEN_NAMES[index]}",
                    expected.tokens[index],
                    actualTokens[index],
                )
            }
            assertEquals("${expected.id}.ctaMaterial", expected.ctaMaterial, expected.id.ctaMaterial())
        }
    }

    @Test
    fun sharedConstantsMatchShippingArgbValues() {
        assertEquals(Color(0xFF7FD6A8), MimirThemePalette.Success)
        assertEquals(Color(0xFFFFCE8A), MimirThemePalette.WarningAmber)
        assertEquals(Color(0xFFD4AF69), MimirThemePalette.BrandGold)
        assertEquals(Color(0xFFE8C57E), MimirThemePalette.BrandGoldBright)
    }

    private fun MimirThemePalette.tokens(): List<Color?> = listOf(
        accent,
        accentBright,
        accentGlow,
        onAccent,
        onAccentBright,
        onSuccess,
        focusRing,
        surfaceBase,
        surfaceRaised,
        surfaceHigh,
        outline,
        tileFill,
        textPrimary,
        textSecondary,
        textMuted,
        signatureTop,
        signatureBottom,
        tileFillTop,
        tileFillDeep,
        tileRing,
        tileGlow,
        tileGlowStrong,
        amb,
        emberSoft,
        emberHot,
        iconGradLight,
        iconGradHot,
        iconGradMid,
        iconGradDeep,
        brandMarkFillTop,
        brandMarkFillBottom,
        danger,
        onDanger,
        success,
        warning,
        scrim,
        runeInk,
        runeMark,
        seatFocusFill,
    )

    private data class ExpectedPalette(
        val id: MimirThemeId,
        val finish: MimirThemeFinish,
        val tokens: List<Color?>,
        val ctaMaterial: List<Color>,
    )

    private companion object {
        val TOKEN_NAMES = listOf(
            "accent",
            "accentBright",
            "accentGlow",
            "onAccent",
            "onAccentBright",
            "onSuccess",
            "focusRing",
            "surfaceBase",
            "surfaceRaised",
            "surfaceHigh",
            "outline",
            "tileFill",
            "textPrimary",
            "textSecondary",
            "textMuted",
            "signatureTop",
            "signatureBottom",
            "tileFillTop",
            "tileFillDeep",
            "tileRing",
            "tileGlow",
            "tileGlowStrong",
            "amb",
            "emberSoft",
            "emberHot",
            "iconGradLight",
            "iconGradHot",
            "iconGradMid",
            "iconGradDeep",
            "brandMarkFillTop",
            "brandMarkFillBottom",
            "danger",
            "onDanger",
            "success",
            "warning",
            "scrim",
            "runeInk",
            "runeMark",
            "seatFocusFill",
        )

        val EXPECTED = listOf(
            ExpectedPalette(
                id = MimirThemeId.DEFAULT,
                finish = MimirThemeFinish.DARK,
                tokens = colors(
                    0xFFD4AF69L, 0xFFE8C57EL, 0x80D4AF69L, 0xFF120A03L, 0xFF120A03L,
                    0xFF06140DL, 0xFFD4AF69L,
                    0xFF080D12L, 0xFF141A30L, 0xFF1B2240L, 0x29D4AF69L, 0xFF141C26L,
                    0xFFEEF2FFL, 0xFFAAB6D4L, 0xFF7A90C0L,
                    0xFF050609L, 0xFF0A0810L,
                    0xFF1B1C2BL, 0xFF05060CL, 0xFFD4AF69L, 0x57D4AF69L, 0x80D4AF69L, 0x1AD4AF69L,
                    0x47D4AF69L, 0xFFE8C57EL, 0xFFFFF3C8L, 0xFFF7D07DL, 0xFFE2B873L, 0xFFBB8C4EL,
                    0xFF16213FL, 0xFF2A1D12L, 0xFFFF6B6BL, 0xFF120A03L,
                    0xFF7FD6A8L, 0xFFFFCE8AL, 0xD104050CL,
                    null, 0xFFD4AF69L,
                    0xFFE8C57EL,
                ),
                ctaMaterial = opaque(0xFFFFE6A6L, 0xFFD4AF69L, 0xFF7D5F2BL),
            ),
            ExpectedPalette(
                id = MimirThemeId.FREYR,
                finish = MimirThemeFinish.DARK,
                tokens = colors(
                    0xFFE2BD70L, 0xFFFFD36FL, 0x66E9C577L, 0xFF120A03L, 0xFF120A03L,
                    0xFF06140DL, 0xFFE2BD70L,
                    0xFF0A0805L, 0xFF1C1810L, 0xFF2A2113L, 0x33E8C57EL, 0xFF1C160DL,
                    0xFFFFF1CAL, 0xFFD8C18AL, 0xFFA99362L,
                    0xFF0A0805L, 0xFF100D07L,
                    0xFF3A2C12L, 0xFF0A0703L, 0xFFE2BD70L, 0x57E2BD70L, 0x80E2BD70L, 0x1CE2BD70L,
                    0x47E2BD70L, 0xFFFFD36FL, 0xFFFFF0B8L, 0xFFFFD36FL, 0xFFE2BD70L, 0xFF9C7839L,
                    0xFF16213FL, 0xFF2A1D12L, 0xFFFF6B6BL, 0xFF120A03L,
                    0xFF7FD6A8L, 0xFFFFCE8AL, 0xD104050CL,
                    null, 0xFFE2BD70L,
                    0xFFFFD36FL,
                ),
                ctaMaterial = opaque(0xFFFFE17AL, 0xFFE2BD70L, 0xFF8F6B32L),
            ),
            ExpectedPalette(
                id = MimirThemeId.SURTR,
                finish = MimirThemeFinish.DARK,
                tokens = colors(
                    0xFFEF5236L, 0xFFFF8A4DL, 0x57EF5236L, 0xFF120A03L, 0xFF120A03L,
                    0xFF06140DL, 0xFFEF5236L,
                    0xFF0A0605L, 0xFF1A0F0BL, 0xFF251410L, 0x33EF6E3CL, 0xFF1E0D0AL,
                    0xFFFFE7D8L, 0xFFD8A890L, 0xFFA97A66L,
                    0xFF0A0605L, 0xFF150A06L,
                    0xFF451A13L, 0xFF0D0503L, 0xFFEF5236L, 0x57EF5236L, 0x80EF5236L, 0x1FEF5236L,
                    0x3DEF5236L, 0xFFFF8A4DL, 0xFFFFCDB8L, 0xFFFF8A4DL, 0xFFEF5236L, 0xFF7A2412L,
                    0xFF16213FL, 0xFF2A1D12L, 0xFFFF6B6BL, 0xFF120A03L,
                    0xFF7FD6A8L, 0xFFFFCE8AL, 0xD104050CL,
                    null, 0xFFEF5236L,
                    0xFFFF8A4DL,
                ),
                ctaMaterial = opaque(0xFFFF8A4DL, 0xFFEF5236L, 0xFF8A2412L),
            ),
            ExpectedPalette(
                id = MimirThemeId.MANI,
                finish = MimirThemeFinish.DARK,
                tokens = colors(
                    0xFF9DB7FFL, 0xFFC5D4FFL, 0x5C96B6FFL, 0xFF120A03L, 0xFF120A03L,
                    0xFF06140DL, 0xFF9DB7FFL,
                    0xFF03050EL, 0xFF0C1422L, 0xFF111D36L, 0x2E9DB7FFL, 0xFF141C30L,
                    0xFFE4EBFFL, 0xFFB6C4EEL, 0xFF8497CAL,
                    0xFF03050EL, 0xFF080D1BL,
                    0xFF24306AL, 0xFF05060CL, 0xFF9DB7FFL, 0x579DB7FFL, 0x809DB7FFL, 0x1F9DB7FFL,
                    0x389DB7FFL, 0xFFC5D4FFL, 0xFFEAF1FFL, 0xFFC5D4FFL, 0xFF9DB7FFL, 0xFF52689FL,
                    0xFF16213FL, 0xFF2A1D12L, 0xFFFF6B6BL, 0xFF120A03L,
                    0xFF7FD6A8L, 0xFFFFCE8AL, 0xD104050CL,
                    null, 0xFF9DB7FFL,
                    0xFFC5D4FFL,
                ),
                ctaMaterial = opaque(0xFFEDF4FFL, 0xFF9DB7FFL, 0xFF536BA4L),
            ),
            ExpectedPalette(
                id = MimirThemeId.HEL,
                finish = MimirThemeFinish.DARK,
                tokens = colors(
                    0xFFB5162BL, 0xFFE3384DL, 0x6BD11F35L, 0xFFFFE9ECL, 0xFF120A03L,
                    0xFF06140DL, 0xFFD11F35L,
                    0xFF020102L, 0xFF0B0607L, 0xFF14090BL, 0x337C141EL, 0xFF1A090DL,
                    0xFFFFE9EBL, 0xFFC99095L, 0xFFA06C74L,
                    0xFF020102L, 0xFF090304L,
                    0xFF46101AL, 0xFF0A0203L, 0xFFD11F35L, 0x57D11F35L, 0x80D11F35L, 0x1CD11F35L,
                    0x33A00C1CL, 0xFFE3384DL, 0xFFFF9AA8L, 0xFFEF4257L, 0xFFD11F35L, 0xFF7A0F18L,
                    0xFF16213FL, 0xFF2A1D12L, 0xFFFF6B6BL, 0xFF120A03L,
                    0xFF7FD6A8L, 0xFFFFCE8AL, 0xD104050CL,
                    0xFFE3384DL, 0xFFE3384DL,
                    0xFFE3384DL,
                ),
                ctaMaterial = opaque(0xFFE3384DL, 0xFFB5162BL, 0xFF4F080FL),
            ),
            ExpectedPalette(
                id = MimirThemeId.NIFLHEIM,
                finish = MimirThemeFinish.DARK,
                tokens = colors(
                    0xFFDFF8FFL, 0xFFFFFFFFL, 0x5C9E8CFFL, 0xFF120A03L, 0xFF120A03L,
                    0xFF06140DL, 0xFF9E8CFFL,
                    0xFF010006L, 0xFF0A0817L, 0xFF120E28L, 0x29BCD1E6L, 0xFF151024L,
                    0xFFF0FBFFL, 0xFFBCC8DCL, 0xFF7F89A3L,
                    0xFF010006L, 0xFF070712L,
                    0xFF2C2560L, 0xFF05040EL, 0xFF9E8CFFL, 0x579E8CFFL, 0x809E8CFFL, 0x1F9E8CFFL,
                    0x29BADAFFL, 0xFFFFFFFFL, 0xFFECE8FFL, 0xFFC9BFFFL, 0xFF9E8CFFL, 0xFF5A4DA0L,
                    0xFF16213FL, 0xFF2A1D12L, 0xFFFF6B6BL, 0xFF120A03L,
                    0xFF7FD6A8L, 0xFFFFCE8AL, 0xD104050CL,
                    null, 0xFFDFF8FFL,
                    0xFFFFFFFFL,
                ),
                ctaMaterial = opaque(0xFFFFFFFFL, 0xFFDFF8FFL, 0xFF5B568EL),
            ),
            ExpectedPalette(
                id = MimirThemeId.YGGDRASIL,
                finish = MimirThemeFinish.DARK,
                // focusRing / tileRing / tileGlow / tileGlowStrong deliberately DIVERGE from the release
                // ARGB here. on the Yggdrasil selection halo: *"warm sap-light
                // catching the edge"*, `Color(0xFFC2A754)`, halo softer. The web spec is historical
                // reference and not authoritative over this tree (prime directive), so the pin moves with
                // the current palette rather than freezing an obsolete palette value. Every other Yggdrasil
                // token still matches release exactly, which is what keeps this a change-detector.
                tokens = colors(
                    0xFFC0873CL, 0xFFE3B063L, 0x52C0873CL, 0xFF120A03L, 0xFF120A03L,
                    0xFF06140DL, 0xFFC2A754L,
                    0xFF060F09L, 0xFF0E1C12L, 0xFF14271AL, 0x3378A05AL, 0xFF161C12L,
                    0xFFEDF6D4L, 0xFFC4D3A4L, 0xFF8EA072L,
                    0xFF060F09L, 0xFF07140BL,
                    0xFF3A2F16L, 0xFF090602L, 0xFFC2A754L, 0x47C2A754L, 0x6BC2A754L, 0x1FC0873CL,
                    0x3DC0873CL, 0xFFE3B063L, 0xFFF0DCA8L, 0xFFD8B06AL, 0xFFC0873CL, 0xFF7A5622L,
                    0xFF16213FL, 0xFF2A1D12L, 0xFFFF6B6BL, 0xFF120A03L,
                    0xFF7FD6A8L, 0xFFFFCE8AL, 0xD104050CL,
                    null, 0xFFC0873CL,
                    0xFFB8863BL,
                ),
                ctaMaterial = opaque(0xFFCBE39AL, 0xFF93C46EL, 0xFFC0873CL),
            ),
            ExpectedPalette(
                id = MimirThemeId.FOLKVANGR,
                finish = MimirThemeFinish.METALLIC,
                tokens = colors(
                    0xFFB6BEC7L, 0xFFDDE4EAL, 0x66B6BEC7L, 0xFF0A0B0DL, 0xFF120A03L,
                    0xFF06140DL, 0xFFC6CDD5L,
                    0xFF0B0C0EL, 0xFF16181CL, 0xFF22252AL, 0x33A9B2BBL, 0xFF101215L,
                    0xFFE8ECF0L, 0xFFB7BFC7L, 0xFF8C959EL,
                    0xFF0B0C0EL, 0xFF131619L,
                    0xFF2B3036L, 0xFF08090BL, 0xFFB6BEC7L, 0x57B6BEC7L, 0x80B6BEC7L, 0x1CB6BEC7L,
                    0x47B6BEC7L, 0xFFDDE4EAL, 0xFFEDF1F5L, 0xFFCBD3DBL, 0xFFB6BEC7L, 0xFF6E7883L,
                    0xFF16213FL, 0xFF2A1D12L, 0xFFCF6F6FL, 0xFF0A0B0DL,
                    0xFF7FD6A8L, 0xFFCBBE96L, 0xD104050CL,
                    null, 0xFFB6BEC7L,
                    0xFFDDE4EAL,
                ),
                ctaMaterial = opaque(0xFFE8ECF0L, 0xFFB6BEC7L, 0xFFC7B183L),
            ),
            ExpectedPalette(
                id = MimirThemeId.BALDR,
                finish = MimirThemeFinish.METALLIC,
                tokens = colors(
                    0xFF6FB8F0L, 0xFF9FD4FFL, 0x666FB8F0L, 0xFF120A03L, 0xFF120A03L,
                    0xFF06140DL, 0xFF7CC4FFL,
                    0xFF060E18L, 0xFF0E1E30L, 0xFF152B42L, 0x336FB8F0L, 0xFF10213AL,
                    0xFFE6F1FFL, 0xFFB8CCE4L, 0xFF8FA8C4L,
                    0xFF060E18L, 0xFF0A1626L,
                    0xFF1E3358L, 0xFF0A1424L, 0xFF6FB8F0L, 0x576FB8F0L, 0x806FB8F0L, 0x1C6FB8F0L,
                    0x476FB8F0L, 0xFF9FD4FFL, 0xFFE8F4FFL, 0xFFA8D4F5L, 0xFF6FB8F0L, 0xFF2F5F8CL,
                    0xFF16213FL, 0xFF2A1D12L, 0xFFFF6B6BL, 0xFF120A03L,
                    0xFF7FD6A8L, 0xFFFFCE8AL, 0xD104050CL,
                    null, 0xFF6FB8F0L,
                    0xFF9FD4FFL,
                ),
                ctaMaterial = opaque(0xFFEAF4FFL, 0xFF6FB8F0L, 0xFFFFCF5CL),
            ),
        )

        fun colors(vararg argb: Long?): List<Color?> = argb.map { value -> value?.let { Color(it) } }

        fun opaque(vararg argb: Long): List<Color> = argb.map { Color(it) }
    }

    @Test
    fun `the design gallery does not teach the retired light-theme rule`() {
        // Found during visual review, 2026-09-01: the gallery's own caption read "Folkvangr/Baldr are light",
        // the exact opposite of MimirThemeId's KDoc. It is the surface a
        // designer opens to learn the system, so a wrong rule there propagates.
        //
        // Pinned by READING the gallery source rather than by grepping this module, because the theme's
        // own KDoc legitimately says Folkvangr and Baldr *were* light in the retired WebView -- a naive
        // repo-wide grep would fire on the sentence that states the history correctly.
        // **COMMENTS ARE STRIPPED FIRST, and the version without that step is why this pin exists
        // twice.** It shipped matching quoted spans straight out of the raw source, and passed with
        // the defect reintroduced. The cause: the fix's own explanatory comment names Folkvangr, so the
        // pattern paired the CLOSING quote of the previous string with the OPENING quote of the
        // caption, captured the comment between them, and then resumed scanning PAST the caption it
        // was written to check. A pin whose subject can hide inside prose is not checking the code.
        val gallery = java.io.File(
            "src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirDesignGallery.kt",
        ).readText().lineSequence().joinToString("\n") { line ->
            val marker = line.indexOf("//")
            if (marker >= 0) line.substring(0, marker) else line
        }
        val captions = Regex("\"([^\"]*Folkvangr[^\"]*)\"").findAll(gallery).map { it.groupValues[1] }.toList()
        assertTrue("the gallery must still carry a caption naming Folkvangr", captions.isNotEmpty())
        captions.forEach { caption ->
            assertFalse("gallery caption still calls them light: $caption", caption.endsWith("are light"))
        }
        // And the claim it replaced must be true of the palettes themselves, not just of the string.
        listOf(MimirThemeId.FOLKVANGR, MimirThemeId.BALDR).forEach { id ->
            assertFalse("$id must be a DARK field", MimirThemePalette.forId(id).usesBrightField)
        }
    }

}
