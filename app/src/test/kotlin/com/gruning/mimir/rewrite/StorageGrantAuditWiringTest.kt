package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [auditStorageGrants] is only honest if something actually RUNS it, and the prompt is only honest if
 * something actually HOSTS it. `StorageGrantAuditTest` pins the pure decision; this pins the two call
 * sites that make the decision reach the player.
 *
 * Source characterization, not a behavioural test — this module has no Robolectric harness that can
 * construct [MainActivity] and drive a real Activity lifecycle, the same gap
 * [MainActivityPerformanceOverlayResumeWiringTest] documents for its own subject and for the identical
 * reason. The `onResume` audit is necessary even when `onCreate` already audits: a grant revoked while Mimir
 * sat backgrounded must be detected when it resumes.
 */
class StorageGrantAuditWiringTest {

    @Test
    fun `onCreate runs the audit, and only after the database gate has passed`() {
        val source = mainActivitySource()
        val onCreateBody = source.substringAfter("override fun onCreate(savedInstanceState: Bundle?) {")
            .substringBefore("override fun onSaveInstanceState(")

        val gate = onCreateBody.indexOf("DatabaseStartupGateState.failure?.let { failure ->")
        val audit = onCreateBody.indexOf("storageGrantRecovery = StorageGrantRecovery(")
        assertTrue(
            "onCreate must run the storage-grant audit — without it a grant lost while Mimir was not " +
                "running stays invisible until an art lookup silently fails",
            audit >= 0,
        )
        assertTrue("the database startup gate must still be present in onCreate", gate >= 0)
        assertTrue(
            "the audit reads source_roots out of the database, so it must run AFTER the " +
                "DatabaseStartupGateState gate that proves the database opened (gate=$gate, audit=$audit)",
            gate < audit,
        )
    }

    @Test
    fun `onResume runs the same audit`() {
        val source = mainActivitySource()
        val onResumeBody = source.substringAfter("override fun onResume() {")
            .substringBefore("private fun settleReturningGameSession(")
        assertTrue(
            "onResume must re-run the storage-grant audit: a persisted SAF grant can be revoked, " +
                "re-taken by a sibling package, or invalidated by an SD re-mount while this Activity sits " +
                "backgrounded, and onCreate's verdict never re-runs for that",
            onResumeBody.contains("storageGrantRecovery?.audit()"),
        )
    }

    @Test
    fun `MainActivity owns the regrant picker and hands it to the coordinator`() {
        val source = mainActivitySource()
        assertTrue(
            "RECONNECT needs a real ActivityResultLauncher; it must be registered unconditionally as a " +
                "field, like pickFolderProbe, because a launcher cannot be created after STARTED",
            source.contains(
                "private val pickStorageRegrant =\n" +
                    "        registerForActivityResult(ActivityResultContracts.OpenDocumentTree())",
            ),
        )
        assertTrue(
            "the picker must open ON the lost tree (OpenDocumentTree forwards its input to " +
                "DocumentsContract.EXTRA_INITIAL_URI), not on an arbitrary default location",
            source.contains("pickStorageRegrant.launch(android.net.Uri.parse(lostRootUri))"),
        )
        assertTrue(
            "the picked tree must be persisted through the same read|write-then-read-only helper " +
                "onboarding already uses, so the two grant paths cannot drift",
            source.contains("val access = persistOnboardingTreeAccess(this, treeUri)"),
        )
        assertTrue(
            "a regrant result must re-run the audit against live truth rather than assume it worked",
            source.contains("recovery.onPickerResult(treeUri.toString())"),
        )
        assertTrue(
            "onDestroy must detach this instance's recovery owner; identity handling is behavior-tested separately",
            source.contains("storageGrantRecovery?.let(StorageGrantAuditCoordinator::detach)"),
        )
        assertTrue("pending picker identity must survive recreation", source.contains(
            "outState.putString(StorageGrantPendingRootKey, storageGrantRecovery?.pendingRootUri)",
        ))
        assertTrue("creation must restore pending identity", source.contains(
            "pendingRootUri = savedInstanceState?.getString(StorageGrantPendingRootKey)",
        ))
    }

    @Test
    fun `MimirAppHost hosts the prompt and gates it on there being no active overlay`() {
        val source = mimirAppHostSource()
        assertTrue(
            "the prompt must be hosted in MimirAppHost's modal band, beside the other overlay hosts",
            source.contains(
                "StorageGrantPromptHost(\n" +
                    "                        authority = bindings.core.focusAuthority,\n" +
                    "                        visible = activeOverlay == null,\n" +
                    "                    )",
            ),
        )
        val hostIndex = source.indexOf("StorageGrantPromptHost(")
        val overlayHostIndex = source.indexOf("MimirAppOverlayHost(bindings, activeOverlay, reducedMotion)")
        val introIndex = source.indexOf("GameOpeningIntroOverlay()")
        assertTrue(
            "the prompt belongs above the overlay host and below the launch intro — a game opening must " +
                "never be painted over by a storage notice",
            overlayHostIndex in 0 until hostIndex && hostIndex < introIndex,
        )
    }

    @Test
    fun `the prompt uses the single FocusAuthority and palette tokens only`() {
        val source = storageGrantPromptSource()
        assertFalse(
            "Compose's own focusable()/focusTarget() would be a SECOND focus engine — this app has " +
                "exactly one FocusAuthority and Modifier.mimirFocusable is its only entry point",
            Regex("\\.(focusable|focusTarget)\\s*\\(").containsMatchIn(source),
        )
        assertTrue(
            "focus must go through Modifier.mimirFocusable",
            source.contains(".mimirFocusable(authority, focusKey, StorageGrantFocusScope)"),
        )
        assertFalse(
            "no hardcoded hex in a feature surface — every colour comes from MimirColors, the " +
                "LocalMimirPalette facade",
            Regex("Color\\(0x[0-9A-Fa-f]{6,8}\\)").containsMatchIn(source),
        )
        assertTrue(
            "the prompt must push its own focus scope rather than registering keys into whatever scope " +
                "happens to be active (mixed-bounds geometric hazard)",
            source.contains("authority.pushScope(StorageGrantFocusScope)") &&
                source.contains("authority?.discardScope(StorageGrantFocusScope)"),
        )
    }

    private fun mainActivitySource(): String = moduleSource("MainActivity.kt")

    private fun mimirAppHostSource(): String = moduleSource("MimirAppHost.kt")

    private fun storageGrantPromptSource(): String = moduleSource("StorageGrantPrompt.kt")

    private fun moduleSource(fileName: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$fileName not found from ${File(".").absolutePath}")
    }
    @Test
    fun `the shell dispatcher routes pad actions to the prompt when no overlay is open`() {
        val source = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppInputDispatch.kt").readText()
        val overlays = source.indexOf("bindings.overlays.dispatch(action)?.let { return it }")
        val arm = source.indexOf("dispatchStorageGrantPrompt(")
        assertTrue("the overlay stack must still be consulted first", overlays >= 0)
        assertTrue("the storage prompt arm must exist in dispatchMimirInput", arm > overlays)
        assertTrue(
            "the arm must be gated on no open overlay, the condition the host composes under",
            source.substring(overlays, arm).contains("bindings.overlays.top == null"),
        )
    }
}
