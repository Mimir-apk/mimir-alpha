package com.gruning.mimir.rewrite.feature.companion

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.Offset

/**
 * What the lattice's touch handling did to a tile's press (Thor Bottom revamp B5, policy R4), read off two machine states and the effects between them.
 *
 * **Why the machine, not the tile.** A production lattice tile has no tap node of its own (`onTap = null`): one detector on the drag root owns every finger, and the
 * [CompanionDragStateMachine] decides what it was (a tap, a lift, a pan the pager took). So the tile's press response is driven from that decision, which is the only
 * place that knows the difference between a press that activates and one that becomes something else:
 *  - [Down]: the machine took a finger down on a tile ([CompanionDragPhase.Pressed] with a tile id);
 *  - [Up]: that press ended in [CompanionDragEffect.ActivateTile], the tap that opens or launches the tile;
 *  - [Cancel]: that press ended any other way: it became a lift, a pan the pager took, a second finger, or a long hold lifted without a drag.
 */
internal sealed interface CompanionTilePress {
    val tileId: String

    data class Down(override val tileId: String) : CompanionTilePress

    data class Up(override val tileId: String) : CompanionTilePress

    data class Cancel(override val tileId: String) : CompanionTilePress
}

internal fun companionTilePresses(
    before: CompanionDragPhase,
    after: CompanionDragPhase,
    effects: List<CompanionDragEffect>,
): List<CompanionTilePress> {
    val was = before as? CompanionDragPhase.Pressed
    val now = after as? CompanionDragPhase.Pressed
    val out = ArrayList<CompanionTilePress>(2)
    // The press that was down has ended unless the machine is still holding the very same press ON THE VERY SAME TILE. A press is (token, tile): the model changing under a
    // held finger can lose the tile (Pressed(A) -> Pressed(null)) or find one (null -> B) with the token unchanged, and either is the end of one press and, for a found tile, the
    // start of another. Keyed on the token alone, A was never cancelled and B never went down.
    if (was?.tileId != null && (now == null || now.pressToken != was.pressToken || now.tileId != was.tileId)) {
        val activated = effects.any { it is CompanionDragEffect.ActivateTile && it.tileId == was.tileId }
        out += if (activated) CompanionTilePress.Up(was.tileId) else CompanionTilePress.Cancel(was.tileId)
    }
    // Down only while the finger is still HELD: a press that has a pending release (the finger lifted while the geometry was still partial) is over, and a tile it resolves to
    // afterwards goes straight to Up or Cancel with no Down, so the release can never reopen a press that nothing is left to close.
    if (now?.tileId != null && now.pendingUp == null && (was == null || was.pressToken != now.pressToken || was.tileId != now.tileId)) out += CompanionTilePress.Down(now.tileId)
    return out
}

/**
 * One [MutableInteractionSource] per lattice tile, fed from [companionTilePresses]. The tile reads its source for `mimirPressResponse` (so it is drawn in while a finger
 * holds it) and nothing else: no focus, no rim. Emission is `tryEmit` from the gesture's own dispatch, so it adds nothing to the input path.
 */
internal class CompanionTilePressBus {
    private val sources = HashMap<String, MutableInteractionSource>()
    private val open = HashMap<String, PressInteraction.Press>()

    fun sourceFor(tileId: String): MutableInteractionSource = sources.getOrPut(tileId) { MutableInteractionSource() }

    fun apply(presses: List<CompanionTilePress>) {
        presses.forEach { press ->
            val source = sourceFor(press.tileId)
            when (press) {
                is CompanionTilePress.Down -> {
                    // A press that was never closed is closed first, so nothing is left dangling on the source.
                    open.remove(press.tileId)?.let { source.tryEmit(PressInteraction.Cancel(it)) }
                    val next = PressInteraction.Press(Offset.Zero)
                    open[press.tileId] = next
                    source.tryEmit(next)
                }
                is CompanionTilePress.Up -> open.remove(press.tileId)?.let { source.tryEmit(PressInteraction.Release(it)) }
                is CompanionTilePress.Cancel -> open.remove(press.tileId)?.let { source.tryEmit(PressInteraction.Cancel(it)) }
            }
        }
    }

    /** The surface is leaving: whatever is still held lets go, so a response is never left drawn in on a source that outlives it. */
    fun cancelAll() {
        open.entries.toList().forEach { (id, press) -> sourceFor(id).tryEmit(PressInteraction.Cancel(press)) }
        open.clear()
    }
}
