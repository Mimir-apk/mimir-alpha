package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirSingleMainTaskTest {
    private val main = "com.gruning.mimir.rewrite.MainActivity"

    @Test
    fun `the home copy on screen removes the launcher-opened copy`() {
        val tasks = listOf(
            MimirAppTaskSnapshot(taskId = 188, baseClassName = main, visible = true, home = true),
            MimirAppTaskSnapshot(taskId = 179, baseClassName = main, visible = false),
        )
        assertEquals(listOf(179), staleMainTaskIds(currentTaskId = 188, tasks = tasks, mainActivityClassName = main))
    }

    @Test
    fun `a launcher-opened copy never removes the home copy`() {
        val tasks = listOf(
            MimirAppTaskSnapshot(179, main, visible = true),
            MimirAppTaskSnapshot(188, main, visible = false, home = true),
        )
        assertEquals(emptyList<Int>(), staleMainTaskIds(179, tasks, main))
    }

    @Test
    fun `a copy that is not on screen removes nothing`() {
        val tasks = listOf(
            MimirAppTaskSnapshot(188, main, visible = false, home = true),
            MimirAppTaskSnapshot(179, main, visible = false),
        )
        assertEquals(emptyList<Int>(), staleMainTaskIds(188, tasks, main))
    }

    @Test
    fun `tasks rooted at another Mimir activity are never removed`() {
        val tasks = listOf(
            MimirAppTaskSnapshot(10, main, true, home = true),
            MimirAppTaskSnapshot(11, "com.gruning.mimir.rewrite.CompanionSecondaryHomeActivity", false, home = true),
            MimirAppTaskSnapshot(12, "com.gruning.mimir.rewrite.CompanionReturnActivity", false),
            MimirAppTaskSnapshot(13, "com.gruning.mimir.rewrite.PrimaryTransferActivity", false),
            MimirAppTaskSnapshot(14, null, false),
        )
        assertEquals(emptyList<Int>(), staleMainTaskIds(10, tasks, main))
    }

    @Test
    fun `a copy still on screen is left for a later sweep`() {
        val tasks = listOf(MimirAppTaskSnapshot(1, main, true, home = true), MimirAppTaskSnapshot(2, main, true))
        assertEquals(emptyList<Int>(), staleMainTaskIds(1, tasks, main))
    }

    @Test
    fun `before API 32 visibility is unknown and the sweep still runs`() {
        val tasks = listOf(MimirAppTaskSnapshot(1, main, null, home = true), MimirAppTaskSnapshot(2, main, null))
        assertEquals(listOf(2), staleMainTaskIds(1, tasks, main))
    }

    @Test
    fun `a copy stopped while another is started is not Mimir leaving the screen`() {
        val copies = MimirStartedCopies()
        val launcherCopy = Any()
        val homeCopy = Any()
        copies.started(launcherCopy)
        copies.started(homeCopy)
        assertFalse(copies.stoppedLeavingScreen(launcherCopy))
        assertTrue(copies.anyStarted())
        assertTrue(copies.stoppedLeavingScreen(homeCopy))
        assertFalse(copies.anyStarted())
    }

    @Test
    fun `a configuration change still reads as leaving, as before`() {
        val copies = MimirStartedCopies()
        val old = Any()
        copies.started(old)
        assertTrue(copies.stoppedLeavingScreen(old))
        copies.started(Any())
        assertTrue(copies.anyStarted())
    }

    @Test
    fun `onStop leaves the app-wide edges to the copy still started`() {
        val source = File("src/main/kotlin/com/gruning/mimir/rewrite/MainActivity.kt").readText()
        val start = source.indexOf("override fun onStop()")
        val body = source.substring(start, source.indexOf("override fun onNewIntent", start))
        val gate = body.indexOf("val leavingScreen = MimirMainStartedCopies.stoppedLeavingScreen(this)")
        assertTrue("onStop must ask whether another copy is still started", gate >= 0)
        listOf(
            "MimirFrontendMusic.onAppBackgrounded()",
            "mButtonTopologyController?.setPrimaryHostForeground(false)",
            "GameLaunchCover.hostStopped()",
            "PrimaryActivityObservationState.onPrimaryStopped(",
        ).forEach { edge ->
            val at = body.indexOf(edge)
            val guard = body.lastIndexOf("if (", at)
            assertTrue(
                "$edge must sit under leavingScreen",
                at > gate && body.substring(guard, body.indexOf('\n', guard)).contains("leavingScreen"),
            )
        }
        val onStart = source.substring(source.indexOf("override fun onStart()"), source.indexOf("override fun onResume()"))
        assertTrue(onStart.contains("MimirMainStartedCopies.started(this)"))
        assertTrue(
            "a copy the sweep finishes beside a started one must not end the launch cover",
            source.contains(
                "if (isFinishing && !isChangingConfigurations && !MimirMainStartedCopies.anyStarted()) GameLaunchCover.teardown()",
            ),
        )
    }
}
