package com.gruning.mimir.rewrite

import android.content.res.Resources
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.feature.library.TomeId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.After
import org.robolectric.shadows.ShadowLooper

/**
 * OR-1 : a focused Editor's Choice game says which Tomes it also lives in. Localization step 2: the words are string
 * resources, so the same mark reads in English by default and in Latin American Spanish on an es-419 device; the Tome's name is the
 * player's own and is never translated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HomeTomeMarkLabelTest {
    /**
     * This class runs in the shared sdk 34 Robolectric sandbox. Apply any snapshot writes it caused and run what they posted, or a
     * later Compose test in the sandbox fails "Compose did not get idle" (CompanionThemeSeedTest, MimirTrackpadPointerStyleTest).
     */
    @After
    fun applyPendingSnapshotWrites() {
        Snapshot.sendApplyNotifications()
        ShadowLooper.idleMainLooper()
    }


    private fun tome(label: String, vararg members: String) = CustomHomeTome(
        id = TomeId.Custom(UUID.nameUUIDFromBytes(label.toByteArray())),
        label = label,
        members = members.map { FavoriteMemberId.Emulated(GameId(it)) },
    )

    private val chrono = FavoriteMemberId.Emulated(GameId("chrono"))
    private val english: Resources = onboardingTestResources()
    private val spanish: Resources = onboardingTestResourcesFor("es-419")

    @Test
    fun `one Tome is named and several are counted`() {
        assertEquals("In Cabinet", homeTomeMarkLabel(english, HomeTomeDocument(customTomes = listOf(tome("Cabinet", "chrono"))), chrono))
        assertEquals(
            "In 2 Tomes",
            homeTomeMarkLabel(
                english,
                HomeTomeDocument(customTomes = listOf(tome("Cabinet", "chrono"), tome("RPGs", "chrono", "zelda"))),
                chrono,
            ),
        )
    }

    @Test
    fun `in Spanish the mark keeps the Tome's own name and counts Tomos`() {
        assertEquals("En Cabinet", homeTomeMarkLabel(spanish, HomeTomeDocument(customTomes = listOf(tome("Cabinet", "chrono"))), chrono))
        assertEquals(
            "En 2 Tomos",
            homeTomeMarkLabel(
                spanish,
                HomeTomeDocument(customTomes = listOf(tome("Cabinet", "chrono"), tome("RPGs", "chrono", "zelda"))),
                chrono,
            ),
        )
    }

    @Test
    fun `a game in no Tome, or no game, has no mark`() {
        assertNull(homeTomeMarkLabel(english, HomeTomeDocument(customTomes = listOf(tome("Cabinet", "zelda"))), chrono))
        assertNull(homeTomeMarkLabel(english, HomeTomeDocument(customTomes = listOf(tome("Cabinet", "chrono"))), null))
    }

    @Test
    fun `Home's eyebrow names the collection in the language of the device`() {
        assertEquals("Editor’s choice", homeViewModeLabel(english, HomeViewMode.EDITORS_CHOICE))
        assertEquals("Pinned Tomes", homeViewModeLabel(english, HomeViewMode.PINNED_TOMES))
        assertEquals("Selección del editor", homeViewModeLabel(spanish, HomeViewMode.EDITORS_CHOICE))
        assertEquals("Tomos fijados", homeViewModeLabel(spanish, HomeViewMode.PINNED_TOMES))
    }
}
