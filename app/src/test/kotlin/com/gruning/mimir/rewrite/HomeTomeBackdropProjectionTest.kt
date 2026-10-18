package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.FocusState
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.HomeShelfTome
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioScope
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioUiState
import com.gruning.mimir.rewrite.feature.library.HomeTomeMember
import com.gruning.mimir.rewrite.feature.library.TomeId
import com.gruning.mimir.rewrite.feature.library.homeTomeSeatFocusKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeTomeBackdropProjectionTest {
    private val tomeId = TomeId.Platform(PlatformId("snes"))
    private val first = HomeTomeMember(
        itemId = HomeShelfItemId.Emulated(GameId("first")),
        title = "First",
        backdropArtUri = "file:///first.jpg",
    )
    private val second = HomeTomeMember(
        itemId = HomeShelfItemId.Emulated(GameId("second")),
        title = "Second",
        backdropArtUri = "file:///second.jpg",
    )
    private val folio = HomeTomeFolioUiState(
        tome = HomeShelfTome(tomeId, "SNES", emptyList(), memberCount = 2),
        members = listOf(first, second),
        focusedIndex = 1,
    )

    @Test
    fun `authoritative painted member wins while reducer requests the next seat`() {
        assertEquals(
            first,
            homeTomeFolioBackdropMember(
                folio,
                FocusState(HomeTomeFolioScope, first.focusKey),
            ),
        )
    }

    @Test
    fun `authoritative empty seat clears backdrop instead of leaking requested member`() {
        assertNull(
            homeTomeFolioBackdropMember(
                folio,
                FocusState(HomeTomeFolioScope, homeTomeSeatFocusKey(tomeId, 2)),
            ),
        )
    }

    @Test
    fun `cold composition falls back to reducer seat until Folio scope publishes`() {
        assertEquals(
            second,
            homeTomeFolioBackdropMember(
                folio,
                FocusState(FocusScope.Root, null),
            ),
        )
    }
}
