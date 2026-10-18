package com.gruning.mimir.rewrite.feature.settings

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ViewportSolverStrictTest {

    @Test
    fun `settings modal width cap rejects zero width and height`() {
        assertThrows(IllegalArgumentException::class.java) {
            settingsModalWidthCapDp(widthDp = 0f, heightDp = 600f, authoredCapDp = 640f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            settingsModalWidthCapDp(widthDp = 640f, heightDp = 0f, authoredCapDp = 640f)
        }
        assertNotNull(settingsModalWidthCapDp(widthDp = 800f, heightDp = 600f, authoredCapDp = 640f))
    }
}
