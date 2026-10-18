package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.FocusState
import com.gruning.mimir.rewrite.core.input.MimirNavFeedback
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.feature.library.HomeShelfEntry
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import com.gruning.mimir.rewrite.feature.library.HomeShelfTome
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioPageSize
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioScope
import com.gruning.mimir.rewrite.feature.library.TomeId
import com.gruning.mimir.rewrite.feature.library.homeTomeFolioTravelStart
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTomeLibraryTest {
    private val oldCustom = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val newCustom = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val snes = TomeId.Platform(PlatformId("snes"))

    // The create cell's label and the editor's status and legend are string resources now; their English and Spanish wording is pinned in HomeTomeLibraryTextTest.

    @Test
    fun `editor collapses status and legend only when keyboard headroom is short`() {
        assertTrue(homeTomeEditorCollapsesCopy(maxHeightDp = 360f))
        assertEquals(false, homeTomeEditorCollapsesCopy(maxHeightDp = 444f))
    }

    @Test
    fun `browser is Home Shelf then custom Tomes then create without platform or pin duplicates`() {
        val overview = HomeShelfTome(TomeId.HomeOverview, "HOME SHELF", emptyList(), 0)
        val document = HomeTomeDocument(
            pinOrder = listOf(oldCustom),
            customTomes = listOf(
                CustomHomeTome(oldCustom, "Old", emptyList(), createdAtMillis = 10),
                CustomHomeTome(newCustom, "New", emptyList(), createdAtMillis = 20),
            ),
        )
        val entries = homeTomeLibraryEntries(
            document,
            mapOf(oldCustom to tome(oldCustom, "Old"), newCustom to tome(newCustom, "New"), snes to tome(snes, "SNES")),
            homeOverview = overview,
        )
        assertEquals(
            listOf(TomeId.HomeOverview, newCustom, oldCustom, null),
            entries.map { (it as? HomeTomeLibraryEntry.Tome)?.model?.tomeId },
        )
    }

    @Test
    fun `Home Shelf leads the custom browser and stored pin data does not remove a custom Tome`() {
        val overview = HomeShelfTome(TomeId.HomeOverview, "HOME SHELF", emptyList(), memberCount = 0)
        val document = HomeTomeDocument(
            pinOrder = listOf(oldCustom),
            customTomes = listOf(
                CustomHomeTome(oldCustom, "Old", emptyList(), createdAtMillis = 10),
                CustomHomeTome(newCustom, "New", emptyList(), createdAtMillis = 20),
            ),
        )
        val available = mapOf(
            oldCustom to tome(oldCustom, "Old"),
            newCustom to tome(newCustom, "New"),
            snes to tome(snes, "SNES"),
        )
        val legacy = homeTomeLibraryEntries(document, available)
        val entries = homeTomeLibraryEntries(document, available, homeOverview = overview)

        assertTrue(entries[0].isHomeShelf)
        assertEquals(legacy, entries.drop(1))
        assertEquals(legacy.size + 1, entries.size)
        assertEquals(0, (entries[0] as HomeTomeLibraryEntry.Tome).model.memberCount)
    }

    @Test
    fun `X and Y are inert on Home Shelf while pins and creates still respond`() {
        val overview = HomeTomeLibraryEntry.Tome(
            HomeShelfTome(TomeId.HomeOverview, "HOME SHELF", emptyList(), 0),
            pinned = false,
            section = HomeTomeLibrarySection.HOME,
        )
        val pin = HomeTomeLibraryEntry.Tome(tome(snes, "SNES"), false, HomeTomeLibrarySection.PLATFORM)
        val state = HomeTomeLibraryState(
            entries = listOf(overview, pin, HomeTomeLibraryEntry.CreateCustom),
            invokingEntry = null,
            focusedIndex = 0,
        )
        var toggled: Pair<TomeId, Boolean>? = null
        val secondary = reduceHomeTomeLibrary(
            state,
            ControllerAction.SECONDARY,
            { true },
            { id, value -> toggled = id to value },
            {},
        )
        assertTrue(secondary === state)
        assertNull(secondary?.pinMutation)
        assertNull(secondary?.draft)
        assertNull(secondary?.editingTomeId)
        assertNull(toggled)

        val tertiary = reduceHomeTomeLibrary(state, ControllerAction.TERTIARY, { true }, { _, _ -> }, {})
        assertTrue(tertiary === state)
        assertNull(tertiary?.draft)
        assertNull(tertiary?.editingTomeId)

        val pinSeat = state.copy(focusedIndex = 1)
        assertEquals(
            HomeTomePinMutation(snes, true),
            reduceHomeTomeLibrary(pinSeat, ControllerAction.SECONDARY, { true }, { _, _ -> }, {})?.pinMutation,
        )
        assertEquals(
            "",
            reduceHomeTomeLibrary(pinSeat, ControllerAction.TERTIARY, { true }, { _, _ -> }, {})?.draft,
        )
    }

    @Test
    fun `A on Home Shelf opens the overview and Back restores the exact browser seat`() {
        val overview = HomeTomeLibraryEntry.Tome(
            HomeShelfTome(TomeId.HomeOverview, "HOME SHELF", emptyList(), 0),
            pinned = false,
            section = HomeTomeLibrarySection.HOME,
        )
        val pin = HomeTomeLibraryEntry.Tome(tome(snes, "SNES"), false, HomeTomeLibrarySection.PLATFORM)
        val entries = listOf(overview, pin, HomeTomeLibraryEntry.CreateCustom)
        val state = HomeTomeLibraryState(entries = entries, invokingEntry = null, focusedIndex = 0)
        var opened: TomeId? = null
        assertEquals(
            true,
            reduceHomeTomeLibrary(state, ControllerAction.CONFIRM, { opened = it; true }, { _, _ -> }, {})?.handingOff,
        )
        assertEquals(TomeId.HomeOverview, opened)

        assertEquals(
            state,
            reduceHomeTomeLibrary(state, ControllerAction.CONFIRM, { false }, { _, _ -> }, {}),
        )

        val session = HomeTomeSession(
            tomeId = TomeId.HomeOverview,
            origin = HomeTomeOrigin.TOME_LIBRARY,
            rootEntry = null,
            browserFocusedIndex = 0,
            browserFirstVisibleIndex = 0,
        )
        val restoredIndex = entries.indexOfFirst { entry ->
            entry is HomeTomeLibraryEntry.Tome && entry.model.tomeId == session.tomeId
        }
        assertEquals(0, restoredIndex)
        assertEquals(HomeTomeOrigin.TOME_LIBRARY, session.origin)
        assertEquals(0, session.browserFocusedIndex)
        assertEquals(0, session.browserFirstVisibleIndex)
    }

    @Test
    fun `focus slots are honest 2-D Home plus grid without a separator seat`() {
        val entries = libraryWithHome(collectionCount = 5)
        val slots = homeTomeLibraryFocusSlots(entries)
        assertEquals(entries.size, slots.size)
        assertEquals(0f, slots[0].bounds.left, 0.01f)
        assertTrue(slots[0].bounds.right <= HomeTomeLibraryHomeFaceWidthDp + 0.01f)
        val firstGrid = slots[1].bounds
        val separatorStart = HomeTomeLibraryHomeFaceWidthDp
        val separatorEnd = HomeTomeLibraryHomeFaceWidthDp + HomeTomeLibrarySeparatorLane.value
        assertTrue(firstGrid.left >= separatorEnd - 0.01f)
        // Separator lane is empty of focus slots.
        assertTrue(slots.none { slot ->
            slot.bounds.left >= separatorStart && slot.bounds.right <= separatorEnd
        })
        assertEquals(entries.size, slots.size)
        // Flat 5 → collection cell 4 → col 1, row 1 under 3-column math.
        assertEquals(1, homeTomeLibraryColumnOf(5, heroOffset = 1))
        assertEquals(1, homeTomeLibraryRowOf(5, heroOffset = 1))
        assertEquals(0, homeTomeLibraryColumnOf(4, heroOffset = 1))
        assertEquals(1, homeTomeLibraryRowOf(4, heroOffset = 1))
    }

    @Test
    fun `browser focus keys encode exact typed entry identity`() {
        val platform = HomeTomeLibraryEntry.Tome(tome(snes, "SNES"), false, HomeTomeLibrarySection.PLATFORM)
        val custom = HomeTomeLibraryEntry.Tome(tome(newCustom, "New"), false, HomeTomeLibrarySection.CUSTOM)

        assertEquals("home/tome/library", HomeTomeLibraryScope.value)
        assertEquals("home/tome/library/platform/snes", homeTomeLibraryFocusKey(platform).value)
        assertEquals("home/tome/library/action/create-custom-tome", homeTomeLibraryFocusKey(HomeTomeLibraryEntry.CreateCustom).value)
        assertEquals(false, homeTomeLibraryFocusKey(platform) == homeTomeLibraryFocusKey(custom))
    }

    @Test
    fun `browser restoration resolves the remembered typed identity rather than a stale index`() {
        val old = HomeTomeLibraryEntry.Tome(tome(oldCustom, "Old"), false, HomeTomeLibrarySection.CUSTOM)
        val fresh = HomeTomeLibraryEntry.Tome(tome(newCustom, "New"), false, HomeTomeLibrarySection.CUSTOM)
        val entries = listOf(fresh, old, HomeTomeLibraryEntry.CreateCustom)

        assertEquals(1, homeTomeLibraryRestoredIndex(entries, homeTomeLibraryFocusKey(old), fallbackIndex = 0))
        assertEquals(2, homeTomeLibraryRestoredIndex(entries, FocusKey("gone"), fallbackIndex = 2))
    }

    @Test
    fun `pin settlement follows the acted Tome and retains last-grid identity`() {
        val old = HomeTomeLibraryEntry.Tome(tome(oldCustom, "Old"), false, HomeTomeLibrarySection.CUSTOM)
        val fresh = HomeTomeLibraryEntry.Tome(tome(newCustom, "New"), false, HomeTomeLibrarySection.CUSTOM)
        val current = HomeTomeLibraryState(
            entries = listOf(old, fresh, HomeTomeLibraryEntry.CreateCustom),
            invokingEntry = null,
            focusedIndex = 0,
            lastGridStableKey = old.stableKey,
        )
        val movedWhileSaving = current.copy(focusedIndex = 1, firstVisibleIndex = 1)
        val reordered = listOf(fresh, old, HomeTomeLibraryEntry.CreateCustom)

        val reconciled = reconcilePinnedHomeTomeLibrary(
            live = movedWhileSaving,
            expectedSession = current.sessionToken,
            entries = reordered,
            actedTomeId = oldCustom,
        )

        assertEquals(old.stableKey, reconciled?.entries?.get(reconciled.focusedIndex)?.stableKey)
        assertEquals(old.stableKey, reconciled?.lastGridStableKey)
        assertEquals(1, reconciled?.focusedIndex)
        assertEquals(1, reconciled?.firstVisibleIndex)
    }

    @Test
    fun `removed last-grid identity falls back to nearest collection seat not raw index`() {
        val a = HomeTomeLibraryEntry.Tome(tome(oldCustom, "A"), false, HomeTomeLibrarySection.CUSTOM)
        val b = HomeTomeLibraryEntry.Tome(tome(newCustom, "B"), false, HomeTomeLibrarySection.CUSTOM)
        val c = HomeTomeLibraryEntry.CreateCustom
        val home = homeOverviewEntry()
        val before = listOf(home, a, b, c)
        val after = listOf(home, b, c) // a removed
        assertEquals(
            b.stableKey,
            homeTomeLibraryReconcileLastGridKey(
                entries = after,
                previousKey = a.stableKey,
                previousFlatHint = 1,
            ),
        )
        assertEquals(
            1,
            homeTomeLibraryResolveCollectionIndex(after, preferredStableKey = a.stableKey, preferredFlatHint = 1),
        )
        val live = HomeTomeLibraryState(
            entries = before,
            invokingEntry = null,
            focusedIndex = 1,
            lastGridStableKey = a.stableKey,
            desiredColumn = 0,
        )
        val reconciled = reconcilePinnedHomeTomeLibrary(live, live.sessionToken, after)
        assertEquals(b.stableKey, reconciled?.lastGridStableKey)
    }

    @Test
    fun `pin settlement cannot resurrect a closed or reopened browser`() {
        val current = HomeTomeLibraryState(
            entries = listOf(HomeTomeLibraryEntry.CreateCustom),
            invokingEntry = null,
        )
        val reopened = HomeTomeLibraryState(
            entries = listOf(HomeTomeLibraryEntry.CreateCustom),
            invokingEntry = null,
        )
        assertNull(reconcilePinnedHomeTomeLibrary(null, current.sessionToken, reopened.entries))
        assertNull(reconcilePinnedHomeTomeLibrary(reopened, current.sessionToken, reopened.entries))
    }

    @Test
    fun `pin and delete mutations coalesce until the first projection settles`() {
        val gate = HomeTomeProjectionMutationGate()
        assertTrue(gate.tryBegin(snes))
        assertEquals(false, gate.tryBegin(snes))
        assertEquals(false, gate.tryBegin(oldCustom))
        gate.finish(snes)
        assertTrue(gate.tryBegin(oldCustom))
    }

    @Test
    fun `leading and trailing clipped Tome cards settle fully inside the carousel viewport`() {
        assertEquals(36, homeTomeLibraryFocusScrollDelta(420, 636, 12, 600))
        assertEquals(-20, homeTomeLibraryFocusScrollDelta(-8, 208, 12, 600))
        assertEquals(0, homeTomeLibraryFocusScrollDelta(24, 240, 12, 600))
        assertEquals(
            -24,
            homeTomeLibraryFocusScrollDelta(
                itemStart = 24,
                itemEnd = 240,
                viewportStart = 12,
                viewportEnd = 600,
                visualOutset = 8,
                revealBefore = 28,
            ),
        )
        assertEquals(
            72,
            homeTomeLibraryFocusScrollDelta(
                itemStart = 420,
                itemEnd = 636,
                viewportStart = 12,
                viewportEnd = 600,
                visualOutset = 8,
                revealAfter = 28,
            ),
        )
    }

    @Test
    fun `dpad settlement scrolls only the minimum reveal and never re-centres a visible Tome`() {
        //  the row is fixed and finite. A Tome already fully visible
        // (with its ring outset) settles at ZERO — including the exact centre case the retired
        // centre-seat law used to move, and both edge seats resting at their honest border padding.
        val outset = 20
        assertEquals(0, homeTomeLibraryFocusScrollDelta(192, 368, 0, 560, visualOutset = outset))
        assertEquals(0, homeTomeLibraryFocusScrollDelta(20, 196, 0, 560, visualOutset = outset))
        assertEquals(0, homeTomeLibraryFocusScrollDelta(364, 540, 0, 560, visualOutset = outset))
        // A clipped Tome moves exactly its own reveal distance (ring + breathing included), no more.
        assertEquals(-16, homeTomeLibraryFocusScrollDelta(4, 180, 0, 560, visualOutset = outset))
        assertEquals(24, homeTomeLibraryFocusScrollDelta(388, 564, 0, 560, visualOutset = outset))
    }

    @Test
    fun `symmetric minimum reveal scrolls only collision distance in both directions`() {
        val outset = 4
        assertEquals(0, homeTomeLibraryFocusScrollDelta(20, 100, 0, 320, visualOutset = outset))
        assertEquals(-4, homeTomeLibraryFocusScrollDelta(0, 80, 0, 320, visualOutset = outset))
        assertEquals(24, homeTomeLibraryFocusScrollDelta(260, 340, 0, 320, visualOutset = outset))
        val up = homeTomeLibraryFocusScrollDelta(0, 80, 0, 320, visualOutset = outset)
        val down = homeTomeLibraryFocusScrollDelta(240, 320, 0, 320, visualOutset = outset)
        assertEquals(-up, down)
        assertEquals(4, down)
    }

    @Test
    fun `vertical focus carries every collection row to the leading reading seat`() {
        val viewportStart = 12
        val outset = 15
        assertEquals(
            0,
            homeTomeLibraryFocusSettlementDelta(
                itemStart = viewportStart + outset,
                viewportStart = viewportStart,
                visualOutset = outset,
            ),
        )
        assertEquals(
            165,
            homeTomeLibraryFocusSettlementDelta(
                itemStart = viewportStart + outset + 165,
                viewportStart = viewportStart,
                visualOutset = outset,
            ),
        )
        assertEquals(
            -165,
            homeTomeLibraryFocusSettlementDelta(
                itemStart = viewportStart + outset - 165,
                viewportStart = viewportStart,
                visualOutset = outset,
            ),
        )
    }

    @Test
    fun `whole card capacity includes card width and both focus outsets`() {
        assertEquals(
            1,
            homeTomeLibraryVisibleCount(
                viewportLength = 400,
                itemStride = 200,
                itemSize = 176,
                visualOutset = 20,
            ),
        )
        assertEquals(
            2,
            homeTomeLibraryVisibleCount(
                viewportLength = 560,
                itemStride = 200,
                itemSize = 176,
                visualOutset = 20,
            ),
        )
    }

    @Test
    fun `one visible tome keeps the authored gap in far target estimates`() {
        assertEquals(
            220,
            homeTomeLibraryMeasuredStride(
                firstOffset = 24,
                secondOffset = null,
                itemSize = 176,
                itemGap = 44,
            ),
        )
        assertEquals(
            228,
            homeTomeLibraryMeasuredStride(
                firstOffset = 24,
                secondOffset = 252,
                itemSize = 176,
                itemGap = 44,
            ),
        )
        assertEquals(
            HomeTomeLibraryLayoutGeometry(viewportLength = 400, itemSize = 176, itemStride = 220),
            homeTomeLibraryLayoutGeometry(
                viewportStart = 0,
                viewportEnd = 400,
                firstOffset = 24,
                secondOffset = null,
                itemSize = 176,
                itemGap = 44,
            ),
        )
    }

    @Test
    fun `Tome face keeps a restrained compact envelope and a stable landscape cap`() {
        assertEquals(225f, homeTomeLibraryFaceHeightDp(350f), 0.01f)
        assertEquals(234.4f, homeTomeLibraryFaceHeightDp(360f), 0.01f)
        assertEquals(248f, homeTomeLibraryFaceHeightDp(444f), 0.01f)
    }

    @Test
    fun `flat firstVisibleIndex rebases through hero offset`() {
        assertEquals(0, homeTomeLibraryGridFirstVisibleIndex(flatFirstVisible = 0, heroOffset = 1))
        assertEquals(3, homeTomeLibraryGridFirstVisibleIndex(flatFirstVisible = 4, heroOffset = 1))
        assertEquals(4, homeTomeLibraryFlatFirstVisibleIndex(gridFirstVisible = 3, heroOffset = 1))
        assertEquals(0, homeTomeLibraryFlatFirstVisibleIndex(gridFirstVisible = 0, heroOffset = 0))
    }

    @Test
    fun `ordinary grid travel projects only nearby uncomposed rows`() {
        assertTrue(homeTomeLibraryCanProjectReveal(targetIndex = 4, anchorIndex = 3))
        assertTrue(homeTomeLibraryCanProjectReveal(targetIndex = 4, anchorIndex = 2))
        assertFalse(homeTomeLibraryCanProjectReveal(targetIndex = 7, anchorIndex = 2))
    }

    @Test
    fun `first and structural layouts restore once while stable focus travel remains animated`() {
        val history = HomeTomeLibraryTravelHistory()
        assertTrue(history.consumeLayout(listOf("home", "snes", "ps2")))
        assertFalse(history.consumeLayout(listOf("home", "snes", "ps2")))
        // Pin/style mutation preserves the same stable identities and must not restart the travel epoch.
        assertFalse(history.consumeLayout(listOf("home", "snes", "ps2")))
        assertTrue(history.consumeLayout(listOf("home", "ps2", "snes")))
        assertFalse(history.consumeLayout(listOf("home", "ps2", "snes")))
    }

    @Test
    fun `footer position owns truthful vertical navigation`() {
        val entries = libraryWithHome(collectionCount = 6)
        assertEquals(
            HomeTomeLibraryPosition(1, 7, canMoveUp = false, canMoveDown = false),
            homeTomeLibraryPosition(focusedIndex = 0, entries = entries),
        )
        assertEquals("2 / 7", homeTomeLibraryPosition(1, entries).label)
        assertTrue(homeTomeLibraryPosition(1, entries).canMoveDown)
        assertFalse(homeTomeLibraryPosition(1, entries).canMoveUp)
        assertTrue(homeTomeLibraryPosition(5, entries).canMoveUp)
        assertEquals(
            HomeTomeLibraryPosition(0, 0, canMoveUp = false, canMoveDown = false),
            homeTomeLibraryPosition(focusedIndex = 0, entries = emptyList()),
        )
    }

    @Test
    fun `divider beam sits at the fixed separator lane midpoint`() {
        assertEquals(186, homeTomeLibraryDividerCenterPx(homeWidthPx = 162, separatorLanePx = 48))
    }

    @Test
    fun `Home separator and Collection group owns equal outer side insets`() {
        val bodyWidth = 778f
        val composition = homeTomeLibraryCenteredCompositionWidthDp(
            bodyWidthDp = bodyWidth,
            homeFaceWidthDp = HomeTomeLibraryHomeFaceWidthDp,
            showsHome = true,
        )

        assertEquals(591.8f, composition, 0.01f)
        assertEquals(
            93.1f,
            homeTomeLibraryCenteredOuterInsetDp(bodyWidth, composition),
            0.01f,
        )
        assertEquals(
            HomeTomeLibraryGridContentWidthDp,
            homeTomeLibraryCenteredCompositionWidthDp(
                bodyWidthDp = bodyWidth,
                homeFaceWidthDp = HomeTomeLibraryHomeFaceWidthDp,
                showsHome = false,
            ),
            0.01f,
        )
    }

    @Test
    fun `compact geometry fits RP5 Thor-symbolic and common aspect bodies`() {
        // Exact two-row viewport: 2*15 + 2*150 + 1*15 = 345dp. No focusScale in row height.
        assertEquals(15f, HomeTomeLibraryFocusRingClearanceDp, 0.01f)
        assertEquals(15f, HomeTomeLibraryGridRowGapDp, 0.01f)
        assertEquals(15f, HomeTomeLibraryGridOuterPaddingDp, 0.01f)
        assertEquals(
            345f,
            homeTomeLibraryGridViewportHeightDp(
                faceHeightDp = 150f,
                visibleRows = 2,
                rowGapDp = 15f,
                clearanceDp = 15f,
            ),
            0.01f,
        )
        // Third-row layout start equals viewport end — not inside the painted window.
        val thirdRowStart = 15f + 2f * (150f + 15f)
        assertEquals(345f, thirdRowStart, 0.01f)
        assertFalse("third row must not start inside viewport", thirdRowStart < 345f)

        // RP5-class body ~329-351dp hosts two enlarged compact rows with 15dp clearance.
        listOf(329f, 340f, 351f).forEach { body ->
            val face = homeTomeLibraryResolvedCompactFaceHeightDp(bodyHeightDp = body, gridWidthDp = 420f)
            assertTrue("RP5 body $body face $face", face in 64f..150f)
            assertEquals(
                "RP5 body $body must fit exactly two unscaled rows",
                HomeTomeLibraryGridVisibleRows,
                homeTomeLibraryVisibleRowCapacity(body, face),
            )
            val viewport = homeTomeLibraryGridViewportHeightDp(face)
            assertEquals(
                2f * HomeTomeLibraryFocusRingClearanceDp +
                    HomeTomeLibraryGridVisibleRows * face +
                    (HomeTomeLibraryGridVisibleRows - 1) * HomeTomeLibraryGridRowGapDp,
                viewport,
                0.01f,
            )
            assertTrue("viewport $viewport must fit body $body", viewport <= body + 0.01f)
            // Live derived face: third-row start at-or-beyond viewport — no third-row sliver.
            val liveThirdRowStart =
                HomeTomeLibraryFocusRingClearanceDp +
                    HomeTomeLibraryGridVisibleRows * (face + HomeTomeLibraryGridRowGapDp)
            assertEquals(viewport, liveThirdRowStart, 0.01f)
            assertFalse(
                "RP5 body $body face $face must not paint a third-row sliver",
                liveThirdRowStart < viewport,
            )
            assertEquals(112.5f, HomeTomeLibraryCompactMaxFaceWidthDp, 0.01f)
            assertEquals(150f, HomeTomeLibraryCompactMaxFaceHeightDp, 0.01f)
            assertEquals(3, HomeTomeLibraryGridColumns)
            assertEquals(2, HomeTomeLibraryGridVisibleRows)
            val home = homeTomeLibraryHomeFaceHeightDp(body)
            assertEquals(194.4f, home, 0.01f)
            assertEquals(0.90f, HomeTomeLibraryHomeFaceScale, 0.001f)
        }
        // Wide regions still bound content to ~398dp so cells stay 112.5×150, not stretched.
        val wide = homeTomeLibraryResolvedCompactFaceHeightDp(bodyHeightDp = 400f, gridWidthDp = 900f)
        assertEquals(150f, wide, 0.5f)
        // Thor-symbolic / 3:2 / 4:3 / 1:1 symbolic bodies — no device-name branching.
        listOf(
            400f to 600f, // thor-ish landscape body height
            300f to 450f, // 3:2
            360f to 480f, // 4:3
            360f to 360f, // 1:1
        ).forEach { (body, gridWidth) ->
            val face = homeTomeLibraryResolvedCompactFaceHeightDp(body, gridWidth)
            assertTrue(face >= 64f)
            assertTrue(face <= 150f)
        }
        // <64 derived keeps 64 and fewer visible rows (scroll / minimum reveal).
        val tiny = homeTomeLibraryCompactFaceHeightDp(bodyHeightDp = 140f, gridWidthDp = 200f)
        assertTrue(tiny < 64f)
        val resolved = homeTomeLibraryResolvedCompactFaceHeightDp(140f, 200f)
        assertEquals(64f, resolved, 0.01f)
        assertTrue(homeTomeLibraryVisibleRowCapacity(140f, resolved) < HomeTomeLibraryGridVisibleRows)
    }

    @Test
    fun `settlement waits through delayed composition and any later endpoint correction`() {
        var state = HomeTomeLibrarySettlementState()
        state = advanceHomeTomeLibrarySettlement(state, null)
        assertEquals(false, state.settled)
        state = advanceHomeTomeLibrarySettlement(state, -24)
        assertEquals(false, state.settled)
        state = advanceHomeTomeLibrarySettlement(state, 0)
        assertEquals(false, state.settled)
        state = advanceHomeTomeLibrarySettlement(state, 72)
        assertEquals(false, state.zeroFrameObserved)
        state = advanceHomeTomeLibrarySettlement(state, 0)
        assertEquals(false, state.settled)
        state = advanceHomeTomeLibrarySettlement(state, 0)
        assertEquals(true, state.settled)
    }

    @Test
    fun `Library Folio Library Home is one exact sibling scope chain`() {
        val authority = DefaultFocusAuthority()
        val libraryEntry = HomeTomeLibraryEntry.Tome(
            tome(snes, "SNES"),
            false,
            HomeTomeLibrarySection.PLATFORM,
        )
        val libraryKey = homeTomeLibraryFocusKey(libraryEntry)
        val memberKey = com.gruning.mimir.rewrite.feature.library.homeTomeMemberFocusKey(
            HomeShelfItemId.Emulated(GameId("chrono")),
        )
        authority.pushScope(HomeShelfScope)

        assertTrue(enterHomeTomeScope(authority, HomeTomeLibraryScope, libraryKey))
        authority.registerVirtual(libraryKey, HomeTomeLibraryScope, FocusBounds(0f, 0f, 1f, 1f))
        assertEquals(FocusState(HomeTomeLibraryScope, libraryKey), authority.state.value)

        assertTrue(replaceHomeTomeScope(authority, HomeTomeLibraryScope, HomeTomeFolioScope, memberKey))
        authority.registerVirtual(memberKey, HomeTomeFolioScope, FocusBounds(0f, 0f, 1f, 1f))
        assertEquals(FocusState(HomeTomeFolioScope, memberKey), authority.state.value)

        assertTrue(replaceHomeTomeScope(authority, HomeTomeFolioScope, HomeTomeLibraryScope, libraryKey))
        assertEquals(FocusState(HomeTomeLibraryScope, libraryKey), authority.state.value)
        assertTrue(leaveHomeTomeScope(authority, HomeTomeLibraryScope))
        assertEquals(HomeShelfScope, authority.state.value.scope)
    }

    @Test
    fun `closed Tome epoch rejects an older durable session`() {
        val old = HomeTomeSession(snes, HomeTomeOrigin.HOME_ROOT, rootEntry = null, generation = 8L)
        assertEquals(old, restorableHomeTomeSession(old, minimumGeneration = 8L))
        assertNull(restorableHomeTomeSession(old, minimumGeneration = 9L))
        assertEquals(9L, nextHomeTomeSessionGeneration(8L))
    }

    @Test
    fun `Back restores exact invoking entry from any grid seat`() {
        val invoking = HomeShelfEntry.Game(HomeShelfItemId.Emulated(GameId("chrono")))
        val state = HomeTomeLibraryState(
            entries = libraryWithHome(collectionCount = 5),
            invokingEntry = invoking,
            focusedIndex = 4,
            firstVisibleIndex = 1,
        )
        var restored: HomeShelfEntry? = null
        val closed = reduceHomeTomeLibrary(state, ControllerAction.CANCEL, { true }, { _, _ -> }, { restored = it })
        assertNull(closed)
        assertEquals(invoking, restored)
    }

    @Test
    fun `exhaustive 3-column navigation for collection counts 0 through 11`() {
        for (collectionCount in 0..11) {
            val entries = libraryWithHome(collectionCount)
            val hero = 1
            val cols = HomeTomeLibraryGridColumns
            assertEquals(hero, homeTomeLibraryHeroOffset(entries))
            assertEquals(collectionCount, homeTomeLibraryCollectionCount(entries))
            if (collectionCount > 0) {
                assertTrue(entries.last() is HomeTomeLibraryEntry.CreateCustom)
            } else {
                assertTrue(entries.single().isHomeShelf)
            }

            fun assertTerminalNav(
                state: HomeTomeLibraryState,
                action: ControllerAction,
                label: String,
            ) {
                val before = MimirNavFeedback.epoch
                val after = navigateHomeTomeLibrary(state, action)
                assertEquals(label, state.focusedIndex, after.focusedIndex)
                assertEquals("$label epoch", before, MimirNavFeedback.epoch)
            }

            val homeState = HomeTomeLibraryState(entries = entries, invokingEntry = null, focusedIndex = 0)
            assertTerminalNav(homeState, ControllerAction.NAV_UP, "count=$collectionCount Home UP")
            assertTerminalNav(homeState, ControllerAction.NAV_DOWN, "count=$collectionCount Home DOWN")
            assertTerminalNav(homeState, ControllerAction.NAV_LEFT, "count=$collectionCount Home LEFT")

            if (collectionCount == 0) {
                assertTerminalNav(homeState, ControllerAction.NAV_RIGHT, "count=0 Home RIGHT")
                continue
            }

            val toFirst = navigateHomeTomeLibrary(homeState, ControllerAction.NAV_RIGHT)
            assertEquals(1, toFirst.focusedIndex)
            assertEquals(entries[1].stableKey, toFirst.lastGridStableKey)

            for (flat in hero until entries.size) {
                val cell = flat - hero
                val row = cell / cols
                val col = cell % cols
                val state = HomeTomeLibraryState(
                    entries = entries,
                    invokingEntry = null,
                    focusedIndex = flat,
                    desiredColumn = col,
                    lastGridStableKey = entries[flat].stableKey,
                )

                // RIGHT: within row only, never wraps.
                val rowEnd = minOf((row + 1) * cols - 1, collectionCount - 1)
                val expectRight = if (col < cols - 1 && cell < rowEnd) flat + 1 else flat
                if (expectRight == flat) {
                    assertTerminalNav(state, ControllerAction.NAV_RIGHT, "count=$collectionCount flat=$flat RIGHT")
                } else {
                    val right = navigateHomeTomeLibrary(state, ControllerAction.NAV_RIGHT)
                    assertEquals("count=$collectionCount flat=$flat RIGHT", expectRight, right.focusedIndex)
                }

                // LEFT
                val left = navigateHomeTomeLibrary(state, ControllerAction.NAV_LEFT)
                if (col > 0) {
                    assertEquals(flat - 1, left.focusedIndex)
                    assertEquals(entries[flat - 1].stableKey, left.lastGridStableKey)
                } else {
                    assertEquals(0, left.focusedIndex)
                    assertEquals(entries[flat].stableKey, left.lastGridStableKey)
                }

                // DOWN then UP inverse identity, including partial rows.
                val lastRow = (collectionCount - 1) / cols
                if (row >= lastRow) {
                    val before = MimirNavFeedback.epoch
                    val down = navigateHomeTomeLibrary(state, ControllerAction.NAV_DOWN)
                    assertEquals("count=$collectionCount flat=$flat DOWN", flat, down.focusedIndex)
                    assertEquals(col, down.desiredColumn)
                    assertEquals("count=$collectionCount flat=$flat DOWN epoch", before, MimirNavFeedback.epoch)
                } else {
                    val down = navigateHomeTomeLibrary(state, ControllerAction.NAV_DOWN)
                    val rowStart = (row + 1) * cols
                    val rowLast = minOf(rowStart + cols - 1, collectionCount - 1)
                    val clampedCol = col.coerceAtMost(rowLast - rowStart)
                    assertEquals(hero + rowStart + clampedCol, down.focusedIndex)
                    assertEquals(col, down.desiredColumn) // retain sticky desired
                    val back = navigateHomeTomeLibrary(down, ControllerAction.NAV_UP)
                    assertEquals(flat, back.focusedIndex)
                    assertEquals(col, back.desiredColumn)
                }

                // UP then DOWN inverse
                if (row == 0) {
                    assertTerminalNav(state, ControllerAction.NAV_UP, "count=$collectionCount flat=$flat UP")
                } else {
                    val up = navigateHomeTomeLibrary(state, ControllerAction.NAV_UP)
                    assertEquals(flat - cols, up.focusedIndex)
                    assertEquals(col, up.desiredColumn)
                    val back = navigateHomeTomeLibrary(up, ControllerAction.NAV_DOWN)
                    assertEquals(flat, back.focusedIndex)
                }
            }

            // Home RIGHT / grid-col0 LEFT stable-key roundtrip.
            val deep = hero + minOf(collectionCount - 1, 6)
            var cursor = HomeTomeLibraryState(
                entries = entries,
                invokingEntry = null,
                focusedIndex = deep,
                desiredColumn = homeTomeLibraryColumnOf(deep, hero),
                lastGridStableKey = entries[deep].stableKey,
            )
            while (homeTomeLibraryColumnOf(cursor.focusedIndex, hero) > 0) {
                cursor = navigateHomeTomeLibrary(cursor, ControllerAction.NAV_LEFT)
            }
            val remembered = cursor.lastGridStableKey
            assertEquals(entries[cursor.focusedIndex].stableKey, remembered)
            val onHome = navigateHomeTomeLibrary(cursor, ControllerAction.NAV_LEFT)
            assertEquals(0, onHome.focusedIndex)
            assertEquals(remembered, onHome.lastGridStableKey)
            val returned = navigateHomeTomeLibrary(onHome, ControllerAction.NAV_RIGHT)
            assertEquals(remembered, returned.entries[returned.focusedIndex].stableKey)
            assertEquals(remembered, returned.lastGridStableKey)
        }
    }

    @Test
    fun `omitted desiredColumn and lastGridStableKey derive from restored focusedIndex`() {
        val entries = libraryWithHome(collectionCount = 8)
        // Existing call-site shape: entries + restored nonzero focusedIndex only.
        // collection cell 6 → col 0, row 2 under 3-column math.
        val restored = HomeTomeLibraryState(
            entries = entries,
            invokingEntry = null,
            focusedIndex = 7,
        )
        assertEquals(0, restored.desiredColumn)
        assertEquals(entries[7].stableKey, restored.lastGridStableKey)

        val up = navigateHomeTomeLibrary(restored, ControllerAction.NAV_UP)
        assertEquals(4, up.focusedIndex)
        assertEquals(0, up.desiredColumn)
        assertEquals(entries[4].stableKey, up.lastGridStableKey)

        val down = navigateHomeTomeLibrary(up, ControllerAction.NAV_DOWN)
        assertEquals(7, down.focusedIndex)
        assertEquals(0, down.desiredColumn)
        assertEquals(entries[7].stableKey, down.lastGridStableKey)

        val home = HomeTomeLibraryState(
            entries = entries,
            invokingEntry = null,
            focusedIndex = 0,
        )
        assertEquals(0, home.desiredColumn)
        assertNull(home.lastGridStableKey)
    }

    @Test
    fun `partial row clamps column but retains desiredColumn for exact reversal`() {
        // Ragged 5-cell case: rows [0,1,2], [3,4]. Column-2 clamps into partial row.
        val entries = libraryWithHome(collectionCount = 5)
        val start = HomeTomeLibraryState(
            entries = entries,
            invokingEntry = null,
            focusedIndex = 1 + 2, // col 2 row 0
            desiredColumn = 2,
            lastGridStableKey = entries[3].stableKey,
        )
        val down = navigateHomeTomeLibrary(start, ControllerAction.NAV_DOWN)
        assertEquals(1 + 4, down.focusedIndex) // clamped to last of partial row
        assertEquals(2, down.desiredColumn)
        val up = navigateHomeTomeLibrary(down, ControllerAction.NAV_UP)
        assertEquals(3, up.focusedIndex)
        assertEquals(2, up.desiredColumn)
    }

    @Test
    fun `Create Tome remains the stable final grid cell`() {
        val entries = libraryWithHome(collectionCount = 5)
        assertTrue(entries.last() is HomeTomeLibraryEntry.CreateCustom)
        assertEquals(entries.lastIndex, homeTomeLibraryResolveCollectionIndex(entries, entries.last().stableKey))
    }

    @Test
    fun `X toggles by TomeId while A opens that same typed Tome`() {
        val entry = HomeTomeLibraryEntry.Tome(tome(snes, "SNES"), false, HomeTomeLibrarySection.PLATFORM)
        val state = HomeTomeLibraryState(listOf(entry), invokingEntry = null, settledFocusedIndex = 0)
        var pin: Pair<TomeId, Boolean>? = null
        val pending = reduceHomeTomeLibrary(
            state,
            ControllerAction.SECONDARY,
            { true },
            { id, value -> pin = id to value },
            {},
        )
        assertEquals(snes to true, pin)
        assertEquals(HomeTomePinMutation(snes, true), pending?.pinMutation)
        var opened: TomeId? = null
        // RESTATED 2026-08-25: CONFIRM no longer clears the browser on the spot. It marks the handoff
        // and keeps painting until the Folio has materialised underneath -- see [handingOff]. What this
        // line is actually for, that A opens THAT typed Tome, is unchanged.
        assertEquals(
            true,
            reduceHomeTomeLibrary(state, ControllerAction.CONFIRM, { opened = it; true }, { _, _ -> }, {})?.handingOff,
        )
        assertEquals(snes, opened)
    }

    /**
     * The handoff hold, and the three ways it could go wrong.
     *
     *  Measured at 60fps on a Nova test device: eleven frames (~185 ms) with neither surface
     * opaque, scene sharpness 1563 -> 83. The browser now holds its pixels until the Folio is finished.
     */
    @Test
    fun `a handing-off browser is inert, and every tome path gets the hold`() {
        val custom = HomeTomeLibraryEntry.Tome(tome(snes, "SNES"), false, HomeTomeLibrarySection.PLATFORM)
        val state = HomeTomeLibraryState(listOf(custom), invokingEntry = null, settledFocusedIndex = 0)

        val holding = reduceHomeTomeLibrary(state, ControllerAction.CONFIRM, { true }, { _, _ -> }, {})
        assertEquals("CONFIRM must hold, not clear", true, holding?.handingOff)

        // INERT: it is painting over a Folio that owns the screen. Nothing may act on it, and above all
        // nothing may CLOSE it -- a Back consumed here would clear the held frame and reopen the hole.
        var dismissed = false
        for (action in listOf(
            ControllerAction.CONFIRM,
            ControllerAction.CANCEL,
            ControllerAction.SYSTEM_BACK,
            ControllerAction.SECONDARY,
            ControllerAction.TERTIARY,
            ControllerAction.NAV_LEFT,
            ControllerAction.NAV_RIGHT,
        )) {
            val after = reduceHomeTomeLibrary(
                requireNotNull(holding),
                action,
                { error("a held browser must not open anything") },
                { _, _ -> error("a held browser must not mutate pins") },
                { dismissed = true },
            )
            assertEquals("$action must leave the held frame exactly as it was", holding, after)
        }
        assertEquals("and none of them may dismiss it", false, dismissed)
    }

    /**
     * The snap's PRECONDITION, pinned where it can be read: the Folio only skips its entrance while a
     * browser is holding a cover over it. That flag is the whole justification for a hard cut, and the
     * cut is only invisible because the cover is there.
     *
     * The composable capture (`remember(tomeId)`) cannot be exercised in a unit test, so what is pinned
     * here is the fact it reads: CONFIRM produces a state whose `handingOff` is true at the moment the
     * Folio identity changes, and a Folio opened any OTHER way sees a state that is not holding.
     */
    @Test
    fun `only an open made under the browsers cover reports a hold`() {
        val entry = HomeTomeLibraryEntry.Tome(tome(snes, "SNES"), false, HomeTomeLibrarySection.PLATFORM)
        val state = HomeTomeLibraryState(listOf(entry), invokingEntry = null, settledFocusedIndex = 0)

        assertEquals(
            "the browser is holding at the instant the Folio identity changes -- this is what the " +
                "composable captures, and why the Folio may cut",
            true,
            reduceHomeTomeLibrary(state, ControllerAction.CONFIRM, { true }, { _, _ -> }, {})?.handingOff,
        )
        assertEquals(
            "a browser sitting idle is NOT a cover; a Folio opened from anywhere else must still ramp",
            false,
            state.handingOff,
        )
    }

    /**
     * A REFUSED open must not hold. `onOpen` returning false is the path where nothing is going to
     * materialise underneath, so holding would paint an inert browser over the surface it came from with
     * no signal left to release it.
     */
    @Test
    fun `an open that is refused leaves the browser interactive`() {
        val entry = HomeTomeLibraryEntry.Tome(tome(snes, "SNES"), false, HomeTomeLibrarySection.PLATFORM)
        val state = HomeTomeLibraryState(listOf(entry), invokingEntry = null, settledFocusedIndex = 0)

        val after = reduceHomeTomeLibrary(state, ControllerAction.CONFIRM, { false }, { _, _ -> }, {})

        assertEquals("refusal returns the state untouched", state, after)
        assertEquals("and it must stay interactive", false, after?.handingOff)
    }

    @Test
    fun `controller confirm follows the logical Tome even before visual settlement telemetry`() {
        val entry = HomeTomeLibraryEntry.Tome(tome(snes, "SNES"), false, HomeTomeLibrarySection.PLATFORM)
        val unsettled = HomeTomeLibraryState(listOf(entry), invokingEntry = null)
        var opened: TomeId? = null

        val retained = reduceHomeTomeLibrary(unsettled, ControllerAction.CONFIRM, { opened = it; true }, { _, _ -> }, {})

        // RESTATED 2026-08-25: the browser survives CONFIRM as a held frame rather than vanishing.
        // The property under test is untouched -- CONFIRM follows the LOGICAL focus even with no
        // settlement telemetry, which is why `opened` is asserted on an unsettled state.
        assertEquals(true, retained?.handingOff)
        assertEquals(snes, opened)
    }

    @Test
    fun `logical focus accepts identity actions while an active pin mutation freezes navigation`() {
        val entry = HomeTomeLibraryEntry.Tome(tome(snes, "SNES"), false, HomeTomeLibrarySection.PLATFORM)
        val unsettled = HomeTomeLibraryState(listOf(entry), invokingEntry = null)
        var pin: Pair<TomeId, Boolean>? = null
        val pinning = reduceHomeTomeLibrary(
            unsettled,
            ControllerAction.SECONDARY,
            { true },
            { id, value -> pin = id to value },
            {},
        )
        assertEquals(snes to true, pin)
        assertEquals(HomeTomePinMutation(snes, true), pinning?.pinMutation)
        assertEquals("", reduceHomeTomeLibrary(unsettled, ControllerAction.TERTIARY, { true }, { _, _ -> }, {})?.draft)

        val pending = unsettled.copy(
            settledFocusedIndex = 0,
            pinMutation = HomeTomePinMutation(snes, true),
        )
        assertEquals(pending, reduceHomeTomeLibrary(pending, ControllerAction.NAV_RIGHT, { true }, { _, _ -> }, {}))
        assertEquals(pending, reduceHomeTomeLibrary(pending, ControllerAction.CONFIRM, { true }, { _, _ -> }, {}))
        assertEquals(pending, reduceHomeTomeLibrary(pending, ControllerAction.TERTIARY, { true }, { _, _ -> }, {}))
    }

    @Test
    fun `Y creates from a platform card and edits only a custom Tome`() {
        val platform = HomeTomeLibraryState(
            listOf(HomeTomeLibraryEntry.Tome(tome(snes, "SNES"), false, HomeTomeLibrarySection.PLATFORM)),
            invokingEntry = null,
            settledFocusedIndex = 0,
        )
        val create = reduceHomeTomeLibrary(platform, ControllerAction.TERTIARY, { true }, { _, _ -> }, {})
        assertEquals("", create?.draft)
        assertNull(create?.editingTomeId)

        val custom = platform.copy(
            entries = listOf(
                HomeTomeLibraryEntry.Tome(tome(newCustom, "New"), false, HomeTomeLibrarySection.CUSTOM),
            ),
        )
        val edit = reduceHomeTomeLibrary(custom, ControllerAction.TERTIARY, { true }, { _, _ -> }, {})
        assertEquals("New", edit?.draft)
        assertEquals(newCustom, edit?.editingTomeId)

        val terminal = platform.copy(entries = listOf(HomeTomeLibraryEntry.CreateCustom))
        assertEquals(terminal, reduceHomeTomeLibrary(terminal, ControllerAction.TERTIARY, { true }, { _, _ -> }, {}))
    }

    @Test
    fun `custom edit requires one explicit delete confirmation`() {
        val editing = HomeTomeLibraryState(
            entries = listOf(
                HomeTomeLibraryEntry.Tome(tome(newCustom, "New"), false, HomeTomeLibrarySection.CUSTOM),
            ),
            invokingEntry = null,
            draft = "New",
            editingTomeId = newCustom,
        )
        val confirm = reduceHomeTomeLibrary(editing, ControllerAction.TERTIARY, { true }, { _, _ -> }, {})
        assertEquals(true, confirm?.confirmDelete)
        var deleted: TomeId.Custom? = null
        val pending = reduceHomeTomeLibrary(
            confirm!!,
            ControllerAction.CONFIRM,
            { true },
            { _, _ -> },
            {},
            onDelete = { deleted = it },
        )
        assertEquals(newCustom, deleted)
        assertEquals(true, pending?.deletePending)
    }

    @Test
    fun draftBackFromKeyboardRoutesThroughReducer() {
        val editing = HomeTomeLibraryState(
            entries = listOf(
                HomeTomeLibraryEntry.Tome(tome(newCustom, "New"), false, HomeTomeLibrarySection.CUSTOM),
            ),
            invokingEntry = null,
            draft = "Renamed",
            editingTomeId = newCustom,
        )
        val reduced = reduceHomeTomeLibrary(
            editing,
            ControllerAction.SYSTEM_BACK,
            { true },
            { _, _ -> },
            {},
        )
        assertNull(reduced?.draft)
        assertNull(reduced?.editingTomeId)
        assertEquals(false, reduced?.confirmDelete)
    }

    @Test
    fun confirmDeleteBackClearsConfirmationWithoutClosingDraft() {
        val editing = HomeTomeLibraryState(
            entries = listOf(
                HomeTomeLibraryEntry.Tome(tome(newCustom, "New"), false, HomeTomeLibrarySection.CUSTOM),
            ),
            invokingEntry = null,
            draft = "New",
            editingTomeId = newCustom,
            confirmDelete = true,
        )
        val reduced = reduceHomeTomeLibrary(
            editing,
            ControllerAction.SYSTEM_BACK,
            { true },
            { _, _ -> },
            {},
        )
        assertEquals("New", reduced?.draft)
        assertEquals(newCustom, reduced?.editingTomeId)
        assertEquals(false, reduced?.confirmDelete)
    }

    @Test
    fun `external Tome return keeps nonzero member index and its page`() {
        val secondPageIndex = HomeTomeFolioPageSize + 1
        val restored = restoredHomeTomePosition(secondPageIndex)
        assertEquals(secondPageIndex, restored.focusedIndex)
        assertEquals(1, restored.transition.settledPage)
    }

    @Test
    fun `launch card accepts exact folio origin and rejects root substitution`() {
        val member = HomeShelfItemId.Emulated(GameId("chrono"))
        val key = com.gruning.mimir.rewrite.feature.library.HomeTomeMember(
            itemId = member,
            title = "Chrono",
            artworkUri = null,
        ).focusKey
        val state = FocusState(HomeTomeFolioScope, key)

        assertEquals(true, homeLaunchCardOriginIsCurrent(state, HomeTomeFolioScope, key))
        assertEquals(false, homeLaunchCardOriginIsCurrent(state, com.gruning.mimir.rewrite.feature.library.HomeShelfScope, key))
    }

    @Test
    fun `unpin chooses the nearest surviving typed neighbor instead of item zero`() {
        val first = HomeShelfEntry.Game(HomeShelfItemId.Emulated(GameId("first")))
        val removed = HomeShelfEntry.Tome(snes)
        val nearest = HomeShelfEntry.Game(HomeShelfItemId.Emulated(GameId("nearest")))
        assertEquals(nearest, nearestSurvivingHomeEntry(listOf(first, removed, nearest), removed))
    }

    @Test
    fun `dynamic Tome grid registers incoming identities before removing stale focus`() {
        val authority = RecordingFocusAuthority()
        val registry = HomeTomeLibraryFocusRegistry()
        val old = HomeTomeLibraryEntry.Tome(tome(oldCustom, "Old"), false, HomeTomeLibrarySection.CUSTOM)
        val new = HomeTomeLibraryEntry.Tome(tome(newCustom, "New"), false, HomeTomeLibrarySection.CUSTOM)
        val platform = HomeTomeLibraryEntry.Tome(tome(snes, "SNES"), false, HomeTomeLibrarySection.PLATFORM)
        registry.update(authority, homeTomeLibraryFocusSlots(listOf(old, new, HomeTomeLibraryEntry.CreateCustom)))
        authority.events.clear()

        registry.update(authority, homeTomeLibraryFocusSlots(listOf(new, platform, HomeTomeLibraryEntry.CreateCustom)))

        val firstRemoval = authority.events.indexOfFirst { it.startsWith("unregister:") }
        val lastRegistration = authority.events.indexOfLast { it.startsWith("register:") }
        assertTrue(firstRemoval > lastRegistration)
        assertTrue("unregister:${homeTomeLibraryFocusKey(old).value}" in authority.events)
        assertTrue("unregister:${homeTomeLibraryFocusKey(new).value}" !in authority.events)
    }

    @Test
    fun carouselWindowUsesTheExactHomeSettledLawForwardReverseTerminal() {
        // Forward: the third-and-later focused Tome carries the window (visible = 4).
        assertEquals(0, homeTomeFolioTravelStart(0, 0, 9, 4))
        assertEquals(0, homeTomeFolioTravelStart(0, 2, 9, 4))
        assertEquals(1, homeTomeFolioTravelStart(0, 3, 9, 4))
        assertEquals(2, homeTomeFolioTravelStart(1, 4, 9, 4))
        // Reverse: Home's one-seat look-behind, including focus AT the window start.
        assertEquals(2, homeTomeFolioTravelStart(2, 3, 9, 4))
        assertEquals(1, homeTomeFolioTravelStart(2, 2, 9, 4))
        assertEquals(0, homeTomeFolioTravelStart(2, 1, 9, 4))
        // Terminal seats clamp; a row that fits never travels; one column anchors the target.
        assertEquals(5, homeTomeFolioTravelStart(4, 8, 9, 4))
        assertEquals(0, homeTomeFolioTravelStart(3, 3, 4, 4))
        assertEquals(6, homeTomeFolioTravelStart(2, 6, 9, 1))
    }

    private fun homeOverviewEntry() = HomeTomeLibraryEntry.Tome(
        HomeShelfTome(TomeId.HomeOverview, "HOME SHELF", emptyList(), 0),
        pinned = false,
        section = HomeTomeLibrarySection.HOME,
    )

    /** collectionCount is the number of collection-grid cells (Create is always the final cell when > 0). */
    private fun libraryWithHome(collectionCount: Int): List<HomeTomeLibraryEntry> {
        val home = homeOverviewEntry()
        if (collectionCount <= 0) return listOf(home)
        val platforms = (0 until (collectionCount - 1)).map { index ->
            val id = TomeId.Platform(PlatformId("p$index"))
            HomeTomeLibraryEntry.Tome(tome(id, "P$index"), false, HomeTomeLibrarySection.PLATFORM)
        }
        return listOf(home) + platforms + HomeTomeLibraryEntry.CreateCustom
    }

    private fun tome(id: TomeId, title: String) = HomeShelfTome(id, title, emptyList(), 0)

    private class RecordingFocusAuthority : FocusAuthority {
        val events = mutableListOf<String>()
        override val state: StateFlow<FocusState> = MutableStateFlow(FocusState(FocusScope.Root, null))

        override fun register(key: FocusKey, scope: FocusScope, bounds: FocusBounds) = Unit
        override fun registerVirtual(key: FocusKey, scope: FocusScope, bounds: FocusBounds) {
            events += "register:${key.value}"
        }
        override fun unregister(key: FocusKey) = Unit
        override fun unregisterVirtual(key: FocusKey) {
            events += "unregister:${key.value}"
        }
        override fun requestFocus(key: FocusKey) = Unit
        override fun moveFocus(direction: ControllerAction): Boolean = false
        override fun pushScope(scope: FocusScope) = Unit
        override fun popScope() = Unit
    }
}
