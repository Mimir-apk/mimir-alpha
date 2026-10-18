package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NoShizukuWiringTest {
    @Test
    fun `main sources and manifests contain no Shizuku route references`() {
        var repoRoot: File? = File("").absoluteFile
        while (repoRoot != null && !File(repoRoot, "settings.gradle.kts").isFile) {
            repoRoot = repoRoot.parentFile
        }
        assertTrue("repository root with settings.gradle.kts was not found", repoRoot != null)
        val root = repoRoot!!
        assertTrue(
            "app main manifest was not found under ${root.absolutePath}",
            File(root, "app/src/main/AndroidManifest.xml").isFile,
        )

        val roots = root.listFiles().orEmpty()
            .filter(File::isDirectory)
            .map { File(it, "src/main") }
            .filter(File::isDirectory) + listOf(File(root, "app/src/lab"))
            .filter(File::isDirectory)
        val files = roots.flatMap { root ->
            root.walkTopDown().filter { file ->
                file.isFile && file.extension in setOf("kt", "aidl", "xml")
            }.toList()
        }
        assertTrue("no main-source files were scanned", files.isNotEmpty())

        val reference = Regex("(?i)shizuku|rikka\\.|moe\\.shizuku")
        val offenders = files.filter { file ->
            reference.containsMatchIn(withoutComments(file.readText(), file.extension))
        }.map(File::getPath)

        assertTrue("Shizuku route reference remains in main source or a manifest: $offenders", offenders.isEmpty())
    }

    private fun withoutComments(source: String, extension: String): String {
        val withoutXml = if (extension == "xml") source.replace(Regex("(?s)<!--.*?-->"), "") else source
        val withoutBlocks = withoutXml.replace(Regex("(?s)/\\*.*?\\*/"), "")
        return withoutBlocks.lines().joinToString("\n") { line -> line.substringBefore("//") }
    }
}
