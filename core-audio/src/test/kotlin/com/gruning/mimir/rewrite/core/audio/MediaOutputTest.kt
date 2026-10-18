package com.gruning.mimir.rewrite.core.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where media sound goes: the platform's device types to the five places the Equalizer's header names, and the words for each. Pure. */
class MediaOutputTest {
    private fun device(type: Int, name: String? = null) = OutputDeviceInfo(type, name)

    @Test
    fun `each platform type is one of the places a player knows`() {
        assertEquals(MediaOutputKind.SPEAKER, MediaOutputText.kindOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(MediaOutputKind.SPEAKER, MediaOutputText.kindOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE))
        for (type in listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL)) {
            assertEquals("type $type", MediaOutputKind.WIRED_HEADPHONES, MediaOutputText.kindOf(type))
        }
        for (type in listOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET)) {
            assertEquals("type $type", MediaOutputKind.USB_AUDIO, MediaOutputText.kindOf(type))
        }
        for (type in listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST)) {
            assertEquals("type $type", MediaOutputKind.BLUETOOTH, MediaOutputText.kindOf(type))
        }
        for (type in listOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC)) {
            assertEquals("type $type", MediaOutputKind.HDMI, MediaOutputText.kindOf(type))
        }
        assertEquals("a type nobody named is still an output", MediaOutputKind.OTHER, MediaOutputText.kindOf(AudioDeviceInfo.TYPE_DOCK))
    }

    @Test
    fun `the earpiece and a phone line are not where media goes`() {
        assertNull(MediaOutputText.kindOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertNull(MediaOutputText.kindOf(AudioDeviceInfo.TYPE_TELEPHONY))
        assertNull(MediaOutputText.choose(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))))
        assertNull(MediaOutputText.choose(emptyList()))
    }

    @Test
    fun `the words, with the device's name for Bluetooth and no vendor string for anything else`() {
        assertEquals("Speaker", MediaOutputText.label(MediaOutput(MediaOutputKind.SPEAKER)))
        assertEquals("Wired headphones", MediaOutputText.label(MediaOutput(MediaOutputKind.WIRED_HEADPHONES)))
        assertEquals("USB audio", MediaOutputText.label(MediaOutput(MediaOutputKind.USB_AUDIO)))
        assertEquals("Bluetooth · WH-1000XM5", MediaOutputText.label(MediaOutput(MediaOutputKind.BLUETOOTH, "WH-1000XM5")))
        assertEquals("Bluetooth", MediaOutputText.label(MediaOutput(MediaOutputKind.BLUETOOTH)))
        assertEquals("HDMI", MediaOutputText.label(MediaOutput(MediaOutputKind.HDMI)))
        assertEquals("Other output", MediaOutputText.label(MediaOutput(MediaOutputKind.OTHER)))
        assertNull("nothing known says nothing", MediaOutputText.label(null))

        assertEquals("the name is kept for Bluetooth", MediaOutput(MediaOutputKind.BLUETOOTH, "Buds"), MediaOutputText.choose(listOf(device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, " Buds "))))
        assertEquals("a blank Bluetooth name is none", MediaOutput(MediaOutputKind.BLUETOOTH, null), MediaOutputText.choose(listOf(device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "  "))))
        assertEquals("a speaker's product string is not printed", MediaOutput(MediaOutputKind.SPEAKER, null), MediaOutputText.choose(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Some Vendor Speaker"))))
        assertEquals("nor a cable's", MediaOutput(MediaOutputKind.USB_AUDIO, null), MediaOutputText.choose(listOf(device(AudioDeviceInfo.TYPE_USB_HEADSET, "Some DAC"))))
    }

    @Test
    fun `where the platform routes media itself the first sink it lists is the answer`() {
        val routed = listOf(device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE), device(AudioDeviceInfo.TYPE_USB_HEADSET), device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(MediaOutputKind.USB_AUDIO, MediaOutputText.choose(routed)?.kind)
    }

    @Test
    fun `where it can only list every output the fallback ranks them Bluetooth, wired, USB, HDMI, speaker`() {
        val all = listOf(
            device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
            device(AudioDeviceInfo.TYPE_HDMI),
            device(AudioDeviceInfo.TYPE_USB_HEADSET),
            device(AudioDeviceInfo.TYPE_WIRED_HEADSET),
            device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds"),
        )
        assertEquals(MediaOutput(MediaOutputKind.BLUETOOTH, "Buds"), MediaOutputText.choose(all, ranked = true))
        assertEquals(MediaOutputKind.WIRED_HEADPHONES, MediaOutputText.choose(all.dropLast(1), ranked = true)?.kind)
        assertEquals(MediaOutputKind.USB_AUDIO, MediaOutputText.choose(all.take(3), ranked = true)?.kind)
        assertEquals(MediaOutputKind.HDMI, MediaOutputText.choose(all.take(2), ranked = true)?.kind)
        assertEquals(MediaOutputKind.SPEAKER, MediaOutputText.choose(all.take(1), ranked = true)?.kind)
        assertEquals("an earpiece beside the speaker is not chosen", MediaOutputKind.SPEAKER, MediaOutputText.choose(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE), device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)), ranked = true)?.kind)
    }
}
