package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameAudioFocusHoldTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val game = "org.libretro.retroarch"

    // ---- the command templates and the package grammar -------------------------------------------

    @Test
    fun `the three command strings are exactly the fixed templates`() {
        assertEquals("cmd appops set $game TAKE_AUDIO_FOCUS ignore", GameAudioFocusCommands.hold(game))
        assertEquals("cmd appops set $game TAKE_AUDIO_FOCUS default", GameAudioFocusCommands.release(game))
        assertEquals("cmd appops get $game TAKE_AUDIO_FOCUS", GameAudioFocusCommands.read(game))
    }

    @Test
    fun `a malformed package yields no command at all`() {
        listOf(
            "",
            "retroarch",
            "1org.game",
            "org.game; reboot",
            "org.game ignore",
            "org.game\nid",
            "org.game\$(id)",
            "org..game",
            "org.game.",
            "-org.game",
            "a".repeat(200) + ".b",
        ).forEach { bad ->
            assertNull("hold for [$bad]", GameAudioFocusCommands.hold(bad))
            assertNull("release for [$bad]", GameAudioFocusCommands.release(bad))
            assertNull("read for [$bad]", GameAudioFocusCommands.read(bad))
        }
    }

    @Test
    fun `mimir packages are recognised by root and by prefix`() {
        assertTrue(GameAudioFocusCommands.isMimirPackage("com.gruning.mimir.rewrite"))
        assertTrue(GameAudioFocusCommands.isMimirPackage("com.gruning.mimir.tweaker"))
        assertTrue(GameAudioFocusCommands.isMimirPackage("com.gruning.mimir"))
        assertFalse(GameAudioFocusCommands.isMimirPackage("com.gruning.mimirgame"))
        assertFalse(GameAudioFocusCommands.isMimirPackage(game))
    }

    // ---- readback parsing --------------------------------------------------------------------------

    @Test
    fun `readback reads ignore, the not-ignored spellings, and refuses to guess`() {
        assertEquals(TakeAudioFocusMode.IGNORE, parseTakeAudioFocusMode("TAKE_AUDIO_FOCUS: ignore"))
        assertEquals(
            TakeAudioFocusMode.IGNORE,
            parseTakeAudioFocusMode("TAKE_AUDIO_FOCUS: ignore; rejectTime=+2s100ms ago"),
        )
        assertEquals(TakeAudioFocusMode.NOT_IGNORED, parseTakeAudioFocusMode("TAKE_AUDIO_FOCUS: default"))
        assertEquals(TakeAudioFocusMode.NOT_IGNORED, parseTakeAudioFocusMode("TAKE_AUDIO_FOCUS: allow; time=+3m ago"))
        assertEquals(TakeAudioFocusMode.NOT_IGNORED, parseTakeAudioFocusMode("No operations."))
        // Either the package mode or the uid mode saying ignore is a hold.
        assertEquals(
            TakeAudioFocusMode.IGNORE,
            parseTakeAudioFocusMode("Uid mode: TAKE_AUDIO_FOCUS: ignore\nTAKE_AUDIO_FOCUS: allow"),
        )
        assertNull(parseTakeAudioFocusMode(null))
        assertNull(parseTakeAudioFocusMode(""))
        assertNull(parseTakeAudioFocusMode("Error: Unknown package: $game"))
        assertNull(parseTakeAudioFocusMode("TAKE_AUDIO_FOCUS: deny"))
    }

    // ---- controller: hold ---------------------------------------------------------------------------

    @Test
    fun `a normal package is admitted, journalled before the command, and held on an ignore readback`() {
        val rig = Rig()

        val result = rig.controller.hold(game)

        assertEquals(GameAudioFocusHoldResult.Held(game), result)
        assertEquals(
            listOf("cmd appops set $game TAKE_AUDIO_FOCUS ignore", "cmd appops get $game TAKE_AUDIO_FOCUS"),
            rig.control.commands,
        )
        assertEquals(listOf("persist:$game", "cmd:set-ignore"), rig.order.take(2))
        assertEquals(game, rig.controller.heldPackage())
    }

    @Test
    fun `system, self, uninstalled and malformed packages are refused with no command and no journal`() {
        val rig = Rig()
        rig.packages.refusals["com.android.systemui"] =
            GameAudioFocusAdmission.Refused(TweakerGameAudioFocusContract.REFUSAL_SYSTEM_APP, "system")
        rig.packages.refusals["gone.app.x"] =
            GameAudioFocusAdmission.Refused(TweakerGameAudioFocusContract.REFUSAL_NOT_INSTALLED, "absent")

        val expected = mapOf(
            "com.android.systemui" to TweakerGameAudioFocusContract.REFUSAL_SYSTEM_APP,
            "gone.app.x" to TweakerGameAudioFocusContract.REFUSAL_NOT_INSTALLED,
            "com.gruning.mimir.rewrite" to TweakerGameAudioFocusContract.REFUSAL_SELF_PACKAGE,
            "com.gruning.mimir.tweaker" to TweakerGameAudioFocusContract.REFUSAL_SELF_PACKAGE,
            "not a package" to TweakerGameAudioFocusContract.REFUSAL_INVALID_PACKAGE,
            "x; id" to TweakerGameAudioFocusContract.REFUSAL_INVALID_PACKAGE,
        )
        expected.forEach { (pkg, code) ->
            val result = rig.controller.hold(pkg)
            assertEquals(pkg, code, (result as GameAudioFocusHoldResult.Refused).code)
        }
        assertTrue(rig.control.commands.isEmpty())
        assertEquals(GameAudioFocusJournalLoad.Empty, rig.journal.load())
    }

    @Test
    fun `a non-ignore readback rolls back to default and refuses`() {
        val rig = Rig()
        rig.control.script { cmd ->
            if (cmd.contains(" get ")) ok("TAKE_AUDIO_FOCUS: allow") else ok("")
        }

        val result = rig.controller.hold(game) as GameAudioFocusHoldResult.Refused

        assertEquals(TweakerGameAudioFocusContract.REFUSAL_WRITE_NOT_HELD, result.code)
        assertFalse(result.releaseDebt)
        assertTrue("put back", rig.control.commands.contains("cmd appops set $game TAKE_AUDIO_FOCUS default"))
        assertEquals(GameAudioFocusJournalLoad.Empty, rig.journal.load())
    }

    @Test
    fun `an ambiguous command with an unreadable op keeps the journal and reports the debt`() {
        val rig = Rig()
        rig.control.script { CheckedCommandResult(CheckedCommandStatus.AMBIGUOUS, detail = "pservice exception") }

        val result = rig.controller.hold(game) as GameAudioFocusHoldResult.Refused

        assertEquals(TweakerGameAudioFocusContract.REFUSAL_WRITE_NOT_HELD, result.code)
        assertTrue(result.releaseDebt)
        assertEquals(game, rig.controller.heldPackage())
    }

    @Test
    fun `a route that cannot run the command at all leaves no journal, so later launches are not poisoned`() {
        val rig = Rig()
        rig.control.script { CheckedCommandResult(CheckedCommandStatus.FAILED, detail = "exit 127") }

        val first = rig.controller.hold(game) as GameAudioFocusHoldResult.Refused

        assertEquals(TweakerGameAudioFocusContract.REFUSAL_HOST_UNAVAILABLE, first.code)
        assertFalse(first.releaseDebt)
        assertNull(rig.controller.heldPackage())
        assertEquals(GameAudioFocusJournalLoad.Empty, rig.journal.load())
        // The next game is judged on its own, not refused for a package that was never held.
        val second = rig.controller.hold("com.example.othergame") as GameAudioFocusHoldResult.Refused
        assertEquals(TweakerGameAudioFocusContract.REFUSAL_HOST_UNAVAILABLE, second.code)
        assertFalse(rig.control.commands.any { it.contains("othergame") && it.endsWith("default") })
        // And once the route works, it holds.
        rig.control.script { cmd ->
            if (cmd.contains(" get ")) {
                CheckedCommandResult(CheckedCommandStatus.CONFIRMED, "TAKE_AUDIO_FOCUS: ignore", 0)
            } else {
                CheckedCommandResult(CheckedCommandStatus.CONFIRMED, "", 0)
            }
        }
        assertTrue(rig.controller.hold(game) is GameAudioFocusHoldResult.Held)
    }

    @Test
    fun `an unavailable privileged transport refuses before any journal or command`() {
        val rig = Rig()
        rig.control.available = false

        val result = rig.controller.hold(game) as GameAudioFocusHoldResult.Refused

        assertEquals(TweakerGameAudioFocusContract.REFUSAL_HOST_UNAVAILABLE, result.code)
        assertTrue(rig.control.commands.isEmpty())
        assertEquals(GameAudioFocusJournalLoad.Empty, rig.journal.load())
    }

    @Test
    fun `a journal that cannot be written changes nothing`() {
        val rig = Rig()
        rig.memory.persistFails = true

        val result = rig.controller.hold(game) as GameAudioFocusHoldResult.Refused

        assertEquals(TweakerGameAudioFocusContract.REFUSAL_JOURNAL_UNAVAILABLE, result.code)
        assertTrue(rig.control.commands.isEmpty())
    }

    @Test
    fun `a new hold puts the previous package back first and never holds two`() {
        val rig = Rig()
        rig.controller.hold(game)
        rig.control.commands.clear()
        val next = "com.example.othergame"

        val result = rig.controller.hold(next)

        assertEquals(GameAudioFocusHoldResult.Held(next), result)
        assertEquals(
            listOf(
                "cmd appops set $game TAKE_AUDIO_FOCUS default",
                "cmd appops get $game TAKE_AUDIO_FOCUS",
                "cmd appops set $next TAKE_AUDIO_FOCUS ignore",
                "cmd appops get $next TAKE_AUDIO_FOCUS",
            ),
            rig.control.commands,
        )
        assertEquals(next, rig.controller.heldPackage())
    }

    @Test
    fun `a previous package that will not go back refuses the new hold`() {
        val rig = Rig()
        rig.controller.hold(game)
        rig.control.script { cmd -> if (cmd.contains(" get ")) ok("TAKE_AUDIO_FOCUS: ignore") else ok("") }
        rig.control.commands.clear()

        val result = rig.controller.hold("com.example.othergame") as GameAudioFocusHoldResult.Refused

        assertEquals(TweakerGameAudioFocusContract.REFUSAL_RELEASE_PREVIOUS_FAILED, result.code)
        assertTrue(result.releaseDebt)
        assertEquals(game, rig.controller.heldPackage())
        assertFalse(rig.control.commands.any { it.endsWith("ignore") })
    }

    // ---- controller: release paths ---------------------------------------------------------------

    @Test
    fun `release with nothing held runs no command`() {
        val rig = Rig()

        assertEquals(GameAudioFocusReleaseResult.NothingHeld, rig.controller.release())
        assertTrue(rig.control.commands.isEmpty())
    }

    @Test
    fun `release sets default, reads it back and clears the journal`() {
        val rig = Rig()
        rig.controller.hold(game)
        rig.control.commands.clear()

        val result = rig.controller.release()

        assertEquals(GameAudioFocusReleaseResult.Released(game), result)
        assertEquals(
            listOf("cmd appops set $game TAKE_AUDIO_FOCUS default", "cmd appops get $game TAKE_AUDIO_FOCUS"),
            rig.control.commands,
        )
        assertNull(rig.controller.heldPackage())
    }

    @Test
    fun `a release whose readback still says ignore is retained and retried later`() {
        val rig = Rig()
        rig.controller.hold(game)
        rig.control.script { cmd -> if (cmd.contains(" get ")) ok("TAKE_AUDIO_FOCUS: ignore") else ok("") }

        val retained = rig.controller.release() as GameAudioFocusReleaseResult.Retained

        assertEquals("RELEASE_NOT_OBSERVED", retained.code)
        assertEquals(game, rig.controller.heldPackage())
        rig.control.script { cmd -> if (cmd.contains(" get ")) ok("TAKE_AUDIO_FOCUS: default") else ok("") }
        assertEquals(GameAudioFocusReleaseResult.Released(game), rig.controller.release())
    }

    @Test
    fun `a boot-id change does not skip the release, because app-ops persist across reboot`() {
        val rig = Rig()
        rig.bootId = "boot-A"
        rig.controller.hold(game)
        rig.bootId = "boot-B"
        rig.control.commands.clear()

        val result = rig.controller.release()

        assertEquals(GameAudioFocusReleaseResult.Released(game), result)
        assertTrue(rig.control.commands.contains("cmd appops set $game TAKE_AUDIO_FOCUS default"))
    }

    @Test
    fun `an uninstalled package whose readback is unreadable is cleared rather than blocking forever`() {
        val rig = Rig()
        rig.controller.hold(game)
        rig.packages.installed = false
        rig.control.script { CheckedCommandResult(CheckedCommandStatus.FAILED) }

        val result = rig.controller.release()

        assertEquals(GameAudioFocusReleaseResult.Released(game, "packageGone=true"), result)
        assertNull(rig.controller.heldPackage())
    }

    @Test
    fun `an unreadable release for an installed package is retained, never cleared`() {
        val rig = Rig()
        rig.controller.hold(game)
        rig.control.script { CheckedCommandResult(CheckedCommandStatus.AMBIGUOUS) }

        val result = rig.controller.release() as GameAudioFocusReleaseResult.Retained

        assertEquals("RELEASE_READBACK_UNCERTAIN", result.code)
        assertEquals(game, rig.controller.heldPackage())
    }

    @Test
    fun `an unparseable corrupt journal is quarantined and reported as recovery needed, never as nothing held`() {
        val rig = Rig()
        rig.memory.loadOverride = GameAudioFocusJournalLoad.Corrupt(null)

        val result = rig.controller.release() as GameAudioFocusReleaseResult.Retained

        assertEquals("RECOVERY_NEEDED", result.code)
        assertNull(result.packageName)
        assertEquals("set aside, not deleted", 1, rig.memory.quarantined)
        assertEquals(0, rig.memory.cleared)
        assertTrue("no command can name a package it does not know", rig.control.commands.isEmpty())
        // The caller is told once; the next hold is judged on its own.
        assertEquals(GameAudioFocusReleaseResult.NothingHeld, rig.controller.release())
        assertTrue(rig.controller.hold(game) is GameAudioFocusHoldResult.Held)
    }

    @Test
    fun `an unparseable corrupt journal that cannot be set aside is retained and still not nothing held`() {
        val rig = Rig()
        rig.memory.loadOverride = GameAudioFocusJournalLoad.Corrupt(null)
        rig.memory.quarantineFails = true

        val result = rig.controller.release() as GameAudioFocusReleaseResult.Retained

        assertEquals("JOURNAL_CORRUPT_NOT_QUARANTINED", result.code)
        assertEquals(0, rig.memory.cleared)
    }

    @Test
    fun `a corrupt journal that still names a package puts that package back and reads it back before clearing`() {
        val rig = Rig()
        rig.memory.loadOverride = GameAudioFocusJournalLoad.Corrupt(game)
        rig.control.script { cmd -> if (cmd.contains(" get ")) ok("TAKE_AUDIO_FOCUS: default") else ok("") }

        val result = rig.controller.release()

        assertEquals(GameAudioFocusReleaseResult.Released(game, "journalCorrupt=true"), result)
        assertEquals(
            listOf("cmd appops set $game TAKE_AUDIO_FOCUS default", "cmd appops get $game TAKE_AUDIO_FOCUS"),
            rig.control.commands,
        )
        assertEquals(1, rig.memory.cleared)
    }

    @Test
    fun `a corrupt journal whose package will not read back default is kept`() {
        val rig = Rig()
        rig.memory.loadOverride = GameAudioFocusJournalLoad.Corrupt(game)
        rig.control.script { cmd -> if (cmd.contains(" get ")) ok("TAKE_AUDIO_FOCUS: ignore") else ok("") }

        val result = rig.controller.release() as GameAudioFocusReleaseResult.Retained

        assertEquals("RELEASE_NOT_OBSERVED", result.code)
        assertEquals(game, result.packageName)
        assertEquals(0, rig.memory.cleared)
        assertEquals(0, rig.memory.quarantined)
    }

    @Test
    fun `a journal that could not be read is kept and retried, not deleted and not nothing held`() {
        val rig = Rig()
        rig.memory.loadOverride = GameAudioFocusJournalLoad.Unreadable

        val result = rig.controller.release() as GameAudioFocusReleaseResult.Retained

        assertEquals("JOURNAL_UNREADABLE", result.code)
        assertEquals(0, rig.memory.cleared)
        assertEquals(0, rig.memory.quarantined)
        // A hold meanwhile refuses with the debt rather than stacking a second hold on an unknown one.
        val hold = rig.controller.hold(game) as GameAudioFocusHoldResult.Refused
        assertEquals(TweakerGameAudioFocusContract.REFUSAL_RELEASE_PREVIOUS_FAILED, hold.code)
        assertTrue(hold.releaseDebt)
        // And once the file reads again, the release proceeds.
        rig.memory.loadOverride = null
        assertEquals(GameAudioFocusReleaseResult.NothingHeld, rig.controller.release())
    }

    // ---- bounded readback: a hung read is SERVICE_BUSY, not a worker held forever ---------------

    @Test
    fun `a hold whose readback is busy is refused as SERVICE_BUSY and puts the package back`() {
        val rig = Rig()
        rig.control.readBusy = true

        val result = rig.controller.hold(game) as GameAudioFocusHoldResult.Refused

        assertEquals(TweakerGameAudioFocusContract.REFUSAL_SERVICE_BUSY, result.code)
        // The put-back could not be confirmed either (its readback is busy too), so the debt is kept.
        assertTrue(result.releaseDebt)
        assertEquals(game, rig.controller.heldPackage())
        assertEquals("one read for the hold and one for the put-back; a busy read is never retried", 2, rig.control.reads.size)
    }

    @Test
    fun `a release whose readback is busy is retained as SERVICE_BUSY`() {
        val rig = Rig()
        rig.controller.hold(game)
        rig.control.readBusy = true

        val result = rig.controller.release() as GameAudioFocusReleaseResult.Retained

        assertEquals(TweakerGameAudioFocusContract.REFUSAL_SERVICE_BUSY, result.code)
        assertEquals(game, rig.controller.heldPackage())
    }

    // ---- query: authoritative readback ------------------------------------------------------------

    @Test
    fun `query with nothing journalled is not held and runs no command`() {
        val rig = Rig()

        assertEquals(GameAudioFocusQueryResult.NotHeld, rig.controller.query())
        assertTrue(rig.control.commands.isEmpty())
    }

    @Test
    fun `query reports held from an ignore readback and not held from a default readback`() {
        val rig = Rig()
        rig.controller.hold(game)

        assertEquals(GameAudioFocusQueryResult.Held(game), rig.controller.query())

        // The journal still names the package, but the app-op is no longer ignore: the journal alone is not the answer.
        rig.control.script { cmd -> if (cmd.contains(" get ")) ok("TAKE_AUDIO_FOCUS: default") else ok("") }
        assertEquals(GameAudioFocusQueryResult.NotHeld, rig.controller.query())
    }

    @Test
    fun `query that cannot read the op back is unavailable, never not held`() {
        val rig = Rig()
        rig.controller.hold(game)

        rig.control.readBusy = true
        assertEquals(GameAudioFocusQueryResult.Unavailable("SERVICE_BUSY"), rig.controller.query())
        rig.control.readBusy = false
        rig.control.script { CheckedCommandResult(CheckedCommandStatus.AMBIGUOUS) }
        assertEquals(GameAudioFocusQueryResult.Unavailable("READBACK_UNREADABLE"), rig.controller.query())
        rig.control.available = false
        assertEquals(GameAudioFocusQueryResult.Unavailable("HOST_UNAVAILABLE"), rig.controller.query())
    }

    @Test
    fun `query over a damaged journal is unavailable, and writes nothing`() {
        val rig = Rig()
        rig.memory.loadOverride = GameAudioFocusJournalLoad.Corrupt(null)
        assertEquals(GameAudioFocusQueryResult.Unavailable("RECOVERY_NEEDED"), rig.controller.query())
        rig.memory.loadOverride = GameAudioFocusJournalLoad.Unreadable
        assertEquals(GameAudioFocusQueryResult.Unavailable("JOURNAL_UNREADABLE"), rig.controller.query())
        assertEquals(0, rig.memory.cleared + rig.memory.quarantined)
        assertTrue(rig.control.commands.isEmpty())
    }

    // ---- session: owner death and daemon restart -------------------------------------------------

    @Test
    fun `the owner Binder dying releases the hold`() {
        val rig = Rig()
        val token = FakeToken()

        assertTrue(rig.session.hold(token, game) is GameAudioFocusHoldResult.Held)
        assertNull("nothing released yet", rig.control.commands.firstOrNull { it.endsWith("default") })
        token.die()

        assertTrue(rig.control.commands.contains("cmd appops set $game TAKE_AUDIO_FOCUS default"))
        assertNull(rig.controller.heldPackage())
        assertFalse(rig.session.ownerPresent())
    }

    @Test
    fun `a dead client token is refused before anything is journalled`() {
        val rig = Rig()
        val token = FakeToken().apply { alive = false }

        val result = rig.session.hold(token, game) as GameAudioFocusHoldResult.Refused

        assertEquals(TweakerGameAudioFocusContract.REFUSAL_CLIENT_TOKEN_DEAD, result.code)
        assertTrue(rig.control.commands.isEmpty())
        assertEquals(GameAudioFocusJournalLoad.Empty, rig.journal.load())
    }

    @Test
    fun `a refused hold with no previous owner leaves no owner and never links the token`() {
        val rig = Rig()
        val token = FakeToken()

        rig.session.hold(token, "com.gruning.mimir.rewrite")

        assertFalse(rig.session.ownerPresent())
        assertFalse("linked only once the hold is confirmed", token.linked)
    }

    @Test
    fun `a refused replacement leaves the surviving hold with its owner and its death link`() {
        val rig = Rig()
        val first = FakeToken()
        val second = FakeToken()
        assertTrue(rig.session.hold(first, game) is GameAudioFocusHoldResult.Held)

        // The put-back of the first package cannot be confirmed, so the replacement is refused with debt.
        rig.control.script { cmd -> if (cmd.contains(" get ")) ok("TAKE_AUDIO_FOCUS: ignore") else ok("") }
        val refused = rig.session.hold(second, "com.example.othergame") as GameAudioFocusHoldResult.Refused

        assertEquals(TweakerGameAudioFocusContract.REFUSAL_RELEASE_PREVIOUS_FAILED, refused.code)
        assertTrue(refused.releaseDebt)
        assertTrue("the first owner is still the owner", rig.session.ownerPresent())
        assertFalse("and its death link was never dropped", first.unlinked)
        assertFalse("the refused token was never linked", second.linked)
        // The first client dying still releases what survives.
        rig.control.script { cmd -> if (cmd.contains(" get ")) ok("TAKE_AUDIO_FOCUS: default") else ok("") }
        first.die()
        assertNull(rig.controller.heldPackage())
    }

    @Test
    fun `a replacement that is confirmed moves ownership and only then drops the previous link`() {
        val rig = Rig()
        val first = FakeToken()
        val second = FakeToken()
        rig.session.hold(first, game)

        val result = rig.session.hold(second, "com.example.othergame")

        assertEquals(GameAudioFocusHoldResult.Held("com.example.othergame"), result)
        assertTrue(second.linked)
        assertTrue(first.unlinked)
        assertTrue(rig.session.ownerPresent())
    }

    @Test
    fun `a token that dies between the liveness check and the link has its confirmed hold put back`() {
        val rig = Rig()
        val token = FakeToken().apply { linkFails = true }

        val result = rig.session.hold(token, game) as GameAudioFocusHoldResult.Refused

        assertEquals(TweakerGameAudioFocusContract.REFUSAL_CLIENT_TOKEN_DEAD, result.code)
        assertFalse(result.releaseDebt)
        assertNull(rig.controller.heldPackage())
        assertFalse(rig.session.ownerPresent())
    }

    @Test
    fun `a stale tokens death does not release a newer owners hold`() {
        val rig = Rig()
        val first = FakeToken()
        val second = FakeToken()
        rig.session.hold(first, game)
        rig.session.hold(second, "com.example.othergame")
        rig.control.commands.clear()

        first.die()

        assertTrue(rig.control.commands.isEmpty())
        assertEquals("com.example.othergame", rig.controller.heldPackage())
    }

    @Test
    fun `a daemon restart puts back what the file journal still names`() {
        val file = File(folder.root, FileGameAudioFocusJournal.FILE_NAME)
        val first = Rig(journal = FileGameAudioFocusJournal(file))
        first.controller.hold(game)
        assertTrue(file.exists())

        val restarted = Rig(journal = FileGameAudioFocusJournal(file))
        val result = restarted.session.recoverOrphan()

        assertEquals(GameAudioFocusReleaseResult.Released(game), result)
        assertFalse(file.exists())
        assertTrue(restarted.control.commands.contains("cmd appops set $game TAKE_AUDIO_FOCUS default"))
    }

    // ---- file journal ---------------------------------------------------------------------------

    @Test
    fun `the file journal round trips, rejects damage, and survives a crash after staging`() {
        val file = File(folder.root, FileGameAudioFocusJournal.FILE_NAME)
        val journal = FileGameAudioFocusJournal(file)
        val record = GameAudioFocusRecord(game, "boot-1", 42L)

        assertEquals(GameAudioFocusJournalLoad.Empty, journal.load())
        assertTrue(journal.persist(record))
        assertEquals(GameAudioFocusJournalLoad.Loaded(record), journal.load())

        file.writeText("garbage")
        assertEquals(GameAudioFocusJournalLoad.Corrupt(null), journal.load())
        // A hand-edited file cannot name anything but a well-formed package.
        file.writeText("MIMIR_GAME_AUDIO_FOCUS_V1\nx; reboot\nboot-1\n1\n")
        assertEquals(GameAudioFocusJournalLoad.Corrupt(null), journal.load())
        // A damaged record that still names a well-formed package keeps that one line, enough to put it back.
        file.writeText("MIMIR_GAME_AUDIO_FOCUS_V1\n$game\nbad boot id\n1\n")
        assertEquals(GameAudioFocusJournalLoad.Corrupt(game), journal.load())
        file.writeText("MIMIR_GAME_AUDIO_FOCUS_V1\n$game\n")
        assertEquals(GameAudioFocusJournalLoad.Corrupt(game), journal.load())
        // A foreign file never donates a package.
        file.writeText("SOMETHING_ELSE\n$game\nboot-1\n1\n")
        assertEquals(GameAudioFocusJournalLoad.Corrupt(null), journal.load())

        assertTrue(journal.clear())
        File(folder.root, FileGameAudioFocusJournal.FILE_NAME + ".new")
            .writeText("MIMIR_GAME_AUDIO_FOCUS_V1\n$game\nboot-1\n42\n")
        assertEquals(GameAudioFocusJournalLoad.Loaded(record), journal.load())
        assertTrue(journal.clear())
        assertEquals(GameAudioFocusJournalLoad.Empty, journal.load())
    }

    @Test
    fun `the file journal quarantines a damaged file instead of deleting it`() {
        val file = File(folder.root, FileGameAudioFocusJournal.FILE_NAME)
        val journal = FileGameAudioFocusJournal(file)
        file.writeText("garbage")

        assertTrue(journal.quarantine())

        assertFalse(file.exists())
        assertEquals("garbage", File(folder.root, FileGameAudioFocusJournal.FILE_NAME + ".corrupt").readText())
        assertEquals(GameAudioFocusJournalLoad.Empty, journal.load())
    }

    @Test
    fun `a file that cannot be read is unreadable, not corrupt`() {
        // A directory where the journal file should be: reading it fails with an I/O error, which is not damage.
        val file = File(folder.root, FileGameAudioFocusJournal.FILE_NAME)
        assertTrue(file.mkdir())

        assertEquals(GameAudioFocusJournalLoad.Unreadable, FileGameAudioFocusJournal(file).load())
        assertTrue("never deleted", file.exists())
    }

    @Test
    fun `a boot or daemon start sees any journal spelling, including the staging file`() {
        val dir = folder.root
        assertFalse(gameAudioFocusJournalFilesPresent(dir))
        assertFalse(gameAudioFocusJournalFilesPresent(null))
        File(dir, FileGameAudioFocusJournal.FILE_NAME + ".new").writeText("x")
        assertTrue(gameAudioFocusJournalFilesPresent(dir))
        File(dir, FileGameAudioFocusJournal.FILE_NAME + ".new").delete()
        File(dir, FileGameAudioFocusJournal.FILE_NAME).writeText("garbage")
        assertTrue("a corrupt journal is still an obligation", gameAudioFocusJournalFilesPresent(dir))
    }

    // ---- who may call --------------------------------------------------------------------------

    @Test
    fun `only the approved Mimir application ids and the daemon itself may call the v11 methods`() {
        val packages = mapOf(
            10100 to listOf("com.gruning.mimir.rewrite"),
            10101 to listOf("com.gruning.mimir.other"),
            10102 to listOf("com.gruning.mimir.rewrite.fake"),
            10103 to listOf("com.example.shared", "com.gruning.mimir.rewrite"),
            10104 to emptyList(),
            10105 to listOf("com.gruning.mimir.rewrite.devlab"),
            10106 to listOf("com.gruning.mimir.rewrite.devlab2"),
            10107 to listOf("com.gruning.mimir.rewrite.devlab.extra"),
            10108 to listOf("com.gruning.mimir.devlab"),
            10109 to listOf("com.gruning.mimir.tweaker"),
        )
        val lookup: (Int) -> List<String> = { uid -> packages[uid] ?: error("unknown uid $uid") }
        fun admits(uid: Int, dev: Boolean) =
            GameAudioFocusCallerPolicy.admits(uid, ownUid = 10050, devVariantsAllowed = dev, packagesForUid = lookup)

        listOf(false, true).forEach { dev ->
            assertTrue("the product app, dev=$dev", admits(10100, dev))
            assertTrue("a shared uid that includes Mimir, dev=$dev", admits(10103, dev))
            assertTrue("the daemon itself", GameAudioFocusCallerPolicy.admits(10050, 10050, dev) { error("never asked") })
            assertFalse("a same-signed sibling, dev=$dev", admits(10101, dev))
            assertFalse("an exact match, not a prefix, dev=$dev", admits(10102, dev))
            assertFalse("no package at all, dev=$dev", admits(10104, dev))
            assertFalse("a devlab lookalike, dev=$dev", admits(10106, dev))
            assertFalse("no sub-package wildcard, dev=$dev", admits(10107, dev))
            assertFalse("not under the other root either, dev=$dev", admits(10108, dev))
            assertFalse("the Tweaker's own package name is not a client, dev=$dev", admits(10109, dev))
            assertFalse("a uid the lookup cannot resolve fails closed, dev=$dev", admits(10999, dev))
        }
        assertTrue("the devlab variant, where dev variants exist", admits(10105, dev = true))
        assertFalse("and refused where they do not", admits(10105, dev = false))
        assertEquals(setOf("com.gruning.mimir.rewrite"), GameAudioFocusCallerPolicy.approvedPackages(false))
        assertEquals(
            setOf("com.gruning.mimir.rewrite", "com.gruning.mimir.rewrite.devlab"),
            GameAudioFocusCallerPolicy.approvedPackages(true),
        )
    }

    // ---- the real AOSP reply shapes ---------------------------------------------------------------

    @Test
    fun `readback reads the AOSP 13 and 14 single-line, uid-plus-package and no-operations shapes`() {
        val ignore = TakeAudioFocusMode.IGNORE
        val notIgnored = TakeAudioFocusMode.NOT_IGNORED
        // Single line, with the optional suffixes.
        assertEquals(ignore, parseTakeAudioFocusMode("TAKE_AUDIO_FOCUS: ignore; rejectTime=+1s2ms ago"))
        assertEquals(notIgnored, parseTakeAudioFocusMode("TAKE_AUDIO_FOCUS: allow; time=+3m ago"))
        // A uid line plus a package line.
        assertEquals(ignore, parseTakeAudioFocusMode("Uid mode: TAKE_AUDIO_FOCUS: ignore\nTAKE_AUDIO_FOCUS: allow; time=+3m ago"))
        assertEquals(notIgnored, parseTakeAudioFocusMode("Uid mode: TAKE_AUDIO_FOCUS: default\nTAKE_AUDIO_FOCUS: default"))
    }

    @Test
    fun `a package ignore under an explicit non-ignore uid mode is a conflict and fails closed`() {
        assertNull(parseTakeAudioFocusMode("Uid mode: TAKE_AUDIO_FOCUS: allow\nTAKE_AUDIO_FOCUS: ignore"))
        assertEquals(
            TakeAudioFocusMode.NOT_IGNORED,
            parseTakeAudioFocusMode("Uid mode: TAKE_AUDIO_FOCUS: allow\nTAKE_AUDIO_FOCUS: default"),
        )
        assertEquals(
            TakeAudioFocusMode.IGNORE,
            parseTakeAudioFocusMode("Uid mode: TAKE_AUDIO_FOCUS: ignore\nTAKE_AUDIO_FOCUS: default"),
        )
    }

    @Test
    fun `no operations followed by the default mode reads as the default, and only that exact pair`() {
        assertEquals(
            TakeAudioFocusMode.NOT_IGNORED,
            parseTakeAudioFocusMode("No operations.\nDefault mode: allow"),
        )
        assertEquals(
            TakeAudioFocusMode.NOT_IGNORED,
            parseTakeAudioFocusMode("No operations.\nDefault mode: default"),
        )
        assertEquals(TakeAudioFocusMode.NOT_IGNORED, parseTakeAudioFocusMode("No operations."))
        // A default mode that is itself ignore, or one that stands alone, is not something to act on.
        assertNull(parseTakeAudioFocusMode("No operations.\nDefault mode: ignore"))
        assertNull(parseTakeAudioFocusMode("Default mode: allow"))
        assertNull(parseTakeAudioFocusMode("TAKE_AUDIO_FOCUS: allow\nDefault mode: allow"))
        assertNull(parseTakeAudioFocusMode("No operations.\nDefault mode: allow\nDefault mode: allow"))
    }

    @Test
    fun `garbage and unknown shapes still fail closed`() {
        listOf(
            "",
            "   \n  ",
            "Error: Unknown package: $game",
            "TAKE_AUDIO_FOCUS: deny",
            "TAKE_AUDIO_FOCUS: ignore\nsomething else",
            "No operations.\nPermission Denial: not allowed",
            "Uid mode: TAKE_AUDIO_FOCUS: wibble",
            "TAKE_AUDIO_FOCUS ignore",
        ).forEach { assertNull("[$it]", parseTakeAudioFocusMode(it)) }
    }

    // ---- fakes ------------------------------------------------------------------------------------

    private fun ok(output: String) = CheckedCommandResult(CheckedCommandStatus.CONFIRMED, output = output, exitCode = 0)

    private inner class Rig(
        val journal: GameAudioFocusJournal = MemoryJournal(),
    ) {
        val order = mutableListOf<String>()
        val control = FakeControl(order)
        val packages = FakePackages()
        var bootId: String? = "boot-1"
        val memory: MemoryJournal get() = journal as MemoryJournal
        val controller = GameAudioFocusController(
            journal = RecordingJournal(journal, order),
            control = control,
            packages = packages,
            bootIdentity = { bootId },
            elapsedRealtimeMs = { 10L },
            sleepMs = {},
        )
        val session = GameAudioFocusSession(controller, executeAsync = { it() })
    }

    private class RecordingJournal(
        private val delegate: GameAudioFocusJournal,
        private val order: MutableList<String>,
    ) : GameAudioFocusJournal by delegate {
        override fun persist(record: GameAudioFocusRecord): Boolean {
            order += "persist:${record.packageName}"
            return delegate.persist(record)
        }
    }

    private class MemoryJournal : GameAudioFocusJournal {
        var record: GameAudioFocusRecord? = null
        var persistFails = false
        var quarantineFails = false
        var loadOverride: GameAudioFocusJournalLoad? = null
        var cleared = 0
        var quarantined = 0
        override fun load(): GameAudioFocusJournalLoad = loadOverride
            ?: record?.let(GameAudioFocusJournalLoad::Loaded)
            ?: GameAudioFocusJournalLoad.Empty
        override fun persist(record: GameAudioFocusRecord): Boolean {
            if (persistFails) return false
            this.record = record
            return true
        }
        override fun clear(): Boolean {
            cleared += 1
            record = null
            loadOverride = null
            return true
        }
        override fun quarantine(): Boolean {
            if (quarantineFails) return false
            quarantined += 1
            record = null
            loadOverride = null
            return true
        }
    }

    private class FakeControl(private val order: MutableList<String>) : GameAudioFocusControlPort {
        override var available = true
        val commands = mutableListOf<String>()
        val reads = mutableListOf<String>()
        var readBusy = false
        private var modeHeld: String = "default"
        private var scripted: ((String) -> CheckedCommandResult)? = null

        fun script(block: (String) -> CheckedCommandResult) {
            scripted = block
        }

        /**
         * The readback goes through the transport's REAL frame decoder: whatever this fake would have answered is wrapped
         * in a genuine `MIMIR_TX_EXIT` frame, so a parser that reads bare text cannot pass.
         */
        override fun readChecked(command: String): CheckedReadResult {
            reads += command
            commands += command
            if (readBusy) return CheckedReadResult.Busy("deadline")
            val reply = answer(command)
            return when (reply.status) {
                CheckedCommandStatus.CONFIRMED -> {
                    val nonce = "nFake0001"
                    val body = reply.output.orEmpty()
                    val frame = "MIMIR_TX_EXIT:$nonce:0:${body.length}:$body\n"
                    PServerClient.decodeCheckedBody(frame, nonce)?.let(CheckedReadResult::Body)
                        ?: CheckedReadResult.Unreadable("frame did not decode")
                }
                CheckedCommandStatus.FAILED -> CheckedReadResult.Failed("nonzero")
                CheckedCommandStatus.AMBIGUOUS -> CheckedReadResult.Unreadable("ambiguous")
            }
        }

        override fun executeChecked(command: String): CheckedCommandResult {
            commands += command
            if (command.endsWith(" ignore")) order += "cmd:set-ignore"
            return answer(command)
        }

        private fun answer(command: String): CheckedCommandResult {
            if (command.endsWith(" ignore")) modeHeld = "ignore"
            if (command.endsWith(" default")) modeHeld = "default"
            scripted?.let { return it(command) }
            return when {
                command.endsWith(" ignore") -> {
                    modeHeld = "ignore"
                    CheckedCommandResult(CheckedCommandStatus.CONFIRMED, "", 0)
                }
                command.endsWith(" default") -> {
                    modeHeld = "default"
                    CheckedCommandResult(CheckedCommandStatus.CONFIRMED, "", 0)
                }
                else -> CheckedCommandResult(CheckedCommandStatus.CONFIRMED, "TAKE_AUDIO_FOCUS: $modeHeld", 0)
            }
        }
    }

    private class FakePackages : GameAudioFocusPackagePolicy {
        val refusals = mutableMapOf<String, GameAudioFocusAdmission.Refused>()
        var installed: Boolean? = true
        override fun admission(packageName: String): GameAudioFocusAdmission =
            refusals[packageName] ?: GameAudioFocusAdmission.Admitted
        override fun isInstalled(packageName: String): Boolean? = installed
    }

    private class FakeToken : ClientTokenHandle {
        var alive = true
        var unlinked = false
        var linked = false
        var linkFails = false
        private var onDeath: (() -> Unit)? = null
        override fun sameToken(other: ClientTokenHandle) = other === this
        override fun isAlive() = alive
        override fun linkToDeath(onDeath: () -> Unit): Boolean {
            if (linkFails) return false
            linked = true
            this.onDeath = onDeath
            return alive
        }
        override fun unlinkFromDeath() {
            unlinked = true
            onDeath = null
        }
        fun die() {
            alive = false
            onDeath?.invoke()
        }
    }
}
