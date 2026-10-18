package com.gruning.mimir.rewrite

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.gruning.mimir.rewrite.core.companionnative.ActivityResumedObservation
import com.gruning.mimir.rewrite.core.companionnative.isCompanionShellPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * D41 (Thor 2026-10-08): notices that a task Mimir did not launch has covered display 4 while Mimir is presenting there, and says which one.
 *
 * ## The defect
 *
 * An edge-back reclaim left a Quick-Launched Chrome in display 4's stack, below Mimir's floor. The ᛗ opened Android's Recents on Main, the player
 * picked Chrome, and Chrome became the top resumed activity on display 4 while Mimir's Presentation (a window type that sorts above activity
 * windows on the Thor) stayed up. Mimir logged nothing: `Yielded` was reachable only through a launch lease (`CompanionReturnActivity` reporting
 * itself covered), and the foreground follow loop was asleep (`MIRROR_PRESENTED`, Main resumed, `externalFollowActive=false`), so not even a
 * package name was observed. Chrome's omnibox keyboard drew over Mimir; hiding it showed Mimir's own page.
 *
 * ## The evidence, and why it takes both halves
 *
 * - **The floor's stop is display-exact.** `CompanionSecondaryHomeActivity` lives on display 4 alone, and Android stops it only when an opaque
 *   activity covers it there (or the display goes dark). A dialog-themed permission activity pauses it and never stops it, which is what keeps
 *   a permission dialog, the IME, a toast and Recents-on-Main out: none of them stops the floor. It is the converse of "the floor resumed
 *   proves nothing covers display 4" ([CompanionReturnCoordinator.noteSecondaryHomeResumed]).
 * - **A UsageStats resume names the package, and nothing more.** It carries no display. Alone it would yield the Bottom when the player picks a
 *   Main app from Recents. So the resume only counts when it falls inside the floor's own pause-to-stop span.
 *
 * Both are read for a bounded window opened by the floor's pause ([WatchMs]). The resume subscription is the launch cover's seam
 * ([com.gruning.mimir.rewrite.core.companionnative.ActivityResumeObserver]), which polls whatever the display disposition: the shared follow
 * loop is asleep exactly when this is needed, and waking it for the whole of every Presenting would poll all session.
 *
 * ## What else resumes in the span
 *
 * Picking a task from Recents also dismisses Recents, and whatever sat under it on Main resumes in the same breath. That is not the cover, so it
 * is taken out before anything is decided: first every package Mimir's own session truth puts on Main ([mainSessionPackages], a cheap pre-filter),
 * then, if more than one package is still left, the package that has a task on THIS display wins ([displayTasks]: a privileged lookup, off the main
 * thread, whose answer is applied only if the watch is still the current one). Exactly one proven package yields; two, or none, do not.
 *
 * ## Fail closed
 *
 * No yield without all of: a disposition that is `Presenting` / `MirrorPresented` on this very display, with the device interactive, at the pause
 * AND at the stop (a launch lease or a reclaim means the floor is covered by Mimir's own host; sleep pauses then stops the floor); a stop that is not
 * a finish or a configuration change; a foreign package left in the span (none: a display going dark); and Usage Access (without it no resume is
 * ever delivered). The decision waits out [SettleMs] after the stop unless the span is already ambiguous, so a resume published a little late
 * (the Main app's is polled before the cover's, and the stop can land between) joins the span instead of leaving the lease on the wrong package. A
 * resume after that settle belongs to something that woke later, a Main game after a screen-off for one. Mimir's own
 * package and the home/launcher/SystemUI shells (by package, or by the resolved HOME / SECONDARY_HOME component: Recents is one) never count. A
 * running Main dual-screen game keeps its own hand-off: the runtime refuses ([CompanionRuntimeCoordinator.yieldToForeignTask]).
 *
 * Every method runs on the main thread, as the floor's lifecycle callbacks do.
 */
internal class CompanionFloorCoverWatch(
    private val snapshot: () -> CompanionRuntimeSnapshot,
    private val observeResumes: (sinceEpochMs: Long, onResumed: (ActivityResumedObservation) -> Unit) -> AutoCloseable,
    private val postToMain: (() -> Unit) -> Unit,
    private val scheduleOnMain: (delayMs: Long, task: () -> Unit) -> AutoCloseable,
    private val epochMs: () -> Long,
    private val ownPackageName: String,
    private val yieldTo: (displayId: Int, packageName: String) -> Boolean,
    /** Home, launcher and SystemUI resumes, by package or by component; the follow loop's own filter in production. */
    private val isShellResume: (packageName: String, className: String) -> Boolean = { packageName, _ -> isCompanionShellPackage(packageName) },
    /** False while the device is asleep: sleep pauses and then stops the floor, and is not a cover. */
    private val isInteractive: () -> Boolean = { true },
    /** The packages Mimir's session ledger places on Main. */
    private val mainPackages: () -> Set<String> = { emptySet() },
    /**
     * Whether each package has a task on [displayId], answered later on the main thread. The default answers nothing, so an ambiguous span stays
     * ambiguous. A callback that never comes leaves the watch to its own expiry.
     */
    private val displayTasks: (displayId: Int, packages: List<String>, onResolved: (Map<String, VendorTaskOutcome>) -> Unit) -> Unit =
        { _, _, onResolved -> onResolved(emptyMap()) },
) {
    private class Watch(val displayId: Int, val pausedAtMs: Long) {
        var observation: AutoCloseable = AutoCloseable { }
        var expiry: AutoCloseable = AutoCloseable { }
        var settle: AutoCloseable? = null
        var stoppedAtMs: Long? = null

        /** The span holds more than one candidate and the display-task lookup is out: nothing else is read until it answers. */
        var resolving = false

        /** Each foreign package that resumed in the span, with its latest resume. */
        val foreign = linkedMapOf<String, Long>()

        fun close() {
            observation.close()
            expiry.close()
            settle?.close()
        }
    }

    private var watch: Watch? = null

    /** The floor stopped being the resumed activity: open the window if Mimir is presenting here. */
    fun floorPaused(displayId: Int?) {
        end()
        if (displayId == null || !coverable(displayId)) return
        val opened = Watch(displayId, epochMs())
        watch = opened
        opened.observation = observeResumes(opened.pausedAtMs - ResumeLeadMs) { resumed ->
            postToMain { onResumed(opened, resumed) }
        }
        opened.expiry = scheduleOnMain(WatchMs) { if (watch === opened) end() }
    }

    /** The floor is resumed again (or its display is unknown): whatever covered it is gone, nothing to follow. */
    fun floorResumed(displayId: Int?) {
        if (displayId == null || watch?.displayId == displayId) end()
    }

    /**
     * The floor is no longer visible: the cover is opaque. A span that is already ambiguous goes to the lookup at once; one package, or none yet,
     * waits for [SettleMs] first, so a resume that UsageStats publishes a little after the stop joins the span before anything is decided.
     */
    fun floorStopped(displayId: Int?, finishing: Boolean, changingConfigurations: Boolean) {
        val current = watch ?: return
        if (current.displayId != displayId) return
        if (finishing || changingConfigurations || !coverable(current.displayId)) {
            end()
            return
        }
        current.stoppedAtMs = epochMs()
        if (!decide(current, settled = false)) {
            current.settle = scheduleOnMain(SettleMs) { if (watch === current && !decide(current, settled = true)) end() }
        }
    }

    private fun onResumed(current: Watch, resumed: ActivityResumedObservation) {
        if (watch !== current || current.resolving) return
        val packageName = resumed.packageName
        if (packageName == ownPackageName || isShellResume(packageName, resumed.className)) return
        val stoppedAt = current.stoppedAtMs
        if (stoppedAt != null && resumed.eventAtMs > stoppedAt + SettleMs) return
        current.foreign[packageName] = maxOf(current.foreign[packageName] ?: 0L, resumed.eventAtMs)
        decide(current, settled = false)
    }

    /**
     * True once the floor has stopped and the watch has what it needs (it yielded, ended, or is waiting on the lookup); false while it waits.
     * One candidate yields only when [settled], which is the settle timer: a second resume inside the settle turns the span ambiguous, and
     * deciding earlier would name the wrong package (the Main app's resume is polled before the cover's, and the stop can land between).
     */
    private fun decide(current: Watch, settled: Boolean): Boolean {
        if (current.stoppedAtMs == null) return false
        if (current.resolving) return true
        if (!coverable(current.displayId)) {
            end()
            return true
        }
        val onMain = mainPackages()
        val candidates = current.foreign.keys.filter { candidate -> onMain.none { it.equals(candidate, ignoreCase = true) } }
        when (candidates.size) {
            0 -> return false
            1 -> {
                if (!settled) return false
                val displayId = current.displayId
                end()
                yieldTo(displayId, candidates.single())
            }
            else -> {
                current.resolving = true
                current.settle?.close()
                displayTasks(current.displayId, candidates) { outcomes -> onResolved(current, candidates, outcomes) }
            }
        }
        return true
    }

    /** Back on the main thread with the lookup's answer: only a package PROVEN to hold a task on this display can be the cover, and only if it is the only one. */
    private fun onResolved(current: Watch, candidates: List<String>, outcomes: Map<String, VendorTaskOutcome>) {
        if (watch !== current) return
        val displayId = current.displayId
        val onDisplay = candidates.filter { outcomes[it] is VendorTaskOutcome.Found }
        val stillCoverable = coverable(displayId)
        end()
        if (stillCoverable && onDisplay.size == 1) yieldTo(displayId, onDisplay.single())
    }

    private fun coverable(displayId: Int): Boolean = floorCoverable(snapshot(), displayId) && isInteractive()

    private fun end() {
        watch?.close()
        watch = null
    }

    companion object {
        /** A resume stamped slightly before the pause callback still belongs to the same transition. */
        const val ResumeLeadMs = 500L

        /** The pause-to-stop span: a pick from Recents stops the floor well inside it; a longer gap is not one transition. */
        const val WatchMs = 6_000L

        /** How long after the stop a resume may still be published (UsageStats stamps an event, then publishes it a little later). */
        const val SettleMs = 500L

        /** The process watch: wired to the runtime, the foreground monitor's resume seam, the privileged task lookup and the main looper. */
        fun forProcess(
            context: Context,
            snapshot: () -> CompanionRuntimeSnapshot,
            yieldTo: (displayId: Int, packageName: String) -> Boolean,
        ): CompanionFloorCoverWatch {
            val appContext = context.applicationContext
            val handler = Handler(Looper.getMainLooper())
            return CompanionFloorCoverWatch(
                snapshot = snapshot,
                // Read when a watch actually opens, never at construction: a floor that pauses with no display to follow builds nothing.
                observeResumes = { since, onResumed ->
                    CompanionProcessRuntime.services(appContext).foregroundMonitor.observeActivityResumes(since, onResumed)
                },
                postToMain = { block -> handler.post(block) },
                scheduleOnMain = { delayMs, task ->
                    val runnable = Runnable(task)
                    handler.postDelayed(runnable, delayMs)
                    AutoCloseable { handler.removeCallbacks(runnable) }
                },
                epochMs = System::currentTimeMillis,
                ownPackageName = appContext.packageName,
                yieldTo = yieldTo,
                isShellResume = { packageName, className ->
                    CompanionProcessRuntime.services(appContext).foregroundMonitor.isShellResume(packageName, className)
                },
                isInteractive = { appContext.getSystemService(PowerManager::class.java)?.isInteractive != false },
                mainPackages = { mainSessionPackages(MimirSessionAuthority.state.value) },
                displayTasks = { displayId, packages, onResolved ->
                    // The lookup is a privileged transaction: off the main thread, as the Yielded collector's landing hook runs it. The process
                    // scope is Main.immediate, so the answer is back on the main thread for the watch to check against itself.
                    CompanionProcessRuntime.processScope.launch {
                        val gateway = CompanionProcessRuntime.services(appContext).privilegedUtility
                        val outcomes = withContext(Dispatchers.IO) {
                            packages.associateWith { packageName ->
                                runCatching { gateway.resolveTaskOutcome(packageName, displayId) }
                                    .getOrElse { VendorTaskOutcome.Unavailable("threw") }
                            }
                        }
                        onResolved(outcomes)
                    }
                },
            )
        }
    }
}

/**
 * Mimir is presenting on [displayId] and holds no lease there: the only dispositions in which a stopped floor can mean a foreign task. A launch
 * lease or a reclaim in flight means the floor is covered by Mimir's own host; `Yielded` already knows; `RecoveryNeeded` has its own reclaim.
 */
internal fun floorCoverable(snapshot: CompanionRuntimeSnapshot, displayId: Int): Boolean =
    snapshot.displayId == displayId &&
        (snapshot.disposition is SecondaryDisplayDisposition.Presenting ||
            snapshot.disposition is SecondaryDisplayDisposition.MirrorPresented)

/**
 * The packages Mimir's session ledger has live on Main: the PRIMARY app and a game placed on Main. A resume of one of them inside the span is Main
 * waking when Recents leaves, not the Bottom's cover. Ended and failed sessions are not live and are not listed, so a finished Main app cannot hide
 * the task that really covered the Bottom. Bottom sessions are never listed: they are the likely cover.
 */
internal fun mainSessionPackages(state: MimirSessionSnapshot): Set<String> = setOfNotNull(
    state.gameSession
        ?.takeIf { it.placement == MimirGamePlacement.MAIN && it.phase in ActivePresentationPhases }
        ?.targetPackageName,
    state.primaryAppSession
        ?.takeIf { it.phase in ActivePresentationPhases }
        ?.packageName,
)
