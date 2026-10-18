package com.gruning.mimir.rewrite.feature.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-app secret entry's callers hold the secret only in their OWN state. The owner object they hand the keyboard
 * reads and writes that state at call time, and nothing a composition keeps (an effect block, a remembered updater, the
 * owner's writer) may capture a String of the secret: not the typed draft, and not the saved key (SteamGridDB's writer
 * used to close over `currentKey`, and a `SideEffect` then copied it into a holder).
 */
class SecretEntryCallersRetentionPinTest {

    private fun source(vararg candidates: String): String = candidates.map(::File).first { it.isFile }.readText()
        .replace(Regex("/[*].*?[*]/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//.*"), "")

    private val steamGridDb = source(
        "src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/SteamGridDbKeyEditModal.kt",
        "feature-settings/src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/SteamGridDbKeyEditModal.kt",
    )
    private val retro = source(
        "src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/RetroAchievementsKeyEditModal.kt",
        "feature-settings/src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/RetroAchievementsKeyEditModal.kt",
    )
    private val artwork = source(
        "../app/src/main/kotlin/com/gruning/mimir/rewrite/ArtworkConnectOverlay.kt",
        "app/src/main/kotlin/com/gruning/mimir/rewrite/ArtworkConnectOverlay.kt",
    )

    /** The brace-balanced block that follows each use of [keyword] with a lambda. */
    private fun blocksAfter(text: String, keyword: String): List<String> {
        val blocks = mutableListOf<String>()
        var from = text.indexOf(keyword)
        while (from >= 0) {
            val open = text.indexOf('{', from)
            if (open >= 0) {
                var depth = 0
                var i = open
                while (i < text.length) {
                    if (text[i] == '{') depth++
                    if (text[i] == '}') {
                        depth--
                        if (depth == 0) break
                    }
                    i++
                }
                blocks += text.substring(open, minOf(i + 1, text.length))
            }
            from = text.indexOf(keyword, from + keyword.length)
        }
        return blocks
    }

    @Test
    fun theSteamGridDbWriterReadsLiveOwnerStateAndCapturesNoKey() {
        val owner = steamGridDb.substringAfter("rememberMimirSecretFieldValueOwner(")
            .substringBefore("MimirSecretFieldKeyboard(")
        assertTrue("the owner block was found", owner.contains("write ="))
        assertFalse("the writer must not close over the saved key String", owner.contains("currentKey"))
        assertFalse("nor over the draft String", Regex("\\bdraft\\b").containsMatchIn(owner))
        assertFalse("nor over the trimmed key", owner.contains("trimmed"))
        assertTrue(
            "the first-edit rule runs on the live draft the writer reads",
            owner.contains("steamGridDbFirstEditOfSavedKey(draftValue.text, draftValue, edited)"),
        )
    }

    @Test
    fun noEffectOrUpdaterInTheThreeCallersReferencesTheKey() {
        val keyNames = listOf("currentKey", "apiKey", "draft", "trimmed")
        for ((name, text) in listOf("SteamGridDB" to steamGridDb, "RetroAchievements" to retro, "ArtworkConnect" to artwork)) {
            for (keyword in listOf("SideEffect", "LaunchedEffect", "DisposableEffect", "rememberUpdatedState", "derivedStateOf")) {
                for (block in blocksAfter(text, keyword)) {
                    for (key in keyNames) {
                        assertFalse(
                            "$name: a $keyword block must not reference `$key`: $block",
                            Regex("\\b$key\\b").containsMatchIn(block),
                        )
                    }
                }
            }
        }
        assertFalse("no holder copy of the saved key", steamGridDb.contains("savedKeyHolder"))
        assertFalse(steamGridDb.contains("SideEffect"))
    }

    @Test
    fun theEffectBlockScannerFindsABlockThatCapturesTheKey() {
        val before = "SideEffect { holder.value = currentKey }\nLaunchedEffect(Unit) { other() }"
        val side = blocksAfter(before, "SideEffect")
        assertTrue(side.size == 1 && side[0].contains("currentKey"))
        assertTrue(blocksAfter(before, "LaunchedEffect").single().contains("other"))
        assertTrue(blocksAfter("nothing here", "SideEffect").isEmpty())
    }

    @Test
    fun theSteamGridDbDoneReadsTheDraftAtCallTime() {
        val done = steamGridDb.substringAfter("MimirSecretFieldKeyboard(").substringBefore("controller = controller")
        assertTrue(done.contains("onDone = { onSave(draftValue.text.trim()) }"))
        assertFalse(done.contains("trimmed"))
    }

    @Test
    fun noCallerHandsTheKeyboardAString() {
        val call = retro.substringAfter("MimirSecretFieldKeyboard(").substringBefore("controllerLegendAction")
        assertFalse("the secret keyboard call passes no value", call.contains("value ="))
        assertTrue(call.contains("owner = secretOwner"))
        for (text in listOf(steamGridDb, retro, artwork)) {
            assertFalse(
                "a caller must not pass the keyboard a text provider",
                text.contains("secretRevealText") || text.contains("revealedText"),
            )
        }
    }
}
