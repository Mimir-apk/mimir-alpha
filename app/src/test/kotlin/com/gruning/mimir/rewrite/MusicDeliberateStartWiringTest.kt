package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Mimir takes over" : the player's own start of Mimir's music asks for audio focus, so the app that was playing
 * pauses itself; a passive start never does. The control decides; this pins WHICH acts of the player tell it, in the Settings coordinator
 * (the panel's play, the notification and the media keys go through `MimirMusicControl.playPause`, which marks it itself). Turning Music on and
 * choosing the source are both a play (they also end the player's own earlier pause); no app code calls the plain `onDeliberateStart()` any more
 * (the folder pick reaches it inside the control).
 */
class MusicDeliberateStartWiringTest {
    private val call = "MimirMusicControl.onDeliberateStart()"
    private val turnedOn = "MimirMusicControl.onMusicTurnedOn()"
    private val chosen = "MimirMusicControl.onSourceChosen()"

    @Test
    fun `turning Music on plays it, choosing the source plays it too, turning Music off does neither`() {
        val source = file("MimirSettingsCoordinator.kt").readText()
        val toggle = source.substringAfter("MusicRowCommand.ToggleMusic -> {").substringBefore("MusicRowCommand.OpenSourcePicker")
        assertTrue("only a switch to On plays the music (and ends the player's pause)", toggle.contains("if (turnOn) $turnedOn"))
        assertFalse("turning Music on is the play, not the plain start", toggle.contains(call))
        assertTrue(
            "the preference is written first, then the control is told",
            toggle.indexOf("AudioPreferences.setMusicEnabled(context, turnOn)") in 0 until toggle.indexOf(turnedOn),
        )
        val choose = source.substringAfter("val chooseMusicSource: (MusicSourceValue) -> Unit = { value ->").substringBefore("val chooseMusicOrder")
        assertTrue("choosing the source is a play, so it ends the player's own pause as Music on does", choose.contains(chosen))
        assertFalse("and not the plain start, which leaves that pause standing", choose.contains(call))
        assertTrue(choose.indexOf("AudioPreferences.setMusicSource(") in 0 until choose.indexOf(chosen))
        for (other in listOf("chooseMusicOrder", "chooseMusicHours", "chooseMusicLaunchSound")) {
            val body = source.substringAfter("val $other").substringBefore("\n    }\n")
            assertFalse("$other is not a start", listOf("onDeliberateStart", "onSourceChosen", "onMusicTurnedOn").any(body::contains))
        }
    }

    @Test
    fun `no passive path tells the control a start was deliberate`() {
        // A cold start, the return to Mimir, screen-on, a rescan and a theme change all reach the music without the player starting it.
        val callers = File(appSources()).walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains(call) }
            .map { it.name }
            .toList()
        assertEquals("the plain start has no app caller left: the folder pick reaches it inside the control", emptyList<String>(), callers)
        for (play in listOf(turnedOn, chosen)) {
            val playCallers = File(appSources()).walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.readText().contains(play) }
                .map { it.name }
                .toList()
            assertEquals("$play is told from the Settings coordinator alone", listOf("MimirSettingsCoordinator.kt"), playCallers)
            assertEquals(1, Regex(Regex.escape(play)).findAll(file("MimirSettingsCoordinator.kt").readText()).count())
        }
    }

    @Test
    fun `both ways of picking the music folder tell the control before the pick reaches the library`() {
        val source = file("MimirSettingsCoordinator.kt").readText()
        // The system tree picker and Mimir's own folder browser: each marks the pick first, with the very string the library is given.
        for (uri in listOf("treeUri.toString()", "Uri.fromFile(File(chosen)).toString()")) {
            val pick = "MimirMusicControl.onFolderPicked(context, $uri)"
            assertEquals("$uri is handed to the control once", 1, Regex(Regex.escape(pick)).findAll(source).count())
        }
        // The control gives the library the folder, in its own order (pick, folder, source): the coordinator does not set the tree itself.
        assertEquals("and there is no third way, and no direct set", 0, Regex("""MimirMusicLibrary\.setTree\(""").findAll(source).count())
        val callers = File(appSources()).walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("MimirMusicControl.onFolderPicked(") }
            .map { it.name }
            .toList()
        assertEquals(listOf("MimirSettingsCoordinator.kt"), callers)
    }

    private fun appSources(): String =
        listOf("app/src/main/kotlin/com/gruning/mimir/rewrite", "src/main/kotlin/com/gruning/mimir/rewrite").first { File(it).isDirectory }

    private fun file(name: String): File = File("${appSources()}/$name")
}
