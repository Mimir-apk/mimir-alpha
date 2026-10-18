package com.gruning.mimir.rewrite

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import android.util.Log

internal const val MimirInstancesLogTag = "MimirInstances"

/** Past the start transition, so a copy the sweep removes is no longer on screen. */
internal const val StaleMainTaskSweepDelayMs = 750L

/** [visible] is null before API 32, where the platform cannot say. [home]: the task was opened by a HOME intent. */
internal data class MimirAppTaskSnapshot(
    val taskId: Int,
    val baseClassName: String?,
    val visible: Boolean?,
    val home: Boolean = false,
)

// singleTask reuses only a task of a compatible type, so a HOME start beside a launcher-opened Mimir makes a second task.
// Only the copy on screen sweeps, and a launcher-opened copy never removes the home one: that one removes it on return.
internal fun staleMainTaskIds(
    currentTaskId: Int,
    tasks: List<MimirAppTaskSnapshot>,
    mainActivityClassName: String,
): List<Int> {
    val mainTasks = tasks.filter { it.baseClassName == mainActivityClassName }
    val current = mainTasks.firstOrNull { it.taskId == currentTaskId } ?: return emptyList()
    if (current.visible == false) return emptyList()
    return mainTasks
        .filter { it.taskId != currentTaskId && it.visible != true && (current.home || !it.home) }
        .map { it.taskId }
        .distinct()
}

/**
 * The MainActivity copies between onStart and onStop. Main thread only; a process death clears it with everything else.
 *
 * Android stops a copy that another one covers only after that one has started, so beside a HOME copy (walk-32m) the
 * launcher-opened copy's stop arrives while Mimir is still on screen: only a stop that leaves no copy started is Mimir
 * leaving the screen. A configuration change stops the old instance before the new one starts, so it still reads as leaving.
 */
internal class MimirStartedCopies {
    private val started = HashSet<Any>()

    fun started(copy: Any) {
        started += copy
    }

    /** Removes [copy]; true when no other copy is started. */
    fun stoppedLeavingScreen(copy: Any): Boolean {
        started -= copy
        return started.isEmpty()
    }

    fun anyStarted(): Boolean = started.isNotEmpty()
}

internal val MimirMainStartedCopies = MimirStartedCopies()

// getAppTasks reads recents, so a task whose process already died (no Activity left to finish) is still removed.
internal fun removeStaleMainTasks(activity: Activity) {
    if (activity.isFinishing || activity.isDestroyed) return
    runCatching {
        val manager = activity.getSystemService(ActivityManager::class.java) ?: return
        val appTasks = manager.appTasks.mapNotNull { task -> runCatching { task.taskInfo to task }.getOrNull() }
        val snapshots = appTasks.map { (info, _) ->
            MimirAppTaskSnapshot(
                taskId = info.taskId,
                baseClassName = info.baseIntent?.component?.className ?: info.baseActivity?.className,
                visible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) info.isVisible else null,
                home = info.baseIntent?.hasCategory(Intent.CATEGORY_HOME) == true,
            )
        }
        for (taskId in staleMainTaskIds(activity.taskId, snapshots, MainActivity::class.java.name)) {
            val task = appTasks.firstOrNull { (info, _) -> info.taskId == taskId }?.second ?: continue
            val removed = runCatching { task.finishAndRemoveTask() }
            Log.i(
                MimirInstancesLogTag,
                "removed stale Mimir task=$taskId kept=${activity.taskId} ok=${removed.isSuccess}" +
                    (removed.exceptionOrNull()?.let { " reason=${it.javaClass.simpleName}" } ?: ""),
            )
        }
    }.onFailure { failure -> Log.w(MimirInstancesLogTag, "stale task sweep failed", failure) }
}
