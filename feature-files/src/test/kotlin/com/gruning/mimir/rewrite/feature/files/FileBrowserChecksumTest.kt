package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two things are pinned here, and they are the two halves of the report: the row has real states rather
 * than one constant string, and a full 40-character SHA-1 has somewhere to live that does not truncate it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserChecksumTest {
    private val english = filesEnglish()

    private val sha1 = "da39a3ee5e6b4b0d3255bfef95601890afd80709"

    // ── the four states ──

    @Test
    fun `nothing asked reads the design's idle copy and offers Verify`() {
        val row = fileBrowserChecksumRow(english, FileBrowserChecksumState.NotChecked, focusedPath = "/root/a.gba")

        assertEquals(english.getString(FileBrowserChecksumIdleLabel), row.status)
        assertEquals("", row.digest)
        assertEquals(FileBrowserChecksumTone.IDLE, row.tone)
        assertEquals(english.getString(FileBrowserVerifyLabel), row.verifyLabel)
    }

    @Test
    fun `checking says so, pastes nothing yet, and the button says Reading`() {
        val row = fileBrowserChecksumRow(english,
            FileBrowserChecksumState.Checking("/root/a.gba"),
            focusedPath = "/root/a.gba",
        )

        assertEquals(english.getString(FileBrowserChecksumCheckingLabel), row.status)
        assertEquals("a digest that does not exist yet must not be drawn", "", row.digest)
        assertEquals(FileBrowserChecksumTone.PENDING, row.tone)
        assertEquals(english.getString(FileBrowserVerifyRunningLabel), row.verifyLabel)
    }

    /**
     * The digest result: the digest lands in the row, **whole**. `status` carries the
     * algorithm rather than a verdict — this tree has no DAT lookup wired, so the design's own
     * `'· matches No-Intro'` (`Mimir Files.dc.html:813`) is deliberately not ported.
     */
    @Test
    fun `a finished check pastes the complete digest and names the algorithm`() {
        val row = fileBrowserChecksumRow(english,
            FileBrowserChecksumState.Checked("/root/a.gba", algorithm = "SHA-1", digest = sha1),
            focusedPath = "/root/a.gba",
        )

        assertEquals("SHA-1", row.status)
        assertEquals("the digest is pasted verbatim -- no prefix, no ellipsis, no 8-char stub", sha1, row.digest)
        assertEquals(40, row.digest.length)
        assertEquals(FileBrowserChecksumTone.RESULT, row.tone)
        assertEquals(english.getString(FileBrowserVerifyAgainLabel), row.verifyLabel)
    }

    @Test
    fun `a failure says what failed, and falls back to a claim that is certainly true`() {
        val named = fileBrowserChecksumRow(english,
            FileBrowserChecksumState.Failed("/root/a.gba", reason = "This file cannot be read"),
            focusedPath = "/root/a.gba",
        )
        assertEquals("This file cannot be read", named.status)
        assertEquals(FileBrowserChecksumTone.FAILURE, named.tone)

        val bare = fileBrowserChecksumRow(english,
            FileBrowserChecksumState.Failed("/root/a.gba"),
            focusedPath = "/root/a.gba",
        )
        assertEquals(english.getString(FileBrowserChecksumFailedLabel), bare.status)
        assertEquals(FileBrowserChecksumTone.FAILURE, bare.tone)
        assertEquals("a failed check has no digest to show", "", bare.digest)
    }

    // ── the path guard ──

    /**
     * The correctness half. A checksum is a claim about ONE file; the inspector re-points at whatever is
     * focused. Without this, moving focus one tile left leaves the previous file's 40 hex characters
     * sitting under the new file's name — a wrong claim about the player's own data, and strictly worse
     * than the stale "Not checked" this whole item replaces.
     */
    @Test
    fun `a digest is never drawn under a different file's name`() {
        val row = fileBrowserChecksumRow(english,
            FileBrowserChecksumState.Checked("/root/a.gba", algorithm = "SHA-1", digest = sha1),
            focusedPath = "/root/b.gba",
        )

        assertEquals(english.getString(FileBrowserChecksumIdleLabel), row.status)
        assertEquals("", row.digest)
        assertEquals(FileBrowserChecksumTone.IDLE, row.tone)
    }

    @Test
    fun `nothing focused shows nothing, in every state`() {
        listOf(
            FileBrowserChecksumState.NotChecked,
            FileBrowserChecksumState.Checking("/root/a.gba"),
            FileBrowserChecksumState.Checked("/root/a.gba", "SHA-1", sha1),
            FileBrowserChecksumState.Failed("/root/a.gba", "nope"),
        ).forEach { state ->
            val row = fileBrowserChecksumRow(english, state, focusedPath = null)
            assertEquals("$state must collapse to idle with nothing focused", english.getString(FileBrowserChecksumIdleLabel), row.status)
            assertEquals("", row.digest)
        }
    }

    /**
     * The unwired default is the whole reason the sink is a parameter rather than a controller latch:
     * with `app/` not yet reporting, the row must render exactly what it rendered before F-05 existed,
     * never a "Reading…" that no one will ever finish.
     *
     * **The source assertion is the load-bearing one and was added after this test was caught being
     * vacuous.** Without it this test only restated `nothing asked reads the design's idle copy` — the
     * SURFACE could have dropped the default entirely, or defaulted to `Checking`, and nothing here
     * would have noticed. The parameter's default IS the subject.
     */
    @Test
    fun `the unwired default is indistinguishable from the row this replaced`() {
        val source = surfaceSource()
        assertTrue(
            "FileBrowserSurface must default `checksum` to NotChecked: an un-updated caller has to " +
                "render the old row, not claim work that no one started",
            source.contains("checksum: FileBrowserChecksumState = FileBrowserChecksumState.NotChecked"),
        )

        val row = fileBrowserChecksumRow(english, FileBrowserChecksumState.NotChecked, focusedPath = "/root/a.gba")
        assertEquals(english.getString(FileBrowserChecksumIdleLabel), row.status)
        assertEquals(english.getString(FileBrowserVerifyLabel), row.verifyLabel)
        assertEquals(FileBrowserChecksumTone.IDLE, row.tone)

        // ...and the same COLOUR, which is the half a data assertion cannot see. The row already drew
        // its status in textPrimary; muting the idle case would have been a silent, un-asked-for visual
        // delta on the established default appearance. Same discipline as core-ui's
        // `mimirNorseBookPlateSafeTopDp`: keep the default path literally, not merely equivalently.
        val arm = source.lines()
            .map { it.trim() }
            .filterNot { it.startsWith("//") }
            .first { it.startsWith("FileBrowserChecksumTone.IDLE") }
        assertTrue("the idle status must stay palette.textPrimary, the colour the row already used", arm.contains("palette.textPrimary"))
        assertFalse("and must not be quietly muted", arm.contains("palette.textMuted"))
    }

    // ── the layout arithmetic: why the digest gets its own line ──

    /**
     * **The measurement that made the fix a two-line row rather than a wider one.** Plex Mono advances
     * exactly 0.600 em (`MimirFonts.MonoAdvanceRatio`, read out of the shipped TTF's `hmtx` table), so
     * this is arithmetic, not an estimate.
     *
     * The lower bound is deliberately generous to the alternative: it grants the value the ENTIRE
     * inspector minus the label gutter, with the Verify button costing nothing. It still does not fit —
     * which is why the previous row's `maxLines = 1` + `TextOverflow.Ellipsis` was the truncating Toast
     * moved indoors.
     */
    @Test
    fun `a 40-char SHA-1 fits the inspector's width but never fits beside the label`() {
        val digestWidth = fileBrowserChecksumDigestWidthDp(40)

        assertEquals("40 chars x 10sp x 0.600 em", 240f, digestWidth, 0.001f)
        assertTrue(
            "the digest must fit the 270dp inspector on its own line -- pasting it is the whole item",
            digestWidth <= FileBrowserInspectorWidthDp,
        )
        assertFalse(
            "and it must NOT fit beside the 70dp label, even before the Verify button is drawn: that " +
                "is why it is a second line and not a wider first one",
            digestWidth <= FileBrowserInspectorWidthDp - FileBrowserInspectorLabelWidthDp,
        )
    }

    /**
     * The wiring half. The pure model above can be perfectly correct while the render still truncates,
     * and no assertion on the data would notice — which is exactly how this defect shipped the first
     * time. Source-text, because the row is a private `@Composable` and this module runs plain JUnit
     * (the established fallback `FileBrowserSupportDimRenderTest` documents).
     *
     * **Comments are stripped before scanning**, the same discipline `tools/mimir_ui_invariants_gate.py`
     * applies for the same reason: on its first run this test failed on the render's OWN comment, which
     * says the digest line must never carry `maxLines = 1`. A source scanner that cannot tell prose from
     * code fails whenever someone documents the rule it enforces.
     */
    @Test
    fun `the render pastes the digest whole -- no maxLines 1, no ellipsis, on the digest line`() {
        val source = surfaceSource()
        val start = source.indexOf("if (row.digest.isNotBlank()) {")
        assertTrue("InspectorChecksumRow must draw the digest on its own line", start > 0)
        val digestLine = source.substring(start, minOf(source.length, start + 700)).withoutComments()

        assertTrue(
            "the digest must be drawn in MimirFonts.Mono -- a hash is read by column comparison",
            digestLine.contains("fontFamily = MimirFonts.Mono"),
        )
        assertFalse(
            "F-05: the digest line must never carry `maxLines = 1` -- truncating it here reproduces " +
                "the cut Toast one layer further in, which is the exact defect reported",
            digestLine.contains("maxLines = 1"),
        )
        assertFalse(
            "F-05: nor `TextOverflow.Ellipsis` -- the design's own row does ellipsise (line 199), but " +
                "its mockup only ever holds an 8-character stub",
            digestLine.contains("TextOverflow.Ellipsis"),
        )
        assertTrue(
            "the digest line must span the panel, not a weighted remainder of the label row",
            digestLine.contains("fillMaxWidth()"),
        )
    }

    /** The Verify button's label must come from the row model, or the three states stop being visible
     * the moment nothing else on the row changes. */
    @Test
    fun `the Verify button reads its label from the row rather than a literal`() {
        val source = surfaceSource()

        assertTrue(
            "InspectorChecksumRow must pass `row.verifyLabel`, not a hardcoded \"Verify\"",
            source.contains("row.verifyLabel"),
        )
        assertFalse(
            "the pre-F-05 literal must be gone: a button frozen at \"Verify\" is how a live row still " +
                "reads as a dead one",
            source.contains("FileBrowserButton(\"Verify\""),
        )
    }

    // ── V-2: the digest becomes something he can take away ──

    /**
     * **The gate.** Copying the checksum is available only once a checksum exists. A copy control
     * over `Not checked` would be the dead-control class this module refuses on principle — and the
     * report that opened F-05 was, at bottom, a control that did nothing useful when pressed.
     */
    @Test
    fun `copy is offered exactly when there is a digest, and never otherwise`() {
        val checked = fileBrowserChecksumRow(english,
            FileBrowserChecksumState.Checked("/root/a.gba", "SHA-1", sha1),
            focusedPath = "/root/a.gba",
        )
        assertTrue("a finished check is the only state with something to copy", fileBrowserChecksumCopyable(checked))

        listOf(
            "nothing asked" to FileBrowserChecksumState.NotChecked,
            "still reading" to FileBrowserChecksumState.Checking("/root/a.gba"),
            "it failed" to FileBrowserChecksumState.Failed("/root/a.gba", "This folder cannot be read"),
        ).forEach { (why, state) ->
            assertFalse(
                "$why -- offering Copy here would be a button with nothing behind it",
                fileBrowserChecksumCopyable(fileBrowserChecksumRow(english, state, focusedPath = "/root/a.gba")),
            )
        }

        assertFalse(
            "and a digest belonging to a DIFFERENT file collapses to the idle row, so there is nothing " +
                "to copy there either -- the path guard does this half for free",
            fileBrowserChecksumCopyable(
                fileBrowserChecksumRow(english,
                    FileBrowserChecksumState.Checked("/root/a.gba", "SHA-1", sha1),
                    focusedPath = "/root/b.gba",
                ),
            ),
        )
    }

    /**
     * **A refused press and a copy affordance can never be on screen together**, and it is by
     * construction rather than by a rule someone has to remember: `fileBrowserVerifyRefusalRow` only ever
     * rewrites a row whose tone is IDLE, and an IDLE row carries no digest.
     */
    @Test
    fun `the refusal notice and the copy control are disjoint by construction`() {
        val refused = fileBrowserVerifyRefusalRow(english,
            row = fileBrowserChecksumRow(english, FileBrowserChecksumState.NotChecked, "/root/a.gba"),
            refusedPath = "/root/a.gba",
            focusedPath = "/root/a.gba",
        )
        assertEquals("precondition: the refusal did fire", english.getString(FileBrowserVerifyRefusedLabel), refused.status)
        assertFalse(
            "V-2's own requirement -- the prompt must not become a second place that can silently say " +
                "nothing. It cannot: there is no digest to copy on a row that refused a press.",
            fileBrowserChecksumCopyable(refused),
        )

        val checked = fileBrowserChecksumRow(english,
            FileBrowserChecksumState.Checked("/root/a.gba", "SHA-1", sha1),
            "/root/a.gba",
        )
        assertEquals(
            "and in the other direction: a real answer outranks a stale refusal, so a copyable row is " +
                "never overwritten by one",
            checked,
            fileBrowserVerifyRefusalRow(english, checked, refusedPath = "/root/a.gba", focusedPath = "/root/a.gba"),
        )
    }

    /** The clipboard gets the hash and nothing else. A `"SHA-1 …"` prefix breaks the one thing anyone
     * does with a digest: paste it into a search box. */
    @Test
    fun `the clipboard payload is the bare digest`() {
        val row = fileBrowserChecksumRow(english,
            FileBrowserChecksumState.Checked("/root/a.gba", "SHA-1", sha1),
            "/root/a.gba",
        )
        val payload = fileBrowserChecksumCopyPayload(row)

        assertEquals(sha1, payload)
        assertEquals("all 40 characters, never a stub", 40, payload.length)
        assertFalse("no algorithm prefix", payload.contains("SHA"))
        assertFalse("no whitespace to break a paste", payload.any(Char::isWhitespace))
        assertEquals(
            "and nothing to copy means an empty payload, so a caller that ignores the gate still cannot " +
                "put a lie on the clipboard",
            "",
            fileBrowserChecksumCopyPayload(fileBrowserChecksumRow(english, FileBrowserChecksumState.NotChecked, "/root/a.gba")),
        )
    }

    /**
     * **The acknowledgement, and how it clears itself.** The bug log's requirement is that a silent copy
     * is indistinguishable from a dead button. The button answers in its own word — the same device the
     * Verify button beside it already uses — and because the acknowledgement is keyed on the DIGEST it
     * needs no clock and no clearing call.
     */
    @Test
    fun `the copy control acknowledges, and stops as soon as the digest it refers to leaves`() {
        val row = fileBrowserChecksumRow(english,
            FileBrowserChecksumState.Checked("/root/a.gba", "SHA-1", sha1),
            "/root/a.gba",
        )
        val other = "b".repeat(40)

        assertEquals(
            "before a press it invites the copy",
            english.getString(FileBrowserChecksumCopyLabel),
            fileBrowserChecksumCopyLabel(english, row, copiedDigest = null),
        )
        assertEquals(
            "after it, the button says so -- a copy the player cannot see is a button they will press again",
            english.getString(FileBrowserChecksumCopiedLabel),
            fileBrowserChecksumCopyLabel(english, row, copiedDigest = sha1),
        )
        assertEquals(
            "move the ring and the row's digest is another file's, so the acknowledgement stops matching " +
                "with no clearing call anywhere",
            english.getString(FileBrowserChecksumCopyLabel),
            fileBrowserChecksumCopyLabel(english,
                fileBrowserChecksumRow(english, FileBrowserChecksumState.Checked("/root/b.gba", "SHA-1", other), "/root/b.gba"),
                copiedDigest = sha1,
            ),
        )
        assertEquals(
            "and an idle row can never read Copied, whatever was copied before",
            english.getString(FileBrowserChecksumCopyLabel),
            fileBrowserChecksumCopyLabel(english,
                fileBrowserChecksumRow(english, FileBrowserChecksumState.NotChecked, "/root/a.gba"),
                copiedDigest = sha1,
            ),
        )
        assertNotEquals(english.getString(FileBrowserChecksumCopyLabel), english.getString(FileBrowserChecksumCopiedLabel))
    }

    /**
     * **Why one 270 dp line can hold a label, a word, and TWO buttons.** The status word and the copy
     * button are anti-correlated by construction: a digest exists only in `Checked`, and that is exactly
     * the state whose status is the short algorithm name rather than a sentence. So `Copy` appears the
     * moment `Not checked` / `Verify did not start — try again` has been replaced by `SHA-1`.
     */
    @Test
    fun `the long status words and the copy button are never on the row together`() {
        val states = listOf(
            FileBrowserChecksumState.NotChecked,
            FileBrowserChecksumState.Checking("/root/a.gba"),
            FileBrowserChecksumState.Checked("/root/a.gba", "SHA-256", "c".repeat(64)),
            FileBrowserChecksumState.Failed("/root/a.gba", "This folder cannot be read"),
        )
        states.forEach { state ->
            val row = fileBrowserChecksumRow(english, state, "/root/a.gba")
            if (fileBrowserChecksumCopyable(row)) {
                assertEquals(
                    "a copyable row's status is the ALGORITHM -- short by nature. If that ever stops " +
                        "being true the two-button line stops fitting, and this is where it shows.",
                    FileBrowserChecksumTone.RESULT,
                    row.tone,
                )
                assertTrue("and the algorithm is short", row.status.length <= 12)
            }
        }
    }

    // ── V-3: the hit area, and what it must not push ──

    @Test
    fun `the row's controls are floored at the app's own minimum touch target`() {
        assertEquals(
            "V-3 says use the token this app already defines rather than inventing a number",
            MimirSpacing.minTarget.value,
            FileBrowserChecksumActionMinWidthDp,
            0f,
        )
        assertEquals("which is 48dp", 48f, FileBrowserChecksumActionMinWidthDp, 0f)
        assertTrue(
            "and it must be an increase on what the Verify button had -- the row overrode it down to 27dp",
            FileBrowserChecksumActionMinWidthDp > 27f,
        )
    }

    /**
     * **Target-growth regression check:** growing the target must not displace the surrounding
     * layout or digest. The assertions below must fail if the larger button pushes the digest.
     *
     * It does not push it, and the reason is structural rather than arithmetic: the digest was already on
     * its own full-width line, below this one. Both halves are asserted — the row still fits with two
     * controls on it, AND the digest's own fit is measured against the whole panel exactly as before.
     */
    @Test
    fun `two floored controls fit the row, and the digest's own line is untouched`() {
        assertTrue(
            "label + Verify at the floor, with the gaps -- the row as it stands with nothing to copy",
            fileBrowserChecksumRowFits(actions = 1),
        )
        assertTrue(
            "and with Copy beside it, which is the arrangement V-2 ships",
            fileBrowserChecksumRowFits(actions = 2),
        )
        assertEquals(
            "the status word keeps 80dp at the floor -- 270 - 70 label - 2x48 controls - 3x8 gaps",
            80f,
            fileBrowserChecksumStatusWidthDp(actions = 2),
            0.001f,
        )
        assertFalse(
            "a THIRD control leaves the status 24dp, which cannot hold `SHA-1` at 11sp. This is the " +
                "assertion that should fail if one is added without re-measuring, rather than the " +
                "constants being nudged to match -- and it is the one that caught this budget being " +
                "written as `> 0` the first time.",
            fileBrowserChecksumRowFits(actions = 3),
        )
        assertEquals(24f, fileBrowserChecksumStatusWidthDp(actions = 3), 0.001f)

        assertEquals(
            "the digest is measured against the WHOLE panel, because nothing shares its line -- the " +
                "arithmetic V-3 says to check rather than adjust",
            240f,
            fileBrowserChecksumDigestWidthDp(40),
            0.001f,
        )
        assertTrue(fileBrowserChecksumDigestWidthDp(40) <= FileBrowserInspectorWidthDp)
    }

    // ── the wiring (source pins) ──

    /**
     * **Source pins, and weak ones — said out loud.** They prove the render names these functions. They
     * prove nothing about hit areas or legibility, neither of which any test in this module can certify:
     * there is no Compose harness here. PR #140 nearly shipped a whole feature dead because its rules were
     * pinned and its wiring was not, which is why these exist at all.
     */
    @Test
    fun `the copy control is drawn under its gate, and writes the bare digest to the clipboard`() {
        val source = surfaceSource()
        assertTrue(
            "the Copy button must sit behind fileBrowserChecksumCopyable -- otherwise it is drawn over " +
                "\"Not checked\"",
            source.contains("if (fileBrowserChecksumCopyable(row)) {"),
        )
        assertTrue(
            "its label must come from the model, or the acknowledgement is invisible",
            source.contains("fileBrowserChecksumCopyLabel(filesResources(), row, copiedDigest)"),
        )
        assertTrue(
            "and the payload must go through the model rather than reading row.digest at the call site",
            source.contains("onCopy(fileBrowserChecksumCopyPayload(row))"),
        )
        assertTrue(
            "the write itself is Android's clipboard, via Compose's own wrapper",
            source.contains("clipboard.setText(AnnotatedString(digest))"),
        )
        assertTrue("and it records what it copied, so the button can answer", source.contains("copiedDigest = digest"))
    }

    @Test
    fun `no call site can ask for a control below the minimum target`() {
        val source = surfaceSource().withoutComments()
        assertTrue(
            "the button's floor is the token, in both axes",
            source.contains(".heightIn(min = MimirSpacing.minTarget)") &&
                source.contains(".widthIn(min = MimirSpacing.minTarget)"),
        )
        assertFalse(
            "the 27dp override that made this the smallest control on the screen must be gone",
            source.contains("minHeight = 27.dp"),
        )
        assertFalse(
            "and the parameter that allowed it must be gone too -- re-defaulting it would leave the next " +
                "row free to do the same thing again",
            source.contains("minHeight: Dp = "),
        )
        assertFalse(
            "including the old 32dp default, which was itself under target",
            source.contains("defaultMinSize(minHeight = 32.dp)"),
        )
    }

    /** Line comments and KDoc removed, so a scanner cannot fail on prose that merely NAMES the literal
     * it forbids. Crude on purpose -- it is not a Kotlin lexer, and it does not need to be for a window
     * this small. */
    private fun String.withoutComments(): String = lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    private fun surfaceSource(): String {
        val name = "FileBrowserSurface.kt"
        val candidates = listOf(
            java.io.File("feature-files/src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$name"),
            java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$name"),
        )
        return candidates.firstOrNull(java.io.File::isFile)?.readText()
            ?: error("$name not found from ${java.io.File(".").absolutePath}")
    }
}
