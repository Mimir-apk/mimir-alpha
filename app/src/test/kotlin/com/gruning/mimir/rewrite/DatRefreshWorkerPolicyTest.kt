package com.gruning.mimir.rewrite

import java.io.ByteArrayInputStream
import java.net.URL
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DatRefreshWorkerPolicyTest {

    @Test
    fun `only an explicit debug request can pass the worker authority gate`() {
        assertTrue(catalogRefreshAuthorized(debugBuild = true, CatalogRefreshDebugAuthority))
        assertFalse(catalogRefreshAuthorized(debugBuild = false, CatalogRefreshDebugAuthority))
        assertFalse(catalogRefreshAuthorized(debugBuild = true, authority = null))
        assertFalse(catalogRefreshAuthorized(debugBuild = true, authority = "legacy-periodic"))
    }

    @Test
    fun `failure budget stops after three total attempts`() {
        assertTrue(catalogRefreshShouldRetry(runAttemptCount = 0))
        assertTrue(catalogRefreshShouldRetry(runAttemptCount = 1))
        assertFalse(catalogRefreshShouldRetry(runAttemptCount = 2))
        assertFalse(catalogRefreshShouldRetry(runAttemptCount = Int.MAX_VALUE))
    }

    @Test
    fun `catalog URL and redirects stay on allowlisted HTTPS hosts`() {
        val current = URL("https://www.gametdb.com/wiitdb.txt?LANG=EN&GAMECUBE=1")
        assertEquals(current, requireAllowedCatalogUrl(current))
        assertEquals(
            "https://gametdb.com/next.txt",
            resolveAllowedCatalogRedirect(current, "https://gametdb.com/next.txt").toString(),
        )
        assertEquals(
            "https://www.gametdb.com/next.txt",
            resolveAllowedCatalogRedirect(current, "/next.txt").toString(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            requireAllowedCatalogUrl(URL("http://www.gametdb.com/wiitdb.txt"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveAllowedCatalogRedirect(current, "https://example.com/catalog.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveAllowedCatalogRedirect(current, "https://www.gametdb.com:8443/catalog.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveAllowedCatalogRedirect(current, null)
        }
    }

    @Test
    fun `response reader enforces declared and streaming byte ceilings`() {
        val exact = "a".repeat(64).toByteArray()
        assertEquals(
            exact.decodeToString(),
            readUtf8Bounded(ByteArrayInputStream(exact), declaredLength = exact.size.toLong(), maxBytes = 64),
        )

        assertThrows(IllegalArgumentException::class.java) {
            readUtf8Bounded(ByteArrayInputStream(exact), declaredLength = 65, maxBytes = 64)
        }
        assertThrows(IllegalArgumentException::class.java) {
            readUtf8Bounded(
                ByteArrayInputStream("b".repeat(65).toByteArray()),
                declaredLength = -1,
                maxBytes = 64,
            )
        }
    }

    @Test
    fun `parser normalizes valid unique ids and rejects malformed rows`() {
        val rows = parseGameTdbRows(
            """
            TITLES = 1
            rmee01 = Mario Kart Wii
            RMEE01 = duplicate
            BAD! = invalid
            GZLE01 = The Legend	of Zelda
            EMPTY1 =
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "GAME_ID\tRMEE01\tMario Kart Wii",
                "GAME_ID\tGZLE01\tThe Legend of Zelda",
            ),
            rows,
        )
    }

    @Test
    fun `catalog replacement is gzip complete and leaves no temporary sibling`() {
        val directory = Files.createTempDirectory("mimir-dat-refresh-test").toFile()
        try {
            val output = directory.resolve("gametdb.idcatgz")
            output.writeText("old-catalog")
            val rows = listOf("GAME_ID\tRMEE01\tMario Kart Wii", "GAME_ID\tGZLE01\tThe Legend of Zelda")

            val length = writeGzippedCatalogAtomically(output, rows)
            val restored = GZIPInputStream(output.inputStream()).bufferedReader().use { it.readText() }

            assertEquals(rows.joinToString("\n"), restored)
            assertEquals(output.length(), length)
            assertTrue(length > 0)
            assertEquals(
                emptyList<String>(),
                directory.listFiles().orEmpty()
                    .filter { it.name != output.name }
                    .map { it.name },
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}
