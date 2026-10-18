package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.feature.library.FavoriteSegment
import com.gruning.mimir.rewrite.feature.library.HomeLaunchReturnTarget
import com.gruning.mimir.rewrite.feature.library.LibraryLaunchReturnTarget
import com.gruning.mimir.rewrite.feature.library.LibraryShelfMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind the return cover's status line (AYN Thor, 2026-10-07), with no composition: which arm holds the
 * cover, whom the line may name, its copy, its log line, the fade's length, and the wiring in `MimirPrimaryReturnCover`.
 * The line itself, on screen, is `ReturnWaitStatusTest`.
 *
 * Return identity contract: the line names a game only when the ledger holds the session that armed THIS
 * return, never the newest or the last-ended one; and an app label that is its own package name is never read out.
 */
class ReturnWaitLogicTest {

    private val homeTarget = HomeLaunchReturnTarget(itemId = HomeShelfItemId.Emulated(GameId("g")), rank = 0L)

    private fun homeHandle(token: String) = HomeLaunchReturnHandle(target = homeTarget, token = token)

    /** A ROM gamelist target for game "g", or (without a game) a shelf target: the two shapes a Library return can have. */
    private fun libraryTarget(withGame: Boolean = true): LibraryLaunchReturnTarget =
        if (withGame) {
            LibraryLaunchReturnTarget.gamelistRom(
                openPlatformId = PlatformId("3ds"),
                romPlatformId = PlatformId("3ds"),
                gameId = GameId("g"),
                shelfMode = LibraryShelfMode.EMULATION,
                favoriteSegment = FavoriteSegment.GAMES,
                searchQuery = "",
                favoritesOnly = false,
            )
        } else {
            LibraryLaunchReturnTarget.shelf(LibraryShelfMode.EMULATION, "shelf")
        }

    private fun game(
        title: String = "Metroid: Samus Returns",
        pkg: String? = "io.github.lime3ds.android",
        phase: MimirSessionPhase = MimirSessionPhase.ENDED,
        sequence: Long = 5L,
        placement: MimirGamePlacement = MimirGamePlacement.MAIN,
        token: String? = null,
        anchor: MimirGameReturnAnchor = MimirGameReturnAnchor.Library(libraryTarget(withGame = false)),
    ) = MimirGameSession(
        operation = MimirGameSessionOperation("op-$sequence"),
        sequence = sequence,
        gameId = GameId("g"),
        title = title,
        platformId = PlatformId("3ds"),
        returnAnchor = anchor,
        homeReturnHandle = token?.let(::homeHandle),
        startedAtEpochMs = 1,
        targetPackageName = pkg,
        placement = placement,
        dualScreen = false,
        phase = phase,
    )

    private fun app(
        label: String = "Chrome",
        pkg: String = "com.android.chrome",
        phase: MimirSessionPhase = MimirSessionPhase.ENDED,
        sequence: Long = 9L,
    ) = MimirAppSession(
        operationId = "app:$sequence",
        sequence = sequence,
        packageName = pkg,
        label = label,
        placement = MimirAppPlacement.PRIMARY,
        returnAnchor = PrimaryReturnOrigin(destination = MimirDestination.HOME.name),
        startedAtEpochMs = 1L,
        phase = phase,
    )

    private fun homeSubject(snapshot: MimirSessionSnapshot, armedToken: String? = "tok-1") =
        returnWaitSubject(snapshot, ReturnWaitCause.HOME_RETURN, ReturnWaitArmed(homeToken = armedToken))

    // ── which arm ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the cause is the first arm that is true, Library and native before Home`() {
        assertEquals(
            ReturnWaitCause.LIBRARY_RETURN,
            returnWaitCause(native = true, library = true, homeAnchored = true),
        )
        assertEquals(
            ReturnWaitCause.NATIVE_RETURN,
            returnWaitCause(native = true, library = false, homeAnchored = true),
        )
        assertEquals(
            ReturnWaitCause.HOME_RETURN,
            returnWaitCause(native = false, library = false, homeAnchored = true),
        )
        assertEquals(
            ReturnWaitCause.HOME_TRUTH,
            returnWaitCause(native = false, library = false, homeAnchored = false),
        )
    }

    // ── whom it names: the session that armed THIS return ────────────────────────────────────────────────────────────

    @Test
    fun `the Home game whose return handle is the armed record is named, with its package`() {
        for (phase in listOf(MimirSessionPhase.RETURNING, MimirSessionPhase.ENDED, MimirSessionPhase.FAILED)) {
            val subject = homeSubject(MimirSessionSnapshot(gameSession = game(phase = phase, token = "tok-1")))
            assertEquals(phase.name, "Metroid: Samus Returns", subject.title)
            assertEquals(phase.name, "io.github.lime3ds.android", subject.packageName)
        }
    }

    @Test
    fun `a Home restore with no armed return names nobody, whatever game ended before it`() {
        val ended = MimirSessionSnapshot(gameSession = game(token = "tok-1"))
        assertEquals(ReturnWaitSubject.None, homeSubject(ended, armedToken = null))
        assertEquals(
            ReturnWaitSubject.None,
            returnWaitSubject(ended, ReturnWaitCause.HOME_RETURN, ReturnWaitArmed.None),
        )
        assertEquals(
            "a Home that is only waiting on its truth is not a return from anything",
            ReturnWaitSubject.None,
            returnWaitSubject(ended, ReturnWaitCause.HOME_TRUTH, ReturnWaitArmed(homeToken = "tok-1")),
        )
    }

    @Test
    fun `an ended game that is not the armed one is not named`() {
        // Another outing's handle, and a game with no handle at all (a Library launch).
        assertEquals(ReturnWaitSubject.None, homeSubject(MimirSessionSnapshot(gameSession = game(token = "tok-OTHER"))))
        assertEquals(ReturnWaitSubject.None, homeSubject(MimirSessionSnapshot(gameSession = game(token = null))))
    }

    @Test
    fun `a Bottom game that ended later than the Main return is not the game being returned from`() {
        // The ledger holds one game: here the newer one, played on the Bottom, carrying the very token that is armed.
        val bottomLater = MimirSessionSnapshot(
            gameSession = game(sequence = 12L, placement = MimirGamePlacement.BOTTOM, token = "tok-1"),
        )
        assertEquals(ReturnWaitSubject.None, homeSubject(bottomLater))
        // And against the Main app that really is returning, the newer Bottom game does not outrank it.
        val appBeatsBottom = MimirSessionSnapshot(
            gameSession = game(sequence = 12L, placement = MimirGamePlacement.BOTTOM, token = "tok-1"),
            primaryAppSession = app(sequence = 9L),
        )
        val subject = returnWaitSubject(
            appBeatsBottom,
            ReturnWaitCause.NATIVE_RETURN,
            ReturnWaitArmed(nativePackage = "com.android.chrome"),
        )
        assertEquals("Chrome", subject.title)
        assertEquals("com.android.chrome", subject.packageName)
    }

    @Test
    fun `a session that is still out or not yet settled names nobody`() {
        val notReturned = listOf(
            MimirSessionPhase.PREPARING,
            MimirSessionPhase.DISPATCHED,
            MimirSessionPhase.FOREGROUND_PROVEN,
            MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
            MimirSessionPhase.RECOVERING,
            MimirSessionPhase.STALE,
        )
        for (phase in notReturned) {
            assertEquals(
                phase.name,
                ReturnWaitSubject.None,
                homeSubject(MimirSessionSnapshot(gameSession = game(phase = phase, token = "tok-1"))),
            )
            assertEquals(
                phase.name,
                ReturnWaitSubject.None,
                returnWaitSubject(
                    MimirSessionSnapshot(primaryAppSession = app(phase = phase)),
                    ReturnWaitCause.NATIVE_RETURN,
                    ReturnWaitArmed(nativePackage = "com.android.chrome"),
                ),
            )
        }
    }

    @Test
    fun `the Library arm names the Main game whose anchor is the pending target, game for game`() {
        val target = libraryTarget()
        val matching = MimirSessionSnapshot(gameSession = game(anchor = MimirGameReturnAnchor.Library(target)))
        val armed = ReturnWaitArmed(libraryTarget = target)
        assertEquals("Metroid: Samus Returns", returnWaitSubject(matching, ReturnWaitCause.LIBRARY_RETURN, armed).title)

        val otherAnchor = MimirSessionSnapshot(
            gameSession = game(anchor = MimirGameReturnAnchor.Library(libraryTarget().copy(searchQuery = "metroid"))),
        )
        assertEquals(ReturnWaitSubject.None, returnWaitSubject(otherAnchor, ReturnWaitCause.LIBRARY_RETURN, armed))
        assertEquals(ReturnWaitSubject.None, returnWaitSubject(matching, ReturnWaitCause.LIBRARY_RETURN, ReturnWaitArmed.None))
        val shelfOnly = ReturnWaitArmed(libraryTarget = libraryTarget(withGame = false))
        assertEquals(ReturnWaitSubject.None, returnWaitSubject(matching, ReturnWaitCause.LIBRARY_RETURN, shelfOnly))
        val bottom = MimirSessionSnapshot(
            gameSession = game(placement = MimirGamePlacement.BOTTOM, anchor = MimirGameReturnAnchor.Library(target)),
        )
        assertEquals(ReturnWaitSubject.None, returnWaitSubject(bottom, ReturnWaitCause.LIBRARY_RETURN, armed))
    }

    @Test
    fun `the native arm names the app of the armed package and no other`() {
        val snapshot = MimirSessionSnapshot(primaryAppSession = app())
        assertEquals(
            "Chrome",
            returnWaitSubject(snapshot, ReturnWaitCause.NATIVE_RETURN, ReturnWaitArmed(nativePackage = "com.android.chrome")).title,
        )
        assertEquals(
            ReturnWaitSubject.None,
            returnWaitSubject(snapshot, ReturnWaitCause.NATIVE_RETURN, ReturnWaitArmed(nativePackage = "com.example.other")),
        )
        assertEquals(
            ReturnWaitSubject.None,
            returnWaitSubject(snapshot, ReturnWaitCause.NATIVE_RETURN, ReturnWaitArmed.None),
        )
    }

    @Test
    fun `a blank game title keeps the package for the log and names nobody on screen`() {
        val subject = homeSubject(MimirSessionSnapshot(gameSession = game(title = "   ", token = "tok-1")))
        assertNull(subject.title)
        assertEquals("io.github.lime3ds.android", subject.packageName)
        assertEquals("Welcome back — restoring your library…", returnWaitHeadline(subject.title))
    }

    @Test
    fun `an empty ledger names nobody`() {
        assertEquals(ReturnWaitSubject.None, homeSubject(MimirSessionSnapshot()))
    }

    // ── an app label that is the ledger's own fallback ───────────────────────────────────────────────────────────────

    @Test
    fun `an app label that is its package name, or shaped like one, gives the generic line`() {
        val armed = ReturnWaitArmed(nativePackage = "com.example.app")
        for (label in listOf("com.example.app", " COM.EXAMPLE.APP ", "com.other.thing", "org.videolan.vlc", "a.b", "_x.y9")) {
            val subject = returnWaitSubject(
                MimirSessionSnapshot(primaryAppSession = app(label = label, pkg = "com.example.app")),
                ReturnWaitCause.NATIVE_RETURN,
                armed,
            )
            assertNull(label, subject.title)
            assertEquals(label, "com.example.app", subject.packageName)
            assertEquals(label, "Welcome back — restoring your library…", returnWaitHeadline(subject.title))
        }
        // A blank label is stored as the package by the ledger; if one ever arrives blank it is the same non-name.
        val blank = returnWaitSubject(
            MimirSessionSnapshot(primaryAppSession = app(label = "  ", pkg = "com.example.app")),
            ReturnWaitCause.NATIVE_RETURN,
            armed,
        )
        assertNull(blank.title)
    }

    @Test
    fun `a real app label survives, dots and all`() {
        for (label in listOf("Chrome", "VLC for Android", "Dr. Mario", "S.T.A.L.K.E.R.", "Plex: Stream", "Mr.Foo Bar")) {
            assertEquals(label, label.appLabelOrNull("com.example.app"))
        }
        assertNull("com.example.app".appLabelOrNull("com.example.app"))
        assertNull("Com.Example.App".appLabelOrNull("com.example.app"))
        assertNull("net.kodi".appLabelOrNull("com.example.app"))
    }

    // ── what it says and writes ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the headline names the game or greets, and never changes the title's case`() {
        assertEquals("Welcome back — restoring your library…", returnWaitHeadline(null))
        assertEquals("Welcome back — restoring your library…", returnWaitHeadline(""))
        assertEquals("Back from Metroid: Samus Returns — restoring your library…", returnWaitHeadline("Metroid: Samus Returns"))
        assertEquals("Back from pokémon RANGER — restoring your library…", returnWaitHeadline("  pokémon RANGER "))
    }

    @Test
    fun `the return-wait log names the package or none`() {
        assertEquals(
            "return-wait shown game=io.github.lime3ds.android waitedMs=4500 cause=home-return",
            returnWaitLogLine(ReturnWaitSubject("Metroid", "io.github.lime3ds.android"), 4_500L, ReturnWaitCause.HOME_RETURN),
        )
        assertEquals(
            "return-wait shown game=none waitedMs=820 cause=home-truth scope=home/shelf",
            returnWaitLogLine(ReturnWaitSubject.None, 820L, ReturnWaitCause.HOME_TRUTH, "scope=home/shelf"),
        )
    }

    @Test
    fun `the fade is the overlay clock, and none under reduced motion or while parked`() {
        assertEquals(MimirMotion.OverlayMs, returnWaitFadeMs(reducedMotion = false, live = true))
        assertEquals(0, returnWaitFadeMs(reducedMotion = true, live = true))
        assertEquals(0, returnWaitFadeMs(reducedMotion = false, live = false))
        assertEquals(0, returnWaitFadeMs(reducedMotion = true, live = false))
    }

    // ── wiring and house rules, by source ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the return cover seats the status over the badge and still swallows Back`() {
        val host = appSource("MimirAppHost.kt").readText()
        val seat = host.substringAfter("private fun MimirPrimaryReturnCover(").substringBefore("private fun MimirCoordinatorAmbientMotionProvider(")
        val cover = appSource("ReturnCoverSurface.kt").readText()
        assertTrue("the badge size is the one constant the status hangs from", cover.contains("MimirMannazBadge(palette = palette, size = ReturnCoverBadgeSize)"))
        assertTrue("the status is seated in the cover with the cover's own flag", seat.contains("MimirPrimaryReturnWaitStatus(bindings, requested)"))
        assertTrue("the cover still blocks Back while it is requested", cover.contains("if (requested) BackHandler { }"))
        assertTrue("and still consumes every pointer event while it is requested", cover.contains("awaitPointerEvent().changes.forEach { it.consume() }"))
    }

    @Test
    fun `the seat reads the armed record without moving a return's bookkeeping, and the subject takes it`() {
        val source = appSource("ReturnWaitStatus.kt").readText()
        assertTrue("it asks the store for the armed token only", source.contains("HomeLaunchReturnStore.armedToken(context)"))
        assertFalse("peek finishes orphaned measurements; a line of words must not", source.contains("HomeLaunchReturnStore.peek("))
        assertTrue("the subject is chosen against the armed identity", source.contains("returnWaitSubject(session, cause, armed)"))
        val store = appSource("HomeLaunchReturnStore.kt").readText()
        val armedToken = store.substringAfter("suspend fun armedToken(").substringBefore("suspend fun acknowledge(")
        assertFalse("armedToken is a pure read", armedToken.contains("finishOrphanedMainReturn") || armedToken.contains("peek("))
    }

    @Test
    fun `the status uses theme tokens and takes no focus of its own`() {
        val source = appSource("ReturnWaitStatus.kt").readText()
        assertFalse("no hex colour literal", Regex("""0x[0-9A-Fa-f]{6,8}""").containsMatchIn(source))
        assertFalse("no Color( literal", source.contains("Color("))
        assertFalse("focus belongs to FocusAuthority", Regex("""\.focusable\(|focusTarget\(""").containsMatchIn(source))
        assertTrue("it rides the shared indeterminate bar", source.contains("MimirIndeterminateBar("))
        assertTrue("and one polite live region", source.contains("liveRegion = LiveRegionMode.Polite"))
        assertTrue(
            "TalkBack is told the visible line, word for word",
            source.contains("contentDescription = headline") && source.contains("text = headline"),
        )
    }

    private fun appSource(fileName: String): File = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
    ).firstOrNull { it.isFile }
        ?: throw AssertionError("$fileName not reachable from the test working directory")
}
