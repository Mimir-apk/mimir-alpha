package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.feature.library.NativeShelfRow
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A native hide shatters its card: a package the player hides leaves the shelf when they
 * confirm, and the rescan the write asks for settles the account. What has to hold is that nothing shows it again in the
 * meantime, that the scan that judges it is the one that ran AFTER the write, and that nothing outlives that scan.
 */
class NativeShelfOptimisticHideTest {

    private fun row(packageName: String, isGame: Boolean = true) = NativeShelfRow(
        packageName = packageName,
        label = packageName.substringAfterLast('.'),
        launchActivity = null,
        isGame = isGame,
    )

    private val snapshot = NativeShelfSnapshot(
        games = listOf(row("com.a"), row("com.b"), row("com.c")),
        apps = listOf(row("com.x", isGame = false), row("com.y", isGame = false)),
    )

    @Test
    fun `a hidden package is taken out of the games and the apps, in the order the rest were in`() {
        val hides = NativeShelfOptimisticHides().hiding("com.b", revision = 4).hiding("com.x", revision = 4)

        val shown = snapshot.withoutOptimisticHides(hides)

        assertEquals(listOf("com.a", "com.c"), shown.games.map { it.packageName })
        assertEquals(listOf("com.y"), shown.apps.map { it.packageName })
    }

    @Test
    fun `the add candidates are the scan's to decide, so a hide leaves them alone`() {
        val withCandidates = snapshot.copy(addToGames = emptyList(), addToApps = emptyList())
        val shown = withCandidates.withoutOptimisticHides(NativeShelfOptimisticHides().hiding("com.b", 4))
        assertEquals(withCandidates.addToGames, shown.addToGames)
        assertEquals(withCandidates.addToApps, shown.addToApps)
    }

    @Test
    fun `nothing to take out is the same snapshot, so a shelf that is not being hidden from sees no change`() {
        assertSame(snapshot, snapshot.withoutOptimisticHides(NativeShelfOptimisticHides()))
        assertSame(
            "a hide of a package the scan never listed changes nothing",
            snapshot,
            snapshot.withoutOptimisticHides(NativeShelfOptimisticHides().hiding("com.gone", 4)),
        )
    }

    @Test
    fun `a scan that ran before the write cannot settle it, and the scan that ran after it does`() {
        // The player hides at shelf revision 3; the write asks for revision 4.
        val hides = NativeShelfOptimisticHides().hiding("com.b", revision = 4)

        assertTrue("a scan already under way (revision 3) cannot have seen the write", "com.b" in hides.settledBy(scanRevision = 3))
        assertTrue("nor an older one", "com.b" in hides.settledBy(scanRevision = 0))
        assertFalse("the scan the write asked for is the truth", "com.b" in hides.settledBy(scanRevision = 4))
        assertFalse("and so is any later one", "com.b" in hides.settledBy(scanRevision = 9))
    }

    @Test
    fun `a write that did not take shows the package again once the scan has judged it`() {
        val hides = NativeShelfOptimisticHides().hiding("com.b", revision = 4)
        // The scan for revision 4 still lists com.b: the exclusion did not land. The shelf tells the truth.
        val unjudged = hides.settledBy(scanRevision = 4)
        val shown = snapshot.withoutOptimisticHides(unjudged)
        assertTrue(unjudged.isEmpty)
        assertEquals(listOf("com.a", "com.b", "com.c"), shown.games.map { it.packageName })
    }

    @Test
    fun `two hides in a row are each judged by their own scan`() {
        val hides = NativeShelfOptimisticHides().hiding("com.a", revision = 4).hiding("com.b", revision = 5)

        val afterFirst = hides.settledBy(scanRevision = 4)
        assertFalse("com.a" in afterFirst)
        assertTrue("com.b" in afterFirst)
        assertEquals(
            "the first one is back in the truth the scan gave; only the second is still filtered",
            listOf("com.a", "com.c"),
            snapshot.withoutOptimisticHides(afterFirst).games.map { it.packageName },
        )
        assertTrue(hides.settledBy(scanRevision = 5).isEmpty)
    }

    @Test
    fun `settling with nothing pending is the same object`() {
        val none = NativeShelfOptimisticHides()
        assertSame(none, none.settledBy(scanRevision = 7))
        val unsettled = none.hiding("com.b", revision = 9)
        assertSame(unsettled, unsettled.settledBy(scanRevision = 3))
    }

    // ============================================================================================ how the coordinator holds it

    private fun coordinatorSource(): String {
        val file = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/MimirNativeShelfCoordinator.kt"),
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirNativeShelfCoordinator.kt"),
        ).first(File::isFile)
        return file.readText()
    }

    @Test
    fun `the coordinator applies the hides to the one snapshot everything downstream reads, and tags every scan with its revision`() {
        val code = coordinatorSource()
        assertTrue(code.contains("val loadState = remember(scannedState, optimisticHides)"))
        assertTrue(code.contains("scannedState.snapshot.withoutOptimisticHides(unjudged)"))
        assertTrue("the scan is tagged with the revision it ran for", code.contains("revision = scanRevision,"))
        assertTrue(code.contains("val snapshot = loadState.snapshot"))
        assertTrue("the settled entries are dropped when a scan lands", code.contains("optimisticHides = optimisticHides.settledBy(scannedState.revision)"))
    }

    @Test
    fun `the one write marks the package hidden after the refresh it asks for has been counted`() {
        val code = coordinatorSource()
        val confirm = code.substringAfter("val confirmHidePackage: () -> Unit = remember(context)").substringBefore("LaunchedEffect(launchReturnRequest")
        val write = confirm.indexOf("setNativeShelfExcluded(context, packageName, excluded = true)")
        val refresh = confirm.indexOf("refreshNow()")
        val mark = confirm.indexOf("optimisticHides = optimisticHides.hiding(packageName, revision)")
        assertTrue("write, then the refresh that bumps the revision, then the mark that records it", write in 0 until refresh && refresh < mark)
    }
}
