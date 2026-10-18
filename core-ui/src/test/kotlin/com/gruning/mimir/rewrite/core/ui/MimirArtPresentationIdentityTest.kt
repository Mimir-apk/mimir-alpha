package com.gruning.mimir.rewrite.core.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirArtPresentationIdentityTest {
    @Test
    fun sameDurableIdentityKeepsTheSamePresentationEpoch() {
        val first = mimirArtPresentationIdentity(
            model = "content://mimir/art/game-1",
            kind = MimirArtPresentationKind.FLAT_COVER,
        )
        val recomposed = mimirArtPresentationIdentity(
            model = StringBuilder("content://mimir/art/game-1"),
            kind = MimirArtPresentationKind.FLAT_COVER,
        )

        assertEquals(first, recomposed)
    }

    @Test
    fun modelAndArtKindEachStartANewPresentationEpoch() {
        val baseline = mimirArtPresentationIdentity(
            model = "content://mimir/art/game-1",
            kind = MimirArtPresentationKind.FLAT_COVER,
        )

        assertNotEquals(
            baseline,
            mimirArtPresentationIdentity(
                model = "content://mimir/art/game-2",
                kind = MimirArtPresentationKind.FLAT_COVER,
            ),
        )
        assertNotEquals(
            baseline,
            mimirArtPresentationIdentity(
                model = "content://mimir/art/game-1",
                kind = MimirArtPresentationKind.FLAT_LOGO,
            ),
        )
    }

    @Test
    fun artToNoArtStartsANewFlatCoverPresentationEpoch() {
        val art = mimirArtPresentationIdentity(
            model = "file:///covers/game-1.png",
            kind = MimirArtPresentationKind.FLAT_COVER,
        )
        val noArt = mimirArtPresentationIdentity(
            model = null,
            kind = MimirArtPresentationKind.FLAT_COVER,
        )

        assertNotEquals(art, noArt)
    }

    /**
     * RC-09 (audit 2026-10-14, H-04, L-20): a rename or an enrichment changes a card's title and not its art, and must not remount the cover. The identity has no title field, and
     * none of the keys that own a cover's painter or reveal names one: a key that held the title tore the painter down for the same bitmap and drew a frame without the art.
     */
    @Test
    fun aTitleIsNotPartOfAnyCoverIdentityOrKey() {
        assertTrue("the identity carries a model and a kind and nothing else", MimirArtPresentationIdentity::class.java.declaredFields.none { it.name == "title" })

        val root = listOf(File(".."), File(".")).first { File(it, "core-ui/src/main/kotlin").isDirectory }
        val dir = File(root, "core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui")
        val frame = File(dir, "MediaArtFrame.kt").readText()
        val logo = File(dir, "MimirFlatLogoArt.kt").readText()
        for ((name, text) in listOf("MediaArtFrame.kt" to frame, "MimirFlatLogoArt.kt" to logo)) {
            assertFalse("$name keys a cover on its title", Regex("""key\(\s*model\s*,\s*title\s*\)""").containsMatchIn(text))
            assertFalse("$name builds an identity from a title", Regex("""mimirArtPresentationIdentity\([^)]*title""").containsMatchIn(text))
            assertFalse("$name hands a painter its title", Regex("""rememberMimirLocalArtPainter\([^)]*title""").containsMatchIn(text))
        }
    }
}
