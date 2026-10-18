package com.gruning.mimir.rewrite.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Native launch seam regression coverage: exact SAF document URI in the locator, form-aware
 * locator selection incl. the new [LaunchLocatorForm.NONE], emulator-aware BIOS truth, and the new
 * [LaunchDispatchFailure.CONTENT_URI_UNAVAILABLE] + preserved native failure metadata.
 */
class SeamCorrectionsTest {

    private val dsEntry = EntryKey("entry:ds:pkmn-white")

    private fun dsFile(
        documentUri: String? = "content://com.android.externalstorage.documents/document/primary%3ARoms%2Fds%2FWhite.nds",
        rootUri: String? = "content://com.android.externalstorage.documents/tree/primary%3ARoms",
        managedPath: String? = null,
    ) = ResidentFile(
        entryKey = dsEntry,
        platformId = PlatformId.DS,
        contentKind = ContentKind.GAME,
        displayName = "White.nds",
        location = ResidentLocationKind.SAF_TREE_GRANT,
        sourceRootUri = rootUri,
        sourceDocumentUri = documentUri,
        managedPath = managedPath,
    )

    // ── Correction 4: exact document URI, form-aware selection ────────────────

    @Test
    fun contentUriFormPrefersExactDocumentUriNotTreeRoot() {
        val f = dsFile()
        // DS/3DS/Switch need the EXACT document, never the tree root.
        assertEquals(f.sourceDocumentUri, f.locatorFor(LaunchLocatorForm.CONTENT_URI))
        assertFalse(f.locatorFor(LaunchLocatorForm.CONTENT_URI) == f.sourceRootUri)
    }

    @Test
    fun filePathFormUsesManagedPath() {
        val f = dsFile(managedPath = "/storage/emulated/0/Mimir/local/Roms/ds/White.nds")
        assertEquals("/storage/emulated/0/Mimir/local/Roms/ds/White.nds", f.locatorFor(LaunchLocatorForm.FILE_PATH))
        // No managed path → no file-path locator available.
        assertNull(dsFile(managedPath = null).locatorFor(LaunchLocatorForm.FILE_PATH))
    }

    @Test
    fun eitherFormPrefersCheapestDirectPathThenDocumentUri() {
        val withPath = dsFile(managedPath = "/storage/emulated/0/Mimir/local/Roms/ds/White.nds")
        assertEquals(withPath.managedPath, withPath.locatorFor(LaunchLocatorForm.EITHER))
        // No path → falls back to the exact document URI (never the bare tree root).
        val noPath = dsFile(managedPath = null)
        assertEquals(noPath.sourceDocumentUri, noPath.locatorFor(LaunchLocatorForm.EITHER))
    }

    @Test
    fun noneFormYieldsEmptyLocatorForOpenEmulatorOnly() {
        assertEquals("", dsFile().locatorFor(LaunchLocatorForm.NONE))
    }

    @Test
    fun bestLocatorPrefersDocumentUriOverTreeRoot() {
        // The default order now prefers the exact document URI ahead of the tree root.
        val f = dsFile(managedPath = null)
        assertEquals(f.sourceDocumentUri, f.bestLocator)
    }

    @Test
    fun launchLocatorFormSetIncludesNone() {
        assertTrue(LaunchLocatorForm.NONE in LaunchLocatorForm.entries)
        assertEquals(4, LaunchLocatorForm.entries.size)
    }

    // ── Correction 2: emulator-aware BIOS ─────────────────────────────────────

    @Test
    fun biosProfileScopedToEmulatorAppliesOnlyToThatEmulator() {
        val nether = EmulatorId.fromNativeLauncherKey("ps2.nethersx2")
        val armsx2 = EmulatorId.fromNativeLauncherKey("ps2.armsx2")
        val scoped = BiosProfile(
            platformId = PlatformId.PS2,
            fileName = "ps2-0230a-20080220.bin",
            emulatorId = nether,
            presence = BiosPresence.MISSING,
        )
        assertTrue(scoped.appliesTo(nether))
        assertFalse(scoped.appliesTo(armsx2))
        // A null target (emulator not yet resolved) never silently drops the requirement.
        assertTrue(scoped.appliesTo(null))
    }

    @Test
    fun platformWideBiosAppliesToEveryEmulator() {
        val wide = BiosProfile(platformId = PlatformId.PS1, fileName = "scph5501.bin", emulatorId = null)
        assertTrue(wide.appliesTo(EmulatorId.fromNativeLauncherKey("ps1.duckstation")))
        assertTrue(wide.appliesTo(EmulatorId.fromNativeLauncherKey("ps1.retroarch.aarch64.swanstation")))
        assertTrue(wide.appliesTo(null))
    }

    // ── Correction 6: new failure value + native metadata ─────────────────────

    @Test
    fun contentUriUnavailableIsADistinctFailure() {
        assertTrue(LaunchDispatchFailure.CONTENT_URI_UNAVAILABLE in LaunchDispatchFailure.entries)
        // Distinct from a wrong-form rejection.
        assertFalse(LaunchDispatchFailure.CONTENT_URI_UNAVAILABLE == LaunchDispatchFailure.LOCATOR_FORM_REJECTED)
    }

    @Test
    fun failedOutcomePreservesNativeMetadata() {
        val meta = mapOf(
            "status" to "error",
            "code" to "CONTENT_URI_UNAVAILABLE",
            "reason" to "content_uri_unavailable",
            "message" to "SAF grant lost",
            "targetPackageKey" to "ds.melonds",
        )
        val failed = LaunchOutcome.Failed(
            reason = LaunchDispatchFailure.CONTENT_URI_UNAVAILABLE,
            targetEmulatorId = EmulatorId.fromNativeLauncherKey("ds.melonds"),
            nativeMetadata = meta,
        )
        assertEquals("content_uri_unavailable", failed.nativeMetadata["reason"])
        assertEquals(meta, failed.nativeMetadata)
        // Optional/defaulted: existing construction without metadata stays valid.
        assertTrue(LaunchOutcome.Failed(LaunchDispatchFailure.BRIDGE_ERROR).nativeMetadata.isEmpty())
    }
}
