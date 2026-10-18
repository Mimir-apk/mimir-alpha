package com.gruning.mimir.rewrite.core.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.intercept.Interceptor
import coil.request.ImageResult
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A frame resolves its art source ONCE and hands the verdict down: the plate above and the
 * content below used to run independent resolvers, so a missing verdict that reached the inner one first drew a glyph before the outer
 * plate appeared, and a flat cover stat'ed its file twice. Each resolver asks a stat of the dispatcher, so the number of blocks the
 * dispatcher is handed IS the number of stats.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirArtOneVerdictTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class CountingDispatcher : CoroutineDispatcher() {
        var dispatched = 0
        val held = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched += 1
            held.addLast(block)
        }

        fun release() {
            while (held.isNotEmpty()) held.removeFirst().run()
        }
    }

    private val dispatcher = CountingDispatcher()

    @Before
    fun hold() {
        MimirArtAvailability.clearForTest()
        MimirArtAvailability.dispatcher = dispatcher
        val context = ApplicationProvider.getApplicationContext<Context>()
        // A loader that never answers, so only the availability stat is under test.
        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .components {
                    add(object : Interceptor {
                        override suspend fun intercept(chain: Interceptor.Chain): ImageResult = awaitCancellation()
                    })
                }
                .build(),
        )
    }

    @After
    fun restore() {
        MimirArtAvailability.dispatcher = Dispatchers.IO
        MimirArtAvailability.clearForTest()
        Coil.reset()
    }

    private fun host(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalMimirPalette provides MimirThemePalette.forId(MimirThemeId.DEFAULT)) { content() }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a flat cover stats its file once, not once for the panel and again inside the box art`() {
        host { MimirFlatCoverArt(title = "Game", artRef = Cover) }
        assertEquals("one stat for the one source", 1, dispatcher.dispatched)
        dispatcher.release()
        compose.waitForIdle()
        assertEquals("and the verdict landing starts no second", 1, dispatcher.dispatched)
    }

    @Test
    fun `a media frame whose content is box art stats its file once`() {
        host {
            MediaArtFrame(
                title = "Game",
                artRef = Cover,
                artContent = { ref -> MimirBoxArt(model = ref, title = "Game", focused = false) },
            )
        }
        assertEquals("the frame's own verdict is the content's, so one stat", 1, dispatcher.dispatched)
        dispatcher.release()
        compose.waitForIdle()
        assertEquals(1, dispatcher.dispatched)
    }

    private companion object {
        const val Cover = "/no/such/dir/media_art/cover.png"
    }
}
