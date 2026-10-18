package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 2026-08-11 review: the in-place Quick Launch panel can sit over a Library surface, so its slot
 * keys can reach the origin capture. A foreign key persisted as the Library return focus poisons
 * the process-restart restore (every bounded requestFocus attempt fails); no key beats a foreign
 * key. A real gamelist key still passes through untouched.
 */
class LibraryOriginReturnFocusTest {

    @Test
    fun `quick launch slot keys never persist as a library return focus`() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        viewModel.openPlatform(PlatformId.PS1)
        withTimeout(5_000) { viewModel.games.first { it.isNotEmpty() } }

        val target = viewModel.captureOriginReturnTarget(FocusKey("home/quicklaunch/slot-1"))
        assertEquals(LibraryOriginReturnTarget.Surface.GAMELIST, target.surface)
        assertNull(target.focusKey)
    }

    @Test
    fun `a real gamelist key passes through the origin capture untouched`() = runBlocking {
        val viewModel = LibraryViewModel(FakeLibraryRepository())
        viewModel.openPlatform(PlatformId.PS1)
        val games = withTimeout(5_000) { viewModel.games.first { it.isNotEmpty() } }

        val key = gameKey(games.first())
        assertEquals(key, viewModel.captureOriginReturnTarget(key).focusKey)
    }
}
