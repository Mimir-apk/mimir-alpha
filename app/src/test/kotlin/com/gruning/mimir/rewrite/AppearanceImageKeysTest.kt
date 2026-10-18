package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirButtonMarkStyle
import com.gruning.mimir.rewrite.core.ui.MimirSceneGroundBlur
import com.gruning.mimir.rewrite.core.ui.MimirSceneGroundDim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire form of the appearance-image preferences (concept A): `sha|DIM|BLUR` and `sha|STRUCK` round-trip, an
 * unknown step name falls back to that field's default, and an id that is not a hex hash is refused because it
 * names a file. The delete-on-default rule itself is pinned with a real preference file in
 * [AppearanceOverrideStoreImagesTest].
 */
class AppearanceImageKeysTest {

    private val id = "0123456789abcdef0123456789abcdef"

    @Test
    fun `a ground value round-trips its three fields`() {
        val value = encodeAppearanceGroundValue(id, MimirSceneGroundDim.STRONG, MimirSceneGroundBlur.SOFT)
        assertEquals("$id|STRONG|SOFT", value)
        assertEquals(
            AppearanceGroundKey(id, MimirSceneGroundDim.STRONG, MimirSceneGroundBlur.SOFT),
            decodeAppearanceGroundValue(value),
        )
    }

    @Test
    fun `unknown step names fall back to the defaults and never throw`() {
        assertEquals(
            AppearanceGroundKey(id, MimirSceneGroundDim.MEDIUM, MimirSceneGroundBlur.OFF),
            decodeAppearanceGroundValue("$id|BLINDING|GLASS"),
        )
        assertEquals(
            AppearanceGroundKey(id, MimirSceneGroundDim.MEDIUM, MimirSceneGroundBlur.OFF),
            decodeAppearanceGroundValue(id),
        )
    }

    @Test
    fun `an absent or malformed value is no ground`() {
        assertNull(decodeAppearanceGroundValue(null))
        assertNull(decodeAppearanceGroundValue(""))
        assertNull(decodeAppearanceGroundValue("|MEDIUM|OFF"))
        // Not a hash: an id names a file under filesDir, so a path-shaped value is refused outright.
        assertNull(decodeAppearanceGroundValue("../../etc/passwd|MEDIUM|OFF"))
        assertNull(decodeAppearanceGroundValue("ABCDEF0123456789|MEDIUM|OFF"))
    }

    @Test
    fun `a mark value round-trips and defaults its style`() {
        val value = encodeAppearanceMarkValue(id, MimirButtonMarkStyle.STRUCK)
        assertEquals("$id|STRUCK", value)
        assertEquals(AppearanceMarkKey(id, MimirButtonMarkStyle.STRUCK), decodeAppearanceMarkValue(value))
        assertEquals(AppearanceMarkKey(id, MimirButtonMarkStyle.STRUCK), decodeAppearanceMarkValue("$id|FULL_COLOUR"))
        assertNull(decodeAppearanceMarkValue(null))
        assertNull(decodeAppearanceMarkValue("rune"))
    }

    @Test
    fun `an id is a lowercase hex hash of 8 to 64 characters`() {
        assertTrue(isAppearanceImageId(id))
        assertTrue(isAppearanceImageId("deadbeef"))
        assertFalse(isAppearanceImageId("deadbee"))
        assertFalse(isAppearanceImageId("DEADBEEF"))
        assertFalse(isAppearanceImageId("dead beef"))
        assertFalse(isAppearanceImageId(null))
    }
}
