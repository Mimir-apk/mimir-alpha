package com.gruning.mimir.rewrite.feature.library

import android.content.res.Resources
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape

internal const val HomeTomeFolioColumns = 6
internal const val HomeTomeFolioRows = 3
const val HomeTomeFolioPageSize = HomeTomeFolioColumns * HomeTomeFolioRows
internal const val HomeTomeTouchEdgeDwellMs = 420L
internal const val HomeTomeTouchDropSettleMs = 120L
/** Bounded wait for the coordinator to publish the exact lift transaction + carried identity. */
internal const val HomeTomeTouchLiftAwaitMs = 500L
internal const val HomeTomeTouchPickupMs = 140L

internal enum class HomeTomeTouchPickup { PENDING, ARMED, REJECTED }

internal fun homeTomeTouchPickupDecision(
    elapsedMs: Long,
    distancePx: Float,
    touchSlopPx: Float,
    pressedPointerCount: Int,
    stillPressed: Boolean,
): HomeTomeTouchPickup {
    if (!stillPressed || pressedPointerCount > 1) return HomeTomeTouchPickup.REJECTED
    // Before the deliberate hold owns the gesture, crossing Android's full touch slop belongs to
    // ordinary scrolling. Never consume or reinterpret that movement as a drag pickup.
    if (elapsedMs < HomeTomeTouchPickupMs && distancePx >= touchSlopPx.coerceAtLeast(1f)) {
        return HomeTomeTouchPickup.REJECTED
    }
    if (elapsedMs >= HomeTomeTouchPickupMs) return HomeTomeTouchPickup.ARMED
    return HomeTomeTouchPickup.PENDING
}

/** Stable identity is preferred across a Tome close/reopen; the seat is only a deterministic fallback. */
data class HomeTomeFolioFocusMemory(
    val itemId: HomeShelfItemId?,
    val seatIndex: Int,
    val wasEmptySeat: Boolean = false,
)

fun homeTomeFolioRestoreFocusIndex(
    memory: HomeTomeFolioFocusMemory?,
    memberSlots: List<HomeShelfItemId?>,
): Int {
    if (memberSlots.isEmpty()) return 0
    memory?.itemId?.let { itemId ->
        memberSlots.indexOf(itemId).takeIf { it >= 0 }?.let { return it }
    }
    memory?.takeIf { it.wasEmptySeat && it.seatIndex in memberSlots.indices }
        ?.seatIndex
        ?.let { return it }
    memory?.seatIndex?.let { rememberedSeat ->
        memberSlots.indices
            .filter { memberSlots[it] != null }
            .minWithOrNull(compareBy<Int> { kotlin.math.abs(it - rememberedSeat) }.thenBy { it })
            ?.let { return it }
    }
    return memberSlots.indexOfFirst { it != null }.takeIf { it >= 0 } ?: 0
}

private const val HomeTomeVirtualCellPitch = 1000f
private const val HomeTomeVirtualCellSize = 500f
internal const val HomeTomeFolioMetadataStartFraction = 0.52f

/** Focus rests at the third visible column before the strip begins to travel (policy, Home law). */
internal const val HomeTomeFolioTravelLead = 2

/**
 * One-row vertical stride for continuous sheet travel. Presentation animates the row window by this
 * distance; discrete 18-seat pageMotion handoffs are not used for Folio painting.
 */
internal fun homeTomeFolioRowStride(
    cellHeight: Float,
    rowGap: Float,
): Float = cellHeight + rowGap

/**
 * Legacy page-travel distance kept for inert compatibility helpers. Continuous Folio presentation
 * animates [homeTomeFolioRowStride] instead of a multi-row page handoff.
 */
internal fun homeTomeFolioPageTravelDistance(
    cellHeight: Float,
    rowGap: Float,
    visibleRows: Int,
): Float = homeTomeFolioRowStride(cellHeight, rowGap) * (visibleRows.coerceAtLeast(1) - 1)

/** Column inside the continuous 6-wide Folio sheet. */
internal fun homeTomeFolioColumnOfIndex(index: Int): Int {
    val safe = index.coerceAtLeast(0)
    return safe % HomeTomeFolioColumns
}

/** Global sheet row = index / 6. */
internal fun homeTomeFolioRowOfIndex(index: Int): Int =
    index.coerceAtLeast(0) / HomeTomeFolioColumns

/** Total global rows for a sparse seat list. */
internal fun homeTomeFolioTotalRows(slotCount: Int): Int =
    if (slotCount <= 0) 0 else (slotCount + HomeTomeFolioColumns - 1) / HomeTomeFolioColumns

/** Seat index from global sheet coordinates: index = globalRow * 6 + column. */
internal fun homeTomeFolioIndexOf(column: Int, globalRow: Int): Int {
    val safeColumn = column.coerceIn(0, HomeTomeFolioColumns - 1)
    return globalRow.coerceAtLeast(0) * HomeTomeFolioColumns + safeColumn
}

/**
 * Compatibility leaf addressing for coordinators that still consume page DTOs. Presentation/touch/
 * carry geometry bypass this and use global [homeTomeFolioIndexOf].
 */
internal fun homeTomeFolioIndexOf(column: Int, row: Int, pageIndex: Int): Int =
    homeTomeFolioIndexOf(
        column = column,
        globalRow = pageIndex.coerceAtLeast(0) * HomeTomeFolioRows +
            row.coerceIn(0, HomeTomeFolioRows - 1),
    )

/** Every Folio page owns the same six horizontal columns; page count never widens the strip. */
internal fun homeTomeFolioColumnCount(@Suppress("UNUSED_PARAMETER") seatCount: Int): Int =
    HomeTomeFolioColumns

/**
 * Home's settled-window law, exactly (HomeShelfGeometry window: minimum displacement, no hysteresis
 * band): moving forward, the window start becomes `focusedColumn - HomeTomeFolioTravelLead` the moment
 * focus crosses the third visible position, so focus settles at visible position 2 and the strip
 * travels with every further step; moving backward, focus left of the window pushes the start to it.
 * Finite clamps at both ends; a strip that fits never travels. Shared by the Folio strip and the Tome
 * Library carousel.
 */
fun homeTomeFolioTravelStart(
    previousStart: Int,
    focusedColumn: Int,
    totalColumns: Int,
    visibleColumns: Int = HomeTomeFolioColumns,
): Int {
    val visible = visibleColumns.coerceAtLeast(1)
    if (totalColumns <= visible) return 0
    val maxStart = totalColumns - visible
    // A one-column window can only ever show the target itself; narrower windows shrink the
    // forward lead so the focused column is always mounted.
    val lead = minOf(HomeTomeFolioTravelLead, visible - 1)
    val pushed = when {
        // Reverse preserves Home's one-seat look-behind (including focus AT the window start),
        // with the single-column fallback anchoring directly on the target.
        focusedColumn <= previousStart ->
            if (visible > 1) focusedColumn - 1 else focusedColumn
        else -> maxOf(previousStart, focusedColumn - lead)
    }
    return pushed.coerceIn(0, maxStart)
}

/** A touch lift fires only once the exact lifted seat owns settled painted focus. */
internal fun homeTomeTouchLiftReady(focusedIndex: Int, liftIndex: Int, focusSettled: Boolean): Boolean =
    focusSettled && focusedIndex == liftIndex

/** What a single tap on a painted Tome member seat does. See [homeTomeFolioMemberTapAction]. */
internal enum class HomeTomeFolioMemberTapAction {
    /** Focus the seat and stop — the seat is not yet the settled painted Arrange target. */
    FOCUS_ONLY,

    /** Focus the seat and open its launch detail. BROWSE only. */
    FOCUS_AND_OPEN,

    /** Focus the seat and hand CONFIRM to the Arrange reducer (lift / drop). ARRANGE only. */
    FOCUS_AND_ARRANGE_CONFIRM,
}

/**
 * **T-1, the Tome folio's touch grammar.** , scoped by him to the Home shelf and the
 * gamelist.
 *
 * In **BROWSE** the seat opens on the FIRST tap. The seat used to require settled painted focus before it
 * would activate — a comment in `HomeTomeFolio` called this *"touch obeys the controller's exact-target
 * law"* — so opening a game from inside a Tome cost two taps here plus two more on Play: four in total.
 * That law is the controller's, and touch is not a controller: a tap already names its target
 * unambiguously by the pixel it lands on, which is the whole thing focus-then-activate exists to
 * establish for a d-pad. Focus still moves — as a *consequence* of the tap, so the ring stays a truthful
 * receipt of where the action landed — but it is no longer a precondition.
 *
 * **ARRANGE deliberately keeps the settled-target gate, and this asymmetry is declared, not overlooked**
 * (§4 PER-ELEMENT ACTION PARITY permits a declared asymmetry with its reason). Three reasons:
 *  1. Arrange's touch grammar is already **direct manipulation** — press-and-hold lifts a cartridge and
 *     drags it to a seat (`HomeTomeFolio`'s gesture owner). Under the model, a drag is a drag and never
 *     an activation; making a bare tap also lift/drop would give one surface two competing carry idioms.
 *  2. A lift/drop is a *transaction* with a transaction id, an insertion index and a reducer CANCEL path.
 *     One-tap activation of a transaction whose target is resolved from live reducer state is precisely
 *     where a stray tap does damage, and the policy is about *opening a ROM*, not about arranging.
 *  3. The action is reachable by touch either way, at the same cost it has today. Nothing regresses.
 *
 * Pure and total so the branch can be pinned in this module's plain-JUnit test set — `feature-library`
 * has no Compose harness on the unit-test classpath.
 *
 * @param arrangeSettledHere the seat is the settled painted Arrange target (`focusSettled` AND this seat
 *   is the focused index). Only consulted in [HomeTomeFolioMode.ARRANGE].
 */
internal fun homeTomeFolioMemberTapAction(
    mode: HomeTomeFolioMode,
    arrangeSettledHere: Boolean,
): HomeTomeFolioMemberTapAction = when (mode) {
    HomeTomeFolioMode.BROWSE -> HomeTomeFolioMemberTapAction.FOCUS_AND_OPEN
    HomeTomeFolioMode.ARRANGE -> if (arrangeSettledHere) {
        HomeTomeFolioMemberTapAction.FOCUS_AND_ARRANGE_CONFIRM
    } else {
        HomeTomeFolioMemberTapAction.FOCUS_ONLY
    }
}

val HomeTomeFolioScope = FocusScope("home/tome/folio")
val HomeTomeEmptyFocusKey = FocusKey("home/tome/empty")

internal data class HomeTomeFolioHorizontalPartitions(
    val titleLeft: Float,
    val titleWidth: Float,
    val metadataLeft: Float,
    val metadataWidth: Float,
    val footerLegendLeft: Float,
    val footerLegendWidth: Float,
    val closeLeft: Float,
    val closeWidth: Float,
)

internal data class HomeTomeFolioVerticalPartitions(
    val headerTop: Float,
    val titleLineHeight: Float,
    val supportLineHeight: Float,
    val headerHeight: Float,
    val gridTop: Float,
    val gridBottom: Float,
    val continuationTop: Float,
    val footerTop: Float,
    val footerHeight: Float,
)

/** Metric-derived vertical rails. Header, grid, continuation, and footer never share paint space. */
internal fun solveHomeTomeFolioVerticalPartitions(
    panelHeight: Float,
    fontScale: Float,
): HomeTomeFolioVerticalPartitions {
    require(panelHeight > 0f && fontScale > 0f)
    val scale = fontScale.coerceAtLeast(1f)
    val headerTop = 12f
    val titleLineHeight = 19.2f * scale
    val supportLineHeight = 15.6f * scale
    val metricHeader = headerTop + maxOf(36f, titleLineHeight + 2f + supportLineHeight) + 8f
    val headerHeight = maxOf(metricHeader, 60.6f + (scale - 1f) * 32f)
    val footerHeight = 8f + 17.6f * scale + 8f
    val footerTop = (panelHeight - footerHeight).coerceAtLeast(headerHeight + 20f)
    val continuationTop = (footerTop - 16f).coerceAtLeast(headerHeight + 4f)
    val gridTop = (headerHeight + 4f).coerceAtMost(continuationTop)
    return HomeTomeFolioVerticalPartitions(
        headerTop = headerTop,
        titleLineHeight = titleLineHeight,
        supportLineHeight = supportLineHeight,
        headerHeight = headerHeight,
        gridTop = gridTop,
        gridBottom = continuationTop,
        continuationTop = continuationTop,
        footerTop = footerTop,
        footerHeight = footerHeight,
    )
}

/** Pure header/footer partitioning: labels may truncate inside their seat, never paint into a sibling. */
internal fun solveHomeTomeFolioHorizontalPartitions(
    width: Float,
    outer: Float,
    emblemWidth: Float,
    titleInset: Float,
    headerGap: Float,
    footerGap: Float,
    requestedCloseWidth: Float,
): HomeTomeFolioHorizontalPartitions {
    require(width > 0f && outer >= 0f && emblemWidth >= 0f)
    val titleLeft = (outer + emblemWidth + titleInset).coerceIn(0f, width - 1f)
    // Platform Tome titles are compact acronyms; give the changing, potentially long game identity the
    // wider header seat. Both sides remain independently bounded and ellipsize inside their own partition.
    val metadataLeft = maxOf(width * HomeTomeFolioMetadataStartFraction, titleLeft + headerGap + 1f)
        .coerceAtMost((width - outer - 1f).coerceAtLeast(titleLeft + headerGap + 1f))
    val titleWidth = (metadataLeft - headerGap - titleLeft).coerceAtLeast(1f)
    val metadataWidth = (width - outer - metadataLeft).coerceAtLeast(1f)
    val availableFooter = (width - outer * 2f).coerceAtLeast(1f)
    val closeWidth = requestedCloseWidth.coerceIn(1f, availableFooter * 0.32f)
    val closeLeft = width - outer - closeWidth
    val footerLegendLeft = outer
    val footerLegendWidth = (closeLeft - footerGap - footerLegendLeft).coerceAtLeast(1f)
    return HomeTomeFolioHorizontalPartitions(
        titleLeft = titleLeft,
        titleWidth = titleWidth,
        metadataLeft = metadataLeft,
        metadataWidth = metadataWidth,
        footerLegendLeft = footerLegendLeft,
        footerLegendWidth = footerLegendWidth,
        closeLeft = closeLeft,
        closeWidth = closeWidth,
    )
}

data class HomeTomeMember(
    val itemId: HomeShelfItemId,
    val title: String,
    val artworkUri: String? = null,
    val platformId: PlatformId? = null,
    /**
     * The face he wired BY HAND for this game, or null when he has not wired one.
     *
     * Still never fetched and still app-local. Between 2026-08-25 and 2026-09-22 it shared the well
     * with the provider's downloaded render, which the Cartridge Hero switch could promote over it; with
     * that switch gone it is once again the only thing a Tome well can show besides the cover.
     */
    val physicalMediaUri: String? = null,
    /**
     * The shape of [physicalMediaUri]: the game article, a picked cover, or a square.
     *
     * **Defaults PHYSICAL, because that is what the field held until 2026-08-25** — the hand pick came from
     * the cartridge store and could be nothing else. It can now be a picked COVER, so the well is told
     * which it is instead of working it out: [homeTomeFolioMemberIsPhysical] used to compare the resolved
     * uri against this one, an inference of exactly the kind that squared off the launch card's box art
     * the same day. A shape rather than a flag since the unified shelf-face change: the one shelf face has three.
     */
    val wiredFaceShape: ShelfFaceShape = ShelfFaceShape.PHYSICAL,
    /** Existing local Hero Art for this exact game; presentation-only and never fetched on focus. */
    val backdropArtUri: String? = null,
) {
    init {
        require(title.isNotBlank()) { "Tome member title must not be blank" }
        require(itemId !is HomeShelfItemId.AndroidApp) { "Android apps cannot be Tome game members" }
    }

    val focusKey: FocusKey
        get() = homeTomeMemberFocusKey(itemId)
}

fun homeTomeMemberFocusKey(itemId: HomeShelfItemId): FocusKey =
    FocusKey("home/tome/member/${itemId.stableKeyValue()}")

/**
 * The one Tome well/preview art law — FL-1's Shelf ladder, on Tome surfaces.
 *
 * Zero network at render or focus time, as it has always been. Two tiers, each a rule:
 *
 *  1. **the hand pick** ([physicalMediaUri]) — C4, absolute. A player-picked face always takes precedence.
 *  2. **the cover** ([artworkUri]), else the placeholder.
 *
 * **A third tier stood between them from 2026-08-25 to 2026-09-22 and is GONE** — the provider's
 * downloaded disc render, reachable only while the Cartridge Hero switch promoted it. FL-7 added it after
 * a 2026-08-22 report that enrichment downloaded cartridge art a Tome well could not show;
 * removing the switch  removes the only thing that could ever select it, so the
 * parameter went with it rather than sitting unread. The scrape itself is untouched: a disc he picks is
 * tier 1, and the launch card still reaches the downloaded render through `launchCardWiredFace`'s L2.
 */
fun homeTomeWellFace(
    physicalMediaUri: String?,
    wiredFaceShape: ShelfFaceShape,
    artworkUri: String?,
): MimirShelfFace = mimirShelfFace(
    wiredFaceUri = physicalMediaUri,
    wiredFaceShape = wiredFaceShape,
    artworkUri = artworkUri,
)

/** [homeTomeWellFace]'s uri alone, for the callers that only warm a cache and do not paint. */
fun homeTomeWellArtUri(
    physicalMediaUri: String?,
    artworkUri: String?,
): String? = mimirShelfFaceUri(
    wiredFaceUri = physicalMediaUri,
    artworkUri = artworkUri,
)

/**
 * FL-1's Shelf ladder — THE law, for every Shelf surface: Home, Library and inside a Tome.
 *
 * Extracted 2026-08-25 rather than copied. C2 puts all three in one state, and a state whose surfaces
 * each carry their own private ladder is exactly the confusion C1 was written to fix -- it is also how
 * the Home shelf came to have no disc tier at all while the Library had one.
 *
 * Tiers, each a rule:
 *
 *  1. **the hand pick** — C4, absolute. A player-picked face always takes precedence over a default.
 *  2. **the cover.**
 *  3. **nothing.** A game without one wears the placeholder.
 *
 * **Tier 2 used to read "the one kind the switch names", and the reversal underneath it stands.** DL-4
 * once said real art of EITHER kind outranks the placeholder, so a game with a disc and no cover showed
 * its disc; across a library that is what produced the mixed-media inconsistency — the
 * shelf mixed box art and cartridge art game by game, decided by whichever the provider happened to
 * carry. Consistency was chosen over coverage. Removing the Cartridge Hero switch on 2026-09-22 settles
 * WHICH single kind that is: the cover, which is the answer the switch's shipped OFF position already
 * gave, so no device changes what it paints.
 *
 * Falling back WITHIN the kind is untouched and still happens, one layer down in `ArtMediaResolver`:
 * `box-3D → box-2D → box-texture`. That is the "same type" half of the media policy.
 *
 * The TILE ICON is deliberately not a tier here. M-2: it is a different slot that sits ABOVE the face,
 * and folding it in would put a chosen square tile where a cover belongs.
 */
fun mimirShelfFaceUri(
    wiredFaceUri: String?,
    artworkUri: String?,
): String? = mimirShelfFace(
    wiredFaceUri = wiredFaceUri,
    // Irrelevant to WHICH uri wins -- the ladder's tiers do not depend on the shape of the pick, only on
    // whether there is one. Callers that need the shape call [mimirShelfFace] and read both.
    wiredFaceShape = ShelfFaceShape.LEDGE_COVER,
    artworkUri = artworkUri,
).uri

/**
 * A resolved Shelf face and its SHAPE. See [mimirShelfFace] for why they are one return value. [physical] is the old reader's
 * question, derived: only the game article is fitted whole in a square lane.
 */
data class MimirShelfFace(val uri: String?, val shape: ShelfFaceShape) {
    val physical: Boolean get() = shape == ShelfFaceShape.PHYSICAL
}

/**
 * [mimirShelfFaceUri]'s ladder, returning the face AND its kind.
 *
 * **The kind cannot be recovered from the uri downstream, and trying is what broke the launch card.** A
 * hand pick may be either kind and the automatic tier is always a cover, and the resulting URIs are
 * indistinguishable — a `file://` path either way. A consumer that guesses ("non-null means disc") paints
 * a 2D box at the disc's square aspect. So the tier that decided also reports what it decided, and it
 * stays a returned KIND rather than an inference even though only tier 1 can now be physical.
 */
fun mimirShelfFace(
    wiredFaceUri: String?,
    wiredFaceShape: ShelfFaceShape,
    artworkUri: String?,
): MimirShelfFace {
    wiredFaceUri?.let { return MimirShelfFace(it, wiredFaceShape) }
    // A wired face is a face someone chose; everything else is the cover. The Cartridge Hero switch used
    // to be able to name the provider's render here instead, and it was the only thing that could -- so
    // with the switch removed  the automatic tier is the cover, full stop. The
    // old note about "ONE kind and the ladder stops there" was about not MIXING kinds across a shelf,
    // which one unconditional kind satisfies by construction.
    return MimirShelfFace(artworkUri, ShelfFaceShape.LEDGE_COVER)
}

/** Stable spatial identity for an intentionally empty Arrange well; it never impersonates a game. */
fun homeTomeSeatFocusKey(tomeId: TomeId, seatIndex: Int): FocusKey {
    require(seatIndex >= 0) { "Tome seat index must be non-negative" }
    return FocusKey("home/tome/seat/${tomeId.stableValue()}/$seatIndex")
}

/**
 * Stable virtual geometry for every member, including pages that are not currently painted.
 * Pages occupy adjacent vertical 6×3 leaves; the typed reducer remains the authority while these
 * bounds make Up/Down the natural cross-page direction for FocusAuthority.
 */
internal fun homeTomeVirtualFocusSlots(members: List<HomeTomeMember>): List<VirtualFocusSlot> =
    if (members.isEmpty()) {
        listOf(
            VirtualFocusSlot(
                index = 0,
                key = HomeTomeEmptyFocusKey,
                bounds = FocusBounds(
                    left = HomeTomeVirtualCellPitch,
                    top = 0f,
                    right = HomeTomeVirtualCellPitch + HomeTomeVirtualCellSize,
                    bottom = HomeTomeVirtualCellSize,
                ),
            ),
        )
    } else members.mapIndexed { index, member ->
        val page = index / HomeTomeFolioPageSize
        val pageOffset = index % HomeTomeFolioPageSize
        val column = pageOffset % HomeTomeFolioColumns
        val row = pageOffset / HomeTomeFolioColumns
        val left = column * HomeTomeVirtualCellPitch
        val top = (page * HomeTomeFolioRows + row) * HomeTomeVirtualCellPitch
        VirtualFocusSlot(
            index = index,
            key = member.focusKey,
            bounds = FocusBounds(
                left = left,
                top = top,
                right = left + HomeTomeVirtualCellSize,
                bottom = top + HomeTomeVirtualCellSize,
            ),
        )
    }

/** Sparse-seat graph. Every painted well is a stable focus destination in every Folio mode. */
internal fun homeTomeVirtualFocusSeatSlots(
    tomeId: TomeId,
    memberSlots: List<HomeTomeMember?>,
): List<VirtualFocusSlot> =
    if (memberSlots.none { it != null }) {
        homeTomeVirtualFocusSlots(emptyList())
    } else memberSlots.mapIndexed { index, member ->
        val key = member?.focusKey ?: homeTomeSeatFocusKey(tomeId, index)
        val page = index / HomeTomeFolioPageSize
        val pageOffset = index % HomeTomeFolioPageSize
        val column = pageOffset % HomeTomeFolioColumns
        val row = pageOffset / HomeTomeFolioColumns
        val left = column * HomeTomeVirtualCellPitch
        val top = (page * HomeTomeFolioRows + row) * HomeTomeVirtualCellPitch
        VirtualFocusSlot(
            index = index,
            key = key,
            bounds = FocusBounds(
                left = left,
                top = top,
                right = left + HomeTomeVirtualCellSize,
                bottom = top + HomeTomeVirtualCellSize,
            ),
        )
    }

/**
 * The focus ring paints the seat that FocusAuthority has actually published, not the reducer's
 * next requested index. This prevents a D-pad row change from flashing the destination before its
 * focus identity is authoritative.
 */
internal fun homeTomeFolioAuthoritativeSeatIndex(
    slots: List<VirtualFocusSlot>,
    activeScope: FocusScope,
    activeKey: FocusKey?,
): Int? {
    if (activeScope != HomeTomeFolioScope || activeKey == null || activeKey == HomeTomeEmptyFocusKey) {
        return null
    }
    return slots.firstOrNull { it.key == activeKey }?.index
}

/**
 * One painted destination for both the Folio sheet and its ring.
 *
 * The reducer can publish its next index one composition before FocusAuthority publishes the matching
 * key. Keeping the viewport on the last authoritative seat during that interval prevents the sheet from
 * moving under an old ring, which reads as a flash through an empty well. Cold composition may use the
 * reducer seed until the scope publishes its first real key.
 */
internal fun homeTomeFolioVisualSeatIndex(
    slots: List<VirtualFocusSlot>,
    activeScope: FocusScope,
    activeKey: FocusKey?,
    reducerFocusedIndex: Int,
): Int = homeTomeFolioAuthoritativeSeatIndex(slots, activeScope, activeKey)
    ?: reducerFocusedIndex.coerceAtLeast(0)

private fun HomeShelfItemId.stableKeyValue(): String = when (this) {
    is HomeShelfItemId.Emulated -> "game/${gameId.value}"
    is HomeShelfItemId.AndroidGame -> "android/${packageName.value}"
    is HomeShelfItemId.AndroidApp -> "app/${packageName.value}"
}

enum class HomeTomeFolioMode { BROWSE, ARRANGE }

/** The folio's controller legend in the language of [res]; the controller tokens stay as they are. */
internal fun homeTomeFolioFooter(
    res: Resources,
    mode: HomeTomeFolioMode,
    carrying: Boolean,
    itemCount: Int = 1,
): String = res.getString(
    when {
        itemCount <= 0 -> R.string.library_home_folio_footer_empty
        mode == HomeTomeFolioMode.ARRANGE && carrying -> R.string.library_home_folio_footer_carrying
        mode == HomeTomeFolioMode.ARRANGE -> R.string.library_home_folio_footer_arrange
        else -> R.string.library_home_folio_footer_browse
    },
)

sealed interface HomeTomeFolioActionRequest {
    data class Navigate(val update: HomeTomeNavigationUpdate) : HomeTomeFolioActionRequest
    data class Open(val member: HomeTomeMember) : HomeTomeFolioActionRequest
    data class Edit(val member: HomeTomeMember) : HomeTomeFolioActionRequest
    data object ToggleArrange : HomeTomeFolioActionRequest
    data class Arrange(val action: ControllerAction) : HomeTomeFolioActionRequest
    /** The Folio handled an intentionally inert action; it must not leak to an underlying surface. */
    data object Consumed : HomeTomeFolioActionRequest
    data object Back : HomeTomeFolioActionRequest
    data class General(val action: ControllerAction) : HomeTomeFolioActionRequest
}

data class HomeTomeFolioUiState(
    val tome: HomeShelfTome,
    val members: List<HomeTomeMember>,
    val memberSlots: List<HomeTomeMember?> = denseHomeTomeMemberSlots(members),
    val focusedIndex: Int = 0,
    val mode: HomeTomeFolioMode = HomeTomeFolioMode.BROWSE,
    val transition: HomeTomePageTransition = HomeTomePageTransition(),
    val carriedItemId: HomeShelfItemId? = null,
    val insertionIndex: Int? = null,
    val arrangeTransactionId: Long? = null,
    val nextArrangeTransactionId: Long = 1L,
    val focusSettled: Boolean = true,
    /**
     * Utility `Cartridge art` toggle — Tome presentation only; persisted by the app seam. The store
     * defaults ON, so the model default matches and a cold frame never flashes cover art falsely.
     */
    /** Exact seat + identity acknowledged by the painted motion. A carried game keeps its key while
     * changing seats, so key-only settlement can deadlock or authorize the wrong destination. */
    val settledFocusTarget: HomeTomeFolioFocusTarget? = null,
) {
    init {
        // The two seat-set invariants depend on NOTHING but these two lists, and this state is rebuilt on
        // every coordinator recomposition — several times per D-pad press, and the all-Home overview's
        // slot list is every Home game. Re-walking it each time cost six collection allocations and six
        // O(n) traversals per recomposition to re-answer a question whose inputs had not moved.
        HomeTomeFolioSeatInvariants.check(memberSlots, members)
        // These do depend on the per-instance fields, and are O(1) or only walk while carrying.
        require(carriedItemId == null || memberSlots.any { it?.itemId == carriedItemId }) {
            "Carried Tome identity must be present in the folio"
        }
        require(insertionIndex == null || insertionIndex in memberSlots.indices) {
            "Tome insertion target must address a painted seat"
        }
        require((carriedItemId != null) == (arrangeTransactionId != null)) {
            "Carried Tome identity and Arrange transaction must agree"
        }
    }

    val pageIndex: Int
        get() = homeTomePageForIndex(focusedIndex, memberSlots.size)

    val focusTarget: HomeTomeFolioFocusTarget?
        get() = homeTomeFolioFocusTarget(tome.tomeId, memberSlots, focusedIndex, mode)

    /**
     * The one activation gate (controller A/Y, Arrange lift/drop, carried touch drop): settlement
     * must be latched AND, when the key seam is wired, must acknowledge the CURRENT expected target.
     * Synchronous invalidation is the composable's unsettle callback firing on every authoritative
     * focus target/scope change, before the next input can observe the state.
     */
    val focusSettledForActivation: Boolean
        get() {
            if (!focusSettled) return false
            val settled = settledFocusTarget ?: return true
            return settled == focusTarget
        }
}

/**
 * The seat-set invariants for [HomeTomeFolioUiState], memoized on the IDENTITY of the two lists.
 *
 * The producing coordinator `remember`s `memberSlots` and `members`, so a state rebuilt for a focus move,
 * a settlement flip, or a page transition hands back the very same list instances — and the answer cannot
 * have changed. Identity (not equality) is the key precisely because equality would itself be the O(n)
 * walk we are avoiding. A miss still validates in full, so nothing invalid can slip through; the memo only
 * removes repeat work for a pair already proven good.
 */
private object HomeTomeFolioSeatInvariants {
    /** One reference so the pair swaps atomically — a torn pair could skip a check it never ran. */
    @Volatile
    private var lastValidated: Pair<List<HomeTomeMember?>, List<HomeTomeMember>>? = null

    fun check(memberSlots: List<HomeTomeMember?>, members: List<HomeTomeMember>) {
        val seen = lastValidated
        if (seen != null && seen.first === memberSlots && seen.second === members) return
        val slottedIds = memberSlots.filterNotNull().map(HomeTomeMember::itemId)
        require(slottedIds.distinct().size == slottedIds.size) { "Tome slots must not duplicate an identity" }
        require(slottedIds.toSet() == members.map(HomeTomeMember::itemId).toSet()) {
            "Tome slots must contain each member identity exactly once"
        }
        lastValidated = memberSlots to members
    }
}

data class HomeTomeFolioFocusTarget(
    val seatIndex: Int,
    val key: FocusKey,
) {
    init {
        require(seatIndex >= 0) { "A Tome focus target must address a non-negative seat" }
    }
}

/**
 * Resolves the one authoritative arrival from discrete FocusAuthority truth. The travel spring only
 * moves the already-selected tile and its ring together; activation does not wait behind that
 * compositor motion. A stale scope/key returns null, and a destination change cancels the caller's
 * one-frame acknowledgement through its LaunchedEffect key.
 */
internal fun homeTomeFolioSettlementTarget(
    expectedTarget: HomeTomeFolioFocusTarget?,
    activeScope: FocusScope,
    activeKey: FocusKey?,
): HomeTomeFolioFocusTarget? =
    expectedTarget?.takeIf { activeScope == HomeTomeFolioScope && activeKey == it.key }

fun homeTomeFolioFocusTarget(
    tomeId: TomeId,
    memberSlots: List<HomeTomeMember?>,
    focusedIndex: Int,
    @Suppress("UNUSED_PARAMETER") mode: HomeTomeFolioMode,
): HomeTomeFolioFocusTarget? {
    val key = memberSlots.getOrNull(focusedIndex)?.focusKey
        ?: if (focusedIndex in memberSlots.indices) homeTomeSeatFocusKey(tomeId, focusedIndex) else null
        ?: HomeTomeEmptyFocusKey.takeIf { memberSlots.none { it != null } && focusedIndex == 0 }
        ?: return null
    return HomeTomeFolioFocusTarget(focusedIndex, key)
}

fun homeTomeFolioFocusKey(
    tomeId: TomeId,
    memberSlots: List<HomeTomeMember?>,
    focusedIndex: Int,
    @Suppress("UNUSED_PARAMETER") mode: HomeTomeFolioMode,
): FocusKey? = homeTomeFolioFocusTarget(tomeId, memberSlots, focusedIndex, mode)?.key

/**
 * Travel-free strip-space pose of the one authoritative carry. Column/row are absolute strip
 * coordinates (same space as homeTomeFolioColumnOfIndex/homeTomeFolioRowOfIndex); the caller folds
 * travel in exactly once, in the draw phase. Identity is the carried item + its transaction, so a
 * seat step or a page crossing changes only the coordinates, never the identity.
 */
data class HomeTomeFolioCarryPose(
    val itemId: HomeShelfItemId,
    val transactionId: Long,
    val column: Int,
    val row: Int,
)

/** Null unless a live carry owns [transactionId]; a stale transaction can never paint a carry. */
fun homeTomeFolioCarryPose(
    state: HomeTomeFolioUiState,
    transactionId: Long? = state.arrangeTransactionId,
): HomeTomeFolioCarryPose? {
    val carried = state.carriedItemId ?: return null
    val activeTransactionId = state.arrangeTransactionId ?: return null
    if (transactionId != activeTransactionId) return null
    return HomeTomeFolioCarryPose(
        itemId = carried,
        transactionId = activeTransactionId,
        column = homeTomeFolioColumnOfIndex(state.focusedIndex),
        row = homeTomeFolioRowOfIndex(state.focusedIndex),
    )
}

fun HomeTomeFolioCarryPose.sameCarryAs(other: HomeTomeFolioCarryPose?): Boolean =
    other != null && itemId == other.itemId && transactionId == other.transactionId

/**
 * Compose may finish an effect launched for an older Folio index after the coordinator has already moved
 * focus. Only the latest model identity may repair a missing focus; an already-settled or stale effect is
 * a no-op. This keeps the coordinator as the single navigation writer without weakening cold-open repair.
 */
internal fun shouldRepairHomeTomeFolioFocus(
    effectKey: FocusKey?,
    latestKey: FocusKey?,
    activeScope: FocusScope,
    activeKey: FocusKey?,
): Boolean = effectKey != null &&
    effectKey == latestKey &&
    activeScope == HomeTomeFolioScope &&
    activeKey != effectKey

internal fun denseHomeTomeMemberSlots(members: List<HomeTomeMember>): List<HomeTomeMember?> {
    if (members.isEmpty()) return emptyList()
    val pageCount = homeTomePageCount(members.size)
    return members.map<HomeTomeMember, HomeTomeMember?> { it } +
        List(pageCount * HomeTomeFolioPageSize - members.size) { null }
}

/** Public app-owned controller routing contract; no callback or persistence is hidden in the visual. */
fun routeHomeTomeFolioAction(
    state: HomeTomeFolioUiState,
    action: ControllerAction,
): HomeTomeFolioActionRequest {
    val member = state.memberSlots.getOrNull(state.focusedIndex)
    val activation = action == ControllerAction.CONFIRM || action == ControllerAction.TERTIARY
    return when {
        action.isDirectional && state.mode == HomeTomeFolioMode.ARRANGE -> {
            HomeTomeFolioActionRequest.Arrange(action)
        }
        action.isDirectional -> HomeTomeFolioActionRequest.Navigate(
            navigateHomeTomeFolio(
                currentIndex = state.focusedIndex,
                itemCount = state.memberSlots.size,
                action = action,
                transition = state.transition,
            ),
        )
        action == ControllerAction.CONFIRM && state.members.isEmpty() -> HomeTomeFolioActionRequest.Back
        // A/Y (open, edit, lift, drop) wait for the painted ring to acknowledge the exact
        // destination; direction and Back stay live so the user is never trapped mid-settle.
        activation && !state.focusSettledForActivation -> if (
            action == ControllerAction.CONFIRM && state.mode == HomeTomeFolioMode.ARRANGE
        ) {
            // The app seam buffers this once for the exact seat target; further navigation cancels it.
            HomeTomeFolioActionRequest.Arrange(action)
        } else {
            HomeTomeFolioActionRequest.Consumed
        }
        action == ControllerAction.CONFIRM && state.mode == HomeTomeFolioMode.ARRANGE -> {
            HomeTomeFolioActionRequest.Arrange(action)
        }
        action == ControllerAction.CONFIRM && member != null -> HomeTomeFolioActionRequest.Open(member)
        action == ControllerAction.CONFIRM -> HomeTomeFolioActionRequest.Consumed
        action == ControllerAction.TERTIARY && state.mode == HomeTomeFolioMode.ARRANGE -> {
            HomeTomeFolioActionRequest.Consumed
        }
        action == ControllerAction.TERTIARY && member != null -> HomeTomeFolioActionRequest.Edit(member)
        action == ControllerAction.TERTIARY -> HomeTomeFolioActionRequest.Consumed
        action == ControllerAction.SECONDARY && state.mode == HomeTomeFolioMode.ARRANGE -> {
            HomeTomeFolioActionRequest.Arrange(action)
        }
        action == ControllerAction.SECONDARY -> HomeTomeFolioActionRequest.ToggleArrange
        (action == ControllerAction.CANCEL || action == ControllerAction.SYSTEM_BACK) &&
            state.mode == HomeTomeFolioMode.ARRANGE -> HomeTomeFolioActionRequest.Arrange(action)
        action == ControllerAction.CANCEL || action == ControllerAction.SYSTEM_BACK -> HomeTomeFolioActionRequest.Back
        else -> HomeTomeFolioActionRequest.General(action)
    }
}


/** a Folio well never paints below this side, or touch/focus targets collapse. */
internal const val HomeTomeFolioMinCellDp = 48f

/**
 * Readability target for the COUNT decision — the side a Folio well wants, not the side below which
 * it stops functioning. Bracketed by two same-day hardware measurements on this build: the Nova's
 * 74.26dp well was too small to read, while the Odin's
 * 84.91dp well remained the reference. 80 sits near the midpoint and is ≥4.9dp from every
 * certified viewport's decision boundary, so no authored value inside that bracket moves either device.
 * Distinct from [HomeTomeFolioMinCellDp], which stays exactly what its KDoc says: the collapse floor.
 */
internal const val HomeTomeFolioWellFloorDp = 80f

/**
 * The logical Folio page is always [HomeTomeFolioColumns] columns. The count walks down from six until
 * a column is at least [minCellDp] wide — the caller supplies that threshold, because the count is
 * decided against what the *surface* can already afford to paint, never against a fixed constant.
 * Fewer visible columns mount a horizontal viewport over the same logical page — never fewer logical
 * columns, and never a sub-floor well.
 */
internal fun homeTomeFolioVisibleColumns(usableWidth: Float, columnGap: Float, minCellDp: Float): Int {
    if (usableWidth <= 0f) return 1
    var columns = HomeTomeFolioColumns
    while (columns > 1) {
        val cell = (usableWidth - columnGap * (columns - 1)) / columns
        if (cell >= minCellDp) return columns
        columns--
    }
    return 1
}

internal data class HomeTomeFolioGridSolve(
    val visibleColumns: Int,
    val visibleRows: Int,
    val cellSideDp: Float,
)

/**
 * Six logical columns always; the visible count is decided against legibility, not against survival.
 * Rows resolve first (unchanged, [HomeTomeFolioMinCellDp]-based), which fixes what the height can pay;
 * the column walk's threshold is then `min(`[HomeTomeFolioWellFloorDp]`, usableWidth, cellFromHeight)`
 * clamped up to the collapse floor, so a viewport only sheds columns until the width cell reaches what
 * the height was already going to pay. Size still solves to fill the binding axis.
 *
 * Height-bound invariance: whenever `cellFromHeight <= cellFromWidth(6)` the target is `<=` the six-column
 * width cell, so the walk stops at six and the painted cell is unchanged. Every height-bound viewport —
 * the Odin included, at every font scale — is therefore bit-identical to the pre-floor law; only a
 * width-bound viewport can lose a column. The clamp up to [HomeTomeFolioMinCellDp] reproduces the old
 * pathological-height behaviour exactly.
 *
 * Row count prefers three painted rows, falling back to a six-by-two viewport over the same 18-seat page
 * when height is tight. Pathological heights still paint two rows at the floor.
 */
internal fun solveHomeTomeFolioGrid(
    usableWidth: Float,
    usableHeight: Float,
    columnGap: Float,
    rowGap: Float,
): HomeTomeFolioGridSolve {
    val visibleRows = homeTomeFolioPreferredVisibleRows(usableHeight, rowGap)
    val cellFromHeight = (usableHeight - rowGap * (visibleRows - 1)) / visibleRows
    val countTarget = minOf(HomeTomeFolioWellFloorDp, usableWidth, cellFromHeight)
        .coerceAtLeast(HomeTomeFolioMinCellDp)
    val visibleColumns = homeTomeFolioVisibleColumns(usableWidth, columnGap, countTarget)
    val cellFromWidth = (usableWidth - columnGap * (visibleColumns - 1)) / visibleColumns
    val cellSide = maxOf(HomeTomeFolioMinCellDp, minOf(cellFromWidth, cellFromHeight))
    return HomeTomeFolioGridSolve(
        visibleColumns = visibleColumns,
        visibleRows = visibleRows,
        cellSideDp = cellSide,
    )
}

/** Three rows when the viewport can host them at the floor; otherwise always two painted rows. */
internal fun homeTomeFolioPreferredVisibleRows(
    usableHeight: Float,
    rowGap: Float,
): Int {
    val threeRowMin = HomeTomeFolioMinCellDp * HomeTomeFolioRows + rowGap * (HomeTomeFolioRows - 1)
    return if (usableHeight + 0.001f >= threeRowMin) HomeTomeFolioRows else 2
}

/**
 * Continuous 6×3 sheet window. Forward pins focus at the top of the window
 * (row0 → 0..2, row1 → 1..3). Cold/same-focus callers use this top-pin seed.
 */
internal fun homeTomeFolioRowWindowStart(
    focusedRow: Int,
    visibleRows: Int,
    totalRows: Int = HomeTomeFolioRows,
): Int {
    val visible = visibleRows.coerceAtLeast(1)
    if (totalRows <= visible) return 0
    val maxStart = (totalRows - visible).coerceAtLeast(0)
    return focusedRow.coerceIn(0, maxStart)
}

/**
 * Direction-aware continuous window. Each logical row crossed shifts the sheet by exactly one row
 * in the same direction, so a reverse never jumps from a forward top-pin to a distant bottom-pin.
 * Coalesced input shifts by the coalesced row delta and therefore still lands on the latest target.
 * Same-focus preserves/clamps the current containing window. Terminal clamp relaxes.
 */
internal fun homeTomeFolioRowWindowStart(
    previousStart: Int,
    previousFocusedRow: Int,
    focusedRow: Int,
    visibleRows: Int,
    totalRows: Int,
): Int {
    val visible = visibleRows.coerceAtLeast(1)
    if (totalRows <= visible) return 0
    val maxStart = (totalRows - visible).coerceAtLeast(0)
    val focus = focusedRow.coerceIn(0, (totalRows - 1).coerceAtLeast(0))
    val priorStart = previousStart.coerceIn(0, maxStart)
    val previousFocus = previousFocusedRow.coerceIn(0, (totalRows - 1).coerceAtLeast(0))
    return when {
        focus != previousFocus -> (priorStart + focus - previousFocus).coerceIn(0, maxStart)
        else -> when {
            focus < priorStart -> focus.coerceIn(0, maxStart)
            focus >= priorStart + visible -> (focus - visible + 1).coerceIn(0, maxStart)
            else -> priorStart
        }
    }
}

/** Defensive clamp so [rowWindowStart] + [visibleRows] never aliases past [totalRows]. */
internal fun homeTomeFolioClampedRowWindow(
    focusedRow: Int,
    visibleRows: Int,
    totalRows: Int = HomeTomeFolioRows,
): Pair<Int, Int> {
    val clampedVisible = visibleRows.coerceIn(1, totalRows.coerceAtLeast(1))
    val windowStart = homeTomeFolioRowWindowStart(focusedRow, clampedVisible, totalRows)
    val safeVisible = ((windowStart + clampedVisible).coerceAtMost(totalRows.coerceAtLeast(1)) - windowStart)
        .coerceAtLeast(1)
    return windowStart to safeVisible
}

/** Mounted hydration rows: visible window plus one buffer above and below. */
internal fun homeTomeFolioHydrationRowRange(
    rowWindowStart: Int,
    visibleRows: Int,
    totalRows: Int,
): IntRange {
    if (totalRows <= 0) return 0 until 0
    val first = (rowWindowStart - 1).coerceAtLeast(0)
    val last = (rowWindowStart + visibleRows.coerceAtLeast(1)).coerceAtMost(totalRows - 1)
    return first..last
}

/**
 * Authoritative drop destination in travel-free strip space. Persists from release through reorder
 * commit, FocusAuthority/travel settlement, and the first stable post-commit frame. [generation]
 * ties the pending pose to the owning touch gesture so a newer gesture cannot be cleared by an
 * older coroutine.
 */
data class HomeTomeFolioDropDestination(
    val seatIndex: Int,
    val transactionId: Long,
    val generation: Long,
    val column: Int,
    val row: Int,
)

fun homeTomeFolioDropDestination(
    seatIndex: Int,
    transactionId: Long,
    generation: Long,
): HomeTomeFolioDropDestination = HomeTomeFolioDropDestination(
    seatIndex = seatIndex,
    transactionId = transactionId,
    generation = generation,
    column = homeTomeFolioColumnOfIndex(seatIndex),
    row = homeTomeFolioRowOfIndex(seatIndex),
)

enum class HomeTomeFolioDropPhase {
    AwaitingLiftTransaction,
    AwaitingDestinationSettlement,
    Completed,
}

/** Pure reducer state for touch release through lift observation and destination settlement. */
data class HomeTomeFolioDropObservation(
    val destination: HomeTomeFolioDropDestination,
    val phase: HomeTomeFolioDropPhase,
    val sourceIndex: Int,
    val sourceFocusKey: FocusKey,
    val liftAwaitElapsedMs: Long = 0L,
    val destinationSettleElapsedMs: Long = 0L,
    val destinationObserved: Boolean = false,
    val destinationAuthorityObserved: Boolean = false,
)

data class HomeTomeFolioDropFrame(
    val mode: HomeTomeFolioMode,
    val arrangeTransactionId: Long?,
    val carriedItemId: HomeShelfItemId?,
    val focusedIndex: Int,
    val focusSettledForActivation: Boolean,
    val focusTarget: HomeTomeFolioFocusTarget?,
    val authorityScope: FocusScope,
    val authorityFocused: FocusKey?,
)

sealed interface HomeTomeFolioDropEffect {
    data class CancelOwned(val transactionId: Long) : HomeTomeFolioDropEffect
    data class Drop(val seatIndex: Int, val transactionId: Long) : HomeTomeFolioDropEffect
}

/** Transaction-local ownership captured by one release job; replacement gestures cannot rewrite it. */
internal class HomeTomeTouchDropOwnership(
    val transactionId: Long,
    val gestureGeneration: Long,
    liftPublished: Boolean = false,
) {
    var liftPublished: Boolean = liftPublished
        private set
    private var terminalClaimed: Boolean = false

    fun publish() {
        if (!terminalClaimed) liftPublished = true
    }

    fun takeCancelEffect(): HomeTomeFolioDropEffect.CancelOwned? {
        if (terminalClaimed) return null
        terminalClaimed = true
        return transactionId
            .takeIf { liftPublished }
            ?.let(HomeTomeFolioDropEffect::CancelOwned)
    }

    fun complete() {
        terminalClaimed = true
    }
}

internal fun homeTomeTouchDropCancelEffect(
    ownership: HomeTomeTouchDropOwnership,
): HomeTomeFolioDropEffect.CancelOwned? = ownership.takeCancelEffect()

internal fun homeTomeTouchDropMayClearSharedOwnership(
    ownership: HomeTomeTouchDropOwnership,
    activeGestureGeneration: Long,
): Boolean = ownership.liftPublished &&
    ownership.gestureGeneration == activeGestureGeneration

data class HomeTomeFolioDropStep(
    val observation: HomeTomeFolioDropObservation?,
    val effect: HomeTomeFolioDropEffect? = null,
)

fun beginHomeTomeFolioDropObservation(
    destination: HomeTomeFolioDropDestination,
    sourceIndex: Int,
    sourceFocusKey: FocusKey,
    liftTransactionObserved: Boolean,
): HomeTomeFolioDropObservation = HomeTomeFolioDropObservation(
    destination = destination,
    phase = if (liftTransactionObserved) {
        HomeTomeFolioDropPhase.AwaitingDestinationSettlement
    } else {
        HomeTomeFolioDropPhase.AwaitingLiftTransaction
    },
    sourceIndex = sourceIndex,
    sourceFocusKey = sourceFocusKey,
)

internal fun homeTomeTouchLiftTransactionObserved(
    frame: HomeTomeFolioDropFrame,
    expectedTransactionId: Long,
): Boolean = frame.mode == HomeTomeFolioMode.ARRANGE &&
    frame.arrangeTransactionId == expectedTransactionId &&
    frame.carriedItemId != null

internal fun homeTomeTouchLiftSuperseded(
    frame: HomeTomeFolioDropFrame,
    expectedTransactionId: Long,
): Boolean {
    val activeId = frame.arrangeTransactionId
    return activeId != null && activeId != expectedTransactionId
}

internal fun stepHomeTomeFolioDropLiftAwait(
    observation: HomeTomeFolioDropObservation,
    frame: HomeTomeFolioDropFrame,
    deltaMs: Long,
): HomeTomeFolioDropStep {
    val transactionId = observation.destination.transactionId
    if (homeTomeTouchLiftTransactionObserved(frame, transactionId)) {
        return HomeTomeFolioDropStep(
            observation = observation.copy(
                phase = HomeTomeFolioDropPhase.AwaitingDestinationSettlement,
                liftAwaitElapsedMs = observation.liftAwaitElapsedMs + deltaMs,
            ),
        )
    }
    if (homeTomeTouchLiftSuperseded(frame, transactionId)) {
        return HomeTomeFolioDropStep(
            observation = null,
            effect = HomeTomeFolioDropEffect.CancelOwned(transactionId),
        )
    }
    val elapsed = observation.liftAwaitElapsedMs + deltaMs
    if (elapsed >= HomeTomeTouchLiftAwaitMs) {
        return HomeTomeFolioDropStep(
            observation = null,
            effect = HomeTomeFolioDropEffect.CancelOwned(transactionId),
        )
    }
    return HomeTomeFolioDropStep(observation = observation.copy(liftAwaitElapsedMs = elapsed))
}

internal fun stepHomeTomeFolioDropSettlement(
    observation: HomeTomeFolioDropObservation,
    frame: HomeTomeFolioDropFrame,
    deltaMs: Long,
): HomeTomeFolioDropStep {
    val destination = observation.destination.seatIndex
    val transactionId = observation.destination.transactionId
    if (frame.mode != HomeTomeFolioMode.ARRANGE ||
        frame.arrangeTransactionId != transactionId ||
        frame.carriedItemId == null
    ) {
        return HomeTomeFolioDropStep(
            observation = null,
            effect = HomeTomeFolioDropEffect.CancelOwned(transactionId),
        )
    }
    if (frame.authorityScope != HomeTomeFolioScope) {
        return HomeTomeFolioDropStep(
            observation = null,
            effect = HomeTomeFolioDropEffect.CancelOwned(transactionId),
        )
    }
    val elapsed = observation.destinationSettleElapsedMs + deltaMs.coerceAtLeast(0L)
    if (elapsed >= HomeTomeTouchDropSettleMs) {
        return HomeTomeFolioDropStep(
            observation = null,
            effect = HomeTomeFolioDropEffect.CancelOwned(transactionId),
        )
    }
    var destinationObserved = observation.destinationObserved
    var destinationAuthorityObserved = observation.destinationAuthorityObserved
    when {
        frame.focusedIndex == destination -> destinationObserved = true
        !destinationObserved && frame.focusedIndex == observation.sourceIndex -> Unit
        else -> return HomeTomeFolioDropStep(
            observation = null,
            effect = HomeTomeFolioDropEffect.CancelOwned(transactionId),
        )
    }
    if (destinationObserved) {
        val target = frame.focusTarget ?: return HomeTomeFolioDropStep(
            observation = null,
            effect = HomeTomeFolioDropEffect.CancelOwned(transactionId),
        )
        val authorityMatches = frame.authorityFocused == target.key
        val awaitingSourceAuthority =
            !destinationAuthorityObserved && frame.authorityFocused == observation.sourceFocusKey
        if (!authorityMatches && !awaitingSourceAuthority) {
            return HomeTomeFolioDropStep(
                observation = null,
                effect = HomeTomeFolioDropEffect.CancelOwned(transactionId),
            )
        }
        if (authorityMatches) destinationAuthorityObserved = true
        if (authorityMatches && frame.focusSettledForActivation) {
            return HomeTomeFolioDropStep(
                observation = observation.copy(
                    phase = HomeTomeFolioDropPhase.Completed,
                    destinationObserved = destinationObserved,
                    destinationAuthorityObserved = destinationAuthorityObserved,
                ),
                effect = HomeTomeFolioDropEffect.Drop(destination, transactionId),
            )
        }
    }
    return HomeTomeFolioDropStep(
        observation = observation.copy(
            destinationSettleElapsedMs = elapsed,
            destinationObserved = destinationObserved,
            destinationAuthorityObserved = destinationAuthorityObserved,
        ),
    )
}

fun stepHomeTomeFolioDropObservation(
    observation: HomeTomeFolioDropObservation,
    frame: HomeTomeFolioDropFrame,
    deltaMs: Long = 0L,
): HomeTomeFolioDropStep = when (observation.phase) {
    HomeTomeFolioDropPhase.AwaitingLiftTransaction ->
        stepHomeTomeFolioDropLiftAwait(observation, frame, deltaMs)
    HomeTomeFolioDropPhase.AwaitingDestinationSettlement ->
        stepHomeTomeFolioDropSettlement(observation, frame, deltaMs)
    HomeTomeFolioDropPhase.Completed -> HomeTomeFolioDropStep(observation = null)
}

/** Post-commit stable frame: clear only when generation and transaction still match. */
fun shouldClearHomeTomeFolioPendingDrop(
    pending: HomeTomeFolioDropDestination?,
    activeGestureGeneration: Long,
    focusedIndex: Int,
    focusSettled: Boolean,
    carriedCleared: Boolean,
): Boolean = pending != null &&
    pending.generation == activeGestureGeneration &&
    carriedCleared &&
    focusedIndex == pending.seatIndex &&
    focusSettled

fun relinquishHomeTomeFolioPendingDrop(
    pending: HomeTomeFolioDropDestination?,
    gestureGeneration: Long,
): HomeTomeFolioDropDestination? =
    if (pending?.generation == gestureGeneration) null else pending

fun homeTomeFolioOutsideReleaseEffect(transactionId: Long?): HomeTomeFolioDropEffect? =
    transactionId?.let { HomeTomeFolioDropEffect.CancelOwned(it) }

/** Fold page travel into a viewport X sample, yielding absolute strip-space cell top-left. */
internal fun homeTomeFolioStripCellLeftX(
    viewportX: Float,
    travelStartColumn: Float,
    ringClearancePx: Float,
    cellWidthPx: Float,
    strideXPx: Float,
): Float = viewportX - ringClearancePx + travelStartColumn * strideXPx - cellWidthPx / 2f

/** Paint an absolute strip-space top-left in outer Folio coordinates (travel subtracted unconditionally). */
internal fun homeTomeFolioPaintLeftX(
    stripLeftX: Float,
    gridLeftPx: Float,
    travelStartColumn: Float,
    strideXPx: Float,
): Float = gridLeftPx + stripLeftX - travelStartColumn * strideXPx

internal fun homeTomeFolioStripSeatedLeftX(
    column: Int,
    strideXPx: Float,
): Float = column * strideXPx

internal fun homeTomeFolioStripCellTopY(
    logicalRow: Int,
    rowWindowStart: Int,
    cellHeightPx: Float,
    rowGapPx: Float,
): Float = (logicalRow - rowWindowStart).coerceAtLeast(0) * (cellHeightPx + rowGapPx)

internal fun homeTomeFolioStripPointerTopY(
    viewportY: Float,
    ringClearancePx: Float,
    cellHeightPx: Float,
): Float = viewportY - ringClearancePx - cellHeightPx / 2f

/**
 * Fractional continuous-sheet hit compensation. Paint translates by `-A * stride` while hit tests
 * address a discrete [rowWindowStart]. Using `round(A)` alone near 0.5 can select a neighboring
 * global row; shift Y by `(A - round(A)) * stride` so the painted center keeps one seat.
 */
internal data class HomeTomeFolioAnimatedRowHit(
    val discreteRowWindowStart: Int,
    val adjustedPositionY: Float,
)

internal fun homeTomeFolioAnimatedRowHit(
    animatedRowWindowStart: Float,
    viewportRelativeY: Float,
    cellHeight: Float,
    rowGap: Float,
): HomeTomeFolioAnimatedRowHit {
    val discreteStart = kotlin.math.round(animatedRowWindowStart).toInt()
    val stride = cellHeight + rowGap
    return HomeTomeFolioAnimatedRowHit(
        discreteRowWindowStart = discreteStart,
        adjustedPositionY = viewportRelativeY + (animatedRowWindowStart - discreteStart) * stride,
    )
}

/**
 * Pixel-space lift hit-test for the traveling strip. [positionX] is viewport-relative; the settled
 * [travelStartColumn] maps it onto absolute strip columns. Only a painted seat resolves; inter-seat
 * gaps and the header/footer rails deliberately resolve to null.
 */
internal fun homeTomeFolioSeatIndexAtTravel(
    positionX: Float,
    positionY: Float,
    travelStartColumn: Int,
    seatCount: Int,
    cellWidth: Float,
    cellHeight: Float,
    columnGap: Float,
    rowGap: Float,
    visibleColumns: Int = HomeTomeFolioColumns,
    @Suppress("UNUSED_PARAMETER") pageIndex: Int = 0,
): Int? = homeTomeFolioSeatIndexAtTravel(
    positionX = positionX,
    positionY = positionY,
    travelStartColumn = travelStartColumn.toFloat(),
    seatCount = seatCount,
    cellWidth = cellWidth,
    cellHeight = cellHeight,
    columnGap = columnGap,
    rowGap = rowGap,
    visibleColumns = visibleColumns,
)

/** Fractional counterpart used while the strip is physically between authored columns. */
internal fun homeTomeFolioSeatIndexAtTravel(
    positionX: Float,
    positionY: Float,
    travelStartColumn: Float,
    seatCount: Int,
    cellWidth: Float,
    cellHeight: Float,
    columnGap: Float,
    rowGap: Float,
    visibleColumns: Int = HomeTomeFolioColumns,
    rowWindowStart: Int = 0,
    visibleRows: Int = HomeTomeFolioRows,
    @Suppress("UNUSED_PARAMETER") pageIndex: Int = 0,
): Int? {
    if (seatCount <= 0 || cellWidth <= 0f || cellHeight <= 0f) return null
    if (positionX < 0f || positionY < 0f) return null
    val strideX = cellWidth + columnGap
    val visible = visibleColumns.coerceAtLeast(1)
    val gridWidth = visible * strideX - columnGap
    if (positionX > gridWidth) return null
    val stripX = positionX + travelStartColumn.coerceAtLeast(0f) * strideX
    val column = (stripX / strideX).toInt()
    if (column !in 0 until HomeTomeFolioColumns) return null
    if (stripX - column * strideX > cellWidth) return null
    val paintedRows = visibleRows.coerceIn(1, HomeTomeFolioRows)
    val row = (0 until paintedRows).firstOrNull { candidate ->
        val top = candidate * (cellHeight + rowGap)
        positionY in top..(top + cellHeight)
    }?.let { rowWindowStart + it } ?: return null
    val index = homeTomeFolioIndexOf(column, row)
    return index.takeIf { it in 0 until seatCount }
}

/**
 * Nearest painted well for a release anywhere inside the visible strip envelope. Outside the
 * envelope is a cancel; gaps never reuse a stale sampled destination.
 */
internal fun homeTomeFolioNearestSeatAtTravel(
    positionX: Float,
    positionY: Float,
    travelStartColumn: Int,
    seatCount: Int,
    cellWidth: Float,
    cellHeight: Float,
    columnGap: Float,
    rowGap: Float,
    visibleColumns: Int = HomeTomeFolioColumns,
    @Suppress("UNUSED_PARAMETER") pageIndex: Int = 0,
): Int? = homeTomeFolioNearestSeatAtTravel(
    positionX = positionX,
    positionY = positionY,
    travelStartColumn = travelStartColumn.toFloat(),
    seatCount = seatCount,
    cellWidth = cellWidth,
    cellHeight = cellHeight,
    columnGap = columnGap,
    rowGap = rowGap,
    visibleColumns = visibleColumns,
)

/** Fractional counterpart used for release against the tile positions actually painted this frame. */
internal fun homeTomeFolioNearestSeatAtTravel(
    positionX: Float,
    positionY: Float,
    travelStartColumn: Float,
    seatCount: Int,
    cellWidth: Float,
    cellHeight: Float,
    columnGap: Float,
    rowGap: Float,
    visibleColumns: Int = HomeTomeFolioColumns,
    rowWindowStart: Int = 0,
    visibleRows: Int = HomeTomeFolioRows,
    @Suppress("UNUSED_PARAMETER") pageIndex: Int = 0,
): Int? {
    if (seatCount <= 0 || cellWidth <= 0f || cellHeight <= 0f) return null
    val visible = visibleColumns.coerceAtLeast(1)
    val paintedRows = visibleRows.coerceIn(1, HomeTomeFolioRows)
    val strideX = cellWidth + columnGap
    val gridWidth = visible * strideX - columnGap
    val gridHeight = (paintedRows - 1) * (cellHeight + rowGap) + cellHeight
    if (positionX !in 0f..gridWidth || positionY !in 0f..gridHeight) return null
    val travel = travelStartColumn.coerceAtLeast(0f)
    val firstColumn = kotlin.math.floor(travel.toDouble()).toInt().coerceAtLeast(0)
    val lastColumn = kotlin.math.ceil((travel + visible).toDouble()).toInt()
    return (firstColumn..lastColumn.coerceAtMost(HomeTomeFolioColumns - 1)).flatMap { column ->
        (0 until paintedRows).mapNotNull { paintedRow ->
            val row = rowWindowStart + paintedRow
            val index = homeTomeFolioIndexOf(column, row)
            if (index !in 0 until seatCount) return@mapNotNull null
            val centerX = column * strideX - travel * strideX + cellWidth / 2f
            val centerY = paintedRow * (cellHeight + rowGap) + cellHeight / 2f
            val dx = positionX - centerX
            val dy = positionY - centerY
            index to (dx * dx + dy * dy)
        }
    }
        .minByOrNull { it.second }
        ?.first
}

data class HomeTomeArrangeState(
    val baselineSlots: List<HomeShelfItemId?>,
    val workingSlots: List<HomeShelfItemId?> = baselineSlots,
    val focusedIndex: Int = 0,
    val carriedItemId: HomeShelfItemId? = null,
    val carryOriginIndex: Int? = null,
    val carryDestinationIndex: Int? = null,
    val activeTransactionId: Long? = null,
    val nextTransactionId: Long = 1L,
    val pageTransition: HomeTomePageTransition = HomeTomePageTransition(),
) {
    val baselineOrder: List<HomeShelfItemId> get() = baselineSlots.filterNotNull()
    val workingOrder: List<HomeShelfItemId> get() = workingSlots.filterNotNull()

    init {
        require(baselineSlots.size == workingSlots.size) { "working seats must preserve folio capacity" }
        require(baselineOrder.distinct().size == baselineOrder.size) { "baseline identities must be unique" }
        require(workingOrder.distinct().size == workingOrder.size) { "working identities must be unique" }
        require(workingOrder.toSet() == baselineOrder.toSet()) { "working seats must contain the baseline identities" }
        require(carriedItemId == null || carriedItemId in workingOrder) { "carried identity must occupy one working seat" }
        require((carriedItemId != null) == (carryOriginIndex != null)) { "carry origin must match carry state" }
        require((carriedItemId != null) == (carryDestinationIndex != null)) { "carry destination must match carry state" }
        require((carriedItemId != null) == (activeTransactionId != null)) { "transaction must match carry state" }
    }
}

/** One semantic input vocabulary for controller and touch Arrange interactions. */
sealed interface HomeTomeArrangeIntent {
    data class MoveCursor(
        val direction: ControllerAction,
        val transactionId: Long? = null,
    ) : HomeTomeArrangeIntent {
        init {
            require(direction.isDirectional) { "Arrange cursor movement requires a directional action" }
        }
    }
    data class Lift(val seatIndex: Int, val transactionId: Long) : HomeTomeArrangeIntent
    data class Retarget(val seatIndex: Int, val transactionId: Long) : HomeTomeArrangeIntent
    data class Drop(val seatIndex: Int, val transactionId: Long) : HomeTomeArrangeIntent
    data class Cancel(val transactionId: Long) : HomeTomeArrangeIntent
    data object Exit : HomeTomeArrangeIntent
}

/** A touch gesture may publish a Lift only after its own pre-lift focus settlement completed. */
internal fun homeTomeTouchLiftIntent(
    liftSettled: Boolean,
    seatIndex: Int,
    transactionId: Long,
): HomeTomeArrangeIntent.Lift? =
    HomeTomeArrangeIntent.Lift(seatIndex, transactionId).takeIf { liftSettled }

data class HomeTomeArrangeUpdate(
    val state: HomeTomeArrangeState,
    /** Non-null only on an explicit A drop; this is the sole durable-write seam. */
    val committedSlots: List<HomeShelfItemId?>? = null,
    val committedOrder: List<HomeShelfItemId>? = committedSlots?.filterNotNull(),
    val committedTransactionId: Long? = null,
    val rollbackSlots: List<HomeShelfItemId?>? = null,
    val rollbackFocusIndex: Int? = null,
    val exitRequested: Boolean = false,
    val navigation: HomeTomeNavigationUpdate? = null,
)

data class HomeTomeNavigationUpdate(
    val targetIndex: Int,
    val targetPage: Int,
    val transition: HomeTomePageTransition,
    val moved: Boolean,
)

fun beginHomeTomeArrange(
    order: List<HomeShelfItemId>,
    focusedIndex: Int,
    nextTransactionId: Long = 1L,
): HomeTomeArrangeState = beginHomeTomeArrangeSlots(
    slots = order.map<HomeShelfItemId, HomeShelfItemId?> { it },
    focusedIndex = focusedIndex,
    nextTransactionId = nextTransactionId,
)

fun beginHomeTomeArrangeSlots(
    slots: List<HomeShelfItemId?>,
    focusedIndex: Int,
    nextTransactionId: Long = 1L,
): HomeTomeArrangeState {
    val normalized = normalizeHomeTomeArrangeSlots(slots)
    val focus = if (normalized.isEmpty()) 0 else focusedIndex.coerceIn(0, normalized.lastIndex)
    return HomeTomeArrangeState(
        baselineSlots = normalized,
        focusedIndex = focus,
        nextTransactionId = nextTransactionId,
        pageTransition = HomeTomePageTransition(
            settledPage = homeTomePageForIndex(focus, normalized.size),
        ),
    )
}

internal fun normalizeHomeTomeArrangeSlots(slots: List<HomeShelfItemId?>): List<HomeShelfItemId?> {
    val seen = hashSetOf<HomeShelfItemId>()
    val unique = slots.map { identity -> identity?.takeIf(seen::add) }
    val used = unique.indexOfLast { it != null } + 1
    if (used <= 0) return emptyList()
    val paddedSize = ((used - 1) / HomeTomeFolioPageSize + 1) * HomeTomeFolioPageSize
    return unique.take(paddedSize) + List((paddedSize - unique.size).coerceAtLeast(0)) { null }
}

fun nearestOccupiedHomeTomeSeat(
    slots: List<HomeShelfItemId?>,
    preferredIndex: Int,
): Int {
    if (slots.none { it != null }) return 0
    val preferred = preferredIndex.coerceIn(0, slots.lastIndex)
    if (slots[preferred] != null) return preferred
    return slots.indices
        .filter { slots[it] != null }
        .minWith(compareBy<Int> { kotlin.math.abs(it - preferred) }.thenBy { it })
}

/** Controller adapter into the same typed reducer used by touch. */
fun reduceHomeTomeArrange(
    state: HomeTomeArrangeState,
    action: ControllerAction,
): HomeTomeArrangeUpdate = when (action) {
        ControllerAction.NAV_LEFT,
        ControllerAction.NAV_RIGHT,
        ControllerAction.NAV_UP,
        ControllerAction.NAV_DOWN,
        -> reduceHomeTomeArrange(state, HomeTomeArrangeIntent.MoveCursor(action))
        ControllerAction.CONFIRM -> if (state.carriedItemId == null) {
            reduceHomeTomeArrange(
                state,
                HomeTomeArrangeIntent.Lift(state.focusedIndex, state.nextTransactionId),
            )
        } else {
            val transactionId = requireNotNull(state.activeTransactionId)
            reduceHomeTomeArrange(
                state,
                HomeTomeArrangeIntent.Drop(state.focusedIndex, transactionId),
            )
        }
        ControllerAction.CANCEL,
        ControllerAction.SYSTEM_BACK,
        -> if (state.carriedItemId == null) {
            reduceHomeTomeArrange(state, HomeTomeArrangeIntent.Exit)
        } else {
            reduceHomeTomeArrange(state, HomeTomeArrangeIntent.Cancel(requireNotNull(state.activeTransactionId)))
        }
        ControllerAction.SECONDARY -> if (state.carriedItemId == null) {
            reduceHomeTomeArrange(state, HomeTomeArrangeIntent.Exit)
        } else {
            HomeTomeArrangeUpdate(state)
        }
        else -> HomeTomeArrangeUpdate(state)
    }

/**
 * Pure Arrange transaction. Preview geometry is always re-derived from the immutable lift snapshot,
 * so pointer sample density and controller burst timing cannot change the committed result.
 */
fun reduceHomeTomeArrange(
    state: HomeTomeArrangeState,
    intent: HomeTomeArrangeIntent,
): HomeTomeArrangeUpdate {
    if (state.workingSlots.isEmpty()) {
        return HomeTomeArrangeUpdate(state, exitRequested = intent is HomeTomeArrangeIntent.Exit)
    }
    return when (intent) {
        is HomeTomeArrangeIntent.MoveCursor -> {
            if (intent.transactionId != null && intent.transactionId != state.activeTransactionId) {
                return HomeTomeArrangeUpdate(state)
            }
            val navigation = navigateHomeTomeFolio(
                state.focusedIndex,
                state.workingSlots.size,
                intent.direction,
                state.pageTransition,
            )
            retargetHomeTomeArrangeInternal(state, navigation.targetIndex, navigation)
        }
        is HomeTomeArrangeIntent.Retarget -> if (intent.transactionId == state.activeTransactionId) {
            retargetHomeTomeArrangeInternal(state, intent.seatIndex)
        } else {
            HomeTomeArrangeUpdate(state)
        }
        is HomeTomeArrangeIntent.Lift -> {
            if (state.carriedItemId != null) return HomeTomeArrangeUpdate(state)
            if (intent.transactionId != state.nextTransactionId) return HomeTomeArrangeUpdate(state)
            val seat = intent.seatIndex.coerceIn(0, state.workingSlots.lastIndex)
            val identity = state.workingSlots[seat] ?: return HomeTomeArrangeUpdate(
                state.copy(focusedIndex = seat),
            )
            val transactionId = intent.transactionId
            HomeTomeArrangeUpdate(
                state.copy(
                    baselineSlots = state.workingSlots,
                    focusedIndex = seat,
                    carriedItemId = identity,
                    carryOriginIndex = seat,
                    carryDestinationIndex = seat,
                    activeTransactionId = transactionId,
                    nextTransactionId = nextHomeTomeTransactionId(transactionId),
                ),
            )
        }
        is HomeTomeArrangeIntent.Drop -> {
            val active = state.activeTransactionId ?: return HomeTomeArrangeUpdate(state)
            if (intent.transactionId != active) {
                return HomeTomeArrangeUpdate(state)
            }
            val target = intent.seatIndex.coerceIn(0, state.workingSlots.lastIndex)
            val preview = retargetHomeTomeArrangeInternal(state, target).state
            val origin = requireNotNull(preview.carryOriginIndex)
            if (target == origin) {
                HomeTomeArrangeUpdate(clearHomeTomeCarry(preview, preview.baselineSlots, origin))
            } else {
                val committed = preview.workingSlots
                HomeTomeArrangeUpdate(
                    state = clearHomeTomeCarry(preview, committed, target).copy(baselineSlots = committed),
                    committedSlots = committed,
                    committedTransactionId = active,
                    rollbackSlots = preview.baselineSlots,
                    rollbackFocusIndex = origin,
                )
            }
        }
        is HomeTomeArrangeIntent.Cancel -> {
            val active = state.activeTransactionId
            if (active == null || intent.transactionId != active) {
                HomeTomeArrangeUpdate(state)
            } else {
                val origin = requireNotNull(state.carryOriginIndex)
                HomeTomeArrangeUpdate(clearHomeTomeCarry(state, state.baselineSlots, origin))
            }
        }
        HomeTomeArrangeIntent.Exit -> if (state.carriedItemId == null) {
            HomeTomeArrangeUpdate(settleHomeTomeArrangeExit(state), exitRequested = true)
        } else {
            HomeTomeArrangeUpdate(state)
        }
    }
}

private fun settleHomeTomeArrangeExit(state: HomeTomeArrangeState): HomeTomeArrangeState {
    val settledFocus = if (state.workingSlots.isEmpty()) 0 else {
        state.focusedIndex.coerceIn(0, state.workingSlots.lastIndex)
    }
    val settledPage = homeTomePageForIndex(settledFocus, state.workingSlots.size)
    return state.copy(
        focusedIndex = settledFocus,
        pageTransition = if (settledPage == state.pageTransition.settledPage) {
            state.pageTransition
        } else {
            retargetHomeTomePageTransition(state.pageTransition, settledPage)
        },
    )
}

/** Direct touch retargeting enters the same reducer as controller movement. */
fun retargetHomeTomeArrange(
    state: HomeTomeArrangeState,
    targetIndex: Int,
    navigation: HomeTomeNavigationUpdate? = null,
): HomeTomeArrangeUpdate = retargetHomeTomeArrangeInternal(state, targetIndex, navigation)

private fun retargetHomeTomeArrangeInternal(
    state: HomeTomeArrangeState,
    targetIndex: Int,
    navigation: HomeTomeNavigationUpdate? = null,
): HomeTomeArrangeUpdate {
    if (state.workingSlots.isEmpty()) return HomeTomeArrangeUpdate(state)
    val target = targetIndex.coerceIn(0, state.workingSlots.lastIndex)
    val movedSlots = state.carryOriginIndex?.let { origin ->
        homeTomeArrangePreview(state.baselineSlots, origin, target)
    } ?: state.workingSlots
    val targetPage = homeTomePageForIndex(target, state.workingSlots.size)
    val resolvedNavigation = navigation ?: HomeTomeNavigationUpdate(
        targetIndex = target,
        targetPage = targetPage,
        transition = if (targetPage == state.pageTransition.settledPage) {
            state.pageTransition
        } else {
            retargetHomeTomePageTransition(state.pageTransition, targetPage)
        },
        moved = target != state.focusedIndex,
    )
    return HomeTomeArrangeUpdate(
        state = state.copy(
            workingSlots = movedSlots,
            focusedIndex = target,
            carryDestinationIndex = target.takeIf { state.carriedItemId != null },
            pageTransition = resolvedNavigation.transition,
        ),
        navigation = resolvedNavigation,
    )
}

/**
 * Apple-style sparse reorder law. Empty destinations are direct placements; occupied destinations
 * insertion-shift the whole source/destination interval, including null wells.
 */
fun homeTomeArrangePreview(
    baselineSlots: List<HomeShelfItemId?>,
    originIndex: Int,
    destinationIndex: Int,
): List<HomeShelfItemId?> {
    if (baselineSlots.isEmpty()) return baselineSlots
    val origin = originIndex.coerceIn(0, baselineSlots.lastIndex)
    val destination = destinationIndex.coerceIn(0, baselineSlots.lastIndex)
    val carried = baselineSlots[origin] ?: return baselineSlots
    if (origin == destination) return baselineSlots
    val result = baselineSlots.toMutableList()
    if (baselineSlots[destination] == null) {
        result[origin] = null
        result[destination] = carried
        return result
    }
    if (origin < destination) {
        for (index in origin until destination) result[index] = baselineSlots[index + 1]
    } else {
        for (index in origin downTo destination + 1) result[index] = baselineSlots[index - 1]
    }
    result[destination] = carried
    return result
}

private fun clearHomeTomeCarry(
    state: HomeTomeArrangeState,
    slots: List<HomeShelfItemId?>,
    focusIndex: Int,
): HomeTomeArrangeState {
    val safeFocus = if (slots.isEmpty()) 0 else focusIndex.coerceIn(0, slots.lastIndex)
    val page = homeTomePageForIndex(safeFocus, slots.size)
    return state.copy(
        workingSlots = slots,
        focusedIndex = safeFocus,
        carriedItemId = null,
        carryOriginIndex = null,
        carryDestinationIndex = null,
        activeTransactionId = null,
        pageTransition = if (page == state.pageTransition.settledPage) {
            state.pageTransition
        } else {
            retargetHomeTomePageTransition(state.pageTransition, page)
        },
    )
}

private fun nextHomeTomeTransactionId(current: Long): Long =
    if (current == Long.MAX_VALUE) 1L else current + 1L

/** Favorite removal is authoritative even mid-Arrange; removing the carried game cancels the carry safely. */
fun reconcileHomeTomeArrange(
    state: HomeTomeArrangeState,
    survivingIds: Set<HomeShelfItemId>,
): HomeTomeArrangeState {
    val baseline = state.baselineSlots.map { it?.takeIf(survivingIds::contains) }
    val working = state.workingSlots.map { it?.takeIf(survivingIds::contains) }
    if (baseline.none { it != null }) {
        return HomeTomeArrangeState(
            baselineSlots = emptyList(),
            focusedIndex = 0,
            nextTransactionId = state.nextTransactionId,
        )
    }
    val carriedSurvives = state.carriedItemId?.let(survivingIds::contains) ?: true
    val resolvedWorking = if (carriedSurvives) working else baseline
    val focusedIndex = state.focusedIndex.takeIf { it in resolvedWorking.indices }
        ?: nearestOccupiedHomeTomeSeat(resolvedWorking, state.focusedIndex)
    val focusedPage = homeTomePageForIndex(focusedIndex, resolvedWorking.size)
    return HomeTomeArrangeState(
        baselineSlots = baseline,
        workingSlots = resolvedWorking,
        focusedIndex = focusedIndex,
        carriedItemId = state.carriedItemId.takeIf { carriedSurvives },
        carryOriginIndex = state.carryOriginIndex.takeIf { carriedSurvives },
        carryDestinationIndex = state.carryDestinationIndex.takeIf { carriedSurvives },
        activeTransactionId = state.activeTransactionId.takeIf { carriedSurvives },
        nextTransactionId = state.nextTransactionId,
        pageTransition = if (focusedPage == state.pageTransition.settledPage) {
            state.pageTransition
        } else {
            retargetHomeTomePageTransition(state.pageTransition, focusedPage)
        },
    )
}

/** Browse and Carry share this one finite navigation law, including explicit page-transition output. */
fun navigateHomeTomeFolio(
    currentIndex: Int,
    itemCount: Int,
    action: ControllerAction,
    transition: HomeTomePageTransition = HomeTomePageTransition(
        settledPage = homeTomePageForIndex(currentIndex, itemCount),
    ),
): HomeTomeNavigationUpdate {
    val current = if (itemCount <= 0) 0 else currentIndex.coerceIn(0, itemCount - 1)
    val target = nextHomeTomeGridIndex(current, itemCount, action)
    val targetPage = homeTomePageForIndex(target, itemCount)
    val retargeted = if (targetPage == transition.settledPage) {
        transition
    } else {
        retargetHomeTomePageTransition(transition, targetPage)
    }
    return HomeTomeNavigationUpdate(
        targetIndex = target,
        targetPage = targetPage,
        transition = retargeted,
        moved = target != current,
    )
}

/**
 * Vertical-leaf law: LEFT/RIGHT stay inside the current 6×3 page.
 * UP/DOWN walk the same column continuously, so crossing a bottom/top row advances to the next/previous
 * page. This keeps a new Folio leaf spatially below/above the current one instead of inventing a
 * horizontal seventh column.
 */
internal fun nextHomeTomeGridIndex(
    currentIndex: Int,
    itemCount: Int,
    action: ControllerAction,
): Int {
    if (itemCount <= 0) return 0
    val current = currentIndex.coerceIn(0, itemCount - 1)
    val column = homeTomeFolioColumnOfIndex(current)
    return when (action) {
        ControllerAction.NAV_LEFT ->
            if (column > 0) current - 1 else current
        ControllerAction.NAV_RIGHT ->
            if (column < HomeTomeFolioColumns - 1 && current + 1 < itemCount) current + 1 else current
        ControllerAction.NAV_UP ->
            if (current - HomeTomeFolioColumns >= 0) current - HomeTomeFolioColumns else current
        ControllerAction.NAV_DOWN ->
            if (current + HomeTomeFolioColumns < itemCount) current + HomeTomeFolioColumns else current
        else -> current
    }
}

internal fun homeTomePageCount(itemCount: Int): Int =
    ((itemCount.coerceAtLeast(1) - 1) / HomeTomeFolioPageSize) + 1

fun homeTomePageForIndex(index: Int, itemCount: Int): Int =
    if (itemCount <= 0) 0 else index.coerceIn(0, itemCount - 1) / HomeTomeFolioPageSize

internal fun <T> homeTomePage(items: List<T>, pageIndex: Int): List<T> {
    val page = pageIndex.coerceIn(0, homeTomePageCount(items.size) - 1)
    val start = page * HomeTomeFolioPageSize
    return items.subList(start, minOf(items.size, start + HomeTomeFolioPageSize))
}

/** Current page and its immediate neighbors are the complete, bounded prewarm structure. */
internal fun <T> homeTomeWarmWindow(items: List<T>, pageIndex: Int): List<T> {
    if (items.isEmpty()) return emptyList()
    val firstPage = (pageIndex - 1).coerceAtLeast(0)
    val lastPage = (pageIndex + 1).coerceAtMost(homeTomePageCount(items.size) - 1)
    val start = firstPage * HomeTomeFolioPageSize
    val end = minOf(items.size, (lastPage + 1) * HomeTomeFolioPageSize)
    return items.subList(start, end)
}

data class HomeTomePageTransition(
    val settledPage: Int = 0,
    val outgoingPage: Int? = null,
    val generation: Long = 0L,
)

fun retargetHomeTomePageTransition(state: HomeTomePageTransition, targetPage: Int): HomeTomePageTransition =
    if (targetPage == state.settledPage) state else HomeTomePageTransition(
        settledPage = targetPage.coerceAtLeast(0),
        outgoingPage = state.settledPage,
        generation = state.generation + 1L,
    )

/** -1 enters from above, +1 enters from below, 0 means no page handoff is pending. */
internal fun homeTomeFolioVerticalPageDirection(transition: HomeTomePageTransition): Int {
    val outgoing = transition.outgoingPage ?: return 0
    return transition.settledPage.compareTo(outgoing)
}

/** Activation remains closed until both horizontal window travel and vertical page travel have settled. */
internal fun homeTomeFolioPageMotionSettled(
    reducedMotion: Boolean,
    pageOffset: Float,
    pageMotionRunning: Boolean,
): Boolean = reducedMotion || (!pageMotionRunning && pageOffset == 0f)

/** First controller/touch interaction removes any outgoing page residue immediately and idempotently. */
fun cancelHomeTomePageResidue(state: HomeTomePageTransition): HomeTomePageTransition =
    if (state.outgoingPage == null) state else state.copy(outgoingPage = null)

// ── Cross-page handoff: pre-render, coalescing, and motion-derived settlement ─────────────────────
//
// The reducer makes the destination page authoritative in the SAME frame that focus crosses the
// boundary, so the painted grid used to swap all 18 seat identities instantly and only then slide.
// Every cell subtree was torn down and rebuilt at the swap, which is what read as a teleport with a
// blank/fallback beat under it. The helpers below let the painter keep the outgoing leaf on screen
// across the existing vertical motion so the destination has that whole window to hydrate behind it.

/**
 * Whether the outgoing page must still be PAINTED this frame, underneath the authoritative
 * destination. It is a backdrop only: never focusable, never interactive, and never a second
 * arrange target — it exists so the viewport is never empty while the destination's art hydrates.
 *
 * Bounded by the live motion, not by the model: the transition keeps [HomeTomePageTransition.outgoingPage]
 * until the next interaction clears it, but nothing may compose a second leaf once the motion is at rest.
 */
internal fun homeTomeFolioPaintsOutgoingLeaf(
    outgoingPage: Int?,
    settledPage: Int,
    pageOffset: Float,
    pageMotionRunning: Boolean,
    reducedMotion: Boolean,
): Boolean {
    if (reducedMotion) return false
    val outgoing = outgoingPage ?: return false
    if (outgoing == settledPage) return false
    return pageMotionRunning || pageOffset != 0f
}

/**
 * Opacity of the outgoing backdrop across the handoff: full at the start of the travel, gone when the
 * motion settles. The destination's own veil (its slight alpha dip) is unchanged and still runs on top,
 * so the pair reads as one cross-dissolve rather than two competing fades.
 */
internal fun homeTomeFolioOutgoingLeafAlpha(pageOffset: Float): Float =
    kotlin.math.abs(pageOffset).coerceIn(0f, 1f)

/**
 * Where the vertical page motion should START for a newly published handoff.
 *
 * Each logical leaf crossing advances the pending live offset by [direction], clamped to [-1, 1], so a
 * held D-pad reads as one continuous sheet rather than reopening a full leaf after every repeat.
 */
internal fun homeTomeFolioPageMotionStart(
    liveOffset: Float,
    direction: Int,
): Float {
    if (direction == 0) return 0f
    return (liveOffset + direction.toFloat()).coerceIn(-1f, 1f)
}

/** Composition-visible offset until the Animatable generation acknowledges the crossing frame. */
internal fun homeTomeFolioComposedPageOffset(
    transitionGeneration: Long,
    animatedGeneration: Long,
    liveOffset: Float,
    startOffset: Float,
): Float = if (transitionGeneration == animatedGeneration) liveOffset else startOffset

/** Outgoing leaf is painted one full travel opposite the incoming direction — the previous sheet, not a ghost. */
internal fun homeTomeFolioOutgoingLeafShift(
    direction: Int,
    travelDistance: Float,
): Float = -direction.toFloat() * travelDistance

/** Page motion is the sole vertical pose owner while its offset is live. */
internal fun homeTomeFolioPageOwnsOverlayY(
    pageOffset: Float,
    pageMotionRunning: Boolean,
): Boolean = pageMotionRunning || kotlin.math.abs(pageOffset) > 0.0001f

/**
 * Extra settle wait AFTER the observable animations report idle, in milliseconds.
 *
 * A page handoff runs [com.gruning.mimir.rewrite.core.ui.MimirMotion.SceneMs] of vertical travel while
 * the per-cell seat tween ([com.gruning.mimir.rewrite.core.ui.MimirMotion.FocusMs]) runs concurrently
 * and finishes first — so awaiting the page motion already covers the cells, and any further pad is dead
 * time the player feels on the input path. Horizontal-only travel has no such cover, so there the cell
 * tween is still waited out. This is derived from the motion durations, not a chosen delay.
 */
internal fun homeTomeFolioSettleTailMillis(
    pageHandoff: Boolean,
    focusMillis: Int,
    sceneMillis: Int,
): Long = if (pageHandoff && sceneMillis >= focusMillis) 0L else focusMillis.toLong()

/** Exact 1:1 sources fill; every other ratio is fully contained (float epsilon only). */
internal const val HomeTomeFolioArtSquareEpsilonRatio = 1e-4f

internal fun homeTomeFolioArtShouldFillWell(
    width: Float,
    height: Float,
): Boolean {
    if (width <= 0f || height <= 0f) return false
    val scale = maxOf(width, height)
    return kotlin.math.abs(width - height) <= scale * HomeTomeFolioArtSquareEpsilonRatio
}

/** Verified square sources fill; portrait, landscape, unknown, and non-square physical art stay contained. */
internal fun homeTomeFolioArtUsesFillScale(
    @Suppress("UNUSED_PARAMETER") physical: Boolean,
    intrinsicWidth: Float,
    intrinsicHeight: Float,
): Boolean = homeTomeFolioArtShouldFillWell(intrinsicWidth, intrinsicHeight)

/** Pure helper-safe path for [androidx.compose.ui.geometry.Size.Unspecified] intrinsic reads. */
internal fun homeTomeFolioArtIntrinsicDimensions(
    intrinsicWidth: Float,
    intrinsicHeight: Float,
    specified: Boolean,
): Pair<Float, Float> = if (specified) intrinsicWidth to intrinsicHeight else 0f to 0f

/**
 * One physical expression for grid and carried call sites — now READ from the resolved face rather than
 * inferred from it.
 *
 * **What it used to be, and why that was wrong twice over:**
 * `wellArtUri != null && wellArtUri == physicalMediaUri` — the face is physical if it happens to equal
 * the hand pick.
 *
 *  1. It said NO to every provider render. C2's switch-on tier and DL-4's last tier both hand back a
 *     disc that is not the hand pick, so a well showing a downloaded disc reported itself a cover.
 *  2. It says YES to a hand-picked COVER, which C4 now allows to be the wired face.
 *
 * Both disappear when the tier that CHOSE the face also reports what it chose ([MimirShelfFace]).
 */
internal fun homeTomeFolioMemberIsPhysical(face: MimirShelfFace): Boolean =
    face.uri != null && face.shape == ShelfFaceShape.PHYSICAL

/**
 * A known local URI never flashes a substitute glyph while Coil hydrates, retries, or fails decode.
 * Placeholders are reserved for the explicit no-art case.
 */
internal fun homeTomeFolioArtShowsPlaceholder(
    artUri: String?,
    @Suppress("UNUSED_PARAMETER") painterReady: Boolean,
): Boolean = artUri == null

/** Known art that is not yet ready (loading or failed decode) paints a quiet palette backdrop only. */
internal fun homeTomeFolioArtShowsQuietBackdrop(
    artUri: String?,
    artReady: Boolean,
): Boolean = artUri != null && !artReady

/**
 * Whether a cell should skip building its Coil painter on this composition.
 *
 * The folio's opening frame builds one painter per mounted cell inside Compose's measure pass, which
 * measured 12.2 ms of a ~45 ms phase on the Thor. Deferring by one frame removes that from the frame
 * the press is waiting on. Art that is genuinely absent is NOT deferred -- there is nothing to build
 * and nothing to gain, and it keeps the deferral from ever being confused with the no-art state.
 */
internal fun homeTomeFolioDefersArtPainter(
    folioArtAttached: Boolean,
    artUri: String?,
): Boolean = !folioArtAttached && artUri != null
