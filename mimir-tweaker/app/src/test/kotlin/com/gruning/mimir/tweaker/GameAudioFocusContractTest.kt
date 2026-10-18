package com.gruning.mimir.tweaker

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The v11 surface cannot carry a command: it takes one package and one Binder, the daemon owns every
 * command string, and an older daemon reads as "unsupported".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GameAudioFocusContractTest {

    @Test
    fun `the hold reply carries the held package and no debt`() {
        val bundle = gameAudioFocusHoldBundle(11, "org.game.x", GameAudioFocusHoldResult.Held("org.game.x"))

        assertEquals(11, bundle.getInt(TweakerGameAudioFocusContract.KEY_API_VERSION))
        assertEquals(TweakerGameAudioFocusContract.OUTCOME_HELD, bundle.getString(TweakerGameAudioFocusContract.KEY_OUTCOME))
        assertEquals("org.game.x", bundle.getString(TweakerGameAudioFocusContract.KEY_PACKAGE_NAME))
        assertFalse(bundle.getBoolean(TweakerGameAudioFocusContract.KEY_RELEASE_DEBT))
    }

    @Test
    fun `a refusal carries its code and never echoes a malformed package`() {
        val refused = GameAudioFocusHoldResult.Refused(
            TweakerGameAudioFocusContract.REFUSAL_INVALID_PACKAGE,
            "not a package name",
            releaseDebt = true,
        )

        val bad = gameAudioFocusHoldBundle(11, "x; reboot", refused)
        assertEquals(TweakerGameAudioFocusContract.OUTCOME_REFUSED, bad.getString(TweakerGameAudioFocusContract.KEY_OUTCOME))
        assertEquals(
            TweakerGameAudioFocusContract.REFUSAL_INVALID_PACKAGE,
            bad.getString(TweakerGameAudioFocusContract.KEY_REFUSAL_CODE),
        )
        assertNull(bad.getString(TweakerGameAudioFocusContract.KEY_PACKAGE_NAME))
        assertTrue(bad.getBoolean(TweakerGameAudioFocusContract.KEY_RELEASE_DEBT))

        val good = gameAudioFocusHoldBundle(11, "org.game.x", refused)
        assertEquals("org.game.x", good.getString(TweakerGameAudioFocusContract.KEY_PACKAGE_NAME))
    }

    @Test
    fun `release and query replies use the contract's outcomes`() {
        assertEquals(
            TweakerGameAudioFocusContract.OUTCOME_RELEASED,
            gameAudioFocusReleaseBundle(11, GameAudioFocusReleaseResult.Released("org.game.x"))
                .getString(TweakerGameAudioFocusContract.KEY_OUTCOME),
        )
        assertEquals(
            TweakerGameAudioFocusContract.OUTCOME_NOTHING_HELD,
            gameAudioFocusReleaseBundle(11, GameAudioFocusReleaseResult.NothingHeld)
                .getString(TweakerGameAudioFocusContract.KEY_OUTCOME),
        )
        val retained = gameAudioFocusReleaseBundle(11, GameAudioFocusReleaseResult.Retained("org.game.x", "RELEASE_NOT_OBSERVED"))
        assertEquals(TweakerGameAudioFocusContract.OUTCOME_RETAINED, retained.getString(TweakerGameAudioFocusContract.KEY_OUTCOME))
        assertEquals("RELEASE_NOT_OBSERVED", retained.getString(TweakerGameAudioFocusContract.KEY_REFUSAL_CODE))
        assertEquals(
            TweakerGameAudioFocusContract.OUTCOME_NOT_HELD,
            gameAudioFocusQueryBundle(11, GameAudioFocusQueryResult.NotHeld).getString(TweakerGameAudioFocusContract.KEY_OUTCOME),
        )
        val held = gameAudioFocusQueryBundle(11, GameAudioFocusQueryResult.Held("org.game.x"))
        assertEquals(TweakerGameAudioFocusContract.OUTCOME_HELD, held.getString(TweakerGameAudioFocusContract.KEY_OUTCOME))
        assertEquals("org.game.x", held.getString(TweakerGameAudioFocusContract.KEY_PACKAGE_NAME))
    }

    @Test
    fun `a query that could not be answered is UNAVAILABLE and never NOT_HELD`() {
        val unavailable = gameAudioFocusQueryBundle(11, GameAudioFocusQueryResult.Unavailable("SERVICE_BUSY"))

        assertEquals(TweakerGameAudioFocusContract.OUTCOME_UNAVAILABLE, unavailable.getString(TweakerGameAudioFocusContract.KEY_OUTCOME))
        assertEquals("SERVICE_BUSY", unavailable.getString(TweakerGameAudioFocusContract.KEY_REFUSAL_CODE))
        assertNull(unavailable.getString(TweakerGameAudioFocusContract.KEY_PACKAGE_NAME))
    }

    @Test
    fun `the advertised api version is the one the family needs`() {
        assertEquals(TweakerGameAudioFocusContract.MIN_API_VERSION, MimirTweakerService.API_VERSION)
        assertTrue(MimirTweakerService.API_VERSION >= TweakerGameAudioFocusContract.MIN_API_VERSION)
    }

    @Test
    fun `no privileged command is built from anything but the three templates and one validated package`() {
        val code = source("GameAudioFocusHold.kt").stripComments()
        // Every executeChecked argument in the file is a value built by GameAudioFocusCommands.
        val executed = Regex("""(?<!fun )executeChecked\(([^)]*)\)""").findAll(code).map { it.groupValues[1].trim() }.toList()
        assertTrue(executed.isNotEmpty())
        // Never the read template: a readback goes through readChecked, which runs under the read deadline.
        val allowed = setOf("command", "holdCommand", "releaseCommand")
        assertTrue("unexpected executeChecked arguments: $executed", executed.all { it in allowed })
        // The bounded readback is the only other privileged call, and it too takes only the fixed read template.
        val reads = Regex("""(?<!fun )readChecked\(([^)]*)\)""").findAll(code).map { it.groupValues[1].trim() }.toList()
        assertTrue(reads.isNotEmpty())
        assertTrue("unexpected readChecked arguments: $reads", reads.all { it == "readCommand" || it == "command" })
        // The only `cmd ` literal is the shared prefix inside GameAudioFocusCommands.command().
        assertEquals(1, Regex("""cmd appops """).findAll(code).count())
        // The service forwards a package and a Binder, never a command, to the controller.
        val service = source("MimirTweakerService.kt").stripComments()
        val v11 = service.substringAfter("override fun holdGameAudioFocusV11").substringBefore("override fun queryGameAudioFocusV11")
        assertFalse("the service must not build or run a command for v11", v11.contains("executeChecked") || v11.contains("cmd "))
    }

    @Test
    fun `every v11 method requires the Mimir caller, and the boot receiver sees the audio journal`() {
        val service = source("MimirTweakerService.kt").stripComments()
        listOf("holdGameAudioFocusV11", "releaseGameAudioFocusV11", "queryGameAudioFocusV11").forEach { method ->
            val declaration = service.substringAfter("override fun $method").substringBefore("{").trim()
            assertTrue("$method must use guardedMimirOnly: $declaration", declaration.endsWith("= guardedMimirOnly"))
        }
        assertTrue(source("TweakerBootReceiver.kt").contains("gameAudioFocusJournalFilesPresent"))
    }

    @Test
    fun `the hold is reachable in every posture and never touches the Guardian, the journal or a lease`() {
        val code = source("GameAudioFocusHold.kt").stripComments()
        listOf(
            "TransactionCoordinator", "TransactionJournal", "GuardianFencedHardwareCommandPort",
            "RemoteGuardianProcessAuthority", "HardwareSessionSupervisor", "OwnerHardLeasePolicy",
            "HARDWARE_WRITES_COMPILED", "Shizuku",
        ).forEach { forbidden ->
            assertFalse("GameAudioFocusHold.kt must not mention $forbidden", code.contains(forbidden))
        }
    }

    @Test
    fun `the v11 methods are appended after every older method so no transaction code moves`() {
        val aidl = sequenceOf(
            File("../contract/src/main/aidl/com/gruning/mimir/tweaker/IMimirTweaker.aidl"),
            File("mimir-tweaker/contract/src/main/aidl/com/gruning/mimir/tweaker/IMimirTweaker.aidl"),
        ).first(File::isFile).readText()
        val names = Regex("""Bundle\s+(\w+)\(""").findAll(aidl).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("holdGameAudioFocusV11", "releaseGameAudioFocusV11", "queryGameAudioFocusV11"),
            names.takeLast(3),
        )
        assertEquals("queryDiscoveredCeilingV10", names[names.size - 4])
        // The only argument the hold takes is the Binder and the package.
        assertTrue(aidl.contains("Bundle holdGameAudioFocusV11(IBinder clientToken, String packageName);"))
        assertTrue(aidl.contains("Bundle releaseGameAudioFocusV11();"))
    }

    private fun String.stripComments(): String = this
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("(?m)//.*$"), "")

    private fun source(fileName: String): String = sequenceOf(
        File("src/main/kotlin/com/gruning/mimir/tweaker/$fileName"),
        File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/$fileName"),
    ).firstOrNull(File::isFile)?.readText() ?: error("Tweaker source $fileName not found")
}
