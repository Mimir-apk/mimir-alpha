package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.posture.MimirConnectedArtGate
import java.io.File
import java.net.URI
import java.net.URL
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirImageLoaderPolicyTest {
    @After
    fun closeProviderGate() {
        MimirConnectedArtGate.setOpen(false)
    }

    @Test
    fun `remote renderer models stay blocked when ScreenScraper consent is open`() {
        MimirConnectedArtGate.setOpen(true)

        listOf(
            "https://media.screenscraper.fr/game.png",
            "HTTP://example.com/game.png",
            "  https:opaque",
            URI("https://media.screenscraper.fr/game.png"),
            URL("https://media.screenscraper.fr/game.png"),
            object {
                override fun toString(): String = "https://media.screenscraper.fr/from-model.png"
            },
        ).forEach { model ->
            assertTrue(
                "model=$model",
                mimirImageRequestDecision(model) == MimirImageRequestDecision.BLOCK_REMOTE,
            )
        }
    }

    @Test
    fun `local renderer models remain available`() {
        val localModels = listOf(
            null,
            "/data/user/0/com.gruning.mimir.rewrite/files/cover.png",
            "file:///data/user/0/com.gruning.mimir.rewrite/files/cover.png",
            "content://com.android.providers.media.documents/document/image%3A1",
            "android.resource://com.gruning.mimir.rewrite/drawable/mimir_mark",
            File("cover.png"),
            File("https:cover.png"),
            42,
        )
        localModels.forEach { model ->
            assertFalse(
                "model=$model",
                mimirImageRequestDecision(model) == MimirImageRequestDecision.BLOCK_REMOTE,
            )
        }
        val opaqueModel = object {
            override fun toString(): String = error("renderer policy must not trust arbitrary models")
        }
        assertFalse(
            mimirImageRequestDecision(opaqueModel) == MimirImageRequestDecision.BLOCK_REMOTE,
        )
    }
}
