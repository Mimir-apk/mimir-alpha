package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Screen 2's OWN navigation, and the library handle it browses with.
 *
 * **Display routing: the Home shelf lives
 * on screen 1 and the LIBRARY lives on screen 2.** Tapping a console tile on the companion display opens
 * that platform's shelf ON THE COMPANION DISPLAY, and a game there raises the launch card.
 *
 * This replaces the earlier primary-navigation-only route in [CompanionCommandState], where a console tap
 * emitted [CompanionCommandState.Command.OpenGamelistOnMain] and drove screen 1 from screen 2.
 *
 * Screen 2 still does not MUTATE the library. It reads.
 *
 * **Why a process-scoped object.** Same shape and same reason as [CompanionCommandState] and
 * `CompanionRecentsState`: the Presentation window and the primary Activity never hold a reference to one
 * another, and the Presentation is recreated whenever the display is rebound. A route parked in the
 * Presentation's composition would reset to the lattice every time the window was rebuilt — which happens
 * on every render-operation change — so the player would lose their place for reasons that have nothing to
 * do with them.
 *
 * **Why the repository is published here rather than passed down the constructor.** `MimirCompanionPresentation`
 * is built by `CompanionDisplayController`, which has an Activity and no library. The composition that DOES
 * hold one (`MimirAppHost`, where `LocalLibraryRepository` is provided) cannot reach across into another
 * window's composition, because the Presentation owns its own composition root. One process-scoped handle,
 * written once at the app root, is the narrowest join between them. [LibraryRepository] lives in
 * `:core-model`, so nothing about this widens the module graph.
 */
object CompanionLibraryRoute {

    /** Where screen 2 is. Two destinations; the launch card is a surface WITHIN the platform shelf. */
    sealed interface Destination {
        /** The 3DS-style lattice: consoles, Android apps, folders. Screen 2's root. */
        data object Lattice : Destination

        /**
         * One platform's games, browsed on screen 2.
         *
         * [label] is carried rather than looked up because the tile that was tapped already knows it, and
         * the alternative — resolving a platform id to a name inside the Presentation — reintroduces the
         * empty-sweep bug the tile's own `onPlatformTap` comment records: a tap that lands before the
         * catalog sweep finishes would render a header with no name.
         */
        data class Platform(val platformIdValue: String, val label: String) : Destination
    }

    private val mutableDestination = MutableStateFlow<Destination>(Destination.Lattice)
    val destination: StateFlow<Destination> = mutableDestination.asStateFlow()

    /**
     * The library screen 2 reads. Null until the app root publishes it, and the companion renders its
     * lattice as before while it is — a screen 2 that came up before the repository did shows the root,
     * never an empty shelf that claims the platform has no games.
     */
    @Volatile
    var repository: LibraryRepository? = null
        private set

    fun publishRepository(repository: LibraryRepository) {
        this.repository = repository
    }

    fun openPlatform(platformIdValue: String, label: String) {
        if (platformIdValue.isBlank()) return
        mutableDestination.value = Destination.Platform(platformIdValue, label)
    }

    /** Back to the lattice. Idempotent, so a double-tap on the back affordance cannot go anywhere odd. */
    fun backToLattice() {
        mutableDestination.value = Destination.Lattice
    }

    /** The typed id for the current platform destination, or null at the lattice. */
    fun currentPlatformId(): PlatformId? =
        (destination.value as? Destination.Platform)?.let { PlatformId(it.platformIdValue) }
}
