package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N6 companion to [SettingsLibraryReadFailureRenderTest]. That test is a real behavioural differential
 * at `SettingsDetailPanel` (a plain composable this module's Robolectric harness CAN drive directly) --
 * but `SettingsDetailPanel` is not what the player ever sees; it is reached through `MimirAppHost`'s
 * `MimirSettingsDestination` -> `SettingsHome` -> `SettingsDetailPane` (two call sites: compact and wide
 * layout) -> `SettingsDetailPanel`. Every hop's `sourceReadFailureMessage` parameter is optional
 * (`String? = null`, matching every other parameter on these composables), so forgetting to forward it
 * at any ONE hop is not a Kotlin compile error -- it silently and permanently resolves to `null`, the
 * exact same "no compiler support" shape N3's C1/C2 fixed one layer down (a `Boolean`/sealed result that
 * can be discarded with nothing catching it). Driving the real `MimirAppHost`/`bindings.library` chain
 * needs the full `MimirAppCoordinator`, which this module's harness cannot construct -- the same
 * accepted gap `MimirLibraryCoordinatorSourceHonestyTest` and `MediaMenuHonestyCoordinatorWiringTest`
 * document for that exact file. So the mechanical forwarding hops are pinned here by **source
 * characterization**, labelled as such, not implied behavioural.
 */
class SettingsLibraryReadFailurePlumbingTest {

    private fun fileText(candidates: List<String>): String {
        val file = candidates.map(::File).firstOrNull(File::isFile)
            ?: error("none of $candidates found from ${File(".").absolutePath}")
        return file.readText()
    }

    private fun settingsHomeSource() = fileText(
        listOf(
            "feature-settings/src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/SettingsHome.kt",
            "../feature-settings/src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/SettingsHome.kt",
        ),
    )

    private fun mimirAppHostSource() = fileText(
        listOf(
            "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt",
            "src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt",
        ),
    )

    @Test
    fun `SettingsHome accepts the parameter and forwards it at both SettingsDetailPane call sites`() {
        val source = settingsHomeSource()
        assertTrue(
            "SettingsHome must accept sourceReadFailureMessage",
            source.contains("sourceReadFailureMessage: String? = null,"),
        )
        val forwardCount = Regex("sourceReadFailureMessage = sourceReadFailureMessage,")
            .findAll(source).count()
        assertEquals(
            "both the compact and the wide-layout SettingsDetailPane call sites, plus the private " +
                "SettingsDetailPane's own forward into SettingsDetailPanel, must all forward the same " +
                "value -- three sites, not two, not four",
            3,
            forwardCount,
        )
    }

    @Test
    fun `the private SettingsDetailPane function itself declares the parameter -- not just its call sites`() {
        val source = settingsHomeSource()
        val paneBlock = source.substringAfter("private fun SettingsDetailPane(")
        assertTrue(
            "SettingsDetailPane's own parameter list must declare sourceReadFailureMessage, or the " +
                "call-site forward above would not compile",
            paneBlock.substringBefore(") {").contains("sourceReadFailureMessage: String?,"),
        )
    }

    @Test
    fun `MimirAppHost -- the actual player-visible entry point -- passes the coordinator's live field, not a literal`() {
        val source = mimirAppHostSource()
        assertTrue(
            "MimirSettingsDestination's SettingsHome( ) call must read the live coordinator field, " +
                "never a hardcoded null or a copy -- this is the one call site with no compiler check " +
                "forcing it to be wired at all, since every hop's parameter is optional",
            source.contains("sourceReadFailureMessage = bindings.library.sourceReadFailureMessage,"),
        )
    }
}
