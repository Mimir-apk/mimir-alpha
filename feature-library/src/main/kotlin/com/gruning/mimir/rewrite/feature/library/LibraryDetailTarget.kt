package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.GameRow

/** A launchable Library item that is eligible for the Play/Add detail surface. */
sealed interface LibraryDetailTarget {
    val itemId: HomeShelfItemId

    /** The exact Library key that opened detail and must regain focus when detail closes. */
    val sourceFocusKey: FocusKey

    data class Emulated(
        val game: GameRow,
        override val itemId: HomeShelfItemId.Emulated = HomeShelfItemId.Emulated(game.gameId),
        override val sourceFocusKey: FocusKey = gameKey(game),
    ) : LibraryDetailTarget {
        init {
            require(itemId.gameId == game.gameId) { "Emulated detail identity must match its game row" }
        }
    }

    data class AndroidGame(
        val row: NativeShelfRow,
        override val itemId: HomeShelfItemId.AndroidGame = HomeShelfItemId.AndroidGame(
            AndroidPackageName(row.packageName),
        ),
        override val sourceFocusKey: FocusKey = nativeShelfKey(row.packageName),
    ) : LibraryDetailTarget {
        init {
            require(row.isGame) { "Android apps cannot open Library game detail" }
            require(row.packageName.isNotBlank()) { "Android game package name must not be blank" }
            require(itemId.packageName.value == row.packageName) {
                "Android detail identity must match its native row"
            }
        }
    }
}

/**
 * The source captured before [GameDetailScope] is pushed. Keeping the scope and key together makes close
 * deterministic even after focus has moved between detail actions.
 */
data class LibraryDetailSession(
    val target: LibraryDetailTarget,
    val originScope: FocusScope,
    val originFocusKey: FocusKey,
) {
    init {
        require(originFocusKey == target.sourceFocusKey) {
            "Detail origin must match the target's source focus key"
        }
    }
}

/**
 * What the Library's launch card is told about its game besides the game itself: whether it is a favourite, and the game's session time. The card's own button row carries
 * no favourite button: favourite lives in the Y menu on both doors , where the row reads "Add favorite" or "Remove favorite" from the game's own favourite
 * state ([isFavorite] for an Android game, the live row's for an emulated one).
 */
data class LibraryDetailActionState(
    val target: LibraryDetailTarget,
    val isOnShelf: Boolean,
    val revision: Long = 0L,
    /**
     * The game's total session time from the elapsed store, or null when none is tracked: the SESSION TIME fact . Threaded by the app (the same total the Home
     * card reads), because this module never reaches into an app store.
     */
    val externalSessionElapsedMillis: Long? = null,
) {
    init {
        require(revision >= 0L) { "Detail action revision must not be negative" }
    }

    val itemId: HomeShelfItemId
        get() = target.itemId

    val isFavorite: Boolean
        get() = isOnShelf
}

val LibraryDetailPlayKey = FocusKey("library/detail/play")
val LibraryDetailOpenWithKey = FocusKey("library/detail/openwith")
val LibraryDetailAchievementsKey = FocusKey("library/detail/achievements")
val LibraryDetailDescriptionKey = FocusKey("library/detail/description")

/** The Library card's Restart: the same action as Home's ([HomeLaunchCardRestartKey]), registered only while its game is parked behind Home. */
val LibraryDetailRestartKey = FocusKey("library/detail/restart")

/**
 * The manual-rename affordance  on the Library detail launch card — the SAME
 * key family as [HomeLaunchCardTitleEditKey], gated to [LibraryDetailTarget.Emulated] only: an Android
 * game/app carries no [com.gruning.mimir.rewrite.core.model.GameId] a rename can target.
 */
val LibraryDetailTitleEditKey = FocusKey("library/detail/title-edit")

/**
 * The Library door's keys for the ONE launch card. The card, its dispatcher ([dispatchHomeLaunchCard]), its bounds solver ([homeLaunchCardActionBounds]) and its
 * registrar ([registerHomeLaunchCardActions]) are shared with Home's; only the scope and these keys differ.
 */
val LibraryLaunchCardFocusContract = MimirLaunchCardFocusContract(
    scope = GameDetailScope,
    playKey = LibraryDetailPlayKey,
    restartKey = LibraryDetailRestartKey,
    openWithKey = LibraryDetailOpenWithKey,
    achievementsKey = LibraryDetailAchievementsKey,
    descriptionKey = LibraryDetailDescriptionKey,
    titleEditKey = LibraryDetailTitleEditKey,
)

internal fun captureLibraryDetailSession(
    authority: FocusAuthority,
    target: LibraryDetailTarget,
): LibraryDetailSession? {
    val focus = authority.state.value
    val originKey = focus.focused ?: return null
    if (originKey != target.sourceFocusKey) return null
    return LibraryDetailSession(
        target = target,
        originScope = focus.scope,
        originFocusKey = originKey,
    )
}
