package com.gruning.mimir.rewrite.core.resolver

import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionPlatformClassifierTest {

    private val classifier = ExtensionPlatformClassifier()

    @Test
    fun `unique extensions classify from the locked registry`() {
        assertEquals(PlatformId.GBA, classifier.classify(input("Metroid Fusion.gba")))
        assertEquals(PlatformId.GAMECUBE, classifier.classify(input("Melee.gcm")))
        assertEquals(PlatformId.WII, classifier.classify(input("Brawl.wbfs")))
        assertNull(classifier.classify(input("readme.txt")))
    }

    /**
     * **PC Games recognition regression.** A real `Blasphemous 2.steam` shortcut was not recognised on device.
     *
     * the earlier change added the platform and proved `registry.extensionCandidates("steam")` answers `pc_games`.
     * the earlier change added the launch route. **Neither proved the RESOLVER classifies one**, and the resolver is
     * what a scan actually asks. Two green PRs, one unasked question between them.
     *
     * All five, because they arrive from the same writer and a gap in any one is the same defect: GameNative's
     * `FrontendSyncManager.extensionFor` maps STEAM/EPIC/GOG/AMAZON/CUSTOM_GAME to these exactly.
     */
    @Test
    fun `every GameNative shortcut extension classifies as PC Games`() {
        listOf("steam", "epic", "gog", "amazon", "pcgame").forEach { extension ->
            assertEquals(
                "a .$extension shortcut must resolve to the PC Games platform -- the registry knowing the " +
                    "extension is not the same as the resolver classifying the file",
                PlatformId("pc_games"),
                classifier.classify(input("Blasphemous 2.$extension")),
            )
        }
    }

    @Test
    fun `shipping baseline discovers PS3 Xbox and Xbox 360`() {
        val ps3Hint = ExtensionPlatformClassifier.platformHintFromFolder("ROMs/RPCS3/Demon's Souls.pkg")
        assertEquals(PlatformId("ps3"), ps3Hint)
        assertEquals(PlatformId("ps3"), classifier.classifyWithContext(input("Demon's Souls.pkg"), ps3Hint))

        val xboxHint = ExtensionPlatformClassifier.platformHintFromFolder("ROMs/Xemu/Halo.xiso")
        assertEquals(PlatformId("xbox"), xboxHint)
        assertEquals(PlatformId("xbox"), classifier.classifyWithContext(input("Halo.xiso"), xboxHint))

        val xbox360Hint = ExtensionPlatformClassifier.platformHintFromFolder("ROMs/Xenia/Forza.xex")
        assertEquals(PlatformId("xbox360"), xbox360Hint)
        assertEquals(PlatformId("xbox360"), classifier.classifyWithContext(input("Forza.xex"), xbox360Hint))
    }

    @Test
    fun `cso remains ambiguous until an exact PS2 or PSP folder alias resolves it`() {
        assertTrue(classifier.isAmbiguousContainer("CSO"))
        assertNull(classifier.classify(input("God of War.cso")))
        assertNull(classifier.classifyWithContext(input("God of War.cso")))

        val ps2Hint = ExtensionPlatformClassifier.platformHintFromFolder("ROMs/PCSX2/Game.cso")
        assertEquals(PlatformId.PS2, ps2Hint)
        assertEquals(PlatformId.PS2, classifier.classifyWithContext(input("Game.cso"), ps2Hint))

        val pspHint = ExtensionPlatformClassifier.platformHintFromFolder("ROMs/PPSSPP/Game.cso")
        assertEquals(PlatformId.PSP, pspHint)
        assertEquals(PlatformId.PSP, classifier.classifyWithContext(input("Game.cso"), pspHint))
    }

    @Test
    fun `folder matching is exact after normalization and preserves shipping aliases`() {
        assertEquals(
            PlatformId("xbox360"),
            ExtensionPlatformClassifier.platformHintFromFolder("ROMs/Microsoft - Xbox 360/Forza.xex"),
        )
        assertEquals(
            PlatformId.PS2,
            ExtensionPlatformClassifier.platformHintFromFolder("ROMs/Sony - PlayStation 2/Game.iso"),
        )

        assertNull(ExtensionPlatformClassifier.platformHintFromFolder("ROMs/my-xbox360-backups/Game.xex"))
        assertNull(ExtensionPlatformClassifier.platformHintFromFolder("ROMs/psp-archive/Game.cso"))
        assertNull(classifier.classifyWithContext(input("Game.cso"), null))
    }

    @Test
    fun `canonical registry ids retain legacy PlatformId compatibility`() {
        assertEquals(PlatformId.WII, ExtensionPlatformClassifier.platformHintFromFolder("ROMs/Wii/Brawl.wbfs"))
        assertEquals(PlatformId.WII, classifier.classify(input("Brawl.wbfs")))
    }

    @Test
    fun `generic unique extensions require compatible folder context on every entry point`() {
        val dat = input("CFL_DB.dat")
        assertEquals(PlatformResolution.ContextRequired, classifier.resolveWithContext(dat))
        assertNull(classifier.classify(dat))
        assertNull(classifier.classifyWithContext(dat))

        val dreamcast = ExtensionPlatformClassifier.platformHintFromFolder("ROMs/Dreamcast/CFL_DB.dat")
        assertEquals(
            PlatformResolution.Resolved(PlatformId.DREAMCAST),
            classifier.resolveWithContext(dat, dreamcast),
        )
    }

    @Test
    fun `exact contradictory folder wins as a fail-closed conflict`() {
        val threeDs = ExtensionPlatformClassifier.platformHintFromFolder("ROMs/3DS/CFL_DB.dat")
        assertEquals(PlatformId.THREE_DS, threeDs)
        assertEquals(
            PlatformResolution.FolderExtensionConflict,
            classifier.resolveWithContext(input("CFL_DB.dat"), threeDs),
        )
        assertNull(classifier.classifyWithContext(input("CFL_DB.dat"), threeDs))
    }

    @Test
    fun `ambiguous flat containers retain a typed reason`() {
        assertEquals(PlatformResolution.Ambiguous, classifier.resolveWithContext(input("Game.rvz")))
    }

    @Test
    fun `every finite generic extension is context-required through the legacy entry point`() {
        for (ext in ExtensionPlatformClassifier.CONTEXT_REQUIRED_EXTENSIONS) {
            val input = input("payload.$ext")
            assertEquals(ext, PlatformResolution.ContextRequired, classifier.resolveWithContext(input))
            assertNull(ext, classifier.classify(input))
        }
    }

    private fun input(name: String): ResolutionInput = ResolutionInput(
        fileName = name,
        extension = name.substringAfterLast('.', ""),
    )
}
