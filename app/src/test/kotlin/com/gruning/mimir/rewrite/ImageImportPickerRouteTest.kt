package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reported picker-route mismatch, 2026-09-11.
 *
 * The route is a pure function and is tested as one. The coordinator half is a source-text pin, on
 * [FileBrowserStorePickSafetyTest]'s disclosed idiom: `MimirAppCoordinator` is a `@Composable` the JVM
 * cannot drive, so this proves the wiring is WRITTEN -- one launcher call, inside the SYSTEM arm, and one
 * import body shared by both doors -- not that it executes. The device closes that half.
 */
class ImageImportPickerRouteTest {

    @Test
    fun `with all-files access the Upload opens Mimir's own browser`() {
        assertEquals(ImageImportPickerRoute.IN_APP, imageImportPickerRoute(allFilesAccess = true))
    }

    @Test
    fun `without it the system picker stays -- a live fallback, not a theoretical one`() {
        assertEquals(
            "the in-app browser lists through java.io.File and would show nothing without the permission",
            ImageImportPickerRoute.SYSTEM,
            imageImportPickerRoute(allFilesAccess = false),
        )
    }

    @Test
    fun `every Upload entry point routes through startImageImport, and only its SYSTEM arm reaches the launcher`() {
        val source = appSource("MimirAppCoordinator.kt").readText()

        assertEquals(
            "exactly ONE system-picker launch may exist, and it must live inside startImageImport",
            1,
            Regex("""imageImportLauncher\.launch\(""").findAll(source).count(),
        )
        val body = source
            .substringAfter("fun startImageImport(request: HomeImageImportRequest) {")
            .substringBefore("\n    }\n")
        assertTrue(body.contains("imageImportPickerRoute(allFilesAccess)"))
        assertTrue(body.contains("ImageImportPickerRoute.IN_APP ->"))
        assertTrue(body.contains("ImageImportPickerRoute.SYSTEM ->"))
        assertTrue(
            "the in-app arm must name PICK_IMAGE explicitly -- the PICK default would draw a 'Use this " +
                "folder' row and commit a DIRECTORY into an image decoder",
            body.contains("mode = FileBrowserMode.PICK_IMAGE"),
        )
        assertFalse("the Upload flow must never reach the destructive half", body.contains("FileBrowserMode.MANAGE"))
        assertTrue(body.contains("imageImportLauncher.launch(arrayOf(\"image/*\"))"))

        assertEquals(
            "the three former launch sites (Upload, ArtPick import, HeroPick import) must all call the route",
            3,
            Regex("""(?<!fun )startImageImport\(""").findAll(source).count(),
        )
        assertEquals(
            "both doors must land on the ONE import body -- the launcher result and the in-app pick",
            2,
            Regex("""importPickedImage\(request, """).findAll(source).count(),
        )
        assertTrue(
            "the in-app pick must hand the decoder a file Uri for the chosen path",
            body.contains("Uri.fromFile(File(path))"),
        )
    }

    private fun appSource(fileName: String): File = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
    ).firstOrNull { it.isFile }
        ?: throw AssertionError("$fileName not reachable from the test working directory")
}
