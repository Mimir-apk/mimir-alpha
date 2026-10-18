package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.feature.library.TomeId
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Add to Tome confirms the copy.
 * Nothing leaves Editor's Choice any more, so the old moved-to wording would be false.
 */
class HomeTomeLooseLaneFeedbackTest {

    private val cabinet = TomeId.Custom(UUID.nameUUIDFromBytes("Cabinet".toByteArray()))
    private val other = TomeId.Custom(UUID.nameUUIDFromBytes("Other".toByteArray()))

    private fun member(id: String): FavoriteMemberId = FavoriteMemberId.Emulated(GameId(id))

    private fun tome(id: TomeId.Custom, label: String, vararg members: FavoriteMemberId) =
        CustomHomeTome(id = id, label = label, members = members.toList())

    private fun document(vararg tomes: CustomHomeTome) = HomeTomeDocument(customTomes = tomes.toList())

    @Test
    fun `adding a game to a Tome says it was added`() {
        assertEquals(
            "Added to Cabinet",
            homeTomeAddedMessage(
                before = document(tome(cabinet, "Cabinet")),
                after = document(tome(cabinet, "Cabinet", member("chrono"))),
                tomeId = cabinet,
                member = member("chrono"),
            ),
        )
    }

    @Test
    fun `a game already in another Tome is still added to this one`() {
        assertEquals(
            "Added to Cabinet",
            homeTomeAddedMessage(
                before = document(tome(cabinet, "Cabinet"), tome(other, "Other", member("chrono"))),
                after = document(tome(cabinet, "Cabinet", member("chrono")), tome(other, "Other", member("chrono"))),
                tomeId = cabinet,
                member = member("chrono"),
            ),
        )
    }

    @Test
    fun `a removal or a game already in this Tome says nothing`() {
        assertNull(
            "a removal adds nothing",
            homeTomeAddedMessage(
                before = document(tome(cabinet, "Cabinet", member("chrono"))),
                after = document(tome(cabinet, "Cabinet")),
                tomeId = cabinet,
                member = member("chrono"),
            ),
        )
        assertNull(
            "already a member, so nothing changed",
            homeTomeAddedMessage(
                before = document(tome(cabinet, "Cabinet", member("chrono"))),
                after = document(tome(cabinet, "Cabinet", member("chrono"))),
                tomeId = cabinet,
                member = member("chrono"),
            ),
        )
    }

    @Test
    fun `toggleTomeMembership publishes the add on the Home toast and never says moved`() {
        val source = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/HomeShelfCoordinator.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/HomeShelfCoordinator.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("HomeShelfCoordinator.kt not found")
        val toggle = source.substringAfter(
            "val toggleTomeMembership: (TomeId.Custom, HomeShelfItemId, (Boolean) -> Unit) -> Unit = " +
                "{ tomeId, itemId, done ->",
        ).substringBefore("val deleteTome: (TomeId.Custom, (Boolean) -> Unit) -> Unit = delete@{")
        assertTrue(toggle.contains("homeTomeAddedMessage(before, after, tomeId, member)"))
        assertTrue(toggle.contains("emitMessage(MimirMessageClass.Confirmation, it)"))
        assertFalse(source.contains("still a Favorite"))
    }
}
