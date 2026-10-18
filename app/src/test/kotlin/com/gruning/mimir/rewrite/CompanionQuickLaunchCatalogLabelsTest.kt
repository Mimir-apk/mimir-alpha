package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.launch.EmulatorSeedRegistry
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round-5 triage 7a: the Bottom Quick Launch picker drew two tiles reading "Azahar" (walkthrough 253,
 * AYN Thor). The seed labels below are Mimir Alpha registry's; the installed labels are what
 * `aapt2 dump badging` reported for the APKs pulled from that Thor (`io.github.lime3ds.android` ->
 * "AzaharPlus", `org.azahar_emu.azahar` -> "Azahar"). Variants come from the real registry, through the
 * same lookup `CompanionQuickLaunchCatalog.refresh` passes.
 */
class CompanionQuickLaunchCatalogLabelsTest {
    private val lookedUp = mutableListOf<String>()

    private fun installedLabels(vararg labels: Pair<String, String?>): (String) -> String? {
        val byPackage = labels.toMap()
        return { packageName ->
            lookedUp += packageName
            byPackage[packageName]
        }
    }

    private val registryVariant: (String) -> String? = { EmulatorSeedRegistry.seedForPackage(it)?.variant }

    @Test
    fun `labels that do not collide stay exactly as given and are never looked up`() {
        val entries = listOf(
            CompanionAppEntry("com.apple.android.music", "Apple Music", "/icons/music.png"),
            CompanionAppEntry("aenu.aps3e", "aPS3e"),
            CompanionAppEntry(AzaharVanilla, "Azahar", "/icons/azahar.png"),
        )

        val result = companionAppEntriesWithDistinctLabels(
            entries,
            installedLabels(AzaharVanilla to "Something else"),
            registryVariant,
        )

        assertEquals(entries, result)
        assertEquals(emptyList<String>(), lookedUp)
    }

    @Test
    fun `two Azahar tiles take their installed labels and only that pair is looked up`() {
        val entries = listOf(
            CompanionAppEntry("com.apple.android.music", "Apple Music", "/icons/music.png"),
            CompanionAppEntry(Lime3ds, "Azahar", "/icons/lime3ds.png"),
            CompanionAppEntry(AzaharVanilla, "Azahar", "/icons/azahar.png"),
        )

        val result = companionAppEntriesWithDistinctLabels(
            entries,
            installedLabels(
                "com.apple.android.music" to "Music",
                Lime3ds to "AzaharPlus",
                AzaharVanilla to "Azahar",
            ),
            registryVariant,
        )

        assertEquals(listOf("Apple Music", "AzaharPlus", "Azahar"), result.map(CompanionAppEntry::label))
        assertEquals(listOf(entries[0], entries[1].copy(label = "AzaharPlus"), entries[2]), result)
        assertEquals(listOf(Lime3ds, AzaharVanilla), lookedUp)
    }

    @Test
    fun `a collision the installed labels do not break is told apart by the registry variant`() {
        val entries = listOf(
            CompanionAppEntry(Lime3ds, "Azahar"),
            CompanionAppEntry(AzaharVanilla, "Azahar"),
        )

        val result = companionAppEntriesWithDistinctLabels(
            entries,
            installedLabels(Lime3ds to "Azahar", AzaharVanilla to null),
            registryVariant,
        )

        assertEquals(listOf("Azahar · play", "Azahar · vanilla"), result.map(CompanionAppEntry::label))
    }

    @Test
    fun `apps outside the registry are told apart by the shortest package tail that differs`() {
        val entries = listOf(
            CompanionAppEntry("com.vendor.one.files", "Files"),
            CompanionAppEntry("com.vendor.two.files", "Files"),
        )

        val result = companionAppEntriesWithDistinctLabels(
            entries,
            installedLabels("com.vendor.one.files" to "Files", "com.vendor.two.files" to "Files"),
            registryVariant,
        )

        assertEquals(listOf("Files · one.files", "Files · two.files"), result.map(CompanionAppEntry::label))
    }

    @Test
    fun `an entry that never collided keeps its label when an installed label lands on it`() {
        val entries = listOf(
            CompanionAppEntry(Lime3ds, "Azahar"),
            CompanionAppEntry(AzaharVanilla, "Azahar"),
            CompanionAppEntry(AzaharPlusPackage, "AzaharPlus"),
        )

        val result = companionAppEntriesWithDistinctLabels(
            entries,
            installedLabels(Lime3ds to "AzaharPlus", AzaharVanilla to "Azahar", AzaharPlusPackage to "AzaharPlus"),
            registryVariant,
        )

        assertEquals(listOf("AzaharPlus · play", "Azahar", "AzaharPlus"), result.map(CompanionAppEntry::label))
        assertEquals(listOf(Lime3ds, AzaharVanilla), lookedUp)
    }

    private companion object {
        const val Lime3ds = "io.github.lime3ds.android"
        const val AzaharVanilla = "org.azahar_emu.azahar"
        const val AzaharPlusPackage = "io.github.azaharplus.android"
    }
}
