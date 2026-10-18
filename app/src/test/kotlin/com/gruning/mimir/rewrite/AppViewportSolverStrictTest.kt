package com.gruning.mimir.rewrite

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AppViewportSolverStrictTest {

    @Test
    fun `home enrich geometry rejects zero width and height`() {
        assertThrows(IllegalArgumentException::class.java) {
            homeCustomEnrichControlGeometry(widthDp = 0f, heightDp = 600f, fontScale = 1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            homeCustomEnrichControlGeometry(widthDp = 800f, heightDp = 0f, fontScale = 1f)
        }
        assertNotNull(homeCustomEnrichControlGeometry(widthDp = 800f, heightDp = 600f, fontScale = 1f))
    }

    @Test
    fun `home tile menu geometry rejects zero width and height`() {
        assertThrows(IllegalArgumentException::class.java) {
            homeTileMenuGeometry(widthDp = 0f, heightDp = 600f, fontScale = 1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            homeTileMenuGeometry(widthDp = 800f, heightDp = 0f, fontScale = 1f)
        }
        assertNotNull(homeTileMenuGeometry(widthDp = 800f, heightDp = 600f, fontScale = 1f))
    }
}
