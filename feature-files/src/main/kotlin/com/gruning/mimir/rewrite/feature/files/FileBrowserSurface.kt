package com.gruning.mimir.rewrite.feature.files

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.files.FileConflictChoice
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueEntry
import com.gruning.mimir.rewrite.core.files.FileSearchFilter
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.FocusState
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.ui.LocalMimirControllerHintLabels
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirFocusTraversal
import com.gruning.mimir.rewrite.core.ui.MimirFonts
import com.gruning.mimir.rewrite.core.ui.MimirHintLineSeparator
import com.gruning.mimir.rewrite.core.ui.MimirSegmentedHintSwap
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirNorseBookCover
import com.gruning.mimir.rewrite.core.ui.MimirFooterKeycap
import com.gruning.mimir.rewrite.core.ui.MimirPageHandoff
import com.gruning.mimir.rewrite.core.ui.MimirPageMotion
import com.gruning.mimir.rewrite.core.ui.MimirPlatformShelfmark
import com.gruning.mimir.rewrite.core.ui.MimirSheetHost
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.MimirTypeStep
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipLabelStyle
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.mimirEtchedChipSurface
import com.gruning.mimir.rewrite.core.ui.mimirNorseBookCoverCorner
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import kotlinx.coroutines.flow.Flow
import java.io.File
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState

/**
 * The browse folio + standing inspector -- the other half of this module's public surface (see
 * [FileBrowserController]). The rebuild uses
 * folders as face-forward tomes, flatter file tiles, a 270dp standing inspector on the right, sized for
 * the design's stated 833x468dp / 369dpi Odin viewport. Presentation and Compose lifecycle only -- it
 * never decides what an input does; that is entirely [FileBrowserController.dispatch] /
 * [FileBrowserController.handleTap].
 *
 * **This slice's screen is Browse only.** Search / Select / More (adoption spec §6's other screens) are
 * out of scope, and as of the 2026-08-14 grounding pass their header icons are **not drawn at all**. The
 * previous pass drew them as static, non-focusable chrome and reasoned that a focusable button doing
 * nothing would teach a dead control -- correct as far as it went, but it stopped one step short: the
 * design's own interaction model is *"Everything reachable by D-pad or touch"* (spec §5), so on a touch
 * surface a magnifier drawn in the header IS an affordance whether or not it takes focus. Not taking
 * focus makes it *less* discoverable to a controller, not more honest. Three in-tree precedents say draw
 * nothing instead -- [com.gruning.mimir.rewrite.core.ui.MimirPlatformShelfmark] ("a blank or '?'
 * shelfmark would be worse than the absence it is standing in for"),
 * [com.gruning.mimir.rewrite.core.ui.MediaArtFrame], and [fileBrowserFooterHints]'s own "a fixed legend
 * would lie". They come back the moment their screens exist. **This is a visible change from the header
 * shown on build `d0b2c550` and is offered as a reversible, precedent-cited decision, not a
 * settled one.**
 *
 * The header's brand mark is absent for a different reason and is a genuine OWED, not a decision. The real mark is
 * [com.gruning.mimir.rewrite.core.ui.MimirMarkBadge], which now lives in `core-ui` (it was in `feature-library`, a module this
 * one deliberately does not depend on, see `build.gradle.kts`, until the Thor Bottom revamp's V2 hoisted it), so the
 * dependency that kept it out is gone and only the wiring is owed. It was previously stubbed as an empty bordered box -- the
 * plate without the ink, exactly what the shelfmark precedent above forbids -- and redrawing the Mannaz locally would make
 * another copy of a mark that already exists once; this codebase has already been burned by three drifting copies
 * of one helper, documented in [com.gruning.mimir.rewrite.core.ui.mimirPlatformShelfmarkCode]'s KDoc.
 * The badge goes in when the header is next opened for this.
 *
 * @param crumbLabels optional friendly labels for volume crumbs, keyed by absolute path (e.g.
 *   `"/storage/1A2B-3C4D" to "SD Card"`), threaded straight through to [fileBrowserBreadcrumbs] -- see
 *   that function for the exact contract, including the two raw-crumb cases `core-files`'
 *   `StorageVolumeListing` produces today and the one expression in `app/` that fills this. Empty (the
 *   default) reproduces the pure-basename trail, which is what every caller gets until `app/` supplies
 *   real volume names.
 * @param checksum the inspector's Checksum row, driven entirely from outside — **F-05**. Defaults to
 *   [FileBrowserChecksumState.NotChecked], which renders exactly what the row rendered before this
 *   parameter existed, so an un-updated caller is unchanged rather than lying. `app/`'s `FileBrowserStore`
 *   is the only thing that can fill it: it owns the coroutine, the repository and `CHECKSUM_ALGORITHM`.
 *   **UNWIRED as of this pass**; the store must publish the checksum state to this parameter. Until it lands, Verify still reports through `onVerifyChecksum` to whatever `app/` does
 *   with it, and this row stays idle.
 * @param queue the store's live operation queue — **F-01**. Collected by `app/` from
 *   `FileBrowserStore.queue` and handed down; this surface publishes it into
 *   [FileBrowserController.publishQueue] on every change, open screen or not, because the browse footer
 *   draws the live count either way (C-1).
 * @param onUndo [FileBrowserQueueAction.UNDO] → `FileBrowserStore.undo`. Puts the staged files back.
 * @param onReleaseUndo [FileBrowserQueueAction.KEEP_DELETED] → `FileBrowserStore.releaseUndo`. Makes the
 *   delete permanent. **A four-callback contract would ship a dead "Keep deleted" button**, which is
 *   what makes this one a separate parameter rather than a second use of [onDismiss].
 * @param onDismiss [FileBrowserQueueAction.DISMISS] → `FileBrowserStore.dismiss`. Drops a terminal card
 *   and frees nothing. Not the same verb as the controller's own `onDismiss` (which closes the browser).
 * @param onResolveConflict the three [com.gruning.mimir.rewrite.core.files.FileConflictChoice] answers →
 *   `FileBrowserStore.resolveConflict`.
 *
 * **There is deliberately no `onDelete` parameter here.** The delete sink is as a [FileBrowserController] **constructor** parameter beside `onExtract` —
 * `onDelete: ((String) -> Unit)?`, with `deleteAvailable` deriving from its non-null-ness — because its
 * whole signature is public types and it needed no help crossing the module seam. There is no setter,
 * so a surface parameter could not reach it: it would be an advertised callback that nothing invokes.
 * The four above exist for the opposite reason — [FileBrowserQueueTarget] is `internal`, so the queue
 * sink physically cannot be a constructor parameter and has to cross here.
 */
@Composable
fun FileBrowserSurface(
    controller: FileBrowserController,
    authority: FocusAuthority,
    modifier: Modifier = Modifier,
    crumbLabels: Map<String, String> = emptyMap(),
    checksum: FileBrowserChecksumState = FileBrowserChecksumState.NotChecked,
    search: ((String, FileSearchFilter) -> Flow<FileItem>)? = null,
    queue: List<FileQueueEntry> = emptyList(),
    onUndo: (Long) -> Unit = {},
    onReleaseUndo: (Long) -> Unit = {},
    onDismiss: (Long) -> Unit = {},
    onResolveConflict: (Long, FileConflictChoice) -> Unit = { _, _ -> },
) {
    DisposableEffect(controller) {
        controller.open()
        onDispose { controller.close() }
    }
    // F-08. Null means "this caller wired no engine", and then nothing about search is offered at all --
    // no header icon, no Y hint, no screen. A search surface with no provider would answer every query
    // with "Nothing here by that name" over a card full of games, which is worse than its absence; see
    // FileBrowserController.searchAvailable. `emptyFlow()` as a default was rejected for exactly that.
    FileBrowserSearchAvailability(controller, available = search != null)
    // F-01. Binds the four store entry points a queue button can reach, and publishes the store's queue
    // into the controller. Non-null IS "the queue is wired" (FileBrowserController.setQueueAction), so
    // this is also what makes START open the screen and what puts the count on the footer.
    FileBrowserQueueSink(
        controller = controller,
        queue = queue,
        onUndo = onUndo,
        onReleaseUndo = onReleaseUndo,
        onDismiss = onDismiss,
        onResolveConflict = onResolveConflict,
    )

    val focusState by authority.state.collectAsState()
    val focusedKey = focusState.takeIf { it.scope == controller.scope }?.focused
    val searchFocusedKey = focusState.takeIf { it.scope == controller.searchScope }?.focused
    val queueFocusedKey = focusState.takeIf { it.scope == controller.queueScope }?.focused
    // F-06. `controller.extractScope`, never `FileBrowserExtractFocus.Scope`: the tracker registers
    // against the CONTROLLER's derived scope (`"${scope.value}/extract"`), and the two are only equal
    // for a browser built on the default scope. Same read the queue screen makes of `queueScope`.
    val extractFocusedKey = focusState.takeIf { it.scope == controller.extractScope }?.focused
    // G2 (Move/Copy slice 1). `controller.transferScope`, on extractFocusedKey's own reasoning one line
    // up -- registration happens against the CONTROLLER's derived scope, not the bare
    // FileBrowserTransferFocus.Scope constant.
    val transferFocusedKey = focusState.takeIf { it.scope == controller.transferScope }?.focused
    // F-1. `controller.actionOverlayScope`, on extractFocusedKey's own reasoning -- registration happens
    // against the CONTROLLER's derived scope, not a bare Focus-object constant.
    val actionOverlayFocusedKey = focusState.takeIf { it.scope == controller.actionOverlayScope }?.focused
    val partition = controller.partition
    val focusedTile = focusedTileAt(partition, focusedKey)
    val palette = LocalMimirPalette.current
    // UI-05: gated on the CONTROLLER's answer, not on the mode alone. `commitAvailable` already
    // contains the mode check; restating it here is what let the drive chooser highlight a row and
    // print "A · CHOOSE" for a commit key the controller had refused to build.
    val commitFocused = controller.commitAvailable &&
        focusedKey == FileBrowserFocus.commitKey(controller.currentPath)
    // F-06 / D-3. **Read ONCE, here, and handed to every consumer below**, because these two are what the
    // legend, the commit row's own words and the inspector's Extract button all have to agree about. Three
    // separate reads of `controller.destinationPickPhase()` would be three chances for the drawn control,
    // the printed verb and the live binding to describe different states of the same press.
    val destinationPick = controller.destinationPickPhase()
    val commitPurpose = controller.commitPurpose
    // F-1. Read ONCE here, on destinationPick's own discipline one line up: fileBrowserActionsAvailable is
    // what the footer legend's SELECT/X entries and the inspector's re-pointed buttons must all agree
    // about, and this is the SURFACE's collected-snapshot side of that predicate --
    // FileBrowserController.handleInspectorSecondaryTap computes its own LIVE copy at press time rather
    // than reading this val; see fileBrowserActionsAvailable's own KDoc for why the two must not share one
    // cached answer.
    val actionsAvailable = fileBrowserActionsAvailable(
        focused = focusedTile.actionableTile(),
        manageMode = controller.mode == FileBrowserMode.MANAGE,
        deleteAvailable = controller.deleteAvailable,
        transferChoiceAvailable = controller.transferChoiceAvailable,
        renameAvailable = controller.renameAvailable,
        destinationPick = destinationPick,
    )
    // F-2. commitFocused's own discipline: read once, thread the BOOLEAN to every consumer that must
    // agree about it (FileBrowserContent's ring gating and the footer's hint both need to know whether
    // this row holds focus, on the same "no drift between the drawn control and the legend" argument
    // commitFocused/commitPurpose already make). The row's own LABEL TEXT has no such hazard -- nothing
    // else in this screen displays it (the footer draws a fixed generic verb, never the folder name), so
    // it is derived locally inside FileBrowserContent instead, on commitKey's own re-derive-from-
    // controller precedent a few lines down.
    val backToOriginKey = controller.pickOriginPath
        ?.takeIf { controller.commitAvailable }
        ?.let(FileBrowserFocus::backToOriginKey)
    val backToOriginFocused = backToOriginKey != null && focusedKey == backToOriginKey
    // The open chooser as ONE snapshot: the branch that decides to render it and the rows it renders are
    // then the same value, never two reads of a `mutableStateOf` that a recomposition could split.
    val extractChooser = controller.extractChooser
    // G2 (Move/Copy slice 1). Same snapshot discipline as extractChooser above -- the branch that decides
    // to render the transfer chooser and the rows it renders must be the same value, never two reads of
    // a `mutableStateOf` a recomposition could split.
    val transferChooser = controller.transferChooser
    // F-1. Same snapshot discipline as the two choosers above, and the same reason: the branch that
    // decides to render the overlay and the rows it renders must be one value.
    val actionOverlay = controller.actionOverlay
    // Policy 2026-08-16. Same snapshot discipline as the two choosers above, and the same reason:
    // the branch that decides to render this screen and the rows it renders must be one value.
    val destinationList = controller.destinationList
    val destinationFocusedKey = focusState.takeIf { it.scope == controller.destinationScope }?.focused
    // The multi-disc warning uses the same snapshot discipline as every screen above.
    val discSetPrompt = controller.discSetPrompt
    val discSetFocusedKey = focusState.takeIf { it.scope == controller.discSetScope }?.focused
    // F-09/C-0. The path a Verify press was REFUSED for, or null. Held here rather than in the
    // controller because nothing about it is a decision this module's input layer makes -- it is one
    // surface's record that the press it drew a button for could not be honoured, and it is keyed by
    // path so moving focus clears it for free (the same trick fileBrowserChecksumRow's own path guard
    // uses, and for the same reason: no caller can forget a clearing call that does not exist).
    var verifyRefusedFor by remember(controller) { mutableStateOf<String?>(null) }
    // V-2. The digest this surface last put on the clipboard, or null. Held here for exactly the reasons
    // `verifyRefusedFor` above is: it is one surface's record of something it did, not a decision the
    // input layer makes -- and keying it by the DIGEST rather than by a path or a clock means it clears
    // itself for free. Move the ring and `row.digest` becomes another file's (or blank); re-verify and a
    // different digest lands; either way the acknowledgement stops matching and the button goes back to
    // offering the copy. A copy of the SAME digest still reads "Copied", which is true.
    var copiedDigest by remember(controller) { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    // R6-F-05 (spec §5). The five screens above, as ONE flag: reusing the same five snapshot reads
    // rather than re-fetching from the controller here, on `extractChooser`'s own established discipline
    // -- the branch that decides to render one of them and the value that says "the slot changed" must
    // be the same read, never two that a recomposition could split.
    val overlayOpen = discSetPrompt != null || destinationList != null || extractChooser != null ||
        transferChooser != null || actionOverlay != null

    // V-1. What just arrived, decided in one place for the whole surface -- see FileBrowserSurfaceMotion.kt.
    // Remembered on the CONTROLLER, not inside the content column: that column is unmounted whenever
    // search, the queue or one of the five screens above takes the slot, and a history that died with it
    // would forget which motions it had already played.
    val screen = fileBrowserScreenOf(
        queueOpen = controller.queueOpen,
        searchOpen = controller.searchOpen,
        overlayOpen = overlayOpen,
    )
    val arrivals = remember(controller) { FileBrowserArrivalHistory() }
    val arrival = remember(screen, controller.currentPath, controller.navigationMotion) {
        arrivals.prepare(screen, controller.currentPath, controller.navigationMotion)
    }
    // Prepare in `remember`, commit in `SideEffect` -- MimirTransitionEpochLedger's own split, and its
    // KDoc gives the reason: a prepared frame may be abandoned, and an arrival recorded inside a
    // discarded `remember` block would be marked played for a frame that never reached the screen.
    SideEffect { arrivals.commit(arrival, screen, controller.navigationMotion) }
    // The description panel beside the listing plays the listing's directory arrival (below).
    val panelArrival = arrival.takeIf { it.kind == FileBrowserArrivalKind.DIRECTORY }
    // Batch F: the ascend's departing child (below) needs its travel in pixels and the reduced-motion decision.
    val reducedMotion = mimirReducedMotion()
    val ascendTravelPx = with(LocalDensity.current) { FileBrowserChildClearTravelDp.dp.toPx() }
    val motionProbe = LocalFileBrowserMotionProbe.current

    // R6-P2-02, coordinator review 2026-09-15 (finding 1). Held HERE, one level above the if/else
    // chain -- same reasoning as `arrivals` above, not a duplicate of it: at the time, a chooser or the
    // action overlay took FileBrowserContent's own slot and gave it back without ever producing a new
    // arrival (`fileBrowserScreenOf` could not see those five screens), so a flag scoped to
    // FileBrowserContent's own `remember` would have restarted -- a SECOND full-page travel, not merely
    // a replayed glide -- every time one of them closed. Keyed on `arrival` alone, exactly as `arrival`
    // itself is: a genuinely NEW arrival still gets a fresh flag; the SAME arrival surviving a
    // chooser/overlay round trip reuses the one that already landed. Passed into FileBrowserContent as
    // a parameter rather than recomputed there. R6-F-05 has since taught `fileBrowserScreenOf` those
    // five screens, exactly as predicted here, unaffected: the arrivals it now produces for them are
    // `SCREEN`, never `DIRECTORY`, so `fileBrowserArrivalTravels` still answers false for every one --
    // this flag does not change what counts as an arrival, only where its own travel-consumed bit is
    // allowed to survive.
    val travelPending = remember(arrival) { mutableStateOf(fileBrowserArrivalTravels(arrival)) }

    // RC-12: this folio's own measured box is where the column/cell/pitch/viewport grid is solved --
    // every subtracted term in [fileBrowserSolveGrid] is THIS surface's own chrome (its header, its
    // content Row's padding, the standing inspector beside it), so measuring anything else would make
    // the formula self-inconsistent even before a host inset enters the picture.
    //
    // `remember(maxWidth, maxHeight)`: recomputes only when the measured viewport actually changes (a
    // rotation, a multi-window resize) -- not on every unrelated recomposition -- which is half of "the
    // same viewport always yields the same solve, and a no-op recomposition re-registers nothing"; the
    // other half is `FileBrowserGrid`'s own data-class equality inside `FileBrowserController.reprojectGrid`.
    BoxWithConstraints {
      val tileHeightDp = rememberFileBrowserFileTileHeightDp()
      val grid = remember(maxWidth, maxHeight, tileHeightDp) { fileBrowserSolveGrid(maxWidth.value, maxHeight.value, fileTileHeightDp = tileHeightDp) }
      // Runs from a composition effect, deliberately -- unlike FileBrowserController.open()'s initial
      // `load()` (which registers the pre-measurement FileBrowserGrid.Authored default before this
      // surface has measured anything), a tile may already hold focus by the time this fires. See
      // FileBrowserController.reprojectGrid's own KDoc for why that is safe: it never retires, so no
      // focused-key fallback can run.
      LaunchedEffect(controller, grid) { controller.reprojectGrid(grid) }
      Box {
      Column(modifier = modifier.fillMaxSize().fileBrowserModalTouchGuard().background(palette.surfaceBase)) {
        FileBrowserHeader(
            title = controller.destinationPick?.archivePath
                ?.let { fileBrowserDestinationContext(filesResources(), it) }
                ?: fileBrowserHeaderTitle(filesResources(), controller.mode),
            rootPath = controller.rootPath,
            currentPath = controller.currentPath,
            crumbLabels = crumbLabels,
            rowCount = partition.totalCount,
            // F-06 item 5 — **the crumb trail goes inert while a question is open**; see
            // [fileBrowserCrumbTapsSuppressed] for both cases and for why the trail keeps being DRAWN.
            onCrumb = { path ->
                if (!fileBrowserCrumbTapsSuppressed(destinationPick, controller.extractChooserOpen) &&
                    // G2. A separate conjunct rather than a third parameter on
                    // fileBrowserCrumbTapsSuppressed: FileBrowserExtractSurfaceTest.kt (outside this
                    // lane's writes) pins that function's 2-arg call site as exact source text, and this
                    // keeps that substring intact. Same reasoning as the extractChooserOpen case one line
                    // up -- the header stays composed above the transfer chooser too, so an unsuppressed
                    // crumb tap would unwind it from underneath while a pick is not even open yet.
                    !controller.transferChooserOpen &&
                    path != controller.currentPath
                ) {
                    navigateToCrumb(controller, path)
                }
            },
            searchAvailable = controller.searchAvailable,
            searchActive = controller.searchOpen,
            onSearch = { if (controller.searchOpen) controller.closeSearch() else controller.openSearch() },
            // The queue entry point stays visible whenever the queue exists, keeping Undo reachable.
            queueAvailable = controller.queueAvailable,
            queueCount = controller.queueEntries.size,
            queueActive = controller.queueOpen,
            // `closeSearch()` FIRST -- **belt AND braces as of the earlier change, and this comment used to claim
            // otherwise.** It said `FileBrowserController.openQueue` "has no search guard because
            // through DISPATCH it does not need one", and that `openSearch` "already closes the queue on
            // its own side". The first half was true and is now obsolete; the second half was simply
            // FALSE, and Lane D found it as a live defect rather than a hypothetical: the header stays
            // composed ABOVE the queue screen, so tapping the magnifier from the queue pushed
            // `searchScope` on top of `queueScope` and left two screens' graphs alive at once -- the
            // exact arrangement the queue-replaces-browse contract exists to prevent.
            //
            // Both entry points now hold the invariant inside the controller (`openQueue` closes search
            // and the chooser; `openSearch` closes the queue and the chooser), so all three screens are
            // mutually exclusive by construction rather than by one call site remembering. This line
            // stays anyway, which `openQueue`'s own KDoc explicitly allows for ("idempotent, so the
            // surface's line stays harmless"): it is the reading order at the call site that makes the
            // tap's effect legible, and deleting it would leave this file silently depending on a
            // guard in a class it cannot see.
            onQueue = {
                if (controller.queueOpen) {
                    controller.closeQueue()
                } else {
                    controller.closeSearch()
                    controller.openQueue()
                }
            },
            palette = palette,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, palette.outline, Color.Transparent),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // V-1: the SCREEN half of the arrival, played on the whole page -- the folio and its
                // standing inspector together, or whichever screen has replaced them. The header above
                // and the footer legend below are the shell and stay put, which is
                // `GamelistDestinationPageTransition`'s own division ("the outer book shell and Hero veil
                // stay mounted"). Handed null on a directory arrival, which makes this layer completely
                // idle while the listing inside it travels.
                //
                // R6-F-05, review 2026-09-15 (finding 1). ALSO null while `screen == OVERLAY`: each of
                // the five screens plays its OWN mount-owned SCREEN arrival on open (the constants at
                // each branch below, e.g. `FileBrowserExtractArrival`) -- ring-safety, `SCREEN`-kind,
                // dipping from the same `FileBrowserGlideStartAlpha`. Without this, BROWSE→OVERLAY
                // stacked this layer's dip on top of that one, two `graphicsLayer` alphas over the same
                // pixels (0.88 × 0.88 = 0.77 at the start) -- "two animations describing one event",
                // the defect class `FileBrowserArrivalHistory.prepare`'s own KDoc names. The RETURN
                // (OVERLAY→BROWSE/QUEUE/SEARCH) is untouched by this filter: `screen` at that frame is
                // whatever was returned TO, never `OVERLAY`, so the page layer keeps that half -- it is
                // the only arrival the folio's seat gate needs. Do not delete the five constants: an
                // OVERLAY→OVERLAY hop (one chooser handing off to another) would then animate nothing.
                .fileBrowserArrivalGlide(
                    arrival.takeIf { it.kind == FileBrowserArrivalKind.SCREEN && screen != FileBrowserScreen.OVERLAY },
                )
                // F-18 (audit 2026-10-14): no top padding on the row itself. The folio's listing has its own ring room inside its bounds (MimirFocusTileRingReach, 14 dp,
                // since 2026-09-28), and this row's 11 dp stacked on it left 25 dp of dead ground above the first heading and pushed the second row of tiles under the
                // footer. Every other screen in this slot takes the gap itself ([fileBrowserScreenTopGap]), so none of them moves, and the inspector takes the listing's ring room ([fileBrowserInspectorTopGap]) so its title and the first heading start together.
                .padding(start = 18.dp, end = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // F-08: search REPLACES the browse content, exactly as the design models it -- one `screen`
            // field, `isBrowse` / `isSearch` mutually exclusive (`Mimir Files.dc.html:744-745`), the same
            // header and footer above and below. Not an overlay: an overlay would keep the folio's own
            // scroll effect and its whole focus graph composed underneath a screen that has its own,
            // which is two scroll owners and two live graphs for one visible list.
            //
            // F-01: the QUEUE takes the same slot on the same reasoning, and is tested FIRST. The two can
            // never both be open (`openSearch` closes the queue; `OpenQueue` is only reachable from the
            // BROWSE dispatch table), so the order is not a tie-break -- it is stated so that if that
            // invariant is ever broken upstream, this surface fails toward the screen that owns the
            // ACTIVE focus scope rather than composing a search graph the D-pad cannot reach.
            //
            // F-06: the extract chooser takes the SAME slot on the same reasoning, and is tested FIRST.
            // The argument for replacing rather than overlaying is made in full at
            // [FileBrowserExtractScreen] rather than inherited from the two above, because a chooser is a
            // short list about ONE archive and keeping that archive visible would have been worth an
            // exception if an overlay delivered it. It cannot: every ring and the whole inspector on this
            // screen are derived from a scope-filtered key that goes null the moment
            // `pushScope(extractScope)` runs, so an overlay would leave a ringless folio and an empty
            // inspector behind the question.
            //
            // G2 (Move/Copy slice 1): the transfer chooser takes the SAME slot for the SAME reason,
            // tested SECOND -- immediately after extract, before queue/search. A plain FILE and an
            // ARCHIVE are disjoint tile kinds (FileBrowserTileKind has exactly three arms, and
            // `transferChoiceAvailable`'s SECONDARY arm is gated on `kind == FILE` where the extract arm
            // is gated on `extractable`, itself archive-only), so `extractChooser` and `transferChooser`
            // are never both non-null by construction -- `FileBrowserController.open`/`close`/
            // `openSearch`/`openQueue` each call `closeTransferChooser()` beside `closeExtractChooser()`,
            // and the transfer dispatch table is tested ahead of the browse one exactly where the extract
            // one is (`authority.state.value.scope == transferScope`, checked immediately after the
            // extractScope check in `FileBrowserController.dispatch`).
            //
            // FIRST is documentation rather than mechanism -- the controller makes all four mutually
            // exclusive (`openSearch`/`openQueue` both call `closeExtractChooser`; the chooser's own
            // dispatch table swallows everything that could open either), pinned by
            // `FileBrowserExtractWiringTest` §8. It is stated so that if that invariant is ever broken
            // upstream this surface fails toward the screen owning the INNERMOST pushed scope, which is
            // the one the D-pad is actually talking to.
            // Policy 2026-08-16. FIRST in the chain, ahead of both choosers, because it is the
            // INNERMOST pushed scope: it opens on top of a destination pick, which the extract chooser
            // has already closed itself to start. The chain's own rule -- "fail toward the screen owning
            // the innermost pushed scope, which is the one the D-pad is actually talking to" -- puts it
            // here rather than at the end.
            // The multi-disc warning is ahead of the destination list, by the chain's
            // own rule: this screen opens on the press that COMMITS a destination pick, so by the time it
            // is up the list is already closed and its scope popped -- but the ordering is stated rather
            // than relied upon, so that if that invariant is ever broken upstream the surface still fails
            // toward the innermost pushed scope, which is the one the D-pad is talking to.
            if (discSetPrompt != null) {
                FileBrowserDiscSetScreen(
                    state = discSetPrompt,
                    controller = controller,
                    authority = authority,
                    focusedKey = discSetFocusedKey,
                    palette = palette,
                    modifier = Modifier
                        .fileBrowserArrivalGlide(FileBrowserDiscSetArrival)
                        .weight(1f)
                        .fillMaxHeight()
                        .fileBrowserScreenTopGap(),
                )
            } else if (destinationList != null) {
                FileBrowserDestinationScreen(
                    state = destinationList,
                    controller = controller,
                    authority = authority,
                    focusedKey = destinationFocusedKey,
                    palette = palette,
                    modifier = Modifier
                        // V-1, on FileBrowserExtractScreen's arrival precedent: mounted-owned and
                        // constant -- unrelated to and unaffected by the page-level glide below.
                        // R6-F-05: `fileBrowserScreenOf` now sees this screen (`overlayOpen`), so
                        // opening and closing it also plays that page-level dip now, same as search or
                        // the queue already do; disclosed, not left over from before.
                        .fileBrowserArrivalGlide(FileBrowserExtractArrival)
                        .weight(1f)
                        .fillMaxHeight()
                        .fileBrowserScreenTopGap(),
                )
            } else if (extractChooser != null) {
                FileBrowserExtractScreen(
                    state = extractChooser,
                    controller = controller,
                    authority = authority,
                    focusedKey = extractFocusedKey,
                    palette = palette,
                    modifier = Modifier
                        // V-1: its own arrival, mounted-owned and constant -- the keyboard's precedent (until F4
                        // made the keyboard a sheet), and the two OWED arrivals this cannot give it are named in
                        // FileBrowserExtractScreen's KDoc. R6-F-05: the page-level glide above is NOT
                        // idle any more while this one plays -- `fileBrowserScreenOf` now sees the
                        // chooser (`overlayOpen`), so opening and closing it plays the page dip too,
                        // same as search or the queue; disclosed, not left over from before.
                        .fileBrowserArrivalGlide(FileBrowserExtractArrival)
                        .weight(1f)
                        .fillMaxHeight()
                        .fileBrowserScreenTopGap(),
                )
            } else if (transferChooser != null) {
                FileBrowserTransferScreen(
                    state = transferChooser,
                    controller = controller,
                    authority = authority,
                    focusedKey = transferFocusedKey,
                    palette = palette,
                    modifier = Modifier
                        // V-1, on FileBrowserExtractScreen's own arrival precedent above: mounted-owned
                        // and constant. R6-F-05: `fileBrowserScreenOf` now sees this chooser too
                        // (`overlayOpen`), on the identical reasoning as the extract chooser above.
                        .fileBrowserArrivalGlide(FileBrowserTransferArrival)
                        .weight(1f)
                        .fillMaxHeight()
                        .fileBrowserScreenTopGap(),
                )
            } else if (actionOverlay != null) {
                // F-1. Same slot, same reasoning as the two choosers above, tested THIRD -- immediately
                // after extract and transfer, before queue/search. Never simultaneously non-null with
                // either chooser (or with destinationList/discSetPrompt): activateFocusedActionOverlayOption
                // closes this scope BEFORE opening any of them, sequentially rather than by nesting one
                // scope under another -- see that function's own KDoc. FIRST-ness among these five is
                // documentation of that invariant, not the mechanism that enforces it, exactly as the
                // extract/transfer comment above states for itself.
                FileBrowserActionOverlayScreen(
                    state = actionOverlay,
                    controller = controller,
                    authority = authority,
                    focusedKey = actionOverlayFocusedKey,
                    palette = palette,
                    modifier = Modifier
                        .fileBrowserArrivalGlide(FileBrowserActionOverlayArrival)
                        .weight(1f)
                        .fillMaxHeight()
                        .fileBrowserScreenTopGap(),
                )
            } else if (controller.queueOpen) {
                FileBrowserQueueScreen(
                    controller = controller,
                    authority = authority,
                    focusedKey = queueFocusedKey,
                    palette = palette,
                    modifier = Modifier.weight(1f).fillMaxHeight().fileBrowserScreenTopGap(),
                )
            } else if (controller.searchOpen && search != null) {
                FileBrowserSearchScreen(
                    controller = controller,
                    authority = authority,
                    focusedKey = searchFocusedKey,
                    search = search,
                    volumeLabel = fileBrowserSearchVolumeLabel(controller.currentPath, crumbLabels),
                    crumbLabels = crumbLabels,
                    palette = palette,
                    // F-S1/F-S2: the SAME solved grid the folio one branch down is handed, and the same
                    // one the tracker is reprojected onto -- so the search screen's scope-card seat, its
                    // page stride and the folio's columns all trace to one measurement of one box.
                    grid = grid,
                    modifier = Modifier.weight(1f).fillMaxHeight().fileBrowserScreenTopGap(),
                )
            } else {
                // Motion audit batch F (2026-09-28): going UP is a page return. The child's last frame stays as recorded
                // pixels (no tree, no input, no focus) and glides back the way it came as it clears, on
                // FileBrowserChildClearMs. The parent stands at rest, opaque, UNDER it from the first frame (folder-up,
                // 2026-10-15: waiting hidden for the clear and then dipping in measured 513 ms with an empty frame on the
                // Thor): the pane is a*child + (1-a)*parent, never less than a whole page. A descent keeps the
                // destination-owned glide inside FileBrowserContent; the ring and seat gates keep their clocks.
                MimirPageHandoff(
                    epoch = arrival.epoch,
                    departs = fileBrowserArrivalDeparts(arrival),
                    exitTravelPx = MimirPageMotion.returnTravelPx(FileBrowserGlideEnter, ascendTravelPx),
                    incomingAlpha = { 1f },
                    incomingTravelPx = { 0f },
                    reducedMotion = reducedMotion,
                    exitMs = FileBrowserChildClearMs,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    probe = motionProbe?.listing,
                ) {
                // A Nova regression (2026-09-29) showed blurred content when returning to a parent folder.
                // The departing page references, not copies, a layered child, and FileBrowserContent's root is always
                // layered (the arrival glide, the seat gate). Unkeyed it survived the flip and re-recorded as the
                // PARENT, so the "departing child" was the parent sliding 22 dp over itself. Keyed on the handoff's
                // own epoch, as MimirPageHandoff's KDoc requires, the real child departs.
                //
                // Keyed on the FOLDER, not the arrival. A folder loading from disk drops the arrival
                // to "none" until it lands, and the arrival key remounted the old listing at its top for a frame; the
                // folder changes exactly when the new listing lands, the flip the departing page needs. And the page
                // carries its own ground, drawn inline so the recording keeps it: the browser's background is painted
                // outside the recording, so the departing child was see-through, and the parent's grid and the
                // child's ring showed through it.
                Box(Modifier.fillMaxSize().background(palette.surfaceBase)) {
                key(controller.currentPath) {
                FileBrowserContent(
                    partition = partition,
                    // RC-12: the SAME solved grid the tracker was (or is about to be) reprojected onto --
                    // see the BoxWithConstraints block above. Threading it here rather than re-solving is
                    // what keeps paint and lattice from ever disagreeing about column count.
                    grid = grid,
                    focusedKey = focusedKey,
                    commitFocused = commitFocused,
                    // F-06 item 3. Threaded from the one read above rather than re-fetched down there:
                    // the row's WORDS and the footer's verb for the same press then trace to a single
                    // value, which is the whole reason FileBrowserCommitPurpose is an enum on the
                    // controller and not a string.
                    commitPurpose = commitPurpose,
                    // F-2. Threaded from the one gated read above, same discipline as commitPurpose one
                    // line up -- FileBrowserContent's own ring gating and the footer's hint must agree
                    // about whether this row holds focus.
                    backToOriginFocused = backToOriginFocused,
                    authority = authority,
                    scope = controller.scope,
                    controller = controller,
                    palette = palette,
                    arrival = arrival,
                    travelPending = travelPending,
                    modifier = Modifier.fillMaxSize(),
                )
                }
                }
                }
                // The description panel used to cut to the new
                // folder's text while the listing was still hidden or gliding, and go blank on the commit row. It now
                // rides the listing: the same handoff going up (the child's panel clears off the top, never
                // travelling, of the parent's, which stands at rest under it), the same arrival dip coming down, keyed on the
                // folder like the listing and with its own ground so the departing copy is opaque. Focus moving
                // inside a folder stays instant: the epoch does not change.
                MimirPageHandoff(
                    epoch = arrival.epoch,
                    departs = fileBrowserArrivalDeparts(arrival),
                    exitTravelPx = 0f,
                    incomingAlpha = { 1f },
                    incomingTravelPx = { 0f },
                    reducedMotion = reducedMotion,
                    exitMs = FileBrowserChildClearMs,
                    modifier = Modifier.width(FileBrowserInspectorWidthDp.dp).fillMaxHeight().fileBrowserInspectorTopGap(),
                    probe = motionProbe?.panel,
                ) {
                Box(Modifier.fillMaxSize().background(palette.surfaceBase)) {
                key(controller.currentPath) {
                Box(Modifier.fillMaxSize().fileBrowserArrivalGlide(panelArrival, travels = false, alphaProbe = motionProbe?.let { probe -> { probe.panelGlideAlpha = it } })) {
                FileBrowserInspectorPanel(
                    // No tile focused (the commit or back-to-origin row, an empty folder): the open folder itself,
                    // from its parent's listing, described but never actionable.
                    tile = focusedTile ?: controller.currentDirectoryTile,
                    describesFolder = focusedTile == null,
                    uploadNote = fileBrowserImagePickNote(filesResources(), controller.mode, focusedTile),
                    currentPath = controller.currentPath,
                    rootPath = controller.rootPath,
                    crumbLabels = crumbLabels,
                    onOpen = { controller.dispatch(ControllerAction.CONFIRM) },
                    // F-1/R-13, 2026-08-18. **Re-pointed from `controller.dispatch(ControllerAction.
                    // SECONDARY)`.** X itself goes quiet once the action overlay covers the same tile
                    // (`fileBrowserCommand`'s SECONDARY arm), and these two buttons routed through the
                    // identical signal -- unbinding X without re-pointing them would have left both drawn,
                    // pressable and silently dead. `handleInspectorSecondaryTap` is the one function both
                    // now share: SYSTEM_SELECT (opens the overlay) when the overlay has a row for this
                    // tile, the SAME `dispatch(SECONDARY)` these buttons always called otherwise -- which
                    // is what keeps PICK's direct Extract path (the overlay never opens there) exactly as
                    // live as it was before this ticket. Same button SLOT, same tile-kind-decides-which-
                    // chooser reasoning as before; only the door changed. Placed before onExtract so this
                    // line sits outside the region FileBrowserVerifyHonestyTest.kt slices from the
                    // onExtract anchor.
                    onPlace = { controller.handleInspectorSecondaryTap() },
                    onExtract = { controller.handleInspectorExtractTap() },
                    // F-06 item 6. This button routes through the same signal the controller now CONSUMES for the
                    // whole duration of a destination pick (both directly via SECONDARY in PICK, and via
                    // actionsAvailable's own destinationPick guard in MANAGE) -- correct on input, and it
                    // left a drawn control over a dead binding, which is the same lie as a hint over one.
                    // Gated on the identical value the legend reads to drop its `X EXTRACT` entry, from the
                    // one read above, so the printed hint and the pressable button cannot come apart.
                    extractLive = fileBrowserSecondaryBindingLive(destinationPick),
                    // G2. The inspector's OWN "Place…" button visibility gate -- unrelated to (and not
                    // deleted alongside) fileBrowserFooterHints' now-removed `transferAvailable` parameter;
                    // this one is FileBrowserInspectorPanel's, still read at its `tile.kind == FILE &&
                    // transferAvailable && extractLive` guard a few lines down in that composable.
                    // ...and not on a row held for a pending delete, which every verb refuses (see
                    // `fileBrowserInspectorFields`' note on `openable`).
                    transferAvailable = controller.transferChoiceAvailable && focusedTile?.deleting != true,
                    // F-09/C-0: routed so a press that cannot name a file SAYS SO instead of vanishing.
                    onVerify = {
                        if (fileBrowserVerifyFires(
                                drawnPath = focusedTile?.path,
                                livePath = fileBrowserLiveFocusedPath(
                                    live = authority.state.value,
                                    browseScope = controller.scope,
                                    partition = controller.partition,
                                ),
                            )
                        ) {
                            verifyRefusedFor = null
                            controller.verifyFocusedChecksum()
                        } else {
                            verifyRefusedFor = focusedTile?.path
                        }
                    },
                    // V-2. The clipboard write and the acknowledgement, in one place. Local by
                    // construction: `LocalClipboardManager` is Compose's wrapper over Android's own
                    // ClipboardManager, and nothing about a digest leaves the device -- this module has
                    // no network dependency at all. The payload is the bare digest, never "SHA-1 <hash>";
                    // see fileBrowserChecksumCopyPayload for why a prefix would break the one use a hash
                    // has. Recorded so the button can answer, because a silent copy is indistinguishable
                    // from a dead one.
                    onCopy = { digest ->
                        if (digest.isNotBlank()) {
                            clipboard.setText(AnnotatedString(digest))
                            copiedDigest = digest
                        }
                    },
                    copiedDigest = copiedDigest,
                    // Projected against the FOCUSED tile's own path, so a digest can never be drawn under
                    // a different file's name -- see fileBrowserChecksumRow's path guard.
                    checksum = fileBrowserVerifyRefusalRow(
                        filesResources(),
                        row = fileBrowserChecksumRow(filesResources(), checksum, focusedTile?.path),
                        refusedPath = verifyRefusedFor,
                        focusedPath = focusedTile?.path,
                    ),
                    palette = palette,
                    modifier = Modifier.width(FileBrowserInspectorWidthDp.dp).fillMaxHeight(),
                )
                }
                }
                }
                }
            }
        }
        FileBrowserFooterLegend(
            // Policy 2026-08-16. FIRST, matching the content-slot chain above exactly -- a mismatch
            // between the two orderings is the one way the screen shown and the legend drawn could
            // disagree if the controller's mutual-exclusion invariant were ever broken upstream.
            // Rename (2026-10-08) is a keyboard modal over the listing: the keyboard draws its own legend, so
            // the browse hints underneath (A opens, B backs out of a folder) would describe presses it absorbs.
            if (controller.renameOpen) {
                emptyList()
            } else if (destinationList != null) {
                fileBrowserDestinationFooterHints(filesResources(),
                    focusedKind = FileBrowserDestinationFocus.indexOf(destinationFocusedKey)
                        ?.let { destinationList.rows.getOrNull(it)?.kind },
                )
            } else if (extractChooser != null) {
                // F-06. A's word is the FOCUSED ROW's own verb, never a fixed one: HERE and
                // PLATFORM_FOLDER extract on the spot, CHOOSE_FOLDER writes nothing at all and hands the
                // browser back to answer "where?". One `EXTRACT` for all three would promise a write that
                // does not happen on the one row where being wrong leaves the player waiting for files.
                fileBrowserExtractFooterHints(filesResources(),
                    focusedKind = FileBrowserExtractFocus.optionOf(extractFocusedKey),
                )
            } else if (transferChooser != null) {
                // G2, same order as the content-slot chain above (extract, then transfer, then
                // queue/search) -- a mismatch between the two chains' orderings would be the one way the
                // screen shown and the legend drawn could disagree if the controller's mutual-exclusion
                // invariant were ever broken upstream.
                fileBrowserTransferFooterHints(filesResources(),
                    focusedKind = FileBrowserTransferFocus.optionOf(transferFocusedKey),
                )
            } else if (actionOverlay != null) {
                // F-1, same order as the content-slot chain above (extract, transfer, action overlay,
                // then queue/search) -- a mismatch between the two chains' orderings would be the one way
                // the screen shown and the legend drawn could disagree if the controller's mutual-exclusion
                // invariant were ever broken upstream.
                fileBrowserActionOverlayFooterHints(filesResources(),
                    focusedKind = FileBrowserActionOverlayFocus.optionOf(actionOverlayFocusedKey),
                )
            } else if (controller.queueOpen) {
                // A's word is the FOCUSED button's own label, never a fixed one -- the same press means
                // "put my files back" or "destroy them for good" depending only on which button holds the
                // ring, and one word for both would be wrong in the direction that loses data. Null is
                // legitimate: a queue of nothing but running transfers registers no buttons at all.
                // W-1. B's word is not fixed either: on a screen the player was REDIRECTED to on his way
                // out, B closes the browser and finalises everything held, and `BACK` over that press
                // would be the same lie W-1 found on the browse legend one press earlier.
                fileBrowserQueueFooterHints(filesResources(),
                    focusedAction = FileBrowserQueueFocus.targetOf(queueFocusedKey)?.action,
                    exitReview = controller.queuePurpose == FileBrowserQueuePurpose.EXIT_REVIEW,
                    heldDeleteCount = controller.heldDeleteCount,
                )
            } else if (controller.searchOpen) {
                fileBrowserSearchFooterHints(filesResources(),
                    onQuery = searchFocusedKey == FileBrowserSearchFocus.QueryKey,
                    onFilter = FileSearchFilter.entries.any {
                        FileBrowserSearchFocus.filterKey(it) == searchFocusedKey
                    },
                    onResult = FileBrowserSearchFocus.resultPath(searchFocusedKey) != null,
                )
            } else {
                fileBrowserFooterHints(filesResources(),
                    // A row held for a pending delete advertises nothing: every press on it is ignored.
                    focused = focusedTile.actionableTile(),
                    atRoot = controller.atRoot,
                    commitFocused = commitFocused,
                    // F-2. Same gated read as the row itself -- see the two parameters threaded into
                    // FileBrowserContent above for the shared source.
                    backToOriginFocused = backToOriginFocused,
                    searchAvailable = controller.searchAvailable,
                    // F-1/F-01. Threading these is what makes the input bindings visible at all: without
                    // them SELECT and START are live presses with no legend entry, which this file's own
                    // standing rule calls worse than no binding. The COUNT is the C-1 half -- Undo is a
                    // delete's only protection, so the queue screen holding it advertises how much is
                    // waiting. `actionsAvailable` is the one local read above (R-13/F-1), not
                    // `controller.deleteAvailable` directly -- it is also what gates the `X EXTRACT` entry
                    // now, so both must trace to the identical snapshot-side predicate.
                    actionsAvailable = actionsAvailable,
                    queueAvailable = controller.queueAvailable,
                    queueCount = controller.queueEntries.size,
                    // F-06 / D-3 — **the two destination-pick parameters, without which this legend lies three times.
                    //** A on the
                    // commit row would say `CHOOSE` (the add-a-ROM-source question, not the one being
                    // asked), `X EXTRACT` would stay drawn over a binding the chooser now consumes, and B
                    // would say `CLOSE` at the volume root for a press that cannot close anything
                    // mid-pick. Every one of those is `fileBrowserFooterHints`' own "a fixed legend would
                    // lie" rule, and all three come from the two values read once above.
                    commitPurpose = commitPurpose,
                    destinationPick = destinationPick,
                    imageFilterShowsAll = controller.imagePickShowsAll,
                    // W-1 — **the value that stops B lying about the one press that ends
                    // reversibility.** With deletes held, the root B opens the queue instead of closing,
                    // and this is what makes the legend say so. Same read the dispatch table branches on
                    // and the same read `FileBrowserStore.clearSession` finalises from, so the word, the
                    // press and the consequence are one fact rather than three.
                    heldDeleteCount = controller.heldDeleteCount,
                    // F-1/R-13. `transferAvailable` (G2's own `X PLACE` entry) is gone with the parameter
                    // it read -- Move/Copy no longer have a legend entry on X at all, see
                    // fileBrowserFooterHints' own deleted-arm comment for why a guarded-but-dead branch was
                    // rejected in favour of removing it outright.
                                sourcePickAvailable = controller.commitPurpose == FileBrowserCommitPurpose.CHOOSE_SOURCE,
                    // PICK_IMAGE (2026-09-11). The same gate the dispatch table and FileTile's dimming
                    // read, so the legend's A and the tile's presence are one fact.
                    imagePickAvailable = focusedTile != null &&
                        fileBrowserFileChoosable(controller.mode, focusedTile.name),
            )
            },
            palette,
        )
      }
      // Composed OUTSIDE the Column so it floats over the search screen rather than compressing it --
      // the same bottom-anchored arrangement every other keyboard host in this app uses. The sheet transition: it rides the shared sheet host, so it slides in from the
      // bottom edge and fades on the sheet tier's one clock like the Library's keyboard, and it stays composed
      // through its exit, with its focus binding withdrawn in the frame the query field lets go of it and
      // every touch on it shielded. It used to fade in over 160 ms with no travel (V-1) and then cut out.
      // See FileBrowserSearchKeyboard for the lifecycle.
      MimirSheetHost(
          visible = controller.searchKeyboardOpen,
          reducedMotion = reducedMotion,
          modifier = Modifier
              .align(Alignment.BottomCenter)
              .fillMaxWidth(0.94f),
      ) {
          FileBrowserSearchKeyboard(controller = controller, authority = authority)
      }
      // Rename's keyboard rides the same sheet tier as the search keyboard, for the same reasons: see
      // FileBrowserRenameKeyboard for its lifecycle.
      MimirSheetHost(
          visible = controller.renameOpen,
          reducedMotion = reducedMotion,
          modifier = Modifier
              .align(Alignment.BottomCenter)
              .fillMaxWidth(0.94f),
      ) {
          FileBrowserRenameKeyboard(controller = controller, authority = authority)
      }
    }
    }
}

// ─────────────────────────────────────────────────────── F-09 / C-0 · the Verify press, made honest ──
//
// **The reported symptom:** the Checksum row still reads "Not checked" after a verify.
// Tracing the pipeline found the HAPPY PATH CORRECT -- the path string is threaded
// identically from the focused tile through `Checking(path)` to `Checked(path, …)`, and
// `fileBrowserChecksumRow`'s guard compares it against that same tile's path. So the defect is not in
// what happens when Verify runs; it is in the case where Verify DOESN'T run and says nothing.
//
// `FileBrowserController.verifyFocusedChecksum` is `focusedTile()?.let { onVerifyChecksum(it.path) }`
// with no else branch, and `focusedTile()` re-reads `authority.state.value` LIVE while this surface
// drew the row from a `collectAsState()` snapshot. `focusedTileAt`'s own KDoc names that asymmetry:
// the two callers "differ only in how reactively they obtain the key". When they disagree, the press
// is swallowed and the row keeps reading "Not checked" -- the reported symptom, with no failure
// anywhere to show. `FileBrowserControllerMountStateTest` already pins one live instance of the
// swallow ("the commit row is focused first in PICK mode, so nothing verifies").
//
// **This closes the silent path; it does not claim to have reproduced F-09.** The fix is correct
// whichever cause wins, because "Not checked" after a press is the one thing the row must never say.
// The two reads below are the SAME authority object asked the SAME question inside one synchronous
// press handler -- not a second copy of a policy that can drift from the controller's.

/**
 * The path `FileBrowserController.verifyFocusedChecksum` would act on **right now**, or null when it
 * would silently no-op.
 *
 * A transcription of that function's own two-step read -- scope guard, then [focusedTileAt] -- so the
 * surface can ask, at press time and with no clock, whether the press it is about to make has a target.
 * Pure, so the transcription is asserted rather than trusted.
 */
internal fun fileBrowserLiveFocusedPath(
    live: FocusState,
    browseScope: FocusScope,
    partition: FileBrowserPartition,
): String? = focusedTileAt(partition, live.takeIf { it.scope == browseScope }?.focused)?.path

/**
 * Whether a Verify press fires.
 *
 * Both halves are load-bearing. [livePath] non-null is "the controller will do something"; equality with
 * [drawnPath] is "and it will be about the file whose row the player pressed". Firing on a live target
 * that is NOT the drawn one would be strictly worse than the swallow it replaces -- it would put a
 * digest under a different file's name, which is the wrong-40-character-claim that
 * [FileBrowserChecksumState.path] exists to prevent.
 */
internal fun fileBrowserVerifyFires(drawnPath: String?, livePath: String?): Boolean =
    drawnPath != null && livePath == drawnPath

/**
 * The row a refused press renders.
 *
 * **Only over an IDLE row.** A refusal must never overwrite a real answer: if `app/` has since reported
 * `Checking`, `Checked` or `Failed` for this file, that answer is the truth and this one is stale. And
 * because [refusedPath] is compared against the focused path, moving the ring one tile clears it with no
 * clearing call anywhere.
 *
 * **Nothing here ever writes `Checking`.** A row that flipped itself to "Reading…" on press would claim
 * work had started that this module cannot see start -- [FileBrowserChecksumState]'s own KDoc forbids
 * exactly that, and under `onVerifyChecksum`'s no-op default the claim would be permanent. The refusal
 * says the opposite thing: nothing started.
 */
internal fun fileBrowserVerifyRefusalRow(
    res: Resources,
    row: FileBrowserChecksumRow,
    refusedPath: String?,
    focusedPath: String?,
): FileBrowserChecksumRow {
    if (refusedPath == null || focusedPath == null || refusedPath != focusedPath) return row
    if (row.tone != FileBrowserChecksumTone.IDLE) return row
    return row.copy(status = res.getString(FileBrowserVerifyRefusedLabel), tone = FileBrowserChecksumTone.FAILURE)
}

/**
 * What the row says when a press could not be honoured.
 *
 * Says only what is certainly true -- **the work never began**. It deliberately does not blame the file
 * ("could not read this file" would be a claim about the player's data that nothing here checked) and it
 * deliberately is not [FileBrowserChecksumIdleLabel]: "Not checked" after a press is the exact sentence
 * F-09 is about.
 *
 * **No longer spells out "try again"** -- the Verify button sits on the same row and its own label already is the retry affordance;
 * restating "try again" in the sentence beside a button that says "Verify" was the row telling the player
 * twice. Device confirmation of this row's fit at `fontScale = 1` is still owed (§8 of that review) --
 * this text is shorter than what it replaced either way, so it can only improve the margin computed by
 * [fileBrowserChecksumStatusWidthDp], never worsen it.
 */
internal val FileBrowserVerifyRefusedLabel: Int = R.string.files_checksum_refused

// ───────────────────────────────────────── F-06 · what a destination pick changes about this screen ──
//
// Three drawn things stop telling the truth the moment `FileBrowserController.destinationPick` is
// non-null, and all three are this surface's to fix because all three are RENDERINGS of a decision the
// controller already made. The controller deliberately holds no player-facing string and no drawn
// control; it holds `commitPurpose` and `destinationPickPhase()`, and everything below is those two
// values projected onto words and visibility. Each is pure so it can be asserted without a Compose
// harness this module does not have.

/**
 * Whether the `SECONDARY` binding is live — **F-06 item 6.**
 *
 * `FileBrowserDispatch` consumes SECONDARY for the whole duration of a destination pick (X cannot mean
 * "extract this archive" while the browser is mid-answer about where to put a different one), and the
 * footer legend already drops its `X EXTRACT` entry for exactly that interval. The inspector's
 * **Extract here** button routes the same action, so without this it stays drawn over a binding nothing
 * will honour — *a drawn control over a dead binding is the same lie as a hint over one*, which is this
 * module's rule for hints ([fileBrowserFooterHints]) and for chrome ([FileBrowserPlaceholderChrome])
 * applied to the third case.
 *
 * **Gated on the PICK PHASE, not on `commitPurpose`, and the difference is reachable.**
 * `commitPurposeForCurrentMode` tests `commitAllowed(currentPath)` first, so at the drive chooser
 * (`/storage`) mid-pick the purpose is **null** while SECONDARY is still being consumed — a
 * purpose-gated button would reappear, live-looking and dead, on exactly the listing UI-05 was about.
 * `destinationPickPhase()` is the value the legend reads, so the button and the hint cannot drift.
 */
internal fun fileBrowserSecondaryBindingLive(
    destinationPick: FileBrowserDestinationPickPhase?,
): Boolean = destinationPick == null

/**
 * Whether a breadcrumb tap is suppressed while the trail remains visible as location context.
 *
 * A crumb tap is [navigateToCrumb], which walks `dispatch(CANCEL)` until the path matches. `CANCEL` is
 * exactly the press whose meaning both of these states change:
 *  - **mid-pick** it walks Up while [FileBrowserDestinationPickPhase.AWAY], then fires
 *    `BackToChooser` at [FileBrowserDestinationPickPhase.SETTLED] — one command for both the extract and
 *    a transfer pick as of G2, since it reads which chooser to reopen off the pick session rather than
 *    naming one in the dispatch table — then *closes the chooser* —
 *    so a tap on a crumb two levels up would unwind the entire question and land the player back in the
 *    listing with no extraction and no explanation. Safe, but it reads as the pick evaporating.
 *  - **with the chooser open** the header is still composed above it (the chooser replaces only the
 *    content slot), and every CANCEL in that loop is absorbed by `dispatchExtract` — the first one closes
 *    the hard modal and the rest walk the player up the tree from underneath it. The magnifier and the
 *    queue plate have no such hole because `openSearch`/`openQueue` close the chooser themselves; crumbs
 *    are the only header affordance that reaches `dispatch` directly.
 *
 * **The trail keeps being DRAWN, and that is the deliberate half.** It is not chrome here: during a pick
 * it is the answer to *"where am I about to extract to?"*, and while the chooser is open it is the
 * context the two rows are being chosen for. Hiding it would remove the one piece of information the
 * question needs. So this accepts what [fileBrowserSecondaryBindingLive] refuses one paragraph above —
 * an affordance that draws and does not act — and the difference is that a crumb's *primary* job on this
 * screen is to be read, while a button's only job is to be pressed. **Stated as a decision, and it is the
 * weaker of the six**: if device review finds the trail feeling dead mid-pick, the fix is a muted treatment
 * for the suppressed interval, not a return to the silent unwind.
 */
internal fun fileBrowserCrumbTapsSuppressed(
    destinationPick: FileBrowserDestinationPickPhase?,
    extractChooserOpen: Boolean,
): Boolean = destinationPick != null || extractChooserOpen

/**
 * The volume the search is scoped to, as a name the player recognises -- what
 * [fileBrowserSearchStatusLabel] puts after "matches on".
 *
 * Derived from [crumbLabels], which `app/` already fills from `StorageVolumeListing.volumeRows` (see
 * [fileBrowserBreadcrumbs]'s contract), by taking the DEEPEST labelled ancestor of [currentPath]. That is
 * the volume: labels exist for `/storage` and for each mount point, so the deepest match on
 * `/storage/1A2B-3C4D/Roms/gba` is `/storage/1A2B-3C4D` -> "SD Card".
 *
 * Null when nothing is labelled, and [fileBrowserSearchStatusLabel] then says "on this volume" rather
 * than naming a card that may be internal storage -- the same refusal to guess a volume name that
 * [fileBrowserBreadcrumbs] documents at length.
 */
internal fun fileBrowserSearchVolumeLabel(
    currentPath: String,
    crumbLabels: Map<String, String>,
): String? {
    if (crumbLabels.isEmpty()) return null
    val path = currentPath.trimEnd('/')
    return crumbLabels
        .asSequence()
        .map { it.key.trimEnd('/') to it.value }
        .filter { (key, value) ->
            value.isNotBlank() && (path == key || path.startsWith("$key/"))
        }
        .maxByOrNull { (key, _) -> key.length }
        ?.second
}

/**
 * A breadcrumb tap navigates by re-dispatching CANCEL/Up the right number of times is NOT how this
 * works -- crumbs jump directly. [FileBrowserController] has no public "jump to path" entry point (its
 * two-symbol surface deliberately keeps navigation to `dispatch`), so a crumb tap walks the controller's
 * own Up command repeatedly, which is observably identical to pressing CANCEL that many times and keeps
 * the controller's back-stack correctly shortened (never a raw path assignment that could desync it).
 */
private fun navigateToCrumb(controller: FileBrowserController, path: String) {
    // Count the required steps up front. Each Up pops the back-stack at once, but a folder loading from disk moves
    // `currentPath` only when it lands, so walking "until the path matches" kept popping, to the root.
    val ups = fileBrowserCrumbUpCount(controller.currentPath, path)
    var guard = 0
    while (guard < ups && !controller.atRoot) {
        controller.dispatch(ControllerAction.CANCEL)
        guard += 1
    }
}

/** How many Ups take [currentPath] to its ancestor [crumbPath]; 0 when [crumbPath] is not an ancestor. */
internal fun fileBrowserCrumbUpCount(currentPath: String, crumbPath: String): Int {
    val current = currentPath.trimEnd('/')
    val target = crumbPath.trimEnd('/')
    if (current == target || !current.startsWith("$target/")) return 0
    return current.removePrefix("$target/").split('/').count { it.isNotEmpty() }
}

// ───────────────────────────────────────────────────────────────────── Header ──

@Composable
internal fun FileBrowserHeader(
    // PICK_IMAGE (2026-09-11) draws its own title; every other mode keeps "Files". Decided by
    // fileBrowserHeaderTitle at the call site so this composable never reads the mode itself.
    title: String,
    rootPath: String,
    currentPath: String,
    crumbLabels: Map<String, String>,
    rowCount: Int,
    onCrumb: (String) -> Unit,
    searchAvailable: Boolean,
    searchActive: Boolean,
    onSearch: () -> Unit,
    queueAvailable: Boolean,
    queueCount: Int,
    queueActive: Boolean,
    onQueue: () -> Unit,
    palette: MimirThemePalette,
) {
    val crumbs = remember(rootPath, currentPath, crumbLabels) {
        // Trimmed from the LEFT so the current folder survives the telemetry pill that another layer
        // centres over this row -- policy 2026-08-15. See fileBrowserVisibleCrumbs.
        fileBrowserVisibleCrumbs(fileBrowserBreadcrumbs(rootPath, currentPath, crumbLabels))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            // F10: the search icon and QUEUE plate end clear of the M badge's corner.
            .padding(start = 18.dp, end = 12.dp + com.gruning.mimir.rewrite.core.ui.LocalMimirTopEndReserve.current),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The design's 27dp brand-mark tile and its three 38dp Search/Select/More buttons are GATED
        // OFF, not deleted --  Flip [FileBrowserPlaceholderChrome.ENABLED] to show them.
        if (FileBrowserPlaceholderChrome.ENABLED) {
            Box(
                modifier = Modifier
                    .size(27.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(palette.surfaceHigh)
                    .border(1.dp, palette.outline, RoundedCornerShape(9.dp)),
            )
        }
        // F-S3: Heading -- a panel/overlay title that sits inside a surface rather than naming the screen, which is exactly what this header is. Shrunk, then wrapped at its
        // words, then ellipsized, to the room its row leaves it (FileBrowserHeaderTitle).
        FileBrowserHeaderTitle(title, palette.textPrimary, Modifier.widthIn(max = 280.dp))
        Box(Modifier.width(1.dp).height(19.dp).background(palette.outline))
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            crumbs.forEachIndexed { index, crumb ->
                if (index > 0) {
                    // F-S3: the separator conforms with the crumbs it sits between.
                    Text("›", fontSize = MimirTypeStep.Body, color = palette.textMuted, maxLines = 1)
                }
                val isLast = index == crumbs.lastIndex
                Text(
                    text = crumb.label,
                    // F-S3: 13sp -> Body. A crumb is a tappable place name the player reads, not chrome.
                    fontSize = MimirTypeStep.Body,
                    fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isLast) palette.textPrimary else palette.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.pointerInput(crumb.path) {
                        detectTapGestures(onTap = { onCrumb(crumb.path) })
                    },
                )
            }
        }
        Text(
            text = rowCount.toString(),
            // F-S3: 10sp -> Micro, conforming DOWN. This is a bare COUNT, which is Micro's own charter;
            // ladder conformance runs in both directions or it is just a licence to grow type.
            fontSize = MimirTypeStep.Micro,
            color = palette.textMuted,
            maxLines = 1,
        )
        // F-08: SEARCH IS REAL, SO ITS GATE IS GONE. This button is no longer counted by
        // FileBrowserPlaceholderChrome and no longer behind FileBrowserPlaceholderChrome.ENABLED -- that
        // object's own KDoc asked for exactly this ("When a real Search/Select/More lands, delete this
        // gate rather than defaulting it true"), and only Select and More are still placeholders.
        //
        // Drawn only when a search provider was wired, so it is never an icon with nothing behind it.
        // It is a TOUCH shortcut to a function the D-pad already reaches through TERTIARY -- see
        // fileBrowserCommand's TERTIARY branch for why the icon is not itself a focus participant.
        if (searchAvailable) {
            FileBrowserSearchIconButton(
                active = searchActive,
                onClick = onSearch,
                palette = palette,
            )
        }
        // C-1. Drawn on the same terms as the START hint: whenever the queue exists, EMPTY OR NOT.
        if (queueAvailable) {
            FileBrowserQueueHeaderPlate(
                count = queueCount,
                active = queueActive,
                onClick = onQueue,
                palette = palette,
            )
        }
        if (FileBrowserPlaceholderChrome.ENABLED) {
            // Select / More. Inert by construction: they take no focus and carry no tap handler, so
            // enabling this shows the design's shape WITHOUT promising behaviour that does not exist yet.
            // Give each one a real action before it earns a gesture -- and delete its share of this gate
            // when that happens, as Search just did.
            repeat(FileBrowserPlaceholderChrome.HEADER_ACTION_COUNT) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.surfaceHigh.copy(alpha = 0.35f)),
                )
            }
        }
    }
}

/**
 * The design's own magnifier (`Mimir Files.dc.html:68`), ported rather than substituted: a 38 dp button
 * carrying an 18 dp mark drawn from the source SVG's own geometry —
 * `<circle cx="11" cy="11" r="6.5">` plus `<path d="M16 16l4.5 4.5">` in a 24×24 viewBox, at its stated
 * `stroke-width:1.7` and `stroke-linecap:round`. Every coordinate below is that file's; nothing is
 * eyeballed, and the whole mark scales from the viewBox so the drawn size is the only free number.
 *
 * Colour comes from the palette — `var(--mm-muted)` is `textMuted`, and the active state uses the ember
 * the design already gives an engaged header button (`selectIconColor`, line 767). No hex anywhere.
 */
@Composable
private fun FileBrowserSearchIconButton(
    active: Boolean,
    onClick: () -> Unit,
    palette: MimirThemePalette,
) {
    val tint = if (active) palette.emberHot else palette.textMuted
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(FileBrowserSearchIconSizeDp.dp)
                .drawWithCache {
                    // The source viewBox is 24 units wide; everything below is in those units, scaled once.
                    val unit = size.minDimension / 24f
                    val stroke = Stroke(width = 1.7f * unit, cap = StrokeCap.Round)
                    onDrawBehind {
                        drawCircle(
                            color = tint,
                            radius = 6.5f * unit,
                            center = Offset(11f * unit, 11f * unit),
                            style = stroke,
                        )
                        drawLine(
                            color = tint,
                            start = Offset(16f * unit, 16f * unit),
                            end = Offset(20.5f * unit, 20.5f * unit),
                            strokeWidth = 1.7f * unit,
                            cap = StrokeCap.Round,
                        )
                    }
                },
        )
    }
}

/** The design's own `width="18" height="18"` on every header icon (`Mimir Files.dc.html:68`). */
private const val FileBrowserSearchIconSizeDp: Float = 18f

/**
 * **The header keeps the Undo queue reachable.**
 *
 * **There is no confirm dialog on delete**, so reachable Undo is essential.
 * Undo lives on a queue card and nowhere else, so a player who deletes and never opens the queue has no
 * protection they can reach. UI-03 already established that a hint which appears and vanishes before the player can use it does not register as a control, so this is **persistent**: drawn whenever the queue exists, empty
 * or not, with only the number live. It is the same call [fileBrowserFooterHints] makes for the START
 * hint, and the two are drawn from the same two values so they cannot disagree.
 *
 * **A count plate, not an icon, and that is a disclosed decision rather than a port.** The design's
 * header carries a brand mark, "Files", the breadcrumbs, a row count, and three 38 dp buttons —
 * search / select / more (`Mimir Files.dc.html:68`). **It has no queue glyph at all**, because in the
 * prototype the queue is not reachable from the header. Drawing one would mean inventing a mark, and
 * this module has a standing rule against exactly that: `FileBrowserSearchQueryField` restates the
 * design's magnifier in type rather than redrawing it, and
 * [com.gruning.mimir.rewrite.core.ui.MimirPlatformShelfmark] refuses to draw a plate it cannot fill.
 * So the affordance is typographic, in the chip vocabulary this surface already owns, at the same 38 dp
 * footprint as its neighbour. **Offered as reversible and precedent-cited, not settled** — if a glyph is adopted, it is one component swap at this call site.
 *
 * **Touch shortcut only, deliberately not a focus participant** — the identical judgement
 * [FileBrowserSearchIconButton] records: SYSTEM_START already reaches this screen from the D-pad with a
 * footer hint advertising it, and adding a header focus slot would put a stop between the breadcrumbs
 * and the folio on every NAV_UP.
 */
@Composable
private fun FileBrowserQueueHeaderPlate(
    count: Int,
    active: Boolean,
    onClick: () -> Unit,
    palette: MimirThemePalette,
) {
    // Three states, all honest: open (warmed, the same "engaged" treatment the search icon uses), waiting (warmed -- there is something in there to settle), and idle
    // (the unselected chip -- the screen exists and has nothing in it). An empty queue is NOT hidden; that is the whole persistence policy.
    //
    // Update: an etched chip, not an ember-tinted capsule with its own outline. It keeps its 26 dp plate inside the 38 dp header slot (the slot is a fixed band and
    // must not grow); the tap area is the slot, and Compose's own 48 dp minimum touch target extends it, with no layout change, to the 48 dp a finger needs.
    val warmed = active || count > 0
    val ink = palette.etchedChipInk(warmed = warmed)
    Box(
        modifier = Modifier
            .height(38.dp)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 26.dp)
                .mimirEtchedChipSurface(palette, warmed = warmed)
                .padding(horizontal = 9.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(text = stringResource(FileBrowserQueuePlateLabel), style = MimirTypeScale.Pill, color = ink, maxLines = 1)
            Text(
                text = count.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                color = ink,
                maxLines = 1,
            )
        }
    }
}

/** The plate's word. "Queue" and not "Undo": the screen holds conflicts and finished transfers too, and
 * a plate promising Undo would be wrong on every card that has none. The footer's START hint uses the
 * same word for the same reason. */
@StringRes
private val FileBrowserQueuePlateLabel: Int = R.string.files_browse_queue_plate

/**
 *
 *
 * The header's brand mark and its three action icons were REMOVED in `c612c305` on this tree's own
 * precedent — an unnameable shelfmark draws no plate, [MediaArtFrame] draws nothing rather than a
 * wrong frame, and `fileBrowserFooterHints` refuses a fixed legend "because a fixed legend would
 * lie." An icon drawn on a touch surface is a promise.
 *
 * The provisional chrome remains gated for review: [ENABLED] is
 * the single switch. **OFF is the honest default and the shipped state** — nothing here does
 * anything yet.
 *
 * When a real Search/Select/More lands, delete this gate rather than defaulting it true: a permanent
 * flag around working features is how dead configuration accumulates.
 */
internal object FileBrowserPlaceholderChrome {
    /** OFF. Flip to `true` to render the design's remaining header chrome as inert placeholders. */
    const val ENABLED: Boolean = false

    /**
     * **Select and More — TWO buttons, down from three.**
     *
     * **Search left this gate on 2026-08-14 (F-08) and its share was DELETED, not defaulted true**, which
     * is what this object's own KDoc above asked for. It is a real, working screen now: its icon is drawn
     * whenever a search provider is wired ([FileBrowserController.searchAvailable]), it carries a tap
     * handler, and TERTIARY reaches it from the D-pad with a footer hint advertising it.
     *
     * Select and More stay gated because they are still exactly what this object was built for — shapes
     * with nothing behind them. Select is F-01's selection mode and More is the design's Storage /
     * Operations / Permissions sheet; neither exists in this tree. Each deletes its own share the same
     * way when it lands, and this constant reaches 0 — at which point the whole object goes with it.
     */
    const val HEADER_ACTION_COUNT: Int = 2
}

// ───────────────────────────────────────────────────────────────────── Content (tome + file grid) ──

@Composable
private fun FileBrowserContent(
    partition: FileBrowserPartition,
    // RC-12: the solved column/cell/pitch/viewport grid this content renders by -- see FileBrowserGrid's
    // own KDoc. Threaded from the surface's own BoxWithConstraints measurement, never re-solved here.
    grid: FileBrowserGrid,
    focusedKey: FocusKey?,
    commitFocused: Boolean,
    commitPurpose: FileBrowserCommitPurpose?,
    // F-2. Whether the sibling row currently holds focus, threaded on commitFocused's own discipline
    // (see FileBrowserScreen's gated computation). The row's own existence is decided locally below, from
    // `backKey` (hoisted a few lines down) -- see that val's KDoc for why re-deriving from `controller`
    // here is safe and matches `commitKey`'s own established pattern.
    backToOriginFocused: Boolean,
    authority: FocusAuthority,
    scope: FocusScope,
    controller: FileBrowserController,
    palette: MimirThemePalette,
    arrival: FileBrowserArrival,
    // R6-P2-02, coordinator review 2026-09-15 (finding 1). A PARAMETER, not a local `remember` --
    // held one level up, in FileBrowserSurface, so it survives a chooser or the action overlay taking
    // and releasing this composable's own slot. See that `remember(arrival)` site's own KDoc.
    travelPending: MutableState<Boolean>,
    modifier: Modifier = Modifier,
) {
    // PICK mode's pinned commit affordance (policy 6, restored 2026-08-14 -- see
    // FileBrowserController's KDoc) is its own LazyColumn item, first wherever it exists at all, outside
    // the tome/file grid entirely -- it is not a FileBrowserTile (no backing FileItem), so it never
    // participates in the tomes/files chunking below.
    //
    // UI-05, 2026-08-14: "unconditionally first" was the regression. `68df882e` taught the controller to
    // refuse a commit key where `commitAllowed` is false -- the drive chooser at /storage -- but this
    // line still asked only the mode, so the row rendered there anyway. It then registered PHYSICAL
    // focus bounds through its own `mimirFocusable` (the controller never registered it virtually, so it
    // entered the graph by a different route), became the only NAV_UP candidate above the volume rows,
    // and dispatched CONFIRM into a null commit key -- which returns Consumed. A control that looks
    // focused, promises CHOOSE and eats the A button, on the first screen a multi-drive player sees.
    // One rule now: the controller's.
    val showCommit = controller.commitAvailable

    // F-2. Read once here (showCommit's own precedent one line up), reused by both the settlement
    // LaunchedEffect below and the item block that renders the row -- a key re-derived from the SAME
    // controller within one composition pass cannot disagree with itself; see commitKey's identical
    // re-derivation-from-controller pattern a few lines down for the established precedent.
    val backKey = controller.backToOriginKeyForCurrentMode()

    // F-03. Computed BEFORE the empty-directory branch below, so its remembered slots survive a
    // navigation INTO an empty folder (that branch drops everything composed after it) -- and so an
    // empty folder is announced by the same glide as any other, which is the whole point: the player is
    // being told the state changed, not what is in it.
    //
    // V-1: only the DIRECTORY half of the surface's arrival is played here. A screen arrival is played
    // one level up, on the whole page, and the two are mutually exclusive by construction
    // (FileBrowserArrivalHistory.arrive's precedence rule) -- so this listing can never be translating
    // inside a page that is simultaneously fading.
    val directoryArrival = arrival.takeIf { it.kind == FileBrowserArrivalKind.DIRECTORY }
    // Focus-arrival timing: Re-created on the SAME arrival the glide keys on, so the two can never disagree about
    // which arrival is in flight; it starts already settled for every arrival that does not travel (a
    // first mount, a refresh, a return from search or the queue), which is what keeps the gate off the
    // movement path entirely. See [fileBrowserRingEligible]'s file KDoc for why gating the SWAP alone
    // satisfies both timing requirements.
    //
    // **Initialised from the ARRIVAL rather than from `fileBrowserNavigationGlideApplies` directly, and
    // that is the V-1 correction, not a tidy-up.** That predicate stays true forever after a navigation,
    // so on every re-mount of this column -- which is what closing search or the queue is -- it still
    // said "a glide is in flight" and gated the ring for one that was never started. The arrival model
    // consumes each armed motion once; see FileBrowserArrivalHistory.arrive.
    val glideSettled = remember(arrival) {
        mutableStateOf(directoryArrival == null)
    }
    // Walk-32b: when this arrival began, so a move made during it shows the ring at once (fileBrowserRingEligible).
    // Read in composition: a move that lands changes `focusedKey`, which recomposes this listing.
    val arrivalStartMs = remember(arrival) { MimirFocusTraversal.clock() }
    // **The listing must not PAINT until it has been re-seated.**
    //
    // `rememberLazyListState()` at the top of this function is unkeyed, so on a directory change the
    // parent is composed and DRAWN at the scroll position inherited from the child. The corrective
    // `SeekTo` in the settlement effect below is unanimated and correct, but a `LaunchedEffect` body
    // launches `CoroutineStart.DEFAULT` and is drained by `AndroidUiDispatcher`'s posted `Handler`
    // message -- so it lands AFTER `doFrame` has measured, laid out and drawn. One painted frame at the
    // wrong offset, then a snap. Measured on device: 214 px on a return into a scrolled parent, and a
    // full page returning from an EMPTY folder, where the early return above disposes the state entirely.
    //
    // Seeding the state's initial index was tried first and REVERTED: it only changes WHICH wrong offset
    // the first frame shows (measured -236 px against +214 px before), because the residual is the
    // centring, which needs `layoutInfo` and therefore cannot be known before layout.
    // `LazyListState.requestScrollToItem` -- a request applied during the next measure, which is exactly
    // this problem's primitive -- does not exist in `foundation-android-1.7.3` (disassembled; it landed
    // in 1.8) and cannot be reached: this project builds `--offline` and only 1.7.3 is cached.
    //
    // So the frame is suppressed rather than corrected. The lane is transparent for the one frame that
    // would have shown the wrong offset, and paints from the frame the seek has landed on. Alpha only:
    // focus, input and dispatch are untouched, and `MimirMotion.InputDelayMs` stays 0 -- this delays a
    // PIXEL, never a press. Same grammar as `FileBrowserRingGate`, which already withholds the ring
    // while a directory glide is arriving.
    //
    // R6-P2-04: raised for every arrival that ANIMATES, not just a directory change. Closing search or
    // the queue re-mounts this column too -- a SCREEN arrival -- with the identical fresh `remember` and
    // the identical late, dispatched `SeekTo`, so it is owed the same hidden frame a directory change
    // already gets. `fileBrowserArrivalAnimates` is the predicate the glide already keys on
    // (`fileBrowserArrivalGlide`'s own call site), so this now reads as one sentence: an arrival that
    // animates is seated first. A `NONE` arrival (refresh, recomposition, first mount) still raises
    // nothing.
    val seatPending = remember(arrival) { mutableStateOf(fileBrowserArrivalAnimates(arrival)) }
    // R6-P2-02. True from a travelling arrival's mount until its travel has landed or been cancelled.
    // Two duties, one window: the first settlement takes the animated branch instead of the blind seek,
    // and the ring waits (F-12) for the travel, as it already waits for the glide. Held by the CALLER
    // (coordinator review 2026-09-15, finding 1) -- see the parameter's own KDoc for why.
    val motionProbe = LocalFileBrowserMotionProbe.current
    if (motionProbe != null) SideEffect { motionProbe.listingSeated = { !seatPending.value } }
    val contentModifier = modifier
        .fileBrowserArrivalGlide(
            arrival = directoryArrival,
            onSettled = { glideSettled.value = true },
            alphaProbe = motionProbe?.let { probe -> { probe.listingGlideAlpha = it } },
        )
    // Paint eligibility ONLY. Nothing below gates dispatch, focus or scroll -- focus still flips on the
    // frame the press lands, the settlement scroll below still runs off `focusedKey`, and
    // `MimirMotion.InputDelayMs` stays 0. All that waits is the ring.
    val ringEligible = fileBrowserRingEligible(
        currentPath = controller.currentPath,
        motion = controller.navigationMotion,
        glideSettled = glideSettled.value,
        movedDuringArrival = directoryArrival != null && MimirFocusTraversal.movedSince(arrivalStartMs),
    ) && !travelPending.value
    // Deliberately a SECOND value rather than a narrowing of `focusedKey`: the settlement effect below
    // reads `focusedKey` to choose its scroll target, and blanking that would stop the destination from
    // scrolling to the landing tile at all -- trading a clipped ring for an off-screen one.
    val ringKey = focusedKey.takeIf { ringEligible }

    if (partition.isEmpty && !showCommit) {
        Box(contentModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(FileBrowserEmptyFolderNote),
                style = MimirTypeScale.Support,
                color = palette.textMuted,
            )
        }
        return
    }

    val listState = rememberLazyListState()
    // RC-12: keyed on `grid` too, not just the partition -- a reproject changes the column count with
    // the partition unchanged, and a chunking that stayed keyed on the partition alone would keep
    // painting the OLD column count until the next directory change, the exact paint/lattice divergence
    // this ticket exists to close.
    val tomeRows = remember(partition.tomes, grid) { partition.tomes.chunked(grid.tomeColumns) }
    val fileRows = remember(partition.files, grid) { partition.files.chunked(grid.fileColumns) }
    val density = LocalDensity.current
    // Remembered on `listState`, exactly as the reference does (`LibraryScreen.kt:2767`): the history
    // and the scroll owner must die together, or a fresh list would inherit a consumed restore epoch.
    val revealHistory = remember(listState) { FileBrowserRevealHistory() }

    // The folio's ONLY scroll system -- see FileBrowserRevealAction for why there used to be two, and
    // why all three mimirFocusable call sites below now pass `autoBringIntoView = false`. F-03's glide
    // is deliberately NOT here: it never touches this LazyListState at all.
    LaunchedEffect(controller, focusedKey, partition) {
        // `finally`, not a line at the end: this body has two early returns and is cancelled whenever a
        // new arrival re-keys it. A `seatPending` left true on any of those paths is an invisible
        // listing, which is a far worse failure than the snap it exists to hide.
        try {
        // No key at all: the first composition runs before `DisposableEffect` calls `open()`, and
        // `load()` itself calls `clearFocus()` before it re-requests.
        if (focusedKey == null) return@LaunchedEffect

        // The controller's answer, never `mode` alone (UI-05): at the drive chooser no commit row is
        // rendered, so nothing is offset by it either.
        val commitKey = FileBrowserFocus.commitKey(controller.currentPath).takeIf { showCommit }
        // F-2. backKey (hoisted above) resolves to the SAME item index as commitKey
        // (fileBrowserScrollTargetIndex's `|| focusedKey == backKey` arm): the sibling lives in
        // commitKey's own LazyColumn item, never a second one.
        val targetIndex = fileBrowserScrollTargetIndex(
            partition = partition,
            focusedKey = focusedKey,
            commitKey = commitKey,
            grid = grid,
            backKey = backKey,
        ) ?: return@LaunchedEffect

        // The restoration epoch is consumed only by a frame that actually LANDED -- after both guards
        // above, and this ordering is a deliberate, grounded deviation from the reference.
        //
        // `ScrollFocusedLazyItemIntoView` consumes immediately after its null-key guard, and that is
        // right THERE because its `focusedIndex` and its `contentIdentity` are derived from one source
        // (the ViewModel's own list), so they cannot disagree about which listing is current. Here they
        // come from two different state systems: `partition` is a direct `mutableStateOf` write, while
        // `focusedKey` arrives through `collectAsState` on the authority's `StateFlow`. A frame carrying
        // the NEW partition beside the OLD directory's key is therefore possible and cannot be ruled out
        // off-device. Consuming on that frame would burn the epoch, resolve no target, return -- and the
        // next frame, with `restoring` now false, would ANIMATE the directory change from wherever the
        // outgoing listing happened to sit. That is UI-02's "correctly animated to the wrong place",
        // rebuilt.
        //
        // Safe for every other path: `fileBrowserScrollTargetIndex` is pure over the partition and never
        // consults `layoutInfo`, so a first mount with an empty viewport still resolves an index here and
        // still takes the non-animated seek below.
        //
        // **Not covered by any test in this module.** The unit tests exercise
        // `fileBrowserRevealAction`, which receives `restoring` already decided; this ordering is
        // upstream of it and passes either way. Read-verified only, device-certifiable only.
        val restoring = revealHistory.consume(partition) && !travelPending.value

        // R6-P2-02, coordinator review 2026-09-15 (finding 2). `listState` is not reliably fresh for a
        // travelling arrival: on a cache MISS, `load()` (`FileBrowserController.kt`) suspends, so this
        // column mounts on the OUTGOING directory first -- a SCREEN arrival, seated (by the ordinary
        // SeekTo path above) on the tile the player left there. `listState` is never re-created between
        // that mount and the DIRECTORY-reveal arrival that follows once the target directory's partition
        // lands (no key on this composable changes; `currentPath` catching up is what PRODUCES that
        // second arrival), so without this it travels from wherever the outgoing directory happened to
        // sit, not from a place this effect chose. Instant, and before `layoutInfo` is read below, so the
        // AnimateTo/ScrollBy choice that follows is made against the origin just set here, not the stale
        // one -- the top, per spec §2.3's simpler of its two named options. A no-op when already there
        // (a cache HIT never leaves this column mounted on anything else first).
        if (travelPending.value) listState.scrollToItem(0, 0)

        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.index == targetIndex }
        val action = fileBrowserRevealAction(
            targetIndex = targetIndex,
            firstVisibleIndex = layout.visibleItemsInfo.firstOrNull()?.index,
            visibleCount = layout.visibleItemsInfo.size,
            focusedItemOffset = item?.offset,
            focusedItemSize = item?.size,
            viewportStart = layout.viewportStartOffset,
            viewportEnd = layout.viewportEndOffset,
            estimatedItemSize = fileBrowserFocusRowHeightDp(partition, focusedKey, commitKey, grid, backKey)
                ?.let { with(density) { it.dp.roundToPx() } },
            restoring = restoring,
        )
        // The three-way spec split is the reference's contract, not an omission (`LibraryScreen.kt:
        // 2794-2804`): a seek is unanimated by definition, an index-addressed travel to an uncomposed
        // destination takes Compose's own default, and only the measured settle carries the house tween.
        if (action is FileBrowserRevealAction.SeekTo) listState.scrollToItem(action.index, action.scrollOffset)
        // The seat has landed, or none was needed: paint from the next frame. BEFORE any animated step --
        // those suspend for their whole duration, and a gate released only in `finally` would hide a
        // reveal's entire journey and pop the listing in at the end (R3).
        seatPending.value = false
        when (action) {
            FileBrowserRevealAction.None, is FileBrowserRevealAction.SeekTo -> Unit
            is FileBrowserRevealAction.AnimateTo -> listState.animateScrollToItem(action.index, action.scrollOffset)
            is FileBrowserRevealAction.ScrollBy -> listState.animateScrollBy(
                value = action.delta,
                animationSpec = tween(MimirMotion.FocusMs, easing = MimirMotion.Easing),
            )
        }
        } finally {
            seatPending.value = false
            travelPending.value = false
        }
    }

    LazyColumn(
        state = listState,
        // The seat gate lives HERE, on the scrollable listing, and NOT on `contentModifier` -- which the
        // empty-folder branch above also uses, and which returns BEFORE the settlement effect that
        // clears the flag. Shared, it made "This folder is empty." permanently invisible: device observation confirmed the missing empty-folder hint.
        // An empty directory has no scroll position to be wrong about, so it needs no gate at all.
        modifier = contentModifier.graphicsLayer { alpha = if (seatPending.value) 0f else 1f },
        verticalArrangement = Arrangement.spacedBy(9.dp),
        // Top and bottom: a lazy list clips exactly on its axis, and a focused row's ring reaches
        // MimirFocusTileRingReach past the row (ring migration, 2026-09-28). End stays the design's own.
        contentPadding = PaddingValues(top = MimirFocusTileRingReach, end = 5.dp, bottom = MimirFocusTileRingReach),
    ) {
        if (showCommit) {
            // V-4: `commit-affordance:<path>`, not `commit-affordance`. **The row is a different row in
            // every directory, and keying it by a bare literal made it the one slot that outlived a
            // navigation.** It carries `mimirFocusRing`'s four `animateFloatAsState`s, so a return that
            // lands on a remembered TILE rather than back on this row (B from the commit row, then
            // `load`'s `focusByDirectory` hit in the parent) left it fading its gold border out over
            // 140 ms in a directory the player had already arrived in -- the same ghost the tiles had,
            // reached by the RETURN path. The path is the row's identity
            // for every other purpose already ([FileBrowserFocus.commitKey] namespaces by it); this makes
            // the composition agree. Prefix kept so the literal stays greppable.
            item(key = "commit-affordance:${controller.currentPath}") {
                // The Back-to-origin and commit controls share ONE LazyColumn item,
                // ONE census entry (see FileBrowserGhostFreeIdentityTest: a plain `if`, not a collection,
                // is outside that census's scope by construction), two focusable children side by side.
                // FileBrowserCommitRow is untouched below -- its own width cap is PENDING REVIEW proportion
                // this lane does not perturb -- and the sibling claims whatever the Row has left after it
                // (~230dp of the 508dp content column at the cap's own numbers), never a hand-picked dp.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FileBrowserCommitRow(
                        currentPath = controller.currentPath,
                        // The Use folder ring clipped during swaps. The
                        // commit row is item 0 of a listing that is still gliding in when focus lands on it.
                        focused = commitFocused && ringEligible,
                        purpose = commitPurpose,
                        authority = authority,
                        scope = scope,
                        controller = controller,
                        palette = palette,
                    )
                    // backKey (hoisted above, from controller.backToOriginKeyForCurrentMode()) is this
                    // row's own existence flag. controller.pickOriginPath is re-read here rather than
                    // threaded, on commitKey's own precedent two lines up in this same file -- both are
                    // pure, cheap, and consistent within one composition pass.
                    if (backKey != null) {
                        FileBrowserBackToOriginRow(
                            key = backKey,
                            label = fileBrowserBackToOriginLabel(filesResources(), requireNotNull(controller.pickOriginPath)),
                            detail = fileBrowserBackToOriginDetail(requireNotNull(controller.pickOriginPath)),
                            focused = backToOriginFocused && ringEligible,
                            authority = authority,
                            scope = scope,
                            controller = controller,
                            palette = palette,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        if (partition.isEmpty) {
            item(key = "empty-note") {
                Text(
                    text = fileBrowserEmptyNote(filesResources(), controller.imagePickHiddenCount),
                    style = MimirTypeScale.Support,
                    color = palette.textMuted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        if (partition.tomes.isNotEmpty()) {
            // OR-9 : these are folders; onboarding teaches Tome as a collection. The key keeps its name.
            item(key = "tomes-heading") { SectionHeading(stringResource(R.string.files_browse_folders_heading, partition.tomes.size), palette) }
            // V-4: **content identity, never `$index`.** `row.first()` is total here and does not need a
            // guard -- `chunked()` never emits an empty chunk and this branch is inside
            // `partition.tomes.isNotEmpty()`. A tile's path is in exactly one chunk, so the key is unique
            // within the list, and the `tome-row:` / `file-row:` prefixes keep it unique against the
            // headings and the commit row even if a partition ever placed one path on both sides.
            //
            // **Belt AND braces, stated precisely rather than sold as two fixes.** Disposing a row node
            // also disposes the tiles inside it, so EITHER this key or [TomeTileRow]'s `key(tile.path)`
            // would have closed the reported ghost on its own. Both are here because they answer
            // different questions: the tile key says *a tile's identity is its file*, which is the
            // invariant; this key removes the last positional identity from a file whose own KDoc now
            // forbids them, so the next author cannot read one as sanctioned. See [TomeTileRow].
            itemsIndexed(tomeRows, key = { _, row -> "tome-row:" + row.first().path }) { rowIndex, row ->
                // F-12: `ringKey`, not `focusedKey` -- the focus graph is unchanged, only the paint waits.
                TomeTileRow(row, grid.tomeColumns, rowIndex, ringKey, authority, scope, controller, palette)
            }
        }
        if (partition.files.isNotEmpty()) {
            item(key = "files-heading") { SectionHeading(stringResource(R.string.files_browse_files_heading, partition.files.size), palette) }
            // V-4: content identity -- see the tome rows above for the totality and uniqueness argument.
            itemsIndexed(fileRows, key = { _, row -> "file-row:" + row.first().path }) { rowIndex, row ->
                // F-12: `ringKey`, not `focusedKey` -- see TomeTileRow above.
                FileTileRow(row, grid.fileColumns, rowIndex, ringKey, authority, scope, controller, palette, grid.fileRowPitchDp - FileBrowserRowGapDp)
            }
        }
    }
}

// ───────────────────────────────────────────────────────── Motion (F-02 settle · F-03 folder glide) ──

/**
 * Which listing this surface last settled against. Split out for one reason: `restoring` must be
 * consumed exactly once per listing, and a plain local would be re-derived on every recomposition.
 * Mirrors `LibraryScreen.kt:2808-2811`'s `LibraryFocusRevealHistory`.
 */
private class FileBrowserRevealHistory {
    private var initialized = false
    private var contentIdentity: Any? = null

    /** True when [identity] is a listing this history has not settled against yet -- first composition
     * included. [FileBrowserController.load] builds a fresh [FileBrowserPartition] on every load, so a
     * directory change and a post-extraction [FileBrowserController.refresh] both read as new content
     * (both want the non-animated seek), while focus movement inside one listing does not. */
    fun consume(identity: Any?): Boolean {
        val restoring = !initialized || contentIdentity != identity
        initialized = true
        contentIdentity = identity
        return restoring
    }
}

/**
 * **The browser shares one transition across directory changes and all other surface arrivals** — the duration, the easing, the frame beat and the
 * layer are identical for every arrival, and only [FileBrowserArrival] differs. See
 * `FileBrowserSurfaceMotion.kt` for which arrivals exist, why a screen swap fades where a folder travels,
 * and what is deliberately left unanimated.
 *
 * **A restatement of `GamelistDestinationPageTransition`** (`feature-library`'s
 * `LibraryScreen.kt:3844-3885`), including its `withFrameNanos {}` beat, its
 * `tween(MimirMotion.ModeTransitionMs, MimirMotion.ModeTransitionEasing)` spec, and its two geometry
 * constants. Restated rather than imported because `feature-files` does not depend on that module and
 * the composable is private to it; every DURATION and EASING still comes from the house
 * [MimirMotion] tokens, so this introduces no new motion constant.
 *
 * **Destination-owned, so there is no second tree and no second scroll owner.** Only the incoming
 * listing moves; the predecessor is already gone. This is deliberately not `AnimatedContent`, which
 * would keep the outgoing listing composed -- a second `LazyColumn` running a second copy of the scroll
 * effect above, which is precisely the two-owners arrangement `MimirFocusable.kt:43-44` forbids. Nothing
 * here touches `LazyListState`: it is [graphicsLayer] alpha + translation, compositor-only, invariant #7.
 * That holds for the screen arrivals too: search and the queue still **replace** the folio rather than
 * overlaying it, and this modifier animates whichever one is composed, never two at once.
 *
 * **The frame beat is load-bearing, not decoration.** `withFrameNanos {}` gives the destination's rows
 * and focus registrations one complete beat before their pixels start moving. Without it the glide can
 * begin on a frame whose tree is still being rebuilt -- this tree's own UI-01, where a rebuilt subtree
 * discarded every animation handle in flight and each one restarted already at its target.
 *
 * **Progress is read inside the layer block**, so a running glide invalidates the draw layer only -- no
 * recomposition of the listing per frame.
 *
 * @param arrival what to play, or null for "not this call site's arrival". Null and
 *   [FileBrowserArrivalKind.NONE] both render a completely idle layer — alpha 1, no travel, no animation
 *   started and no [onSettled] — so a call site that is handed someone else's arrival is literally
 *   unchanged rather than merely equivalent. That is what keeps the two application sites below from ever
 *   playing at once.
 */
@Composable
private fun Modifier.fileBrowserArrivalGlide(
    arrival: FileBrowserArrival?,
    onSettled: () -> Unit = {},
    /** False for the description panel: it dips with the listing but never travels. */
    travels: Boolean = true,
    /** A test seam ([FileBrowserMotionProbe]): handed the alpha this layer paints, on the test's clock. */
    alphaProbe: ((() -> Float) -> Unit)? = null,
): Modifier {
    val animates = fileBrowserArrivalAnimates(arrival)
    // Folder-up, 2026-10-15 (Thor, combined44: 513 ms and one near-empty frame): going up, the parent animates nothing. It stands
    // opaque from its first frame, UNDER the departing child (`MimirPageHandoff` draws the recorded child above it and fades it
    // 1 -> 0), so the pane is `a*child + (1-a)*parent`: complementary, never less than one whole page, whatever `a` is. It used to
    // be held hidden for the child's whole clear (180 ms), then dip in from 0.88 (160 ms), and only then release the ring (140 ms
    // more): the child was gone a frame before the parent showed (the empty frame), and 180 + 160 + 140 plus the frame beats were
    // the 513 ms. The ring and the panel now settle with the clear, not after it.
    val standsAtRest = fileBrowserArrivalDeparts(arrival)
    // Keyed on the ARRIVAL, so the ring gate that waits for this animation and the animation itself
    // cannot be re-armed on different identities -- the reason FileBrowserArrival.epoch exists, and the
    // same guarantee fileBrowserRingEpoch gave when the directory glide was the only one.
    val progress = remember(arrival) { Animatable(if (animates && !standsAtRest) 0f else 1f) }
    val startAlpha = fileBrowserArrivalStartAlpha(arrival)
    val travelDp = if (travels) fileBrowserArrivalTravelDp(arrival) else 0f
    LaunchedEffect(arrival) {
        if (!animates) return@LaunchedEffect
        withFrameNanos { }
        // Walk-32d: presses do not wait for any of it (the departing child is recorded pixels with no tree, no input and no focus,
        // and the parent's focus graph has been the only one, registered, since the Up itself).
        if (!standsAtRest) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = MimirMotion.ModeTransitionMs,
                    easing = MimirMotion.ModeTransitionEasing,
                ),
            )
        }
        // F-12: the ring's release, fired from the animation's OWN completion rather than a parallel
        // delay(). Two clocks would drift, and a focus treatment timed off a second clock is this tree's
        // UI-01. Bounded and self-cancelling: a new arrival changes the key, which cancels this effect
        // and re-creates the caller's state, so a never-fired callback can only belong to a glide whose
        // gate no longer exists. A parent at rest has no animation to complete: it is the frame beat
        // itself, so the ring (its own FocusMs fade) finishes inside the child's clear.
        onSettled()
    }
    val paintedAlpha = { startAlpha + ((1f - startAlpha) * progress.value.coerceIn(0f, 1f)) }
    if (alphaProbe != null) SideEffect { alphaProbe(paintedAlpha) }
    return this.graphicsLayer {
        val settled = progress.value.coerceIn(0f, 1f)
        alpha = paintedAlpha()
        // Zero for everything except a directory arrival -- see fileBrowserArrivalTravelDp, and
        // FileBrowserSurfaceMotion.kt's KDoc for why a screen swap must not move geometry.
        translationX = travelDp.dp.toPx() * (1f - settled)
    }
}

/**
 * [FileBrowserMode.PICK]'s pinned "Use this folder" commit affordance -- policy 6: a real
 * [Modifier.mimirFocusable] participant in the focus graph, styled as a row (matching the design's own
 * Move-dialog row shape, `Mimir Files.dc.html` lines 728-733) rather than a grid tile, since it sits
 * above the tome/file grid rather than inside it.
 *
 * ## F-10 — compact width and shared material
 *
 * **The treatment is reused, not invented.** It uses material vocabulary this app already owns:
 * the stamped leather plate [com.gruning.mimir.rewrite.core.ui.MimirMannazPin] draws -- a
 * `coverTop -> coverBottom` leather gradient under a `hardware` metal rim, both straight off
 * `palette.materials.hero`. Every colour below is that recipe read from the same public tokens. It
 * replaces the flat `surfaceRaised` fill.
 *  - **`Modifier.mimirNorseBookFrame` was considered and rejected**, though it is the public core-ui
 *    helper for exactly this material. It draws the companion book's **centre spine line**
 *    (`MimirNorseBook.kt:466-471`), a vertical hairline down the middle of whatever it is applied to.
 *    On an open book that is the binding; on a button it bisects the label.
 *  - `mimirNorseBookTreatment` itself is `internal` to `core-ui`, so `feature-files` cannot call it.
 *    The recipe is therefore restated here from the public tokens, which is a **third instance** of a
 *    two-line material; hoisting `Modifier.mimirStampedPlate(palette)` into core-ui remains deferred.
 *
 * **Focus stays compositor-cheap** (invariant #7): focusing swaps the gradient's lower stop and lifts
 * the rim's alpha. No size branch, no layout, nothing that can reflow the item under the ring.
 *
 * ## Width, and the dispatch-table hazard that had to be checked first
 *
 * Capped at [FileBrowserCommitRowWidthDp] rather than filling the content column. Two properties were
 * pinned and hard-won, and neither moves:
 *  - **Its focus registration and its index are untouched.** It is still the first `item {}` in the
 *    column at index 0, still registered through the same [FileBrowserFocus.commitKey],
 *    (its lazy key gained a `:<path>` suffix in V-4 — a COMPOSITION identity, which changes what the row
 *    remembers across a navigation and nothing about where it sits or what it registers),
 *    and [fileBrowserCommitVirtualBounds] -- which is column-0-aligned for NAV_DOWN determinism -- is
 *    not consulted here at all. The cap is applied **start-aligned** (a `LazyColumn` places items at
 *    `Alignment.Start` by default and this file sets no other), so the row's *physical* bounds still
 *    begin at column 0 and cannot disagree with its virtual ones about which tile is below it.
 *  - **`FileBrowserDispatch` consumed NAV_LEFT/NAV_RIGHT on this row because it had no horizontal
 *    neighbour -- checked here, then, and now updated rather than quietly stale.** F-2 (2026-08-18) places [FileBrowserBackToOriginRow] in the SAME `item {}`,
 *    immediately after this composable, while a destination pick is open. This row's own width cap and
 *    start-aligned physical bounds are UNCHANGED by that addition -- it still measures and registers
 *    exactly as this KDoc describes, and it is still the sole determiner of what sits below it in the
 *    tile grid ([fileBrowserCommitVirtualBounds] is column-0-aligned regardless of any sibling). What
 *    changed is in `FileBrowserDispatch.kt`: NAV_RIGHT off this row now hops to the sibling when one is
 *    open (a hand-computed `requestFocus`, never geometric `moveFocus` -- see that file's own comment for
 *    the recorded hazard), and stays consumed exactly as before when there is none to hop to.
 *
 * The number itself is [FileBrowserInspectorWidthDp], the standing inspector's own width, so the
 * screen's two pinned columns measure the same -- a statable relationship rather than a taste value.
 * **It is still a proposal about proportion and is PENDING REVIEW:** hardware validation must establish the proportion. F-2's sibling claims whatever width this cap leaves free in the pinned row (a `Row`
 * with this composable first and the sibling's own `Modifier.weight(1f)` after it) rather than a second
 * hand-picked number, so it inherits that same pending review proportion rather than adding a new one.
 */
@Composable
private fun FileBrowserCommitRow(
    currentPath: String,
    focused: Boolean,
    purpose: FileBrowserCommitPurpose?,
    authority: FocusAuthority,
    scope: FocusScope,
    controller: FileBrowserController,
    palette: MimirThemePalette,
) {
    val key = FileBrowserFocus.commitKey(currentPath)
    val copy = fileBrowserCommitCopy(
        filesResources(),
        purpose,
        imagePickHiddenCount = controller.imagePickHiddenCount,
        imagePickShowsAll = controller.imagePickShowsAll,
    )
    val hero = palette.materials.hero
    val shape = RoundedCornerShape(FileBrowserRowCorner)
    Row(
        modifier = Modifier
            // widthIn OUTSIDE fillMaxWidth: the cap has to narrow the incoming constraints before the
            // fill expands into them, or the fill wins and the cap does nothing.
            .widthIn(max = FileBrowserCommitRowWidthDp.dp)
            .fillMaxWidth()
            .defaultMinSize(minHeight = FileBrowserPinnedRowHeightDp.dp)
            // V-4, 2026-08-14: no focus grow (it was pinned to a scale of 1). **This row is the add-game path's landing element**
            // -- a first visit to any directory in FileBrowserMode.PICK lands on it (load()'s
            // `keys.firstOrNull()`) -- and it was the ONE row in this tree taking `mimirFocusRing`'s
            // 1.03f tile grow. The gate holds `focused` false for the whole 160 ms arrival, so the flag
            // flips on an ALREADY-COMPOSED row and `animateFloatAsState` runs for real instead of
            // starting at its target: the row grew 3% over a further 140 ms AFTER the page had landed.
            // A size change beginning after the page transition creates a second visible settlement.
            //
            // **The rule is read off this tree, not invented.** Every ROW in the repo is 1f -- this
            // module's own five (FileBrowserExtractSurface.kt:188, FileBrowserQueueSurface.kt:372,
            // FileBrowserSearchSurface.kt:261/318/455), plus LibrarySearchOverlay.kt:491/534/761,
            // LibraryScreen.kt:4011/4063/4120, LibraryComponents.kt:632, BrowseScreen.kt:276. The grow is
            // TILE/CARD vocabulary (PlatformTile's own comment: *"the 1.03 scale grows the WHOLE card"*,
            // LibraryComponents.kt:138-139; 1.02f on the carousel and the pickers). The folio's direct
            // reference row, `GamelistRow`, does not animate its focus treatment at all -- a snapping
            // border width/colour, an offset and a shadow (LibraryScreen.kt:2877-2892).
            //
            // The ring itself is UNCHANGED and still animates: border colour, border width and content
            // alpha all keep MimirMotion.FocusMs. What is removed is the geometry, which is the only
            // channel that reads as the page moving a second time. **FileTile keeps the 1.03f** -- it is
            // a tile and fits the tile vocabulary. One inconsistency remains (a tome in the same grid gets MimirNorseBookCover's
            // `focusScale = 1f` default).
            .mimirSelectionRing(focused = focused, identity = currentPath, shape = MimirRingShape.Row(FileBrowserRowCorner))
            .clip(shape)
            // The leather: MimirMannazPin's own two-stop cover gradient. Focused lifts the lower stop
            // from coverBottom to coverMiddle -- a colour change only, never a size or layout branch.
            .background(
                Brush.verticalGradient(
                    listOf(hero.coverTop, if (focused) hero.coverMiddle else hero.coverBottom),
                ),
                shape,
            )
            // The metal: the same `hardware` rim MimirMannazPin stamps its plate with, at that
            // component's own 0.72 resting alpha.
            .drawWithCache {
                onDrawBehind {
                    drawRoundRect(
                        color = hero.hardware.copy(
                            alpha = if (focused) FileBrowserPlateRimFocusedAlpha else FileBrowserPlateRimAlpha,
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(FileBrowserRowCorner.toPx()),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            }
            // This surface runs its own settlement scroll (see FileBrowserRevealAction); a second
            // bring-into-view here would fight it for the same LazyListState.
            .mimirFocusable(authority, key, scope, autoBringIntoView = false)
            .pointerInput(currentPath) {
                detectTapGestures(onTap = { controller.handleCommitTap() })
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            // F-06 item 3: the WORDS come from the controller's purpose, never from this row's own idea
            // of what it is for -- see [fileBrowserCommitCopy].
            text = copy.eyebrow,
            style = MimirTypeScale.Pill,
            // The hardware colour, so the eyebrow reads as stamped INTO the plate rather than printed
            // on top of it in an unrelated accent.
            color = hero.hardware,
            maxLines = 1,
        )
        Text(
            text = copy.label,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            // The cover's own ink, which is the token authored to be read against this leather on all
            // nine themes -- textPrimary is authored against surfaceBase and is a different contrast
            // problem.
            color = hero.onCover,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The narrower commit control is capped at the standing inspector's width so the screen's two
 * pinned columns measure the same. A cap, not a fixed size: a narrower viewport still gets a row that
 * fits it. PENDING REVIEW -- this is a proposal about proportion, closed only on hardware. */
internal const val FileBrowserCommitRowWidthDp: Float = FileBrowserInspectorWidthDp

/** [com.gruning.mimir.rewrite.core.ui.MimirMannazPin]'s own rim alpha, so the plate's metal matches the
 * shared component rather than being re-picked here. */
internal const val FileBrowserPlateRimAlpha: Float = 0.72f

/** The focused lift. Compositor-cheap by construction: an alpha, not a stroke width or a size. */
internal const val FileBrowserPlateRimFocusedAlpha: Float = 0.92f

/**
 * F-2's escape-hatch row label -- **"Back to `<origin folder name>`"**, policy 2026-08-18. The
 * origin's own BASENAME, never its path (the ticket's own wording: *"using the origin folder's own
 * name"*).
 *
 * **Degradation, pinned rather than left to guesswork.** [File.getName] answers `""` for a bare `/` or
 * any path ending in a slash -- a blank quote is exactly what the ticket forbids printing. `ifBlank {
 * originPath }` is not invented here: it is [fileBrowserBreadcrumbs]' own `rootFallback` idiom
 * (`File(rootPath).name.ifBlank { rootPath }`), reused verbatim so the two places in this module that
 * turn a path into a displayed name never disagree about how to handle the same edge case. A friendlier
 * volume-root label (the breadcrumb trail's own `crumbLabels` seam, e.g. "SD card") is out of this
 * function's reach on purpose: that map is threaded in from `app/`, which a controller-level string has
 * no way to depend on -- named here as a disclosed follow-up, not silently worked around.
 */
internal fun fileBrowserBackToOriginLabel(res: Resources, originPath: String): String = res.getString(FileBrowserReturnLabel)

/** The Return row uses a fixed label and sign. Folder identity is carried separately for semantics. */
@StringRes
internal val FileBrowserReturnLabel: Int = R.string.files_browse_return

/**
 * The return sign, the label. U+21A9 — the hooked arrow that means *go back the way you came*, not a plain
 * left arrow, which on this screen would read as "previous item" beside a horizontal row of siblings.
 *
 * The return sign is required; the exact glyph remains unreviewed.
 */
internal const val FileBrowserReturnSign: String = "\u21A9"

/**
 * The origin folder.
 *
 * **No longer drawn.** The Return row is one line, with no second-line folder name. Therefore the row now shows the sign and the word,
 * nothing else, and the origin is carried only by the semantics label — which is what a screen reader and
 * the test suite read, and costs no pixels.
 *
 * Kept as a function rather than deleted with its four degradation pins: basename, trailing slash,
 * blank-degrades-to-full-path and the breadcrumb root fallback are still the right answers to *"name this
 * folder"*, and the next surface that needs to will find them here rather than re-deriving them.
 */
internal fun fileBrowserBackToOriginDetail(originPath: String): String =
    File(originPath).name.ifBlank { originPath }

/**
 * Half the shared row width: this one-word control uses 50% of the width of other rows.
 *
 * Derived from the shared row width rather than typed as a number, so it stays half of whatever that
 * becomes. It is deliberately NOT aligned to the sibling rows any more: a one-word control has no reason
 * to occupy a full destination row's width, and matching them is what made it look wrong.
 */
internal const val FileBrowserReturnRowWidthDp: Float = FileBrowserActionOverlayRowWidthDp / 2f

/**
 * The height the commit row and the Return row BOTH use.
 *
 * **One constant because they sit in the same `Row`, to prevent independent heights drifting.** The original
 * Return row read `defaultMinSize(minHeight = 46.dp)` with a comment saying it matched the commit row's
 * own minimum *"so the two rows' plates align on one visual line rather than one being taller than the
 * other."* An intermediate change used the action overlay's height instead of the adjacent commit row's height,
 * breaking alignment.
 *
 * A shared constant rather than the same number typed twice: two literals that must agree is the shape
 * that let them disagree.
 */
internal const val FileBrowserPinnedRowHeightDp: Float = 46f

/** One symbol for a browser row's clip and its ring (ring migration, 2026-09-28; R-1): listed, pinned, or in a dialog. */
internal val FileBrowserRowCorner = 12.dp

/** The file tile's corner, shared by its clip and its ring. */
internal val FileBrowserTileCorner = 14.dp

/**
 * F-2's escape hatch, [FileBrowserCommitRow]'s new sibling in the SAME pinned row -- one press returns the browse listing to the directory a
 * destination pick started in, from any depth, via [FileBrowserController.handleBackToOriginTap] /
 * [FileBrowserCommand.BackToChooser].
 *
 * **Deliberately the plainer of the two rows.** [FileBrowserCommitRow] carries the hero leather/metal
 * treatment because it is the PRIMARY question this screen is asking; this is a secondary escape, so it
 * borrows [FileBrowserDiscSetOptionRow]'s simpler `surfaceHigh`/`surfaceRaised` vocabulary instead --
 * still fully palette-driven, no hex literal, and disclosed as a starting point rather than a finished
 * visual treatment: exact emphasis is still subject to design review, the same standing this row's neighbour
 * carries for its own width.
 *
 * `Modifier.mimirFocusable` + `detectTapGestures` + `.semantics { onClick }`, never `clickable` -- the
 * design standing's sanctioned three-part pattern, [FileBrowserDiscSetOptionRow]'s own current house
 * example, all three legs present (unlike [FileBrowserCommitRow], which predates the semantics leg -- a disclosed, pre-existing gap in that composable).
 *
 * @param key [FileBrowserFocus.backToOriginKey] for the open pick's origin -- passed by the caller
 *   ([FileBrowserDiscSetOptionRow]'s own shape) rather than re-derived from a path parameter here, since
 *   the identity is origin-namespaced, not derivable from anything this composable itself is handed.
 * @param modifier Applied first in the chain (`modifier.then(...)`), so the caller's
 *   `Modifier.weight(1f)` narrows the incoming constraint before this row's own `fillMaxWidth` expands
 *   into it -- [FileBrowserCommitRow]'s own "widthIn OUTSIDE fillMaxWidth" ordering, restated for a
 *   weight supplied by the parent `Row` instead of a literal cap.
 */
@Composable
private fun FileBrowserBackToOriginRow(
    key: FocusKey,
    label: String,
    /** Not drawn -- it rides the semantics label so the press still announces where it goes. */
    detail: String,
    focused: Boolean,
    authority: FocusAuthority,
    scope: FocusScope,
    controller: FileBrowserController,
    palette: MimirThemePalette,
    modifier: Modifier = Modifier,
) {
    val spokenLabel = if (detail.isBlank()) label else stringResource(R.string.files_browse_return_to, detail)
    Row(
        modifier = modifier
            // **Policy 2026-08-18, on the device: match the action overlay.** Copied from
            // FileBrowserActionOverlayRow rather than re-derived, including the ordering comment it
            // carries: widthIn OUTSIDE fillMaxWidth, because the cap has to narrow the incoming
            // constraints before the fill expands into them. Getting that order wrong is exactly the
            // reported defect -- this row filled the whole content column while every other row
            // on the screen capped, so it alone sat wider than the geometry it was supposed to align to.
            .widthIn(max = FileBrowserReturnRowWidthDp.dp)
            .fillMaxWidth()
            // The row it sits BESIDE, not the surface it borrows its material from. See
            // [FileBrowserPinnedRowHeightDp] for why this is a shared constant.
            .defaultMinSize(minHeight = FileBrowserPinnedRowHeightDp.dp)
            .mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Row(FileBrowserRowCorner))
            .clip(RoundedCornerShape(FileBrowserRowCorner))
            // **The control gradient, ** `materials.controlTop`->`controlBottom` is this product's CONTROL leather --
            // what MimirLiquidStatusPill, MimirRunicQuickRail and the home shelf's own rails already
            // paint. Not `hero.coverTop/Middle/Bottom`, which is the TOME cover: MimirAchievementCard's
            // own KDoc records that using the control gradient on a card was the wrong recipe, and the
            // inverse is just as wrong. This row is a control, so it wears the control's leather.
            //
            // The control uses the action overlay's leather material. Copying surfaceRaised from the
            // adjacent overlay instead would select the wrong material.
            .background(
                Brush.verticalGradient(
                    listOf(
                        palette.materials.controlTop.copy(alpha = if (focused) 1f else 0.92f),
                        palette.materials.controlBottom.copy(alpha = if (focused) 1f else 0.92f),
                    ),
                ),
            )
            .mimirFocusable(authority, key, scope, autoBringIntoView = false)
            .pointerInput(key) { detectTapGestures(onTap = { controller.handleBackToOriginTap() }) }
            .semantics(mergeDescendants = true) {
                onClick(label = spokenLabel) {
                    controller.handleBackToOriginTap()
                    true
                }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // **One line, the Return-row layout.** Sign and word in a single centred Text rather than two children with a
        // gap: at half width there is no room to arrange, and one string cannot wrap between the sign and
        // the word the way two children can. `onCover` inks it, because the control gradient is what it
        // sits on -- reading textPrimary over leather is how a label ends up nearly invisible on one of
        // the four identities.
        Text(
            text = "$FileBrowserReturnSign  $label",
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.materials.hero.onCover,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionHeading(text: String, palette: MimirThemePalette) {
    Text(
        text = text,
        style = MimirTypeScale.RunicEyebrow,
        color = palette.textMuted,
        maxLines = 1,
    )
}

/**
 * **V-4, 2026-08-14 — `key(tile.path)`, and the worked example behind
 * [FileBrowserGhostFreeIdentity]. Not a tidy-up.**
 *
 * The folio's LazyColumn used to key its items by GRID POSITION (`"tome-row-$index"`,
 * `"file-row-$index"`) and this loop used to key its tiles by nothing at all. A tile's composition
 * identity was therefore *where it sat*, not *which file it was* — so a directory change handed the
 * outgoing listing's composition slot, and every `remember` in it, to whatever the incoming listing
 * happened to put in the same cell.
 *
 * **What rode along in that slot is a focus animation mid-flight.** [MimirNorseBookCover] holds three
 * `animateFloatAsState`s (`MimirNorseBook.kt:231-245`) and [Modifier.mimirFocusRing] four more
 * (`MimirTokens.kt:380-404`), all on `MimirMotion.FocusMs`. The tile that held the ring in the OUTGOING
 * directory left them at their focused targets; the tile that inherited the slot composes with
 * `focused = false` — the gate holds `ringKey` null for the whole arrival — so those seven animations run
 * BACKWARDS, over 140 ms, painting the previous selection's gold bloom on a folder the player never chose,
 * while the listing translates 22 dp underneath it. That is the trail, and it is also half of the
 * "resettle": the ring leaves at the old grid position, then arrives 160 ms later at the landing element,
 * so the eye is shown the ring settling twice for one press.
 *
 * **Neither the arrival model nor [fileBrowserRingEligible] can reach this, which is why five rounds did
 * not.** Both decide *when* a ring may paint for the FOCUSED key. This ghost is painted by a key that is
 * not focused and never was — a slot animating away from a focus it inherited. No arming rule can gate a
 * ring the gate is not being asked about.
 *
 * **Grounded in this tree's own reference, not invented.** `FileBrowserContent`'s glide is a restatement
 * of `feature-library`'s `GamelistDestinationPageTransition`, and the list that transition animates keys
 * its rows by CONTENT: `items(games, key = { it.gameId.value })` (`LibraryScreen.kt:2742`), as do its two
 * siblings (`:3215` by `packageName`, `:3767` the same). The folio restated the transition and not the
 * keying. This closes that gap. The reference file even names the artefact in its own words —
 * *"leaves the familiar focus/scroll **ghost trail**"* (`LibraryScreen.kt:2881-2882`).
 *
 * **Applied at BOTH levels; the second protects future stateful rows.** The
 * tile's own group is content-identified here, and the LazyColumn's row keys became
 * `"tome-row:" + row.first().path` in the same change. The rows hold no `remember` today, so keying them
 * closes nothing that was broken — it removes the positional key a future stateful row would have
 * inherited. Leaving one positional key in a file whose
 * KDoc forbids them is how the next author learns the wrong lesson. `key` is `inline`, so `RowScope`
 * survives and `Modifier.weight(1f)` still resolves.
 *
 * **No focus-graph change, and that is checkable rather than hoped for.** `Modifier.mimirFocusable`
 * already carries `DisposableEffect(key, scope) { onDispose { authority.unregister(key) } }`
 * (`MimirFocusable.kt:61-63`) and the FocusKey is path-derived, so a reused slot ALREADY unregistered the
 * old key and registered the new one on a directory change; this changes what the tile REMEMBERS, not what
 * the authority is told. And `FileBrowserController.load` seeds the landing through the tracker's virtual
 * registrations before any physical re-registration runs, so where focus lands is untouched. Focus landing was not the defect — paint identity was.
 *
 * **One disclosed behaviour change, on the refresh path.** After contract A's post-extraction
 * `FileBrowserController.refresh`, a file whose grid position shifted now re-composes at its target
 * instead of animating out of the previous occupant's state — an instant ring at the new position rather
 * than today's ghost pair. A refresh that changes no paths is byte-identical: same keys, same groups,
 * nothing disposed.
 */
@Composable
private fun TomeTileRow(
    row: List<FileBrowserTile>,
    // RC-12: the SOLVED column count this row was chunked at ([FileBrowserContent]'s `tomeRows`), not
    // the authored ceiling -- this is what makes the ragged-row filler below match the same lattice
    // width [FileBrowserFocusRegistration]'s virtual bounds were registered against.
    columns: Int,
    rowIndex: Int,
    focusedKey: FocusKey?,
    authority: FocusAuthority,
    scope: FocusScope,
    controller: FileBrowserController,
    palette: MimirThemePalette,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FileBrowserTomeColumnGapDp.dp)) {
        row.forEach { tile ->
            val key = FileBrowserFocus.tileKey(tile.path)
            // V-4: content identity, not grid position. See this function's KDoc.
            key(tile.path) {
                TomeTile(
                    tile = tile,
                    focused = key == focusedKey,
                    authority = authority,
                    key = key,
                    scope = scope,
                    controller = controller,
                    palette = palette,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
internal fun FileTileRow(
    row: List<FileBrowserTile>,
    // RC-12: see [TomeTileRow]'s own `columns` KDoc -- identical reasoning, file section.
    columns: Int,
    rowIndex: Int,
    focusedKey: FocusKey?,
    authority: FocusAuthority,
    scope: FocusScope,
    controller: FileBrowserController,
    palette: MimirThemePalette,
    // The height every tile of every row has at least ([FileBrowserGrid.fileRowPitchDp] less the gap): one number, so a row's tiles are one height whatever their names.
    tileHeightDp: Float = FileBrowserFileTileMinHeightDp,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FileBrowserFileColumnGapDp.dp)) {
        row.forEach { tile ->
            val key = FileBrowserFocus.tileKey(tile.path)
            // V-4: content identity, not grid position -- the ghost trail. [TomeTileRow]'s KDoc carries
            // the full argument; the file side inherits it verbatim, and the seven animations it strands
            // are `mimirFocusRing`'s four (MimirTokens.kt:380-404) rather than the cover's three.
            key(tile.path) {
                FileTile(
                    tile = tile,
                    focused = key == focusedKey,
                    authority = authority,
                    key = key,
                    scope = scope,
                    controller = controller,
                    palette = palette,
                    minHeightDp = tileHeightDp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

/**
 * A directory tile. Folders draw as [MimirNorseBookCover] -- not a hand-rolled box (task brief).
 *
 * **Nothing is faded.** Mimir's own folders (`saves`, `media`, `.mimir`) used to be demoted to 0.55 alpha
 * (adoption spec §4); that demotion was retired on 2026-10-08. They
 * draw at full presence and the cover's eyebrow names them -- "Mimir", or "Deleting…" for the staging
 * folder a held delete parks in the listing ([fileBrowserTomeEyebrow]) -- so a folder the player did not
 * make still reads as what it is. Never hidden, never resized.
 *
 * A Mimir folder keeps its embossed Mannaz: [FileBrowserPartition] forces `platformIdValue` to null for
 * machinery, and with no [MimirNorseBookCover.plate] either, the cover falls through to its own
 * `drawMimirMannaz` (`MimirNorseBook.kt:289-307`). That is the design's own `showRune: tome && !e.g`
 * (line 662), and it is why "no glyph" is satisfied without suppressing anything by hand.
 */
@Composable
private fun TomeTile(
    tile: FileBrowserTile,
    focused: Boolean,
    authority: FocusAuthority,
    key: FocusKey,
    scope: FocusScope,
    controller: FileBrowserController,
    palette: MimirThemePalette,
    modifier: Modifier = Modifier,
) {
    // Gated on the CODE, not on the id. Two ways to blank a cover here, both silent at compile time:
    //  (1) an UNCONDITIONAL plate lambda -- MimirNorseBookCover skips its embossed Mannaz whenever
    //      `plate != null` (MimirNorseBook.kt:289-309), so every unrecognised tome would lose its rune
    //      and gain nothing. Under this slice's `classifyPlatform = { null }` that is EVERY tome on a
    //      real device.
    //  (2) branching on `tile.platformIdValue != null` -- a recognised-but-uncodeable id suppresses the
    //      Mannaz via the cover's own `platformIdValue` parameter AND renders no shelfmark. See
    //      [fileBrowserPlatformCode]'s KDoc.
    // Passing the same nullable code to both parameters is what makes the two decisions inseparable.
    val platformCode = fileBrowserPlatformCode(tile.platformIdValue)
    val platformIdValue = tile.platformIdValue?.takeIf { platformCode != null }
    val glyphPlate: (@Composable BoxScope.() -> Unit)? = if (platformIdValue == null) {
        null
    } else {
        { TomeGlyph(platformIdValue, palette) }
    }
    Box(
        modifier = modifier
            // autoBringIntoView off: this surface owns its scroll -- see FileBrowserRevealAction.
            .mimirFocusable(authority, key, scope, autoBringIntoView = false)
            // The ring the tile is drawing, as the one state a reader of the tree can see (the reported missing root-tile ring had no way to be pinned outside the pixels). `focused` is what the cover receives, so it is true only while the ring is allowed to paint. Its own key, not Compose's Focused: see [FileBrowserRingLitSemantics].
            .semantics { fileBrowserRingLit = focused }
            // ONE tap opens this tome; a hold offers its SECONDARY (nothing, for a directory). The
            // gesture registered here used to include the double-tap variant, and removing it is the
            // point rather than a side effect: registering that callback makes Compose withhold EVERY
            // single tap for a full platform double-tap timeout (~300ms) before anything at all runs,
            // ring included. See FileBrowserController.handleTap's KDoc for the bytecode offsets, and
            // FileBrowserTouchGrammarTest for the pin that keeps it out. onLongPress does NOT pay that
            // cost -- its timeout bounds a wait, not the tap.
            .pointerInput(tile.path) {
                detectTapGestures(
                    onTap = { controller.handleTap(tile) },
                    onLongPress = { controller.handleLongPress(tile) },
                )
            },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box {
                MimirNorseBookCover(
                    // The design's tome is 1 : 1.14 (Mimir Files.dc.html:588), not the shelf's 3 : 4.
                    // Load-bearing, not cosmetic: at 833x468dp the 3:4 face leaves ~12dp of slack, so
                    // the child-count line (~17dp/row) would drop this folio from EIGHT tomes to FOUR.
                    aspectRatio = FileBrowserTomeAspectRatio,
                    focused = focused,
                    eyebrow = fileBrowserTomeEyebrow(filesResources(), tile),
                    title = tile.name,
                    platformIdValue = platformIdValue,
                    modifier = Modifier.fillMaxWidth(),
                    palette = palette,
                    // A folder's name is the player's own and arrives at any length: the cover sets it to its room, whole words only, ending above the Mannaz
                    // (alpha-cert sweep 10-09: "Audiobook" / "s" at 1.3x, the stray letter on the rune).
                    fitTitle = true,
                    // The design puts the platform glyph ON THE COVER, centred (line 106-112), not in a
                    // corner. `plate` is exactly that slot -- the cover's own "tipped-in lead-art
                    // miniature", centred in the safe band it already clears below the title
                    // (MimirNorseBook.kt:344-371). The previous pass hung the shelfmark off BottomStart
                    // instead, which read as a caption on the book rather than a device on its cover.
                    plate = glyphPlate,
                )
                // Clipped to the SAME rounded rect the cover itself clips to (mimirNorseBookCoverCorner),
                // not to this Box's square aspect-ratio bounds. MimirNorseBookCover's own `modifier`
                // param lands on its outer BoxWithConstraints, OUTSIDE its internal `.clip(shape)`
                // (MimirNorseBook.kt:179 vs 229) -- chaining the scanline draw onto that parameter, as an
                // earlier version of this tile did, drew past the leather into the four rounded corners.
                BoxWithConstraints(Modifier.matchParentSize()) {
                    val corner = mimirNorseBookCoverCorner(maxWidth.value)
                    Box(
                        Modifier
                            .matchParentSize()
                            .clip(RoundedCornerShape(corner))
                            .fileBrowserScanlines(palette),
                    )
                }
            }
            // The design's tome carries a second line under its name -- the child count (line 116,
            // `metaOf` line 552). It sits UNDER the cover here rather than on it: the cover's own leaf
            // is already spoken for by the eyebrow/rule/title lockup this module must not restyle.
            // LIVE as of policy 1 (2026-08-14): `core-files` now writes `FileItem.childCount`, so
            // this draws "146 items" on the device instead of nothing. It still draws nothing for a
            // directory the app cannot read (null, never 0) -- see [fileBrowserTileMeta]'s KDoc.
            //
            // **VERTICAL COST -- RESOLVED.** This line adds ~17dp per tome row, and the 3:4 cover left
            // only ~12dp of slack at the design's 833x468dp Odin viewport, which would have dropped this
            // folio from EIGHT tomes to FOUR. Policy 2 is the fix and it has LANDED: the additive
            // `aspectRatio` parameter exists on MimirNorseBookCover and is passed above
            // (FileBrowserTomeAspectRatio, the design's own 1 : 1.14). The earlier inability to add this parameter was resolved by the 2026-08-14 finishing pass.
            //
            // The shorter face has one consequence, closed 2026-08-14 by UI-04: the cover's glyph-plate
            // safe band used to be a fraction of HEIGHT while the title lockup it must clear is anchored
            // to WIDTH, so shortening the face squeezed the mark up into the title (~10.5dp of clearance
            // at 3:4, ~0.9dp here). See mimirNorseBookPlateSafeTopDp.
            val meta = fileBrowserTileMeta(filesResources(), tile)
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    fontSize = 10.sp,
                    // F-18 (audit 2026-10-14): the line is the grid's own FileBrowserTomeMetaHeightDp, in dp, at every font scale. Left to the ambient style it was a
                    // 24 sp line (31 dp at 1.3) under a 10 sp word, 7 dp taller than the pitch counts, and the second row's caption ended below the listing.
                    lineHeight = with(LocalDensity.current) { FileBrowserTomeMetaHeightDp.dp.toSp() },
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }
        }
    }
}

/**
 * Test seam: the folio's folder tile exactly as the grid draws it. [TomeTile] stays private (source pins in this module anchor on its declaration); a capture test that has to
 * mount one tile on its own goes through here.
 */
@Composable
internal fun TomeTileForCapture(
    tile: FileBrowserTile,
    focused: Boolean,
    authority: FocusAuthority,
    key: FocusKey,
    scope: FocusScope,
    controller: FileBrowserController,
    palette: MimirThemePalette,
    modifier: Modifier = Modifier,
) = TomeTile(tile, focused, authority, key, scope, controller, palette, modifier)

/** The cover glyph, drawn at the shelfmark's own [FileBrowserTomeGlyphTier] size with **no paint-time
 * scale at all** to avoid oversized platform labels. The previous
 * two passes scaled this plate up to the design's 34px, which is the side of a square masked ICON with no
 * text; on a plate whose width tracks its ink that reached ~60dp across and put an 18.7sp call number over
 * an 11sp title. See [FileBrowserTomeGlyphTier] for the full measurement. */
@Composable
private fun TomeGlyph(platformIdValue: String, palette: MimirThemePalette) {
    MimirPlatformShelfmark(
        platformIdValue = platformIdValue,
        tier = FileBrowserTomeGlyphTier,
        palette = palette,
    )
}

/** A file/archive tile -- flatter, squarer, no spine (task brief). The card is struck by the file's TYPE
 * ([fileBrowserFileKind] -> [fileBrowserCardStyle]): a game wears the theme's accent wash and its platform
 * plate (or the word GAME), media wears the counter-accent, saves and BIOS a single accent rule, an archive
 * the leather's top colour, a document a scanline texture. Every card carries a badge word
 * ([fileBrowserBadgeLabel]); none is filled (no filled or tinted chips) and an extractable archive's is told by the ember of its word and outline.
 *
 * **Nothing is faded, ever**. This replaces
 * policy 5's 0.55 demotion of sidecars and PICK_IMAGE's dimming of non-images: a sidecar gets a card
 * of its own kind instead of a faded game card, and a file the image picker cannot take keeps its full look,
 * is marked `disabled()` with the reason in semantics, and simply does not advertise A. The tile applies no
 * `graphicsLayer` alpha, so there is no offscreen layer to clip the focus ring either.
 *
 * The name draws in `textPrimary` and the meta line in `textSecondary`: `textMuted` is a second grey, and on
 * the tinted washes it falls under the 4.5:1 minimum at 10sp. The hue is never the only carrier of the type
 * -- the badge word is always there. */
@Composable
private fun FileTile(
    tile: FileBrowserTile,
    focused: Boolean,
    authority: FocusAuthority,
    key: FocusKey,
    scope: FocusScope,
    controller: FileBrowserController,
    palette: MimirThemePalette,
    minHeightDp: Float = FileBrowserFileTileMinHeightDp,
    modifier: Modifier = Modifier,
) {
    val base = if (focused) palette.surfaceHigh else palette.surfaceRaised
    val fileKind = fileBrowserFileKind(tile)
    val style = fileBrowserCardStyle(fileKind)
    // PICK_IMAGE : a file the pick cannot take keeps its full look and says so to a
    // screen reader. Read from the one pure gate the dispatch table and the footer legend also read
    // (fileBrowserFileChoosable), so a tile can never look choosable and swallow A. False in every other
    // mode by construction.
    val imagePickInert = controller.mode == FileBrowserMode.PICK_IMAGE &&
        !fileBrowserFileChoosable(controller.mode, tile.name)
    val notAnImage = stringResource(FileBrowserNotAnImageDescription)
    Column(
        modifier = modifier
            .defaultMinSize(minHeight = minHeightDp.dp)
            // The one ring on a tile (ring migration, 2026-09-28): a card ring, no grow. The listing's top and
            // bottom padding clear it; a lazy column does not clip its cross axis.
            .mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Card(FileBrowserTileCorner), restingOutline = true)
            .clip(RoundedCornerShape(FileBrowserTileCorner))
            .fileBrowserCardGround(base, style, palette)
            // autoBringIntoView off: this surface owns its scroll -- see FileBrowserRevealAction.
            .mimirFocusable(authority, key, scope, autoBringIntoView = false)
            .semantics { fileBrowserRingLit = focused }
            // PICK_IMAGE: this is what a screen reader gets for a file the pick cannot take -- it names the
            // reason rather than the state. Attached only when the tile really is inert, so every other
            // mode's tile keeps its exact semantics node.
            .then(
                if (imagePickInert) {
                    Modifier.semantics {
                        disabled()
                        stateDescription = notAnImage
                    }
                } else {
                    Modifier
                },
            )
            // Same grammar as TomeTile's, and the same removal -- see its comment for the latency
            // measurement. What a tap DOES differs because the dispatch table differs, not because the
            // gesture does: a file focuses and fills the inspector (CONFIRM on it is Consumed for the
            // controller too), and a hold on an ARCHIVE reaches Extract, which before this change no
            // touch-only session could reach at all.
            .pointerInput(tile.path) {
                detectTapGestures(
                    onTap = { controller.handleTap(tile) },
                    onLongPress = { controller.handleLongPress(tile) },
                )
            }
            .padding(bottom = FileBrowserTileBottomPadDp.dp),
        // The chip and the name hang from the top and the meta is pinned to the bottom , so a row's size lines sit at one height whatever the names' line counts. No weight:
        // a tile in the lazy listing has an unbounded height; the column spreads the slack between its two children over the height the grid gave it.
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(Modifier.fillMaxWidth()) {
        Box(
            // No weight here: a tile in the lazy listing has an unbounded height, and a weighted child there measures against the tile's minimum, so a name that takes a
            // second line (font 1.3) squeezed this box to nothing and the chip lost its word (emulator re-look vqa17). The chip takes the height its own text needs.
            modifier = Modifier.fillMaxWidth().padding(top = FileBrowserTileTopPadDp.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Same code-not-id gate as TomeTile's, for the same reason: a recognised-but-uncodeable id
            // makes MimirPlatformShelfmark draw nothing (MimirPlatformShelfmark.kt:89), and branching on
            // the id alone would take this arm and leave the tile's whole centre empty -- no glyph AND no
            // badge. Falling back to the GAME word is the honest render. See [fileBrowserPlatformCode].
            val platformIdValue = tile.platformIdValue?.takeIf { fileBrowserPlatformCode(it) != null }
            if (fileKind == FileBrowserFileKind.GAME && platformIdValue != null) {
                // The shelfmark's own size, no paint-time scale -- F-04. FOCUSED sits one rung below the
                // tome's PICKER, which is how the design's louder-tome relationship (34 : 30) survives
                // without an override: the ladder's own 20 : 18 is the same ratio to within 2%. See
                // [FileBrowserFileGlyphTier].
                MimirPlatformShelfmark(
                    platformIdValue = platformIdValue,
                    tier = FileBrowserFileGlyphTier,
                    palette = palette,
                )
            } else {
                FileBrowserBadge(
                    text = fileBrowserBadgeLabel(filesResources(), tile),
                    archive = tile.kind == FileBrowserTileKind.ARCHIVE,
                    palette = palette,
                )
            }
        }
        // The name breaks at its own separators and never inside a word; the meta is a whole `EXT · size` or a whole size ([FileBrowserTileText.kt]).
        FileBrowserTileName(name = fileBrowserDisplayName(tile), style = MimirTypeScale.SupportCompact, color = palette.textPrimary, modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp))
      }
      // The gap above the meta is the least there is between it and a two-line name; any slack the tile has goes above it, so the meta's bottom edge is the tile's own.
      FileBrowserTileMeta(
          candidates = fileBrowserTileMetaCandidates(filesResources(), tile),
          fontSize = FileBrowserTileMetaFontSize,
          color = palette.textSecondary,
          modifier = Modifier.fillMaxWidth().padding(start = 11.dp, top = FileBrowserTileTextGapDp.dp, end = 11.dp),
      )
    }
}

/** Test seam: the folio's detail panel as the surface draws it, with its buttons idle ([FileBrowserInspectorPanel] stays private for the source pins). */
@Composable
internal fun FileBrowserInspectorPanelForCapture(tile: FileBrowserTile, currentPath: String, rootPath: String, crumbLabels: Map<String, String>, palette: MimirThemePalette, modifier: Modifier = Modifier) =
    FileBrowserInspectorPanel(tile, currentPath, rootPath, crumbLabels, {}, {}, {}, {}, {}, null, fileBrowserChecksumRow(filesResources(), FileBrowserChecksumState.NotChecked, null), palette, modifier = modifier)

/** Test seam: the folio's file tile exactly as the grid draws it ([FileTile] stays private for the source pins). */
@Composable
internal fun FileTileForCapture(tile: FileBrowserTile, focused: Boolean, authority: FocusAuthority, key: FocusKey, scope: FocusScope, controller: FileBrowserController, palette: MimirThemePalette, minHeightDp: Float = FileBrowserFileTileMinHeightDp, modifier: Modifier = Modifier) = FileTile(tile, focused, authority, key, scope, controller, palette, minHeightDp, modifier)

/**
 * The word on a card, an outlined pill and never a filled one (no filled or tinted rounded chips). Every badge is outlined in the theme's `outline`, in
 * `textSecondary`, so it is a label and never a button; an extractable [archive] (it can be unpacked) stands out by the ember of its word and its outline, which is colour only.
 */
@Composable
internal fun FileBrowserBadge(text: String, archive: Boolean, palette: MimirThemePalette, modifier: Modifier = Modifier) {
    val pill = RoundedCornerShape(MimirSpacing.radiusPill)
    Text(
        text = text,
        style = MimirTypeScale.Pill,
        color = if (archive) palette.emberHot else palette.textSecondary,
        maxLines = 1,
        modifier = modifier
            .clip(pill)
            .border(1.dp, if (archive) palette.emberHot else palette.outline, pill)
            .padding(horizontal = 10.dp, vertical = FileBrowserBadgeVerticalPadDp.dp),
    )
}

/** File tiles show the name without its extension (matches the design's own `tile()` helper); tomes
 * (directories) keep their full name -- a directory's "extension" is not one.
 *
 * `internal` as of F-08 so the search screen's result rows name a hit exactly as the folio names the
 * tile it becomes after a reveal. Widened rather than restated: two naming rules would be two chances
 * for the same file to read differently on two screens. */
internal fun fileBrowserDisplayName(tile: FileBrowserTile): String =
    if (tile.kind == FileBrowserTileKind.DIRECTORY) {
        tile.name
    } else {
        tile.name.substringBeforeLast('.').ifBlank { tile.name }
    }

/**
 * Claim every touch inside this full-screen modal, so none of them reaches the destination underneath.
 *
 * [FileBrowserSurface] draws over the whole screen opaquely, but `background()` is a DRAW modifier -- it
 * registers no pointer-input node. Compose hit-tests the app root's children topmost-first and stops at
 * the first sibling that produces a hit, so with no pointer node here a tap anywhere this surface does
 * not itself handle -- the header outside a crumb, the inspector outside its three buttons, the footer
 * legend, the outer gutters -- missed this overlay entirely and hit `MimirDestinationHost`'s Settings
 * screen underneath. Those rows run their `onActivate()` unconditionally (`SettingsComponents.kt:143`,
 * `SettingsHome.kt:425` -- only the `requestFocus` beside it is scope-guarded), so an invisible row the
 * player could not see was being activated behind an opaque modal: rescans, source removals, pickers.
 *
 * When such an activation pushed a `FocusScope` (theme / accent / backdrop / focus-ring, every one of
 * them ranked BELOW `FOLDER_BROWSER` in `resolveMimirOverlayStack`, so this browser stayed top of the
 * overlay stack and kept being asked), this browser lost the active scope while remaining mounted -- and
 * that killed BOTH input paths at once, because every affordance here is behind
 * [FileBrowserController]'s scope guard and every controller action was being absorbed by the shell's
 * `FOLDER_BROWSER` branch. Unrecoverable without killing the process. See `app/`'s
 * `FileBrowserOverlayAbsorbTest` for the containment half of the fix; this is the cause half.
 *
 * **Deliberately NON-CONSUMING.** Ending the fall-through is a HIT-TEST property, not a consumption one:
 * merely owning a pointer-input node over these bounds ends the sibling walk before the destination is
 * reached. A consuming absorber (`detectTapGestures {}` / `clickable {}`, as `MimirSelectOverlay.kt:188`
 * uses for a small dialog scrim) would additionally take the DOWN out from under this surface's own
 * `LazyColumn` drag-to-scroll -- a dialog scrim has no scrolling descendants, a full-screen root does.
 * This claims the touches the surface does not want; it never eats its own children's.
 *
 * Chained BEFORE `background()` for readability only -- pointer bounds come from the layout node, so
 * modifier order does not change what this covers.
 */
private fun Modifier.fileBrowserModalTouchGuard(): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial)
            }
        }
    },
)

/**
 * The gap above every screen in the content row that replaces the folio (and has no inspector beside it): [FileBrowserContentTopPaddingDp], what the row itself used to give all of them. The listing
 * does not take it because its ring room ([MimirFocusTileRingReach], inside its bounds) is the gap above its first heading already; see the row's own comment.
 */
private fun Modifier.fileBrowserScreenTopGap(): Modifier = this.padding(top = FileBrowserContentTopPaddingDp.dp)

/**
 * The inspector's gap: the listing's own ring room ([MimirFocusTileRingReach]), so its title starts exactly where the listing's first heading does ("Folders", "Files"). It sits beside the
 * folio and nothing else (the screens that replace the folio take [fileBrowserScreenTopGap] and have no inspector), so the two share one number and cannot drift.
 */
private fun Modifier.fileBrowserInspectorTopGap(): Modifier = this.padding(top = MimirFocusTileRingReach)

/** A cheap, cached CRT-scanline wash -- thin repeated horizontal lines at low alpha, matching the
 * design's `--mm-scanlines` texture token (no Compose/palette color equivalent exists for a repeating
 * texture, so this hand-draws it; the neutral-shading precedent is
 * [Color.Black] here is the same neutral-shading convention `core-ui`'s own `MimirNorseBook.kt` /
 * `MediaArtFrame.kt` already use for shadow washes, not a themed color). */
private fun Modifier.fileBrowserScanlines(palette: MimirThemePalette): Modifier = this.then(
    Modifier.drawWithCache {
        val lineColor = Color.Black.copy(alpha = 0.16f)
        val step = 3.dp.toPx()
        onDrawWithContent {
            drawContent()
            var y = 0f
            while (y < size.height) {
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += step
            }
        }
    },
)

// ───────────────────────────────────────────────────────────────────── Inspector ──

@Composable
private fun FileBrowserInspectorPanel(
    tile: FileBrowserTile?,
    currentPath: String,
    rootPath: String,
    crumbLabels: Map<String, String>,
    onOpen: () -> Unit,
    onExtract: () -> Unit,
    onPlace: () -> Unit,
    onVerify: () -> Unit,
    onCopy: (String) -> Unit,
    copiedDigest: String?,
    checksum: FileBrowserChecksumRow,
    palette: MimirThemePalette,
    extractLive: Boolean = true,
    transferAvailable: Boolean = false,
    /**
     * [tile] is the open folder itself, not a focused tile: its Where is its parent, and it offers
     * no buttons -- A there confirms the focused ROW (the commit or back-to-origin row), so an Open here would lie.
     */
    describesFolder: Boolean = false,
    /** The image picker's reason this file cannot be chosen ("Not an image"), or null. */
    uploadNote: String? = null,
    modifier: Modifier = Modifier,
) {
    if (tile == null) {
        Box(modifier)
        return
    }
    val res = filesResources()
    // The fields hold words, so the player's language is a key: a language change must not keep the old language's panel.
    val locale = res.filesLocale()
    val fields = remember(tile, currentPath, rootPath, crumbLabels, describesFolder, uploadNote, locale) {
        val where = if (describesFolder) File(currentPath).parent ?: currentPath else currentPath
        fileBrowserInspectorFields(res, tile, where, rootPath, crumbLabels, uploadNote = uploadNote)
    }

    Column(
        modifier = modifier.padding(bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = fields.name,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 19.sp,
            color = palette.textPrimary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(fields.tag, style = MimirTypeScale.Pill, color = palette.textSecondary, maxLines = 1)
            if (fields.meta.isNotBlank()) {
                Text(
                    fields.meta,
                    fontSize = 11.5.sp,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FileBrowserChip(fields.chipLabel, fields.chipTone, palette)
        }
        Column {
            InspectorFieldRow(res.getString(R.string.files_inspector_where), fields.where, palette)
            InspectorFieldRow(res.getString(R.string.files_inspector_modified), fields.modified, palette)
            InspectorFieldRow(res.getString(R.string.files_inspector_launches), fields.launches, palette)
            fields.uploadNote?.let { InspectorFieldRow(res.getString(R.string.files_inspector_upload), it, palette) }
            // F-05: no Checksum row on a tome. A directory has no digest, so the button's only possible
            // outcome there was a failure -- see [FileBrowserInspectorFields.checksumable].
            if (fields.checksumable) {
                InspectorChecksumRow(checksum, onVerify, onCopy, copiedDigest, palette)
            }
        }
        if (!describesFolder) Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // This slice: Open and Extract here only (task brief). The row itself is a generic
            // wrapping button row, not a fixed two-slot layout, so Move/Delete have room to land here
            // in the queue/Undo slice without a restructure.
            if (fields.openable) {
                FileBrowserButton(res.getString(R.string.files_inspector_open), FileBrowserButtonVariant.PRIMARY, palette, onOpen)
            }
            // F-06 item 6: **hidden, not disabled.** A greyed control still occupies its slot and still
            // invites the press it will refuse, and this panel has no disabled treatment to reach for --
            // every other absence here is an absence (no Checksum row on a tome, no Copy without a
            // digest, no Open on a plain file). Removing it also keeps `Open` in the position the player
            // has been pressing, which a greyed neighbour would have preserved and a *reordered* row
            // would not. The archive is not made unextractable by this: the chooser the pick came from is
            // one B press away, with "Extract here" as its first row.
            if (fields.extractable && extractLive) {
                FileBrowserButton(res.getString(R.string.files_inspector_extract), FileBrowserButtonVariant.PRIMARY, palette, onExtract)
            }
            // G2. `tile.kind == FILE`, not a `fields.*` flag -- FileBrowserInspector.kt (another lane's
            // file this slice) has no `placeable` field, and `tile` is already in scope non-null here.
            // Same disjointness `fields.extractable` relies on (a tile is DIRECTORY xor ARCHIVE xor FILE,
            // FileBrowserInspector.kt's own partition), so this can never draw beside Extract's button.
            //
            // Gated on `extractLive`, not a second "transferLive" parameter: SECONDARY has exactly one
            // live/dead state for the whole duration of any destination pick, extract's or a transfer's
            // (`fileBrowserSecondaryBindingLive` takes the pick phase, not a verb), so the two buttons
            // share the identical signal. Kept under the name `extractLive` rather than something neutral
            // because `FileBrowserExtractSurfaceTest.kt` (outside this lane's writes) pins that parameter
            // name and its call-site text as exact source text.
            //
            // "Place…", never "Move…" or "Copy…" -- the same one-word-two-verbs policy the footer legend
            // and the chooser eyebrow both carry (`fileBrowserFooterHints`' `X PLACE` branch,
            // `FileBrowserTransferEyebrow`): this button opens a chooser offering BOTH verbs, so naming
            // either one here would be wrong half the time.
            if (tile.kind == FileBrowserTileKind.FILE && transferAvailable && extractLive) {
                FileBrowserButton(res.getString(R.string.files_inspector_place), FileBrowserButtonVariant.PRIMARY, palette, onPlace)
            }
        }
    }
}

@Composable
private fun InspectorFieldRow(label: String, value: String, palette: MimirThemePalette) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 29.dp)
            .drawWithCache {
                onDrawBehind {
                    drawLine(palette.outline, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                }
            }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            fontSize = 10.5.sp,
            color = palette.textMuted,
            maxLines = 1,
            modifier = Modifier.width(70.dp),
        )
        // The value wraps at its words and is never cut inside one; a deep path is shortened in the middle and keeps its last folder ([FileBrowserInspectorText.kt]).
        FileBrowserInspectorValue(text = value, fontSize = 11.5.sp, color = palette.textPrimary, modifier = Modifier.weight(1f))
    }
}

/**
 * The Checksum row displays the current digest beneath its status (F-05).
 *
 * **Two lines, and the second one is the fix.** The status line keeps the design's shape
 * (`Mimir Files.dc.html:198-200`: a 70px label, a value, a small outlined button) and carries the short
 * word — `Not checked` / `Reading…` / the algorithm / why it failed. The DIGEST goes underneath, across
 * the panel's full width, in [MimirFonts.Mono].
 *
 * That split is arithmetic, not taste. A SHA-1 is 40 characters; Plex Mono advances exactly 0.600 em, so
 * at [FileBrowserChecksumDigestSp] the digest is 240 dp. The inspector is
 * [FileBrowserInspectorWidthDp] = 270 dp wide, of which the label alone takes
 * [FileBrowserInspectorLabelWidthDp] = 70 dp before the button is drawn at all. Beside the label it
 * cannot fit, and the row it replaced said so with `maxLines = 1` + [TextOverflow.Ellipsis] — which is the
 * truncating Toast the regression report described, moved inside the panel. On its own line it fits whole at
 * `fontScale = 1` and wraps rather than truncates above that, which is why `maxLines` is 3 and not 1.
 *
 * **The button is live in every state**, its label tracking the design's own
 * `Verify` / `Reading` / `Again`. Re-running a hash is always meaningful and always safe, so there is no
 * state here where a control is drawn that cannot be pressed — this tree's standing objection to
 * placeholder chrome ([FileBrowserPlaceholderChrome], [fileBrowserFooterHints]).
 *
 * ## V-2 — the digest is available to copy
 *
 * **Copy sits beside Verify, and only once there is a digest** ([fileBrowserChecksumCopyable]). The two
 * are anti-correlated by construction, which is what lets one 270 dp line hold both: a digest exists only
 * in [FileBrowserChecksumState.Checked], and that is precisely the state whose `status` word is the short
 * algorithm name rather than a sentence — so `Copy` appears exactly when `Not checked` /
 * `Verify did not start — try again` has been replaced by `SHA-1`. [fileBrowserChecksumRowFits] states the
 * budget as arithmetic instead of confidence.
 *
 * **Verify does not move.** It keeps the row's trailing slot it has always had; Copy is inserted before
 * it. That is deliberate given this control's history — the earlier P0 regression was a *mis-touch beside this very
 * button*, and re-teaching its position while making it bigger would be the one change able to make that
 * report worse.
 *
 * **The prompt is this row, not a dialog.** It presents readable, copyable information
 * — the status word plus the whole digest plus a copy control — and not as a modal, because a modal here
 * would need a [FocusScope] pushed and a dispatch arm to answer B, both of which live in
 * `FileBrowserController` / `FileBrowserDispatch`. A modal without them is exactly
 * the absorbing branch with no way out that this browser already shipped once (see
 * [fileBrowserModalTouchGuard]). A literal dialog would therefore require a separate lifecycle and input contract.
 */
@Composable
private fun InspectorChecksumRow(
    row: FileBrowserChecksumRow,
    onVerify: () -> Unit,
    onCopy: (String) -> Unit,
    copiedDigest: String?,
    palette: MimirThemePalette,
) {
    val statusColor = when (row.tone) {
        // IDLE is textPrimary and NOT muted, deliberately -- it is the colour the row already used, and
        // keeping the unwired path LITERALLY identical rather than merely equivalent is this tree's own
        // discipline for an additive change (`mimirNorseBookPlateSafeTopDp` states it in those words).
        // It also matches every sibling row: InspectorFieldRow draws "Unknown" at textPrimary too, so
        // muting only this one absence would make it read as less present than the others.
        // RESULT joins it because a digest is likewise a statement about the file.
        FileBrowserChecksumTone.IDLE, FileBrowserChecksumTone.RESULT -> palette.textPrimary
        // Transient chrome about Mimir rather than about the file: it recedes.
        FileBrowserChecksumTone.PENDING -> palette.textMuted
        // The one palette token that means "this went wrong" (MimirThemePalette.danger). Not a literal.
        FileBrowserChecksumTone.FAILURE -> palette.danger
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawWithCache {
                onDrawBehind {
                    drawLine(palette.outline, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                }
            }
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            // The same gap [fileBrowserChecksumRowFits] budgets against -- named there, spelled once here.
            horizontalArrangement = Arrangement.spacedBy(FileBrowserChecksumRowGapDp.dp),
        ) {
            Text(
                text = filesResources().getString(R.string.files_inspector_checksum),
                fontSize = 10.5.sp,
                color = palette.textMuted,
                maxLines = 1,
                modifier = Modifier.width(FileBrowserInspectorLabelWidthDp.dp),
            )
            Text(
                text = row.status,
                fontSize = 11.sp,
                color = statusColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // V-2. Drawn only when there is a digest -- see [fileBrowserChecksumCopyable] for why a copy
            // control over "Not checked" would be the dead-control class this module refuses, and for why
            // that same gate keeps this button and the refused-press notice off the screen together.
            if (fileBrowserChecksumCopyable(row)) {
                FileBrowserButton(
                    fileBrowserChecksumCopyLabel(filesResources(), row, copiedDigest),
                    FileBrowserButtonVariant.OUTLINED,
                    palette,
                    { onCopy(fileBrowserChecksumCopyPayload(row)) },
                )
            }
            // V-3: the row no longer overrides this button down to 27dp. It carries the same
            // MimirSpacing.minTarget floor as every other control in the app -- and it stays in the
            // trailing slot it has always occupied.
            FileBrowserButton(
                row.verifyLabel,
                FileBrowserButtonVariant.OUTLINED,
                palette,
                onVerify,
            )
        }
        if (row.digest.isNotBlank()) {
            Text(
                text = row.digest,
                fontFamily = MimirFonts.Mono,
                fontSize = FileBrowserChecksumDigestSp.sp,
                lineHeight = (FileBrowserChecksumDigestSp * 1.35f).sp,
                color = palette.textPrimary,
                // NOT `maxLines = 1` and NOT ellipsised: truncating the digest here would reproduce
                // exactly the defect this row exists to fix, one layer further in.
                maxLines = 3,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun FileBrowserChip(label: String, tone: FileBrowserChipTone, palette: MimirThemePalette) {
    // An outlined pill, never a filled or tinted one , like the file tile's badge: a recognised file's is told by the green of its word and outline, an archive's by the ember of
    // both, and the rest by the muted word in the plain outline.
    val (text, border) = when (tone) {
        FileBrowserChipTone.RECOGNISED -> palette.success to palette.success.copy(alpha = 0.4f)
        FileBrowserChipTone.PENDING -> palette.emberHot to palette.emberHot
        FileBrowserChipTone.NEUTRAL, FileBrowserChipTone.MUTED -> palette.textMuted to palette.outline
    }
    Text(
        text = label,
        style = MimirTypeScale.Pill,
        color = text,
        maxLines = 1,
        modifier = Modifier
            .defaultMinSize(minHeight = 30.dp)
            .wrapContentHeight(Alignment.CenterVertically)
            .drawWithCache {
                onDrawBehind {
                    drawRoundRect(
                        color = border,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension / 2f),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            }
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

internal enum class FileBrowserButtonVariant { PRIMARY, TONAL, OUTLINED }

/**
 * Every pressable word in the inspector shares the minimum target (V-3).
 *
 * **The target is [MimirSpacing.minTarget], as a hard floor in both axes, and the size override is gone.**
 * This was `defaultMinSize(minHeight = 32.dp)`, which the Checksum row then overrode down to 27 dp — under
 * the token this app defines as *"minimum touch/focus target on a handheld"* and applies as
 * `heightIn(min = …)` at twenty-odd call sites across settings, library, the home overlays and the
 * keyboard. `heightIn`/`widthIn` rather than `defaultMinSize` for the same reason those call sites use it:
 * `defaultMinSize` yields to an incoming constraint, and a touch target that a parent can silently shrink
 * is not a floor.
 *
 * **The `minHeight` parameter was deleted rather than re-defaulted.** It existed for exactly one caller —
 * the one that made the repeatedly reported button the smallest control on the screen —
 * and while it exists any future row can do the same thing again. There is no longer a way to ask for a
 * button below target.
 *
 * **This also grows Open and Extract here, beyond the Verify fix.** Disclosed rather than silent: they
 * are the same private component, they sit directly beneath the difficult-to-hit control, and leaving
 * them at 32 dp would fix the reported control while keeping two under-target buttons a thumb's width
 * away. The cost is vertical — the inspector is a fixed [FileBrowserInspectorWidthDp] column with no
 * scroll, so its rows must still fit the viewport; **hardware validation is still needed
 * to establish viewport fit.**
 *
 * The painted pill grows with the target rather than hiding inside a larger invisible one, which is what
 * every other `minTarget` call site in this app does: a control that is easier to hit should look it.
 */
@Composable
internal fun FileBrowserButton(
    label: String,
    variant: FileBrowserButtonVariant,
    palette: MimirThemePalette,
    onClick: () -> Unit,
) {
    // Buttons use the house rounded rectangle rather than a capsule: radiusCard is 14dp,
    // while radiusPill=999dp produces a stadium. The etched-chip plate uses its shared corner.
    // Update (no filled blob; sweep 10-09: Open was solid gold): the button is the etched chip's plate, cut to that rounded rectangle ([mimirEtchedChipSurface]'s
    // `corner`), so both treatments align: PRIMARY is the primary chip (the same dark ground, rim and word in the accent), TONAL and OUTLINED the unselected one. This panel is touch-only, so
    // there is no focus ring to place.
    val primary = variant == FileBrowserButtonVariant.PRIMARY
    Box(
        modifier = Modifier
            // V-3. A hard floor, both axes, from the app's own token -- see this function's KDoc.
            .heightIn(min = MimirSpacing.minTarget)
            .widthIn(min = MimirSpacing.minTarget)
            .mimirEtchedChipSurface(palette, primary = primary, corner = MimirSpacing.radiusCard)
            .pointerInput(label) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MimirEtchedChipLabelStyle,
            color = palette.etchedChipInk(primary = primary),
            maxLines = 1,
        )
    }
}

// ───────────────────────────────────────────────────────────────────── Footer ──

@Composable
private fun FileBrowserFooterLegend(hints: List<Pair<String, String>>, palette: MimirThemePalette) {
    val labels = LocalMimirControllerHintLabels.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .drawWithCache {
                onDrawBehind {
                    drawLine(palette.outline, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                }
            }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Hint transitions: hints must not cut when focus moves. Only the words dissolve; the band and its rule stay.
        // Fit from the first frame: the swap spans the band, so a longer word is never cut mid-change.
        // the whole-line fade read as no transition. Each hint is now its own piece (the Tome footer's swap):
        // hints both lines share glide to their new place, the others fade out and in. Left-aligned, 18dp between hints.
        // A piece's words are its token and verb ("B BACK"), split again at the first space to draw the keycap and the verb.
        // Read from the words, not from [hints]: a leaving piece belongs to the previous line and still has to draw while it
        // fades. Tokens never contain a space (A, B, X, Y, START).
        MimirSegmentedHintSwap(
            line = hints.joinToString(MimirHintLineSeparator) { (token, verb) -> "$token $verb" },
            modifier = Modifier.fillMaxWidth(),
            centred = false,
            separator = { Spacer(Modifier.width(18.dp)) },
        ) { words ->
            val token = words.substringBefore(' ')
            val verb = words.substringAfter(' ', missingDelimiterValue = "")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // The footer's one keycap (footer keycaps are not tinted): the etched plate, shared with every other footer.
                labels.labelFor(token).takeIf(String::isNotBlank)?.let { MimirFooterKeycap(key = it, palette = palette) }
                Text(
                    text = verb,
                    fontSize = 11.sp,
                    color = palette.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}
