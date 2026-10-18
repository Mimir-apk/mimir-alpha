package com.gruning.mimir.rewrite.feature.library

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi

/**
 * Counts how many times each composable function BODY runs. The Compose compiler brackets every composable body with a trace event
 * that fires only when the body actually executes (a skipped call fires nothing), so a count taken between two frames is "which
 * composables recomposed in them", by name. Install it around a measured stretch with [measure]; the tracer is process-wide, so it
 * is always removed again.
 */
@OptIn(InternalComposeTracingApi::class)
internal class ComposableInvocationTally(private val keepLines: Boolean = false) : CompositionTracer {
    private val counts = LinkedHashMap<String, Int>()

    override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
        counts.merge(if (keepLines) info else info.substringBefore(" ("), 1, Int::plus)
    }

    override fun traceEventEnd() = Unit

    override fun isTraceInProgress(): Boolean = true

    /** Runs [block] with the tally installed and returns what ran during it. */
    fun measure(block: () -> Unit): Map<String, Int> {
        counts.clear()
        Composer.setTracer(this)
        try {
            block()
        } finally {
            Composer.setTracer(Idle)
        }
        return LinkedHashMap(counts)
    }

    /** The tracer left installed afterwards: the runtime takes no null, and one that reports no trace in progress makes every body skip its events. */
    private object Idle : CompositionTracer {
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) = Unit
        override fun traceEventEnd() = Unit
        override fun isTraceInProgress(): Boolean = false
    }

    companion object {
        /** The runs of every function whose qualified name contains [fragment]. */
        fun Map<String, Int>.runsOf(fragment: String): Int = entries.filter { fragment in it.key }.sumOf { it.value }

        fun Map<String, Int>.total(): Int = values.sum()

        /** The runs of exactly [qualified] (a function's own name, no source line), not of the lambdas and helpers whose names contain it. */
        fun Map<String, Int>.bodiesOf(qualified: String): Int = this[qualified] ?: 0

        fun Map<String, Int>.top(n: Int = 25): String =
            entries.sortedByDescending { it.value }.take(n).joinToString("\n") { "  ${it.value} x ${it.key.substringAfterLast('.')}" }

        /** Only the project's own composables (the runtime and library helpers are noise), most frequent first. */
        fun Map<String, Int>.ours(n: Int = 60): String =
            entries.filter { it.key.startsWith("com.gruning") }.sortedByDescending { it.value }.take(n)
                .joinToString("\n") { "  ${it.value} x ${it.key.removePrefix("com.gruning.mimir.rewrite.")}" }

        /** The project's own composables in the order they first ran, which is the order of the call tree (parents before children). */
        fun Map<String, Int>.oursInOrder(): String =
            entries.filter { it.key.startsWith("com.gruning") }
                .joinToString("\n") { "  ${it.value} x ${it.key.removePrefix("com.gruning.mimir.rewrite.")}" }

        fun Map<String, Int>.oursTotal(): Int = entries.filter { it.key.startsWith("com.gruning") }.sumOf { it.value }
    }
}
