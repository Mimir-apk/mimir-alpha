package com.gruning.mimir.rewrite.feature.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gruning.mimir.rewrite.core.files.DiscSetMoveOption
import com.gruning.mimir.rewrite.core.files.DiscSetWarning
import com.gruning.mimir.rewrite.core.files.ExtractDestinationOptions
import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.files.FileOperationQueue
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueEntry
import com.gruning.mimir.rewrite.core.files.FileSearchFilter
import com.gruning.mimir.rewrite.core.files.PlatformDestination
import com.gruning.mimir.rewrite.core.files.RenameNamePolicy
import com.gruning.mimir.rewrite.core.files.RenameProblem
import com.gruning.mimir.rewrite.core.files.discSetMoveOptions
import com.gruning.mimir.rewrite.core.files.discSetMovePaths
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusBinding
import com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusScope
import com.gruning.mimir.rewrite.core.input.dispatchMimirKeyboardInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.gruning.mimir.rewrite.core.model.jobs.FileQueueState
import com.gruning.mimir.rewrite.core.model.jobs.StagedDeletion

/**
 * The browser's two operating modes. The design adoption spec's own screen table (§6:
 * Browse/Inspector/Selection/Search/Queue/Storage/Permissions/Move/More) has no "choose this folder"
 * affordance anywhere -- it is a general-purpose file manager. But this module's ONE production caller
 * (`MimirSettingsCoordinator.launchSourcePicker`'s "add ROMs source" flow, via
 * `app/src/main/kotlin/.../FileBrowserStore.kt`'s `open()`) is a folder PICKER, not a manager. The
 * design-adoption rebuild (2026-08-14) dropped the pinned commit row wholesale because the design never
 * has one, which made that caller's `onChoose` structurally unreachable -- a user-visible
 * regression (a player could browse to a folder but never say "use this one"), caught and fixed the
 * same day. [FileBrowserMode] makes the distinction the design itself doesn't need to make explicit,
 * so the fix is additive rather than a silent reversal of the rebuild.
 *
 * **Defaults to [PICK], not [MANAGE] -- a deliberate fallback, not the first choice.** The intended fix
 * wires this explicitly at both call sites (`FileBrowserController`'s own constructor AND
 * `FileBrowserStore.open()`'s call into it, plus `MimirSettingsCoordinator.kt:203`'s call into THAT).
 * Existing callers which omit [mode] therefore keep the pinned commit row and the add-source flow.
 * Explicit mode wiring remains owed. [PICK] is also
 * the safer polarity for the default itself: a future MANAGE caller that forgets the parameter gets one
 * visible extra row (annoying, easy to spot); a MANAGE default would let a forgotten PICK caller
 * silently reproduce the exact regression this class exists to fix.
 */
enum class FileBrowserMode {
    /** Opened from add-games (`MimirSettingsCoordinator`'s only current caller). Shows a pinned "Use
     * this folder" commit affordance -- policy 6: registered VIRTUALLY alongside the tile grid's
     * real focus slots, a genuine participant in the D-pad focus graph, never chrome outside it -- that
     * invokes `onChoose(currentPath)`. Shape matches the design's own Move dialog, whose `moveRows`
     * pins an identical "Use this folder" row ahead of the subdirectory list (`Mimir Files.dc.html`
     * lines 728-733: `tagLabel: 'USE'`, `onTap: () => this.commitMove()`). */
    PICK,

    /** The plain file manager -- what the design actually specifies, and this module's future Utility
     * entry point (not wired anywhere in this tree yet). No commit affordance; `onChoose` is
     * unreachable, matching the design's own intent for this mode specifically (not a regression here
     * -- MANAGE never promised a commit row). */
    MANAGE,

    /**
     * Opened from the Customize-media picker's **Upload** action (`MimirAppCoordinator.startImageImport`)
     * to keep image browsing within Mimir. Browses exactly like [PICK]: directories navigate, and nothing
     * destructive is reachable -- every capability gate in this module tests `mode == MANAGE`
     * (`deleteAvailable`, `transferChoiceAvailable`, `fileBrowserQueueAvailable`,
     * `fileBrowserSelectionEnter`, `fileBrowserActionsAvailable`'s `manageMode`, and
     * `FileBrowserStore`'s one `ManagedFileOperations` construction), so this mode fails closed on all
     * of them with no second test anywhere. What differs is WHAT is committed: a FILE, not a folder.
     * There is no pinned "Use this folder" row (`commitPurposeForCurrentMode` answers null here), and
     * CONFIRM -- or one tap -- on a FILE tile whose extension is in [FileBrowserImageExtensions] fires
     * `onChoose(filePath)` with the tile's own path, through the same close-then-report
     * [FileBrowserCommand.CommitSourceFile] arm R-21 built for PICK. Every other file draws dimmed and is
     * inert on CONFIRM, and its semantics say why ([FileBrowserNotAnImageDescription]). The gate is
     * [fileBrowserFileChoosable], one pure function read by the dispatch table, the footer legend and
     * the tile's own dimming, so the drawn state, the advertised verb and the press cannot disagree.
     */
    PICK_IMAGE,

    /**
     * Opened from Settings › Music › Music folder when Mimir has all-files
     * access, on the standing stance that Mimir owns its surfaces. Browses exactly like [PICK] and shows
     * the same pinned "Use this folder" affordance, which reports the open directory through
     * `onChoose(currentPath)` -- the one thing a music folder pick needs. What it does NOT inherit from
     * PICK is R-21's single-file commit: its purpose is [FileBrowserCommitPurpose.CHOOSE_MUSIC_FOLDER],
     * not `CHOOSE_SOURCE`, so `sourcePickAvailable` stays false and a focused mp3 is never an "ADD GAME".
     * Every destructive gate tests `mode == MANAGE`, so this mode fails closed on all of them like PICK.
     */
    PICK_MUSIC_FOLDER,
}

/**
 * Direction + identity for the destination-owned glide that acknowledges a directory change (F-03).
 *
 * **A restatement of `GamelistPlatformSwitchMotion`** (`feature-library`'s `LibraryViewModel.kt:69-77`),
 * whose applicator `GamelistDestinationPageTransition` (`LibraryScreen.kt:3844-3885`) is this tree's own
 * answer to exactly this problem: *"One destination-owned page glide... The outer book shell and Hero
 * veil stay mounted, no predecessor game tree remains composed, and focus/semantics belong to the
 * destination from its first frame."* Copied rather than imported for the same reason the settlement
 * model is ([FileBrowserRevealAction]): that module is deliberately not a dependency of this one, and
 * the type is `internal` to it.
 *
 * **[direction] is why this is not a fade.** Entering a folder and backing out are opposite movements,
 * and one symmetric fade reads as neither. `+1` glides the incoming listing in from the trailing side
 * (descending, [FileBrowserGlideEnter]); `-1` from the leading side (returning, [FileBrowserGlideReturn]).
 * The reference constrains the same field to the same two values, and so does this.
 *
 * **[sequence] is the identity, not [path].** Without it, a refresh that reloads the SAME directory
 * ([FileBrowserController.refresh], after an extraction) would be indistinguishable from a navigation to
 * it, and the listing would glide for a change of contents rather than a change of state. Only
 * [FileBrowserCommand.Enter] and [FileBrowserCommand.Up] bump it, so only they animate.
 *
 * **[reveal] (R6-P2-02).** A reveal is an Enter whose row-level settle travels onto the found file; the
 * glide is unchanged. `false` for every navigation except [FileBrowserController.revealPath]'s.
 */
internal data class FileBrowserNavigationMotion(
    val path: String,
    val direction: Int,
    val sequence: Long,
    val reveal: Boolean = false,
    /**
     * The listing reloaded where it stands (the commit row appearing or going, [loadAfterPickChange]).
     * It dips in place: nothing departs and nothing travels, while [direction] keeps its ±1 for the ring gate.
     */
    val inPlace: Boolean = false,
) {
    init {
        require(direction == FileBrowserGlideEnter || direction == FileBrowserGlideReturn) {
            "direction must be +1 (enter) or -1 (return), was $direction"
        }
    }
}

/** How many folder tiles [FileBrowserController] keeps to describe the open folder. */
internal const val FileBrowserKnownDirectoryTilesCap = 512

/** Descending into a folder: the incoming listing arrives from the trailing side. */
internal const val FileBrowserGlideEnter = 1

/** Backing out to the parent: the incoming listing arrives from the leading side. */
internal const val FileBrowserGlideReturn = -1

/**
 * Whether the directory glide applies to the listing currently rendered -- the mirror of
 * `libraryGamelistPageMotionApplies` (`LibraryScreen.kt:172-176`).
 *
 * The reference also gates on `gamesLoaded`, because its rows arrive asynchronously from Room and a
 * glide begun before them would animate an empty page. This browser has no such gate and needs none:
 * [FileBrowserController.load] is synchronous, so [FileBrowserController.partition] is already the new
 * directory's by the time [currentPath] is. Stated rather than silently dropped.
 */
internal fun fileBrowserNavigationGlideApplies(
    currentPath: String,
    motion: FileBrowserNavigationMotion?,
): Boolean = motion?.path == currentPath

/**
 * The file browser's state holder -- most of this module's public surface (the others are
 * [FileBrowserSurface] and [FileBrowserMode]). Everything else in this module is `internal`, so a
 * caller cannot reach past this seam and grow a second opinion about how the browser behaves.
 *
 * The flat single-column listing
 * (slices 5-6) was replaced by a tome/file folio + standing inspector. The two-symbol public API SHAPE
 * grew to three, disclosed rather than silently: this class + [FileBrowserSurface] + [FileBrowserMode]
 * (the last needed only because [FileBrowserStore] -- outside this module -- must name a mode when it
 * constructs this class). The state this class carries also grew -- [partition] replaces `rows`, and
 * the constructor grew [mode] plus two optional callbacks ([classifyPlatform], [onVerifyChecksum]).
 *
 * **Deliberately ignorant of the filesystem beyond [FileItem].** [listDirectory] is a plain function from
 * an absolute path to that directory's [FileItem]s; this class never calls `LocalFileRepository` itself
 * (unchanged from slice 5-6 -- see `FileBrowserStore.kt` for the real bridge). Production additionally
 * supplies [listDirectoryAsync] + [loadScope], which is the same listing allowed to suspend, so the read
 * stops happening on the thread the player is pressing buttons on.
 *
 * **Owns its [FocusScope]'s entire lifecycle**, unchanged from slice 5-6: [open] pushes [scope]; [close]
 * clears every virtual registration and pops it back off, idempotently.
 *
 * **Closed 2026-08-14** (were on this list; kept named so the history is readable):
 *  - ~~No per-directory focus memory~~ -- [focusByDirectory]. Backing out of a directory now restores the
 *    tile the player left, instead of dumping them at index 0 on every navigation step.
 *  - ~~No PAGE_LEFT/PAGE_RIGHT jump support~~ -- [FileBrowserCommand.Page] / [fileBrowserPageTarget],
 *    with the stride measured in dp against the adoption spec's 833x468dp viewport
 *    ([fileBrowserPageStride]) rather than fixed at a row count, so a jump through the taller tome grid
 *    covers one screen there too instead of ~1.4 of them.
 *
 * **Owed, disclosed rather than silent** (the `onChoose` regression [FileBrowserMode]'s KDoc describes
 * is fixed as of this class, not on this list):
 *  - **Virtual registration stays unbounded -- now a decision, not an omission.** See
 *    [FileBrowserFocusTracker]'s KDoc for why a radius window is wrong for this surface specifically and
 *    what the size actually costs.
 *  - **The inspector's Verify button has no controller path.** `FileBrowserSurface` wires it to
 *    [verifyFocusedChecksum] through a tap gesture only, and no [ControllerAction] maps to it, so it is
 *    touch-only. TERTIARY is free and would be the natural binding, but advertising it needs a footer
 *    hint (`fileBrowserFooterHints`) and a silent binding would be worse than none -- see that function's
 *    own "a fixed legend would lie". Adding the binding also requires the matching footer hint.
 *  - **Extraction and checksum verification are reported, not performed.** [onExtract] and
 *    [onVerifyChecksum] receive the affected tile's path; doing the actual work is the mounting slice's
 *    job (matches [onExtract]'s slice 5-6 precedent exactly).
 *  - **Platform recognition defaults to `{ null }`.** No classifier is wired into this tree yet (adoption
 *    spec §9); [classifyPlatform] exists so the whole glyph/chip/Launches pipeline is provably wired
 *    once one lands, exercised today only by this module's own tests injecting a fake.
 *
 * @param rootPath the directory this browser opens into and cannot navigate above.
 * @param mode [FileBrowserMode.PICK], [FileBrowserMode.MANAGE] or [FileBrowserMode.PICK_IMAGE] -- see that
 *   enum's KDoc, in particular
 *   "Defaults to PICK, not MANAGE" for why this parameter has a default at all and why it is that one
 *   specifically; explicit wiring at every call site remains owed.
 * @param listDirectory synchronous listing for an absolute path -- see the class KDoc. Still the only
 *   listing path for the first load and for every caller that supplies no [listDirectoryAsync]/[loadScope]
 *   pair, which is every test in this module.
 * @param onChoose fires with the current directory's path when the player commits it, or with a FILE's
 *   own path when a tile commits itself (R-21's single-game add in [FileBrowserMode.PICK]; the chosen
 *   image in [FileBrowserMode.PICK_IMAGE]). Reachable ONLY in those two modes; in
 *   [FileBrowserMode.MANAGE] this is genuinely unreachable, matching that mode's own design intent.
 * @param onDismiss the browser was left without choosing anything (CANCEL/SYSTEM_BACK at the root).
 * @param onExtract the SECONDARY gesture / "Extract here" button fired on an extractable tile; receives
 *   that tile's absolute path.
 * @param onVerifyChecksum the inspector's "Verify" button fired on the focused tile; receives its
 *   absolute path. No-op by default -- checksum computation is not wired this slice (adoption spec §9).
 * @param classifyPlatform recognises a [FileItem] -- a platform, a game whose platform is unsettled, a BIOS
 *   payload -- as a [FileBrowserRecognition]. Defaults to [FileBrowserRecognition.None] for every row --
 *   see the class KDoc's "owed" list.
 * @param scope this instance's [FocusScope]. Defaults to a shared constant; a caller mounting more than
 *   one browser at once must supply distinct scopes.
 */
class FileBrowserController(
    private val authority: FocusAuthority,
    internal val rootPath: String,
    internal val mode: FileBrowserMode = FileBrowserMode.PICK,
    private val listDirectory: (String) -> List<FileItem>,
    private val onChoose: (String) -> Unit,
    private val onDismiss: () -> Unit,
    private val onExtract: (String) -> Unit = {},
    private val onVerifyChecksum: (String) -> Unit = {},
    /**
     * F-01: the SELECT gesture fired on a focused tile; receives that tile's absolute path. Wired in
     * production to `FileBrowserStore::deleteInManager`, which stages the delete and leaves the queue
     * holding an Undo -- nothing is destroyed by the press itself.
     *
     * **Nullable, defaulting null, and that is the capability gate.** Unlike [onExtract] (a no-op lambda
     * default), the absence of a delete sink has to be *observable* so [deleteAvailable] can be false and
     * the whole binding can vanish -- dispatch, footer hint and all. A `{}` default would leave a MANAGE
     * browser advertising "SELECT DELETE" over a button that silently did nothing, which is the exact
     * failure [searchAvailable] exists to prevent, in the one place where the player's files are the
     * subject.
     */
    private val onDelete: ((String) -> Unit)? = null,
    /**
     * F-06: resolve the Extract destinations for an archive — `FileBrowserStore::extractOptions` in
     * production, which reads the real library's ROM folders and the archive's own volume.
     *
     * **A constructor parameter, not a surface setter, matching the existing surface contract.**
     * `FileBrowserSurface`'s own KDoc states the rule: the queue's sink crosses at the surface *because*
     * [FileBrowserQueueTarget] is `internal` to this module and could not appear in this class's public
     * signature; [onDelete] is a constructor parameter *because* its whole signature is public types.
     * `ExtractDestinationOptions` is public in `core-files`, which `app/` already depends on, so this
     * belongs with [onDelete] — and it has to be settled before the first frame for the same reason
     * [onDelete] does: its presence IS the capability, and a sink arriving later would un-deaden a
     * binding the footer had already declined to advertise.
     *
     * **Nullable, defaulting null, and that is half of [extractChoiceAvailable].** With no resolver,
     * `SECONDARY` produces [FileBrowserCommand.Extract] exactly as it did before the earlier change.
     */
    private val resolveExtractOptions: ((String) -> ExtractDestinationOptions)? = null,
    /**
     * F-06: extract an archive into an explicitly chosen destination — `FileBrowserStore::extractTo`.
     *
     * The other half of [extractChoiceAvailable]. **Two sinks rather than one** for the reason
     * `FileBrowserSurface` gives for splitting `onReleaseUndo` out of `onDismiss`: a single-sink contract
     * would let a caller wire the resolver and not the performer, and the chooser would then draw two
     * rows, take focus, advertise a verb and do nothing on A. Both or neither.
     *
     * Used for **every** row, including "Extract here" — [onExtract] stays for the unwired path only.
     * `ExtractDestinationPolicy.extractHereDestination` is the same sibling directory
     * `FileBrowserStore.extract` computes inline (its own KDoc says it was lifted from there), so the
     * wired "Extract here" and the unwired one write to the identical place by one shared rule rather
     * than by two expressions that agree today.
     */
    private val onExtractTo: ((String, String) -> Unit)? = null,
    /**
     * G2: move the file at the first argument into the directory at the second —
     * `FileBrowserStore::moveInManager` in production, which stages through the rollback journal, resolves
     * conflicts through the queue, and deletes its own staging on success.
     *
     * **Half of [transferChoiceAvailable], and the pair is not negotiable.** A caller that wired this and
     * not [onCopyTo] would get a chooser drawing `Copy into…` as a focusable, advertised, dead row — the
     * exact failure [onExtractTo]'s own "both or neither" paragraph exists to prevent, one screen along.
     *
     * **One path, not a list, and the widening is deliberately deferred.** `moveInManager` takes
     * `List<String>` because the engine has always been multi-path; this browser presses one file at a
     * time, and selection mode (G3) is what will fill the list. A singleton today costs nothing.
     *
     * **Undo, corrected 2026-08-16.** This paragraph used to say a completed move had none, and that was
     * true when it was written: `FileBrowserQueueAction.UNDO` required a `FileQueueState.UndoAvailable`
     * and its `StagedDeletion` handle, which a finished move has no way to produce. A move now settles as
     * `FileQueueState.ReverseAvailable`, whose handle is the landed paths and the folder they left, and
     * Undo sends them back as an ordinary move. What is still true, and is the part a label must not
     * overstate: the reverse is a **new transfer** that can itself collide or fail, and a game that
     * departed the library does not return to the shelf until the next scan.
     */
    private val onMoveTo: ((String, String) -> Unit)? = null,
    /**
     * Move a whole multi-disc set — the playlist and every disc — into one folder, after warning
     * and offering the complete set. `FileBrowserStore::moveInManager` in
     * production, which has always taken a list.
     *
     * **Separate from [onMoveTo] rather than widening it**, because the two are different promises. A
     * single-file move is the player naming one file; this is Mimir naming several on their behalf, off
     * the back of a library lookup, and the only screen that may reach it is the one that showed them the
     * list. Keeping [onMoveTo]'s single-path shape also keeps its "one path, widening deferred" contract
     * honest for every other caller.
     */
    private val onMoveSetTo: ((List<String>, String) -> Unit)? = null,
    /**
     * G2: copy the file at the first argument into the directory at the second —
     * `FileBrowserStore::copyInManager` in production.
     *
     * The other half of [transferChoiceAvailable].  — this sink is that sentence, and it is why the chooser has two rows.
     */
    private val onCopyTo: ((String, String) -> Unit)? = null,
    private val classifyPlatform: (FileItem) -> FileBrowserRecognition = { FileBrowserRecognition.None },
    /**
     * Every platform folder available for explicit destination selection.
     *
     * Supplied by `FileBrowserStore.platformDestinations`, which reads the library's own folders and the
     * platform-named folders discovered on the drives. Null by default and null in every test that does
     * not name it, and that default is the previous behaviour exactly: no list, and a destination pick
     * drops the player straight into manual navigation.
     */
    private val resolvePlatformDestinations: ((String) -> List<PlatformDestination>)? = null,
    /**
     * Whether the file about to be moved is one disc of a multi-disc game; the warning can offer to move all discs.
     *
     * Supplied by `FileBrowserStore.discSetWarningFor`, which asks the library. Suspends, and is
     * dispatched on [loadScope] exactly like the async listing: it is a Room read plus a small file read,
     * and composition-time cost was measured ([listDirectoryAsync]).
     *
     * **Null by default, and null answers the previous behaviour exactly** — no warning, the move goes
     * straight through. That is also what a *failed* lookup answers, deliberately: see
     * `FileBrowserStore.discSetWarningFor` for why "I could not check" is stated on the move's own card
     * rather than as a warning nobody can act on.
     */
    private val resolveDiscSet: (suspend (String) -> DiscSetWarning?)? = null,
    /**
     * Whether the pinned commit affordance should exist for a given directory. Defaults to always.
     *
     * **Contract C, 2026-08-14.** `core-files`' `StorageVolumeListing` makes `/storage` a browser root
     * whose listing IS the drive chooser, and committing the chooser itself as a ROM source is
     * meaningless. A coordinator-side guard could only Toast AFTER the fact, because [close] runs before
     * `onChoose` -- so the player would be ejected from the browser and then told no. Suppressing the row
     * is the honest fix: an action that cannot succeed should not be offered, and index 0 then belongs to
     * the first drive instead.
     */
    private val commitAllowed: (String) -> Boolean = { true },
    val scope: FocusScope = FileBrowserFocus.DefaultScope,
    /**
     * The same listing as [listDirectory], allowed to suspend — supplied together with [loadScope] to
     * take the filesystem off the thread the player is pressing buttons on.
     *
     * **Why the pair is optional and why both are needed.** With either one null, [load] behaves exactly
     * as it always has: a synchronous call, applied before the press returns. Every one of this module's
     * ~25 controller tests keeps that shape, which is what makes them deterministic without a test
     * dispatcher. Production supplies both.
     *
     * **The contract is that this function may return WITHOUT suspending, and that fast path is
     * load-bearing.** [load] starts the coroutine [CoroutineStart.UNDISPATCHED], so a suspend function
     * that answers from memory runs to completion inline on the calling thread and the listing is applied
     * in the same frame as the press — no dispatch, no extra frame, indistinguishable from the
     * synchronous path. `FileBrowserStore`'s per-path cache is exactly that case, which is what makes
     * backing out of a directory cost nothing. A cold directory suspends at the IO hop instead, and then
     * the player's thread is free while it reads.
     *
     * @see loadScope
     */
    private val listDirectoryAsync: (suspend (String) -> List<FileItem>)? = null,
    /**
     * Where an asynchronous [load] runs. Must be a MAIN-thread scope: the apply half writes Compose
     * snapshot state and drives [FocusAuthority], neither of which may be touched off the main thread.
     *
     * The coroutine starts undispatched (see [listDirectoryAsync]) and resumes on this scope's dispatcher
     * after the IO hop, which is the frame the new listing lands on. A load in flight is superseded by the
     * next one — see [supersedePendingLoad].
     */
    private val loadScope: CoroutineScope? = null,
    /**
     * Rename the file or folder at the first argument to the second, in the folder it is in --
     * `FileBrowserStore::renameInManager` in production . The name has already been
     * through [RenameNamePolicy] on the keyboard; the store and engine check it again.
     *
     * Its presence IS the capability ([renameAvailable]), MANAGE only, so the picker cannot rename
     * anything: the Rename row is not drawn and the keyboard never opens.
     */
    private val onRename: ((String, String) -> Unit)? = null,
    /**
     * Stop the running queue entry with this id -- `FileBrowserStore::cancel` .
     * Reached from the queue screen's Cancel button; null in a caller that wired no engine, where the
     * button does nothing because no running card can exist.
     */
    private val onCancelEntry: ((Long) -> Unit)? = null,
) {
    private val stack = ArrayDeque<String>()

    /** The asynchronous [load] currently in flight, if any. Cancelled by the next one and by [close]. */
    private var loadJob: Job? = null

    /**
     * Bumped by every [load]. The in-flight coroutine captures the value it was started with and applies
     * nothing if it no longer matches — belt-and-braces beside [loadJob]'s cancellation, because a
     * coroutine that has already resumed past its last suspension point cannot be cancelled any more.
     */
    private var loadGeneration = 0

    /**
     * Undo for the navigation that started the in-flight [load], run when a newer one supersedes it.
     *
     * A navigation mutates [stack] at the press and applies its listing later. If it never applies, its
     * mutation has to come back off — otherwise the browser's idea of where it is and the player's part
     * company, and [atRoot] is `stack.isEmpty()`, so a corrupted stack does not merely mis-navigate: it
     * makes CANCEL **close the browser from inside a subdirectory**.
     *
     * Both kinds register an undo, and [supersedePendingLoad] decides whether to run it — see
     * [pendingLoadKind] for the one pair that must not.
     */
    private var supersedeLoad: (() -> Unit)? = null

    /**
     * What kind of navigation the in-flight [load] is, so [supersedePendingLoad] can tell the one case
     * apart where the pending mutation must SURVIVE being superseded.
     *
     * **Up superseded by Up is that case, and it is the only one.** Up *consumes* a stack entry per
     * press: two Ups inside one load window must climb two levels, so putting the first one's entry back
     * would land the second press where the first already was. Every other pair undoes:
     *
     * | pending | superseded by | undo? | why |
     * |---|---|---|---|
     * | Enter | Enter | yes | the push is "the directory I am leaving", and it has not changed |
     * | Enter | Up | yes | the descent never happened, so the player ascends from where they still are |
     * | Up | Enter | yes | the ascent never happened; leaving it consumed silently skips a level |
     * | Up | Up | **no** | consumption — this is what makes two presses climb two levels |
     * | either | refresh / reveal / open | yes | none of those are navigations; the press is abandoned |
     */
    private var pendingLoadKind: FileBrowserLoadKind = FileBrowserLoadKind.OTHER

    /**
     * True once a listing has been applied. Until then [load] stays synchronous no matter what
     * [listDirectoryAsync] was given: the first frame after [open] has always had rows in it, and an
     * async first load would compose an EMPTY browser and pop the listing in afterwards — a new visual
     * and a new `directoryArrival` over an empty partition, which is a design change, not a perf fix.
     */
    private var hasListed = false

    private val tracker = FileBrowserFocusTracker()
    private val searchTracker = FileBrowserSearchFocusTracker()
    private val queueTracker = FileBrowserQueueFocusTracker()
    private val extractTracker = FileBrowserExtractFocusTracker()
    private val transferTracker = FileBrowserTransferFocusTracker()
    private val discSetTracker = FileBrowserDiscSetFocusTracker()

    /**
     * RC-12: the solved column/cell/pitch/viewport geometry this controller lays the folio's focus
     * lattice out by -- see [FileBrowserGrid]'s own KDoc for why this is ONE value rather than four.
     *
     * **Defaults to [FileBrowserGrid.Authored], the Odin's own solve, not an empty/zero grid.** This
     * controller is constructed OUTSIDE composition (`app/.../FileBrowserStore.kt`), so [open] / [load]
     * can run — and in production, always does run once, for the very first directory — before
     * [FileBrowserSurface] has measured anything at all. Seeding the pre-measurement default to the
     * authored solve rather than to some placeholder means that first frame behaves exactly as it did
     * before this ticket; [reprojectGrid] is what corrects it once the surface reports its real bounds.
     */
    private var grid: FileBrowserGrid = FileBrowserGrid.Authored

    /**
     * The solved grid, for the search screen's own geometry (F-S2).
     *
     * Read-only and internal: `dispatchSearch`'s stride and the collector's flush cadence both derive
     * from the SAME solve the folio lays its lattice out by, rather than from the frozen constants they
     * used to divide. The search SURFACE deliberately does not read this — it is handed the grid the
     * enclosing `BoxWithConstraints` just solved, because this field still holds
     * [FileBrowserGrid.Authored] (the Odin's box) until `reprojectGrid` runs, and a first frame that
     * seated the scope card off the Odin's width would flash a seated card on a Nova.
     */
    internal val solvedGrid: FileBrowserGrid get() = grid

    /**
     * RC-12: called from a composition effect once [FileBrowserSurface] has measured its own box —
     * `remember(maxWidth, maxHeight) { fileBrowserSolveGrid(...) }` on the caller's side, so this is only
     * invoked when the measured viewport actually changes (a rotation, a multi-window resize), never on
     * an unrelated recomposition.
     *
     * **Idempotent by construction, on top of the caller's own `remember` guard.** [FileBrowserGrid] is a
     * data class, so an unchanged solve short-circuits here too — a defence-in-depth no-op, not the only
     * one, preserving idempotency: the same viewport always yields the same solve,
     * and re-applying it re-registers nothing.
     *
     * **Never retires, never clears focus.** [tracker]'s [FileBrowserFocusTracker.reproject] re-registers
     * every already-registered key's bounds in place; it does not diff against a `desired` set the way
     * [FileBrowserFocusTracker.update] does, so the focused-key hazard [FileBrowserFocusTracker.retire]
     * exists to avoid ([FocusAuthority.unregister]'s sibling-fallback branch) is unreachable here without
     * this function ever depending on a `clearFocus()` call it does not make — see
     * [FileBrowserFocusTracker.reproject]'s own KDoc.
     */
    internal fun reprojectGrid(newGrid: FileBrowserGrid) {
        if (newGrid == grid) return
        grid = newGrid
        tracker.reproject(authority, scope, grid)
    }

    /**
     * F-08's own [FocusScope], a CHILD of [scope] rather than a sibling constant.
     *
     * Derived from [scope] so the two can never disagree: a caller that supplies its own browse scope
     * (the documented way to mount two browsers at once) automatically gets a matching, distinct search
     * scope instead of both browsers sharing one global search graph. With the default it evaluates to
     * exactly [FileBrowserSearchScope] (`files/search`).
     *
     * **Being a separate scope is what makes CANCEL restore the player's place for free.**
     * `DefaultFocusAuthority.popScope` restores `focusMemory[activeScope]`, so leaving search returns
     * focus to the tile the player was standing on with no bookkeeping here at all — see
     * [FileBrowserSearchScope]'s KDoc for the second, harder reason (global `FocusKey` keying).
     */
    val searchScope: FocusScope = FocusScope("${scope.value}/search")

    /**
     * F-01's own [FocusScope], derived from [scope] on exactly [searchScope]'s reasoning.
     *
     * **It evaluates to [FileBrowserQueueFocus.Scope] (`files/queue`) for the default browse scope**, so
     * the default queue scope remains compatible while also giving a caller
     * that mounts two browsers at once two distinct queue graphs instead of one shared global. That
     * equality is pinned in `FileBrowserControllerTest` rather than left as a coincidence of two string
     * literals in different files.
     *
     * Being a separate scope is what makes CANCEL restore the player's place for free:
     * `DefaultFocusAuthority.popScope` restores `focusMemory[browse]`, so leaving the queue returns focus
     * to the tile the player was standing on -- including, after a delete, the tile [load] landed on in
     * their absence (see [fileBrowserSurvivingNeighbour]).
     */
    val queueScope: FocusScope = FocusScope("${scope.value}/queue")

    /**
     * F-06's own [FocusScope], derived from [scope] on exactly [searchScope]'s and [queueScope]'s
     * reasoning, and evaluating to [FileBrowserExtractFocus.Scope] (`files/extract`) for the default
     * browse scope — pinned in `FileBrowserExtractWiringTest` rather than left as a coincidence of two
     * string literals in different files.
     *
     * **Note what does NOT get a scope: destination-pick.** The pick reuses the browse scope, because it
     * reuses the browse SCREEN — the folio, the ring and the D-pad are the picker. A scope of its own
     * would mean a second graph over the same rows, which is the arrangement half of this browser's
     * shipped D-pad defects came from.
     */
    val extractScope: FocusScope = FocusScope("${scope.value}/extract")

    /**
     * G2's own [FocusScope], derived from [scope] on exactly [extractScope]'s reasoning, and evaluating to
     * [FileBrowserTransferFocus.Scope] (`files/transfer`) for the default browse scope — pinned in
     * `FileBrowserTransferWiringTest` rather than left as a coincidence of two string literals in
     * different files.
     *
     * **A fifth scope, not a second posture on [extractScope].** The two choosers are mutually exclusive
     * and could in principle have shared one graph; sharing it would also share the authority's
     * `focusMemory` entry, so a remembered `MOVE` row would validate perfectly against the *extract*
     * chooser's rows and land the ring somewhere the player has not read. See
     * [FileBrowserTransferFocus.Scope].
     */
    val transferScope: FocusScope = FocusScope("${scope.value}/transfer")

    /**
     * Per-directory focus memory: absolute path -> the key focus was on when that directory was last
     * left. **Added 2026-08-14**, closing this class's own longest-standing owed item.
     *
     * Before it, [load] refocused index 0 unconditionally, so every navigation step -- in OR out --
     * dumped the player back at the top of the listing. Backing out of a folder to keep browsing a
     * 200-entry ROM directory meant re-traversing it from the first tile every single time; on a
     * handheld that is an unnecessary navigation cost.
     *
     * **A map, not a scope stack** -- the browser deliberately stays one flat [FocusScope]
     * ([FileBrowserFocus]'s own KDoc), so there is still exactly one scope to pop on [close].
     *
     * **Keyed by path both ways, which is what makes stale entries harmless.** A remembered key is only
     * restored if it is still in the incoming listing's own key set, and both sides of that check are
     * path-qualified ([FileBrowserFocus.tileKey]) -- so a file that was deleted, renamed, or reordered
     * while the browser was elsewhere simply is not found, and focus falls back to index 0. Focus can
     * never slide onto a DIFFERENT file that happens to occupy the old one's position, because no
     * position is remembered anywhere.
     *
     * **Bounded to one browse session**, cleared by [open]. It grows with the number of distinct
     * directories visited while mounted, holding two small strings each; that is the same order as the
     * back-stack a player builds by hand and it dies with the surface.
     */
    private val focusByDirectory = HashMap<String, FocusKey>()

    /**
     * The current directory's tiles, split into tomes and files. `internal` (not part of the public
     * surface -- see the class KDoc) but still Compose-observable: [FileBrowserSurface] reads it
     * directly and recomposes when [load] reassigns it.
     */
    internal var partition: FileBrowserPartition by mutableStateOf(FileBrowserPartition(emptyList(), emptyList()))
        private set

    /**
     * [FileBrowserMode.PICK_IMAGE] only: whether
     * the player has flipped the pinned row to **All files**. False -- images and folders only -- from every
     * [open]. Compose-observable because the pinned row's words and the listing both follow it.
     */
    internal var imagePickShowsAll: Boolean by mutableStateOf(false)
        private set

    /**
     * How many files in the open folder the image picker cannot take (not a still image). Set by
     * [applyListing] BEFORE the pinned row's key is read, because the row exists exactly when there is
     * something to flip: [commitPurposeForCurrentMode] answers [FileBrowserCommitPurpose.TOGGLE_IMAGE_FILTER]
     * only while this is positive or all files are showing. Always 0 outside [FileBrowserMode.PICK_IMAGE].
     */
    internal var imagePickHiddenCount: Int by mutableStateOf(0)
        private set

    /**
     * The open folder's listing before the image picker's filter, kept so flipping the pinned row re-derives
     * the view from memory -- no second disk read, no loading flash -- through [applyListing] itself, the one
     * place a listing becomes the screen.
     */
    private var unfilteredPartition: FileBrowserPartition? = null

    /** The directory currently open. */
    var currentPath: String = rootPath
        private set

    /**
     * Every folder tile a listing has shown, by path, so the inspector can describe the folder that is
     * open -- its own tile, from its parent's listing -- when no tile is focused (the commit or back-to-origin row, an
     * empty folder) instead of going blank. Read from listings already made: no extra disk access. Bounded.
     */
    private val knownDirectoryTiles = HashMap<String, FileBrowserTile>()

    /** The open folder's own tile, when a listing has shown it; null otherwise, and the inspector then stays blank. */
    internal val currentDirectoryTile: FileBrowserTile?
        get() = knownDirectoryTiles[currentPath]

    /**
     * The glide the surface should play for the directory currently open, or null when it should play
     * none -- F-03. See [FileBrowserNavigationMotion] for the shape and its reference.
     *
     * Compose-observable, unlike [currentPath] and [commitAvailable], because it is the ONE piece of
     * state whose whole purpose is to reach the composition: a navigation reassigns [partition] too, so
     * recomposition is already guaranteed, but this being observable in its own right means a future
     * caller that changes it without touching the listing cannot silently fail to animate.
     *
     * **Null on [open] and never re-nulled afterwards.** A first mount must not glide -- there is no
     * previous listing to have come from -- which is the same polarity the reference gets from
     * `shouldAnimate == false` starting its progress at 1f. It is not cleared when a glide finishes:
     * the surface keys its animation on [FileBrowserNavigationMotion.sequence], so a settled motion left
     * in place re-triggers nothing, and clearing it would need a completion callback threaded back into
     * the controller for no behavioural gain.
     */
    internal var navigationMotion: FileBrowserNavigationMotion? by mutableStateOf(null)
        private set

    private var navigationSequence: Long = 0L

    // ───────────────────────────────────────────────────────────────── F-08 · the search session ──
    //
    //  All six fields are Compose-observable because the surface renders every one of
    // them AND keys its collection effect on the query/filter pair; the decisions they feed are pure and
    // live in FileBrowserSearch.kt, not here.

    /** Whether the search screen is showing. [searchScope] is pushed for exactly this interval. */
    internal var searchOpen: Boolean by mutableStateOf(false)
        private set

    /** The typed query. Only ever written through [setSearchQuery], i.e. only ever by the keyboard. */
    internal var searchQuery: String by mutableStateOf("")
        private set

    /** The active filter chip. `ALL` on open, matching the design's own initial state. */
    internal var searchFilter: FileSearchFilter by mutableStateOf(FileSearchFilter.ALL)
        private set

    /**
     * Bumped by [setSearchFilter] on every call, including a re-press of the already-active chip
     * (R6-F-09, reviewer of R6-P2-07/R6-P3-03, 2026-09-15).
     *
     * A same-value write to [searchFilter] changes nothing a reader can observe: Compose's snapshot
     * state (`structuralEqualityPolicy`, the default `mutableStateOf` uses) does not notify observers of
     * a write whose new value equals the old one. [FileBrowserSearchSurface]'s `LaunchedEffect` reads
     * [searchQuery] and [searchFilter] as its restart key, so a re-press of the active chip left that
     * key list unchanged and the collector never reran — the exact defect this field exists to close.
     * This is [navigationSequence]'s own idiom, reused for the identical reason: some presses must be
     * observable even though the value they set does not change.
     */
    internal var searchWalkToken: Long by mutableStateOf(0L)
        private set

    /** What the walk has produced so far. Grows during a walk; replaced wholesale when the query or the
     * chip changes. Published in batches — see [fileBrowserSearchShouldFlush] for why not per emission. */
    internal var searchResults: List<FileItem> by mutableStateOf(emptyList())
        private set

    /** Idle / running / settled. The one thing that makes an empty result honest rather than a spinner. */
    internal var searchPhase: FileBrowserSearchPhase by mutableStateOf(FileBrowserSearchPhase.IDLE)
        private set

    /** Whether the on-screen keyboard is up over the search screen. */
    internal var searchKeyboardOpen: Boolean by mutableStateOf(false)
        private set

    /**
     * The keyboard's focus binding, handed down by [FileBrowserSurface] because
     * `rememberMimirKeyboardFocusBinding` is `@Composable` and this class is not.
     *
     * Held rather than only composed so [dispatch] can route to it and so [closeSearch] can call
     * [MimirKeyboardFocusBinding.exit] **defensively**: a host teardown disposes `MimirKeyboard`'s own
     * `DisposableEffect` and this class's `close()` in an order this module does not control, and a
     * keyboard scope left pushed would corrupt the stack for whatever surface comes next. `exit()` is
     * idempotent (`if (!entered) return`), so calling it from both paths is safe by construction.
     */
    private var searchKeyboard: MimirKeyboardFocusBinding? = null

    // ───────────────────────────────────────────────────────────────────────────────────── Rename ──

    /**
     * Whether Mimir's keyboard is up for a rename. **The Actions overlay's Rename row opens it** (policy
     * 2026-10-08: the name is typed on Mimir's own keyboard, the one Library search uses, never a system IME
     * dialog), and it is a hard modal exactly as the search keyboard is: [dispatch] routes everything to it.
     */
    internal var renameOpen: Boolean by mutableStateOf(false)
        private set

    /** What has been typed so far. Survives the keyboard's exit animation, which is why it is not nulled on close. */
    internal var renameText: String by mutableStateOf("")
        private set

    /** The rule [renameText] currently breaks, or null. Shown under the field and re-checked at Done. */
    internal var renameProblem: RenameProblem? by mutableStateOf(null)
        private set

    /** The item being renamed. Null while no rename is open. */
    private var renamePath: String? = null

    /** The name it has now: typing it back unchanged is a problem of its own ([RenameProblem.UNCHANGED]). */
    internal var renameOriginal: String by mutableStateOf("")
        private set

    /**
     * The keyboard's focus binding, handed down by [FileBrowserSurface] because
     * `rememberMimirKeyboardFocusBinding` is `@Composable` and this class is not -- the search keyboard's own
     * arrangement, see [searchKeyboard].
     */
    private var renameKeyboard: MimirKeyboardFocusBinding? = null

    /**
     * Whether the mounting caller wired a real search engine — [FileBrowserSurface]'s `search` parameter,
     * filled in production by `FileBrowserStore::search`.
     *
     * **Nothing about search is offered when this is false**: TERTIARY stays inert, the footer draws no
     * SEARCH hint, and the header's magnifier is not drawn at all. That is deliberate rather than
     * defensive. A search screen with no provider behind it would answer every query with "Nothing here
     * by that name" over a card full of games — a surface that lies, which is worse than one that is
     * absent. Same posture as `MimirPlatformShelfmark` drawing nothing for an unnameable platform.
     */
    internal var searchAvailable: Boolean by mutableStateOf(false)
        private set

    /** [FileBrowserSurface] reports whether it was given a search provider. See [searchAvailable]. */
    internal fun setSearchAvailable(available: Boolean) {
        if (searchAvailable == available) return
        searchAvailable = available
        if (!available) closeSearch()
    }

    // ──────────────────────────────────────────────────────────────── F-01 · the queue session ──
    //
    //  `FileBrowserStore`'s whole operations engine
    // -- deleteInManager, undo, releaseUndo, dismiss, resolveConflict, and the `queue` flow itself -- was
    // finished, tested, and had ZERO production callers. This region is the half of that gap that lives in
    // this module: a screen with its own scope, and one press that opens it.

    /**
     * **Why** the queue screen is showing, or null while it is not — W-1. [queueScope] is pushed for
     * exactly the interval this is non-null.
     *
     * One nullable value rather than `queueOpen: Boolean` plus an `exitReview: Boolean`, for
     * [FileBrowserCommitPurpose]'s reason: "open with no purpose" and "reviewing while closed" are both
     * meaningless states a two-flag shape can hold and a caller can therefore produce. [queueOpen] stays
     * as the derived read every existing caller already uses.
     */
    internal var queuePurpose: FileBrowserQueuePurpose? by mutableStateOf(null)
        private set

    /** Whether the queue screen is showing. [queueScope] is pushed for exactly this interval. */
    internal val queueOpen: Boolean get() = queuePurpose != null

    /**
     * The store's queue, as last published by [publishQueue]. Held even while [queueOpen] is false,
     * because the browse footer draws its live COUNT (C-1: Undo is the only protection a delete has, so
     * the queue has to be findable from a screen that is not it).
     */
    internal var queueEntries: List<FileQueueEntry> by mutableStateOf(emptyList())
        private set

    /**
     * How many deletes the queue is still holding an Undo for — **W-1's whole input.**
     *
     * `FileOperationQueue.heldDeletions` is not a second opinion about what is held: it is the exact
     * function `FileBrowserStore.clearSession` walks to decide what to `finalizeDelete` when this browser
     * closes. Reading it here rather than counting `UndoAvailable` entries locally is what makes it
     * impossible for the footer to promise a number the teardown then disagrees with.
     *
     * Recomputed rather than cached, exactly as [queueCards] is: [queueEntries] is the state, this is a
     * projection of it, and a cached copy is a second place for it to be stale.
     */
    internal val heldDeleteCount: Int get() = FileOperationQueue.heldDeletions(queueEntries).size

    /**
     * Where an activated queue button goes -- `FileBrowserStore.undo` / `releaseUndo` / `dismiss` /
     * `resolveConflict`, routed by [FileBrowserQueueAction]'s own stated mapping.
     *
     * **Set by [FileBrowserSurface], not by the constructor, and that is forced rather than chosen.**
     * [FileBrowserQueueTarget] is `internal` to this module, so a constructor parameter carrying it would
     * expose an internal type in this class's public signature -- and `app/`, which builds this class,
     * could not name the type anyway. The surface is inside the module, so the binding crosses the seam
     * where it already crosses for search: as `FileBrowserSurface` parameters that `app/` fills with
     * plain `(Long) -> Unit` store calls. [onDelete] needs no such treatment; its whole signature is
     * public types, so it stays a constructor parameter beside [onExtract], which it mirrors exactly.
     *
     * **Non-null IS "the queue is wired"** -- deliberately one flag rather than two. A separate
     * `setQueueAvailable(true)` could be reported by a surface that then never supplied a handler, giving
     * a queue screen whose buttons are focusable, advertised, and inert. Deriving availability from the
     * sink's own presence makes that state unrepresentable.
     */
    private var queueAction: ((FileBrowserQueueTarget) -> Unit)? by mutableStateOf(null)

    /**
     * [FileBrowserSurface] reports the store's queue sink, or null when it was given none.
     *
     * Null closes the screen if it is open, matching [setSearchAvailable]: a screen whose engine has been
     * taken away must not be left standing with a pushed scope nothing can act in.
     */
    internal fun setQueueAction(action: ((FileBrowserQueueTarget) -> Unit)?) {
        if ((queueAction != null) == (action != null)) {
            queueAction = action
            return
        }
        queueAction = action
        if (action == null) closeQueue()
    }

    // ────────────────────────────────────────────────── F-06 · the Extract chooser + destination pick ──
    //
    // The Extract chooser offers extraction here and into a chosen or recognised platform folder.
    // Previously unreachable components: `ExtractDestinationPolicy`, `fileBrowserExtractOptions` and
    // `FileBrowserStore.extractOptions`/`extractTo` were all finished and pinned with zero production
    // callers. This region is the door.

    /**
     * The Extract chooser's rows and the archive they are about, or null when it is not showing.
     * [extractScope] is pushed for exactly this interval.
     *
     * **`by mutableStateOf`, and on this field that is load-bearing rather than stylistic.** The commit row reads
     * [commitPurpose] and [commitAvailable] as plain getters, and those getters are only reactive because
     * the chain underneath them touches snapshot state. [partition] cannot carry that job here: it is
     * compared structurally, so re-loading the SAME directory -- which is exactly what
     * [beginDestinationPick] and [endDestinationPick] do -- can write an equal value and skip
     * recomposition entirely. Make this a plain `var` and the commit row's copy changes late or never,
     * with a green suite and no Compose harness in this module able to catch it.
     */
    internal var extractChooser: FileBrowserExtractChoiceState? by mutableStateOf(null)
        private set

    /** Whether the chooser screen is showing. */
    internal val extractChooserOpen: Boolean get() = extractChooser != null

    /**
     * The destination pick in progress, whatever verb asked for it, or null. See [FileBrowserPickSession];
     * `by mutableStateOf` for [extractChooser]'s reason, which applies here more sharply still because
     * this field is what changes the commit row's very EXISTENCE in [FileBrowserMode.MANAGE].
     *
     * **One field for three verbs (G2).** Every consumer below — the phase, the commit gate, the abandon
     * path, the arming in [loadAfterPickChange], and all four teardown sites — reads this and only this,
     * so there is no arrangement in which one of them has heard about a pick and another has not.
     */
    private var pickSession: FileBrowserPickSession? by mutableStateOf(null)

    /**
     * The **extract** destination pick in progress, or null — F-06's own read, preserved exactly.
     *
     * Derived rather than stored as of G2: `null` now means *"no pick, or a pick that is not an
     * extraction"*, which is what every existing caller already meant by it. Compose-observable through
     * [pickSession], which is the snapshot state; see that field.
     */
    internal val destinationPick: FileBrowserDestinationPick?
        get() = (pickSession as? FileBrowserPickSession.Extract)?.pick

    /**
     * The open pick's origin path, or null — **F-2's own read of [pickSession], exposed for exactly
     * [destinationPick]'s reason.** [FileBrowserSurface] builds [FileBrowserFocus.backToOriginKey] itself
     * from this, the same way it already builds [FileBrowserFocus.commitKey] from [currentPath] rather
     * than reaching into a private controller method — this is the one piece of [pickSession] a caller
     * outside this class legitimately needs, and exposing exactly that (not the session itself) keeps
     * [pickSession]'s three verbs (G2) a decision this class alone still makes.
     */
    internal val pickOriginPath: String? get() = pickSession?.originPath

    /**
     * The transfer chooser's rows and the file they are about, or null when it is not showing —
     * **G2's door.** [transferScope] is pushed for exactly this interval.
     *
     * `by mutableStateOf` for [extractChooser]'s reason, unchanged: The surface reads this to decide whether to
     * draw the chooser at all, and a plain `var` would leave the screen appearing late or never with a
     * green suite and no Compose harness in this module able to catch it.
     */
    internal var transferChooser: FileBrowserTransferChooser? by mutableStateOf(null)
        private set

    /** Whether the transfer chooser is showing. */
    internal val transferChooserOpen: Boolean get() = transferChooser != null

    /**
     * Whether `SECONDARY` offers Move/Copy at all on a plain file — **[FileBrowserMode.MANAGE] plus both
     * sinks.**
     *
     * ## Why this is [deleteAvailable]'s shape and not [extractChoiceAvailable]'s
     *
     * The two gates one line apart look interchangeable and are not, and getting it backwards was caught
     * in review of this very slice. [extractChoiceAvailable] is deliberately mode-blind, and its own KDoc
     * says why: *"Extraction writes new files and removes none, so it is not destructive… Contrast
     * [deleteAvailable] and [queueAvailable], which are mode-gated precisely because the things behind them
     * destroy files."*
     *
     * **A move removes its source.** `ManagedFileOperations.move` relocates the file and deletes its own
     * staging on success, and there is no `undoMove` — so Move belongs to the class the sentence above
     * puts delete in, not to extract's. PICK must be *incapable* of that,
     * not merely restricted, and `mode == MANAGE` here is what makes it incapable: with it false there is
     * no value of any other state that produces a [FileBrowserCommand.OfferTransfer], the chooser cannot
     * open, and the footer advertises nothing on a plain file.
     *
     * **Copy is not split out, and that is a decision rather than an oversight.** A copy alone removes
     * nothing and could in principle live in PICK. It does not, because the two verbs share one chooser
     * and **PICK has no queue** ([queueAvailable] is MANAGE-only): a copy that hits a name collision
     * raises `ConflictResolution.ASK`, and the Keep both / Replace / Skip question is asked on the queue
     * screen. A PICK copy would therefore stall with nowhere to ask. One gate, MANAGE-only, is the honest
     * shape; splitting them remains deferred.
     *
     * **Both sinks or neither**, the same honesty gate [extractChoiceAvailable] applies: a caller wiring
     * [onMoveTo] and not [onCopyTo] would draw a chooser whose second row is focusable, advertised and
     * dead. Two enforcement layers sit underneath this one and neither replaces it —
     * `FileBrowserStore.moveInManager`/`copyInManager` each open with `operations ?: return` (null in
     * PICK), so the cost of getting this gate wrong is a **dead verb**, not a lost file. That is exactly
     * why it is still gated: a chooser that opens, walks the player through a whole destination pick and
     * then silently does nothing is the advertised-but-inert shape this module refuses everywhere else.
     */
    internal val transferChoiceAvailable: Boolean
        get() = mode == FileBrowserMode.MANAGE && onMoveTo != null && onCopyTo != null

    /**
     * Whether `SECONDARY` offers a destination choice at all -- **both sinks wired.**
     *
     * Fails closed, the same polarity as [deleteAvailable] and [queueAvailable]: with either sink absent
     * there is no value of any other state that produces a [FileBrowserCommand.OfferExtract], the footer
     * still advertises the plain EXTRACT it always did, and `SECONDARY` reaches [onExtract] exactly as it
     * did before the earlier change.
     *
     * **Deliberately NOT gated on [mode].** Extraction writes new files and removes none, so it is not
     * destructive; PICK has always offered it (`FileBrowserStore.extractOptions`' own KDoc: *"Available
     * in BOTH modes"*), and gating the *destination choice* to MANAGE would take from PICK a capability
     * PICK already had. Contrast [deleteAvailable] and [queueAvailable], which are mode-gated precisely
     * because the things behind them destroy files.
     */
    internal val extractChoiceAvailable: Boolean
        get() = resolveExtractOptions != null && onExtractTo != null

    /**
     * Whether SELECT deletes at all -- **[FileBrowserMode.MANAGE] plus a real sink.**
     *
     * PICK must be *incapable*, not merely restricted. In PICK this is false by
     * MODE, so [fileBrowserCommand] cannot produce a [FileBrowserCommand.Delete] whatever else is true,
     * the footer advertises nothing, and there is no branch anywhere downstream that has to remember to
     * check. The `onDelete != null` half is the separate honesty gate -- see that parameter's KDoc.
     */
    internal val deleteAvailable: Boolean get() = mode == FileBrowserMode.MANAGE && onDelete != null

    /** Whether the Actions overlay offers Rename -- MANAGE plus a real sink, [deleteAvailable]'s own shape. */
    internal val renameAvailable: Boolean get() = mode == FileBrowserMode.MANAGE && onRename != null

    /** Whether START opens the queue at all -- [fileBrowserQueueAvailable] (MANAGE only) plus somewhere
     * for a button press to go ([queueAction]). */
    internal val queueAvailable: Boolean
        get() = fileBrowserQueueAvailable(mode) && queueAction != null

    /** The cards the queue screen draws, projected from [queueEntries]. Recomputed rather than cached:
     * the projection is pure and the list is short (one entry per operation this session). */
    internal val queueCards: List<FileBrowserQueueCard> get() = fileBrowserQueueCards(queueEntries)

    /** True when there is no parent to go up to -- what turns CANCEL/SYSTEM_BACK into [close] instead of [up-navigation]. */
    val atRoot: Boolean get() = stack.isEmpty()

    /**
     * Whether [open] has EVER run on this instance -- i.e. whether this controller's [scope] has ever
     * actually been pushed. Latches on the first [open]; deliberately **not** reset by [close].
     *
     * **P0 2026-08-14.** The app shell's `FOLDER_BROWSER` dispatch branch absorbs controller input
     * deliberately, to cover the one-frame gap between "the store holds a controller" (which makes this
     * overlay top of the stack) and "[open] has pushed [scope]" (which is what makes [dispatch] stop
     * refusing). That absorb was UNCONDITIONAL, so it could not tell that transient gap apart from a
     * PERMANENT one -- a browser whose scope was taken while it stayed mounted -- and the same line
     * became an infinite input sink. Every touch affordance here is behind the same scope guard, so both
     * input paths died together. This flag is the discriminator: `false` means "has never mounted,
     * absorb"; `true` plus a refusing [dispatch] means "cannot act on this action and never will, so
     * never absorb".
     *
     * **Monotonic on purpose.** Resetting it in [close] would make a closed controller indistinguishable
     * from an unmounted one, and a closed controller left reachable is precisely the hazard `MimirAppHost`
     * already guards with its own wrapping `DisposableEffect` ("does not leave a closed controller
     * sitting in the store still reporting the overlay as active"). If that guard ever fails, this
     * polarity makes the shell REFUSE rather than swallow -- the direction that keeps a defect
     * recoverable. The cost is one leaked press if the same instance were ever re-opened after a close,
     * a sequence no caller performs: `FileBrowserSurface`'s `DisposableEffect` is keyed on the controller
     * and the store nulls its reference on the same teardown.
     *
     * Public because the reader is `app/`'s `MimirOverlayCoordinator`, in another module. That grows
     * this class's deliberately small public surface by one read-only property, disclosed rather than
     * silent -- the alternative the shell can already see, "are any keys registered in [scope]", is an
     * inference from an invariant ([load] always registering something) rather than a statement of the
     * fact itself, and would fail silently the day a directory legitimately registers nothing.
     */
    var hasOpened: Boolean = false
        private set

    /** Enter [scope] and load [rootPath]. Call once per mount -- [FileBrowserSurface] already does this. */
    fun open() {
        stack.clear()
        focusByDirectory.clear()
        // A first mount has no previous listing to have arrived from, so it must not glide -- cleared
        // alongside the other two per-session structures rather than merely relying on the field's
        // initial value, so a re-opened instance behaves like a fresh one.
        navigationMotion = null
        // F-08/F-01/F-06: a re-opened instance must not inherit the previous session's search, queue OR
        // extract-chooser screen, whose scope would then sit ABOVE the browse scope this line is about to
        // push. The destination pick is cleared alongside them and NOT through [endDestinationPick]:
        // that helper reloads to rebuild the commit row, and there is nothing to rebuild yet -- [load]
        // below is about to register the whole graph from scratch.
        closeQueue()
        closeSearch()
        closeExtractChooser()
        // G2: and the transfer chooser, on the identical argument. A fifth screen is a fifth scope that
        // must not survive a re-open.
        closeTransferChooser()
        // F-1. SEVENTH screen, same mutual exclusion, held HERE rather than at a call site -- a re-opened
        // instance must not inherit the previous session's overlay, whose scope would sit ABOVE the
        // browse scope this function is about to push.
        closeActionOverlay()
        closeRename()
        // Policy 2026-08-16. SIXTH screen, same mutual exclusion, and the one that matters
        // most: it holds an undelivered decision about a move. Closing it abandons that move, which
        // is the fail-closed direction -- a warning that survived into a new session would commit a
        // transfer whose subject and destination came from a session that is over.
        closeDiscSetPrompt()
        // A re-opened instance starts on images only, with nothing remembered of the last session's folder.
        imagePickShowsAll = false
        imagePickHiddenCount = 0
        unfilteredPartition = null
        // Policy 2026-08-16. A FIFTH mutually-exclusive one-deep stack, unwound on the same
        // argument as the four above: left pushed, [scope] is buried and nothing below is popped.
        closeDestinationList()
        pickSession = null
        authority.pushScope(scope)
        // Set BEFORE load(): load() is where this browser's focus slots register, and a registration can
        // synchronously drive a FocusState emission that reaches a collector. Anything that reads this
        // flag off that emission must already see the mount, not a half-open browser.
        hasOpened = true
        load(rootPath)
    }

    /**
     * Leave [scope]: pop the scope if still active, then unregister every virtual focus slot. Idempotent.
     *
     * **Pop BEFORE clear, deliberately -- the order is load-bearing (fixed 2026-08-14).** Clearing first
     * unregisters the focused key while [scope] is still active, and `DefaultFocusAuthority` answers that
     * by falling focus onto the nearest surviving sibling
     * (`DefaultFocusAuthority.removeEntryAndFallback`). That sibling is then unregistered too, and so on:
     * one focus change, one `FocusState` emission and one focus-memory write PER REMAINING TILE, cascading
     * the length of the listing on the way out of a large directory. It also left the scope's remembered
     * focus pointing at whichever tile the cascade happened to stop on -- a tile that no longer exists --
     * instead of the one the player was actually standing on, corrupting the focus memory
     * [FocusAuthority.pushScope] would read on a later re-open.
     *
     * Popping first makes [scope] inactive, so `_state.value.focused` is a key in some OTHER scope
     * throughout the clear and the fallback branch simply never matches. No cascade, no emissions, and
     * the remembered key is the real one. Note this is NOT done with `clearFocus()`: on the second,
     * idempotent call the active scope is already the parent's, and clearing there would null the
     * PARENT's focus and park the cleared-sentinel in its memory.
     */
    fun close() {
        // F-08/F-01: unwind the whole stack this controller may have pushed, innermost first. A teardown
        // can arrive at any moment -- `closeIfCurrent`, a volume switch, an external overlay taking the
        // screen -- including while search and its keyboard are both up, or while the queue screen is. If
        // any of them is left pushed, [scope] is buried, the guard below matches nothing, NOTHING is
        // popped, and the scope stack stays corrupted for whatever surface mounts next. Idempotent at
        // every level, so unwinding both branches unconditionally costs nothing.
        //
        // Queue and search cannot both be open (the only path to `OpenQueue` is the BROWSE dispatch
        // table), so this is not a nesting order so much as two mutually exclusive one-deep stacks, both
        // unwound rather than the one we think is up.
        closeQueue()
        closeSearch()
        // F-06. Third mutually-exclusive one-deep stack, unwound on the same argument. The pending
        // destination pick dies with the browser too: a teardown mid-pick is an abandoned pick, and
        // leaving it set would let a re-opened instance answer [commitPurpose] with EXTRACT_DESTINATION
        // for an archive from a session that is over. Assigned directly rather than through
        // [endDestinationPick] for the reason [open] states -- the reload that helper exists for would be
        // registering a graph into a scope this function is about to tear down.
        closeExtractChooser()
        // G2. Fourth such stack. A move mid-pick that survived a teardown would be worse than an extract
        // mid-pick surviving one: the re-opened instance's commit row would answer MOVE_DESTINATION for a
        // file from a session that is over. Undo (2026-08-16) does not soften this: it walks back a move
        // the player MADE, not one a stale chooser made on their behalf while they were elsewhere.
        closeTransferChooser()
        // F-1. SEVENTH screen, same mutual exclusion. A teardown mid-overlay would otherwise leave
        // [actionOverlayScope] buried on the real scope stack with nothing below ever popping it.
        closeActionOverlay()
        closeRename()
        // Policy 2026-08-16. SIXTH screen, same mutual exclusion, and the one that matters
        // most: it holds an undelivered decision about a move. Closing it abandons that move, which
        // is the fail-closed direction -- a warning that survived into a new session would commit a
        // transfer whose subject and destination came from a session that is over.
        closeDiscSetPrompt()
        // Policy 2026-08-16. A FIFTH mutually-exclusive one-deep stack, unwound on the same
        // argument as the four above: left pushed, [scope] is buried and nothing below is popped.
        closeDestinationList()
        pickSession = null
        // A listing still in flight must not land on a torn-down browser: [applyListing] writes focus into
        // [scope], and this function is about to pop it. [hasListed] resets with it so a re-opened instance
        // gets its synchronous first load back -- see that field's KDoc.
        supersedePendingLoad()
        hasListed = false
        if (authority.state.value.scope == scope) authority.popScope()
        tracker.clear(authority)
        // [hasOpened] is deliberately NOT cleared here -- see its KDoc. A closed controller must not
        // become indistinguishable from one that never mounted, because those two states want opposite
        // answers from the shell's absorb.
    }

    /**
     * One controller action in, `true` always out (this is a hard modal -- see [fileBrowserCommand]). A
     * stray action reaching this controller while [scope] is not active is refused rather than acted on.
     */
    fun dispatch(action: ControllerAction): Boolean {
        // Rename's keyboard, ahead of every other arm for the search keyboard's reason below: it is a hard
        // modal and answers true unconditionally. B abandons the rename (Done is what commits it), and the
        // arm absorbs even the frame before the binding has entered its scope, because letting that press
        // fall through would move the listing underneath the keyboard.
        if (renameOpen) {
            if (authority.state.value.scope == MimirKeyboardFocusScope) {
                val binding = renameKeyboard
                val handled = binding != null && dispatchMimirKeyboardInput(
                    authority = authority,
                    scope = MimirKeyboardFocusScope,
                    action = action,
                    binding = binding,
                    onDismiss = ::closeRename,
                )
                if (!handled && (action == ControllerAction.CANCEL || action == ControllerAction.SYSTEM_BACK)) {
                    closeRename()
                }
            } else if (action == ControllerAction.CANCEL || action == ControllerAction.SYSTEM_BACK) {
                closeRename()
            }
            return true
        }
        // F-08 routes THREE scopes through this one entry point, innermost first, so `app/`'s
        // `dispatchFolderBrowser` branch needs no change at all: it still calls exactly this function and
        // still gets `false` for exactly one reason (nothing this browser owns is the active scope).
        //
        // **The keyboard arm returns `true` unconditionally, and that is load-bearing.**
        // `dispatchMimirKeyboardInput` answers `false` for TERTIARY, for an unwired trigger, and for a
        // CONFIRM whose focused key has no registered activator. Letting that `false` fall through to the
        // browse guard below would make this whole function return false while a KEYBOARD is on screen --
        // and `dispatchFolderBrowser`'s last arm then refuses, so the press leaves the overlay layer and
        // moves the destination underneath. That is the P0 class this browser already shipped once. A
        // modal absorbs; see [fileBrowserCommand]'s own `Consumed`.
        if (searchKeyboardOpen && authority.state.value.scope == MimirKeyboardFocusScope) {
            val binding = searchKeyboard
            val handled = binding != null && dispatchMimirKeyboardInput(
                authority = authority,
                scope = MimirKeyboardFocusScope,
                action = action,
                binding = binding,
                onDismiss = ::closeSearchKeyboard,
            )
            // The one escape hatch. If the binding has not been attached yet (a mount frame) or a key had
            // no activator, CANCEL must still work -- an absorbing branch with no way out is the other
            // half of that same P0.
            if (!handled && (action == ControllerAction.CANCEL || action == ControllerAction.SYSTEM_BACK)) {
                closeSearchKeyboard()
            }
            return true
        }
        if (searchOpen && authority.state.value.scope == searchScope) return dispatchSearch(action)
        // F-01. Same shape and same position in the chain as the search arm: innermost scope first, and
        // it absorbs unconditionally because the queue screen is no more a hole in this hard modal than
        // the search screen is.
        if (queueOpen && authority.state.value.scope == queueScope) return dispatchQueue(action)
        // F-06. Same shape and same position in the chain as the search and queue arms, and the position
        // is what makes CANCEL close the chooser before it can ever mean Up. The DESTINATION PICK has no
        // arm here on purpose: it runs in the browse scope, so it is the browse table below that grows a
        // level -- see fileBrowserCommand's CANCEL branch.
        if (extractChooserOpen && authority.state.value.scope == extractScope) return dispatchExtract(action)
        // G2. Same shape and same position in the chain as the extract arm, and the position is what makes
        // CANCEL close the transfer chooser before it can ever mean Up. The transfer DESTINATION PICK has
        // no arm here either, for the identical reason: it runs in the browse scope.
        if (transferChooserOpen && authority.state.value.scope == transferScope) {
            return dispatchTransfer(action)
        }
        // F-1. Same shape and same position in the chain as extract/transfer -- a sixth mutually-exclusive
        // one-deep stack, never nested with any of the other five: activateFocusedActionOverlayOption
        // closes this scope BEFORE opening a chooser or a pick, sequentially rather than by nesting one
        // scope under another, so at most one of these six checks ever matches at once. See that
        // function's own KDoc for why sequential-close-then-open is the deliberate shape here rather than
        // the DiscSet/destination-list nesting one.
        if (actionOverlayOpen && authority.state.value.scope == actionOverlayScope) {
            return dispatchActionOverlay(action)
        }
        // Policy 2026-08-16. Innermost of all: this screen opens ON TOP of a destination pick that
        // is already live in the browse scope, so it must absorb before the browse table below can read
        // CANCEL as Up and walk the player out of a folder they were only being asked about.
        if (destinationListOpen && authority.state.value.scope == destinationScope) {
            return dispatchDestination(action)
        }
        // The multi-disc warning is innermost of all, above even the destination list:
        // it opens on the press that would have started the move, after the pick has already ended, so
        // whatever is beneath it is a live browse listing with no idea a decision is outstanding. A press
        // that fell through would move focus on a listing the player cannot see.
        if (discSetPromptOpen && authority.state.value.scope == discSetScope) {
            return dispatchDiscSet(action)
        }

        if (authority.state.value.scope != scope) return false
        val commitKey = commitKeyForCurrentMode()
        // Null unless the pinned row BOTH exists and holds focus -- one value carrying both facts, so a
        // caller cannot pass "focused" without "what it commits". See FileBrowserCommitPurpose's KDoc.
        val focusedCommitPurpose = commitPurposeForCurrentMode()
            ?.takeIf { commitKey != null && authority.state.value.focused == commitKey }
        // F-2. backKey mirrors commitKey's own read one line up; focusedBackToOrigin mirrors
        // focusedCommitPurpose's -- one value for "does it exist", a plain boolean (not an enum) for
        // "does it currently hold focus", because this row has exactly one meaning, unlike the commit
        // row's four purposes.
        val backKey = backToOriginKeyForCurrentMode()
        val focusedBackToOrigin = backKey != null && authority.state.value.focused == backKey
        // F-1. Bound once and reused below (both directly and inside fileBrowserActionsAvailable) rather
        // than re-read at each use -- focusedTile()/destinationPickPhase() are cheap and pure, but a
        // second call is a second place the two reads could observe different snapshot state were either
        // ever changed to do more than a plain lookup. Named `pickPhase`, NOT `destinationPick` -- this
        // class already has a property of that name ([destinationPick], the open EXTRACT pick itself,
        // `FileBrowserDestinationPick?`) and a local of the same name would shadow it for the rest of this
        // function, including inside the `when (command)` block below.
        // A row held for a pending delete is a label for what the player just did, not a thing: to every verb it is
        // no tile at all, so a press on it opens, selects and offers nothing.
        val focused = focusedTile()?.takeUnless { it.deleting }
        val pickPhase = destinationPickPhase()
        return when (
            val command = fileBrowserCommand(
                action = action,
                focused = focused,
                atRoot = atRoot,
                focusedCommitPurpose = focusedCommitPurpose,
                focusedBackToOrigin = focusedBackToOrigin,
                backToOriginAvailable = backKey != null,
                searchAvailable = searchAvailable,
                // F-1. One predicate for SELECT's own door and SECONDARY's quiet-down (R-13) --
                // fileBrowserActionsAvailable's own KDoc explains why this is computed once here rather
                // than re-derived at each of its call sites (SELECT, SECONDARY, the footer legend, the
                // inspector's re-pointed buttons).
                actionsAvailable = fileBrowserActionsAvailable(
                    focused = focused,
                    manageMode = mode == FileBrowserMode.MANAGE,
                    deleteAvailable = deleteAvailable,
                    transferChoiceAvailable = transferChoiceAvailable,
                    renameAvailable = renameAvailable,
                    destinationPick = pickPhase,
                ),
                queueAvailable = queueAvailable,
                extractChoiceAvailable = extractChoiceAvailable,
                transferChoiceAvailable = transferChoiceAvailable,
                destinationPick = pickPhase,
                // W-1. The same read `FileBrowserStore.clearSession` finalises from, so the press that
                // ends reversibility and the branch that marks it cannot disagree about what is held.
                heldDeleteCount = heldDeleteCount,
                // R-21: CHOOSE_SOURCE is the only purpose where a focused file is committable.
                sourcePickAvailable = commitPurpose == FileBrowserCommitPurpose.CHOOSE_SOURCE,
                // PICK_IMAGE (2026-09-11): the focused FILE is committable iff it is an image -- the same
                // pure gate the footer legend and the tile's own dimming read, so the press, the verb and
                // the drawn state cannot disagree. False in every other mode by construction
                // (fileBrowserFileChoosable tests the mode first), so PICK and MANAGE are untouched.
                imagePickAvailable = focused != null && fileBrowserFileChoosable(mode, focused.name),
            )
        ) {
            is FileBrowserCommand.Move -> {
                authority.moveFocus(command.action)
                true
            }
            is FileBrowserCommand.Page -> {
                val target = fileBrowserPageTarget(
                    rows = fileBrowserFocusRows(partition, grid, commitKey, backKey),
                    focused = authority.state.value.focused,
                    forward = command.forward,
                    // Same partition, same commit-row presence, so the pitches line up row-for-row with
                    // the rows above -- the contract [fileBrowserRowPitchesDp] documents and cannot
                    // enforce. Built here rather than inside the target function so the row model stays
                    // one list of keys, exactly as every other caller reads it. backKey changes no pitch
                    // -- F-2's sibling shares the commit row's single pitch entry, never adding one.
                    rowPitchDp = fileBrowserRowPitchesDp(partition, hasCommitRow = commitKey != null, grid),
                    // RC-12: the SAME grid the rows/pitches above were built from -- see
                    // fileBrowserPageTarget's own @param grid for why a mismatched one would measure a
                    // screenful against the wrong viewport.
                    grid = grid,
                )
                // Null at an edge (already clamped, or nothing focused): issue no requestFocus at all,
                // so holding L1 at the top of a listing does not re-emit the same FocusState per repeat.
                target?.let(authority::requestFocus)
                true
            }
            FileBrowserCommand.FocusBackToOrigin -> {
                // backKey read at the top of this call -- see FileBrowserCommand.FocusBackToOrigin's own
                // KDoc for why this is a direct requestFocus rather than authority.moveFocus.
                backKey?.let(authority::requestFocus)
                true
            }
            FileBrowserCommand.FocusCommitRow -> {
                commitKey?.let(authority::requestFocus)
                true
            }
            is FileBrowserCommand.Enter -> {
                // Remember where the player was standing BEFORE descending, so backing out of the child
                // lands them here again rather than at index 0 -- see [focusByDirectory].
                rememberFocusInCurrentDirectory()
                // Before the push, not after: the pending navigation's undo is positional, so running it
                // afterwards takes back THIS press's mutation instead of the abandoned one. See
                // [supersedePendingLoad].
                supersedePendingLoad(by = FileBrowserLoadKind.ENTER)
                stack.addLast(currentPath)
                // Armed BEFORE load(), so the recomposition that load() triggers by reassigning
                // [partition] can never observe a new listing next to the PREVIOUS navigation's motion.
                armNavigationGlide(command.path, FileBrowserGlideEnter)
                load(
                    command.path,
                    onSuperseded = { stack.removeLastOrNull() },
                    kind = FileBrowserLoadKind.ENTER,
                )
                true
            }
            FileBrowserCommand.Up -> {
                // Symmetrical with Enter: a directory the player leaves upward is one they may come back
                // down into, and its own place is worth keeping too.
                rememberFocusInCurrentDirectory()
                // Before the pop, for [FileBrowserCommand.Enter]'s reason and one more: an Enter still in
                // flight has pushed an entry this pop would otherwise consume, sending the player up two
                // levels for one press -- and then the Enter's own undo would pop a THIRD.
                supersedePendingLoad(by = FileBrowserLoadKind.UP)
                stack.removeLastOrNull()?.let { parent ->
                    armNavigationGlide(parent, FileBrowserGlideReturn)
                    // The undo registered here is deliberately NOT run when the next press is also an Up
                    // -- see [pendingLoadKind]'s table. It exists for Up-then-Enter, where the ascent
                    // never happened and leaving the entry consumed silently skips a level.
                    load(parent, onSuperseded = { stack.addLast(parent) }, kind = FileBrowserLoadKind.UP)
                }
                true
            }
            FileBrowserCommand.Close -> {
                close()
                onDismiss()
                true
            }
            FileBrowserCommand.ReviewHeldDeletes -> {
                // W-1. Nothing is finalised here and nothing is closed: the player is shown the payload
                // his exit was about to release, on the screen that already carries Undo and "Keep
                // deleted", and the exit itself is the NEXT press -- see [FileBrowserQueuePurpose] and
                // [dispatchQueue]'s CANCEL arm.
                openQueue(FileBrowserQueuePurpose.EXIT_REVIEW)
                true
            }
            // **R-21.** The same close-then-report shape as CommitSource below, with one difference that
            // is the whole ticket: the path is the TILE's, not `currentPath`. A player standing on one
            // game in a folder of two hundred adds that game, not the two hundred.
            is FileBrowserCommand.CommitSourceFile -> {
                val chosen = command.path
                close()
                onChoose(chosen)
                true
            }

            FileBrowserCommand.CommitSource -> {
                // currentPath read HERE, not earlier: a player who navigated deeper before committing
                // must have the directory they are actually standing in reported, never the directory
                // that happened to be current when this dispatch call started (they are the same value
                // for this call, since navigation and commit can't both happen from one input, but
                // reading it fresh at the point of use -- rather than threading it through the command,
                // which carries none -- keeps that true by construction, not by coincidence).
                val chosen = currentPath
                close()
                onChoose(chosen)
                true
            }
            FileBrowserCommand.CommitExtractDestination -> {
                // F-06. The archive comes from the PICK, never from the ring: the commit row is what
                // holds focus at this instant, so focusedTile() is null anyway, and a browser that
                // recovered the archive from the listing would extract whatever the player last stood on.
                //
                // Both values are read BEFORE the pick is ended, because [endDestinationPick] reloads --
                // and the reload is not optional. This press changes what commitKeyForCurrentMode()
                // answers while the player is standing still, which no previous state change in this
                // class could do: navigation was the only thing that moved the gate, and navigation
                // always reloaded. Skip it and MANAGE keeps a registered, focusable, advertised commit
                // row whose gate has already said it does not exist -- UI-05 exactly, arriving by a door
                // UI-05's fix does not cover.
                val pick = destinationPick
                val destination = currentPath
                endDestinationPick()
                pick?.let { onExtractTo?.invoke(it.archivePath, destination) }
                true
            }
            // G2. Byte-for-byte the extract branch's shape, twice, and the duplication is the point rather
            // than something to factor out: each branch names ONE sink, so no arm can reach the other's.
            // The subject comes from the PICK for the reason the extract branch states -- and here it is
            // sharper still: a subject recovered from the ring would relocate a file the player never
            // chose. Undo (2026-08-16) is not the answer to that -- it is a button on a card for a move
            // the player is aware of, and nobody presses Undo on a move they did not know happened.
            //
            // The read-before-end ordering is also the extract branch's, and mandatory for its reason:
            // [endDestinationPick] reloads, and this press is one of the three that changes what
            // commitKeyForCurrentMode() answers while the player is standing still.
            FileBrowserCommand.CommitMoveDestination -> {
                val subject = transferPickSubject(FileBrowserTransferKind.MOVE)
                val destination = currentPath
                endDestinationPick()
                // [beginMove], never onMoveTo directly -- see that function for why the disc-set warning
                // is one gate rather than a check at each of the two commit paths.
                subject?.let { beginMove(it, destination) }
                true
            }
            FileBrowserCommand.ToggleImageFilter -> {
                toggleImagePickFilter()
                true
            }
            FileBrowserCommand.CommitCopyDestination -> {
                val subject = transferPickSubject(FileBrowserTransferKind.COPY)
                val destination = currentPath
                endDestinationPick()
                subject?.let { onCopyTo?.invoke(it, destination) }
                true
            }
            FileBrowserCommand.BackToChooser -> {
                abandonDestinationPick()
                true
            }
            is FileBrowserCommand.Extract -> {
                onExtract(command.path)
                true
            }
            is FileBrowserCommand.OfferExtract -> {
                offerExtract(command.path)
                true
            }
            is FileBrowserCommand.OfferTransfer -> {
                openTransferChooser(command.path)
                true
            }
            FileBrowserCommand.OpenSearch -> {
                openSearch()
                true
            }
            is FileBrowserCommand.OpenActionOverlay -> {
                // F-1/R-3. SELECT no longer deletes directly -- it opens the menu Delete now lives inside,
                // alongside Move/Copy/Extract as the tile's kind allows. See
                // activateFocusedActionOverlayOption for where onDelete now actually fires (Delete's own
                // row, reported-never-performed exactly as [FileBrowserCommand.Extract]'s precedent
                // states: staging, the Undo handle and the queue entry all belong to `FileBrowserStore`).
                openActionOverlay(command.path, command.kind)
                true
            }
            FileBrowserCommand.OpenQueue -> {
                openQueue()
                true
            }
            FileBrowserCommand.Consumed -> true
        }
    }

    /**
     * A UI-originated tap on [tile] -- **one tap activates it.** Exactly what `CONFIRM` does with that
     * tile focused: a directory opens, an archive or a plain file takes focus and fills the inspector
     * (`CONFIRM` on either is [FileBrowserCommand.Consumed], so the activation is the focus).
     *
     * ## What changed on 2026-08-14, and why the old rule carried no authority
     *
     * This used to take a `doubleTap: Boolean`: one tap moved focus, a second opened. That was
     * transcribed from the adoption spec's *"Single tap inspects. Double tap or Enter opens"* -- a
     * desktop/web file-manager idiom, from a document that rejects `tabIndex`/`onKeyDown` as web
     * artefacts three lines later. Two independent findings retired it:
     *
     *  1. **The WebView constraint does not apply to Compose.** Compose dispatches pointer and key input
     *     separately; touch never needed to imitate a gamepad's focus-then-activate.
     *  2. **The property this KDoc claimed to protect was never implemented.** It said the double tap
     *     existed so touch and controller *"can never disagree about what opening a tile means"* -- while
     *     `CONFIRM` on a directory opened it in ONE press and touch needed TWO. They disagreed about the
     *     COST the whole time; only the meaning ever matched. Nothing was given up by deleting it.
     *
     * **And it was not free.** The gesture that fed it -- `detectTapGestures`' double-tap callback,
     * named exactly and forbidden by `FileBrowserTouchGrammarTest`, which is why this KDoc spells it in
     * prose instead -- makes Compose defer EVERY single tap until a second down fails to arrive.
     * Verified in this tree's own `androidx.compose.foundation:foundation-android:1.7.3` bytecode:
     * `detectTapGestures$2$1` offset 569 reads that field, and only when it is NULL does offset 576 fire
     * `onTap` on the up event; non-null branches to 610 -> `awaitSecondDown` ->
     * `withTimeoutOrNull(getDoubleTapTimeoutMillis())`. That is the platform default, ~300ms, before
     * ANYTHING happened -- including the focus ring, which then takes another 120ms to animate. Removing the binding restores immediate tap feedback; keeping it as a compatibility alias would have preserved the whole defect while appearing to
     * address it.
     *
     * ## The invariant that replaced "one true path"
     *
     * **PER-ELEMENT ACTION PARITY** -- an element's action *set*, and the meaning of each action, are
     * identical whichever input triggered them; the *grammar of triggering* may differ, and any asymmetry
     * must be declared. This function is stricter than it needs to be about that: it does not re-derive
     * what a tap means, it calls [dispatch] with `CONFIRM`, so there is exactly one decision table
     * ([fileBrowserCommand]) and no second place to forget a gate. The scope guard is
     * [handleCommitTap]'s, unchanged and load-bearing -- see [FileBrowserControllerMountStateTest].
     *
     * Adding a single game is decided in [fileBrowserCommand], so tap-to-add and CONFIRM-to-add
     * share one decision without another touch-specific branch.
     *
     * The remaining asymmetry is narrower and still declared: a file is committable **only** while
     * `sourcePickAvailable`, so in MANAGE and in any destination pick it is exactly as inert as it was.
     */
    internal fun handleTap(tile: FileBrowserTile) {
        if (authority.state.value.scope != scope) return
        authority.requestFocus(FileBrowserFocus.tileKey(tile.path))
        dispatch(ControllerAction.CONFIRM)
    }

    /**
     * A UI-originated long-press on [tile] -- the tile's **SECONDARY**, which in this browser means
     * "Extract" on an archive and nothing on anything else.
     *
     * **This is the parity half of the tap change, not a new feature.** Before it, extract was reachable
     * only by a controller's X button: on a touch-only session the archive tiles were inert and the
     * extract chooser this module builds was unreachable. Long-press is deliberately narrow -- it maps to
     * `SECONDARY` because that is the action the surface already binds, and to nothing where the surface
     * binds none. **It never maps to `SYSTEM_SELECT` (delete),** which is why this change leaves the
     * destructive path exactly as gesture-unbound as it was.
     *
     * Every gate lives in [fileBrowserCommand]'s `SECONDARY` arm and none is restated here: inert unless
     * `extractable`, swallowed while a destination pick is open, and [FileBrowserCommand.OfferExtract]
     * only when the mounting caller wired both sinks. An extraction writes new files and removes none, so
     * it is available in [FileBrowserMode.PICK] too -- the same reasoning [FileBrowserCommitPurpose]
     * states, not a new exception.
     *
     * **Costs no tap latency**, which is the whole reason it is `onLongPress` on the existing
     * `detectTapGestures` rather than `Modifier.combinedClickable`. Same bytecode read as above: the
     * long-press timeout bounds a `withTimeout` around `waitForUpOrCancellation`, so a finger that leaves
     * early falls straight through to `onTap` at offset 576. `combinedClickable` would have been the
     * worse mechanism for a second reason -- `AbstractClickableNode` constructs a `FocusableNode`, which
     * delegates to `FocusTargetModifierNode()`, so it plants a Compose focus target beside this module's
     * single-FocusAuthority one. Read from the same artifact.
     */
    internal fun handleLongPress(tile: FileBrowserTile) {
        if (authority.state.value.scope != scope) return
        authority.requestFocus(FileBrowserFocus.tileKey(tile.path))
        dispatch(ControllerAction.SECONDARY)
    }

    /**
     * **F-1/R-13.** The inspector's Extract…/Place… buttons (`FileBrowserSurface.kt`) — re-pointed here so
     * both route through one function rather than two independently-edited lambdas that could drift.
     *
     * **In MANAGE**, once the action overlay has at least one legal row for the focused tile
     * ([fileBrowserActionsAvailable]), these buttons now open it instead of acting directly — the same
     * verb, one door later, matching X's own retirement in [fileBrowserCommand]'s `SECONDARY` arm.
     * **In PICK**, [fileBrowserActionsAvailable] is false by construction (the overlay never opens there —
     * see its own KDoc), so this falls through to the SAME `dispatch(SECONDARY)` these two buttons always
     * called, unchanged: PICK's only path to Extract stays exactly as live as it was before this ticket.
     *
     * **Without this fallback the buttons would die silently in PICK** the moment X's controller binding
     * went quiet in MANAGE — the failure mode this ticket was explicitly told to guard against, since both
     * buttons are gated on `extractLive`/`transferAvailable` in the surface, never on mode, so nothing else
     * would stop them from being drawn, pressed, and answered with nothing.
     *
     * A live read, matching [handleTap]/[handleLongPress]'s own discipline: this fires at the moment of a
     * real press, so [focusedTile]'s synchronous value is the correct one here, unlike the surface's own
     * footer legend, which must read its collected snapshot instead — see
     * [fileBrowserActionsAvailable]'s KDoc for why the two call sites intentionally do not share one
     * cached answer.
     */
    internal fun handleInspectorSecondaryTap() {
        // **D-3 collapsed this, and the collapse is the point.** F-1 had to choose here -- SELECT when the
        // overlay was available, SECONDARY when it was not -- because the two doors were on two buttons
        // and this seam had to know which. After the action binding moved the overlay onto X, one
        // button owns both branches: SECONDARY opens the overlay when there is one, and falls through to
        // the legacy extract/transfer arms when there is not. The dispatch table already decides that, so
        // deciding it a second time here would be the duplicate-rule shape this file keeps removing.
        //
        // Left as its own function rather than inlined at the two call sites: the inspector's buttons
        // dying silently when the binding under them moved is exactly what F-1's spec warned about, and a
        // named seam is what makes the next move survivable.
        dispatch(ControllerAction.SECONDARY)
    }

    /** The inspector's Extract button: use the extract door directly, without opening the action menu. */
    internal fun handleInspectorExtractTap() {
        if (authority.state.value.scope != scope) return
        if (actionOverlayOpen || extractChooserOpen || transferChooserOpen || destinationListOpen ||
            discSetPromptOpen || queueOpen || searchOpen || destinationPickPhase() != null
        ) return
        val tile = focusedTile()?.takeIf { it.extractable && !it.deleting } ?: return
        if (extractChoiceAvailable) offerExtract(tile.path) else onExtract(tile.path)
    }

    /**
     * A UI-originated tap on [FileBrowserMode.PICK]'s pinned commit affordance. A single tap commits
     * directly -- there is no "inspect first" step for a control that IS the action. **This row was
     * always the odd one out and is now the norm:** an earlier version of this KDoc opened with *"Unlike
     * [handleTap]'s directory case"*, because tiles then needed two taps and this row needed one. As of
     * the 2026-08-14 touch-grammar change every tap in this browser activates, so the contrast is gone
     * and this row's behaviour is unchanged -- it is the tiles that came to meet it.
     *
     * Focuses the affordance first so touch and controller can never disagree about what was focused at
     * the moment of commit, then reuses [dispatch]'s own CONFIRM path -- the same single-decision-table
     * discipline [handleTap] follows.
     */
    internal fun handleCommitTap() {
        if (authority.state.value.scope != scope) return
        val commitKey = commitKeyForCurrentMode() ?: return
        authority.requestFocus(commitKey)
        dispatch(ControllerAction.CONFIRM)
    }

    /**
     * A UI-originated tap on F-2's "Back to origin" affordance — [handleCommitTap]'s own shape, exactly,
     * for its own reason: focusing first means touch and controller can never disagree about what was
     * focused at the moment CONFIRM ran, which is what routes it to [FileBrowserCommand.BackToChooser]
     * regardless of [destinationPickPhase] (see that command's own KDoc).
     */
    internal fun handleBackToOriginTap() {
        if (authority.state.value.scope != scope) return
        val backKey = backToOriginKeyForCurrentMode() ?: return
        authority.requestFocus(backKey)
        dispatch(ControllerAction.CONFIRM)
    }

    /** The inspector's "Verify" button. Reports the focused tile's path to [onVerifyChecksum]; computes
     * nothing itself -- see the class KDoc's "owed" list. No-op if nothing is focused. */
    fun verifyFocusedChecksum() {
        focusedTile()?.takeUnless { it.deleting }?.let { onVerifyChecksum(it.path) }
    }

    // ───────────────────────────────────────────────────────────────── F-08 · the search session ──

    /**
     * Open the search screen. Idempotent.
     *
     * **What actually determines the landing is the explicit `requestFocus` on the last line, and an
     * earlier draft of this KDoc said otherwise.** It claimed the register-then-remember-then-push
     * ORDER was load-bearing, on the reasoning that `DefaultFocusAuthority.pushScope` seeds from
     * `focusMemory[scope]` only `takeIf { entries[it]?.scope == scope }` -- so an unregistered key is
     * silently rejected and the landing falls back to `entries.firstOrNull { it.value.scope == scope }`.
     * All true, and all irrelevant here: a differential that pushed BEFORE registering left every test
     * green, because the explicit `requestFocus` below overrides whatever `pushScope` seeded. The order
     * is kept as defence in depth -- it is also the idiom `MimirKeyboardFocusBinding.enter` uses -- but
     * it is documented as such rather than as the mechanism.
     *
     * A fresh query and `ALL` on every open, deliberately: a remembered query would silently re-walk the
     * volume the moment the screen appeared, which is exactly the un-asked-for walk the debounce and
     * [fileBrowserSearchLaunches] exist to prevent.
     */
    fun openSearch() {
        if (searchOpen) return
        // **Found adjacent to F-06, and it was a live defect rather than a hypothetical.**
        // `FileBrowserSurface`'s own header-tap comment asserts that *"`openSearch` already closes the
        // queue on its own side"* -- it did not. Through DISPATCH the claim held for the reason that
        // comment gives (the only press reaching OpenSearch is a TERTIARY in the browse table, where the
        // queue cannot be open), but the header stays composed ABOVE the queue screen, so tapping the
        // magnifier from the queue pushed searchScope on top of queueScope and left two screens' graphs
        // alive at once -- the exact arrangement the queue-replaces-browse contract
        // exists to prevent. The chooser is closed here for the identical reason, ahead of it having a
        // chance to be reached that way. Both idempotent; both no-ops on the dispatch path.
        closeQueue()
        closeExtractChooser()
        // G2: and the transfer chooser, on the identical argument -- the header magnifier stays composed
        // above it, so a tap from the chooser would push searchScope on top of transferScope and leave
        // two screens' graphs alive at once.
        closeTransferChooser()
        // F-1: and the action overlay, on the identical argument -- the header magnifier stays composed
        // above it too, so a tap from the overlay would push searchScope on top of actionOverlayScope and
        // leave two screens' graphs alive at once.
        closeActionOverlay()
        closeRename()
        // Policy 2026-08-16. SIXTH screen, same mutual exclusion, and the one that matters
        // most: it holds an undelivered decision about a move. Closing it abandons that move, which
        // is the fail-closed direction -- a warning that survived into a new session would commit a
        // transfer whose subject and destination came from a session that is over.
        closeDiscSetPrompt()
        searchOpen = true
        searchQuery = ""
        searchFilter = FileSearchFilter.ALL
        searchResults = emptyList()
        searchPhase = FileBrowserSearchPhase.IDLE
        searchKeyboardOpen = false
        searchTracker.update(authority, searchScope, emptyList())
        authority.rememberFocusForScope(searchScope, FileBrowserSearchFocus.QueryKey)
        authority.pushScope(searchScope)
        authority.requestFocus(FileBrowserSearchFocus.QueryKey)
    }

    /**
     * Leave search for the listing the player came from. Idempotent.
     *
     * **Pop before clear, and the keyboard before either** -- the same ordering [close] documents at
     * length. Clearing the search registrations while [searchScope] is still active would unregister the
     * focused result, which `DefaultFocusAuthority.removeEntryAndFallback` answers by falling focus onto
     * the nearest surviving sibling; that one is then unregistered too, cascading one `FocusState`
     * emission per remaining result on the way out of a long result list. Popping first makes the browse
     * scope active, so the fallback branch never matches.
     *
     * Nothing here restores browse focus by hand: `popScope` restores `focusMemory[files]`, which is the
     * tile the player was standing on when they opened search. Their place is preserved.
     */
    fun closeSearch() {
        if (!searchOpen) return
        closeSearchKeyboard()
        if (authority.state.value.scope == searchScope) authority.popScope()
        searchTracker.clear(authority)
        searchOpen = false
        searchResults = emptyList()
        searchPhase = FileBrowserSearchPhase.IDLE
    }

    /** Raise the on-screen keyboard over the search screen -- the only way a query is ever typed. */
    internal fun openSearchKeyboard() {
        if (!searchOpen) return
        searchKeyboardOpen = true
    }

    /** Lower the keyboard. `exit()` is called defensively as well as by `MimirKeyboard`'s own
     * `DisposableEffect`; see [searchKeyboard] for why both. */
    internal fun closeSearchKeyboard() {
        if (!searchKeyboardOpen) return
        searchKeyboardOpen = false
        searchKeyboard?.exit()
    }

    /** [FileBrowserSurface] hands down the `@Composable`-created binding. Null while the keyboard is
     * down, which is also when [dispatch]'s keyboard arm is unreachable. */
    internal fun attachSearchKeyboard(binding: MimirKeyboardFocusBinding?) {
        searchKeyboard = binding
    }

    /**
     * Raise the keyboard to rename [path]. A no-op unless [renameAvailable]: the picker cannot rename.
     *
     * The field starts holding the current name, so the player edits it rather than retyping it, and no
     * problem is shown until they change something.
     */
    internal fun openRename(path: String) {
        if (!renameAvailable || renameOpen) return
        val original = path.substringAfterLast('/').substringAfterLast('\\')
        renamePath = path
        renameOriginal = original
        renameText = original
        renameProblem = null
        renameOpen = true
    }

    /** The keyboard wrote a new name. The rule it breaks, if any, is shown as the player types. */
    internal fun setRenameText(text: String) {
        if (!renameOpen || renameText == text) return
        renameText = text
        renameProblem = RenameNamePolicy.check(text, renameOriginal)
    }

    /**
     * Done. A name that breaks a rule keeps the keyboard up with the reason under it; a usable name closes
     * the keyboard and hands it to the store, which raises the Keep both / Replace / Skip card itself if the
     * name is already taken in that folder (only the folder can say).
     */
    internal fun confirmRename() {
        val path = renamePath ?: return
        if (!renameOpen) return
        val problem = RenameNamePolicy.check(renameText, renameOriginal)
        if (problem != null) {
            renameProblem = problem
            return
        }
        val newName = RenameNamePolicy.normalise(renameText)
        closeRename()
        onRename?.invoke(path, newName)
    }

    /** Abandon the rename: B on the keyboard, a teardown, or any other screen taking over. Idempotent. */
    fun closeRename() {
        if (!renameOpen) return
        renameOpen = false
        renamePath = null
        renameKeyboard?.exit()
    }

    /** [FileBrowserSurface] hands down the `@Composable`-created binding; null while the keyboard is down. */
    internal fun attachRenameKeyboard(binding: MimirKeyboardFocusBinding?) {
        renameKeyboard = binding
    }

    /** The queue screen's Cancel button: stop the running entry [entryId] (the store decides if it can). */
    internal fun cancelEntry(entryId: Long) {
        onCancelEntry?.invoke(entryId)
    }

    /** The same classifier the browse folio's tiles are built with, so a search hit and the tile it
     * becomes after a reveal draw the identical glyph and badge. Exposed rather than re-derived because
     * two classification paths would be two chances to disagree. */
    internal val searchClassifier: (FileItem) -> FileBrowserRecognition get() = classifyPlatform

    /** The keyboard wrote a new query. Results are dropped immediately so the screen never shows hits
     * from the previous query beside the new one; the surface's collector restarts on the change. */
    internal fun setSearchQuery(query: String) {
        if (!searchOpen || searchQuery == query) return
        searchQuery = query
        resetSearchResults()
    }

    /**
     * A chip was chosen. Same reset as [setSearchQuery], and deliberately NOT guarded on "already
     * selected": re-confirming the active chip restarts the walk, which is the cheapest possible retry.
     *
     * **[searchWalkToken] is bumped unconditionally, before the reset** — see its own KDoc for why an
     * equal-value write to [searchFilter] cannot be trusted to make the re-press observable by itself.
     * (R6-F-09, 2026-09-15: at d7e306f3 this comment already promised the restart, but nothing kept it —
     * `FileBrowserSearchSurface`'s collector keyed its `LaunchedEffect` on [searchQuery] and
     * [searchFilter] alone, so a same-chip press changed neither key and the collector never reran. The
     * player was left staring at the empty list [resetSearchResults] leaves behind until the query or
     * the chip genuinely changed. [searchWalkToken] gives the surface a key that changes on every press,
     * re-press included.)
     */
    internal fun setSearchFilter(filter: FileSearchFilter) {
        if (!searchOpen) return
        searchFilter = filter
        searchWalkToken++
        resetSearchResults()
    }

    /**
     * Publish a batch of results, or the final list, from the surface's collector.
     *
     * **Focus is checked against the incoming list BEFORE anything is retired**, mirroring [load]'s
     * `clearFocus()`-then-`update` ordering and for the identical reason: retiring the focused key inside
     * an active scope triggers `removeEntryAndFallback`'s sibling cascade. During a normal walk the list
     * only grows and focus always survives, so the clear branch does not run; it exists for the query
     * change, where every previous result departs at once.
     */
    internal fun publishSearchResults(results: List<FileItem>, phase: FileBrowserSearchPhase) {
        if (!searchOpen) return
        // The image picker lists search hits the way it lists a folder: images and folders, unless all files are
        // showing. Filtered BEFORE the keys are derived so the registered keys and the drawn tiles are one set.
        val shown = fileBrowserImagePickSearchResults(mode, imagePickShowsAll, results)
        // **The focus repair runs ONLY while [searchScope] is the active one. This guard is the fix for a
        // real, shipping-severity defect, kept named rather than quietly correct.**
        //
        // Every publish can land while the KEYBOARD scope is active, and the common case does: typing one
        // character calls `setSearchQuery` -> `resetSearchResults` -> here. Without this guard the repair
        // below ran against whatever scope happened to be on top:
        //  - `DefaultFocusAuthority.clearFocus` acts on the ACTIVE scope (`:184-191`) -- it would null the
        //    KEYBOARD's focused key and park `ClearedFocusSentinel` in the keyboard's own focus memory;
        //  - `requestFocus` then refuses silently, because it returns at
        //    `if (entry.scope != activeScope) return` (`:112`) and [FileBrowserSearchFocus.QueryKey] is a
        //    search-scope key.
        // Net effect on device: the player types ONE character and the keyboard goes dead -- no ring, no
        // D-pad (`moveFocus` returns at `_state.value.focused ?: return false`), no A
        // (`activateFocused` finds no focused key). It would appear to revive only on L2/R2, because those
        // change the visible key set and `reconcileFocus` re-seeds. A bizarre, expensive bug.
        //
        // The retire pass below stays safe without the clear: `removeEntryAndFallback` only falls focus
        // `if (_state.value.focused == key)`, and while the keyboard owns the scope the focused key is a
        // keyboard key, which no result key can equal. Same argument [close] makes for pop-before-clear.
        // When the keyboard later exits, `popScope` restores `focusMemory[searchScope]` validated against
        // live entries, so focus comes back to a key that still exists.
        val searchScopeActive = authority.state.value.scope == searchScope
        val focused = authority.state.value.takeIf { it.scope == searchScope }?.focused
        val survives = focused != null && focused in fileBrowserSearchKeys(shown)
        val repair = searchScopeActive && !survives
        if (repair) authority.clearFocus()
        searchResults = shown
        searchPhase = phase
        searchTracker.update(authority, searchScope, shown)
        if (repair) authority.requestFocus(FileBrowserSearchFocus.QueryKey)
    }

    /** A UI-originated tap on a search control. Focuses first, then reuses [dispatch]'s own CONFIRM path,
     * so touch and controller can never disagree about what activating a control means -- the same "one
     * true path" discipline [handleTap] and [handleCommitTap] already follow. */
    internal fun handleSearchTap(key: FocusKey) {
        if (!searchOpen || authority.state.value.scope != searchScope) return
        authority.requestFocus(key)
        dispatch(ControllerAction.CONFIRM)
    }

    private fun resetSearchResults() {
        publishSearchResults(
            results = emptyList(),
            phase = if (fileBrowserSearchLaunches(searchQuery, searchFilter)) {
                FileBrowserSearchPhase.RUNNING
            } else {
                FileBrowserSearchPhase.IDLE
            },
        )
    }

    private fun dispatchSearch(action: ControllerAction): Boolean {
        val focused = authority.state.value.focused
        when (val command = fileBrowserSearchCommand(action, focused, searchResults, grid)) {
            is FileBrowserSearchCommand.Focus -> authority.requestFocus(command.key)
            FileBrowserSearchCommand.OpenKeyboard -> openSearchKeyboard()
            is FileBrowserSearchCommand.SelectFilter -> setSearchFilter(command.filter)
            is FileBrowserSearchCommand.Reveal -> revealPath(command.path)
            FileBrowserSearchCommand.Close -> closeSearch()
            FileBrowserSearchCommand.Consumed -> Unit
        }
        // Always true -- this is still the hard modal [fileBrowserCommand]'s KDoc describes, and the
        // search screen is not a hole in it.
        return true
    }

    /**
     * Leave search and land the player in the directory holding [path], focused on that file -- the
     * design's own result behaviour (`Mimir Files.dc.html:723`).
     *
     * Returns false when the target cannot be expressed inside this browser's [rootPath]. **That is
     * structurally unreachable in production and is still handled rather than assumed:**
     * `FileBrowserStore.open` roots the browser at `StorageVolumeListing.browserRoot`, which is `/storage`
     * (multi-volume), the single volume's own path, or `PRIMARY_FALLBACK_PATH` -- and
     * `FileBrowserStore.search` roots the WALK at `volumeRootFor(currentPath)`, which is at or below every
     * one of those. `switchVolume` re-roots to a volume path, same relation. So the walk can never surface
     * a file outside the browser's root; if that ever changes, this refuses instead of dropping the player
     * somewhere plausible.
     *
     * **The focus memory is written BEFORE [load], not requested after it.** [load] restores
     * `focusByDirectory[path]` when the remembered key is in the incoming listing, and it does so as its
     * final act -- a `requestFocus` issued after `load` would be overwritten by nothing, but one issued
     * *inside* would fight it. Seeding the memory instead means the found file is restored through
     * exactly the same path a returning player uses, with the same validation (a file that vanished
     * between the walk and the reveal degrades to index 0 rather than a dead `requestFocus`).
     *
     * **Arms the F-03 glide as an ENTER.** A reveal is a descent in the player's terms -- they picked
     * something and the browser went there -- so it reads as one.
     */
    private fun revealPath(path: String): Boolean {
        val parent = fileBrowserSearchParentPath(path) ?: return false
        val rebuilt = fileBrowserRevealStack(rootPath, parent) ?: return false
        closeSearch()
        rememberFocusInCurrentDirectory()
        // Before the rebuild: a navigation still in flight owns an undo that would fire mid-load() and
        // mutate the stack this function has just authored absolutely.
        supersedePendingLoad()
        stack.clear()
        stack.addAll(rebuilt)
        focusByDirectory[parent] = FileBrowserFocus.tileKey(path)
        armNavigationGlide(parent, FileBrowserGlideEnter, reveal = true)
        load(parent)
        return true
    }

    // ───────────────────────────────────────────────────────────────── F-01 · the queue session ──

    /**
     * Open the queue screen. Idempotent, and a no-op unless [queueAvailable].
     *
     * **The screen REPLACES the browse content rather than overlaying it** (for
     * `FileBrowserSurface.kt:186`'s stated reason): an overlay would keep the folio's whole focus
     * graph composed underneath a screen with its own, and two live graphs on one list was half of the
     * D-pad defect this browser already shipped once. The scope push here is the input half of that
     * decision -- while [queueScope] is active, no browse key can be reached at all.
     *
     * **Focus is seeded explicitly to the first button rather than to whatever the scope remembers.**
     * `pushScope` would otherwise restore `focusMemory[queueScope]` from an earlier visit, and the queue's
     * contents change under the player by nature -- entries complete, undo handles are released, cards
     * leave. Worse, `FileBrowserStore`'s ids restart at 1 for a new session, so a remembered
     * `files/queue/1/UNDO` could validate against a DIFFERENT operation's button. The newest card is at
     * the top and is the one a player who just pressed START is looking for, so first-button is both the
     * deterministic answer and the right one. Register-then-remember-then-push is
     * [openSearch]'s idiom, kept for the same defence-in-depth reason it states.
     *
     * **This entry point is the VISIT posture and it is the only public one** — W-1. The player came to
     * look, so B returns him to his listing and nothing is finalised, exactly as before W-1. The
     * EXIT_REVIEW posture is reachable only from [FileBrowserCommand.ReviewHeldDeletes], i.e. only from a
     * real B press at the root with deletes held: a public `openQueue(purpose)` would let a caller put the
     * screen into the posture whose B destroys files, from a tap that never asked to leave.
     */
    fun openQueue() = openQueue(FileBrowserQueuePurpose.VISIT)

    private fun openQueue(purpose: FileBrowserQueuePurpose) {
        if (queueOpen || !queueAvailable) return
        // The other half of the mutual exclusion [openSearch] now states. `FileBrowserSurface`'s header
        // tap already calls `closeSearch()` before this and explains why; holding the invariant HERE as
        // well means all three screens are mutually exclusive by construction inside this class rather
        // than by one call site remembering. Idempotent, so the surface's line stays harmless.
        closeSearch()
        closeExtractChooser()
        // G2. Fifth screen, same mutual exclusion, held HERE rather than at a call site.
        closeTransferChooser()
        // F-1. Sixth screen, same mutual exclusion, held HERE for the identical reason.
        closeActionOverlay()
        closeRename()
        // Policy 2026-08-16. SIXTH screen, same mutual exclusion, and the one that matters
        // most: it holds an undelivered decision about a move. Closing it abandons that move, which
        // is the fail-closed direction -- a warning that survived into a new session would commit a
        // transfer whose subject and destination came from a session that is over.
        closeDiscSetPrompt()
        queuePurpose = purpose
        val cards = queueCards
        queueTracker.update(authority, queueScope, cards)
        // Legitimately empty: a queue of nothing but running transfers registers no buttons at all
        // (fileBrowserQueueKeys' own KDoc). The scope is still pushed, still absorbs, and CANCEL still
        // leaves -- dispatchQueue does not consult focus to answer it.
        val landing = fileBrowserQueueKeys(cards).firstOrNull()
        landing?.let { authority.rememberFocusForScope(queueScope, it) }
        authority.pushScope(queueScope)
        landing?.let(authority::requestFocus)
    }

    /**
     * Leave the queue for the listing the player came from. Idempotent.
     *
     * **Pop before clear**, the ordering [close] and [closeSearch] both document at length: clearing the
     * registrations while [queueScope] is still active would unregister the focused button, which
     * `DefaultFocusAuthority.removeEntryAndFallback` answers by falling focus onto a sibling that is then
     * unregistered in turn -- one `FocusState` emission per remaining button on the way out.
     *
     * Nothing here restores browse focus by hand: `popScope` restores `focusMemory[scope]`, validated
     * against live entries. After a delete that landed while this screen was up, that memory is the
     * surviving neighbour [load] wrote there -- see [fileBrowserSurvivingNeighbour].
     *
     * **The posture dies with the screen** (W-1). Clearing [queuePurpose] here rather than only on the
     * paths that set it is what re-arms the redirect: a player who leaves an EXIT_REVIEW back to his
     * listing (START, or a teardown) and later presses B at the root again is answered by
     * [fileBrowserExitOpensQueue] from scratch, against whatever is held THEN. A leaked EXIT_REVIEW would
     * silently skip the second review; a leaked VISIT would silently close the browser on the next B.
     */
    fun closeQueue() {
        if (!queueOpen) return
        if (authority.state.value.scope == queueScope) authority.popScope()
        queueTracker.clear(authority)
        queuePurpose = null
    }

    /**
     * The store's queue changed. Called by [FileBrowserSurface] from its collector on
     * `FileBrowserStore.queue`, open or not -- the browse footer draws the count either way.
     *
     * **Focus is checked against the incoming cards BEFORE anything is retired**, mirroring
     * [publishSearchResults] exactly and for the identical reason: retiring the focused key inside an
     * active scope triggers the sibling cascade. Unlike search, the repair branch here is the COMMON case
     * rather than the rare one -- pressing Undo removes the entry the focused button belongs to, so almost
     * every press invalidates its own focus.
     */
    internal fun publishQueue(entries: List<FileQueueEntry>) {
        queueEntries = entries
        if (!queueOpen) return
        val cards = queueCards
        val keys = fileBrowserQueueKeys(cards)
        // Guarded on the queue scope actually being active for the same reason publishSearchResults is:
        // a publish can land while some other scope owns focus, and `clearFocus` acts on the ACTIVE scope
        // -- it would null a foreign screen's focus and park the sentinel in its memory.
        val queueScopeActive = authority.state.value.scope == queueScope
        val focused = authority.state.value.takeIf { it.scope == queueScope }?.focused
        val repair = queueScopeActive && (focused == null || focused !in keys)
        if (repair) authority.clearFocus()
        queueTracker.update(authority, queueScope, cards)
        if (repair) keys.firstOrNull()?.let(authority::requestFocus)
    }

    /** A UI-originated tap on a queue button. Focuses first, then reuses [dispatch]'s own CONFIRM path,
     * so touch and controller can never disagree about what activating a button means -- the same "one
     * true path" discipline [handleTap], [handleCommitTap] and [handleSearchTap] already follow. */
    internal fun handleQueueTap(key: FocusKey) {
        if (!queueOpen || authority.state.value.scope != queueScope) return
        authority.requestFocus(key)
        dispatch(ControllerAction.CONFIRM)
    }

    /**
     * The queue screen's dispatch table. Always `true` -- still the hard modal [fileBrowserCommand]'s KDoc
     * describes.
     *
     * **CANCEL closes the queue before it can mean Up, and that ordering is the point.** This function
     * runs ahead of the browse table, so B on the queue screen never reaches the branch where CANCEL means
     * "go to the parent directory" -- otherwise one press would both leave the queue and walk the player
     * out of the directory they are still standing in. It also does not consult focus, which is what makes
     * the zero-button queue (all transfers running) an escapable screen rather than a dead end.
     *
     * **W-1: what CANCEL leaves depends on why the screen was opened, and on nothing else.** On a
     * [FileBrowserQueuePurpose.VISIT] it leaves the SCREEN, unchanged. On a
     * [FileBrowserQueuePurpose.EXIT_REVIEW] it leaves the BROWSER -- the player pressed B at the root to
     * go, was shown what going would make permanent, and this is him saying it again with the payload in
     * front of him. Deciding on the queue's CONTENTS instead would have made a casual START visit's B
     * destroy files, which is why the posture is carried rather than derived; see
     * [FileBrowserQueuePurpose].
     */
    private fun dispatchQueue(action: ControllerAction): Boolean {
        when (action) {
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN,
            ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT,
            -> authority.moveFocus(action)

            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> when (queuePurpose) {
                // Exactly the browse table's own Close arm, called from here: `close()` unwinds this
                // screen's scope on its way out (it calls closeQueue itself), then `onDismiss()` is the
                // press that reaches `FileBrowserStore.clearSession` and finalises what is held. One
                // teardown path for both, so a change to leaving the browser cannot apply to one door and
                // not the other.
                FileBrowserQueuePurpose.EXIT_REVIEW -> {
                    close()
                    onDismiss()
                }
                // Includes null, which dispatchQueue cannot actually be reached with (the caller checks
                // `queueOpen`); the exhaustive `when` is what stops a third posture being added later
                // without answering this question for it.
                FileBrowserQueuePurpose.VISIT, null -> closeQueue()
            }

            // START closes the screen it opened. A second press of the button that got you here must not
            // be a no-op on a screen with no other way out advertised beyond B.
            //
            // **It stays "close the screen" in BOTH postures, and that is the escape hatch** (W-1): a
            // player redirected here on his way out who changes his mind gets back to his listing with
            // nothing finalised. It is deliberately not the same press as B -- the button that means
            // "show me the queue" must not also be able to mean "destroy the payload".
            ControllerAction.SYSTEM_START -> closeQueue()

            ControllerAction.CONFIRM -> {
                // Intent is read OFF THE FOCUSED KEY (FileBrowserQueueFocus.targetOf) rather than from a
                // parallel map, so a stale key from a card that has already left degrades to "nothing to
                // press" instead of firing the previous entry's Undo.
                val focused = authority.state.value.takeIf { it.scope == queueScope }?.focused
                FileBrowserQueueFocus.targetOf(focused)?.let { target -> queueAction?.invoke(target) }
            }

            // Everything else swallowed: the triggers, the shoulders (this screen does not page), SELECT
            // (deleting from inside the queue is not a gesture that means anything), and every remaining
            // SYSTEM_*/GLOBAL_*.
            else -> Unit
        }
        return true
    }

    // ────────────────────────────────────────────────── F-06 · the Extract chooser + destination pick ──

    /**
     * `SECONDARY` on an archive, with both sinks wired: resolve the destinations and decide whether the
     * press is worth a chooser at all.
     *
     * **The resolution is the sink's; the decision is pure.** [resolveExtractOptions] reaches the real
     * library (ROM folders, volumes); everything after it is [fileBrowserExtractOptions] and
     * [fileBrowserExtractNeedsChooser], both pinned in `FileBrowserExtractChoiceTest` against the real
     * `ExtractDestinationPolicy` with no controller at all.
     *
     * **`folderPickerAvailable = true`, and this line is the whole of F-06's option 2.** The picker
     * `fileBrowserExtractOptions` has always asked about is *this browser*, entered through
     * [beginDestinationPick]. Passing true is therefore a statement of fact rather than optimism -- and
     * its consequence, that row 2 now always exists so **every archive opens a two-row chooser**, is
     * called out in that function's KDoc rather than left to be discovered.
     *
     * The single-row arm below is consequently unreachable from this call site. It is kept, not asserted
     * away, because the row list is data: if a future caller resolves options with no picker, the one
     * honest row must still be performed rather than silently dropped.
     */
    private fun offerExtract(archivePath: String) {
        val resolve = resolveExtractOptions ?: return
        val perform = onExtractTo ?: return
        val rows = fileBrowserExtractOptions(resolve(archivePath), folderPickerAvailable = true)
        if (fileBrowserExtractNeedsChooser(rows)) {
            openExtractChooser(archivePath, rows)
        } else {
            rows.firstOrNull()?.destination?.let { perform(archivePath, it) }
        }
    }

    /**
     * Open the Extract chooser for [archivePath] over [rows]. Idempotent, and a no-op on an empty list.
     *
     * Register-then-remember-then-push-then-focus, [openSearch]'s and [openQueue]'s idiom. Focus is
     * seeded explicitly to the first row rather than to whatever [extractScope] remembers, and here the
     * memory would be actively wrong rather than merely stale: the keys are kind-derived
     * ([FileBrowserExtractFocus.optionKey]), not archive-derived, so a remembered `PLATFORM_FOLDER` from
     * the last archive would validate perfectly against a different archive's rows and land the ring on
     * option 2 of a question the player has not read yet.
     */
    internal fun openExtractChooser(archivePath: String, rows: List<FileBrowserExtractOption>) {
        if (extractChooserOpen || rows.isEmpty()) return
        extractChooser = FileBrowserExtractChoiceState(archivePath = archivePath, rows = rows)
        extractTracker.update(authority, extractScope, rows)
        val landing = fileBrowserExtractKeys(rows).firstOrNull()
        landing?.let { authority.rememberFocusForScope(extractScope, it) }
        authority.pushScope(extractScope)
        landing?.let(authority::requestFocus)
    }

    /**
     * Leave the chooser for the listing underneath. Idempotent.
     *
     * **Pop before clear**, the ordering [close], [closeSearch] and [closeQueue] all document at length.
     * Nothing here restores browse focus by hand: `popScope` restores `focusMemory[scope]`, which is the
     * archive tile the player pressed X on.
     */
    fun closeExtractChooser() {
        if (!extractChooserOpen) return
        if (authority.state.value.scope == extractScope) authority.popScope()
        extractTracker.clear(authority)
        extractChooser = null
    }

    /**
     * The chooser's dispatch table. Always `true` -- still the hard modal [fileBrowserCommand] describes.
     *
     * **CANCEL closes the chooser before it can mean Up**, exactly as [dispatchQueue] documents: this
     * function runs ahead of the browse table, so B here never reaches the branch where CANCEL walks the
     * player out of the directory they are still standing in. It also does not consult focus to answer
     * CANCEL, so a chooser that somehow registered nothing is still escapable rather than a dead end.
     *
     * NAV_LEFT/NAV_RIGHT are routed to `moveFocus` like the other two and simply find no candidate --
     * see [fileBrowserExtractOptionBounds] for why this screen needs no consumption rule of its own.
     */
    private fun dispatchExtract(action: ControllerAction): Boolean {
        when (action) {
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN,
            ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT,
            -> authority.moveFocus(action)

            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> closeExtractChooser()

            // X closes the chooser X opened -- the same courtesy dispatchQueue extends to START, and for
            // the same reason: a second press of the button that got you here must not be a no-op.
            ControllerAction.SECONDARY -> closeExtractChooser()

            ControllerAction.CONFIRM -> activateFocusedExtractOption()

            // Everything else swallowed: the shoulders (nothing to page), SELECT, START, both triggers
            // and every remaining SYSTEM_*/GLOBAL_*. A two-row question is not a screen that needs them.
            else -> Unit
        }
        return true
    }

    /**
     * Perform the focused chooser row.
     *
     * **The row is looked up in the OPEN CHOOSER's own carried rows, not rebuilt from the focused key.**
     * [FileBrowserExtractFocus.optionOf] recovers only the *kind*; the destination lives on the row, and
     * the archive on the state. A key whose kind is not in the carried rows degrades to nothing at all --
     * the same "a stale key means nothing to press, never the previous entry's action" discipline
     * [dispatchQueue]'s CONFIRM branch states.
     *
     * **A null destination IS the picker row**, which is what [FileBrowserExtractOption.destination]'s
     * own KDoc says nullability is for ("Null is what forces a caller to route that row through a picker
     * rather than silently extracting somewhere"). Switching on the destination rather than on
     * [FileBrowserExtractOptionKind.CHOOSE_FOLDER] means a future row that legitimately carries no
     * destination cannot be added without picking up the picker route for free.
     */
    // ───────────────────────────────────────── Policy 2026-08-16 · the destination list ──
    //
    // Destination selection includes platform folders across drives, whether empty or populated.
    // Measured first: a zipped PS2 game was offered nothing at all, because PS2's content contract does
    // not list `zip`, and a freshly downloaded archive was offered nothing on any platform, because `.zip`
    // needs folder evidence to resolve. Everything in this region is the screen that answers *where?*
    // before the player has to navigate there by hand. It creates no new sink: the commit goes through the
    // pick session that is already open, exactly as the pinned commit row's does.

    /** This screen's own [FocusScope] — see [FileBrowserDestinationFocus.Scope] for why it is not shared. */
    val destinationScope: FocusScope = FocusScope("${scope.value}/destination")

    /**
     * The open destination list, or null.
     *
     * `by mutableStateOf` for [extractChooser]'s reason: the surface reads it to decide whether to compose
     * this screen at all, so a plain field would leave the screen invisible until something else
     * recomposed.
     */
    internal var destinationList: FileBrowserDestinationList? by mutableStateOf(null)

    internal val destinationListOpen: Boolean get() = destinationList != null

    private val destinationTracker = FileBrowserDestinationFocusTracker()

    // ────────────────────────────────────────────── the disc-set warning  ──

    val discSetScope: FocusScope = FocusScope("${scope.value}/discset")

    /** The open multi-disc warning, or null. `by mutableStateOf` for [destinationList]'s reason. */
    internal var discSetPrompt: FileBrowserDiscSetPrompt? by mutableStateOf(null)

    internal val discSetPromptOpen: Boolean get() = discSetPrompt != null

    /** Cancels an in-flight disc-set lookup whose answer can no longer matter. */
    private var discSetJob: Job? = null

    /**
     * [load]'s generation idiom, and it is load-bearing for the same reason: a superseded lookup must
     * decide **nothing**, and "the job was cancelled" is not something a `catch` can be trusted to see.
     *
     * The first version of [beginMove] wrapped the lookup in `runCatching { … }.getOrNull()`, and
     * `runCatching` catches `CancellationException` like any other throwable — so a cancelled lookup
     * answered null, and null is the arm that **dispatches the move**. Cancelling a lookup would have
     * performed the press it was supposed to drop. `M3uPlaylistResolver` rethrows cancellation for
     * exactly this reason, one module along; this is the same trap in a different shape.
     */
    private var discSetGeneration = 0

    /**
     * **The one door every PLAYER-INITIATED FORWARD move goes through** — both commit paths, the pinned
     * row and the destination list, and nothing else in this module may call [onMoveTo].
     *
     * ## The two moves that deliberately do NOT come through here
     *
     * Stated because "one door" was written first without them, and an unstated exception is how someone
     * later closes a hole that is not one. Both live in `FileBrowserStore`, one module along:
     *
     * - **`undoMove`** re-runs `transfer(MOVE, …)` to send a completed move back. Warning there would be
     *   nonsense in the exact case this feature exists for: a player who moved one disc out and pressed
     *   Undo is **repairing** the set, and a screen telling them it is about to break would be the
     *   feature firing backwards.
     * - **`resolveConflict`** replays a transfer the player parked on a *Keep both / Replace / Skip*
     *   question. That transfer already came through this gate — replaying it is the same move finishing,
     *   not a second one starting, and re-asking would make one press cost two answers.
     *
     * ## Why one gate and not a check at each call site
     *
     * There are two ways to commit a move (navigate and press the pinned row, or pick a folder off the
     * list), and there will be more. A warning wired into one of them is a warning with a hole in it, and
     * the hole is invisible: both paths reach the same sink and produce the same card, so nothing about
     * the unwarned path looks different afterwards. This is the same reasoning that put the destination
     * pick at the head of all three verbs rather than on each chooser.
     *
     * ## The lookup is asynchronous, and the move still starts on the press
     *
     * [resolveDiscSet] suspends. It is launched `UNDISPATCHED`, so a lookup that answers without
     * suspending — no library, no `loadScope`, or a repository that has the answer to hand — dispatches
     * the move in the SAME frame as the press, which is the property [load]'s own async seam was rebuilt
     * to hold after `runBlocking` was measured at 33 ms for three files. Only a lookup that genuinely
     * has to wait costs a frame, and in that case the alternative was blocking the press.
     *
     * **No spinner, deliberately.** A Room read against an indexed path is sub-frame in every measurement
     * recorded for this path, and a progress affordance that flashes for one frame is worse than none. If a
     * device ever shows this waiting, that is a measurement to act on, not a spinner to add.
     *
     * ## No warning is not the same as no set
     *
     * A null answer covers *not a disc set*, *no library*, and *the lookup failed*, and all three
     * dispatch the move. The unreachable-library case is stated on the move's own card by
     * `FileBrowserStore.tellLibraryTheFilesMoved` — one honest sentence in the surface the player is
     * already looking at, rather than a second warning that says "I could not check".
     */
    private fun beginMove(subject: String, destination: String) {
        val perform = onMoveTo ?: return
        val resolve = resolveDiscSet
        val target = loadScope
        if (resolve == null || target == null) return perform(subject, destination)
        val generation = ++discSetGeneration
        discSetJob?.cancel()
        discSetJob = target.launch(start = CoroutineStart.UNDISPATCHED) {
            val warning = try {
                resolve(subject)
            } catch (error: CancellationException) {
                // Rethrown, never folded into the null arm -- see [discSetGeneration].
                throw error
            } catch (_: Throwable) {
                // A library that could not answer is not a set. The unreachable-library case is stated
                // on the move's own card by the store; a warning here would say "I don't know".
                null
            }
            // Superseded work decides nothing. Belt to the rethrow's braces: a lookup that was already
            // PAST its suspension point when it was cancelled has nothing to throw, and would otherwise
            // dispatch a move for a press that has since been replaced.
            if (generation != discSetGeneration) return@launch
            discSetJob = null
            if (warning == null || warning.discCount < 2) perform(subject, destination)
            else openDiscSetPrompt(warning, destination)
        }
    }

    /**
     * Put the warning up. Idempotent, and it never opens on top of itself.
     *
     * Focus is seeded to row 0 explicitly rather than to whatever [discSetScope] remembers —
     * [openTransferChooser]'s rule, and it matters more here: the rows are option-derived, so a remembered
     * `MOVE_WHOLE_SET` from a previous set would validate perfectly against a screen that no longer offers
     * it, landing the player on a row that is not there.
     */
    private fun openDiscSetPrompt(warning: DiscSetWarning, destination: String) {
        if (discSetPromptOpen) return
        val rows = discSetMoveOptions(warning)
        discSetPrompt = FileBrowserDiscSetPrompt(warning, destination, rows)
        discSetTracker.update(authority, discSetScope, rows)
        rows.firstOrNull()?.let { authority.rememberFocusForScope(discSetScope, FileBrowserDiscSetFocus.optionKey(it)) }
        authority.pushScope(discSetScope)
    }

    /**
     * Take the warning down without deciding anything — **and drop any lookup still in flight.**
     *
     * The cancellation is above the open-guard on purpose. A lookup that has not answered yet has no
     * prompt to close, and it is precisely the state that must not survive a teardown: this controller's
     * store is a process-lifetime `object`, so a lookup outliving [close] would dispatch a move into a
     * session that is over, with a subject and a destination from the session before it. Bumping the
     * generation is what actually stops it; the `cancel()` only frees the coroutine.
     */
    internal fun closeDiscSetPrompt() {
        discSetGeneration++
        discSetJob?.cancel()
        discSetJob = null
        if (!discSetPromptOpen) return
        if (authority.state.value.scope == discSetScope) authority.popScope()
        discSetTracker.clear(authority)
        discSetPrompt = null
    }

    /**
     * The warning's own dispatch — a **hard modal**, exactly like the transfer chooser.
     *
     * Every action is consumed. A press that fell through to the browse ring underneath would move focus
     * on a listing the player cannot see, and the next CONFIRM would act on whatever it landed on.
     */
    private fun dispatchDiscSet(action: ControllerAction): Boolean {
        when (action) {
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN,
            ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT,
            -> authority.moveFocus(action)

            // B is *"leave it where it is"* -- the same answer as the last row, reachable without moving.
            // Closing without dispatching IS that answer: nothing has been moved yet, so abandoning the
            // question abandons the move, which is the fail-closed direction for a press that breaks a
            // game.
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> closeDiscSetPrompt()

            ControllerAction.CONFIRM -> activateFocusedDiscSetOption()

            // Everything else swallowed, on dispatchTransfer's reasoning, and SECONDARY is deliberately
            // NOT a second way out here: X opened the transfer chooser several screens back, so treating
            // it as "close" would silently drop a decision the player is mid-way through making, rather
            // than reversing the press that opened this.
            //
            // SELECT matters for the same reason it does one screen up -- it deletes on the listing
            // underneath, and answering "this would break your game" by destroying the file would be the
            // worst possible reading of the press.
            else -> Unit
        }
        return true
    }

    /**
     * Run the focused row.
     *
     * **The prompt is closed before the sink fires**, matching [activateFocusedExtractOption]: the sink
     * enqueues a transfer and refreshes, and a screen still up over a listing that just changed is the
     * shape that produced this module's stale-affordance bugs.
     *
     * [DiscSetMoveOption.MOVE_WHOLE_SET] reaches [onMoveSetTo] and **falls back to nothing, not to a
     * single-file move**, if that sink is absent: `discSetMovePaths` already refuses to answer paths for a
     * set that cannot travel, and a fallback that quietly moved one file after the player pressed *move
     * all* would be the exact lie this screen exists to prevent.
     */
    private fun activateFocusedDiscSetOption() {
        val prompt = discSetPrompt ?: return
        val focused = authority.state.value.takeIf { it.scope == discSetScope }?.focused
        val option = FileBrowserDiscSetFocus.optionOf(focused)?.takeIf { it in prompt.rows } ?: return
        closeDiscSetPrompt()
        when (option) {
            DiscSetMoveOption.MOVE_WHOLE_SET -> {
                val paths = discSetMovePaths(prompt.warning, option)
                if (paths.isNotEmpty()) onMoveSetTo?.invoke(paths, prompt.destination)
            }
            DiscSetMoveOption.MOVE_SUBJECT_ONLY ->
                onMoveTo?.invoke(prompt.warning.subjectPath, prompt.destination)
            DiscSetMoveOption.CANCEL -> Unit
        }
    }

    /** A UI-originated tap on a warning row — [handleDestinationTap]'s "one true path" discipline. */
    internal fun handleDiscSetTap(key: FocusKey) {
        if (!discSetPromptOpen || authority.state.value.scope != discSetScope) return
        authority.requestFocus(key)
        dispatch(ControllerAction.CONFIRM)
    }

    /**
     * Offer the platform folders for [subjectPath], or do nothing at all.
     *
     * **Called at the head of every destination pick**, whichever verb opened it. Both callers have
     * already set [pickSession] and reloaded, so the browse listing underneath is a live pick with its
     * commit row — which is exactly what the player gets if they take the browse row or press CANCEL out
     * of here.
     *
     * **Silent when there is nothing to show**, and that is the honest branch rather than a guard: with no
     * resolver wired, or a player whose drives hold no platform-named folder at all, this screen would be
     * a door with nothing behind it. The pick then behaves exactly as it did before this screen existed.
     */
    private fun openDestinationList(subjectPath: String) {
        if (destinationListOpen) return
        val resolve = resolvePlatformDestinations ?: return
        val destinations = resolve(subjectPath)
        if (destinations.isEmpty()) return
        val rows = fileBrowserDestinationRows(destinations)
        destinationList = FileBrowserDestinationList(subjectPath = subjectPath, rows = rows)
        destinationTracker.update(authority, destinationScope, rows)
        val landing = fileBrowserDestinationLanding(rows)
        landing?.let { authority.rememberFocusForScope(destinationScope, it) }
        authority.pushScope(destinationScope)
        landing?.let(authority::requestFocus)
    }

    /**
     * Leave the destination list for the pick underneath. Idempotent.
     *
     * **Pop before clear**, the ordering every close in this class documents. The pick itself is NOT
     * ended here: closing this screen means "I will find the folder myself", and the browser below is
     * still in destination-pick mode with its commit row waiting.
     */
    fun closeDestinationList() {
        if (!destinationListOpen) return
        if (authority.state.value.scope == destinationScope) authority.popScope()
        destinationTracker.clear(authority)
        destinationList = null
    }

    /**
     * The destination list's dispatch table. Always `true` — the same hard modal the other two choosers are.
     *
     * **CANCEL abandons the whole pick, it does not merely close this screen.** The screen the player
     * would fall back into is a browse listing wearing a commit row for a verb they have just backed out
     * of; leaving them there with no visible question would be the ghost-affordance defect this module
     * keeps closing. [FileBrowserDestinationRowKind.BROWSE] is the deliberate way to reach that listing,
     * and it says so on the row.
     */
    private fun dispatchDestination(action: ControllerAction): Boolean {
        when (action) {
            ControllerAction.CONFIRM -> activateFocusedDestination()
            ControllerAction.CANCEL -> {
                closeDestinationList()
                abandonDestinationPick()
            }
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN,
            ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT,
            -> authority.moveFocus(action)
            else -> Unit
        }
        return true
    }

    /**
     * Activate the focused row: commit its folder, or hand the pick back to the browser.
     *
     * **Switches on the row's PATH, not on its kind** — [activateFocusedExtractOption]'s own rule, for its
     * reason: a future row that legitimately carries no destination picks up the browse route for free
     * rather than silently committing null.
     */
    private fun activateFocusedDestination() {
        val state = destinationList ?: return
        val focused = authority.state.value.takeIf { it.scope == destinationScope }?.focused
        val index = FileBrowserDestinationFocus.indexOf(focused) ?: return
        val row = state.rows.getOrNull(index) ?: return
        val destination = row.path
        if (destination == null) {
            closeDestinationList()
        } else {
            commitDestinationAt(destination)
        }
    }

    /**
     * Finish the open pick at [destination] — the same three sinks the pinned commit row reaches, chosen
     * the same way, from a folder the player picked off a list instead of one they navigated to.
     *
     * **Every subject is read BEFORE [endDestinationPick]**, which is the ordering the three
     * `Commit*Destination` branches document at length: that call clears the session and reloads, so a
     * subject read afterwards is null and the sink never runs.
     *
     * **[transferPickSubject]'s kind guard is kept**, and it is load-bearing here for the reason it gives:
     * a subject recovered against the wrong verb would relocate a file the player never chose, and Undo
     * (2026-08-16) is no answer to that -- nobody presses Undo on a move they did not know happened.
     */
    private fun commitDestinationAt(destination: String) {
        val extract = destinationPick?.archivePath
        val move = transferPickSubject(FileBrowserTransferKind.MOVE)
        val copy = transferPickSubject(FileBrowserTransferKind.COPY)
        closeDestinationList()
        endDestinationPick()
        when {
            extract != null -> onExtractTo?.invoke(extract, destination)
            // [beginMove], never onMoveTo directly -- the other of the two commit paths.
            move != null -> beginMove(move, destination)
            copy != null -> onCopyTo?.invoke(copy, destination)
        }
    }

    /** A UI-originated tap on a destination row — [handleExtractTap]'s "one true path" discipline. */
    internal fun handleDestinationTap(key: FocusKey) {
        if (!destinationListOpen || authority.state.value.scope != destinationScope) return
        authority.requestFocus(key)
        dispatch(ControllerAction.CONFIRM)
    }

    private fun activateFocusedExtractOption() {
        val state = extractChooser ?: return
        val focused = authority.state.value.takeIf { it.scope == extractScope }?.focused
        val kind = FileBrowserExtractFocus.optionOf(focused) ?: return
        val option = state.rows.firstOrNull { it.kind == kind } ?: return
        val destination = option.destination
        closeExtractChooser()
        if (destination != null) {
            onExtractTo?.invoke(state.archivePath, destination)
        } else {
            beginDestinationPick(state.archivePath, state.rows)
        }
    }

    // ──────────────────────────────────────────────────────── G2 · the transfer chooser (Move / Copy) ──
    //
    // Copy is available alongside Move as the less destructive path. The FOURTH unreachable engine this
    // programme has had to give a door to: `FileBrowserStore.moveInManager`/`copyInManager` were finished,
    // staged, rollback-backed, conflict-disciplined and tested, with zero production callers (QA's G2).
    // Everything in this region is the door; none of it is new machinery.

    /**
     * Open the transfer chooser for the plain file at [path] — `SECONDARY`'s new arm. Idempotent.
     *
     * **Fails closed on [transferChoiceAvailable], and the check is here as well as in
     * [fileBrowserCommand] on purpose.** This function is `internal` and the surface can reach it, so
     * it is a door rather than a private helper — and a chooser opened with no sinks behind it would push
     * a focus scope over the listing, take the ring, advertise a verb and do nothing on A. That is UI-05's
     * exact shape. The dispatch table's gate stops the controller path; this one stops every other.
     *
     * **The rows are built here, not passed in** — [fileBrowserTransferOptions] takes no arguments, so
     * there is nothing a caller could supply and nothing it could get wrong. That is the whole difference
     * from [openExtractChooser], whose rows depend on what the library resolved.
     *
     * Register-then-remember-then-push-then-focus, [openSearch]'s, [openQueue]'s and [openExtractChooser]'s
     * idiom. Focus is seeded explicitly to row 0 rather than to whatever [transferScope] remembers, and
     * here the memory would be actively **wrong** rather than merely stale: the keys are kind-derived
     * ([FileBrowserTransferFocus.optionKey]), not file-derived, so a remembered `COPY` from the last file
     * would validate perfectly against this one's rows and land the ring on the verb the player did not
     * choose, for a file they have not read the name of yet.
     */
    internal fun openTransferChooser(path: String) {
        if (transferChooserOpen || !transferChoiceAvailable) return
        val rows = fileBrowserTransferOptions()
        if (rows.isEmpty()) return
        transferChooser = FileBrowserTransferChooser(path = path, rows = rows)
        transferTracker.update(authority, transferScope, rows)
        val landing = fileBrowserTransferKeys(rows).firstOrNull()
        landing?.let { authority.rememberFocusForScope(transferScope, it) }
        authority.pushScope(transferScope)
        landing?.let(authority::requestFocus)
    }

    /**
     * Leave the transfer chooser for the listing underneath. Idempotent.
     *
     * **Pop before clear**, the ordering [close], [closeSearch], [closeQueue] and [closeExtractChooser] all
     * document at length. Nothing here restores browse focus by hand: `popScope` restores
     * `focusMemory[scope]`, which is the file tile the player pressed X on.
     */
    fun closeTransferChooser() {
        if (!transferChooserOpen) return
        if (authority.state.value.scope == transferScope) authority.popScope()
        transferTracker.clear(authority)
        transferChooser = null
    }

    /**
     * The transfer chooser's dispatch table. Always `true` -- still the hard modal [fileBrowserCommand]
     * describes.
     *
     * **CANCEL closes the chooser before it can mean Up**, exactly as [dispatchExtract] and [dispatchQueue]
     * document: this function runs ahead of the browse table, so B here never reaches the branch where
     * CANCEL walks the player out of the directory they are still standing in. It also does not consult
     * focus to answer CANCEL, so a chooser that somehow registered nothing is still escapable rather than a
     * dead end.
     *
     * NAV_LEFT/NAV_RIGHT are routed to `moveFocus` like the other two and simply find no candidate --
     * see [fileBrowserTransferOptionBounds] for why this screen needs no consumption rule of its own.
     */
    private fun dispatchTransfer(action: ControllerAction): Boolean {
        when (action) {
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN,
            ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT,
            -> authority.moveFocus(action)

            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> closeTransferChooser()

            // X closes the chooser X opened -- the same courtesy dispatchExtract and dispatchQueue extend,
            // and for the same reason: a second press of the button that got you here must not be a no-op.
            ControllerAction.SECONDARY -> closeTransferChooser()

            ControllerAction.CONFIRM -> activateFocusedTransferOption()

            // Everything else swallowed: the shoulders (nothing to page), SELECT, START, both triggers and
            // every remaining SYSTEM_*/GLOBAL_*. A two-row question is not a screen that needs them.
            //
            // SELECT is worth naming rather than leaving to the blanket: it deletes on the listing
            // underneath, and letting it through here would answer a "where should this go?" question by
            // destroying the subject of it.
            else -> Unit
        }
        return true
    }

    /**
     * Perform the focused chooser row: arm the verb and hand the browser back to the player to answer
     * "where?".
     *
     * **The row is looked up in the OPEN CHOOSER's own carried rows, not rebuilt from the focused key** --
     * [FileBrowserTransferFocus.optionOf] recovers only the kind, and the file lives on the state. A key
     * whose kind is not in the carried rows degrades to nothing at all, the same "a stale key means nothing
     * to press, never the previous entry's action" discipline [dispatchQueue]'s CONFIRM branch states.
     *
     * **Every row goes to the picker; none writes anything here.** That is slice 1 by construction rather
     * than by a check -- [FileBrowserTransferOption] carries no destination, so there is no value it could
     * hold that would make this function move a file. Slice 2's smart rows are what introduce a row that
     * writes, and they will have to add the branch deliberately.
     */
    private fun activateFocusedTransferOption() {
        val state = transferChooser ?: return
        val focused = authority.state.value.takeIf { it.scope == transferScope }?.focused
        val kind = FileBrowserTransferFocus.optionOf(focused) ?: return
        val option = state.rows.firstOrNull { it.kind == kind } ?: return
        closeTransferChooser()
        // The retired chooser was files-only and is no longer dispatch-reachable (R-13 took its door
        // away); FILE is what it always carried. Retirement is its own ticket.
        beginTransferPick(state.path, option.kind, FileBrowserTileKind.FILE)
    }

    /** A UI-originated tap on a transfer chooser row. Focuses first, then reuses [dispatch]'s own CONFIRM
     * path, so touch and controller can never disagree about what activating a row means -- the same "one
     * true path" discipline [handleTap], [handleCommitTap], [handleSearchTap], [handleQueueTap] and
     * [handleExtractTap] follow. */
    internal fun handleTransferTap(key: FocusKey) {
        if (!transferChooserOpen || authority.state.value.scope != transferScope) return
        authority.requestFocus(key)
        dispatch(ControllerAction.CONFIRM)
    }

    /**
     * Enter destination-pick mode for [path] with [kind] armed — G2's "Move into…" / "Copy into…".
     *
     * Everything true of [beginDestinationPick] is true here and is not restated: the browser does not
     * change screens, the reload is mandatory rather than tidying, the landing onto the commit row is
     * guaranteed by [loadAfterPickChange] registering the key in the same synchronous call, and
     * [loadAfterPickChange] rather than [load] is what arms the O-7 glide for a row appearing under a
     * stationary player.
     *
     * **The one thing that IS different: [kind] is carried on the session and read nowhere else.** It is
     * what [commitPurposeForCurrentMode] projects onto the pinned row, and therefore what decides whether
     * `CONFIRM` produces [FileBrowserCommand.CommitMoveDestination] or
     * [FileBrowserCommand.CommitCopyDestination]. The player's row choice and the sink that eventually runs
     * are the same value read twice, so they cannot disagree.
     */
    private fun beginTransferPick(
        path: String,
        kind: FileBrowserTransferKind,
        tileKind: FileBrowserTileKind,
    ) {
        // Same root start as [beginDestinationPick]: settle any in-flight navigation before the origin
        // snapshot, then browse from rootPath. Starting at the subject's folder made B at that folder
        // abandon the pick, so parents, sibling folders and other volumes were unreachable.
        supersedePendingLoad()
        val hadCommitRow = commitAvailable
        val originPath = currentPath
        pickSession = FileBrowserPickSession.Transfer(
            subjectPath = path,
            kind = kind,
            subjectTileKind = tileKind,
            originPath = originPath,
            originStack = stack.toList(),
        )
        if (originPath != rootPath) {
            stack.clear()
            armNavigationGlide(rootPath, FileBrowserGlideReturn)
        }
        loadAfterPickChange(hadCommitRow, rootPath)
        if (authority.state.value.scope == scope) {
            commitKeyForCurrentMode()?.let(authority::requestFocus)
        }
        // Policy 2026-08-16, and it lands AFTER the pick is live rather than instead of it: the
        // browse listing underneath is what the player gets by taking the browse row, and it has to be a
        // real pick with a real commit row by then.
        openDestinationList(path)
    }

    /**
     * The open pick's subject **only if it is a transfer pick of exactly [kind]**, else null.
     *
     * **A guard, not a getter, and it is the reason the two commit branches can be read independently.**
     * `CONFIRM` cannot reach [FileBrowserCommand.CommitMoveDestination] unless the gate already answered
     * `MOVE_DESTINATION`, so this can only return null in a state that is already unreachable — but the
     * branch that would run on a mismatch is `onMoveTo(<a file the player marked for COPY>)`, which
     * relocates a file the player did not choose. This makes that unreachable state *also* inert rather than merely
     * improbable, which is the polarity this module uses wherever a press can destroy something.
     */
    private fun transferPickSubject(kind: FileBrowserTransferKind): String? =
        (pickSession as? FileBrowserPickSession.Transfer)?.takeIf { it.kind == kind }?.subjectPath

    /** A UI-originated tap on a chooser row. Focuses first, then reuses [dispatch]'s own CONFIRM path, so
     * touch and controller can never disagree about what activating a row means -- the same "one true
     * path" discipline [handleTap], [handleCommitTap], [handleSearchTap] and [handleQueueTap] follow. */
    internal fun handleExtractTap(key: FocusKey) {
        if (!extractChooserOpen || authority.state.value.scope != extractScope) return
        authority.requestFocus(key)
        dispatch(ControllerAction.CONFIRM)
    }

    /**
     * Enter destination-pick mode for [archivePath] — F-06's "Extract in…" with no default.
     *
     * The browser does not change screens: it keeps its folio, its ring and its D-pad, and the only
     * difference is that the pinned commit affordance now means [FileBrowserCommitPurpose
     * .EXTRACT_DESTINATION]. Manual browsing starts at [rootPath], so a pick begun inside a nested
     * folder can reach sibling folders and other storage volumes; the original directory and stack stay
     * on [FileBrowserDestinationPick] for the cancel path. **This is the picker Move and Copy will need**,
     * which is why building extract destinations first pays twice.
     *
     * **The reload is mandatory, not tidying.** In [FileBrowserMode.MANAGE] the commit row does not exist
     * a line earlier and must exist a line later; [load] is where [FileBrowserFocusTracker] registers it,
     * with its virtual bounds and its index-0 place in [fileBrowserFocusRows]. Setting the state without
     * reloading gives the inverse of UI-05: a gate that says the row exists over a graph that has never
     * heard of it, so CONFIRM can never reach it because focus cannot.
     *
     * **Focus is then moved explicitly onto that row.** [load]'s own landing restores the tile the player
     * left, which is the archive — the right answer for a navigation and the wrong one for a question the
     * browser has just asked. The guard is [load]'s: with another scope active, focus belongs to that
     * screen and must not be taken from it.
     *
     * **The landing is guaranteed, not hoped for, and that is checkable here rather than assumed.**
     * `DefaultFocusAuthority.requestFocus` returns silently at `entries[key] ?: return` for an
     * unregistered key (BUG#01's mechanism), so a landing onto a key the graph has never heard of would be
     * a no-op with no signal. It cannot be one here: [loadAfterPickChange] delegates to [load], which
     * computes `commitKeyForCurrentMode()` **after** [destinationPick] is set and passes it to
     * `tracker.update` — so the key below is registered by the line above it, in the same synchronous
     * call, before anything asks for it. `FileBrowserExtractWiringTest` pins the resulting focus and
     * `FileBrowserRingGateStructuralTest` pins the registration itself.
     *
     * **[loadAfterPickChange] rather than [load]** — O-7. The reload inserts a row at index 0 under a
     * player who has not moved; see that function for why the ring must be told.
     */
    private fun beginDestinationPick(archivePath: String, rows: List<FileBrowserExtractOption>) {
        // Resolve any in-flight navigation before snapshotting the origin. The load's stack undo is
        // positional; capturing first and undoing later could preserve a stack from a navigation that
        // never became visible, then make cancel restore the wrong parent chain.
        supersedePendingLoad()
        val hadCommitRow = commitAvailable
        val originPath = currentPath
        val originStack = stack.toList()
        pickSession = FileBrowserPickSession.Extract(
            FileBrowserDestinationPick(
                archivePath = archivePath,
                originPath = originPath,
                originStack = originStack,
                rows = rows,
            ),
        )
        // The destination picker must cover the whole browser root. Crumbs are intentionally inert while
        // a pick is open, and CANCEL at the origin abandons the pick, so keeping a nested origin here
        // would make sibling folders and other volumes unreachable. Preserve the origin snapshot above,
        // then start the live browse journey at rootPath; abandonDestinationPick restores both later.
        if (originPath != rootPath) {
            stack.clear()
            armNavigationGlide(rootPath, FileBrowserGlideReturn)
        }
        loadAfterPickChange(hadCommitRow, rootPath)
        if (authority.state.value.scope == scope) {
            commitKeyForCurrentMode()?.let(authority::requestFocus)
        }
        // see [beginTransferPick]'s identical line for why it is after.
        openDestinationList(archivePath)
    }

    /**
     * The pick was answered: drop it and rebuild the commit row for whatever the browser is now.
     *
     * **The player stays where they chose.** That is the deliberate asymmetry with
     * [abandonDestinationPick], which restores the origin: a committed destination is a directory the
     * player navigated to on purpose, and it is where `FileBrowserStore.runExtraction`'s own `refresh()`
     * will make the extracted files appear a moment later. Landing them back at the archive would show
     * them the one directory the extraction did NOT change.
     *
     * **The reverse transition has the same focus hazard as**
     * [beginDestinationPick]'s, run backwards: in [FileBrowserMode.MANAGE] the reload REMOVES the row the
     * player is standing on, every tile behind it shifts up by a row, and focus falls into that listing —
     * so the ring repaints at full strength on geometry that moved this frame. [loadAfterPickChange]
     * covers both directions with one rule.
     */
    private fun endDestinationPick() {
        if (pickSession == null) return
        val hadCommitRow = commitAvailable
        pickSession = null
        loadAfterPickChange(hadCommitRow)
    }

    /**
     * Unwind the pick one step: back to the chooser it came from, in the directory it started in.
     *
     * See [FileBrowserDestinationPickPhase] for why this is what `CANCEL` means only once the player has
     * no navigation of their own left to unwind, and for the alternative that was rejected.
     *
     * The origin's *whole back-stack* is restored, not just its path — [FileBrowserDestinationPick
     * .originStack]'s own KDoc. The archive is seeded into [focusByDirectory] rather than requested after
     * [load], so it arrives through exactly the path a returning player's remembered tile does, with the
     * same validation: an archive that vanished during the pick degrades to index 0 instead of a dead
     * `requestFocus`. That is [revealPath]'s idiom, reused rather than re-invented.
     *
     * **F-1, 2026-08-18 — the `Transfer` arm below now reopens the action overlay, not the two-row
     * transfer chooser.** [beginTransferPick] is reached exclusively from the overlay's own MOVE/COPY rows
     * ([activateFocusedActionOverlayOption]); [openTransferChooser] has no caller left in the dispatch
     * table (SECONDARY is quiet wherever the overlay would cover a tile — [fileBrowserActionsAvailable]).
     * Reopening the chooser here would land the player on a screen the press that started this pick never
     * showed them — a door back to a room they never walked through. The subject is a
     * [FileBrowserTileKind.FILE] by construction: [FileBrowserPickSession.Transfer] is only ever created by
     * [beginTransferPick], and every caller of that gates on `focused.kind == FileBrowserTileKind.FILE`
     * ([fileBrowserActionOverlayRows]' MOVE/COPY arm, mirrored by the dispatch table's own legacy check).
     * [FileBrowserTransferSurface.kt] and [FileBrowserTransferChoice.kt]'s chooser screen are consequently
     * unreachable from any player input as of this ticket; their retirement remains deferred.
     */
    private fun abandonDestinationPick() {
        val pick = pickSession ?: return
        val hadCommitRow = commitAvailable
        pickSession = null
        // The directory being abandoned keeps its own place, in case the player comes back to it.
        rememberFocusInCurrentDirectory()
        stack.clear()
        stack.addAll(pick.originStack)
        focusByDirectory[pick.originPath] = FileBrowserFocus.tileKey(pick.subjectPath)
        // Only when it is a real movement. Abandoning from the origin itself changes no DIRECTORY, and a
        // *travelling* glide for a listing that stayed put is the "animate for a change of contents rather
        // than a change of state" mistake [FileBrowserNavigationMotion.sequence] exists to prevent.
        //
        // **O-7 corrected the second half of that sentence, which used to say the listing "did not
        // change".** In [FileBrowserMode.MANAGE] it changes structurally even at the origin: the commit
        // row leaves and every tile shifts up a row under a stationary player. [loadAfterPickChange] is
        // the arm for exactly that case, and its own `path == currentPath` guard is why this branch and
        // that one can never both fire for one press.
        if (pick.originPath != currentPath) armNavigationGlide(pick.originPath, FileBrowserGlideReturn)
        loadAfterPickChange(hadCommitRow, pick.originPath)
        // G2. **The one place the verb changes what unwinding means**, and it is an exhaustive `when` over
        // a sealed type rather than an `if`: a fourth kind of pick cannot be added without answering what
        // `CANCEL` reopens for it. This is also why `CANCEL` needed no second command
        // ([FileBrowserCommand.BackToChooser]'s own KDoc) -- the press has one effect, and WHICH chooser
        // comes back is data on the session, never a decision the dispatch table has to be told.
        when (pick) {
            is FileBrowserPickSession.Extract -> openExtractChooser(pick.pick.archivePath, pick.pick.rows)
            // R-19: reads the session's own subject kind. Hardcoding FILE was correct while transfers
            // were files-only and became wrong the instant an archive could be moved -- an abandoned
            // archive pick would have reopened an overlay with no Extract row.
            is FileBrowserPickSession.Transfer -> openActionOverlay(pick.subjectPath, pick.subjectTileKind)
        }
    }

    /**
     * Reload after a state change that may have added or removed the pinned commit row, arming the F-03
     * glide when it did — **the THIRD case that may arm [fileBrowserRingEligible] (O-7, the earlier change).**
     *
     * ## The category, in this file's own words
     *
     * [dispatch]'s `CommitExtractDestination` branch already names it: committing a destination *"changes
     * what commitKeyForCurrentMode() answers while the player is standing still, which no previous state
     * change in this class could do: navigation was the only thing that moved the gate, and navigation
     * always reloaded."* Beginning and abandoning a pick are the same event. In [FileBrowserMode.MANAGE]
     * the commit row does not exist a line earlier and does a line later, so [load] gives it index 0 of
     * [fileBrowserFocusRows] and **every tile behind it shifts by a whole row** — a structural change to
     * the listing under a player who has not navigated. The ring then lands, at full strength on its very
     * first frame (`mimirFocusRing` animates from `animateFloatAsState`, which starts AT its target on a
     * composition that is itself brand new), on geometry that was created this frame. That is the
     * repeated clipped/stale ring regression.
     *
     * ## Why arming is the fix, and widening the gate's predicate is not
     *
     * [fileBrowserRingEligible] is `glideSettled || !fileBrowserNavigationGlideApplies(...)`, and
     * `glideSettled` is state the surface re-creates **on the same identity the animation is keyed on**
     * ([fileBrowserRingEpoch]: *"the SAME key both of them are remembered against"*). Gate and animation
     * therefore arm together or not at all — **a gate armed with no animation behind it has nothing to
     * release it**, and widening the predicate to answer `false` for a structural change would suppress
     * the ring for a window that never ends. The honest third case is to give the structural change the
     * arrival it genuinely is: both halves arm at once, and the release is the glide's own completion
     * callback. No second clock, no new state, no surface change.
     *
     * **Nothing here gates dispatch, focus or scroll.** `MimirMotion.InputDelayMs` is still 0; [load] still
     * registers and lands focus on the same synchronous call; the settlement scroll still runs off its own
     * key. Only PAINT waits, for one bounded, self-cancelling `MimirMotion.ModeTransitionMs` window.
     *
     * **A directional press still cannot reach this, by construction.** The condition is a change in the
     * commit row's EXISTENCE, which only [beginDestinationPick], [endDestinationPick] and
     * [abandonDestinationPick] can produce. Nothing a D-pad press touches appears in it, so directional movement remains unaffected,
     * exactly as in the first two cases.
     *
     * **[FileBrowserMode.PICK] deliberately does NOT arm.** There the row already exists and only its
     * *purpose* changes ([commitPurposeForCurrentMode]) — the copy on a row whose geometry is untouched.
     * Nothing shifts, so nothing may blink the ring off; arming there would introduce input-visible lag without any geometry change. Same for the drive chooser, where `commitAllowed` suppresses the row in both states.
     *
     * **A real movement is left to the navigation's own glide.** When [path] is not [currentPath] the
     * listing is changing directory and [abandonDestinationPick] has already armed a `Return`; a second
     * bump would be two arrivals for one press.
     *
     * **Direction is `±1`, and that is a constraint respected rather than a claim about space.** The
     * listing grew or lost a row; it did not travel. `FileBrowserArrival` renders a signed 22 dp slide for
     * either value, so this plays a small horizontal move for a vertical structural change. The visually
     * exact answer is a third direction — `0`, "dip, no travel" — which `fileBrowserArrivalTravelDp`
     * already computes correctly (`0 * 22dp`) and which needs no surface edit at all; it is a follow-up
     * rather than part of this change because `FileBrowserNavigationMotion`'s `require` is pinned by
     * `FileBrowserControllerTest."a direction that is neither enter nor return is rejected at
     * construction"`; changing that constraint remains deferred.
     *
     * @param hadCommitRow [commitAvailable] read BEFORE the state change, by the caller. Passed rather
     *   than recomputed because every caller mutates [destinationPick] first — by the time this runs the
     *   "before" answer is already gone.
     * @param path the directory to load. Defaults to [currentPath], which is what beginning and ending a
     *   pick both do; only [abandonDestinationPick] ever passes anything else.
     */
    private fun loadAfterPickChange(hadCommitRow: Boolean, path: String = currentPath) {
        val hasCommitRow = commitAvailable
        if (path == currentPath && hasCommitRow != hadCommitRow) {
            // Reload in place. As a page return the pre-commit listing slid 22 dp and dissolved over the new
            // one, which had shifted a row -- the "third direction" this KDoc asked for, as a flag.
            armNavigationGlide(
                path,
                if (hasCommitRow) FileBrowserGlideEnter else FileBrowserGlideReturn,
                inPlace = true,
            )
        }
        load(path)
    }

    /**
     * How much of the open pick is left to unwind, or null when there is none — the value
     * [fileBrowserCommand]'s CANCEL branch reads.
     *
     * [FileBrowserDestinationPickPhase.SETTLED] **only at the volume root**: the root has no parent, so
     * there is no `Up` left to give and the alternative would be a `Close` that throws away the browse
     * session mid-question. Every pick now starts at the root ([beginDestinationPick], [beginTransferPick]),
     * so the origin is just another folder on the way: settling there too made B cancel the pick in the
     * middle of a walk back up, and the folders above the origin were unreachable again .
     *
     * `internal` rather than private because [fileBrowserFooterHints] needs it too — the legend has to
     * drop `X EXTRACT` and stop saying `CLOSE` for exactly the interval this answers non-null, and
     * `FileBrowserSurface` is the caller that passes it.
     */
    internal fun destinationPickPhase(): FileBrowserDestinationPickPhase? {
        // G2: [pickSession], not [destinationPick] -- a transfer pick unwinds by exactly the same two
        // levels, and reading the extract-only projection here would have made `CANCEL` mid-move mean
        // "close the browser" at the root.
        if (pickSession == null) return null
        return if (atRoot) {
            FileBrowserDestinationPickPhase.SETTLED
        } else {
            FileBrowserDestinationPickPhase.AWAY
        }
    }

    /**
     * Whether the commit affordance exists *in the directory currently open* -- the one answer the
     * surface may ask, so that rendering the row, highlighting it, and honouring CONFIRM on it can
     * never disagree.
     *
     * Added for UI-05 (2026-08-14). `commitAllowed` landed in `68df882e` and taught
     * [commitKeyForCurrentMode] to refuse at the drive chooser, but [FileBrowserSurface] still decided
     * for itself from `mode == PICK` alone -- so at `/storage` the row rendered, registered physical
     * focus bounds, took NAV_UP, highlighted, advertised "A · CHOOSE", and then swallowed CONFIRM
     * against a null commit key. Deriving this from [commitKeyForCurrentMode] rather than restating the
     * predicate is the point: there is now one rule, and a future condition added to it reaches the
     * surface for free.
     *
     * Deliberately NOT Compose-observable on its own, matching the surface's existing `commitFocused`,
     * which already reads [currentPath] the same way. What drives recomposition after a navigation is
     * [partition] (`by mutableStateOf`), reassigned by [load] *after* [currentPath] -- so any
     * recomposition that can observe a new listing is already observing the matching path.
     */
    internal val commitAvailable: Boolean get() = commitKeyForCurrentMode() != null

    /**
     * What the commit affordance commits in the directory currently open, or null when it has none —
     * **the state the surface renders the row's copy from.**
     *
     * Exposed instead of a string because the controller decides which QUESTION is being asked and the
     * surface decides how to word the answer; a hardcoded "Use this folder" here would be this module's
     * input layer holding player-facing copy, which is the split `fileBrowserFooterHints` already keeps.
     *
     * Not Compose-observable in its own right, exactly like [commitAvailable] — but the chain underneath
     * it reads [destinationPick], which IS snapshot state, so a composition that reads this subscribes to
     * the transition that matters. See that field's KDoc for why [partition] could not carry it.
     */
    internal val commitPurpose: FileBrowserCommitPurpose? get() = commitPurposeForCurrentMode()

    /** [FileBrowserFocus.commitKey] for the directory currently open, or null when the affordance does
     * not exist there. Namespaced by [currentPath] so every directory gets its own distinct identity; see
     * [FileBrowserFocus.commitKey].
     *
     * **Still the single gate (D-3).** It gained a second reason to exist and did not gain a second
     * gate: the key is now nothing but [commitPurposeForCurrentMode]'s answer projected onto a key, so
     * the row's existence, its identity, its virtual bounds, its NAV_LEFT/RIGHT consumption rule, its
     * footer verb and its CONFIRM behaviour all still trace back to ONE predicate. Two predicates is
     * UI-05. */
    private fun commitKeyForCurrentMode(): FocusKey? =
        commitPurposeForCurrentMode()?.let { FileBrowserFocus.commitKey(currentPath) }

    /**
     * [FileBrowserFocus.backToOriginKey] for the pick in progress, or null — **F-2, [commitKeyForCurrentMode]'s
     * sibling.** Gated on [commitAvailable] rather than merely `pickSession != null`: the escape hatch
     * cannot exist where the pick's own commit cannot (`commitAllowed` refuses both at the synthetic
     * drive chooser, for the identical physical reason -- nothing can be written into, or returned from,
     * a listing of volumes). `internal`, unlike [commitKeyForCurrentMode], because [FileBrowserSurface]
     * needs this exact value to derive its own focused/tap state, the same way it already reads
     * [commitAvailable] and [commitPurpose] rather than a private equivalent.
     */
    internal fun backToOriginKeyForCurrentMode(): FocusKey? =
        pickOriginPath?.takeIf { commitAvailable }?.let(FileBrowserFocus::backToOriginKey)

    /**
     * The one predicate. Null means the pinned row does not exist in this directory at all.
     *
     * **[commitAllowed] is checked first and applies to BOTH purposes.** Contract C suppressed the row at
     * the drive chooser because committing `/storage` as a ROM source is meaningless; extracting *into*
     * `/storage` is meaningless in the same way and for a more physical reason — it is a synthetic
     * listing of volumes, not a directory anything can be written to. Exempting the new purpose would
     * have been the un-grounded choice; failing closed for both is the polarity this file uses
     * everywhere.
     *
     * **A destination pick outranks [FileBrowserMode.PICK]'s own commit**, and the order is the point
     * rather than an accident of `when`: while the browser is asking "extract where?", that row must
     * answer that question and nothing else. Without the precedence a PICK-mode player who chose
     * "Extract in…" would find the row still meaning "use this folder as a ROM source", press A, and
     * have the whole browser close and report a source they never asked for — the archive unextracted
     * and the add-games flow completed behind their back.
     *
     * **G2 extended this predicate and deliberately did NOT add a second one.** The pick arm no longer
     * names a constant; it reads the open session's own [FileBrowserPickSession.purpose], so Move and Copy
     * arrive as *data* rather than as two more branches. Three consequences worth stating, because they are
     * what the slice was asked to preserve:
     *  - `commitAllowed` still gates all four purposes. Committing `/storage` is as meaningless a Move
     *    destination as it is a ROM source, and the drive chooser is a synthetic listing of volumes that
     *    nothing can be written into at all.
     *  - A pick still outranks [FileBrowserMode.PICK]'s own commit, unchanged.
     *  - **The row's purpose and the sink that runs are the same value read twice.** `CONFIRM` maps this
     *    purpose to exactly one `Commit*Destination` command, so there is no arrangement of state in which
     *    the player answers a Move question and a Copy is performed, or vice versa.
     */
    private fun commitPurposeForCurrentMode(): FileBrowserCommitPurpose? = when {
        !commitAllowed(currentPath) -> null
        pickSession != null -> pickSession?.purpose
        mode == FileBrowserMode.PICK -> FileBrowserCommitPurpose.CHOOSE_SOURCE
        // The same pinned row, a different question: a music folder, never a ROM source (2026-09-30).
        mode == FileBrowserMode.PICK_MUSIC_FOLDER -> FileBrowserCommitPurpose.CHOOSE_MUSIC_FOLDER
        // The image picker has no "use this folder" row, so the slot carries its one switch -- and only while
        // there is something to switch .
        mode == FileBrowserMode.PICK_IMAGE && (imagePickShowsAll || imagePickHiddenCount > 0) ->
            FileBrowserCommitPurpose.TOGGLE_IMAGE_FILTER
        else -> null
    }

    /**
     * Arms the F-03 directory glide for the directory about to be loaded.
     *
     * The sequence is bumped on every call, so entering the SAME child twice in one session (down, up,
     * down) animates both times -- a path-only identity would make the second descent silent because
     * nothing the surface keys on would have changed.
     *
     * **Its callers are a closed, enumerated set, and [refresh] and [open] are deliberately not in it**
     * (see [navigationMotion]):
     *  1. [dispatch]'s two navigation branches -- `Enter` and `Up`, F-03's original pair.
     *  2. [revealPath], F-08's CONFIRM on a search result, which is an `Enter` reached by another door.
     *  3. [abandonDestinationPick] when the pick had wandered off its origin -- a real `Return`.
     *  4. **[loadAfterPickChange], O-7 (the earlier change)** -- the commit row appearing or disappearing under a
     *     stationary player. The first genuinely non-navigational caller; that function's KDoc is where
     *     the case is argued, and [fileBrowserRingEligible]'s file KDoc carries it in the ring gate's own
     *     enumeration.
     */
    private fun armNavigationGlide(path: String, direction: Int, reveal: Boolean = false, inPlace: Boolean = false) {
        navigationSequence += 1
        navigationMotion = FileBrowserNavigationMotion(path, direction, navigationSequence, reveal, inPlace)
    }

    /** Records the currently-focused key against the directory currently open. No-op when this browser's
     * scope is not active or nothing is focused -- there is nothing worth remembering in either case, and
     * writing a key from a foreign scope would be a genuine cross-surface leak. */
    private fun rememberFocusInCurrentDirectory() {
        val focused = authority.state.value.takeIf { it.scope == scope }?.focused ?: return
        focusByDirectory[currentPath] = focused
    }

    /**
     * Flip the image picker between images-only and all files, in place. Re-runs the listing already in
     * memory through [applyListing], so the folder does not reload, nothing flashes, and the focus rules (the
     * remembered key, the surviving neighbour) are exactly the ones every other relist uses -- the player
     * stays on the pinned row they just pressed.
     */
    private fun toggleImagePickFilter() {
        val full = unfilteredPartition ?: return
        if (mode != FileBrowserMode.PICK_IMAGE) return
        imagePickShowsAll = !imagePickShowsAll
        rememberFocusInCurrentDirectory()
        applyListing(currentPath, full, captureLoad(restoreNeighbour = true))
    }

    /**
     * Re-read the directory currently open, keeping the player where they are.
     *
     * **Contract A, 2026-08-14.** Extraction writes real files into the directory being browsed, and
     * without this they stayed invisible until the player backed out and came back -- which reads as
     * "extract did nothing". [rememberFocusInCurrentDirectory] runs first so [load]'s own per-directory
     * memory restores the focused tile rather than yanking the player to index 0 mid-browse; a tile that
     * genuinely vanished still degrades to index 0, because [load] validates the remembered key against
     * the incoming listing.
     */
    fun refresh() {
        rememberFocusInCurrentDirectory()
        load(currentPath, restoreNeighbour = true)
    }

    /**
     * @param restoreNeighbour re-read the SAME directory, so the tile that held focus a moment ago is
     *   meaningfully comparable to the incoming listing -- see the B-4 block inside. Passed only by
     *   [refresh]. A navigation must never set it: on [FileBrowserCommand.Enter]/[FileBrowserCommand.Up]
     *   the outgoing keys belong to a DIFFERENT directory, and on [open] they belong to a previous
     *   session, where restoring the tile the last session ended on would resurrect focus memory that
     *   `open()` has just deliberately cleared.
     */
    private fun load(
        path: String,
        restoreNeighbour: Boolean = false,
        onSuperseded: (() -> Unit)? = null,
        kind: FileBrowserLoadKind = FileBrowserLoadKind.OTHER,
    ) {
        val captured = captureLoad(restoreNeighbour)
        val async = listDirectoryAsync
        val target = loadScope
        // Rule: the FIRST listing is always synchronous -- see [hasListed].
        if (async == null || target == null || !hasListed) {
            supersedePendingLoad(by = kind)
            applyListing(path, fileBrowserPartition(listDirectory(path), classifyPlatform), captured)
            return
        }
        // A no-op for the two dispatch branches, which supersede BEFORE touching [stack] and have to --
        // see [FileBrowserCommand.Up]'s call. Load-bearing for every other caller (refresh, reveal, the
        // pick reload), none of which is a navigation and all of which must cancel one cleanly.
        supersedePendingLoad(by = kind)
        val generation = ++loadGeneration
        supersedeLoad = onSuperseded
        pendingLoadKind = kind
        // UNDISPATCHED so a cache-answered listing applies inline, in this frame, on this thread -- see
        // [listDirectoryAsync]. A cold directory suspends at the first real IO and the press returns.
        loadJob = target.launch(start = CoroutineStart.UNDISPATCHED) {
            val rows = async(path)
            // Re-checked AFTER the suspension, not before: cancellation cannot reach a coroutine that has
            // already resumed, and a superseded load must apply nothing at all.
            if (generation != loadGeneration) return@launch
            applyListing(path, fileBrowserPartition(rows, classifyPlatform), captured)
            supersedeLoad = null
            pendingLoadKind = FileBrowserLoadKind.OTHER
            loadJob = null
        }
    }

    /**
     * Everything [applyListing] needs that must be read BEFORE the listing moves — the outgoing
     * directory's keys and the tile that held focus in it.
     *
     * Captured at the load's START even on the asynchronous path, and deliberately: this is the *departed*
     * state, and the comparison [fileBrowserSurvivingNeighbour] makes is only meaningful against the
     * listing the player was actually looking at when the press landed.
     *
     * `commitKeyForCurrentMode()` reads [currentPath], and the departed key has to be read before
     * `clearFocus()` parks its sentinel over it.
     *
     * The `?: rememberedFocusIn` fallback is not belt-and-braces -- it is the ONLY source when this load
     * lands while the queue (or search) screen owns the scope, which is the normal case for a delete: the
     * player presses SELECT, presses START to watch the queue, and staging completes while they are on
     * that screen. `state.value.focused` is a queue key then, so the tile the delete removed is only
     * recoverable from the browse scope's own focus memory.
     */
    private fun captureLoad(restoreNeighbour: Boolean): LoadContext = LoadContext(
        previousKeys = if (restoreNeighbour) {
            fileBrowserKeys(partition, grid, commitKeyForCurrentMode(), backToOriginKeyForCurrentMode())
        } else {
            emptyList()
        },
        previousFocus = if (restoreNeighbour) {
            authority.state.value.takeIf { it.scope == scope }?.focused ?: authority.rememberedFocusIn(scope)
        } else {
            null
        },
    )

    private class LoadContext(val previousKeys: List<FocusKey>, val previousFocus: FocusKey?)

    /**
     * Drop the load in flight, if there is one, and take back the [stack] mutation of the navigation that
     * started it — unless [pendingLoadKind]'s table says this exact pair must not.
     *
     * **Must be called BEFORE the superseding navigation touches [stack].** Both undos are positional
     * (`removeLastOrNull` / `addLast`), so running one after the new press has already pushed or popped
     * takes back the wrong entry — which is how "Up during an in-flight Enter" emptied the stack and made
     * CANCEL close the browser from a subdirectory.
     *
     * Called before every new load and by [close]. Idempotent: with nothing in flight the job is null and
     * [supersedeLoad] was already cleared by the apply that completed it.
     */
    private fun supersedePendingLoad(by: FileBrowserLoadKind = FileBrowserLoadKind.OTHER) {
        val undo = supersedeLoad
        val pending = pendingLoadKind
        supersedeLoad = null
        pendingLoadKind = FileBrowserLoadKind.OTHER
        loadJob?.cancel()
        loadJob = null
        val consumed = pending == FileBrowserLoadKind.UP && by == FileBrowserLoadKind.UP
        if (!consumed) undo?.invoke()
    }

    /**
     * The second half of a load: commit the listing and re-seat focus in it.
     *
     * **Every piece of state this reads is read HERE, after the wait, never captured at the load's
     * start** -- the exception being [LoadContext], which is about the departed directory by definition.
     * The player can reach the queue or the search screen during an in-flight load, and a `browseActive`
     * decided before the wait would act on a scope that is no longer active: the earlier change's dead-D-pad bug,
     * rebuilt by a perf change. [currentPath] moves here too, atomically with [partition], so nothing
     * reading the path mid-flight sees a directory whose rows are not on screen yet.
     */
    private fun applyListing(path: String, next: FileBrowserPartition, captured: LoadContext) {
        val previousKeys = captured.previousKeys
        val previousFocus = captured.previousFocus

        hasListed = true
        currentPath = path
        // The image picker's view of the listing (a no-op in every other mode). The hidden count is written
        // before the pinned row's key is read below: whether that row exists depends on it.
        unfilteredPartition = next
        val view = fileBrowserImagePickListing(mode, imagePickShowsAll, next)
        imagePickHiddenCount = view.hiddenCount
        partition = view.partition
        if (knownDirectoryTiles.size > FileBrowserKnownDirectoryTilesCap) knownDirectoryTiles.clear()
        for (tile in next.tomes) if (tile.kind == FileBrowserTileKind.DIRECTORY) knownDirectoryTiles[tile.path] = tile

        // **P0 guard, the earlier change: this load can land while ANOTHER scope is active, and until now it acted on
        // that scope anyway.** `FileBrowserStore.deleteInManager` stages in a coroutine and calls
        // [refresh] when it finishes; the player is very likely on the queue screen by then, because
        // pressing START to check on a delete is the whole point of the queue. `clearFocus()` acts on the
        // ACTIVE scope (`DefaultFocusAuthority:184-191`) -- it would null the QUEUE screen's focused
        // button and park the cleared-sentinel in the queue's own memory -- and the closing
        // `requestFocus` would then be refused silently, because `DefaultFocusAuthority.requestFocus`
        // returns at `if (entry.scope != activeScope) return` for a browse key. Net effect on device: a
        // dead D-pad on a queue screen that looks ready. BUG#01 exactly, on the screen this PR adds.
        // (The same hole existed for search + an extraction refresh, masked only by
        // `publishSearchResults` repairing focus on the next batch.)
        //
        // When browse is NOT active there is nothing to clear -- `maybeSeedFocusOnRegister` returns early
        // on a foreign active scope, so the auto-seed this call exists to suppress cannot fire either --
        // and the retire pass below stays safe for the reason `close()` states: the focused key belongs to
        // another scope, so `removeEntryAndFallback`'s focused-key branch cannot match and no cascade runs.
        val browseActive = authority.state.value.scope == scope
        // clearFocus() before re-registering: FocusAuthority auto-seeds focus onto the first thing that
        // registers into an empty active scope -- relying on that implicitly would silently break the
        // moment anything else ever registered first. The landing below is the real, explicit contract.
        if (browseActive) authority.clearFocus()
        val commitKey = commitKeyForCurrentMode()
        // F-2. Read after commitKey exactly as commitKey is read after destinationPick is set (this
        // function's own contract, stated below at the CommitExtractDestination-style callers) --
        // backToOriginKeyForCurrentMode() derives from commitAvailable, which derives from commitKey's
        // own predicate, so the two can never disagree about whether the pinned row's second column
        // exists here.
        val backKey = backToOriginKeyForCurrentMode()
        tracker.update(authority, scope, partition, grid, commitKey, backKey)
        val keys = fileBrowserKeys(partition, grid, commitKey, backKey)
        // Per-directory focus memory (see [focusByDirectory]), else index 0. The remembered key must
        // still BE in the incoming listing -- validated against `keys` rather than trusted -- so a tile
        // that vanished while the browser was elsewhere degrades to index 0 instead of a dead
        // requestFocus. Both sides of that check are path-qualified, so a listing that merely REORDERED
        // still restores the same FILE, never whatever now sits where it used to.
        //
        // Index 0 in PICK mode is the commit affordance (policy 6, "a pinned action row at index
        // 0"): that is still where a FIRST visit to a directory lands, unchanged. What changed is that a
        // RETURN visit lands on the tile the player left. The navigation contract requires the row to be at index 0 and
        // to be a real D-pad-reachable focus participant; it does not require focus to be dumped onto it
        // on every single load, and doing so is what made backing out of a folder lose the player's place.
        val remembered = focusByDirectory[path]?.takeIf { it in keys }
        // **B-4.** The memory is deliberately checked first and usually answers: an extraction's refresh
        // leaves the focused tile in place, so `remembered` matches and this branch never runs. It runs
        // when the tile that held focus is GONE from a directory that is otherwise the same one -- which
        // is what a delete is. Falling to `keys.firstOrNull()` there would throw the player back to the
        // top of the listing every time they removed a file, the same navigation tax [focusByDirectory]
        // exists to remove, arriving by a different door. See [fileBrowserSurvivingNeighbour] for why the
        // authority's own `removeEntryAndFallback` does not and cannot catch this.
        val neighbour = if (remembered == null) {
            fileBrowserSurvivingNeighbour(previousKeys, previousFocus, keys)
        } else {
            null
        }
        // The image picker's pinned row is a switch, not a place to start: a first visit lands on the first
        // folder or image, and only an empty listing leaves the player on the row.
        val firstKey = if (commitPurposeForCurrentMode() == FileBrowserCommitPurpose.TOGGLE_IMAGE_FILTER) {
            keys.firstOrNull { it != commitKey } ?: commitKey
        } else {
            keys.firstOrNull()
        }
        val landing = remembered ?: neighbour ?: firstKey
        if (browseActive) {
            landing?.let(authority::requestFocus)
        } else {
            // Focus belongs to another screen right now and must not be taken from it. Writing the landing
            // into the browse scope's memory instead means `popScope` hands it over the moment that screen
            // closes -- the player leaves the queue and lands on the tile beside the one they deleted,
            // with no repair pass, no second requestFocus, and no frame where nothing is focused.
            landing?.let { authority.rememberFocusForScope(scope, it) }
        }
    }

    /** A plain, synchronous read of the focused tile, for [dispatch]. Not Compose-observable -- see
     * [FileBrowserSurface], which derives its own reactive copy from a *collected*
     * `FocusAuthority.state` instead of calling this. */
    internal fun focusedTile(): FileBrowserTile? {
        val focusedKey = authority.state.value.takeIf { it.scope == scope }?.focused
        return focusedTileAt(partition, focusedKey)
    }

    // ──────────────────────────────────────────────────────────── F-1 · the SELECT action overlay ──
    //
    //  R-3: SELECT opens it,
    // Delete moves inside as a row -- `FileBrowserCommand.OpenActionOverlay` replaces
    // `FileBrowserCommand.Delete`. R-13: X/SECONDARY goes quiet in MANAGE once this ships (see
    // `fileBrowserCommand`'s SECONDARY arm). Everything in this region is the door and the row activation;
    // the row MODEL is `FileBrowserActionOverlayChoice.kt`, the SCREEN is
    // `FileBrowserActionOverlaySurface.kt`.

    /** An eighth [FocusScope], on every chooser in this browser's own convention: a shared key would evict
     * a tile from the graph underneath it. */
    val actionOverlayScope: FocusScope = FocusScope("${scope.value}/actions")

    /**
     * The open overlay, or null. `by mutableStateOf` for [extractChooser]'s reason: [FileBrowserSurface]
     * reads it to decide whether to compose this screen at all, so a plain field would leave the screen
     * invisible until something else recomposed.
     */
    internal var actionOverlay: FileBrowserActionOverlayState? by mutableStateOf(null)
        private set

    /** Whether the overlay is showing. */
    internal val actionOverlayOpen: Boolean get() = actionOverlay != null

    private val actionOverlayTracker = FileBrowserActionOverlayFocusTracker()

    /**
     * Open the overlay for [path] of tile-kind [kind]. Idempotent, and fails closed on an empty row list —
     * [openTransferChooser]'s own two gates, restated for the identical reason: this function is
     * `internal` and reachable from outside the dispatch table, so it is a door rather than a private
     * helper that stops every caller, not only the one dispatch table's own gate already stops.
     *
     * Register-then-remember-then-push-then-focus, every chooser in this module's idiom. Focus is seeded
     * explicitly to row 0 rather than to whatever [actionOverlayScope] remembers, for
     * [openTransferChooser]'s exact reason: the keys are kind-derived
     * ([FileBrowserActionOverlayFocus.optionKey]), not tile-derived, so a remembered `DELETE` from the
     * last tile would validate perfectly against this one's rows and land the ring on a row that is not
     * the safest one for a tile the player has not read yet — landing on row 0 is the module-wide rule
     * (design spec §1b).
     */
    internal fun openActionOverlay(path: String, kind: FileBrowserTileKind) {
        if (actionOverlayOpen) return
        val rows = fileBrowserActionOverlayRows(kind, deleteAvailable, transferChoiceAvailable, renameAvailable)
        if (rows.isEmpty()) return
        actionOverlay = FileBrowserActionOverlayState(path = path, kind = kind, rows = rows)
        actionOverlayTracker.update(authority, actionOverlayScope, rows)
        val landing = fileBrowserActionOverlayKeys(rows).firstOrNull()
        landing?.let { authority.rememberFocusForScope(actionOverlayScope, it) }
        authority.pushScope(actionOverlayScope)
        landing?.let(authority::requestFocus)
    }

    /**
     * Leave the overlay for the listing underneath. Idempotent.
     *
     * **Pop before clear**, the ordering every close in this class documents at length. Nothing here
     * restores browse focus by hand: `popScope` restores `focusMemory[scope]`, which is the tile the
     * player pressed SELECT on.
     */
    fun closeActionOverlay() {
        if (!actionOverlayOpen) return
        if (authority.state.value.scope == actionOverlayScope) authority.popScope()
        actionOverlayTracker.clear(authority)
        actionOverlay = null
    }

    /**
     * The overlay's own dispatch — a hard modal, exactly like every other chooser in this module.
     *
     * **CANCEL closes the overlay before it can mean Up**, [dispatchExtract]'s own reasoning: this
     * function runs ahead of the browse table (see [dispatch]), so B here never reaches the branch where
     * CANCEL walks the player out of the directory underneath.
     *
     * **SELECT is explicitly swallowed, not a toggle-close** — design spec §5, and deliberately UNLIKE X's
     * own courtesy-close on the extract/transfer choosers (`dispatchExtract`/`dispatchTransfer`'s
     * SECONDARY arm closes the chooser X opened). This overlay is not X's chooser, and the established
     * polarity for "the same button that opened a sub-flow, pressed again while it's open" is
     * `SECONDARY`'s own during an open destination pick: `Consumed`, not a restart. B remains the one way
     * out, matching every chooser in this module.
     */
    private fun dispatchActionOverlay(action: ControllerAction): Boolean {
        when (action) {
            ControllerAction.NAV_UP, ControllerAction.NAV_DOWN,
            ControllerAction.NAV_LEFT, ControllerAction.NAV_RIGHT,
            -> authority.moveFocus(action)

            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> closeActionOverlay()

            ControllerAction.CONFIRM -> activateFocusedActionOverlayOption()

            ControllerAction.SYSTEM_SELECT -> Unit

            // Everything else swallowed: the shoulders (nothing to page), START, both triggers and every
            // remaining SYSTEM_*/GLOBAL_*. A short row list is not a screen that needs them.
            else -> Unit
        }
        return true
    }

    /**
     * Run the focused row.
     *
     * **The overlay is closed BEFORE its sink fires**, [activateFocusedDiscSetOption]'s own ordering:
     * every sink here either reloads the listing (`beginTransferPick`/`offerExtract`) or stages a delete
     * whose queue card is about to appear, and a screen still up over a listing that just changed is the
     * shape that produced this module's stale-affordance bugs.
     *
     * **Sequential close-then-open, not nesting.** `beginTransferPick`/`offerExtract` are free to push
     * their OWN scope on top of the browse scope because the overlay's own scope has already been popped —
     * mirroring `activateFocusedTransferOption`/`activateFocusedExtractOption`'s identical close-then-open
     * shape one section up, not the DiscSet/destination-list nesting shape. Pushing a second scope on top
     * of an overlay scope that is never popped would leave `actionOverlayScope` buried on the real stack
     * with its own state already nulled by a later defensive `closeActionOverlay()` call
     * (`open`/`close`/`openSearch`/`openQueue` all carry one) — an orphaned, contentless scope
     * [dispatch]'s own top guard would then refuse all input against. Closing first is what keeps that
     * unreachable.
     */
    private fun activateFocusedActionOverlayOption() {
        val state = actionOverlay ?: return
        val focused = authority.state.value.takeIf { it.scope == actionOverlayScope }?.focused
        val kind = FileBrowserActionOverlayFocus.optionOf(focused)?.takeIf { it in state.rows } ?: return
        closeActionOverlay()
        when (kind) {
            FileBrowserActionOverlayRowKind.MOVE ->
                beginTransferPick(state.path, FileBrowserTransferKind.MOVE, state.kind)
            FileBrowserActionOverlayRowKind.COPY ->
                beginTransferPick(state.path, FileBrowserTransferKind.COPY, state.kind)
            // Byte-for-byte SECONDARY's own pre-R-13 archive arm, relocated rather than rewritten: the
            // chooser when the modern sinks are wired, the legacy sibling-directory extract when they are
            // not -- see FileBrowserActionOverlayChoice.kt's own KDoc for why this row carries no
            // extractChoiceAvailable gate of its own.
            FileBrowserActionOverlayRowKind.EXTRACT ->
                if (extractChoiceAvailable) offerExtract(state.path) else onExtract(state.path)
            // Reported, never performed here -- FileBrowserCommand.OpenActionOverlay's own KDoc: staging,
            // the Undo handle and the queue entry all belong to FileBrowserStore; this module never
            // touches a file.
            FileBrowserActionOverlayRowKind.DELETE -> onDelete?.invoke(state.path)
            // Opens Mimir's own on-screen keyboard, never a system dialog; the sink fires on Done.
            FileBrowserActionOverlayRowKind.RENAME -> openRename(state.path)
        }
    }

    /** A UI-originated tap on an overlay row — [handleDiscSetTap]'s "one true path" discipline. */
    internal fun handleActionOverlayTap(key: FocusKey) {
        if (!actionOverlayOpen || authority.state.value.scope != actionOverlayScope) return
        authority.requestFocus(key)
        dispatch(ControllerAction.CONFIRM)
    }
}

/**
 * What a load is FOR, as far as `FileBrowserController.stack` is concerned.
 *
 * Only exists because a load can now outlive the press that started it. While a listing is in flight the
 * player can press again, and whether the abandoned press's stack mutation comes back off depends on what
 * both presses were — see `FileBrowserController.pendingLoadKind` for the table and
 * `FileBrowserAsyncLoadTest`'s four mixed-pair cases for the proof.
 *
 * [OTHER] covers everything that is not a navigation: `open`, `refresh`, the pick reload, `revealPath`'s
 * absolute rebuild. None of them consumes or creates a stack entry, and all of them cancel a navigation
 * cleanly rather than inheriting it.
 */
internal enum class FileBrowserLoadKind { ENTER, UP, OTHER }
