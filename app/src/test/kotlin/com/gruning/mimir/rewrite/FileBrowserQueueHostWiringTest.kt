package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState

/**
 * The CALL SITES, not the policy.
 *
 * the earlier change wired the queue and delete across three lanes. Every *rule* is pinned behaviourally —
 * `fileQueueDismissable` and `fileDeleteSubtitle` here in `app`, the commands and focus landings in
 * `feature-files`. **The three lines that connect them were pinned by nothing at all**, and that was
 * verified by differential rather than assumed: deleting `onDelete` from the controller construction and
 * `onUndo`/`onDismiss` from the host left `:app:testDebugUnitTest` **BUILD SUCCESSFUL**.
 *
 * That is not a cosmetic gap. `FileBrowserController.deleteAvailable` is `mode == MANAGE && onDelete !=
 * null` and `queueAvailable` needs a live sink, so **a dropped line does not fail — it silently un-arms
 * the feature**: SELECT and START fall back to `Consumed`, the footer stops advertising them, and the
 * manager looks exactly as it did before this PR. The same shape as the `landSettingsReturnFocusFallback`
 * hole (`SettingsReturnFallbackWiringTest`), which is where this file's idiom comes from.
 *
 * A source-text pin because `app` has **no Compose or Robolectric harness**: `FileBrowserSurface` is a
 * composable and `FileBrowserStore.open` needs a `Context`, so neither call site can be driven from a JVM
 * test. **This proves the lines exist. It proves nothing about the screen** — that a card renders, that
 * Undo is reachable, or that a delete completes. Hardware certifies those.
 */
class FileBrowserQueueHostWiringTest {

    private fun appSource(fileName: String): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
    ).firstOrNull { it.isFile }?.readText()
        ?: throw AssertionError("$fileName not reachable from the test working directory")

    private val store: String by lazy { appSource("FileBrowserStore.kt") }
    private val host: String by lazy { appSource("MimirAppHost.kt") }

    // Move/Copy slice 3 reads two more coordinators
    // than slice 1 needed: the announcement Toasts/logs live at the call sites, not in the store.
    private val appBrightnessCoordinator: String by lazy { appSource("AppBrightnessCoordinator.kt") }
    private val mimirAppCoordinator: String by lazy { appSource("MimirAppCoordinator.kt") }

    @Test
    fun `the browser is constructed with a delete sink, or SELECT is silently dead`() {
        val construction = store.substringAfter("mutableController.value = FileBrowserController(")
            .substringBefore("\n        )")
        assertTrue(
            "FileBrowserStore must pass `onDelete` when it constructs FileBrowserController. Without it " +
                "`deleteAvailable` is false, SELECT falls through to Consumed, and the footer declines to " +
                "advertise DELETE -- the manager looks untouched and nothing fails. Construction was:\n" +
                construction,
            construction.contains("onDelete = "),
        )
        assertTrue(
            "...and it must route to deleteInManager, which is the only entry point that stages the " +
                "delete rather than unlinking it -- staging is what makes Undo survive a battery pull",
            construction.contains("deleteInManager(listOf(path))"),
        )
    }

    @Test
    fun `the host collects the queue and wires all four card actions`() {
        val call = host.substringAfter("FileBrowserSurface(").substringBefore("\n        )")
        assertTrue(
            "sanity: the host must collect FileBrowserStore.queue, or the surface renders an empty " +
                "queue forever no matter what the store holds",
            host.contains("FileBrowserStore.queue.collectAsState()"),
        )
        assertTrue("the collected queue must reach the surface. Call was:\n$call", call.contains("queue = queue"))

        // Four, not three: KEEP_DELETED routes to releaseUndo, and a missing arm there ships a button
        // that draws, focuses and does nothing. This spec originally listed three and was corrected.
        listOf(
            "onUndo" to "FileBrowserStore::undo",
            "onReleaseUndo" to "FileBrowserStore::releaseUndo",
            "onDismiss" to "FileBrowserStore::dismiss",
            "onResolveConflict" to "FileBrowserStore::resolveConflict",
        ).forEach { (parameter, sink) ->
            assertTrue(
                "$parameter must be wired to $sink. A queue card's buttons are derived from its state, " +
                    "so an unwired sink does not hide the button -- it draws one that cannot act. " +
                    "Call was:\n$call",
                call.contains("$parameter = $sink"),
            )
        }
    }

    /**
     * F-06's two sinks — **and this pin exists because the differential proved they had none.**
     *
     * `feature-files`' own `FileBrowserExtractWiringTest` cannot see `app/`, so with both lines deleted
     * `:app:testDebugUnitTest` was `BUILD SUCCESSFUL`. That is the fifth unpinned call site in four PRs and
     * the failure mode is always identical: `extractChoiceAvailable` is
     * `resolveExtractOptions != null && onExtractTo != null`, so a dropped line does not fail — SECONDARY
     * silently reverts to extract-here and the chooser the request specified for simply never appears.
     */
    @Test
    fun `the browser is constructed with both extract sinks, or the chooser never appears`() {
        val construction = store.substringAfter("mutableController.value = FileBrowserController(")
            .substringBefore("\n        )")
        assertTrue(
            "FileBrowserStore must pass `resolveExtractOptions` -- without it extractChoiceAvailable is " +
                "false and X falls back to extract-here, which is the pre-F-06 behaviour with no failure " +
                "anywhere. Construction was:\n$construction",
            construction.contains("resolveExtractOptions = "),
        )
        assertTrue(
            "...and `onExtractTo`, which is the half that actually writes. BOTH OR NEITHER: a resolver " +
                "with no sink draws two rows, takes focus, advertises a verb and does nothing on A.",
            construction.contains("onExtractTo = "),
        )
        assertTrue(
            "the destination sink must route to extractTo, the only entry point that takes an explicit " +
                "destination -- routing it back through the extract-here path would silently ignore the " +
                "folder the player chose",
            construction.contains("extractTo(archivePath, destination)"),
        )
    }

    /**
     * Move/Copy slice 1 (QA gap G2) — **and this pin exists because the pattern already burned five call
     * sites before this one.** `moveInManager`/`copyInManager` shipped tested and callerless; without this
     * pin, deleting both `onMoveTo`/`onCopyTo` lines from the controller construction would leave
     * `:app:testDebugUnitTest` `BUILD SUCCESSFUL`, exactly like the extract sinks and the delete sink
     * before them.
     *
     * `transferChoiceAvailable` is `onMoveTo != null && onCopyTo != null` -- BOTH OR NEITHER, the same
     * shape as `extractChoiceAvailable` above. A sink with no partner draws a row, takes focus, advertises
     * a verb and does nothing.
     *
     * PICK's incapability is NOT re-checked here on purpose: [moveInManager]/[copyInManager] already open
     * with `operations ?: return`, and `FileBrowserStorePickSafetyTest.everyDestructiveEntryPointRefusesWhenNoManageSessionIsOpen`
     * already lists both by name. Wiring a second, mode-keyed guard at this call site would just be a
     * second place for a future edit to get wrong.
     */
    @Test
    fun `the browser is constructed with both transfer sinks, or Move and Copy stay unreachable`() {
        val construction = store.substringAfter("mutableController.value = FileBrowserController(")
            .substringBefore("\n        )")
        assertTrue(
            "FileBrowserStore must pass `onMoveTo` -- without it transferChoiceAvailable is false and " +
                "Move never appears, the same silent-un-arming shape as the extract sinks and the delete " +
                "sink before it. Construction was:\n$construction",
            construction.contains("onMoveTo = "),
        )
        assertTrue(
            "...and `onCopyTo`. BOTH OR NEITHER: a sink with no partner draws a row, takes focus, " +
                "advertises a verb and does nothing. Construction was:\n$construction",
            construction.contains("onCopyTo = "),
        )
        assertTrue(
            "the move sink must route to moveInManager(listOf(path), destination) -- the only entry " +
                "point that stages the transfer through the existing Keep both / Replace / Skip conflict " +
                "machinery. Construction was:\n$construction",
            construction.contains("moveInManager(listOf(path), destination)"),
        )
        assertTrue(
            "the copy sink must route to copyInManager(listOf(path), destination), same discipline as " +
                "the move sink. Construction was:\n$construction",
            construction.contains("copyInManager(listOf(path), destination)"),
        )
    }

    // ---- Move/Copy completion announcement ----
    //
    // Slice 1 pinned that onMoveTo/onCopyTo reach the controller (the test above). That makes a transfer
    // RUNNABLE. It says nothing about whether a completed one is ever reported -- a successful Copy previously had no visible confirmation. The five
    // pins below are that other half: the sink reaches Session, Session's field is actually read from
    // transfer()'s success arm, the subtitle actually consults the origin, and each of the two
    // coordinators that can reach MANAGE actually surfaces it (a Toast in production, a log line in the
    // QA probe). MimirSettingsCoordinator.kt is deliberately
    // NOT pinned here -- that call site never sets `mode`, so it always opens
    // PICK, where transfer() cannot fire either sink at all (`operations ?: return`). It already omits
    // `onDeleted` for the identical reason, silently; wiring a dead Toast there would be new dead code,
    // not a fix for a gap.

    @Test
    fun `open() actually threads onMoved and onCopied into the Session it constructs`() {
        val fields = store.substringAfter("private class Session(").substringBefore("\n    )")
        assertTrue(
            "sanity: the bound must reach the Session class's real end, or the checks below may be " +
                "checking less than they think. Fields were:\n$fields",
            fields.contains("val classifyPlatform:"),
        )
        assertTrue(
            "Session must declare an onMoved field, or a coordinator has nowhere to hand its callback " +
                "to. Fields were:\n$fields",
            fields.contains("val onMoved:"),
        )
        assertTrue(
            "...and onCopied, same discipline. Fields were:\n$fields",
            fields.contains("val onCopied:"),
        )

        val construction = store.substringAfter("val opened = Session(").substringBefore("\n        )")
        assertTrue(
            "sanity: the bound must reach the construction's real end. Construction was:\n$construction",
            construction.contains("classifyPlatform = classifyPlatform,"),
        )
        assertTrue(
            "open() must thread its OWN onMoved parameter into the Session it builds -- `onMoved = " +
                "onMoved`, not a hardcoded stub -- or a coordinator's callback is accepted and silently " +
                "dropped on the floor before transfer() could ever reach it. Construction was:\n" +
                construction,
            construction.contains("onMoved = onMoved,"),
        )
        assertTrue(
            "...and onCopied, same discipline. Construction was:\n$construction",
            construction.contains("onCopied = onCopied,"),
        )
    }

    /**
     * **§6.2 — Move and Copy announce themselves, on extract's own model.** Before this slice a
     * completed Move left the player exactly where they were with no acknowledgement, and a completed
     * Copy was "indistinguishable from a press that did nothing" (spec §6.1). `onDeleted`'s own pin
     * above is the direct precedent for the firing point: announce when the effect is REAL (the SUCCESS
     * arm), never at the press and never from the FAILURE arm.
     *
     * Bounded on the next declaration (`commonParentFolderName`), not on `"\n    }"` -- `transfer`
     * nests several block closes at deeper indentation before its own, and the G1 sweep test above
     * records the differential that caught exactly this hazard on an expression-bodied neighbour.
     */
    @Test
    fun `a move or copy announces itself, at the moment the transfer actually succeeds`() {
        val body = store.substringAfter("private fun transfer(")
            .substringBefore("internal fun commonParentFolderName(")
        assertTrue(
            "sanity: the bound must land inside transfer() itself and stop before its neighbour, or " +
                "the assertions below may be silently checking the wrong function's body",
            !body.contains("private fun enqueue("),
        )
        assertTrue(
            "the move announcement must fire from transfer(), or a completed Move stays exactly as " +
                "silent as it was before slice 3. Body was:\n$body",
            body.contains("session?.onMoved?.invoke("),
        )
        assertTrue(
            "...and the copy announcement, same discipline -- a completed Copy is otherwise " +
                "indistinguishable from a press that did nothing (spec §6.1's own description of the " +
                "pre-slice-3 defect). Body was:\n$body",
            body.contains("session?.onCopied?.invoke("),
        )

        val successIndex = body.indexOf("onSuccess = { count ->")
        val movedIndex = body.indexOf("session?.onMoved?.invoke(")
        val copiedIndex = body.indexOf("session?.onCopied?.invoke(")
        val failureIndex = body.indexOf("onFailure = { error ->")
        assertTrue(
            "sanity: all four anchors must actually be found, or the ordering check below is vacuous",
            successIndex >= 0 && movedIndex >= 0 && copiedIndex >= 0 && failureIndex >= 0,
        )
        assertTrue(
            "both announcements must fire strictly between the SUCCESS arm's opening and the FAILURE " +
                "arm's opening -- onDeleted's own precedent: firing before the effect is real would " +
                "promise something not yet true, and firing from onFailure would announce an operation " +
                "that never actually completed.",
            successIndex < movedIndex && movedIndex < failureIndex &&
                successIndex < copiedIndex && copiedIndex < failureIndex,
        )
    }

    /**
     * The queue card must name the origin and destination. This source pin checks that the
     * commonParentFolderName result reaches the subtitle; value-level behavior belongs in
     * FileBrowserStoreQueueHonestyTest.kt. This file does not test those helper values.
     */
    @Test
    fun `the transfer card's subtitle actually consults the origin, or it silently reverts to destination-only`() {
        val body = store.substringAfter("private fun transfer(")
            .substringBefore("internal fun commonParentFolderName(")
        assertTrue(
            "sanity: the bound must land inside transfer() itself and stop before its neighbour, or " +
                "the assertions below may be silently checking the wrong function's body",
            !body.contains("private fun enqueue("),
        )
        assertTrue(
            "§6.4: the subtitle must be built from commonParentFolderName's result, or the queue card " +
                "silently reverts to naming only the destination. Body was:\n$body",
            body.contains("val originFolder = commonParentFolderName(picked)"),
        )
        assertTrue(
            "the subtitle must actually branch on originFolder, not merely compute it and discard the " +
                "result. Body was:\n$body",
            body.contains("if (originFolder != null)"),
        )
    }

    @Test
    fun `the MANAGE files entry point wires both transfer announcements to real Toasts`() {
        val call = appBrightnessCoordinator.substringAfter("FileBrowserStore.open(")
            .substringBefore("\n        )")
        assertTrue(
            "sanity: the bound must reach the call's real end, or the assertions below may be " +
                "checking less of the call than they think. Call was:\n$call",
            call.contains("classifyPlatform = FileBrowserPlatformClassifier.forBrowser"),
        )
        assertTrue(
            "AppBrightnessCoordinator's Utility \"Files\" entry point must wire onMoved -- this is the " +
                "ONLY production call site where `mode` is unconditionally MANAGE, so if Move stays " +
                "silent anywhere, it is here. Call was:\n$call",
            call.contains("onMoved = "),
        )
        assertTrue(
            "...and onCopied, same discipline. Call was:\n$call",
            call.contains("onCopied = "),
        )
        assertTrue(
            "both Toasts must route through transferAnnouncement -- ONE template per verb, so a change " +
                "of wording is a one-line edit there, not a hunt through two drifting string literals. " +
                "Call was:\n$call",
            call.contains("transferAnnouncement(\"Moved\", label, destination)") &&
                call.contains("transferAnnouncement(\"Copied\", label, destination)"),
        )
    }

    @Test
    fun `a fully skipped transfer must not announce itself, or the Toast names a move that never happened`() {
        val body = store.substringAfter("private fun transfer(")
            .substringBefore("internal fun commonParentFolderName(")
        assertTrue(
            "sanity: the bound must land inside transfer() and stop before its neighbour, or this " +
                "assertion is checking the wrong function",
            !body.contains("private fun enqueue("),
        )
        // F-6(a) replaced the guard: it used to infer "nothing transferred" from the player's conflict
        // CHOICE (`resolution != ConflictResolution.SKIP`), which only happened to be correct because
        // production transfers exactly one path. `transferredCount` is the real count, recovered from
        // the engine's new `onSkipped` callback (`count - skippedCount`) -- ManagedFileOperations' own
        // KDoc for `copy`/`move` -- so the guard now reads the actual outcome rather than inferring it,
        // and stays correct for a future multi-path list where some sources transfer and others skip in
        // the same dispatch.
        val transferredCountIndex = body.indexOf("val transferredCount = count - skippedCount")
        val guardIndex = body.indexOf("if (transferredCount > 0)")
        val movedIndex = body.indexOf("session?.onMoved?.invoke(")
        assertTrue(
            "sanity: all three anchors must be found, or the ordering assertion below is vacuous. " +
                "Body was:\n$body",
            transferredCountIndex >= 0 && guardIndex >= 0 && movedIndex >= 0,
        )
        assertTrue(
            "the split must be computed before it is used to guard anything",
            transferredCountIndex < guardIndex,
        )
        assertTrue(
            "the announcement must sit INSIDE the transferredCount guard, or a Skip that moved nothing " +
                "still says \"Moved Foo.iso to PS2\" about a file still sitting where it was. Body was:\n$body",
            guardIndex < movedIndex,
        )
    }

    @Test
    fun `a completed Move kicks the targeted rescan, and a Copy deliberately does not`() {
        val call = appBrightnessCoordinator.substringAfter("FileBrowserStore.open(")
            .substringBefore("\n        )")
        assertTrue(
            "sanity: the bound must reach the call's real end. Call was:\n$call",
            call.contains("classifyPlatform = FileBrowserPlatformClassifier.forBrowser"),
        )
        val movedArm = call.substringAfter("onMoved = ").substringBefore("onCopied = ")
        val copiedArm = call.substringAfter("onCopied = ").substringBefore("onDeleted = ")
        assertTrue(
            "sanity: both arms must be non-empty and distinct, or the two assertions below are vacuous",
            movedArm.isNotBlank() && copiedArm.isNotBlank() && movedArm != copiedArm,
        )
        assertTrue(
            "onMoved must rescan only the folders touched. " +
                "Without it a moved ROM's stale row survives, launchLocatorFrom reads its dead " +
                "managedPath with no filesystem check, and pressing Play records a SUCCESSFUL play that " +
                "bumps playCount and lifts the ghost tile up recently-played. Arm was:\n$movedArm",
            movedArm.contains("kickTransferRescan(sources, destination)"),
        )
        assertFalse(
            "onCopied must NOT kick it. The refresh contract covers MOVING a ROM into its " +
                "platform folder; Copy removes nothing and so can never strand a tile pointing at a " +
                "file that is gone. Extending the rescan to Copy remains a separate design decision, and this " +
                "assertion exists so that widening is a deliberate edit rather than a drift. " +
                "Arm was:\n$copiedArm",
            copiedArm.contains("kickTransferRescan("),
        )
    }

    @Test
    fun `the rescan resolves roots through LibraryRootMatcher, or it silently matches nothing`() {
        val body = appBrightnessCoordinator.substringAfter("private fun kickTransferRescan(")
            .substringBefore("private fun openFileBrowser(")
        assertTrue(
            "sanity: kickTransferRescan must exist and the bound must stop before its neighbour",
            body.isNotBlank() && !body.contains("FileBrowserStore.open("),
        )
        assertTrue(
            "the roots must come from the repository's own registry, not a guess. Body was:\n$body",
            body.contains("observeSources()"),
        )
        assertTrue(
            "and must be matched with LibraryRootMatcher. Library sources are registered as SAF tree " +
                "URIs (content://.../tree/primary%3ARoms) while the file manager deals in " +
                "/storage/emulated/0/... -- a plain startsWith matches NOTHING, so this whole function " +
                "would pass every gate while doing nothing at all. Body was:\n$body",
            body.contains("LibraryRootMatcher.rootsContaining("),
        )
        assertTrue(
            "and must actually call rescan on what it resolved. Body was:\n$body",
            body.contains("library.rescan("),
        )
        assertTrue(
            "the burst must be debounced -- rescan is serializedScan, so twenty moves would otherwise " +
                "queue twenty scans each waiting on the last. Body was:\n$body",
            body.contains("transferRescanJob?.cancel()") && body.contains("delay(TransferRescanDebounceMs)"),
        )
        assertTrue(
            "...and the scan itself must be NonCancellable, which is correctness and not caution. The " +
                "job takes its batch and CLEARS the pending set before scanning, and rescan() is a " +
                "long suspend -- so a second move arriving mid-scan would cancel a job whose paths are " +
                "already gone from `pending`, and that origin would never be rescanned and never " +
                "re-queued. The ghost tile comes back, triggered by the very burst the debounce exists " +
                "to serve. Body was:\n$body",
            body.contains("withContext(NonCancellable)"),
        )
    }

    @Test
    fun `the QA files probe logs both transfer announcements, never a Toast`() {
        // Anchored on the hook's own dispatch arm first: as of 2026-09-11 the coordinator holds a SECOND
        // FileBrowserStore.open call (startImageImport's PICK_IMAGE browser for the Upload action),
        // declared earlier in the file, so a bare substringAfter would slice the wrong call.
        val call = mimirAppCoordinator.substringAfter("is DebugNavigationRequest.OpenFiles ->")
            .substringAfter("FileBrowserStore.open(")
            .substringBefore("\n                )")
        assertTrue(
            "sanity: the bound must reach the call's real end, or the assertions below may be " +
                "checking less of the call than they think. Call was:\n$call",
            call.contains("classifyPlatform stays defaulted"),
        )
        assertTrue(
            "the QA diagnostic hook must wire onMoved -- `mimir.openFiles manage` is the documented way " +
                "to drive a MANAGE session for the design spec's own M2/M3 device measurements, and a QA " +
                "lane reading logcat needs the same reachability proof extract/checksum already get. " +
                "Call was:\n$call",
            call.contains("onMoved = "),
        )
        assertTrue(
            "...and onCopied, same discipline. Call was:\n$call",
            call.contains("onCopied = "),
        )
        assertFalse(
            "POSTURE: this hook must never pop UI -- it is a headless QA probe, by its own comment on " +
                "onExtracted/onChecksum a few lines above. A Toast here is exactly what that comment " +
                "says this hook has \"no business showing\". Call was:\n$call",
            call.contains("Toast.makeText"),
        )
    }

    /**
     * **G1 — the sweep that makes Undo survive a crash must actually be called.**
     *
     * `ManagedFileOperations.recoverInterrupted` calls itself "the session-start sweep" and had **zero
     * callers anywhere** — found by the QA lane, which ranked it above F-09 and was right to. the earlier change
     * removed the delete confirm dialog and justified it on the engine: *"Survives a battery pull
     * (rollback journal, not memory)"*. The data did survive. Nothing reconciled it, so a crash left the
     * player's deleted files neither restored nor gone, under `.mimir-staging`, unmentioned by any
     * surface.
     *
     * This pin exists because the defect WAS a missing call site, and this tree has now shipped five of
     * those. Deleting the call is silent by construction — `operations` is null in PICK and the sweep
     * returns 0 when there is nothing to recover, so nothing fails and nothing looks wrong until a player
     * loses files to a crash.
     */
    @Test
    fun `the browser sweeps interrupted operations, or Undo does not survive a crash`() {
        assertTrue(
            "FileBrowserStore must call ManagedFileOperations.recoverInterrupted. Without it the delete " +
                "confirm dialog was removed on a guarantee whose entry point is dead code -- a crash " +
                "leaves staged deletions orphaned under .mimir-staging forever.",
            store.contains("recoverInterrupted("),
        )
        val sweep = store.substringAfter("private fun sweepInterruptedOperations(")
            .substringBefore("\n    }")
        assertTrue(
            "the sweep must be MANAGE-only: `operations` is null in PICK, and the picker must be as " +
                "incapable of reconciling a staging journal as it is of creating one. Body was:\n$sweep",
            sweep.contains("operations ?: return"),
        )
        assertTrue(
            "a recovery that lands after the listing is drawn must refresh it, or the restored files " +
                "stay invisible until the player leaves and comes back",
            sweep.contains("refresh()"),
        )

        // It has to run where a journal can actually BE. recoverOrphaned does not walk -- it sweeps one
        // directory -- and MANAGE opens at the drive chooser, where no journal can exist. A sweep wired
        // only to open() would therefore be reachable and still useless.
        //
        // NOTE ON THIS SLICE, because the first version of it was VACUOUS and the differential caught it:
        // `listDirectory` is EXPRESSION-BODIED, so it has no `\n    }` terminator. Slicing to one ran
        // straight past the function and swallowed `sweepInterruptedOperations`' own definition, so the
        // assertion matched itself and stayed green with the call deleted. Bounded by the next
        // declaration instead, and re-differentialled.
        val listing = store.substringAfter("private fun listDirectory(")
            .substringBefore("private fun sweepInterruptedOperations(")
        assertTrue(
            "the sweep must be driven per-directory from listDirectory, not once at session start. " +
                "listDirectory was:\n$listing",
            listing.contains("sweepInterruptedOperations("),
        )
    }

    /**
     * **Delete has a voice, and it names where Undo lives** — policy 2026-08-14.
     *
     * Extract has always announced itself (`"Extracted 3 items to Roms"`). Delete — the only verb in this
     * manager that can lose anything — said nothing at all, which left Undo undiscoverable on a screen the action never opened.
     *
     * **The firing point is the substance of the ruling, not a detail.** `onDeleted` fires when the state
     * flips to `UndoAvailable`, i.e. the instant the staged payload exists — never at the press. A message
     * at the press would promise a recovery that is still being written, and there is a real window where
     * a fast B-at-root closes the browser before staging completes.
     */
    @Test
    fun `a delete announces itself, at the moment Undo actually exists`() {
        assertTrue(
            "FileBrowserStore must expose an onDeleted sink, or delete stays silent while extract speaks",
            store.contains("onDeleted"),
        )
        val deleteBody = store.substringAfter("fun deleteInManager(").substringBefore("onFailure =")
        assertTrue(
            "the announcement must fire from the SUCCESS branch, beside the UndoAvailable flip -- that is " +
                "the instant the staged payload exists and Undo is genuinely available. Body was:\n" +
                deleteBody,
            deleteBody.contains("session?.onDeleted?.invoke("),
        )
        val undoFlip = deleteBody.indexOf("FileQueueState.UndoAvailable(deletion)")
        val announce = deleteBody.indexOf("session?.onDeleted?.invoke(")
        assertTrue(
            "it must come AFTER the UndoAvailable flip, not before: announcing a recovery route while the " +
                "state still says Running would name a queue entry the player cannot yet act on",
            undoFlip >= 0 && announce > undoFlip,
        )
        assertFalse(
            "SAFETY: the empty-deletion branch must stay silent. `Nothing to delete` is a FAILURE, and " +
                "telling the player their files are recoverable when nothing was staged is the " +
                "fabrication class this tree has shipped twice.",
            deleteBody.substringBefore("} else {").contains("onDeleted"),
        )
    }

    @Test
    fun `the delete sink stages rather than deleting outright`() {
        val construction = store.substringAfter("mutableController.value = FileBrowserController(")
            .substringBefore("\n        )")
        assertTrue(
            "SAFETY: the controller's delete sink must never reach finalizeDelete. There is no confirm " +
                "dialog because the delete is staged and reversible; " +
                "a sink that finalised would make SELECT an irreversible one-press destroy.",
            !construction.contains("finalizeDelete"),
        )
    }

    // ---- D-1, extract's conflict card ----
    //
    // `ArchiveHelper.extract` (`core-files`, landed separately) now refuses a collision instead of
    // silently backing up and overwriting: ASK is the default and it throws FileOperationConflictException
    // with nothing written. This lane's job was the other half -- give the queue a card to park that on,
    // exactly like move/copy already have. The four pins below are the call sites: nothing here re-checks
    // ArchiveHelper's own refusal (that is core-files' own test suite's job).

    @Test
    fun `extract and extractTo both delegate to dispatchExtraction, or one of them reinvents the conflict card`() {
        assertTrue("sanity: dispatchExtraction must exist", store.contains("private fun dispatchExtraction("))

        val implicitDestinationBody = store
            .substringAfter("private fun extract(archivePath: String, session: Session) {")
            .substringBefore("internal suspend fun runExtraction(")
        assertTrue(
            "SECONDARY's extract-here path (implicit sibling-directory destination) must route through " +
                "dispatchExtraction -- a bespoke scope.launch here would silently skip D-1's conflict " +
                "card the next time this file is touched. Body was:\n$implicitDestinationBody",
            implicitDestinationBody.contains("dispatchExtraction(archivePath, destination, session)"),
        )

        val explicitDestinationBody = store
            .substringAfter("fun extractTo(archivePath: String, destination: String) {")
            .substringBefore("private fun dispatchExtraction(")
        assertTrue(
            "F-06's \"Extract in...\" path (explicit chosen destination) must route through the SAME " +
                "dispatchExtraction as the implicit path, not a second copy of the enqueue/park logic. " +
                "Body was:\n$explicitDestinationBody",
            explicitDestinationBody.contains("dispatchExtraction(archivePath, destination, current)"),
        )
    }

    @Test
    fun `dispatchExtraction only enqueues a card when a MANAGE engine is present, or PICK gets a stuck invisible card`() {
        val body = store.substringAfter("private fun dispatchExtraction(")
            .substringBefore("// ---- F-08: search ----")

        assertTrue(
            "SAFETY: the queue id must be minted ONLY when `operations` is non-null (MANAGE) -- the same " +
                "MANAGE/PICK signal every destructive entry point in this file already reads. Without this " +
                "gate a PICK-mode collision enqueues a card that fileQueueDismissable refuses to dismiss, " +
                "resolveConflict refuses to answer (operations == null), and the Queue screen cannot even " +
                "open to show it (fileBrowserQueueAvailable is MANAGE-only) -- a permanently stuck, " +
                "invisible entry. Body was:\n$body",
            body.contains("val id = if (operations != null) nextQueueId++ else null"),
        )
        val gateIndex = body.indexOf("if (id != null) {")
        val enqueueIndex = body.indexOf("enqueue(")
        assertTrue(
            "the enqueue call must sit textually INSIDE that gate, not merely follow it in the function",
            gateIndex >= 0 && enqueueIndex > gateIndex,
        )
        assertFalse(
            "SAFETY: the queue entry must never be tagged FileOperation.EXTRACT. The engine lane's own " +
                "warning was that a live use forces exhaustive-when edits in feature-files/app while other " +
                "lanes are active there -- FileOperation.COPY is the tag actually used, and this pin is " +
                "what stops a future edit from reaching for the 'more correct'-looking enum member instead.",
            body.contains("operation = FileOperation.EXTRACT"),
        )
    }

    @Test
    fun `resolveConflict re-dispatches a parked extraction with the player's real answer, never a fixed one`() {
        val body = store.substringAfter("fun resolveConflict(entryId: Long, choice: FileConflictChoice) {")
            .substringBefore("private class PendingTransfer(")

        assertTrue(
            "sanity: resolveConflict must look up a parked extraction, or the whole retry path is dead. " +
                "Body was:\n$body",
            body.contains("pendingExtractions.remove(entryId)"),
        )
        assertTrue(
            "the retry must carry the PLAYER'S OWN choice through -- Keep both / Replace / Skip -- never a " +
                "resolution this function decided on its own. Body was:\n$body",
            body.contains("resolution = choice.resolution"),
        )
        assertFalse(
            "E2 O4: Keep both names the arriving file `name (copy).ext`, so there is no hash suffix to " +
                "compute and the retry hands the engine nothing but the player's own choice. Body was:\n$body",
            body.contains("ConflictNamePolicy.suffix(") || body.contains("conflictSuffix"),
        )
        assertFalse(
            "SAFETY: no branch here may hardcode a resolution. A hardcoded OVERWRITE/RENAME/SKIP is " +
                "exactly the silent-overwrite bug D-1 exists to close, just relocated one level up from " +
                "the engine. Body was:\n$body",
            body.contains("ConflictResolution.OVERWRITE") ||
                body.contains("ConflictResolution.RENAME") ||
                body.contains("ConflictResolution.SKIP"),
        )
    }

    @Test
    fun `the conflict card's sentence is written identically for a transfer and an extraction`() {
        // Deliberately NOT refactored into a shared helper -- transfer()'s
        // existing park block was left untouched to keep this change's blast radius to the extraction
        // path alone. Byte-identical text is what "one language" has to mean without that refactor.
        val sentence = "A file named \${error.conflicts.first().name} is already there."
        val occurrences = Regex(Regex.escape(sentence)).findAll(store).count()
        assertEquals(
            "HONESTY: transfer's park and dispatchExtraction's park must show the player the exact same " +
                "sentence for the exact same situation -- a name-taken collision. A count other than 2 " +
                "means either a wording drifted apart, or one of the two conflict paths lost its subtitle " +
                "entirely.",
            2,
            occurrences,
        )
    }
}
