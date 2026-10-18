package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.audio.MimirFrontendBed
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * the ambient piece is the frontend music for every theme except Hel,
 * which keeps "Fallen Hero".
 *
 * Enumerated over [MimirThemeId.entries] rather than spot-checked, so a tenth theme cannot join the
 * roster and quietly inherit a bed nobody chose for it.
 */
class MimirFrontendBedForThemeTest {

    @Test
    fun `Hel keeps Fallen Hero`() {
        assertEquals(MimirFrontendBed.FallenHero, mimirFrontendBedFor(MimirThemeId.HEL))
    }

    @Test
    fun `every other theme wears the ambient bed`() {
        val ambient = MimirThemeId.entries.filter { it != MimirThemeId.HEL }
        // Guards the guard: if the roster ever shrank to just Hel, the loop below would pass vacuously.
        assertEquals(8, ambient.size)
        ambient.forEach { themeId ->
            assertEquals(
                "theme ${themeId.storageKey} should wear the ambient bed",
                MimirFrontendBed.Ambient,
                mimirFrontendBedFor(themeId),
            )
        }
    }

    /** Hel is persisted as `nightmare`, and older builds exported it as `evil`. Both must land on Fallen Hero. */
    @Test
    fun `the persisted keys for Hel resolve to Fallen Hero`() {
        assertEquals(
            MimirFrontendBed.FallenHero,
            mimirFrontendBedFor(MimirThemeId.fromStorageKey("nightmare")),
        )
        assertEquals(
            MimirFrontendBed.FallenHero,
            mimirFrontendBedFor(MimirThemeId.fromStorageKey("evil")),
        )
    }

    /** An unknown or absent persisted key falls back to DEFAULT, which is an ambient theme. */
    @Test
    fun `an unknown persisted theme opens on the ambient bed`() {
        assertEquals(MimirFrontendBed.Ambient, mimirFrontendBedFor(MimirThemeId.fromStorageKey(null)))
        assertEquals(MimirFrontendBed.Ambient, mimirFrontendBedFor(MimirThemeId.fromStorageKey("nonsense")))
    }
}
