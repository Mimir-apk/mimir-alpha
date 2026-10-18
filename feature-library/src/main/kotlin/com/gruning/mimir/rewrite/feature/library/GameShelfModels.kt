package com.gruning.mimir.rewrite.feature.library

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.semantics.SemanticsPropertyKey
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.library.ShelfFace

/**
 * One seat's worth of content on a [GameShelf]. The shelf is told what to paint and never looks the identity up: a
 * game arrives with its [ShelfFace] already chosen, so the shelf cannot re-derive a shape from whichever field happened
 * to be set (that re-derivation is what painted a square pick as a box before).
 *
 * The caller keeps its own row type and maps a tapped item back through [GameShelfItem.focusKey].
 */
internal sealed interface GameShelfItem {
    val focusKey: FocusKey
    val title: String

    /**
     * A game tile.
     *
     * @param face what the tile paints, its picture and shape together ([ShelfFace.None] paints no art).
     * @param platformIdValue the platform shelfmark's identity, or empty for a game with no console (an Android game gets no
     *   plate). Painted only where the shelf is asked to ([GameShelf]'s `showShelfmark`).
     * @param marks the on-tile marks. The Library's shelf paints them ([GameShelf]'s `paintMarks`); Home has
     *   none, so its items carry [GameShelfMarks.None] and the shelf is not asked.
     * @param stableId the identity a hide's picture is taken under ([LibraryShatterStage]): the game's own id, which is not the
     *   focus key (that one is a path in the surface's scope). Defaults to the focus key.
     * @param artPending the absence of a cover is not established yet (the resolver has not finished): the tile then paints
     *   nothing, because the runed book is a claim that the game has no art and may only be made once that is known. False on Home, whose rows settle before they are listed.
     */
    data class Game(
        override val focusKey: FocusKey,
        override val title: String,
        val face: ShelfFace,
        val platformIdValue: String = "",
        val marks: GameShelfMarks = GameShelfMarks.None,
        val stableId: String = focusKey.value,
        val artPending: Boolean = false,
    ) : GameShelfItem

    /** A Tome seat. Home only; the Library never passes one. */
    data class Tome(
        override val focusKey: FocusKey,
        val tome: HomeShelfTome,
    ) : GameShelfItem {
        override val title: String get() = tome.title
    }
}

/** The two marks a game tile can carry: the favourite rune, and the badge that says a title collapses several discs. */
internal data class GameShelfMarks(
    val favorite: Boolean = false,
    val collapseBadge: String? = null,
) {
    companion object {
        val None = GameShelfMarks()
    }
}

/**
 * One game leaving a shelf: the Library's walk-32g/h departure, told to the shelf in seats. The leaving game is no longer in the
 * shelf's items; [item] is what it painted when the player last saw it and [index] the place it left from. [gap] runs 1 to 0:
 * the games from [index] on stand one seat further along at 1, exactly where the old list put them, and at 0 where the shorter
 * list does, so the first frame is the last frame of the old list. [presence] is how much of the leaving tile still paints
 * (1 to 0 for a dissolve), and [paintsLeavingTile] is false for a shatter, whose picture the overlay already took and breaks.
 * [ringReveal] holds the selection ring back until the gap has shut (a shatter's survivor wears no ring while the hole is open);
 * null leaves it at full strength, as a dissolve does. All three are read in layout and draw lambdas, never in composition, so a
 * departure never recomposes the tiles it moves.
 */
@Stable
internal class GameShelfDeparture(
    val item: GameShelfItem.Game,
    val index: Int,
    val paintsLeavingTile: Boolean,
    val gap: () -> Float,
    val presence: () -> Float,
    val ringReveal: (() -> Float)? = null,
)

/**
 * Where one surface's shelf window last stood. A shelf is composed afresh on every return to its screen;
 * seeding its window from here puts the focused tile back in the seat it left from. Process memory.
 *
 * One INSTANCE per calling surface -- Home owns one, the Library another -- because a Library visit recording on a shared memory
 * would displace Home's key, and Home would start at seat 0 again on the way back. The [key] then tells states apart within a
 * surface (Home: surface epoch and label; the Library: platform, favourites filter and search), and another key starts afresh.
 *
 * [capacity] is how many states one surface remembers, the least recently used going first. Home has one state and keeps the
 * default, a single slot. The Library pages between platforms with L2/R2 (PS1, N64, PS1), and each must come back in the seat it
 * was left in, so it remembers many: a single slot forgot every platform but the last (regression: the window restarted at 0 and
 * a focused game past the third stood in the wrong seat).
 */
internal class GameShelfWindowMemory(private val capacity: Int = 1) {
    init {
        require(capacity >= 1) { "a memory holds at least one state" }
    }

    // Access order: a seed counts as a use, so a state that is being returned to outlives one that is not.
    private val starts = object : LinkedHashMap<Any, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, Int>): Boolean = size > capacity
    }

    fun seed(key: Any): Int? = starts[key]

    fun record(key: Any, windowStart: Int) {
        starts[key] = windowStart
    }
}

/**
 * The names a shelf wears for tests and tooling, all derived from one [prefix]: the tag of each tile (the item's focus key follows
 * it), of the selected seat, of the stationary ring and of the two continuation runes; the semantics keys a tile publishes its
 * presentation in; and the labels of the shelf's animations. Two shelves on one screen, or a second surface's tests, tell theirs
 * apart by the prefix.
 *
 * [Home] is what a shelf wears when it is handed nothing, and it is exactly what Home's tags, keys and labels have always been
 * (`home_shelf_tile_`, `HomeShelfPresentationAlpha`, `home-emphasis-seat`, ...), so every Home pin and test reads what it did. Build
 * another with `GameShelfIds("library")` ONCE and share it between the screen and its tests: each build mints its own semantics
 * keys, and a key is matched by identity.
 */
@Immutable
internal class GameShelfIds private constructor(
    val prefix: String,
    val presentationAlpha: SemanticsPropertyKey<Float>,
    val presentationScale: SemanticsPropertyKey<Float>,
    val visualSlot: SemanticsPropertyKey<Float>,
) {
    constructor(prefix: String) : this(
        prefix = prefix,
        presentationAlpha = SemanticsPropertyKey("${prefix.keyName()}ShelfPresentationAlpha"),
        presentationScale = SemanticsPropertyKey("${prefix.keyName()}ShelfPresentationScale"),
        visualSlot = SemanticsPropertyKey("${prefix.keyName()}ShelfVisualSlot"),
    )

    /** A tile's tag is this followed by its item's focus key. */
    val tilePrefix: String = "${prefix}_shelf_tile_"

    /** The tile of a game that has just left the list wears this instead of [tilePrefix]: a probe for the game's tile finds nothing. */
    val leavingTilePrefix: String = "${prefix}_shelf_tile_leaving_"
    val selectedSlotTag: String = "${prefix}_shelf_selected_slot"
    val ringTag: String = "${prefix}_shelf_stationary_ring"
    val leftRuneTag: String = "${prefix}_shelf_continuation_left"
    val rightRuneTag: String = "${prefix}_shelf_continuation_right"
    val emphasisSeatLabel: String = "$prefix-emphasis-seat"
    val leftRuneLabel: String = "$prefix-left-continuation"
    val rightRuneLabel: String = "$prefix-right-continuation"

    companion object {
        /** Home's own: the tags and keys its pins and tests have always read. */
        val Home = GameShelfIds(
            prefix = "home",
            presentationAlpha = HomeShelfPresentationAlphaSemantics,
            presentationScale = HomeShelfPresentationScaleSemantics,
            visualSlot = HomeShelfVisualSlotSemantics,
        )

        private fun String.keyName(): String = replaceFirstChar { it.uppercase() }
    }
}
