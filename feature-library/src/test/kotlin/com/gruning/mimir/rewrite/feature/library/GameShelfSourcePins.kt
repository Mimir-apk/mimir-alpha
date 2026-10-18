package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertTrue

/**
 * Shared helpers for the tests that pin the shelf's wiring as source text. The strip moved out of HomeShelfScreen.kt into
 * GameShelf.kt (2026-10-10), so several pins read both files; every slice goes through [sliceBetween] so an anchor that is
 * renamed fails the test loudly and never degrades to "the whole file".
 */

/** Presence-checked slice: a missing anchor fails loudly instead of degrading to the whole file. */
internal fun String.sliceBetween(after: String, before: String): String {
    assertTrue("anchor missing: $after", contains(after))
    val tail = substringAfter(after)
    assertTrue("anchor missing after '$after': $before", tail.contains(before))
    return tail.substringBefore(before)
}

/** Presence-checked prefix: everything before [before], failing loudly when [before] is not there (a plain substringBefore returns the whole text). */
internal fun String.sliceUntil(before: String): String {
    assertTrue("anchor missing: $before", contains(before))
    return substringBefore(before)
}

/** One main source file of this module, found whether the test runs from the module or from the repository root. */
internal fun featureLibrarySource(name: String): String = listOf(
    File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
    File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
).firstOrNull(File::isFile)?.readText() ?: error("$name not found from ${File(".").absolutePath}")

/** The body of `GameShelf` (its parameters and everything it composes), bounded on the next declaration below it. */
internal fun gameShelfStripBody(): String =
    featureLibrarySource("GameShelf.kt").sliceBetween("internal fun GameShelf(", "private fun GameShelfMovingSlot(")

/**
 * What Home hands the shelf: the call itself, from `GameShelf(` to the `state` argument that closes it. A pin that asks "does
 * Home pass its scope" must ask it of this slice, because `HomeShelfScope` also appears elsewhere in the screen (the focus
 * registration, for one) and would satisfy a search of the whole body.
 */
internal fun gameShelfCallInHome(homeShelfScreenBody: String): String =
    homeShelfScreenBody.sliceBetween("GameShelf(", "state = shelfState,")

/** The body of `HomeShelfScreen`, bounded on the next top-level declaration (each occurs once in the file). */
internal fun homeShelfScreenBody(): String =
    featureLibrarySource("HomeShelfScreen.kt").sliceBetween("fun HomeShelfScreen(", "internal fun homeShelfTitleTrailingReserve(")
