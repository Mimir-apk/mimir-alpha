package com.gruning.mimir.rewrite.feature.companion

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scene transition a Bottom surface edge is named for. Thor 2026-10-02: the lattice entered through a running `scene.glide`
 * (and `scene.crossfade`) and its receipt said `transition=none`.
 */
class CompanionSceneEntryTransitionTest {
    @Test
    fun aGlidingSceneNamesTheGlideWhateverElseFades() {
        assertEquals("scene.glide", companionSceneEntryTransition(hasOutgoingLayer = true, glides = true))
        assertEquals("scene.glide", companionSceneEntryTransition(hasOutgoingLayer = false, glides = true))
    }

    @Test
    fun aSceneThatOnlyCrossfadesNamesTheCrossfade() {
        assertEquals("scene.crossfade", companionSceneEntryTransition(hasOutgoingLayer = true, glides = false))
    }

    @Test
    fun theHostsFirstSceneSnapsInAndNamesNothingSoAColdStartIsNotReportedMissing() {
        assertNull(companionSceneEntryTransition(hasOutgoingLayer = false, glides = false))
    }

    @Test
    fun theLatticeTakesItsTransitionFromTheSceneHostAndNamesWhatItReplaced() {
        val source = File("src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt").readText()
        assertTrue(source.contains("CompositionLocalProvider(LocalCompanionSceneEntryTransition provides entryTransition)"))
        assertTrue(source.contains("else -> sceneEntry"))
        assertTrue(source.contains("from = replaced?.takeIf { it != baseSurface }"))
        // The glide is started from the very value the name is decided from.
        assertTrue(source.contains("glides = glidePx != 0f"))
    }
}
