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
 * Settings › Music › Order and Music hours: two more menus on the Source picker's select shell and its focus scope. Order is the
 * spec's two words; Music hours is the smallest list of named windows (no spec fixed a time editor).
 */
class MusicMenuPickersTest {

    private fun orderOptions(current: MusicOrderValue) =
        musicOrderItems(current).filterIsInstance<MimirSelectItem.Option>()

    private fun hoursOptions(current: MusicHoursValue) =
        musicHoursItems(current).filterIsInstance<MimirSelectItem.Option>()

    @Test
    fun `Order offers the spec's two words, in order, with the current one marked`() {
        assertEquals(listOf("In order", "Shuffle"), orderOptions(MusicOrderValue.SHUFFLE).map { it.label })
        assertEquals(listOf(false, true), orderOptions(MusicOrderValue.SHUFFLE).map { it.current })
        assertEquals(listOf(true, false), orderOptions(MusicOrderValue.IN_ORDER).map { it.current })
        assertTrue("every row can be chosen", orderOptions(MusicOrderValue.SHUFFLE).all { it.enabled })
    }

    @Test
    fun `Music hours offers all day first, then each window with its hours in words`() {
        val rows = hoursOptions(MusicHoursValue.ALL_DAY)
        assertEquals(listOf("All day", "Daytime", "Evening", "Not overnight"), rows.map { it.label })
        assertEquals(MusicHoursValue.ALL_DAY.label, rows.first().label)
        assertEquals(listOf(true, false, false, false), rows.map { it.current })
        assertEquals("7:00 AM to 7:00 PM", rows[1].subtitle)
        assertEquals("5:00 PM to 11:00 PM", rows[2].subtitle)
        assertEquals("7:00 AM to 11:00 PM", rows[3].subtitle)
        assertTrue(rows.all { it.enabled })
    }

    @Test
    fun `no menu row shows a code or a storage name`() {
        val everything = orderOptions(MusicOrderValue.SHUFFLE) + hoursOptions(MusicHoursValue.ALL_DAY)
        everything.forEach { option ->
            assertFalse(option.label, option.label.contains("_"))
            assertFalse(option.label, option.label == option.label.uppercase())
            assertFalse(option.subtitle.orEmpty(), option.subtitle.orEmpty().contains("_"))
        }
        assertFalse(MusicComingNextCopy, everything.any { it.subtitle.orEmpty().contains(MusicComingNextCopy) })
    }

    @Test
    fun `every key maps back to its value, a foreign key maps to nothing, and the three menus never share a key`() {
        orderOptions(MusicOrderValue.SHUFFLE).forEachIndexed { index, option ->
            assertEquals(MusicOrderValue.entries[index], musicOrderForKey(option.key))
            assertNull("an Order key is not a hours key", musicHoursForKey(option.key))
            assertNull("an Order key is not a source key", musicSourceForKey(option.key))
        }
        hoursOptions(MusicHoursValue.ALL_DAY).forEachIndexed { index, option ->
            assertEquals(MusicHoursValue.entries[index], musicHoursForKey(option.key))
            assertNull(musicOrderForKey(option.key))
            assertNull(musicSourceForKey(option.key))
        }
        assertNull(musicOrderForKey(FocusKey("appearance/backdrop/OFF")))
        assertNull(musicHoursForKey(null))
        val keys = (orderOptions(MusicOrderValue.SHUFFLE) + hoursOptions(MusicHoursValue.ALL_DAY)).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `the four menus are the Source picker's slot, and Source is the default`() {
        assertEquals(
            listOf(MusicPickerMenu.SOURCE, MusicPickerMenu.ORDER, MusicPickerMenu.HOURS, MusicPickerMenu.LAUNCH_SOUND),
            MusicPickerMenu.entries,
        )
    }

    private fun launchOptions(current: MusicLaunchSoundValue) =
        musicLaunchSoundItems(current).filterIsInstance<MimirSelectItem.Option>()

    @Test
    fun `Launch sound offers Off then 25 to 100 percent, with the current step marked and every step choosable`() {
        assertEquals(listOf("Off", "25%", "50%", "75%", "100%"), launchOptions(MusicLaunchSoundValue.HALF).map { it.label })
        assertEquals(listOf(false, false, true, false, false), launchOptions(MusicLaunchSoundValue.HALF).map { it.current })
        assertEquals(listOf(true, false, false, false, false), launchOptions(MusicLaunchSoundValue.OFF).map { it.current })
        assertTrue(launchOptions(MusicLaunchSoundValue.FULL).all { it.enabled })
    }

    @Test
    fun `every Launch sound key maps back to its step and never to another menu's value`() {
        launchOptions(MusicLaunchSoundValue.OFF).forEachIndexed { index, option ->
            assertEquals(MusicLaunchSoundValue.entries[index], musicLaunchSoundForKey(option.key))
            assertNull(musicOrderForKey(option.key))
            assertNull(musicHoursForKey(option.key))
            assertNull(musicSourceForKey(option.key))
        }
        assertNull(musicLaunchSoundForKey(FocusKey("music/order/SHUFFLE")))
        assertNull(musicLaunchSoundForKey(null))
        val keys = launchOptions(MusicLaunchSoundValue.OFF).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `A on a Launch sound step chooses it and B leaves without choosing`() {
        MusicLaunchSoundValue.entries.forEach { value ->
            val sinks = Sinks<MusicLaunchSoundValue>()
            val keys = launchOptions(MusicLaunchSoundValue.FULL).map { it.key }
            val key = launchOptions(MusicLaunchSoundValue.FULL).first { musicLaunchSoundForKey(it.key) == value }.key
            assertTrue(
                "the Launch sound menu is a hard modal",
                dispatchMusicLaunchSoundPicker(
                    authority = authority(keys, key),
                    current = MusicLaunchSoundValue.FULL,
                    action = ControllerAction.CONFIRM,
                    onSelect = { sinks.selected = it },
                    onDismiss = { sinks.dismissed += 1 },
                ),
            )
            assertEquals(value, sinks.selected)
        }
        val sinks = Sinks<MusicLaunchSoundValue>()
        val keys = launchOptions(MusicLaunchSoundValue.FULL).map { it.key }
        assertTrue(
            dispatchMusicLaunchSoundPicker(
                authority = authority(keys, keys.first()),
                current = MusicLaunchSoundValue.FULL,
                action = ControllerAction.CANCEL,
                onSelect = { sinks.selected = it },
                onDismiss = { sinks.dismissed += 1 },
            ),
        )
        assertNull(sinks.selected)
        assertEquals(1, sinks.dismissed)
    }

    private class Sinks<T> {
        var selected: T? = null
        var dismissed = 0
    }

    private fun authority(keys: List<FocusKey>, focused: FocusKey): DefaultFocusAuthority {
        val authority = DefaultFocusAuthority()
        keys.forEachIndexed { index, key ->
            authority.register(key, MusicSourcePickerScope, FocusBounds(0f, index * 100f, 100f, index * 100f + 100f))
        }
        authority.pushScope(MusicSourcePickerScope)
        authority.requestFocus(focused)
        return authority
    }

    private fun dispatchOrder(focused: MusicOrderValue, action: ControllerAction): Sinks<MusicOrderValue> {
        val sinks = Sinks<MusicOrderValue>()
        val keys = orderOptions(MusicOrderValue.SHUFFLE).map { it.key }
        val key = orderOptions(MusicOrderValue.SHUFFLE).first { musicOrderForKey(it.key) == focused }.key
        assertTrue(
            "the Order menu is a hard modal",
            dispatchMusicOrderPicker(
                authority = authority(keys, key),
                current = MusicOrderValue.SHUFFLE,
                action = action,
                onSelect = { sinks.selected = it },
                onDismiss = { sinks.dismissed += 1 },
            ),
        )
        return sinks
    }

    private fun dispatchHours(focused: MusicHoursValue, action: ControllerAction): Sinks<MusicHoursValue> {
        val sinks = Sinks<MusicHoursValue>()
        val keys = hoursOptions(MusicHoursValue.ALL_DAY).map { it.key }
        val key = hoursOptions(MusicHoursValue.ALL_DAY).first { musicHoursForKey(it.key) == focused }.key
        assertTrue(
            "the Music hours menu is a hard modal",
            dispatchMusicHoursPicker(
                authority = authority(keys, key),
                current = MusicHoursValue.ALL_DAY,
                action = action,
                onSelect = { sinks.selected = it },
                onDismiss = { sinks.dismissed += 1 },
            ),
        )
        return sinks
    }

    @Test
    fun `A on an Order row chooses it, and B leaves without choosing`() {
        assertEquals(MusicOrderValue.IN_ORDER, dispatchOrder(MusicOrderValue.IN_ORDER, ControllerAction.CONFIRM).selected)
        assertEquals(MusicOrderValue.SHUFFLE, dispatchOrder(MusicOrderValue.SHUFFLE, ControllerAction.CONFIRM).selected)
        val back = dispatchOrder(MusicOrderValue.SHUFFLE, ControllerAction.CANCEL)
        assertNull(back.selected)
        assertEquals(1, back.dismissed)
    }

    @Test
    fun `A on a Music hours row chooses it, and B leaves without choosing`() {
        MusicHoursValue.entries.forEach { value ->
            assertEquals(value, dispatchHours(value, ControllerAction.CONFIRM).selected)
        }
        val back = dispatchHours(MusicHoursValue.EVENING, ControllerAction.CANCEL)
        assertNull(back.selected)
        assertEquals(1, back.dismissed)
    }
}
