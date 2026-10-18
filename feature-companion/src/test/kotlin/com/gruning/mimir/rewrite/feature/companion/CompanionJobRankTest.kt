package com.gruning.mimir.rewrite.feature.companion

import com.gruning.mimir.rewrite.core.model.CompanionJobProgress
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5b's rank, pinned as a table rather than an inline condition, so a future edit that reshuffles the
 * `when` cannot silently change which truth wins without a red test:
 *
 * | what is true                              | panel        | line              |
 * |--------------------------------------------|--------------|-------------------|
 * | PLAYER (`NOW_PLAYING`/`RUNNING_APP`) + job  | the payload  | the job           |
 * | no PLAYER truth (`HOME`/`IDLE`) + job       | the job      | — (nothing left)  |
 * | any mode, no job                            | the payload  | — (nothing to say)|
 *
 * The "line: —" rows are deliberate, not an omission: the rank table in the standing (§5b) reads the
 * `HOME` + job case as "screen 1 is already showing the browsing" — the PLACE truth is exactly what
 * screen 1 already shows, so putting it on the line would be the duplication the whole chapter exists
 * to forbid. For `IDLE`, the job running has already falsified `IDLE`'s own claim ("nothing is true"),
 * so there is no residual PLACE truth to hand the line either way. Both land on the same empty line for
 * different reasons, and this file tests the empty line rather than any invented caption for it — no
 * new string exists for either case, and copy remains a surface concern.
 *
 * **The LINE column is now empty in every row.** The app legend was removed to avoid shrinking
 * the platform shelf while an app runs. The running-app legend was the
 * last thing this surface drew under the panel, and it cost the `HOME` lattice a 48 dp touch floor
 * plus padding for as long as a foreign app ran. The PANEL column is unchanged: `RUNNING_APP` still
 * hands it to `HOME`, which is the behavior this file pins.
 */
class CompanionJobRankTest {

    private fun job(kind: String = "SCAN", seen: Int = 1, total: Int? = null) =
        CompanionJobProgress(kind = kind, seen = seen, total = total)

    // ---- companionJobOutranksPayload: the rank as a predicate ----

    @Test
    fun `a job outranks HOME and IDLE for the panel`() {
        assertTrue(companionJobOutranksPayload(CompanionMode.HOME))
        assertTrue(companionJobOutranksPayload(CompanionMode.IDLE))
    }

    @Test
    fun `PLAYER truth outranks the job -- NOW_PLAYING and RUNNING_APP keep the panel`() {
        assertFalse(companionJobOutranksPayload(CompanionMode.NOW_PLAYING))
        assertFalse(companionJobOutranksPayload(CompanionMode.RUNNING_APP))
    }

    @Test
    fun `every CompanionMode has a defined answer -- nothing free-floats between the two outcomes`() {
        // Guards the `when` in companionJobOutranksPayload staying exhaustive rather than falling back
        // to an `else` that would silently absorb a fifth mode into either bucket.
        CompanionMode.entries.forEach { mode ->
            val outranks = companionJobOutranksPayload(mode)
            assertEquals(
                mode == CompanionMode.HOME || mode == CompanionMode.IDLE,
                outranks,
            )
        }
    }

    // ---- a running job never takes the scene: the panel is retired ----

    @Test
    fun `no job, in any mode, at any count, can take the scene`() {
        // **This replaces the demotion and promotion suites, which tested a mechanic that no longer
        // exists.** The panel's RENDERER was retired 2026-08-31 but this SELECTOR went on returning `JobPanel`, so a
        // scan won the scene and then drew nothing: screen 2 faded the shelf out and sat empty for the
        // whole job. The demotion flag could not rescue it either — the tap that set it lived on the
        // panel that no longer draws. Found by the companion-transition review, 2026-09-01.
        //
        // So what is pinned is the only thing left that is true: the scene is ALWAYS the payload.
        // Exhaustive over the modes, and over one job and several, because "one job" and "two jobs"
        // took different branches in the version this replaces.
        CompanionMode.entries.forEach { mode ->
            listOf(
                listOf(job()),
                listOf(job("SCAN"), job("SCRAPE")),
            ).forEach { running ->
                val target = companionRequestedSceneTarget(
                    CompanionPrimaryRoute(), mode, jobs = running,
                )
                assertNotEquals(
                    "$mode with ${running.size} job(s) must not hand the scene to a retired panel",
                    CompanionSceneTarget.JobPanel,
                    target,
                )
                assertTrue("$mode with ${running.size} job(s) must render a payload", target is CompanionSceneTarget.Payload)
            }
        }
    }

    @Test
    fun `a job changes nothing about which payload is shown`() {
        // The stronger half: not merely "not the panel", but IDENTICAL to the jobless answer. A rank
        // that quietly swapped HOME for IDLE while a scan ran would satisfy the test above.
        CompanionMode.entries.forEach { mode ->
            assertEquals(
                "$mode: a running job must not change the scene at all",
                companionRequestedSceneTarget(CompanionPrimaryRoute(), mode),
                companionRequestedSceneTarget(CompanionPrimaryRoute(), mode, jobs = listOf(job())),
            )
        }
    }

    // ---- RUNNING_APP: policy 2026-09-11, the app takes neither the scene nor a line ----

    @Test
    fun `RUNNING_APP always gives the panel to HOME -- nothing can ask for the card any more`() {
        // **The demotion is UNCHANGED.** Its requirement lived in the KDoc of the
        // sentence retired with the legend -- "The HOME lattice remains reachable while the app runs"
        // -- and now lives on the selector itself. What 2026-09-11 removed is the promotion: the
        // legend that could hand the card back is gone, so the half of this test that read "and a tap
        // takes the card back" has no input left to express, not merely no assertion.
        assertEquals(
            "RUNNING_APP hands the panel to HOME",
            CompanionSceneTarget.Payload(CompanionMode.HOME),
            companionRequestedSceneTarget(CompanionPrimaryRoute(), CompanionMode.RUNNING_APP),
        )
    }

    @Test
    fun `the scene can never be RUNNING_APP -- the arm that would draw it is retired`() {
        // **Load-bearing, not pedantic.** The `RUNNING_APP` arm in `renderScene` draws `Unit` now, so
        // a selector that returned this target again would reproduce 2026-08-31's job panel exactly:
        // the scene won and nothing drew. Exhaustive over the whole input space rather than over the
        // RUNNING_APP mode alone -- the drag hold and the job list both feed this function, and
        // "unreachable" has to mean unreachable from all of them.
        CompanionMode.entries.forEach { mode ->
            listOf(true, false).forEach { dragging ->
                listOf(emptyList<CompanionJobProgress>(), listOf(job()), listOf(job("SCAN"), job("SCRAPE")))
                    .forEach { running ->
                        assertNotEquals(
                            "$mode, dragging=$dragging, ${running.size} job(s): nothing may select the retired card",
                            CompanionSceneTarget.Payload(CompanionMode.RUNNING_APP),
                            companionRequestedSceneTarget(
                                CompanionPrimaryRoute(),
                                mode,
                                companionDragging = dragging,
                                jobs = running,
                            ),
                        )
                    }
            }
        }
    }

    @Test
    fun `a running app renders HOME whether or not a job is running`() {
        // This asserted, in turn, that the job took the PANEL from a demoted app (retired 2026-08-31)
        // and then that a demoted app and a promoted one each rendered their own payload (the
        // promotion is retired 2026-09-11). What survives is the half that was always about the APP --
        // screen 1 already shows it, so screen 2 keeps the PLACE -- and the half that was always about
        // the job: a running job does not move the answer.
        listOf(emptyList<CompanionJobProgress>(), listOf(job()), listOf(job("SCAN"), job("SCRAPE")))
            .forEach { running ->
                assertEquals(
                    "RUNNING_APP with ${running.size} job(s) renders HOME",
                    CompanionSceneTarget.Payload(CompanionMode.HOME),
                    companionRequestedSceneTarget(
                        CompanionPrimaryRoute(),
                        CompanionMode.RUNNING_APP,
                        jobs = running,
                    ),
                )
            }
    }

    @Test
    fun `the running-app legend is retired -- nothing composes it and nothing reserves its height`() {
        //  This REPLACES `the running-app sentence coins no new copy`, which
        // pinned `companionRunningAppSentence` -- a function that existed only to feed the legend and
        // is deleted with it. A source pin for the same reason the job line's is one: the defect is a
        // COMPOSED CHILD, and the cheapest instrument that can see it come back reads the call sites.
        val source = java.io.File(
            "src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt",
        ).readText()
        assertFalse(
            "no running-app line may be composed",
            source.contains("CompanionRunningAppLine"),
        )
        assertFalse(
            "and its sentence goes with it -- the line was its only caller",
            source.contains("companionRunningAppSentence"),
        )
        // Excludes the DECLARATION rather than anchoring to the start of a line: the call this retires
        // sat mid-line, as a `when` arm (`CompanionMode.RUNNING_APP -> RunningAppCard(modePayload)`), so
        // a start-of-line anchor would have been a pin that cannot see the thing it is pinning -- the
        // blind instrument the job line's pin already cost this file once. Replayed against the
        // pre-change source to check it actually fires.
        assertFalse(
            "and the card the legend promoted to is no longer composed either",
            Regex("""(?<!fun )RunningAppCard\(""").containsMatchIn(source),
        )
        // **Lattice space regression: the lattice must stop paying for the legend.** The
        // scene column's SECOND CHILD is what shrank the shelf, so what is pinned is that there is no
        // second child at all -- not a filler, not a zero-height slot, not a reserved minimum. Retiring
        // a view and leaving its height reserved is a mistake this file has already paid for once.
        val sceneColumn = source
            .substringAfter("Box(Modifier.weight(1f).fillMaxWidth()) { renderScene(target) }")
            .substringBefore("if (quickLaunchVisible)")
        // A blacklist of the children it might be would be a blind instrument -- it can only see the
        // explicitly listed shapes. Pinned as ANY composable call instead: an indented capitalised call in
        // the slice below is a second child, whatever it is named. `[ \t]` rather than `\s` on purpose,
        // so the class cannot swallow a newline and go hunting on the next line.
        assertFalse(
            "the scene column must keep ONE child -- something is composed where the legend used to sit",
            Regex("""^[ \t]+[A-Z][A-Za-z0-9]*\(""", RegexOption.MULTILINE).containsMatchIn(sceneColumn),
        )
        assertTrue(
            "and the scene itself still takes the whole column",
            source.contains("Box(Modifier.weight(1f).fillMaxWidth()) { renderScene(target) }"),
        )
    }

    @Test
    fun `no bar on this surface draws a fraction it did not compute`() {
        // removed IdleAmbient's LoadingStrip, which drew a hardcoded 42% -- a
        // denominator that does not exist -- on the same panel that ratified "a null total draws NO
        // bar". This is a source pin rather than a render test: it is the cheapest instrument that can
        // see the defect coming back, and the defect is a LITERAL, not a behaviour.
        val source = java.io.File(
            "src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt",
        ).readText()
        assertFalse(
            "LoadingStrip drew a fraction of nothing and was removed; it must not return",
            source.contains("private fun LoadingStrip("),
        )
        // The one bar left is the job's, and its fraction is a real quotient the model computed.
        assertTrue(
            "the job bar must keep taking its fraction as a parameter, never a literal",
            source.contains("private fun CompanionJobProgressBar(fraction: Float"),
        )
    }

    @Test
    fun `the panel names what is selected, and says the phrase only once`() {
        // Policy 2026-08-31. §5b rule 3: the panel must be able to say what is selected in plain
        // language. HOME's tiles render their label at height(0.dp), so the footer seat carries it --
        // paid for by the duplicated "Well of Memory". Source pin: both facts are literals.
        val source = java.io.File(
            "src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt",
        ).readText()
        assertFalse(
            "the runic duplicate paid for the selection name; it must not come back",
            source.contains("ᚹᛖᛚᛚ ᛟᚠ ᛗᛖᛗᛟᚱᚤ"),
        )
        // Since the header consolidation the rune eyebrow
        // is gone from the lattice and the words are the header's tagline: the panel carries no runic literal, never draws the eyebrow, and says the phrase
        // exactly once, as the lowercase tagline after "Mimir".
        assertEquals(
            "the panel carries no literal of the runic line",
            0,
            Regex("WELL OF MEMORY").findAll(source).count(),
        )
        assertEquals(
            "the panel no longer draws the rune eyebrow",
            0,
            Regex("MimirRuneEyebrow").findAll(source).count(),
        )
        assertEquals(
            "the panel says 'the well of memory' exactly once, as the header's tagline",
            1,
            Regex("the well of memory").findAll(source).count(),
        )
        // **The selection name is RETIRED — ** It had a footer seat, then a rail seat, and both were narrating a state
        // the player could already see: the ringed tile. §5b rule 3 asked the panel to be ABLE to say
        // what is selected; the ring says it, and a second telling of it in words is duplicate signaling.
        //
        // What this pins now is that the line carries nothing ambient. The launch message stays because
        // it is not ambient -- it exists only while a launch is in flight or has failed, which is the
        // one thing here the player cannot read off the surface.
        assertTrue(
            "the rail line carries the launch message and nothing ambient",
            source.contains("val railLine = launchMessage\n"),
        )
        assertFalse(
            "no held-seat or cursor name may creep back onto it",
            source.contains("launchMessage ?: heldSeatName"),
        )
    }

    @Test
    fun `the retired arm left nothing behind, and neither did the retired job panel and line`() {
        val source = java.io.File(
            "src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt",
        ).readText()
        // Direct opening replaced the two-beat arm and the VIEW outlived the
        // STATE by months, gated on a flag nothing could set. Deleted 2026-08-31 with its §4-banned
        // pill. A grep pin because the defect is dead code, which no behavioural test can see.
        //
        // **The job panel and the job line joined this list on 2026-09-11.** Both were retired on
        // 2026-08-31 and the composables stayed behind, unreachable, still carrying player-facing copy
        // ("Show the job on the panel", "Hand the panel back to the shelf") -- the copy review's F6.
        // What this used to pin was the panel's overflow guard; a guard on a view that cannot draw is
        // not a law, so the pin inverts the way this file's other retirements did. `NewBookPill` and
        // `RunningAppCard` went in the same pass, for the same reason.
        listOf(
            "armedApp",
            "private fun StatusPill(",
            "private fun EmptyCompanionLine(",
            "private fun CompanionJobPanel(",
            "private fun CompanionJobLine(",
            "private fun NewBookPill(",
            "private fun RunningAppCard(",
        ).forEach {
            assertFalse("$it was dead and was removed; it must not return", source.contains(it))
        }
    }

    @Test
    fun `the retired job panel and line are not composed and the hero budget does not reserve their seat`() {
        // (The hero ceiling this test pinned went with NowPlayingCard, slice A3, below.) Policy 2026-08-31. Box art is 3:4, so the shipped width(250.dp) asked for 333 dp of a
        // 468 dp panel and Compose gave the hero the space first -- the title and platform line
        // measured to nothing, against §5b's law that the panel must always say what is selected in
        // plain language. This pins the ARITHMETIC, which is what actually rots: the constant is a
        // budget, and a budget nobody checks starts clipping the thing it protects.
        // **This assertion used to recompute `lineSeat = 48` from the same wrong premise as the code,
        // so it was GREEN WHILE WRONG.** The job line's real seat is 60 dp — §4's 48 dp minimum plus
        // the 12 dp the call site pads beneath it, which is the OUTER node over the `heightIn`. It now
        // checks the budget's model against the TOKENS THE LAYOUT ACTUALLY USES, so the two cannot
        // drift apart again; a duplicated literal is what let them drift the first time.
        // **The job line is RETIRED and its seat with it —.** What this used to pin
        // was that the budget's model of the line matched the tokens the layout really spent. The line
        // is gone, so the pin becomes the other half of that same law: a retired view's height must
        // stop being spent. A budget that kept reserving 60 dp for something that cannot draw would be
        // the deletion's missing half, which is a mistake this file has made before.
        val source = java.io.File(
            "src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt",
        ).readText()
        // The CALL SITES are what is watched, not the definitions. Both composables are unreachable;
        // cutting them out of a 3,700-line file by hand is what broke this build once already, and an
        // unreachable private composable is a warning rather than a defect.
        assertFalse(
            "no job panel may be composed",
            source.contains("CompanionJobPanel(jobs ="),
        )
        // Anchored to an INDENTED call, so the (unreachable) top-level `private fun CompanionJobLine(`
        // does not match its own retirement. A pin that fires on the definition it is retiring is the
        // same failure to distinguish declarations from calls.
        assertFalse(
            "no job line may be composed",
            Regex("""^\s+CompanionJobLine\(""", RegexOption.MULTILINE).containsMatchIn(source),
        )
        assertFalse(
            "and the hero budget must not still reserve its seat",
            source.contains("- CompanionJobLineSeatDp"),
        )
        // Slice A3 (2026-10-02): the NOW_PLAYING hero card could not run in production (the shelf page projects the payload to HOME and the Activity page's
        // sceneOverride short-circuits first), so the card, its public wrapper and the hero ceiling this test used to pin as arithmetic are deleted. The `when`
        // keeps a NOW_PLAYING arm only because it is exhaustive over the enum.
        assertFalse("the unreachable NowPlayingCard must not return", source.contains("NowPlayingCard"))
        assertFalse("nor its hero budget", source.contains("CompanionCoverHeroMaxHeightDp") || source.contains("CompanionNowPlayingChromeDp"))
    }

    @Test
    fun `no button on this surface wears a pill, and every one is reachable`() {
        val source = java.io.File(
            "src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt",
        ).readText()
        // §4, buttons are rectangles with rounded corners, not pills. Applied
        // throughout the UI -- four pill-shaped buttons had shipped here for months. radiusPill
        // remains a sanctioned token for CHIPS; it is not a shape a button may borrow.
        assertEquals(
            "a 999.dp corner is a pill; no button on the second screen may wear one",
            0,
            Regex("RoundedCornerShape\\(999").findAll(source).count(),
        )
        // §4's 48 dp, on a surface with no controller: every touch affordance must be reachable.
        //
        // The list changed with the 2026-09-11 copy review: "Create Tome" left with the dead `NewBookPill`
        // that was its only carrier (the live create cell is a whole lattice tile, spoken as "Create a
        // Tome"), and "Discard Tome" became a second "Remove Tome" -- one handler, one verb (§2 #9). So a
        // label can now occur MORE THAN ONCE and every occurrence must clear the floor. `substringBefore`
        // graded only the first, and for a label that had vanished it returned the whole file and graded
        // the file's tail -- a pin that could not see what it pinned.
        listOf("Move to shelf", "Remove Tome").forEach { label ->
            val hits = Regex(Regex.escape("\"$label\"")).findAll(source).toList()
            assertTrue("$label must still be a button on this surface", hits.isNotEmpty())
            hits.forEach { hit ->
                val block = source.substring(0, hit.range.first).takeLast(700)
                assertTrue(
                    "$label must carry a 48 dp minimum -- this panel has no controller to fall back on",
                    block.contains("minTarget") || block.contains("48.dp"),
                )
            }
        }
    }

    @Test
    fun `the job bar's gradient is decoration, and nothing on this panel runs forever`() {
        val source = java.io.File(
            "src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt",
        ).readText()
        val bar = source.substringAfter("private fun CompanionJobProgressBar").substringBefore("private fun ")
        // a decorative ramp. The gradient must span the bar's own WIDTH, so
        // the same inks appear at every fraction -- not the fraction, which would make hue a verdict on
        // a magnitude and land it under §4's threshold-colour ban.
        assertTrue("the fill must carry a gradient", bar.contains("Brush.horizontalGradient"))
        assertTrue("spanning the bar's width", bar.contains("endX = size.width"))
        assertFalse(
            "the gradient must not be a function of the fraction -- that would be a colour verdict",
            Regex("(startX|endX)\\s*=\\s*[^,\\n]*fraction").containsMatchIn(bar),
        )
        // And no perpetual producer: this panel's animator count is zero and that is the budget.
        listOf("infiniteRepeatable", "rememberInfiniteTransition", "InfiniteRepeatableSpec").forEach {
            assertFalse("$it would make the job bar the panel's first perpetual producer", bar.contains(it))
        }
    }

    @Test
    fun `the second screen's leather takes the palette, not absolute inks`() {
        val source = java.io.File(
            "src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt",
        ).readText()
        // the fork converges. core-ui's MimirNorseBook uses ZERO absolute
        // colours -- its highlight/shadow lerp against book.onCover / book.coverBottom with the exact
        // coefficients this file had been applying to white and black. A metal that ignores the theme
        // it sits in is the drift; §4's palette rule is the law.
        assertEquals(
            "no absolute ink may shade this surface's leather",
            0,
            Regex("Color\\.(White|Black)").findAll(source).count(),
        )
        // And the uniform-corner rule, which the fork never received: it drew 3/3/8/8.
        assertFalse(
            "the asymmetric cover corner was abolished in 2026-08-09 and must not return",
            source.contains("topStart = 3.dp"),
        )
        assertTrue(
            "the corner comes from core-ui's public rule, derived from the cover's own painted width",
            source.contains("mimirNorseBookCoverCorner(size.width"),
        )
    }

    @Test
    fun `the Tome face is core-ui's cover, parameterised - the local fork and its copies are gone`() {
        val source = java.io.File(
            "src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt",
        ).readText()
        // V11: the fork's leather, signature and emboss are deleted, not hidden.
        listOf("private fun NorseBookCover", "drawCompanionBookSignature", "CompanionBookSignature", "companionBookSignature", "runeShadow", "val emboss").forEach {
            assertFalse("`$it` was the local copy of the canon, and is gone", source.contains(it))
        }
        // The face takes the canon, additive parameters only: no lockup (the tile's nameplate carries the name), the lattice's square, the ring and scale
        // left to the tile (Main's `mimirSelectionRing` is the one ring), the Tome's own palette.
        val face = source.substringAfter("private fun CompanionTomeFace(").substringBefore("/** Empty shelf teaches")
        assertTrue("the face is the canon", face.contains("MimirNorseBookCover("))
        assertTrue("no lockup inside the face", face.contains("showLockup = false"))
        assertTrue("the lattice's square", face.contains("aspectRatio = CompanionTomeTileAspectRatio"))
        assertTrue("the canon's own ring is off: the tile's ring is Main's", face.contains("showFocusRing = false"))
        assertTrue("and the canon's focus scale is off", face.contains("focusScale = 1f"))
        // The tile's `when` arm stays one call, and the face is handed the same condition the ring wears.
        assertTrue("the FOLDER arm calls the face", source.contains("CompanionHomeTileKind.FOLDER -> CompanionTomeFace(tile, focused = cursor && !binding && !isArming && !ghost, palette = palette)"))
        // The tile still owns the ring, the clip, the nameplate and the press response: V6, A1 and B5 are untouched.
        val tile = source.substringAfter("internal fun CompanionHomeGridTile(").substringBefore("internal val LocalCompanionLatticeArmed")
        assertTrue(tile.contains(".mimirSelectionRing("))
        assertTrue(tile.contains("Modifier.clip(CompanionNorseCoverShape)"))
        assertTrue(tile.contains(".mimirPressResponse(pressSource ?: interaction)"))
        assertTrue(tile.contains("maxLines = cell.labelLines.coerceAtLeast(1)"))
    }


    @Test
    fun `the cursor ring is Main's one ring, still at rest, and stands its gleam down while something covers the lattice`() {
        val source = java.io.File(
            "src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt",
        ).readText()
        val tile = source.substringAfter("internal fun CompanionHomeGridTile(").substringBefore("internal val LocalCompanionLatticeArmed")
        // The breath, its 20 Hz ticker and the private 1.4 dp ring are retired.
        assertFalse("the private breathing marker is gone", source.contains("CompanionCursorMarker"))
        assertFalse("the breath's clock is gone", source.contains("CompanionCursorBreath"))
        assertFalse("nothing on the tile ticks: no delay loop, no per-frame clock", tile.contains("delay(") || tile.contains("withFrameNanos") || tile.contains("rememberInfiniteTransition"))
        // The ring is Main's: the modifier before the face's clip, in the face's own corner.
        assertTrue("the face wears mimirSelectionRing", tile.contains(".mimirSelectionRing("))
        assertTrue("the ring is drawn before the clip, because it is drawn outside the face", tile.indexOf(".mimirSelectionRing(") < tile.indexOf("Modifier.clip(CompanionNorseCoverShape)"))
        assertTrue("the ring takes the face's own clip corner", tile.contains("MimirRingShape.Card(companionFaceCorner(tile.kind, cell.faceEdgeDp))"))
        // A covering layer stands the gleam down, as it stood the breath down: the tome from inside, the drawer from the host.
        assertTrue("a covered tile is not armed", tile.contains("{ !covered && hostArmed() }"))
        assertTrue(
            "an open tome covers the lattice",
            source.contains("covered = openFolderId != null || panelCovered"),
        )
        // V6 fix round. The ring follows the cursor ONLY: a press is not a focus change, so `lit` (the press) is not in `focused`.
        val focused = tile.substringAfter(".mimirSelectionRing(").substringBefore("identity =")
        assertTrue("focused is the cursor's, less the drag states: $focused", focused.contains("focused = cursor && !binding && !isArming && !ghost"))
        assertFalse("a press is not a focus change", focused.contains("lit") || focused.contains("pressed"))
        // A cover over a ring that is mid-motion settles it at once: a settled identity (a fresh ring that is whole on its first frame) and no gleam, kept until the cursor leaves.
        assertTrue("a covered cursor tile re-keys onto a settled ring identity", tile.contains("coverSettled -> tile.id + CompanionCoveredRingSuffix"))
        assertTrue("and draws it with no gleam", tile.contains("gleam = if (coverSettled) MimirRingGleam.None else MimirRingGleam.Sweep"))
        // D3: the flag is the tile's (not keyed on the cursor), so a cursor that leaves a settled tile keeps the identity and the ring fades out on the ring clock; any cover sets it
        // (a non-cursor ring mid-fade is cut by a new cover), each arrival is its own identity, and it clears only after that fade, with the cover gone.
        val coverSettle = source.substringAfter("private fun rememberCompanionRingCoverSettle(").substringBefore("private const val CompanionGhostRingSuffix")
        assertTrue("the tile asks the helper", tile.contains("rememberCompanionRingCoverSettle(tile.id, cursor, covered)") && tile.contains("val coverSettled = coverRing.settled"))
        assertTrue("remembered per tile, so the cover leaving replays nothing and the cursor leaving does not drop the ring", coverSettle.contains("remember(tileId) { mutableStateOf(false) }"))
        assertTrue("any cover settles a ring, not only the cursor's", coverSettle.contains("settled = covered || spent.value"))
        assertTrue("each cover arrival is its own ring identity", tile.contains("coverSettled -> tile.id + CompanionCoveredRingSuffix + coverRing.epoch"))
        assertTrue("the flag clears after the whole ring clock, with the cover gone and the cursor away", coverSettle.contains("delay(MimirFocusRingSettleMs.toLong())") && coverSettle.contains("if (!cursor && !covered && spent.value)"))
        // A Tome covered during its reveal: its arming reads the cover, and its children are handed it.
        assertTrue("the Tome's arming reads the live cover", source.contains("val tomeCovered by rememberUpdatedState(panelCovered)") && source.contains("latticeArmed() && reveal >= 1f && !tomeCovered"))
        val folderPage = source.substringAfter("private fun CompanionFolderPage(").substringBefore("internal fun ColumnScope.CompanionTomeLeaves(")
        assertTrue("the Tome page passes the cover to its leaves", folderPage.contains("covered = covered,") && folderPage.contains("CompanionTomeLeaves("))
        assertTrue("and the Home hands the page the drawer's cover", source.contains("covered = panelCovered,"))
        val leaves = source.substringAfter("internal fun ColumnScope.CompanionTomeLeaves(").substringBefore("@Preview")
        assertTrue("every child tile is handed it", leaves.contains("covered = covered,"))
    }

    @Test
    fun `the page diamonds stand on the frame's bottom line and the footer is the hint's alone`() {
        val source = java.io.File(
            "src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt",
        ).readText()
        // The diamonds are drawn by a sibling AFTER the clipping drag root (its glass backdrop is `graphicsLayer { clip = true }` and with the hint retired the frame's bottom edge is
        // its bottom edge, so a slot inside it cut half of every diamond): placed from the frame's measured bottom, centred on its line, over a quiet base scrim, at the trailing end.
        val marker = "// **The page diamonds sit ON the frame's bottom edge**"
        assertEquals("one diamonds block", 1, source.split(marker).size - 1)
        // Under the rename scrim and keyboard (they follow it), above the lattice.
        val diamonds = source.substringAfter(marker).substringBefore("        if (renamingFolder != null || renameGlassUp) {")
        val dragRoot = source.substringAfter("testTag(CompanionDragRootTag)").substringBefore(marker)
        assertFalse("the diamonds are not inside the clipping drag root", dragRoot.contains("MimirPageRunes("))
        assertTrue("the lifted tile is still the drag root's own", dragRoot.contains("overlayVisual?.let { visual ->"))
        assertTrue("they take no height from the grid: a sibling Box that fills the body and an offset in the layout phase", diamonds.contains("Box(Modifier.fillMaxSize()) {") && diamonds.contains(".offset {"))
        assertTrue("centred on the frame line, from the frame's own measured bottom", diamonds.contains("railBounds.bottom - overlayOrigin.y - 0.5.dp.toPx()") && diamonds.contains("CompanionPageRunesScrimHeightDp.dp.toPx() / 2f"))
        assertTrue("at the trailing end", diamonds.contains("Alignment.TopEnd") && diamonds.contains("-CompanionPageRunesEndInsetDp.dp.roundToPx()"))
        assertTrue("over a quiet base scrim, as the rune eyebrow sits on the header rule", diamonds.contains("palette.surfaceBase.copy(alpha = 0.72f)"))
        assertTrue("not drawn until the frame has been laid out", diamonds.contains("alpha = if (railBounds.height > 0f) 1f else 0f"))
        assertTrue("the semantics node is kept", diamonds.contains("testTag(CompanionPageRunesTag)") && diamonds.contains("companionPageRunesDescription(currentPage, pageCount)"))
        // The footer exists only while the hint does, and carries nothing else.
        val footer = source.substringAfter("The footer is the swipe hint and nothing else").substringBefore("val overlayVisual = liftedVisual")
        assertTrue("the row exists only while the hint is not retired", footer.contains("if (onWorkspaceDrag != null && showActivitySwipeHint) {"))
        assertFalse("the page runes are not in the footer row", footer.contains("MimirPageRunes"))
    }

    @Test
    fun `the instruments switch names what the press gives you, from its seat on the rail`() {
        // **The footer's copy of this switch is RETIRED** -- retired the whole
        // footer row. The switch had already moved to the rail, which is the shared mode strip and is
        // up on every surface that has one. What this pins is the LAW, which did not move: a control
        // titled by what pressing it GIVES you, never by where you are.
        val source = java.io.File(
            "src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt",
        ).readText()

        assertFalse(
            "the footer's duplicate switch must not come back; one control, one seat",
            source.contains("private fun CompanionFooterToggle"),
        )
        // A destination needs a noun and this mode is not one: CompanionSceneTarget still has exactly
        // two members, and neither of them is instrumentation.
        // Singular since the 2026-09-11 copy review (§2 #3): the Bottom lattice is "the shelf" wherever
        // else it is named, and the plural is the Main face-row noun.
        assertTrue(
            "the label swaps with the state",
            source.contains("""if (instrumentsShown) "Shelf" else "Tweaker""""),
        )
        // The rail seat is 56 dp, over the 48 dp floor, and it is the only way in on a screen no
        // controller reaches.
        //
        // **E7b made the size a TOKEN, and this pin reads the token.** It used to grep for
        // `.size(56.dp)`, which pinned the literal rather than the fact: the seventh seat E7b added to
        // this rail made the row's WIDTH a budget, and a budget cannot be computed from a value that
        // only exists as text inside a modifier chain. Checking the constant the layout actually uses
        // is strictly stronger than checking a copy of its digits — the same reason
        // a layout budget is written as its arithmetic.
        val seat = source.substringAfter("private fun QuickLaunchSlot")
        assertTrue("the seat is drawn at the shared token", seat.contains(".size(CompanionQuickSeatDp.dp)"))
        assertTrue("and the token clears the touch minimum", CompanionQuickSeatDp >= 48f)
        // A mark-only seat has no icon to borrow a name from, so it must carry one itself.
        assertTrue("and it must be announceable", seat.contains("onClickLabel = name"))
    }

    @Test
    fun `the blur is on the scene, never on the rail or the picker it would blur`() {
        val source = java.io.File(
            "src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt",
        ).readText()
        // A graphicsLayer renderEffect filters the layer's OWN content -- it is not a backdrop filter.
        // The blur sat on a Box that also enclosed the quick-launch layer, so opening the picker
        // blurred the picker's own card, header and labels. core-ui states the rule at
        // MimirLiquidGlass.kt:81. This pins the containment rather than the pixels.
        //
        // The blur-plan change altered the blur's SPELLING, not its place: the raw `BlurEffect` ramp became
        // core-ui's `mimirGlassBackdropProgress` (same 24 dp peak, same `overlayBlur` clock, no small-radius tail),
        // so the pin now names that call on the scene and refuses either spelling on the enclosing ancestor.
        //
        // R6-F-04: re-pointed after b8f603f9 renamed the composable itself --
        // `ImmediateCompanionSceneTransition` -> `CompanionSceneTransition` (now internal, reused by the
        // Activity's live-game card) -- with no change to where the blur sits. The old anchor string no
        // longer occurs anywhere in the source, so `substringBefore`/`substringAfter` silently fell back to
        // their no-match default (the WHOLE remaining string) instead of failing loudly: `enclosing` ballooned
        // from the ~80-line span between the two real anchors to everything from `quickLaunchVisible` to EOF,
        // which swallows an unrelated `BlurEffect` mention in a comment on a different composable further down
        // the file. Re-pointed to the current name; the containment this pin forbids is unchanged.
        val sceneCall = source.substringAfter("CompanionSceneTransition(\n                    targetState = requestedTarget,")
            .substringBefore(") { target ->")
        assertTrue(
            "the overlay blur must live on the scene transition itself",
            sceneCall.contains(".mimirGlassBackdropProgress({ CompanionTomeBackdropBlurDp.dp }, { overlayBlur }, snapshot = { true })"),
        )
        val enclosing = source.substringAfter("val quickLaunchVisible =").substringBefore("CompanionSceneTransition(")
        listOf("BlurEffect", "mimirGlassBackdropProgress").forEach { blur ->
            assertFalse(
                "and never on an ancestor that also encloses the rail and the picker ($blur)",
                enclosing.contains(blur),
            )
        }
    }

    @Test
    fun `a rail seat paints no disc and no rim, and the mark or the icon carries the state`() {
        val source = java.io.File(
            "src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt",
        ).readText()
        val seat = source.substringAfter("private fun QuickLaunchSlot").substringBefore("private fun QuickAppIcon")
        // The seat's OWN modifier chain: from its size to its content. The 14 dp pending dot below it is a mark, and is a circle.
        val chain = ".size(CompanionQuickSeatDp.dp)" + seat.substringAfter(".size(CompanionQuickSeatDp.dp)").substringBefore("contentAlignment = Alignment.Center")
        assertTrue("the chain was found", chain.contains(".combinedClickable("))
        // Main's 09-27 rail paints no disc, no sheen and no rim; the 08-31 struck coin is gone.
        listOf("CircleShape", "clip(", "background(", "border(", "book.", "coverTop", "hardware", "glassSheen", "leather", "sheen").forEach {
            assertFalse("the seat's own chain no longer draws `$it`", chain.contains(it))
        }
        listOf("book.coverTop", "book.hardware", "glassSheen", "companionBookTreatment", "glazeAlpha", "glazeStep").forEach {
            assertFalse("`$it` was the disc's, and is not in the seat", seat.contains(it))
        }
        // A blur here would filter an almost-uniform gradient and return it unchanged, for a layer.
        assertFalse("nothing behind the rail is worth blurring", seat.contains("BlurEffect"))
        // The seat is still 56 dp, the touch target and the rail's budget.
        assertTrue("the seat is drawn at the shared token", chain.contains(".size(CompanionQuickSeatDp.dp)"))
        // The medal's MEANING must not come with a material -- six medals in a row read as an achievement shelf.
        listOf("facet", "boss", "hardcore").forEach {
            assertFalse("$it belongs to the medal, not to a launch seat", seat.lowercase().contains(it))
        }
        // press pushes IN, with no ripple.
        assertTrue("the seat presses in, on the Bottom's one press response", seat.contains(".mimirPressResponse(interaction)"))
        assertFalse("and no longer on a literal of its own", seat.contains("0.97f"))
        assertTrue("a locked seat takes only a token dim", seat.contains("if (enabled) 1f else 0.88f"))
        assertTrue("and the ripple is refused explicitly", seat.contains("indication = null"))
        assertTrue("enablement animates rather than snapping, on the focus beat", seat.contains("tween(MimirMotion.FocusMs, easing = MimirMotion.Easing)") && seat.contains("""label = "seat-enabled""""))
        // The instruments switch says *you are here* with its mark: `accentBright`, the ink Main's rail gives its current destination.
        assertTrue("the active instruments mark is accentBright, quiet at rest", seat.contains("val instrumentsInk = if (instrumentsActive) palette.accentBright else markInk"))
        assertTrue("and the rest ink is textMuted, the + 's ink: textSecondary is 0.07 from accentBright on MANI", seat.contains("val markInk = palette.textMuted"))
        assertFalse("the console metal went with the disc it was struck into", seat.contains("markMetal") || seat.contains("platformConsoleIconBrush"))
        assertTrue("the bars wear it", seat.contains(".background(instrumentsInk)"))
        // A pending pin and an empty seat keep their muted marks.
        assertTrue("the empty seat is a + in textMuted", seat.contains("""Text("+", style = MimirTypeScale.Title, color = palette.textMuted)"""))
        // Both animated values are event-bounded; §6 forbids a perpetual producer on this panel.
        listOf("infiniteRepeatable", "rememberInfiniteTransition").forEach {
            assertFalse("$it would make a rail seat the panel's first perpetual producer", seat.contains(it))
        }
        // the rail's existing 18 dp line names the seat under your finger,
        // so a MARK-only seat can say what it is without every seat growing a caption.
        assertTrue("the seat reports its hold", seat.contains("onHeldChanged(name.takeIf { pressed })"))
        // And the two mark-only seats finally have an accessible name -- they had none, because the
        // app seats borrowed theirs from the icon's contentDescription and a mark has no icon.
        assertTrue("TalkBack gets a name too", seat.contains("onClickLabel = name"))
        // **The held name no longer reaches the line**: ambient status text was retired on 2026-08-31
        // at the foot of this surface. The seat still REPORTS its hold (asserted above) because the
        // reporting is what gives a mark-only seat a name at all; what changed is that nothing renders
        // it as a caption. The line now carries the launch message alone, which is not ambient: it
        // exists only while a launch is in flight or has failed.
        val row = source.substringAfter("private fun CompanionQuickLaunchRow").substringBefore("private fun QuickLaunchSlot")
        assertTrue("the line carries the launch message", row.contains("val railLine = launchMessage"))
        assertFalse("and nothing ambient beside it", row.contains("launchMessage ?: heldSeatName"))
        // The icon is the one app-icon shape, on the icon's own ground, and the seat's app icon takes it.
        val icon = source.substringAfter("private fun QuickAppIcon").substringBefore("/** UX01 picker")
        assertTrue("the dock's icon is Main's rounded square", icon.contains(".clip(mimirAppIconShape(CompanionQuickIconDp.dp))"))
        assertFalse("and no longer a circle crop", icon.contains("CircleShape"))
        assertTrue("its 09-01 ground arrives with the icon", icon.contains("drawRect(groundColor, alpha = alpha)"))
        assertTrue("the seat grounds it", seat.contains("QuickAppIcon(entry, ground = true)"))
        assertTrue("and the picker's well is Main's own, so the picker does not", source.contains("QuickAppIcon(entry, ground = false)"))
    }

    @Test
    fun `the drag hold still wins over a demoted job`() {
        // The drag hold is checked FIRST for a reason -- a tile in the air pins HOME regardless of
        // anything else. Demotion must not reorder that.
        assertEquals(
            CompanionSceneTarget.Payload(CompanionMode.HOME),
            companionRequestedSceneTarget(
                CompanionPrimaryRoute(),
                CompanionMode.NOW_PLAYING,
                companionDragging = true,
                jobs = listOf(job()),
            ),
        )
    }

    // ---- companionRequestedSceneTarget: the rank applied to target selection ----

    @Test
    fun `HOME and IDLE keep their own payload while a job runs`() {
        // Was: "a job running while HOME or IDLE would show takes the panel". That is precisely the
        // assertion that kept the retired panel selected and left screen 2 empty for whole scans.
        listOf(CompanionMode.HOME, CompanionMode.IDLE).forEach { mode ->
            assertEquals(
                "$mode: the shelf stays up while the job runs",
                CompanionSceneTarget.Payload(mode),
                companionRequestedSceneTarget(CompanionPrimaryRoute(), mode, jobs = listOf(job())),
            )
        }
    }

    @Test
    fun `a running job does not move the selected scene for NOW_PLAYING or RUNNING_APP`() {
        // Player truth standing over a job is the policy and is unchanged: RUNNING_APP no longer yields the scene to a
        // job either, because there is no longer a panel for the job to take. NOW_PLAYING selects itself (the selector's answer
        // only: the Bottom draws no Now playing card since slice A3). RUNNING_APP, unpromoted, renders HOME — its demotion, not
        // the job's doing: the same answer it gives with no job running at all, which is the point.
        mapOf(
            CompanionMode.NOW_PLAYING to CompanionMode.NOW_PLAYING,
            CompanionMode.RUNNING_APP to CompanionMode.HOME,
        ).forEach { (mode, shown) ->
            assertEquals(
                "$mode: player truth keeps the scene, and the job does not move it",
                CompanionSceneTarget.Payload(shown),
                companionRequestedSceneTarget(CompanionPrimaryRoute(), mode, jobs = listOf(job())),
            )
        }
    }

    @Test
    fun `no job running means the payload holds the panel -- with RUNNING_APP the one defined exception`() {
        CompanionMode.entries.forEach { mode ->
            // RUNNING_APP hands the panel to HOME until promoted . Written as
            // an expected-value branch rather than by skipping the mode, so the exception is asserted
            // instead of merely un-tested -- a skipped mode is how a rank quietly loses a member.
            val expected = if (mode == CompanionMode.RUNNING_APP) CompanionMode.HOME else mode
            assertEquals(
                "$mode with nothing running",
                CompanionSceneTarget.Payload(expected),
                companionRequestedSceneTarget(CompanionPrimaryRoute(), mode, jobs = emptyList()),
            )
        }
        // **The "promoted" half of this test is retired with the legend** : there
        // is no longer an input that hands `RUNNING_APP` its own panel, so the loop that asked for the
        // card is gone rather than rewritten. What replaces it is the stronger pin above -- `the scene
        // can never be RUNNING_APP` -- which asserts the absence across the whole input space instead
        // of for one call shape.
    }

    @Test
    fun `the default jobs argument is empty -- an untouched call site is not silently reinterpreted`() {
        assertEquals(
            CompanionSceneTarget.Payload(CompanionMode.HOME),
            companionRequestedSceneTarget(CompanionPrimaryRoute(), CompanionMode.HOME),
        )
    }

    @Test
    fun `a drag hold outranks even the job -- a tile in the air must not be yanked out from under the finger`() {
        // Checked BEFORE the job-panel rank on purpose (see companionRequestedSceneTarget's KDoc): the
        // drag hold is a UI-mechanical override, not one of §5b's four truths, and it wins regardless.
        listOf(CompanionMode.HOME, CompanionMode.IDLE, CompanionMode.NOW_PLAYING, CompanionMode.RUNNING_APP)
            .forEach { mode ->
                assertEquals(
                    "dragging during $mode + a running job must still pin HOME",
                    CompanionSceneTarget.Payload(CompanionMode.HOME),
                    companionRequestedSceneTarget(
                        CompanionPrimaryRoute(),
                        mode,
                        companionDragging = true,
                        jobs = listOf(job()),
                    ),
                )
            }
    }

    // ---- the rail's availability: the retired panel is no longer among the targets that can ask ----

    @Test
    fun `every payload target carries the rail, and so does the job panel`() {
        // **The HOME exception was removed on 2026-08-31**:
        // the rail is on every surface now. Inverted rather than deleted — "HOME is not special here"
        // is the rule now and deserves a pin as much as the exception did.
        CompanionMode.entries.forEach { mode ->
            assertTrue("$mode carries the rail", companionQuickLaunchAvailable(CompanionSceneTarget.Payload(mode)))
        }
    }

    // ---- the data contract CompanionJobPanel's "no bar on a null fraction" draw rule depends on ----

    @Test
    fun `a null or zero total carries no honest fraction -- the panel's bar-or-nothing check has no bar to skip`() {
        assertNull("no denominator yet", job(total = null).fraction)
        assertNull("a zero total is not a real denominator", job(total = 0).fraction)
        assertEquals(0.5f, job(seen = 5, total = 10).fraction)
    }
}
