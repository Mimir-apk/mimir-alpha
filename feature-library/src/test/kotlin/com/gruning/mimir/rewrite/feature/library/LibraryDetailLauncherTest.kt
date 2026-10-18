package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.launch.OpenWithCandidate
import com.gruning.mimir.rewrite.core.launch.OpenWithOptions
import com.gruning.mimir.rewrite.core.model.EmulatorId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Launch choice: Main's Library detail EMULATOR fact names the emulator the launch would start
 * (`OpenWithCandidate.isLaunchPick`) and never changes the card's layout. With nothing marked it keeps its pre-change
 * text: null, which the card paints as its own "Automatic".
 */
class LibraryDetailLauncherTest {

    private val platformDefault = "RetroArch 64 / Gambatte"
    /** Auto's first pick, which a launch with an unusable override actually starts. */
    private val autoPick = "RetroArch 64 / SameBoy"
    private val overrideLabel = "Pizza Boy GBC"

    private fun candidate(
        label: String,
        installed: Boolean = true,
        launchable: Boolean = true,
        override: Boolean = false,
        auto: Boolean = false,
        launchPick: Boolean = false,
    ) = OpenWithCandidate(
        emulatorId = EmulatorId("emu.${label.filter(Char::isLetterOrDigit).lowercase()}"),
        label = label,
        packageName = "pkg.${label.filter(Char::isLetterOrDigit).lowercase()}",
        installed = installed,
        dispatchSupported = launchable,
        isCurrentOverride = override,
        isAutoPick = auto,
        isLaunchPick = launchPick,
    )

    @Test
    fun `options not loaded keep the pre-change text`() {
        assertNull("null is what the detail passed before: the card paints its own Automatic", libraryDetailLauncherLabel(null))
    }

    @Test
    fun `an uninstalled override names the emulator the launch falls to`() {
        val options = OpenWithOptions(
            hasOverride = true,
            candidates = listOf(
                candidate(autoPick, launchPick = true),
                candidate(platformDefault, auto = true),
                candidate(overrideLabel, installed = false, override = true),
            ),
        )
        assertEquals(autoPick, libraryDetailLauncherLabel(options))
    }

    @Test
    fun `an override the launch cannot dispatch names the emulator the launch falls to`() {
        val options = OpenWithOptions(
            hasOverride = true,
            candidates = listOf(
                candidate(autoPick, launchPick = true),
                candidate(platformDefault, auto = true),
                candidate(overrideLabel, launchable = false, override = true),
            ),
        )
        assertEquals(autoPick, libraryDetailLauncherLabel(options))
    }

    @Test
    fun `an override that is no longer listed names the emulator the launch falls to`() {
        val options = OpenWithOptions(
            hasOverride = true,
            candidates = listOf(candidate(autoPick, launchPick = true), candidate(platformDefault, auto = true)),
        )
        assertEquals(autoPick, libraryDetailLauncherLabel(options))
    }

    @Test
    fun `a usable override and a game with no override name the marked emulator`() {
        val usable = OpenWithOptions(
            hasOverride = true,
            candidates = listOf(
                candidate(platformDefault, auto = true),
                candidate(overrideLabel, override = true, launchPick = true),
            ),
        )
        assertEquals(overrideLabel, libraryDetailLauncherLabel(usable))
        val noOverride = OpenWithOptions(
            hasOverride = false,
            candidates = listOf(candidate(autoPick), candidate(platformDefault, auto = true, launchPick = true)),
        )
        assertEquals(platformDefault, libraryDetailLauncherLabel(noOverride))
    }

    @Test
    fun `nothing marked keeps the pre-change text, even with a stale override`() {
        val options = OpenWithOptions(
            hasOverride = true,
            candidates = listOf(candidate(overrideLabel, installed = false, override = true)),
        )
        assertNull(libraryDetailLauncherLabel(options))
    }

    @Test
    fun `the EMULATOR fact is always painted, with the card's pre-change text when there is no name`() {
        val card = sourceFile("HomeLaunchCard.kt")
        val cardLines = card.lines().map(String::trim)
        assertTrue(
            "the row is added unconditionally, its text byte for byte as before",
            "add(\"EMULATOR\" to (state.emulatorLabel ?: \"Automatic\"))" in cardLines,
        )
        assertTrue(
            "the identity row is painted unconditionally",
            "HomeLaunchFactRow(*identityFacts.toTypedArray())" in cardLines,
        )
        assertFalse("no flag may hide the row", card.contains("emulatorLineShown"))
        val detail = sourceFile("GameDetailScreen.kt")
        assertFalse(detail.contains("emulatorLineShown"))
        assertTrue("val launcherLabel = libraryDetailLauncherLabel(openWith)" in detail.lines().map(String::trim))
    }

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
