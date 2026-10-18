package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.launch.OpenWithCandidate
import com.gruning.mimir.rewrite.core.launch.OpenWithOptions
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.ui.select.MimirSelectItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenWithPickerTest {
    @Test
    fun `open with contains automatic and emulator candidates only`() {
        val items = openWithItems(
            OpenWithOptions(
                hasOverride = false,
                candidates = listOf(
                    OpenWithCandidate(
                        emulatorId = EmulatorId("dolphin"),
                        label = "Dolphin",
                        packageName = "org.dolphinemu.dolphinemu",
                        installed = true,
                        isCurrentOverride = false,
                        isAutoPick = true,
                    ),
                ),
            ),
        ).filterIsInstance<MimirSelectItem.Option>()

        assertEquals(listOf(OpenWithAutoKey, openWithFocusKey("dolphin")), items.map { it.key })
        assertFalse(items.any { it.label.contains("tuning", ignoreCase = true) })
        assertFalse(items.any { it.subtitle?.contains("frame limit", ignoreCase = true) == true })
    }

    private fun candidate(
        id: String,
        installed: Boolean = true,
        auto: Boolean = false,
        listsFile: Boolean? = null,
        manualCore: Boolean = false,
        dispatchSupported: Boolean = true,
    ) = OpenWithCandidate(
        emulatorId = EmulatorId(id),
        label = id,
        packageName = "pkg.$id",
        installed = installed,
        dispatchSupported = dispatchSupported,
        requiresManualCoreProof = manualCore,
        isCurrentOverride = false,
        isAutoPick = auto,
        listsFile = listsFile,
    )

    @Test
    fun `an emulator whose list leaves the file out says so on its own muted line and nothing is hidden`() {
        val options = OpenWithOptions(
            hasOverride = false,
            candidates = listOf(
                candidate("ppsspp", auto = true, listsFile = false),
                candidate("gold", listsFile = true),
                candidate("other"),
            ),
            fileExtension = "cso",
        )
        val items = openWithItems(options)
        val rows = items.filterIsInstance<MimirSelectItem.Option>()

        assertEquals(listOf(OpenWithAutoKey, openWithFocusKey("ppsspp"), openWithFocusKey("gold"), openWithFocusKey("other")), rows.map { it.key })
        assertEquals("Doesn’t list .cso files", rows.first { it.key == openWithFocusKey("ppsspp") }.subtitle)
        assertNull("a listed file has no line", rows.first { it.key == openWithFocusKey("gold") }.subtitle)
        assertNull("no list, no line", rows.first { it.key == openWithFocusKey("other") }.subtitle)
        // Nothing hidden, nothing disabled, Recommended where Auto put it, Automatic still names what it uses.
        assertTrue(rows.all { it.enabled })
        assertEquals(listOf(openWithFocusKey("ppsspp")), rows.filter { it.recommended }.map { it.key })
        assertEquals("Uses ppsspp", rows.first { it.key == OpenWithAutoKey }.subtitle)
        assertTrue("one emulator without a list accepts everything, so no line above the rows", items.none { it is MimirSelectItem.Note })
    }

    @Test
    fun `when no installed emulator lists the file one line above the rows says so and Play is untouched`() {
        val options = OpenWithOptions(
            hasOverride = false,
            candidates = listOf(
                candidate("ppsspp", auto = true, listsFile = false),
                candidate("gold", listsFile = false),
                candidate("missing", installed = false, listsFile = null),
            ),
            fileExtension = "xyz",
        )
        val items = openWithItems(options)

        assertEquals(MimirSelectItem.Note("None of your emulators list .xyz files."), items.first())
        assertEquals(1, items.count { it is MimirSelectItem.Note })
        val rows = items.filterIsInstance<MimirSelectItem.Option>()
        assertEquals(listOf(OpenWithAutoKey, openWithFocusKey("ppsspp"), openWithFocusKey("gold"), openWithFocusKey("missing")), rows.map { it.key })
        assertEquals("Doesn’t list .xyz files", rows.first { it.key == openWithFocusKey("gold") }.subtitle)
        assertEquals("Not installed", rows.first { it.key == openWithFocusKey("missing") }.subtitle)
        assertTrue("both installed rows stay selectable", rows.filter { it.key != openWithFocusKey("missing") }.all { it.enabled })
        assertEquals(listOf(openWithFocusKey("ppsspp")), rows.filter { it.recommended }.map { it.key })
    }

    @Test
    fun `a state subtitle keeps its line and the specific label beats the generic setup caution`() {
        val options = OpenWithOptions(
            hasOverride = false,
            candidates = listOf(
                candidate("core", auto = true, listsFile = false, manualCore = true),
                candidate("core-ok", listsFile = true, manualCore = true),
                candidate("off", installed = false, listsFile = false),
                candidate("shell", dispatchSupported = false, listsFile = false),
            ),
            fileExtension = "gba",
        )
        val subtitles = openWithItems(options).filterIsInstance<MimirSelectItem.Option>().associate { it.key to it.subtitle }
        assertEquals("Doesn’t list .gba files", subtitles[openWithFocusKey("core")])
        assertEquals("Additional emulator setup may be required", subtitles[openWithFocusKey("core-ok")])
        assertEquals("Not installed", subtitles[openWithFocusKey("off")])
        assertEquals("Not supported yet", subtitles[openWithFocusKey("shell")])
    }

    @Test
    fun `a file with no plain extension is described as this file`() {
        assertEquals("Doesn’t list this file", openWithDoesNotListLine(null))
        assertEquals("None of your emulators list this file.", openWithNoneListLine(null))
        assertEquals("Doesn’t list .cso files", openWithDoesNotListLine("cso"))
        assertEquals("None of your emulators list .cso files.", openWithNoneListLine("cso"))
    }

    @Test
    fun `without any list the picker is exactly the rows it always was`() {
        val options = OpenWithOptions(
            hasOverride = true,
            candidates = listOf(candidate("a", auto = true), candidate("b", manualCore = true), candidate("c", installed = false)),
        )
        val items = openWithItems(options)
        assertTrue(items.none { it is MimirSelectItem.Note })
        assertEquals(
            listOf("Uses a", null, "Additional emulator setup may be required", "Not installed"),
            items.filterIsInstance<MimirSelectItem.Option>().map { it.subtitle },
        )
    }
}
