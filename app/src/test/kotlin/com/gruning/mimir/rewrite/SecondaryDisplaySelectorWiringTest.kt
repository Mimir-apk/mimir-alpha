package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source characterization for the Android adapters; selector behavior is covered semantically in core. */
class SecondaryDisplaySelectorWiringTest {
    @Test
    fun `process runtime constructs one selector and shares it with registry and topology`() {
        val source = appSource("CompanionProcessRuntime.kt")
        assertTrue(source.contains("val secondaryDisplaySelector = SecondaryDisplaySelector(context)"))
        assertTrue(source.contains("secondaryDisplaySelector = secondaryDisplaySelector"))
        assertTrue(source.contains("val secondaryDisplaySelector: SecondaryDisplaySelector"))
    }

    @Test
    fun `display consumers do not independently choose first presentation display`() {
        listOf(
            appSource("CompanionDisplayController.kt"),
            appSource("MButtonRuntime.kt"),
            coreSource("AndroidSecondaryTaskRegistry.kt"),
        ).forEach { source ->
            assertFalse(
                "a display consumer must not independently select a Presentation display",
                Regex(
                    "getDisplays\\(DisplayManager\\.DISPLAY_CATEGORY_PRESENTATION\\)[\\s\\S]{0,120}" +
                        "\\?\\.firstOrNull\\(\\)",
                ).containsMatchIn(source),
            )
        }
    }

    @Test
    fun `brightness keeps the sole candidate safety gate`() {
        val mainActivity = appSource("MainActivity.kt")
        val brightness = appSource("AndroidBrightnessStore.kt")
        assertTrue(mainActivity.contains("snapshot.value.soleEligibleId"))
        assertTrue(brightness.contains(".singleOrNull()"))
        assertTrue(brightness.contains("selectSoleSecondaryPresentationDisplayId"))
    }

    private fun appSource(name: String): String = sourceOf(
        listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("../src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        ),
    )

    private fun coreSource(name: String): String = sourceOf(
        listOf(
            File("core-companion-native/src/main/kotlin/" +
                "com/gruning/mimir/rewrite/core/companionnative/$name"),
            File("../core-companion-native/src/main/kotlin/" +
                "com/gruning/mimir/rewrite/core/companionnative/$name"),
        ),
    )

    private fun sourceOf(candidates: List<File>): String {
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("source file not found from ${File(".").absolutePath}")
    }
}
