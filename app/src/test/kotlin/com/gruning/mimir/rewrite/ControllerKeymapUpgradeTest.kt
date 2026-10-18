package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.PhysicalControl
import com.gruning.mimir.rewrite.core.input.defaultControllerKeymap
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stored remap must survive the app growing a new [PhysicalControl], and must never be erased.
 *
 * `decodeControllerKeymap` required the stored string to carry EXACTLY `PhysicalControl.entries.size`
 * entries, and `initialize` deleted the preference outright when the decode returned null. Together those
 * two lines meant that adding one control to the enum — an L3, an R3, a second menu button on a new
 * handheld — would silently and irrecoverably destroy every existing user's controller remap on their
 * first launch of the upgraded build. The player would find factory bindings and nothing to restore.
 *
 * A save that predates a control is not corruption; it is a save that has no opinion about that control.
 * The safe reading is to honour every binding it does carry and give the new control its default.
 *
 * Rejecting genuinely unrecognisable input is still correct and is still pinned by
 * [ControllerMappingTest.keymapCodecRejectsUnknownEnumNames] — but a rejection must fall back, never erase.
 */
class ControllerKeymapUpgradeTest {

    @Test
    fun `a remap saved before a control existed keeps its bindings and defaults the new one`() {
        val saved = defaultControllerKeymap().toMutableMap().apply {
            this[PhysicalControl.FACE_A] = ControllerAction.CANCEL
            this[PhysicalControl.FACE_B] = ControllerAction.CONFIRM
        }
        // Drop MENU entirely: this is byte-for-byte what a build that predates the control would have
        // written, not a corrupted string.
        val encoded = ControllerMappingStore.encodeControllerKeymap(saved)
            .split("|")
            .filterNot { it.startsWith("${PhysicalControl.MENU.name}=") }
            .joinToString("|")

        val decoded = ControllerMappingStore.decodeControllerKeymap(encoded)

        assertNotNull("a save predating a control must not be thrown away wholesale", decoded)
        val map = requireNotNull(decoded)
        assertEquals("the player's own bindings must survive", ControllerAction.CANCEL, map[PhysicalControl.FACE_A])
        assertEquals(ControllerAction.CONFIRM, map[PhysicalControl.FACE_B])
        assertEquals(
            "a control the save never mentioned takes its factory binding",
            defaultControllerKeymap()[PhysicalControl.MENU],
            map[PhysicalControl.MENU],
        )
        assertEquals(PhysicalControl.entries.toSet(), map.keys)
    }

    @Test
    fun `an explicitly unbound control stays unbound rather than being defaulted`() {
        val saved = defaultControllerKeymap().toMutableMap().apply { this[PhysicalControl.FACE_Y] = null }
        val decoded = ControllerMappingStore.decodeControllerKeymap(
            ControllerMappingStore.encodeControllerKeymap(saved),
        )

        assertTrue(requireNotNull(decoded).containsKey(PhysicalControl.FACE_Y))
        assertNull(
            "absent and explicitly-unbound must stay distinct now that absent means 'take the default'",
            decoded[PhysicalControl.FACE_Y],
        )
    }

    @Test
    fun `an undecodable stored mapping falls back to defaults instead of being erased`() {
        val restored = ControllerMappingStore.restoreControllerMapping(storedKeymap = "v1|total-garbage", storedRaw = null)

        assertEquals(defaultControllerKeymap(), restored.keymap)
    }

    /**
     * The absence-of-behaviour gate. Falling back is only safe if nothing deletes the stored string on
     * the way past, and a deletion is invisible to every behavioural test above — the fallback looks
     * identical either way. This reads the source, which is the only instrument that can see it.
     */
    @Test
    fun `initialize never removes a stored mapping it failed to decode`() {
        val source = File("src/main/kotlin/com/gruning/mimir/rewrite/ControllerMapping.kt")
        assertTrue("source not found at ${source.absolutePath}", source.exists())
        val body = source.readText()
            .substringAfter("fun initialize(context: Context)")
            .substringBefore("\n    }")

        listOf("KeymapKey", "RawBindingsKey").forEach { key ->
            assertTrue(
                "initialize erases $key when the decode fails; a decode failure must fall back, never " +
                    "destroy the player's remap — a later build may well understand the string",
                !body.contains("remove($key)"),
            )
        }
    }
}
