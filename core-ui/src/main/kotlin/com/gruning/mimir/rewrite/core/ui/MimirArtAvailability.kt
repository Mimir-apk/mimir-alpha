package com.gruning.mimir.rewrite.core.ui

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The ONE off-main answer to "is this art source wired?" . A local source (a [File], an absolute path,
 * a `file:` URI) whose file no longer exists is NOT wired, so it takes the normal no-art fallback; every other source counts as
 * wired. The `File.exists()` that decides it is a disk stat, so it never runs on the composition thread: [rememberMimirArtWired]
 * resolves it once per model on [Dispatchers.IO].
 *
 * While the stat is pending the source is treated as WIRED, so the painter loads normally under the 10-01 fade (nothing is drawn
 * while a wired art loads) and flips to the no-art fallback only if the stat comes back missing. Pending = wired, always, and a
 * cached "missing" NEVER changes that: art restored at a path, then remounted within [FreshMs], must load, not show the fallback
 * (and not stay on it for the life of the mount), so a missing verdict seeds nothing and spares no stat. Missing files are rare,
 * so the one extra stat on a remount is the price.
 *
 * One small bounded cache serves the re-check. Only a FRESH "exists" verdict (stat'ed within [FreshMs], 5 s) is trusted: a mount
 * that finds one does NOT stat again, so seats that remount during a scroll repeat no I/O. The cache holds at most [Capacity]
 * (256) paths. A "missing" verdict is kept only so that verdicts are ordered: each stat takes a TICKET from one monotonic counter
 * when it starts, and a stat whose ticket is older than the one already remembered never overwrites it, so concurrent cold
 * resolutions of one path settle on the newest answer whichever finishes last. The order is a ticket, never a millisecond stamp:
 * two stats that start in the same millisecond have different tickets, so neither can be taken for the other's equal.
 *
 * A capacity clear forgets every verdict, including newer ones, so it must not let a stat that STARTED before one of them land after
 * the clear as though nothing newer had ever been known: a clear raises a floor at the newest ticket it forgot, and a stat holding
 * a ticket at or below the floor is answered to its caller but never remembered. The next mount stats afresh.
 */
object MimirArtAvailability {
    /** How long an "exists" verdict stays fresh: a stat younger than this spares a mount a new stat. */
    const val FreshMs = 5_000L

    /** The most paths remembered. At the bound, expired verdicts go first, then (if every one is fresh) the cache restarts. */
    const val Capacity = 256

    /**
     * [atMs] is when the stat that produced this verdict STARTED, on [nowMs]'s monotonic clock: it decides FRESHNESS only.
     * [ticket] is the order the stats started in: it decides which verdict wins.
     */
    private class Verdict(val exists: Boolean, val atMs: Long, val ticket: Long)

    private val known = ConcurrentHashMap<String, Verdict>()

    /** The tickets handed out, one per stat started; strictly increasing across threads. */
    private val tickets = AtomicLong()

    /** A capacity clear raised this to the newest ticket it forgot: a stat holding a ticket at or below it is not remembered. */
    @Volatile
    private var clearedThrough = 0L

    /** Held across [record] so the clear, the floor and the write are one step. */
    private val lock = Any()

    /** The monotonic clock the freshness and the verdict order run on; a host test moves it. */
    @VisibleForTesting
    @Volatile
    internal var nowMs: () -> Long = { System.nanoTime() / 1_000_000L }

    /**
     * The dispatcher the stat runs on: IO in production. A host test that composes an art surface sets it to an inline dispatcher so the
     * verdict lands deterministically (a paused compose clock does not wait on a real IO thread); production never assigns it.
     */
    @VisibleForTesting
    @Volatile
    internal var dispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * Whether a FRESH "exists" verdict is remembered for [path]: the only verdict that is trusted. A remembered "missing", fresh or
     * not, answers false here (it is never trusted: the file may have been restored since). A map read: no I/O.
     */
    fun peekExists(path: String): Boolean {
        val verdict = known[path] ?: return false
        return verdict.exists && nowMs() - verdict.atMs <= FreshMs
    }

    /** The ticket for a stat that is about to start: strictly greater than every ticket handed out before it. */
    fun nextTicket(): Long = tickets.incrementAndGet()

    /**
     * Remembers a stat of [path] that took [ticket] when it started (and, for freshness, started at [startedAtMs]) and answered
     * [exists], unless a stat with a LATER ticket is already remembered (the older answer arrived late). Returns the verdict that
     * stands afterwards. A stat that started before the last capacity clear is not remembered at all and gets its own answer back
     * (or the newer verdict that has landed since).
     */
    fun record(path: String, exists: Boolean, ticket: Long = nextTicket(), startedAtMs: Long = nowMs()): Boolean =
        synchronized(lock) {
            if (ticket <= clearedThrough) return@synchronized known[path]?.exists ?: exists
            if (known.size >= Capacity) {
                val now = nowMs()
                // Remove an expired entry only if it is still THAT entry: a concurrent stat may have refreshed the path since.
                for ((key, verdict) in known) if (now - verdict.atMs > FreshMs) known.remove(key, verdict)
                if (known.size >= Capacity) {
                    // The clear forgets verdicts. A stat that started before the NEWEST of them must not land afterwards as if that
                    // verdict had never been said, so the floor is the newest ticket forgotten; a stat that started after it is
                    // newer than everything the clear took, and is remembered as usual.
                    clearedThrough = maxOf(clearedThrough, known.values.maxOf { it.ticket })
                    known.clear()
                    if (ticket <= clearedThrough) return@synchronized exists
                }
            }
            known.compute(path) { _, old ->
                if (old != null && old.ticket > ticket) old else Verdict(exists, startedAtMs, ticket)
            }!!.exists
        }

    /**
     * The answer for [path]: a fresh remembered "exists" as it stands, with no stat; otherwise a stat on the IO dispatcher, which is
     * remembered (a remembered "missing" is never reused: it always stats). The answer is the verdict that stands once the stat
     * is recorded, so a stat overtaken by a newer one reports the newer. [fileExists] is injectable so the law is host-testable.
     */
    suspend fun resolve(path: String, fileExists: (String) -> Boolean = { File(it).exists() }): Boolean {
        if (peekExists(path)) return true
        return withContext(dispatcher) {
            // The ticket is taken here, on the thread that stats, immediately before the stat: ticket order is stat order.
            val ticket = nextTicket()
            val startedAtMs = nowMs()
            record(path, fileExists(path), ticket, startedAtMs)
        }
    }

    /** Empties the remembered answers (a host test starts from a cold process). */
    fun clearForTest() = known.clear()

    /**
     * [dispatcher] for a host test in ANOTHER module (the `:app` composition tests cannot reach the internal property): an inline
     * dispatcher lands the verdict during the composition's own idle, with no real IO thread and no wall-clock wait. Null puts IO back;
     * a test that set one restores it in its teardown, since the dispatcher is process-wide. Production never calls it.
     */
    @VisibleForTesting
    fun useDispatcherForTest(testDispatcher: CoroutineDispatcher?) {
        dispatcher = testDispatcher ?: Dispatchers.IO
    }

    /** How many paths are remembered; for the bound's own test. */
    @VisibleForTesting
    internal fun size(): Int = known.size
}

/** Whether [model] names a source at all: null and blank do not. */
internal fun mimirArtHasSource(model: Any?): Boolean = when (model) {
    null -> false
    is CharSequence -> model.isNotBlank()
    else -> true
}

/** The filesystem path whose existence decides [model], or null when no local file is named (remote, `content:`, a resource id). */
internal fun mimirArtLocalFile(model: Any?): String? = when (model) {
    null -> null
    is File -> model.path
    else -> mimirArtLocalPath(model.toString())
}

/**
 * Whether [model] is wired BEFORE its file has been stat'ed (no I/O): false for no source, true for every source, a local file
 * included (the pending rule). No cached verdict seeds it: a remembered "missing" would flash the fallback over art restored at
 * that path, and an "exists" is what pending already says.
 */
internal fun mimirArtWiredBeforeCheck(model: Any?): Boolean = mimirArtHasSource(model)

/**
 * Whether [model] is a wired art source, resolved OFF the main thread, once per [model] (never per recomposition or per frame). See
 * [MimirArtAvailability]. Every existence guard in the product consumes this instead of calling `File.exists()` in composition.
 */
@Composable
fun rememberMimirArtWired(model: Any?): Boolean {
    val wired = remember(model) { mutableStateOf(mimirArtWiredBeforeCheck(model)) }
    LaunchedEffect(model) {
        val local = mimirArtLocalFile(model)
        if (local != null && mimirArtHasSource(model)) wired.value = MimirArtAvailability.resolve(local)
    }
    return wired.value
}

/**
 * A source verdict an outer surface has already resolved, handed DOWN to the art composables it hosts, so the plate and the content
 * of one frame can never disagree and the same file is not stat'ed twice. [model] is what was resolved; an inner composable that
 * is given the same model reads [wired] instead of resolving its own.
 */
internal class MimirArtVerdict(val model: Any?, val wired: Boolean)

internal val LocalMimirArtVerdict = compositionLocalOf<MimirArtVerdict?> { null }

/**
 * [rememberMimirArtWired] for a composable that may sit inside a surface that already resolved the same [model]
 * ([LocalMimirArtVerdict]): it takes that verdict and starts no resolver of its own, otherwise it resolves as ever.
 */
@Composable
internal fun rememberMimirArtWiredOrInherited(model: Any?): Boolean {
    val inherited = LocalMimirArtVerdict.current?.takeIf { it.model == model }
    val own = rememberMimirArtWired(if (inherited == null) model else null)
    return inherited?.wired ?: own
}
