package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
// --- the moved pieces: this block is the only thing that differs from the run that recorded the golden hashes at aa5c100e2 ---
import com.gruning.mimir.rewrite.core.ui.GlassMenuCapsule
import com.gruning.mimir.rewrite.core.ui.HeaderRule
import com.gruning.mimir.rewrite.core.ui.MimirMannazBadge
import com.gruning.mimir.rewrite.core.ui.MimirMarkBadge
import com.gruning.mimir.rewrite.core.ui.PlainMenuSeparator
import com.gruning.mimir.rewrite.core.ui.drawMimirBadgeMannaz
import com.gruning.mimir.rewrite.core.ui.drawMimirCornerBrackets
// --- end of the moved pieces ---
import com.gruning.mimir.rewrite.core.ui.AmbientMotionPhase
import com.gruning.mimir.rewrite.core.ui.LocalMimirAmbientMotionPhase
import com.gruning.mimir.rewrite.core.ui.mimirNorseBookFrame
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * V2 of the Thor Bottom revamp: the pieces promoted into core-ui draw EXACTLY what they drew at their Main call sites.
 *
 * Each case composes the piece the way Main does (same size, same palette, same arguments), reads the rendered pixels back under
 * Robolectric's native graphics, and compares their SHA-256 with the hash recorded by the same cases run against the code before
 * it moved (commit aa5c100e2, the pieces where they lived: the app's brand lockup, the shelf chrome, the platform hero tile,
 * the tile menu, MimirNorseBook). The hash is over every ARGB pixel of the case's container, so one pixel's difference fails it.
 * A case that rendered nothing fails its own ink check, so a blank frame cannot pass for a match.
 *
 * Pixel values are the JVM's (Skia through Robolectric); the pixels on a handheld are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MovedPiecesPixelIdentityTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ground = Color(0xFF101418)

    private class Case(
        val width: Dp,
        val height: Dp,
        val themeId: MimirThemeId,
        val phase: AmbientMotionPhase?,
        val content: @Composable () -> Unit,
    )

    private var current by mutableStateOf<Case?>(null)
    private var mounted = false

    /**
     * SHA-256 of the case's pixels, plus how many differ from [ground] (0 means the case drew nothing). The host is mounted once
     * and each case swapped into it, because a rule takes one `setContent`. The pixels are the view hierarchy's own, drawn into a
     * software bitmap: `captureToImage` waits on a redraw Robolectric's paused looper never delivers (MimirArtPlateLawTest's note).
     *
     * [phase] and [atMillis] are for the pieces that animate (the header rule's molten band): the case is composed under that ambient
     * phase, the test clock is PAUSED, and the pixels are read exactly [atMillis] of clock time after the case mounted, so one case is
     * one reproducible frame. A case with no [atMillis] is read at rest after a second, as every static piece always was.
     */
    private fun hashOf(
        width: Dp,
        height: Dp,
        themeId: MimirThemeId = MimirThemeId.DEFAULT,
        phase: AmbientMotionPhase? = null,
        atMillis: Long? = null,
        content: @Composable () -> Unit,
    ): Pair<String, Int> {
        val next = Case(width, height, themeId, phase, content)
        if (atMillis != null) compose.mainClock.autoAdvance = false
        if (!mounted) {
            mounted = true
            current = next
            compose.setContent {
                val case = current
                if (case != null) {
                    key(case) {
                        val host: @Composable () -> Unit = {
                            MimirTheme(themeId = case.themeId) {
                                Box(
                                    Modifier.size(case.width, case.height).background(ground).testTag(Tag),
                                    contentAlignment = Alignment.Center,
                                ) { case.content() }
                            }
                        }
                        // The ambient phase is provided only for a case that names one, so every static case composes exactly as it did
                        // when its golden was recorded.
                        val phase = case.phase
                        if (phase != null) {
                            CompositionLocalProvider(LocalMimirAmbientMotionPhase provides phase) { host() }
                        } else {
                            host()
                        }
                    }
                }
            }
        } else {
            compose.runOnIdle { current = next }
        }
        if (atMillis != null) {
            compose.mainClock.advanceTimeBy(atMillis)
        } else {
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1_000L)
            compose.waitForIdle()
        }
        val decor = compose.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(full))
        val bounds = compose.onNodeWithTag(Tag).fetchSemanticsNode().boundsInWindow
        val crop = Bitmap.createBitmap(full, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
        val map = crop.asImageBitmap().toPixelMap()
        val pixels = map.buffer.copyOf(map.width * map.height)
        val bytes = ByteBuffer.allocate(pixels.size * 4).also { b -> pixels.forEach(b::putInt) }.array()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return digest to pixels.count { it != ground.toArgb() }
    }

    private fun check(name: String, result: Pair<String, Int>) {
        println("PIXEL-HASH $name ${result.first} inked=${result.second}")
        assertTrue("$name drew nothing, so a match would prove nothing", result.second > 0)
        assertEquals("$name is not pixel-identical to what Main drew before the move", GOLDEN[name], result.first)
    }

    private fun palette(id: MimirThemeId) = MimirThemePalette.forId(id)

    @Test
    fun mannazBadgeAtTheSizesMainSeatsIt() {
        for (size in listOf(24, 32, 56)) {
            check("mannaz-badge-default-$size", hashOf(64.dp, 64.dp) { MimirMannazBadge(palette = palette(MimirThemeId.DEFAULT), size = size.dp) })
        }
        check("mannaz-badge-surtr-32", hashOf(64.dp, 64.dp, MimirThemeId.SURTR) { MimirMannazBadge(palette = palette(MimirThemeId.SURTR), size = 32.dp) })
    }

    @Test
    fun markBadgeWithStudsAtTheSizesMainSeatsIt() {
        for (size in listOf(28, 32, 34)) {
            check("mark-badge-default-$size", hashOf(64.dp, 64.dp) { MimirMarkBadge(palette = palette(MimirThemeId.DEFAULT), sizeDp = size.dp) })
        }
        check("mark-badge-hel-32", hashOf(64.dp, 64.dp, MimirThemeId.HEL) { MimirMarkBadge(palette = palette(MimirThemeId.HEL), sizeDp = 32.dp) })
    }

    @Test
    fun heroTileMannazFaceAtTheAlphasItFadesThrough() {
        for (alpha in listOf(1f, 0.55f, 0.2f)) {
            val ink = palette(MimirThemeId.DEFAULT).runeMark.copy(alpha = alpha)
            check("hero-mannaz-$alpha", hashOf(32.dp, 32.dp) { Box(Modifier.size(16.dp).drawBehind { heroMannaz(ink) }) })
        }
    }

    @Test
    fun shelfHeaderRulePlainAndMoltenWithAndWithoutBleed() {
        for (id in listOf(MimirThemeId.DEFAULT, MimirThemeId.SURTR, MimirThemeId.NIFLHEIM)) {
            for (bleed in listOf(0, 16)) {
                check(
                    "header-rule-$id-bleed$bleed",
                    hashOf(392.dp, 24.dp, id) {
                        Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                            HeaderRule(palette = palette(id), bleed = bleed.dp)
                        }
                    },
                )
            }
        }
    }

    @Test
    fun tileMenuSeparator() {
        check("menu-separator-default", hashOf(300.dp, 24.dp) { Box(Modifier.size(280.dp, 8.dp)) { PlainMenuSeparator() } })
        check("menu-separator-surtr", hashOf(300.dp, 24.dp, MimirThemeId.SURTR) { Box(Modifier.size(280.dp, 8.dp)) { PlainMenuSeparator() } })
    }

    @Test
    fun tileMenuCapsuleRestingAndFocused() {
        for (focused in listOf(false, true)) {
            check(
                "menu-capsule-focused$focused",
                hashOf(220.dp, 72.dp) {
                    GlassMenuCapsule(focused = focused, identity = "action") {
                        Box(Modifier.size(96.dp, 20.dp).background(Color(0xFF5A7D9A)))
                    }
                },
            )
        }
    }

    @Test
    fun bookCoverCornerBrackets() {
        check(
            "corner-brackets-default",
            hashOf(200.dp, 300.dp) {
                val accent = palette(MimirThemeId.DEFAULT).accent
                Box(
                    Modifier.size(200.dp, 300.dp).drawBehind {
                        drawMimirCornerBrackets(accent = accent, insetPx = 12.dp.toPx(), armPx = 16.dp.toPx(), widthPx = 1.4.dp.toPx())
                    },
                )
            },
        )
    }

    // ---- V3 fold-in: three coverage gaps the V2 review named ---------------------------------------------------------------------

    /** Main's onboarding lockup seats the badge at 52 dp (OnboardingFlow, `badgeSize`), a size none of the first cases drew. */
    @Test
    fun onboardingBadgeAtFiftyTwo() {
        check("mannaz-badge-default-52", hashOf(64.dp, 64.dp) { MimirMannazBadge(palette = palette(MimirThemeId.DEFAULT), size = 52.dp) })
    }

    /**
     * The corner brackets exactly as the production book draws them: through `mimirNorseBookFrame`, which hands the brackets
     * `book.hardware` (a theme's own metal, not the bare accent the first case used), with the frame's border, inner line and gradient
     * around them. The whole frame is hashed, so a bracket that moved by one pixel or took another ink fails it.
     */
    @Test
    fun bookFrameWithItsProductionHardwareInk() {
        for (id in listOf(MimirThemeId.DEFAULT, MimirThemeId.SURTR, MimirThemeId.HEL)) {
            check(
                "book-frame-$id",
                hashOf(200.dp, 300.dp, id) { Box(Modifier.size(200.dp, 300.dp).mimirNorseBookFrame(palette(id))) },
            )
        }
    }

    /**
     * The molten header rule while it MOVES: Surtr and Niflheim, under both ambient phases that run it (the rule is frozen only at
     * IDLE, which the first cases covered), each read at two instants of a paused clock, so the crest, the core and the glow floor are
     * checked mid-flow and not only at the instant they rest.
     */
    @Test
    fun shelfHeaderRuleWhileItAnimates() {
        fun ruleAt(id: MimirThemeId, phase: AmbientMotionPhase, atMillis: Long) =
            hashOf(392.dp, 24.dp, id, phase = phase, atMillis = atMillis) {
                Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    HeaderRule(palette = palette(id), bleed = 16.dp)
                }
            }
        // The first read of a test is the host's own first composition, which starts its clock a frame off every case that is swapped in
        // after it (Surtr's quick flicker shows it: the same instant read first and read second hashed differently). Read once and
        // thrown away, so every case that is checked is a swap and they are all read the same way.
        try {
            ruleAt(MimirThemeId.SURTR, AmbientMotionPhase.INTERACTING, 700L)
            for (id in listOf(MimirThemeId.SURTR, MimirThemeId.NIFLHEIM)) {
                for (phase in listOf(AmbientMotionPhase.INTERACTING, AmbientMotionPhase.RECENT)) {
                    for (atMillis in listOf(700L, 2_300L)) {
                        check("header-rule-animated-$id-$phase-t$atMillis", ruleAt(id, phase, atMillis))
                    }
                }
            }
        } finally {
            // The molten loops never rest, and the clock is paused: left composed, they outlive the test and keep the shared looper
            // busy, so the next test's waitForIdle never returns. Take the case out and give the composition its frames to dispose it.
            compose.runOnIdle { current = null }
            compose.mainClock.advanceTimeBy(200L)
            compose.mainClock.autoAdvance = true
        }
    }

    /** The hero tile's one call, as it calls it: the badge glyph on a 16 dp box with the ink already resolved. */
    private fun DrawScope.heroMannaz(ink: Color) = drawMimirBadgeMannaz(ink)

    private companion object {
        const val Tag = "case"

        /** Recorded from the pre-move code (aa5c100e2) by these same cases, on two separate runs that agreed bit for bit. */
        val GOLDEN: Map<String, String> = mapOf(
            // V3 fold-in: recorded the same way (aa5c100e2 in a scratch worktree, the shelf chrome's private HeaderRule made public for the
            // run, these cases verbatim). The animated rows were read on two separate runs that agreed bit for bit; INTERACTING and
            // RECENT run the same loops, so each theme's two phases share a hash at each instant, and the two instants differ.
            "book-frame-DEFAULT" to "5861409cdaf8a677a801eb5346f95069877af275840cda2fb21912970016da6e",
            "book-frame-HEL" to "33dd8104ef591e0d9f7f29f7d8a41800d4195b093b556f6d7cd4563cd2118faa",
            "book-frame-SURTR" to "775457713086bd622706e9da28c9ab9755df88693c8476a437912e85b300feaf",
            "header-rule-animated-NIFLHEIM-INTERACTING-t2300" to "5860c7b628d548f6253e5f71d21b96075b0a74b6d4a5a734fa11ae3e38ef6c54",
            "header-rule-animated-NIFLHEIM-INTERACTING-t700" to "2cd85ff1d293768d0c5155862629532d69cbd109d1bba3a87a6ddd5d29d0665d",
            "header-rule-animated-NIFLHEIM-RECENT-t2300" to "5860c7b628d548f6253e5f71d21b96075b0a74b6d4a5a734fa11ae3e38ef6c54",
            "header-rule-animated-NIFLHEIM-RECENT-t700" to "2cd85ff1d293768d0c5155862629532d69cbd109d1bba3a87a6ddd5d29d0665d",
            "header-rule-animated-SURTR-INTERACTING-t2300" to "b2f59f7c599aa5b2344f69dafdaba48dd63a7bc30ca4a096d984d6a81e001df1",
            "header-rule-animated-SURTR-INTERACTING-t700" to "eeabdcb516cf3fbcbd83a1f2e735b81111993e148151d93d05edfa494b079e7c",
            "header-rule-animated-SURTR-RECENT-t2300" to "b2f59f7c599aa5b2344f69dafdaba48dd63a7bc30ca4a096d984d6a81e001df1",
            "header-rule-animated-SURTR-RECENT-t700" to "eeabdcb516cf3fbcbd83a1f2e735b81111993e148151d93d05edfa494b079e7c",
            "mannaz-badge-default-52" to "b54dc0927cbf0c34c56866eb89adf7e7f9ef758b5baba17a12f0d9e0db1dd64d",
            "corner-brackets-default" to "ccd3af8a8bdfdfc84414ac085f3613571b35f8c3fc1a1f794780410db32e0ce0",
            "header-rule-DEFAULT-bleed0" to "6e8d6bae979bc6af27ad1ae06b5d4d5602285be1db1d9a5114bd121eae6e3d6d",
            "header-rule-DEFAULT-bleed16" to "6e8d6bae979bc6af27ad1ae06b5d4d5602285be1db1d9a5114bd121eae6e3d6d",
            "header-rule-NIFLHEIM-bleed0" to "4b2031faaf05e37848be339fca979af7d7c274f19f88b12018c73c937c9e3e4b",
            "header-rule-NIFLHEIM-bleed16" to "673d0f098ab78343e172e60c58d4b62baaaba1672cb03364fa4b29df01f96b70",
            "header-rule-SURTR-bleed0" to "ca01d6f7e18215a4c9ad8bab1ebad8cd2c958d747021037634d64c3997fad06f",
            "header-rule-SURTR-bleed16" to "6f52424dfce1526a2b424c3fbd292a63522e33b040b8a96fc2f263d979af2d9e",
            "hero-mannaz-0.2" to "6faffcd8a9a20045968b487899623897ce8c55a7d421b57abb2cc7dea57c761d",
            "hero-mannaz-0.55" to "385b879f4454b63f95e2cf673a6af99182ce694803f555d4f1cc2be7a2f9d540",
            "hero-mannaz-1.0" to "1f35f754541bfe068b4672b206a1cbf23ac74c2e894cfe3388c450451d112e95",
            "mannaz-badge-default-24" to "ef8f7263d312357a990f51b3a2a0bf2a3374bbeafa2b122b99dcd58038f1d4fd",
            "mannaz-badge-default-32" to "9c03a517d46f67149cd20fbbb71d5d1246e7288fadd8ad7ef243550c20e4455a",
            "mannaz-badge-default-56" to "728b26d63acc5d7ced10e192ec75309e3ffa26ad22a029c4e8f19962239763f0",
            "mannaz-badge-surtr-32" to "cc70756c5589831801159cfc02f252c511a7a08b8f54ca0b9acfaa37e0c2154b",
            "mark-badge-default-28" to "e3fb03e671568419ab5241a1dd43f8ca64484baf4a66a933ee3db130c64c7583",
            "mark-badge-default-32" to "44eefedb44ac4de18398e3c908f57ad13d0ce5406dbe5e88367c42c1a0a6f69b",
            "mark-badge-default-34" to "8e73ce3aa88632bc782a6940e012cdd4c261c519aea3937a01cfcce3d2941adf",
            "mark-badge-hel-32" to "5257b9164e7d5244b7cc758fdf4f554aec39e1f84a08626ab714b5c99c48c1ec",
            "menu-capsule-focusedfalse" to "e6089ac7ea3e5efc297cfcdb69eff575c150c4bc94108f80edb03a06f6aff5c2",
            "menu-capsule-focusedtrue" to "20a5ed364c08f27bd5f3bdb2fa01ceb6ed3a4cd16753640c0fc9958b56b3e8c2",
            "menu-separator-default" to "20d4890614f183b28d9573dd30b6d39c82be0bdbb42ababfa6c6cbbd8e7bb55b",
            "menu-separator-surtr" to "0897c0d5ba7ba3721745477c583a9ee711e08da414b9709feb23efc9001bea4b",
        )
    }
}
