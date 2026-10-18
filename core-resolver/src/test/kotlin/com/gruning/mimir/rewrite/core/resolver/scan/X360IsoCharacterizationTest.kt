package com.gruning.mimir.rewrite.core.resolver.scan

import com.gruning.mimir.rewrite.core.model.ContentKind
import com.gruning.mimir.rewrite.core.resolver.ExtensionPlatformClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CHARACTERIZATION of one reported field case: a real `.iso` under an `x360/` folder reported as missing from the shelf, replayed against the real locked platform registry.
 *
 * These assertions record what the recognizer ACTUALLY does today, not what it ought to do. The
 * observed result is that the file IS recognized, as `xbox360` — so the reported disappearance does
 * NOT originate in extension/folder classification, and looking for the fault here is a dead end.
 * If a future change makes this test fail, recognition behaviour moved; decide deliberately whether
 * that was intended rather than editing the expectation to match.
 */
class X360IsoCharacterizationTest {

    private val recognizer = ScanRecognizer()

    @Test
    fun `an iso under an x360 folder resolves to xbox360 through the folder hint`() {
        val result = recognizer.recognize(walked(), ROOT)

        val recognized = assertRecognized(result)
        assertEquals("xbox360", recognized.file.platformId.value)
        assertEquals(ContentKind.GAME, recognized.file.contentKind)
        // The `~` alternate-title separator and the (World)/(languages) parentheticals are stripped
        // from the title but do NOT push the file out of the BASE content role.
        assertEquals("Resident Evil 5 ~ Biohazard 5", recognized.file.identity?.title)
        assertEquals(REL_PATH, recognized.file.relativePath)
    }

    @Test
    fun `the x360 folder alias is what resolves an otherwise ambiguous iso`() {
        // `iso` alone is claimed by many platforms; the folder is the only disambiguator here.
        assertEquals("xbox360", ExtensionPlatformClassifier.platformHintFromFolder("x360")?.value)
        // recognize() derives the hint from the FULL relative path, filename included.
        assertEquals("xbox360", ExtensionPlatformClassifier.platformHintFromFolder(REL_PATH)?.value)

        // Same file with the folder removed: no hint, so the same extension goes ambiguous. This is
        // what proves the alias — not the extension — is carrying the resolution.
        val rootLevel = recognizer.recognize(walked(relativePath = FILE_NAME), ROOT)
        assertEquals(
            SkipReason.AMBIGUOUS_PLATFORM,
            (rootLevel as RecognitionResult.Skipped).reason,
        )
    }

    private fun assertRecognized(result: RecognitionResult): RecognitionResult.Recognized {
        assertTrue(
            "Expected the x360 iso to be recognized, but it was " +
                "${(result as? RecognitionResult.Skipped)?.reason}",
            result is RecognitionResult.Recognized,
        )
        return result as RecognitionResult.Recognized
    }

    private fun walked(relativePath: String = REL_PATH): WalkedFile = WalkedFile(
        displayName = FILE_NAME,
        relativePath = relativePath,
        sizeBytes = 2L * 1024 * 1024,
        documentUri = "$ROOT/document/x360",
        filePath = null,
    )

    private companion object {
        const val FILE_NAME = "Resident Evil 5 ~ Biohazard 5 (World) (En,Ja,Fr,De,Es,It).iso"
        const val REL_PATH = "x360/$FILE_NAME"
        const val ROOT = "content://com.android.externalstorage.documents/tree/691E-7BEA%3AROMs"
    }
}
