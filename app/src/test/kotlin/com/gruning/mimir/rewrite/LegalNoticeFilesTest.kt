package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The legal files the apps show must be the files the repository publishes. Each text lives once at the repository root (`NOTICE`,
 * `LICENSE`, `mimir-tweaker/NOTICE.md`, `mimir-tweaker/LICENSE`) and is copied into the assets of the APK that shows it. A copy that
 * drifts would put a different license or notice in front of the player than the one in the repository, so these tests fail on any
 * difference. Read from source, relative to this module, like the other source pins.
 */
class LegalNoticeFilesTest {

    private val repoRoot: File = File("..").canonicalFile

    private fun repoFile(path: String): File = File(repoRoot, path)

    private fun text(path: String): String {
        val file = repoFile(path)
        assertTrue("$path must exist", file.isFile)
        return file.readText()
    }

    @Test
    fun `the notice bundled in the Mimir app is identical to the root notice`() {
        assertEquals(text("NOTICE"), text("app/src/main/assets/legal/NOTICE"))
    }

    @Test
    fun `the license bundled in the Mimir app is identical to the root license`() {
        assertEquals(text("LICENSE"), text("app/src/main/assets/legal/LICENSE"))
    }

    @Test
    fun `the Tweaker license and its bundled copy are identical to the root license`() {
        val root = text("LICENSE")
        assertEquals(root, text("mimir-tweaker/LICENSE"))
        assertEquals(root, text("mimir-tweaker/app/src/main/assets/legal/LICENSE"))
    }

    @Test
    fun `the Tweaker notice bundled in the Tweaker app is identical to its notice`() {
        assertEquals(text("mimir-tweaker/NOTICE.md"), text("mimir-tweaker/app/src/main/assets/legal/NOTICE.md"))
    }

    @Test
    fun `the license is the GNU General Public License version 3 text`() {
        val license = text("LICENSE")
        assertTrue(license.contains("GNU GENERAL PUBLIC LICENSE"))
        assertTrue(license.contains("Version 3, 29 June 2007"))
        assertEquals("the standard text is 674 lines; an edited copy is not the canonical text", 674, license.lines().dropLastWhile { it.isEmpty() }.size)
    }

    @Test
    fun `both notices state the license of the app and the vendored zstd license is reproduced`() {
        val notice = text("NOTICE")
        listOf("NOTICE", "mimir-tweaker/NOTICE.md").forEach { path ->
            val body = text(path)
            assertTrue("$path must name the copyright holder", body.contains("Copyright (C) 2026 Mimir-apk"))
            assertTrue("$path must say GPL-3.0-or-later", body.contains("GPL-3.0-or-later"))
            assertTrue("$path must state the warranty position", body.contains("ABSOLUTELY NO WARRANTY"))
        }
        val zstdLicense = text("core-metadata/src/main/cpp/vendor/libchdr/deps/zstd-1.5.7/LICENSE")
        assertTrue("the zstd license file must be reproduced in NOTICE without change", notice.contains(zstdLicense.trim()))
    }

    @Test
    fun `the notice does not refer to files the public repository does not have or to a settled-later license`() {
        val notice = text("NOTICE")
        listOf(".agents", "MIMIR_", "evidence packet", "Mimir is not", "separate owner decision").forEach { stale ->
            assertFalse("NOTICE must not contain \"$stale\"", notice.contains(stale))
        }
    }

    @Test
    fun `the notice counts the bundled identity assets as they are on disk`() {
        val onDisk = repoFile("app/src/main/assets/dat").listFiles().orEmpty().count { it.isFile }
        assertTrue("NOTICE says 47 identity assets", text("NOTICE").contains("holds $onDisk files"))
    }

    private val sourceUrlRe = Regex("const val MimirSourceUrl\\s*=\\s*\"([^\"]+)\"")

    @Test
    fun `Mimir and the Tweaker show the same source address`() {
        val mimir = sourceUrlRe.find(text("feature-settings/src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/LegalNotice.kt"))?.groupValues?.get(1)
        val tweaker = sourceUrlRe.find(text("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/LegalNotice.kt"))?.groupValues?.get(1)
        assertNotNull("feature-settings must define MimirSourceUrl", mimir)
        assertNotNull("the Tweaker must define MimirSourceUrl", tweaker)
        assertEquals("https://github.com/Mimir-apk/mimir-alpha", mimir)
        assertEquals(mimir, tweaker)
    }

    @Test
    fun `the Tweaker reads the legal assets it bundles`() {
        val tweaker = text("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/LegalNotice.kt")
        assertTrue(tweaker.contains("\"legal/NOTICE.md\""))
        assertTrue(tweaker.contains("\"legal/LICENSE\""))
        assertTrue(repoFile("mimir-tweaker/app/src/main/assets/legal/NOTICE.md").isFile)
        assertTrue(repoFile("mimir-tweaker/app/src/main/assets/legal/LICENSE").isFile)
    }
}
