package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.FocusState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **F-09 / C-0 — "the checksum row still says Not checked after a verify."**
 *
 * The happy path was re-traced and found correct: the path string is threaded identically from the
 * focused tile through `Checking(path)` to `Checked(path, …)`, and `fileBrowserChecksumRow`'s guard
 * compares it against that same tile's path. What was never closed is the case where Verify **does not
 * run** — `FileBrowserController.verifyFocusedChecksum` is `focusedTile()?.let { … }` with no else
 * branch, and `focusedTile()` re-reads `authority.state.value` LIVE while the surface drew the row from
 * a `collectAsState()` snapshot. `focusedTileAt`'s own KDoc names that asymmetry. When the two disagree,
 * the press vanishes and the row keeps reading "Not checked" — the observed symptom, with no
 * failure anywhere to show.
 *
 * **Scope note, stated plainly:** `FileBrowserController.kt` belongs to another lane, so the swallow is
 * closed at the call site rather than in `verifyFocusedChecksum` itself. That is the second of the two
 * remedies the spec allows ("the row reports honestly that it could not read the focused file").
 * **F-09 was NOT reproduced** — no device, no Compose harness. What is proven below is that a refused
 * press can no longer render "Not checked", which is true whichever cause wins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserVerifyHonestyTest {
    private val english = filesEnglish()

    private val browseScope = FocusScope("files")
    private val foreignScope = FocusScope("files/queue")

    private val partition: FileBrowserPartition = fileBrowserPartition(
        items = listOf(
            FileItem(name = "Zelda.gba", path = "/root/Zelda.gba", extension = "gba"),
            FileItem(name = "Metroid.gba", path = "/root/Metroid.gba", extension = "gba"),
        ),
        classifyPlatform = { FileBrowserRecognition.None },
    )

    // ─────────────────────────────────────────────────── The live read the controller will make ──

    @Test
    fun `the live path is the focused tile's, when the browse scope owns focus`() {
        assertEquals(
            "/root/Zelda.gba",
            fileBrowserLiveFocusedPath(
                live = FocusState(browseScope, FileBrowserFocus.tileKey("/root/Zelda.gba")),
                browseScope = browseScope,
                partition = partition,
            ),
        )
    }

    @Test
    fun `the live path is null exactly where verifyFocusedChecksum would no-op`() {
        assertNull(
            "a FOREIGN scope owns focus -- this is the case that makes the press a silent no-op, and it " +
                "must be visible here rather than swallowed",
            fileBrowserLiveFocusedPath(
                live = FocusState(foreignScope, FileBrowserFocus.tileKey("/root/Zelda.gba")),
                browseScope = browseScope,
                partition = partition,
            ),
        )
        assertNull(
            "nothing focused at all",
            fileBrowserLiveFocusedPath(FocusState(browseScope, null), browseScope, partition),
        )
        assertNull(
            "the PICK commit affordance holds focus -- it is not a tile and has no path, which is the " +
                "one live instance of the swallow already pinned in FileBrowserControllerMountStateTest",
            fileBrowserLiveFocusedPath(
                live = FocusState(browseScope, FileBrowserFocus.commitKey("/root")),
                browseScope = browseScope,
                partition = partition,
            ),
        )
        assertNull(
            "a key from a listing that has since been replaced",
            fileBrowserLiveFocusedPath(
                live = FocusState(browseScope, FocusKey("files/tile/root/gone.gba")),
                browseScope = browseScope,
                partition = partition,
            ),
        )
    }

    // ─────────────────────────────────────────────────────────── Whether the press fires at all ──

    @Test
    fun `the press fires only when the live target is the file the row was drawn for`() {
        assertTrue(
            "the ordinary case: the snapshot and the live read agree",
            fileBrowserVerifyFires(drawnPath = "/root/Zelda.gba", livePath = "/root/Zelda.gba"),
        )
        assertFalse(
            "no live target -- this is the swallow, and it must now be answered rather than ignored",
            fileBrowserVerifyFires(drawnPath = "/root/Zelda.gba", livePath = null),
        )
        assertFalse(
            "the live target is a DIFFERENT file. Firing here would be strictly worse than the swallow: " +
                "it would put Metroid's digest under Zelda's name, which is the wrong-40-character claim " +
                "FileBrowserChecksumState.path exists to prevent.",
            fileBrowserVerifyFires(drawnPath = "/root/Zelda.gba", livePath = "/root/Metroid.gba"),
        )
        assertFalse(
            "no row was drawn for any file",
            fileBrowserVerifyFires(drawnPath = null, livePath = "/root/Zelda.gba"),
        )
        assertFalse(
            "two nulls are not agreement",
            fileBrowserVerifyFires(drawnPath = null, livePath = null),
        )
    }

    // ───────────────────────────────────────────────────────────────── What a refused press says ──

    /** **The F-09 sentence.** Whatever else changes, this is the assertion the regression reduces
     * to: after a press that did not run, the row must not read "Not checked". */
    @Test
    fun `a refused press can never leave the row reading Not checked`() {
        val idle = fileBrowserChecksumRow(english, FileBrowserChecksumState.NotChecked, "/root/Zelda.gba")
        assertEquals(
            "precondition: the row uses the existing idle copy",
            english.getString(FileBrowserChecksumIdleLabel),
            idle.status,
        )

        val refused = fileBrowserVerifyRefusalRow(english,
            row = idle,
            refusedPath = "/root/Zelda.gba",
            focusedPath = "/root/Zelda.gba",
        )
        assertNotEquals(
            "\"Not checked\" after a press is the ONE thing this row must never say -- it is the whole " +
                "content of F-09",
            english.getString(FileBrowserChecksumIdleLabel),
            refused.status,
        )
        assertEquals(english.getString(FileBrowserVerifyRefusedLabel), refused.status)
        assertEquals(
            "a refusal reads as a failure, not as an idle row",
            FileBrowserChecksumTone.FAILURE,
            refused.tone,
        )
    }

    /** No self-transition to `Checking`. A row that claimed work had started would be the same
     * fabrication class, one layer in — and `onVerifyChecksum` defaults to `{}`, so the claim would be
     * permanent under an unwired caller. */
    @Test
    fun `a refusal never claims work started`() {
        val refused = fileBrowserVerifyRefusalRow(english,
            row = fileBrowserChecksumRow(english, FileBrowserChecksumState.NotChecked, "/root/Zelda.gba"),
            refusedPath = "/root/Zelda.gba",
            focusedPath = "/root/Zelda.gba",
        )
        assertNotEquals(
            "\"Reading…\" would assert a computation this module cannot see start",
            english.getString(FileBrowserChecksumCheckingLabel),
            refused.status,
        )
        assertEquals("and it pastes no digest", "", refused.digest)
    }

    @Test
    fun `a real answer always outranks a stale refusal`() {
        listOf(
            FileBrowserChecksumState.Checking("/root/Zelda.gba"),
            FileBrowserChecksumState.Checked("/root/Zelda.gba", "SHA-1", "a".repeat(40)),
            FileBrowserChecksumState.Failed("/root/Zelda.gba", "This folder cannot be read"),
        ).forEach { state ->
            val row = fileBrowserChecksumRow(english, state, "/root/Zelda.gba")
            assertEquals(
                "once app/ has answered for this file, the refusal is stale and must not overwrite it -- " +
                    "a $state row showing \"${english.getString(FileBrowserVerifyRefusedLabel)}\" would hide a real digest",
                row,
                fileBrowserVerifyRefusalRow(english, row, refusedPath = "/root/Zelda.gba", focusedPath = "/root/Zelda.gba"),
            )
        }
    }

    @Test
    fun `moving the ring clears the refusal with no clearing call`() {
        val idleElsewhere = fileBrowserChecksumRow(english, FileBrowserChecksumState.NotChecked, "/root/Metroid.gba")
        assertEquals(
            "the refusal is keyed by path, so focusing another tile drops it for free -- the same trick " +
                "fileBrowserChecksumRow's own guard uses, and for the same reason: no caller can forget " +
                "a clearing call that does not exist",
            idleElsewhere,
            fileBrowserVerifyRefusalRow(english,
                row = idleElsewhere,
                refusedPath = "/root/Zelda.gba",
                focusedPath = "/root/Metroid.gba",
            ),
        )
        assertEquals(
            "and with nothing focused there is nothing to say it about",
            idleElsewhere,
            fileBrowserVerifyRefusalRow(english, idleElsewhere, refusedPath = "/root/Zelda.gba", focusedPath = null),
        )
    }

    @Test
    fun `with no refusal recorded the row is untouched`() {
        val idle = fileBrowserChecksumRow(english, FileBrowserChecksumState.NotChecked, "/root/Zelda.gba")
        assertEquals(idle, fileBrowserVerifyRefusalRow(english, idle, refusedPath = null, focusedPath = "/root/Zelda.gba"))
    }

    /** The refusal label must not be a paraphrase of the idle one, and must not blame the file — nothing
     * here checked whether the file is readable. */
    @Test
    fun `the refusal wording says only what is certainly true`() {
        assertNotEquals(english.getString(FileBrowserChecksumIdleLabel), english.getString(FileBrowserVerifyRefusedLabel))
        listOf("corrupt", "unreadable", "cannot be read", "failed to read").forEach { blame ->
            assertFalse(
                "\"$blame\" is a claim about the player's file that this path never established -- the " +
                    "press simply did not start",
                english.getString(FileBrowserVerifyRefusedLabel).lowercase().contains(blame),
            )
        }
    }

    /**
     * **Source pin for the refusal label.** Every other test in this file reads [FileBrowserVerifyRefusedLabel] as a
     * symbol, so none of them would have caught a wording change; this is the one place the literal is
     * pinned. Drops "— try again": the Verify button sits on the same row and is itself the retry
     * affordance, so the sentence no longer needs to say it too.
     */
    @Test
    fun `the refusal label is exactly Verify didn't start`() {
        assertEquals("Verify didn't start", english.getString(FileBrowserVerifyRefusedLabel))
    }

    // ───────────────────────────────────────────────────────────── The call site (source pin) ──

    /**
     * The inspector's Verify button must route through the guard.
     *
     * **A source pin, and a weak one — said out loud.** It proves the call site names
     * [fileBrowserVerifyFires] and no longer calls `verifyFocusedChecksum()` unconditionally. It proves
     * nothing about what the player sees. `FileBrowserSupportDimRenderTest` discloses the same limit for
     * the same reason: no Compose harness exists in this module.
     */
    @Test
    fun `the inspector's Verify press cannot be swallowed silently`() {
        val call = surfaceSource().sliceBetween(
            after = "onExtract = { controller.handleInspectorExtractTap() },",
            before = "palette = palette,\n                    modifier = Modifier.width(FileBrowserInspectorWidthDp.dp)",
        )
        assertTrue(
            "the press must be gated by fileBrowserVerifyFires. Found: <$call>",
            call.contains("fileBrowserVerifyFires("),
        )
        assertTrue(
            "and a refused press must record itself so the row can report it",
            call.contains("verifyRefusedFor = focusedTile?.path"),
        )
        assertFalse(
            "`onVerify = { controller.verifyFocusedChecksum() }` -- the unguarded form -- is exactly the " +
                "shape that swallows the press",
            call.contains("onVerify = { controller.verifyFocusedChecksum() }"),
        )
        assertTrue(
            "the row handed to the panel must go through the refusal projection",
            call.contains("fileBrowserVerifyRefusalRow("),
        )
    }

    /**
     * **V-2 asked whether the prompt gives a cheaper way to observe F-09. It does, and this pins the
     * property that makes it work.**
     *
     * F-09 — *"the checksum row still says Not checked after a verify"* — is still **unreproduced**: no
     * device, no Compose harness, and the happy path traces correct. What was missing was a way to tell
     * the two remaining explanations apart by eye, since both leave the row looking idle:
     *
     *  - the press was **swallowed** before it reached the controller (the F-09/C-0 case) — the row now
     *    says [FileBrowserVerifyRefusedLabel], and offers no copy;
     *  - the press **was reported** to `app/` and nothing ever came back — the row stays on its idle copy,
     *    with no refusal and no copy.
     *
     * The copy control is the third state made visible: **it appears if and only if a digest reached this
     * row for the focused file.** So "did the answer arrive?" stops being a question about a word that
     * two different failures can produce, and becomes a question about whether a button is there. Cheap
     * for a player to report, and it costs nothing — the gate already had to exist.
     *
     * Still an observation aid, **not a reproduction and not a fix.**
     */
    @Test
    fun `the three post-press outcomes are distinguishable without a digest to read`() {
        val path = "/root/Zelda.gba"

        val swallowed = fileBrowserVerifyRefusalRow(english,
            row = fileBrowserChecksumRow(english, FileBrowserChecksumState.NotChecked, path),
            refusedPath = path,
            focusedPath = path,
        )
        val neverAnswered = fileBrowserChecksumRow(english, FileBrowserChecksumState.NotChecked, path)
        val answered = fileBrowserChecksumRow(english,
            FileBrowserChecksumState.Checked(path, "SHA-1", "a".repeat(40)),
            path,
        )

        assertNotEquals("a swallowed press must not look like one that was never made", neverAnswered.status, swallowed.status)
        assertFalse("nothing to copy after a swallow", fileBrowserChecksumCopyable(swallowed))
        assertFalse("nor after a press that was reported and never answered", fileBrowserChecksumCopyable(neverAnswered))
        assertTrue(
            "and the copy control is present exactly when an answer arrived -- the observation V-2 asked " +
                "for, at no extra cost",
            fileBrowserChecksumCopyable(answered),
        )
    }

    /** The copy sink must reach the panel, or the button is drawn and wired to nothing. */
    @Test
    fun `the inspector is handed a copy sink and the digest it last copied`() {
        val call = surfaceSource().sliceBetween(
            after = "onExtract = { controller.handleInspectorExtractTap() },",
            before = "palette = palette,\n                    modifier = Modifier.width(FileBrowserInspectorWidthDp.dp)",
        )
        assertTrue("the panel needs the sink. Found: <$call>", call.contains("onCopy = { digest ->"))
        assertTrue("and the acknowledgement it renders", call.contains("copiedDigest = copiedDigest,"))
        assertTrue(
            "a blank payload must never reach the clipboard, whatever the caller does",
            call.contains("if (digest.isNotBlank()) {"),
        )
    }

    // ───────────────────────────────────────────────────────────── Helpers ──

    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", contains(before))
        return substringAfter(after).substringBefore(before)
    }

    private fun surfaceSource(): String {
        val relative = "src/main/kotlin/com/gruning/mimir/rewrite/feature/files/FileBrowserSurface.kt"
        val candidates = listOf(File(relative), File("feature-files/$relative"), File("../feature-files/$relative"))
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("FileBrowserSurface.kt not found from ${File(".").absolutePath}")
    }
}
