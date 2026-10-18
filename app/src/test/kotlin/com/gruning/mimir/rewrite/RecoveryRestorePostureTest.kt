package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryRestorePostureTest {
    @Test
    fun `restore service source has no network credential or keystore seams`() {
        val source = serviceSource()

        assertFalse(source.contains("INTERNET"))
        assertFalse(source.contains("Keystore"))
        assertFalse(source.contains("takePersistableUriPermission"))
        assertFalse(source.contains("HttpURLConnection"))
        assertFalse(source.contains("managedPath"))
        assertFalse(source.contains("sourceRootUri"))
        assertFalse(source.contains("relativePath"))
        assertFalse(source.contains("game_id_aliases"))
        assertFalse(source.contains("logical_game_groups"))
    }

    @Test
    fun `restore seams persist only typed local ids and accepted android packages`() {
        val source = serviceSource()

        assertTrue(source.contains("promoteGroupIfAbsent"))
        assertTrue(source.contains("USER_DISMISSED"))
        assertTrue(source.contains("buildRestoreSeams"))
        assertTrue(source.contains("safetyArchive"))
        assertTrue(source.contains("Mandatory pre-apply safety snapshot"))
    }

    @Test
    fun `document authority accepts content uris only`() {
        assertTrue(isSafeCreatedDocumentUri("content://com.example.documents/document/recovery"))
        assertFalse(isSafeCreatedDocumentUri("file:///sdcard/archive.zip"))
        assertFalse(isSafeCreatedDocumentUri("https://example.invalid/archive.zip"))
        assertFalse(isSafeCreatedDocumentUri("content:///missing-authority"))
        assertFalse(isSafeCreatedDocumentUri("content://user@example.invalid/archive.zip"))
        assertFalse(isSafeCreatedDocumentUri("content://com.example/archive.zip#fragment"))
    }

    private fun serviceSource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/RecoveryRestoreService.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/RecoveryRestoreService.kt"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("RecoveryRestoreService.kt not found for posture test")
        return file.readText()
    }
}
