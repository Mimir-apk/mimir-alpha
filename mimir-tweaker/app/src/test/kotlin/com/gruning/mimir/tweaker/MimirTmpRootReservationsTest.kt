package com.gruning.mimir.tweaker

import com.gruning.mimir.tweaker.mgde.MgdeAdmission
import com.gruning.mimir.tweaker.mgde.MgdeD7Pins
import com.gruning.mimir.tweaker.mgde.MgdeD7PublicationStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirTmpRootReservationsTest {
    @Test
    fun `reservations are exact distinct direct children without traversal`() {
        assertEquals(
            setOf(
                "/data/local/tmp/mimir-rct1",
                "/data/local/tmp/mimir-mgde1",
                "/data/local/tmp/mimir_restore_guardian_fence_v1",
            ),
            MimirTmpRootReservations.ALL,
        )
        assertEquals(3, MimirTmpRootReservations.ALL.size)
        MimirTmpRootReservations.ALL.forEach { root ->
            assertTrue(root, root.startsWith("/data/local/tmp/"))
            assertEquals(root, "/data/local/tmp", File(root).parent)
            assertFalse(root, root.endsWith('/'))
            assertFalse(root, root.split('/').any { it == "." || it == ".." })
        }
    }

    @Test
    fun `compiled owners retain their frozen roots and do not nest`() {
        assertEquals(
            MimirTmpRootReservations.RECEIPT_CHANNEL,
            GuardianReceiptChannelPostUnlockTopology.ROOT,
        )
        assertEquals(MimirTmpRootReservations.MGDE_PUBLICATION, MgdeD7PublicationStore.ROOT)
        val identity = GenerationFenceIdentity(
            bootIdentity = "boot",
            generation = 1,
            transactionId = "transaction",
            sessionId = "session",
        )
        assertEquals(
            MimirTmpRootReservations.TERMINAL_MUTATION_FENCE,
            GenerationTerminalMutationFence.directory(identity).substringBeforeLast('/'),
        )
        MimirTmpRootReservations.ALL.forEach { left ->
            MimirTmpRootReservations.ALL.filterNot { it == left }.forEach { right ->
                assertFalse("$left must not contain $right", right.startsWith("$left/"))
            }
        }
    }

    @Test
    fun `shared nonce cannot collide across receipt and mgde publication roots`() {
        val nonce = "a".repeat(32)
        val mgde = "${MimirTmpRootReservations.MGDE_PUBLICATION}/${MgdeAdmission.locatorFor(nonce)}"
        val receipt = "${MimirTmpRootReservations.RECEIPT_CHANNEL}/$nonce"

        assertNotEquals(mgde, receipt)
        assertFalse(mgde.startsWith("$receipt/"))
        assertFalse(receipt.startsWith("$mgde/"))
        val mgdeChildren = setOf(".stage", "record")
        val receiptChildren = GuardianReceiptChannelRecord.entries.map { it.fileName }.toSet()
        assertEquals(setOf("R", "T", "X"), receiptChildren)
        assertTrue(mgdeChildren.intersect(receiptChildren).isEmpty())
    }

    @Test
    fun `reservation registry grants no reducer or service authority`() {
        val sourceRoot = sourceRoot()
        val users = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { "MimirTmpRootReservations" in it.readText() }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath }
            .toSet()

        assertEquals(
            setOf(
                "MimirTmpRootReservations.kt",
                "mgde/MgdePublication.kt",
            ),
            users,
        )
        assertTrue(
            sourceRoot.resolve("MimirTmpRootReservations.kt").readText()
                .contains("registry of reservations, not permissions"),
        )
    }

    @Test
    fun `publication proof store remains quarantined from production consumers`() {
        val sourceRoot = sourceRoot()
        val users = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { "MgdeD7PublicationStore" in it.readText() }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath }
            .toSet()

        assertEquals(setOf("mgde/MgdePublication.kt"), users)
        val declaration = sourceRoot.resolve("mgde/MgdePublication.kt").readText()
        assertTrue(declaration.contains("Unwired no-replace publication proof model"))
        assertTrue(declaration.contains("stage-name unlink is not substitution-safe"))
        assertTrue(declaration.contains("cleanupResidue(taskNonce: String): MgdeCleanupResult = MgdeCleanupResult.Rejected"))
    }

    @Test
    fun `fixed root literals remain confined and D7 pins remain unchanged`() {
        val sourceRoot = sourceRoot()
        val literalOwners = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { "\"/data/local/tmp/mimir" in it.readText() }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath }
            .toSet()

        assertEquals(
            setOf(
                "GenerationTerminalMutationFence.kt",
                "GuardianReceiptChannel.kt",
                "MimirTmpRootReservations.kt",
            ),
            literalOwners,
        )
        assertEquals("8b377b4ae37590980de3e73e3758c814d04dba73", MgdeD7Pins.COMMIT)
        assertEquals(
            "dd13758c4455b65980deb29aa5e2eeff7828502f0c705d85e309df9e014f5700",
            MgdeD7Pins.MANIFEST_SHA256,
        )
    }

    private fun sourceRoot(): File = sequenceOf(
        File("src/main/kotlin/com/gruning/mimir/tweaker"),
        File("mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker"),
    ).firstOrNull(File::isDirectory) ?: error("Tweaker source root not found")
}
