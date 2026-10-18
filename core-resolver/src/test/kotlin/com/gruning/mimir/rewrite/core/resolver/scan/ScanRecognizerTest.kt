package com.gruning.mimir.rewrite.core.resolver.scan

import com.gruning.mimir.rewrite.core.model.ContentKind
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.physicalResidentKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanRecognizerTest {

    private val recognizer = ScanRecognizer()

    @Test
    fun `hidden Mimir index and hidden-folder game-like files are skipped before classification`() {
        val index = recognizer.recognize(
            walked("resident-index.json", ".mimir/resident-index.json"),
            ROOT,
        )
        val disguised = recognizer.recognize(
            walked("phantom.gcm", "GameCube/.cache/phantom.gcm"),
            ROOT,
        )

        assertEquals(SkipReason.HIDDEN_PATH, (index as RecognitionResult.Skipped).reason)
        assertEquals(SkipReason.HIDDEN_PATH, (disguised as RecognitionResult.Skipped).reason)
    }

    @Test
    fun `known sidecar extensions are skipped outside hidden folders`() {
        val extensions = listOf(
            "json", "xml", "txt", "text", "nfo", "md5", "sha1", "sfv", "rtf", "pdf", "doc", "docx",
            "log", "csv", "tsv", "sbi", "sub", "idx", "mds", "toc",
        )
        for (name in extensions.map { "sidecar.$it" }) {
            val result = recognizer.recognize(walked(name, "GameCube/$name"), ROOT)
            assertEquals(name, SkipReason.SIDECAR_EXTENSION, (result as RecognitionResult.Skipped).reason)
        }
    }

    @Test
    fun `clonecd descriptor is a launchable track manifest while sub remains a sidecar`() {
        val descriptor = recognizer.recognize(
            walked("Resident Evil 2.ccd", "Roms/PSX/Resident Evil 2.ccd", 4096),
            ROOT,
            "Roms/PSX",
        )
        assertEquals(ContentKind.TRACK_MANIFEST, (descriptor as RecognitionResult.Recognized).file.contentKind)
        val sidecar = recognizer.recognize(
            walked("Resident Evil 2.sub", "Roms/PSX/Resident Evil 2.sub", 32_000),
            ROOT,
            "Roms/PSX",
        )
        assertEquals(SkipReason.SIDECAR_EXTENSION, (sidecar as RecognitionResult.Skipped).reason)
    }

    @Test
    fun `side A and B map to stable disc ordinals`() {
        val a = recognizer.recognize(
            walked("D (Side A).chd", "Roms/Saturn/D (Side A).chd"),
            ROOT,
            "Roms/Saturn",
        ) as RecognitionResult.Recognized
        val b = recognizer.recognize(
            walked("D (Side B).chd", "Roms/Saturn/D (Side B).chd"),
            ROOT,
            "Roms/Saturn",
        ) as RecognitionResult.Recognized
        assertEquals(1, a.file.discNumber)
        assertEquals(2, b.file.discNumber)
        assertEquals(ContentKind.DISC_MEMBER, a.file.contentKind)
        assertEquals(ContentKind.DISC_MEMBER, b.file.contentKind)
    }

    @Test
    fun `normal game remains recognized`() {
        val result = recognizer.recognize(walked("Metroid Fusion.gba", "GBA/Metroid Fusion.gba"), ROOT)

        assertTrue(result is RecognitionResult.Recognized)
        assertEquals(ContentKind.GAME, (result as RecognitionResult.Recognized).file.contentKind)
    }

    @Test
    fun `managed removable game persists its exact scanned root ownership`() {
        val root = "/storage/ABCD-1234/ROMs"
        val result = recognizer.recognize(
            managedWalked(
                name = "Metroid Fusion.gba",
                relativePath = "GBA/Metroid Fusion.gba",
                root = root,
            ),
            root,
        ) as RecognitionResult.Recognized

        assertEquals(root, result.file.sourceRootUri)
        assertEquals(
            physicalResidentKey(root, "GBA/Metroid Fusion.gba"),
            result.file.entryKey.value,
        )
        assertEquals("$root/GBA/Metroid Fusion.gba", result.file.managedPath)
    }

    @Test
    fun `new legacy cartridge zip contracts reach their resolver platform`() {
        val cases = mapOf(
            "atari_2600" to PlatformId("atari2600"),
            "atari_7800" to PlatformId("atari7800"),
            "atari_lynx" to PlatformId("atarilynx"),
            "bandai_wonderswan" to PlatformId("wonderswan"),
            "bandai_wonderswan_color" to PlatformId("wonderswancolor"),
        )

        for ((folder, expectedPlatform) in cases) {
            val result = recognizer.recognize(walked("Game.zip", "$folder/Game.zip"), ROOT)
            val recognized = result as RecognitionResult.Recognized
            assertEquals(folder, expectedPlatform, recognized.file.platformId)
        }
    }

    @Test
    fun `roman disc ordinal is recognized consistently`() {
        val result = recognizer.recognize(
            walked("Final Fantasy VII (Disc II).chd", "PSX/Final Fantasy VII (Disc II).chd"),
            ROOT,
        ) as RecognitionResult.Recognized

        assertEquals(ContentKind.DISC_MEMBER, result.file.contentKind)
        assertEquals(2, result.file.discNumber)
    }

    @Test
    fun `Dreamcast cdi extension is not mistaken for roman CD one`() {
        val result = recognizer.recognize(
            walked("Sonic Adventure.cdi", "Dreamcast/Sonic Adventure.cdi"),
            ROOT,
        ) as RecognitionResult.Recognized

        assertEquals(ContentKind.GAME, result.file.contentKind)
        assertEquals(0, result.file.discNumber)
    }

    @Test
    fun `RP5 misclassification corpus fails closed before identity`() {
        val cases = listOf(
            Triple("CFL_DB.dat", "3DS/CFL_DB.dat", SkipReason.FOLDER_EXTENSION_CONFLICT),
            Triple("ubll.lst", "3DS/ubll.lst", SkipReason.FOLDER_EXTENSION_CONFLICT),
            Triple("TombRaider.exe", "STEAM/TombRaider/TombRaider.exe", SkipReason.PLATFORM_CONTEXT_REQUIRED),
            Triple("SCPH1001.BIN", "PS1/BIOS/SCPH1001.BIN", SkipReason.SUPPORT_CONTENT),
            Triple("README.TXT", "README.TXT", SkipReason.SIDECAR_EXTENSION),
            Triple(".DS_Store", ".DS_Store", SkipReason.HIDDEN_PATH),
        )
        for ((name, path, reason) in cases) {
            val result = recognizer.recognize(walked(name, path), ROOT)
            assertEquals(path, reason, (result as RecognitionResult.Skipped).reason)
        }
    }

    @Test
    fun `ROM lab ambiguous and malformed containers are never admitted`() {
        val cases = listOf(
            "Game.rvz" to SkipReason.AMBIGUOUS_PLATFORM,
            "Game.iso" to SkipReason.AMBIGUOUS_PLATFORM,
            "Game.zip" to SkipReason.AMBIGUOUS_PLATFORM,
            "Game.7z" to SkipReason.AMBIGUOUS_PLATFORM,
            "Game.chd" to SkipReason.AMBIGUOUS_PLATFORM,
            "archive.chd.cpgz" to SkipReason.UNKNOWN_EXTENSION,
            "Legend of Zelda.nes 2" to SkipReason.UNKNOWN_EXTENSION,
            "robotbwl.1a" to SkipReason.UNKNOWN_EXTENSION,
        )
        for ((name, reason) in cases) {
            val result = recognizer.recognize(walked(name, name), ROOT)
            assertEquals(name, reason, (result as RecognitionResult.Skipped).reason)
        }
    }

    @Test
    fun `ROM lab title-folder cue and bin remain ambiguous without a platform folder`() {
        val folder = "Sonic Adventure 2 (USA) (EnJaFrDeEs)"
        for (name in listOf("Sonic Adventure 2 (USA).cue", "Sonic Adventure 2 (USA) (Track 1).bin")) {
            val result = recognizer.recognize(walked(name, "$folder/$name"), ROOT)
            assertEquals(name, SkipReason.AMBIGUOUS_PLATFORM, (result as RecognitionResult.Skipped).reason)
        }
    }

    @Test
    fun `Switch support packages skip while ambiguous abbreviated version stays base`() {
        for (name in listOf(
            "Game [0100123412345800][v262144][UPD].nsp",
            "Game [0100123412345001] DLC.nsp",
            "Game Update.nsp",
        )) {
            val result = recognizer.recognize(walked(name, "Switch/$name"), ROOT)
            assertEquals(name, SkipReason.SUPPORT_CONTENT, (result as RecognitionResult.Skipped).reason)
        }

        val portal = recognizer.recognize(
            walked("Portal 2 Upd v1.0.3.nsp", "Switch/Portal 2 Upd v1.0.3.nsp"),
            ROOT,
        ) as RecognitionResult.Recognized
        assertEquals("Portal 2 Upd", portal.file.identity?.title)
    }

    @Test
    fun `ROM lab decomposed Pokemon Switch APP is admitted and UPD is rejected`() {
        val app = "Poke\u0301mon Legends Z-A v1.0.0[0100F43008C44000][v0][APP].nsp"
        val update = "Poke\u0301mon Legends Z-A v2.0.0[0100F43008C44800][v262144][UPD].nsp"
        val base = recognizer.recognize(walked(app, app), ROOT) as RecognitionResult.Recognized
        assertEquals("Pokémon Legends: Z-A", base.file.identity?.title)
        assertEquals(
            SkipReason.SUPPORT_CONTENT,
            (recognizer.recognize(walked(update, update), ROOT) as RecognitionResult.Skipped).reason,
        )
    }

    @Test
    fun `shipping plausibility floors reject truncated payloads`() {
        val gba = recognizer.recognize(walked("Game.gba", "GBA/Game.gba", 127L * 1024), ROOT)
        val nsp = recognizer.recognize(walked("Game [APP].nsp", "Switch/Game [APP].nsp", 1024L), ROOT)
        val empty = recognizer.recognize(walked("Game.nes", "NES/Game.nes", 0L), ROOT)
        assertEquals(SkipReason.IMPLAUSIBLE_SIZE, (gba as RecognitionResult.Skipped).reason)
        assertEquals(SkipReason.IMPLAUSIBLE_SIZE, (nsp as RecognitionResult.Skipped).reason)
        assertEquals(SkipReason.EMPTY_FILE, (empty as RecognitionResult.Skipped).reason)
    }

    @Test
    fun `every finite minimum-size boundary rejects one byte below but not the boundary`() {
        val floors = buildMap {
            listOf("gb", "dmg", "sgb", "gbx", "gbc", "cgb").forEach { put(it, 32L * 1024) }
            listOf("gba", "agb", "nds", "dsi", "ids").forEach { put(it, 128L * 1024) }
            listOf("3ds", "cia", "cci", "cxi", "cfa", "iso", "gcm", "rvz", "wbfs", "cso", "xci", "nsp", "xcz", "nsz")
                .forEach { put(it, 1024L * 1024) }
        }
        for ((ext, floor) in floors) {
            val below = recognizer.recognize(walked("Game.$ext", "Game.$ext", floor - 1), ROOT)
            assertEquals(ext, SkipReason.IMPLAUSIBLE_SIZE, (below as RecognitionResult.Skipped).reason)
            val boundary = recognizer.recognize(walked("Game.$ext", "Game.$ext", floor), ROOT)
            if (boundary is RecognitionResult.Skipped) {
                assertTrue("$ext must pass size gate", boundary.reason != SkipReason.IMPLAUSIBLE_SIZE)
            }
        }
    }

    @Test
    fun `exact support segments do not reject similar game names`() {
        assertTrue(recognizer.recognize(walked("BioShock.nes", "NES/BioShock.nes"), ROOT) is RecognitionResult.Recognized)
        assertTrue(recognizer.recognize(walked("updates.nes", "NES/updates.nes"), ROOT) is RecognitionResult.Recognized)
    }

    private fun walked(name: String, relativePath: String, sizeBytes: Long = 2L * 1024 * 1024): WalkedFile = WalkedFile(
        displayName = name,
        relativePath = relativePath,
        sizeBytes = sizeBytes,
        documentUri = "content://test/$relativePath",
        filePath = null,
    )

    private fun managedWalked(
        name: String,
        relativePath: String,
        root: String,
        sizeBytes: Long = 2L * 1024 * 1024,
    ): WalkedFile = WalkedFile(
        displayName = name,
        relativePath = relativePath,
        sizeBytes = sizeBytes,
        documentUri = null,
        filePath = "$root/$relativePath",
    )

    private companion object {
        const val ROOT = "content://test/tree/roms"
    }
}
