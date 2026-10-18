package com.gruning.mimir.rewrite.feature.settings

import com.gruning.mimir.rewrite.core.ui.select.MimirSelectItem

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings › Music › Source (spec 2026-09-30): two rows on the house select shell. "Music on this device" is offered
 * dim until a folder holds tracks (and says to choose one first), and a dim row cannot be chosen
 * -- a choice that changes nothing audible is never made.
 */
class MusicSourcePickerTest {

    private fun options(current: MusicSourceValue, customAvailable: Boolean) =
        musicSourceItems(current, customAvailable).filterIsInstance<MimirSelectItem.Option>()

    @Test
    fun `two rows in enum order and the current one is marked`() {
        val rows = options(MusicSourceValue.MIMIR, customAvailable = false)
        assertEquals(listOf("Mimir’s music", "Music on this device"), rows.map { it.label })
        assertEquals(listOf(true, false), rows.map { it.current })
        assertEquals(listOf(false, true), options(MusicSourceValue.CUSTOM, customAvailable = true).map { it.current })
    }

    @Test
    fun `Your music is dim and says what to do first until it is available and never otherwise`() {
        val unavailable = options(MusicSourceValue.MIMIR, customAvailable = false)
        assertTrue(unavailable[0].enabled)
        assertFalse(unavailable[1].enabled)
        assertEquals(MusicSourceNeedsFolderCopy, unavailable[1].subtitle)

        val available = options(MusicSourceValue.MIMIR, customAvailable = true)
        assertTrue(available[1].enabled)
        assertFalse("the phrase is reserved for the engineering rows", unavailable[1].subtitle.orEmpty().contains(MusicComingNextCopy))
        assertEquals("Tracks from your music folder.", available[1].subtitle)
    }

    @Test
    fun `every option key maps back to its value and a foreign key maps to nothing`() {
        options(MusicSourceValue.MIMIR, customAvailable = true).forEachIndexed { index, option ->
            assertEquals(MusicSourceValue.entries[index], musicSourceForKey(option.key))
        }
        assertNull(musicSourceForKey(FocusKey("appearance/backdrop/OFF")))
        assertNull(musicSourceForKey(null))
    }

    private class Sinks {
        var selected: MusicSourceValue? = null
        var dismissed = 0
    }

    private fun authorityOn(value: MusicSourceValue, customAvailable: Boolean): DefaultFocusAuthority {
        val authority = DefaultFocusAuthority()
        options(MusicSourceValue.MIMIR, customAvailable).forEachIndexed { index, option ->
            authority.register(option.key, MusicSourcePickerScope, FocusBounds(0f, index * 100f, 100f, index * 100f + 100f))
        }
        authority.pushScope(MusicSourcePickerScope)
        authority.requestFocus(options(MusicSourceValue.MIMIR, customAvailable).first { musicSourceForKey(it.key) == value }.key)
        return authority
    }

    private fun dispatch(
        authority: DefaultFocusAuthority,
        action: ControllerAction,
        customAvailable: Boolean,
    ): Sinks {
        val sinks = Sinks()
        assertTrue(
            "the picker is a hard modal",
            dispatchMusicSourcePicker(
                authority = authority,
                current = MusicSourceValue.MIMIR,
                customAvailable = customAvailable,
                action = action,
                onSelect = { sinks.selected = it },
                onDismiss = { sinks.dismissed += 1 },
            ),
        )
        return sinks
    }

    @Test
    fun `A on Mimir's music selects it`() {
        val sinks = dispatch(authorityOn(MusicSourceValue.MIMIR, false), ControllerAction.CONFIRM, customAvailable = false)
        assertEquals(MusicSourceValue.MIMIR, sinks.selected)
        assertEquals(0, sinks.dismissed)
    }

    @Test
    fun `A on a dim Your music selects nothing and the picker stays`() {
        val sinks = dispatch(authorityOn(MusicSourceValue.CUSTOM, false), ControllerAction.CONFIRM, customAvailable = false)
        assertNull(sinks.selected)
        assertEquals(0, sinks.dismissed)
    }

    @Test
    fun `A on Your music selects it once it is available`() {
        val sinks = dispatch(authorityOn(MusicSourceValue.CUSTOM, true), ControllerAction.CONFIRM, customAvailable = true)
        assertEquals(MusicSourceValue.CUSTOM, sinks.selected)
    }

    @Test
    fun `B dismisses without choosing`() {
        val sinks = dispatch(authorityOn(MusicSourceValue.MIMIR, false), ControllerAction.CANCEL, customAvailable = false)
        assertNull(sinks.selected)
        assertEquals(1, sinks.dismissed)
    }
}
