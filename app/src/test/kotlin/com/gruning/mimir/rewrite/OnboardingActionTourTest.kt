package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.After
import org.robolectric.shadows.ShadowLooper

/**
 * The action tour and permission introduction:
 * - a page per action during the library scan (A open, Y edit, X Tomes, the dock, M where the device has one);
 * - A goes forward, B goes back, and Skip ends them, with nothing advancing by itself;
 * - FINISH keeps its list as the recap;
 * - ORIENTATION's paragraph becomes four permission cards, with the privacy promise outside them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OnboardingActionTourTest {
    /**
     * This class runs in the shared sdk 34 Robolectric sandbox, and the copy it reads can write Compose snapshot state outside any
     * composition. Apply those writes and run what they posted, or a later Compose test in the sandbox fails "Compose did not get
     * idle" (CompanionThemeSeedTest, MimirTrackpadPointerStyleTest).
     */
    @After
    fun applyPendingSnapshotWrites() {
        Snapshot.sendApplyNotifications()
        ShadowLooper.idleMainLooper()
    }

    private val res = onboardingTestResources()

    @Test
    fun `the pages are the Home deck in order, in FINISH's words, each with one short line`() {
        val pages = onboardingActionPages(res, teachMButton = true)
        assertEquals(
            listOf(
                OnboardingHintScene.OPEN,
                OnboardingHintScene.EDIT,
                OnboardingHintScene.TOMES,
                OnboardingHintScene.DOCK,
                OnboardingHintScene.M_MENU,
            ),
            pages.map { it.card.scene },
        )
        // FINISH recaps what the pages taught, in the same words. Its hints row is the one pointer past them (action-tour contract): where to find every other control, not a button the pages taught.
        val finish = onboardingFinishLegend(res, teachMButton = true).map { it.action }
            .filterNot { it == res.getString(R.string.app_onboarding_hint_show_hints) }
            .toSet()
        assertTrue(finish.all { action -> pages.any { it.card.action == action } })
        pages.forEach { page ->
            assertTrue(page.card.scene.name, page.detail.isNotBlank())
            // Action descriptions use at most ten words; OnboardingScreenFitTest checks that they fit.
            assertTrue(page.detail, page.detail.split(" ").size <= 10)
            // every line says where the button works.
            assertTrue(page.detail, listOf("Home", "Library", "Anywhere").any { page.detail.contains(it) })
            // 2026-09-28 (R8): a line that says where an action works starts with the place.
            assertTrue(
                page.detail,
                listOf("On your Home shelf", "From your Home shelf", "Anywhere").any { page.detail.startsWith(it) },
            )
        }
        assertTrue(
            "Tomes open from Home's shelf only (in the Library X means Favorites)",
            pages.first { it.card.scene == OnboardingHintScene.TOMES }.detail.startsWith("From your Home shelf"),
        )
        assertEquals(4, onboardingActionPages(res, teachMButton = false).size)
        assertTrue(onboardingActionPages(res, teachMButton = false).none { it.card.scene == OnboardingHintScene.M_MENU })
    }

    @Test
    fun `A turns forward and ends after the last page, B turns back but never before the first`() {
        val tour = OnboardingActionTourState().apply { pageCount = 3 }
        assertFalse("B on the first page does nothing: LIBRARY's Back is forward-only", tour.back())
        tour.next()
        tour.next()
        assertEquals(2, tour.page)
        assertTrue(tour.back())
        assertEquals(1, tour.page)
        assertEquals(2, tour.previousPage)
        tour.next()
        tour.next()
        assertTrue("A on the last page ends them", tour.finished)
        val skipped = OnboardingActionTourState().apply { pageCount = 5 }
        skipped.skip()
        assertTrue(skipped.finished)
    }

    @Test
    fun `the pages show from the pick until they end, and never for a second scan`() {
        val tour = OnboardingActionTourState()
        assertFalse(onboardingActionTourShows(OnboardingLibraryStatus.IDLE, tour))
        assertTrue(onboardingActionTourShows(OnboardingLibraryStatus.SCANNING, tour))
        assertFalse("a finished scan never shows pages that never started", onboardingActionTourShows(OnboardingLibraryStatus.DONE, tour))
        tour.start()
        assertTrue("a scan that finishes first waits for the pages", onboardingActionTourShows(OnboardingLibraryStatus.DONE, tour))
        assertTrue("so does one that fails", onboardingActionTourShows(OnboardingLibraryStatus.FAILED, tour))
        tour.skip()
        assertFalse(onboardingActionTourShows(OnboardingLibraryStatus.SCANNING, tour))
        assertFalse(onboardingActionTourShows(OnboardingLibraryStatus.SCANNING, null))
    }

    @Test
    fun `LIBRARY's views follow the scan, the pages winning while they show`() {
        assertEquals(OnboardingLibraryView.ASK, onboardingLibraryView(OnboardingLibraryStatus.IDLE, pagesShow = false))
        assertEquals(OnboardingLibraryView.ASK, onboardingLibraryView(OnboardingLibraryStatus.FAILED, pagesShow = false))
        assertEquals(OnboardingLibraryView.PAGES, onboardingLibraryView(OnboardingLibraryStatus.SCANNING, pagesShow = true))
        assertEquals(OnboardingLibraryView.PAGES, onboardingLibraryView(OnboardingLibraryStatus.DONE, pagesShow = true))
        assertEquals(OnboardingLibraryView.BUILDING, onboardingLibraryView(OnboardingLibraryStatus.SCANNING, pagesShow = false))
        assertEquals(OnboardingLibraryView.RESULT, onboardingLibraryView(OnboardingLibraryStatus.DONE, pagesShow = false))
        assertEquals(OnboardingActionTourKeys, onboardingLibraryFocusKeys(OnboardingLibraryView.PAGES))
        assertTrue(onboardingLibraryFocusKeys(OnboardingLibraryView.BUILDING).isEmpty())
    }

    @Test
    fun `on the pages, A on Next turns, B turns back, and A on Skip ends them`() {
        val tour = OnboardingActionTourState().apply { pageCount = 5 }
        val library = OnboardingLibraryState().apply { status = OnboardingLibraryStatus.SCANNING }
        val authority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) }
        authority.register(OnboardingActionTourNextKey, OnboardingScope, FocusBounds(0f, 0f, 100f, 40f))
        authority.register(OnboardingActionTourSkipKey, OnboardingScope, FocusBounds(120f, 0f, 220f, 40f))
        authority.requestFocus(OnboardingActionTourNextKey)
        var chose = 0
        fun press(action: ControllerAction) = dispatch(authority, library, tour, action) { chose += 1 }

        press(ControllerAction.CONFIRM)
        assertEquals(1, tour.page)
        press(ControllerAction.CANCEL)
        assertEquals(0, tour.page)
        press(ControllerAction.NAV_RIGHT)
        assertEquals(OnboardingActionTourSkipKey, authority.state.value.focused)
        press(ControllerAction.CONFIRM)
        assertTrue(tour.finished)
        assertEquals("A on the pages never reopens the folder picker", 0, chose)
    }

    @Test
    fun `a failed scan ends the pages on the next press, onto the failure`() {
        val tour = OnboardingActionTourState().apply {
            pageCount = 5
            start()
        }
        val library = OnboardingLibraryState().apply { status = OnboardingLibraryStatus.FAILED }
        val authority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) }
        dispatch(authority, library, tour, ControllerAction.CONFIRM) {}
        assertTrue(tour.finished)
        assertEquals(OnboardingLibraryView.ASK, onboardingLibraryView(library.status, onboardingActionTourShows(library.status, tour)))
    }

    @Test
    fun `ORIENTATION's four cards are the approved words, with no keycap, and count the screens after it`() {
        val deck = onboardingPermissionDeck(res, steps = 6)
        assertEquals(
            listOf(
                "One screen per permission",
                "Choose Allow, or skip with Not now",
                "Android may ask you to turn it on",
                // these permissions live in Android's settings, not Mimir's.
                "Change them later in Android's settings",
            ),
            deck.map { it.action },
        )
        assertTrue(deck.all { it.key == null })
        assertEquals(6, deck.first().steps)
        assertEquals("One screen per permission", onboardingHintCardDescription(res, deck.first()))
        // The promise is on the page, never a card a player could skip past.
        assertTrue(res.getString(R.string.app_onboarding_access_orientation_promise).contains("offline"))
        assertTrue(deck.none { it.action.contains("location") })
    }

    @Test
    fun `on ORIENTATION, LEFT and RIGHT turn the cards and focus stays on Continue`() = runBlocking {
        val carousel = OnboardingHintCarouselState()
        val authority = DefaultFocusAuthority().apply { pushScope(OnboardingScope) }
        val accessReview = OnboardingAccessReviewState().apply { goToScreen(OnboardingAccessScreenId.ORIENTATION) }
        val turned = async { withTimeoutOrNull(2_000) { carousel.turns.first() } }
        yield()
        dispatchOnboarding(
            authority = authority,
            step = OnboardingStep.ACCESS,
            profile = OnboardingProfileState(),
            accessReview = accessReview,
            focusIntent = OnboardingFocusIntentState(),
            library = OnboardingLibraryState(),
            access = OnboardingAccessUi(usageAvailable = true, usageGranted = false, homeRoleAvailable = true, homeRoleHeld = false),
            hintCarousel = carousel,
            artworkAccount = null,
            settlement = OnboardingSettlementUi(phase = OnboardingSettlementPhase.READY, title = "Ready", detail = "Ready"),
            action = ControllerAction.NAV_RIGHT,
            onAdvance = {},
            onCommitName = {},
            onRequestFilesAccess = {},
            onRequestUsageAccess = {},
            onRequestHomeRole = {},
            onChooseFolder = {},
            onOpenArtworkSetup = {},
            onOpenSteamGridDbKey = {},
            onRetrySettlement = {},
            onContinueLocally = {},
            onApplyTheme = {},
        )
        assertEquals(1, turned.await())
        assertEquals(OnboardingAccessScreenId.ORIENTATION, accessReview.currentScreen)
        assertNull("the turn moved no focus", authority.state.value.focused)
    }

    private fun dispatch(
        authority: DefaultFocusAuthority,
        library: OnboardingLibraryState,
        tour: OnboardingActionTourState,
        action: ControllerAction,
        onChooseFolder: () -> Unit,
    ): Boolean = dispatchOnboarding(
        authority = authority,
        step = OnboardingStep.LIBRARY,
        profile = OnboardingProfileState(),
        accessReview = OnboardingAccessReviewState(),
        focusIntent = OnboardingFocusIntentState(),
        library = library,
        access = OnboardingAccessUi(usageAvailable = true, usageGranted = false, homeRoleAvailable = true, homeRoleHeld = false),
        actionTour = tour,
        artworkAccount = null,
        settlement = OnboardingSettlementUi(phase = OnboardingSettlementPhase.READY, title = "Ready", detail = "Ready"),
        action = action,
        onAdvance = {},
        onCommitName = {},
        onRequestFilesAccess = {},
        onRequestUsageAccess = {},
        onRequestHomeRole = {},
        onChooseFolder = onChooseFolder,
        onOpenArtworkSetup = {},
        onOpenSteamGridDbKey = {},
        onRetrySettlement = {},
        onContinueLocally = {},
        onApplyTheme = {},
    )
}
