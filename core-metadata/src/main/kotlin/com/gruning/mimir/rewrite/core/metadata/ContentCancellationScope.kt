package com.gruning.mimir.rewrite.core.metadata

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/**
 * One operation-scoped cancellation backstop for [ContentOpener] reads. Parent coroutine cancellation
 * immediately cancels registered hooks and closes every registered resource even while an IO thread is
 * blocked inside a hostile provider.
 */
class ContentCancellationScope {
    private val cancelled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val resources = ConcurrentHashMap.newKeySet<ResourceRegistration>()
    private val cancelHooks = ConcurrentHashMap.newKeySet<CancelHook>()

    val isCancelled: Boolean
        get() = cancelled.get()

    /** Test seam: registrations still tracked for scope-driven close. */
    internal fun activeRegistrationCount(): Int = resources.size

    /** Test seam: cancel hooks still registered and not yet dismissed or invoked. */
    internal fun activeCancelHookCount(): Int = cancelHooks.size

    fun throwIfCancelled() {
        if (isCancelled) throw CancellationException("content operation cancelled")
    }

    /** Runs [action] once when this scope is cancelled. The hook is removed when the returned handle closes. */
    fun registerOnCancel(action: () -> Unit): Closeable {
        val hook = CancelHook(action)
        if (isCancelled) {
            hook.invokeOnce()
            return Closeable { }
        }
        cancelHooks.add(hook)
        if (isCancelled) {
            cancelHooks.remove(hook)
            hook.invokeOnce()
            return Closeable { }
        }
        return Closeable { hook.dismiss() }
    }

    /**
     * Registers [resource] for scope-driven close. Registering after cancel closes [resource] immediately.
     * Closing the returned handle deregisters and closes [resource] once.
     */
    fun register(resource: Closeable): Closeable {
        val entry = ResourceRegistration(resource)
        if (cancelled.get() || closed.get()) {
            entry.closeOnce()
            return Closeable { }
        }
        resources.add(entry)
        if (cancelled.get()) {
            resources.remove(entry)
            entry.closeOnce()
            return Closeable { }
        }
        return Closeable {
            resources.remove(entry)
            entry.closeOnce()
        }
    }

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        val hooks = cancelHooks.toList()
        cancelHooks.clear()
        hooks.forEach { hook -> hook.invokeOnce() }
        closeRegistered()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancel()
    }

    private fun closeRegistered() {
        resources.forEach { entry -> entry.closeOnce() }
        resources.clear()
    }

    private class ResourceRegistration(
        private val delegate: Closeable,
    ) {
        private val closed = AtomicBoolean(false)

        fun closeOnce() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { delegate.close() }
        }
    }

    private inner class CancelHook(
        private val action: () -> Unit,
    ) {
        private val terminal = AtomicReference(CancelHookTerminal.ACTIVE)

        fun invokeOnce() {
            if (terminal.compareAndSet(CancelHookTerminal.ACTIVE, CancelHookTerminal.INVOKED)) {
                runCatching(action)
            }
        }

        fun dismiss() {
            if (terminal.compareAndSet(CancelHookTerminal.ACTIVE, CancelHookTerminal.DISMISSED)) {
                cancelHooks.remove(this)
            }
        }
    }

    private enum class CancelHookTerminal {
        ACTIVE,
        DISMISSED,
        INVOKED,
    }
}

/**
 * Opens one [ContentCancellationScope] for a suspend content operation. Parent cancellation cancels the
 * scope immediately, even when the IO thread is blocked inside a provider open/read.
 */
suspend fun <T> withContentCancellation(
    block: suspend (ContentCancellationScope) -> T,
): T {
    val scope = ContentCancellationScope()
    val relay = CompletableDeferred<Unit>(currentCoroutineContext()[Job])
    val handle = relay.invokeOnCompletion { cause ->
        if (cause is CancellationException) scope.cancel()
    }
    return try {
        block(scope)
    } finally {
        scope.close()
        handle.dispose()
        relay.complete(Unit)
    }
}
