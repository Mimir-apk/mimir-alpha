package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * picking the music folder is a deliberate start of Your music, keyed to THAT pick and not to a timer. The scan that
 * follows can outlast any window, so the start is marked when the plan flips to the picked folder.
 */
class FolderPickStartTest {
    private val picked = "file:///storage/emulated/0/Music/Mimir"
    private val other = "file:///storage/emulated/0/Music/Old"

    @Test
    fun `the plan playing the picked folder is the start, once, and another folder's plan leaves the pick waiting`() {
        val pick = PickedFolder()
        assertFalse("no pick, no start", pick.playedNow(picked))
        pick.pick(picked)
        assertFalse("the old folder is still what plays while the scan runs", pick.playedNow(other))
        assertTrue("the plan has flipped to the picked folder", pick.playedNow(picked))
        assertFalse("used up: a later flip back to it is not a start", pick.playedNow(picked))
    }

    @Test
    fun `a new pick replaces the old one, and picking the same folder again is a new start`() {
        val pick = PickedFolder()
        pick.pick(other)
        pick.pick(picked)
        assertFalse("the replaced pick is gone", pick.playedNow(other))
        assertTrue(pick.playedNow(picked))
        pick.pick(picked)
        assertTrue("a second pick of the same folder starts again", pick.playedNow(picked))
    }

    @Test
    fun `the start waits for the plan however long the scan takes, because no clock is involved`() {
        // The only clock in a start is DeliberateStart's window, which opens when the plan flips and the start is marked, never at the pick.
        val control = source("MimirMusicControl.kt")
        val picked = control.substringAfter("fun onFolderPicked(context: Context, tree: String) {").substringBefore("/** The engine's plan is now the folder")
        assertTrue(picked.contains("pickedFolder.pick(tree)"))
        assertFalse(picked.contains("uptimeMillis") || picked.contains("deliberate.mark"))
        val flip = control.substringAfter("internal fun onPlanIsFolder(tree: String) {").substringBefore("private val pickedFolder")
        assertTrue("the pick plays: it ends the player's own pause and another app's hold, as choosing a source does", flip.contains("if (pickedFolder.playedNow(tree)) startDeliberately(\"folder chosen\", MusicPausePolicy::onFolderChosen)"))
        assertFalse("not the plain start, which leaves the player's own pause standing", flip.contains("onDeliberateStart"))
    }

    @Test
    fun `every pick waits for its plan, and the engine reports every folder plan before it can return early`() {
        val control = source("MimirMusicControl.kt")
        val pick = control.substringAfter("fun onFolderPicked(context: Context, tree: String) {").substringBefore("/** The engine's plan is now the folder")
        assertTrue("whatever the source was, the pick waits for the plan", pick.contains("pickedFolder.pick(tree)") && !pick.contains("if (AudioPreferences.musicSource.value == MimirMusicSource.CUSTOM) pickedFolder.pick(tree)"))
        val engine = source("MimirFrontendMusic.kt")
        val apply = engine.substringAfter("private fun applyPlan(next: FrontendMusicPlan) {").substringBefore("plan = next")
        val report = apply.indexOf("if (next is FrontendMusicPlan.Folder) MimirMusicControl.onPlanIsFolder(next.tree)")
        assertTrue(report >= 0)
        assertTrue("before the equal-plan return", report < apply.indexOf("if (next == plan) return"))
        assertTrue("before the same-tracks return, so picking the folder that already plays still counts", report < apply.indexOf("if (next.playsTheSameAs(plan))"))
        assertEquals("one report site", 1, Regex("""onPlanIsFolder\(""").findAll(engine).count())
    }


    @Test
    fun `picking the folder ends the player's own pause and another app's hold, and nothing that is not theirs to end`() {
        val held = MusicPauseState(user = true, otherApp = OtherAppHold.PERMANENT)
        val played = MusicPausePolicy.onFolderChosen(held)
        assertFalse("the pause the player pressed before the pick", played.user)
        assertEquals("another app's standing hold", OtherAppHold.NONE, played.otherApp)
        assertNull("so nothing holds it", played.reason)
        assertFalse(played.paused)
        val stays = MusicPauseState(user = true, headphones = true, screenOff = true, schedule = true, call = true, otherApp = OtherAppHold.TRANSIENT)
        val after = MusicPausePolicy.onFolderChosen(stays)
        assertTrue("the cable", after.headphones)
        assertTrue("the screen", after.screenOff)
        assertTrue("the music hours", after.schedule)
        assertTrue("a call", after.call)
        assertFalse(after.user)
        assertEquals(OtherAppHold.NONE, after.otherApp)
        assertEquals("it is the same clearing choosing the source makes", MusicPausePolicy.onSourceChosen(stays), after)
    }

    @Test
    fun `the plan flipping to the picked folder hands the control the clearing transition, which is what ends the pause`() {
        val pick = PickedFolder()
        pick.pick("file:///storage/emulated/0/Music/Mimir")
        assertTrue(pick.playedNow("file:///storage/emulated/0/Music/Mimir"))
        val control = source("MimirMusicControl.kt")
        val flip = control.substringAfter("internal fun onPlanIsFolder(tree: String) {").substringBefore("private val pickedFolder")
        assertTrue("the start is handed exactly the clearing transition", flip.contains("startDeliberately(\"folder chosen\", MusicPausePolicy::onFolderChosen)"))
        assertFalse(flip.contains("onDeliberateStart"))
        // startDeliberately applies what it is handed to the pause state, under the lock, before it reconciles.
        val start = control.substringAfter("private fun startDeliberately(why: String, ends: (MusicPauseState) -> MusicPauseState) {").substringBefore("/**")
        assertTrue(start.indexOf("mutatePause(why, ends)") in 0 until start.indexOf("reconcile()"))
        // And applied to the state a pressed pause and another app's hold leave, that transition leaves nothing holding the folder.
        val afterPick = MusicPausePolicy.onFolderChosen(MusicPauseState(user = true, otherApp = OtherAppHold.PERMANENT))
        assertNull(afterPick.reason)
        assertTrue("so the pick takes focus: nothing stops it asking", MusicPausePolicy.mayRequestFocus(afterPick, gameInFront = false))
    }

    @Test
    fun `picking a folder with Mimir's music as the source switches it to Your music, and then the pick plays`() {
        val control = source("MimirMusicControl.kt")
        val pick = control.substringAfter("fun onFolderPicked(context: Context, tree: String) {").substringBefore("/** The engine's plan is now the folder")
        // The same setter Settings' source choice calls, with the same value Your music is stored as.
        assertTrue(pick.contains("AudioPreferences.setMusicSource(context, MimirMusicSource.CUSTOM)"))
        // The library has the new folder BEFORE the source flips: flipped first, the plan would be the OLD folder for a moment, started passively.
        assertTrue(
            "recorded, then the folder is given to the library, then the source",
            pick.indexOf("pickedFolder.pick(tree)") < pick.indexOf("MimirMusicLibrary.setTree(context, tree)") &&
                pick.indexOf("MimirMusicLibrary.setTree(context, tree)") < pick.indexOf("AudioPreferences.setMusicSource("),
        )
        assertTrue("only when Mimir's music is the source: Your music is left as it is", pick.contains("if (AudioPreferences.musicSource.value != MimirMusicSource.CUSTOM)"))
        assertTrue("the pick is recorded first, so no plan flip can precede it", pick.indexOf("pickedFolder.pick(tree)") < pick.indexOf("AudioPreferences.setMusicSource("))
        assertFalse("the start is not made here: it waits for the plan to be the folder", pick.contains("startDeliberately") || pick.contains("deliberate.mark"))
        // The plan flipping to that folder is the start, with the clearing that choosing a source makes.
        val pending = PickedFolder().apply { pick(picked) }
        assertTrue(pending.playedNow(picked))
        val held = MusicPauseState(user = true, otherApp = OtherAppHold.PERMANENT)
        val played = MusicPausePolicy.onFolderChosen(held)
        assertFalse("the pause the player pressed", played.user)
        assertEquals("the other app's hold", OtherAppHold.NONE, played.otherApp)
        assertEquals("it plays: the request is made over another player, as a deliberate start", MusicPausePolicy.FocusDecision.REQUEST, MusicPausePolicy.focusDecision(played, gameInFront = false, deliberate = true, otherPlayerPlaying = true))
        // And Settings reaches it from both lanes before the pick reaches the library.
        assertTrue(source("MimirMusicControl.kt").contains("internal fun onPlanIsFolder(tree: String)"))
    }

    private fun source(name: String): String =
        File("src/main/kotlin/com/gruning/mimir/rewrite/core/audio/$name").readText()
}
