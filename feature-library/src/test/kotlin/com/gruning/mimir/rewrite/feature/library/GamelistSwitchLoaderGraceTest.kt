package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 *
 *
 * Measured on the Odin on the shipped build: a platform switch rendered *"NO GAMES ON THIS PLATFORM
 * YET."* for PlayStation, which has three games — on every switch, twice over (once with no rows, again
 * with rows but no art). That blank is what made the switch read as a snap, and transition changes alone
 * could not make the missing page visible: no alpha curve can fade in a page that is not there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GamelistSwitchLoaderGraceTest {
    private val english = RuntimeEnvironment.getApplication().resources

    @Test
    fun `an empty list right after a switch holds the loader, not the empty message`() {
        assertTrue(
            "this is the exact frame he photographed: the query has 'returned' because the " +
                "paint-immediately cache already marked it loaded, while the stale-list guard has " +
                "dropped the list to empty. Believing that pair prints 'no games' about a platform " +
                "that has three.",
            libraryGamelistShowsLoader(
                gamesLoaded = true,
                gamesEmpty = true,
                withinSwitchGrace = true,
            ),
        )
    }

    /**
     * The bound, and the reason there is one. Holding until the list is non-empty would spin forever on
     * a platform that genuinely has no games — absence needs an exit, or a missing thing becomes a hang.
     */
    @Test
    fun `a genuinely empty platform still gets to say so, once the window closes`() {
        assertFalse(
            "past the grace the empty message is believed -- an honest empty platform is not made to " +
                "spin, it just has to wait out the switch first",
            libraryGamelistShowsLoader(
                gamesLoaded = true,
                gamesEmpty = true,
                withinSwitchGrace = false,
            ),
        )
    }

    @Test
    fun `content ends the loader immediately, without waiting out the window`() {
        assertFalse(
            "the grace is a ceiling on the WAIT, never a floor on it -- rows that land in 40ms must " +
                "paint at 40ms",
            libraryGamelistShowsLoader(
                gamesLoaded = true,
                gamesEmpty = false,
                withinSwitchGrace = true,
            ),
        )
    }

    @Test
    fun `an unsettled query holds the loader regardless of the window`() {
        assertTrue(
            "not-yet-returned outranks the grace entirely; the window only ever decides what an EMPTY " +
                "answer means",
            libraryGamelistShowsLoader(
                gamesLoaded = false,
                gamesEmpty = true,
                withinSwitchGrace = false,
            ),
        )
    }

    /**
     * The header carried the SAME false claim in a different string: a zero game count renders
     * "No games", and during a switch the list is legitimately empty for a beat — so the header told him a
     * three-game platform had none, right beside a body that had just been fixed to stay quiet.
     */
    @Test
    fun `the header omits the count while the loader holds, instead of saying No games`() {
        val duringSwitch = gamelistHeaderMetaForTest(english, count = 0, countSettled = false)
        assertFalse(
            "the header must not claim emptiness on a platform whose query has not settled: $duringSwitch",
            duringSwitch.contains("No games"),
        )
        assertTrue(
            "and once settled an honest empty platform still says so",
            gamelistHeaderMetaForTest(english, count = 0, countSettled = true).contains("No games"),
        )
        assertTrue(
            "a settled non-empty platform reads normally",
            gamelistHeaderMetaForTest(english, count = 3, countSettled = true).contains("3 games"),
        )
    }

    @Test
    fun `the window is his 400ms`() {
        assertEquals(
            "the floor follows one page-glide duration",
            400L,
            LibraryGamelistEmptyGraceMs,
        )
    }
}
