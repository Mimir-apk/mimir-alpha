package com.gruning.mimir.rewrite.core.metadata.online

import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * The HTTP connection a blocking call is using right now, so cancelling the coroutine can close it.
 *
 * A socket read in `HttpURLConnection` never looks at the thread's interrupt flag, so a cancelled coroutine that is parked in
 * one stays parked until the read times out (the game-music card kept showing "Running" for as long as a dead network took to
 * give up). Closing the connection from another thread is what unblocks it: [abort] does that, and a connection [attach]ed after
 * the abort is closed at once instead of being used.
 */
class ConnectionAbort {
    private val aborted = AtomicBoolean(false)

    @Volatile
    private var current: HttpURLConnection? = null

    /** Registers [connection] as the live one. False when the call was already aborted: the connection is closed and must not be used. */
    fun attach(connection: HttpURLConnection): Boolean {
        current = connection
        if (aborted.get()) {
            connection.disconnect()
            return false
        }
        return true
    }

    /** Closes the live connection, and any attached later. Safe from any thread, any number of times. */
    fun abort() {
        aborted.set(true)
        current?.disconnect()
    }
}

/**
 * Runs the BLOCKING [block] and, if the calling coroutine is cancelled meanwhile, calls [abort] from another thread so the
 * block comes back at once, then throws the cancellation. Whatever [block] returns or throws after an abort is discarded: the
 * caller sees a [kotlinx.coroutines.CancellationException], never a transport failure it would count and classify.
 *
 * The watcher runs on [Dispatchers.Default], not the caller's dispatcher: the caller's thread is the one stuck in [block].
 */
suspend fun <T> runAbortingOnCancel(abort: () -> Unit, block: () -> T): T = coroutineScope {
    val finished = AtomicBoolean(false)
    val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (!finished.get()) abort()
        }
    }
    try {
        block()
    } catch (failure: Throwable) {
        // A block that was aborted fails with whatever its transport throws; the cancellation is the truth, so it wins.
        currentCoroutineContext().ensureActive()
        throw failure
    } finally {
        finished.set(true)
        watcher.cancel()
    }
}
