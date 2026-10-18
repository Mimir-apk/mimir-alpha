package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * C5-F2 and C5-F3 — which kind of picture is wired as a game's face, and what ABSENCE means.
 *
 * These are rules about **first boot on a device that already holds picks**. That situation happens once
 * and cannot be re-run on the reference hardware once it has passed, so it has to be provable here rather than
 * observed there.
 */
class WiredFaceKindTest {

    private fun resolve(
        recorded: WiredFaceKind? = null,
        state: MediaState = MediaState.SHELF,
        cover: Boolean = false,
        physical: Boolean = false,
        square: Boolean = false,
        squareAt: Long? = null,
        faceAt: Long? = null,
    ) = wiredFaceKind(
        recorded, state, cover, physical,
        hasSquarePick = square,
        squarePickedAtMillis = squareAt,
        facePickedAtMillis = faceAt,
    )

    @Test
    fun `an explicit record wins in either state`() {
        listOf(MediaState.SHELF, MediaState.LAUNCH_CARD).forEach { state ->
            assertEquals(
                "C4: his last choice is honoured, and nothing else may overrule it",
                WiredFaceKind.COVER,
                resolve(recorded = WiredFaceKind.COVER, state = state, physical = true),
            )
            assertEquals(
                WiredFaceKind.PHYSICAL,
                resolve(recorded = WiredFaceKind.PHYSICAL, state = state, cover = true),
            )
        }
    }

    /**
     * The asymmetric absent-record behavior preserves existing selections during migration.
     *
     * The record is new, so it is absent for every pick already on the reference device. Reading absent as "never
     * wired" in BOTH states would drop every existing cartridge pick out of the top tier on first boot, and
     * the Tome wells would paint the provider's downloaded render instead of the manually chosen image.
     */
    @Test
    fun `absence means never-wired for the launch card and consult-the-stores for the shelf`() {
        // LAUNCH CARD: correct by construction. No file can exist under a key never written.
        assertNull(resolve(state = MediaState.LAUNCH_CARD, cover = true, physical = true))
        assertNull(resolve(state = MediaState.LAUNCH_CARD, cover = true))
        assertNull(resolve(state = MediaState.LAUNCH_CARD, physical = true))

        // SHELF: a legacy pick still counts as a wiring, with nothing re-made.
        assertEquals(WiredFaceKind.PHYSICAL, resolve(physical = true))
        assertEquals(WiredFaceKind.COVER, resolve(cover = true))
        assertNull("no picks at all is not a wiring", resolve())
    }

    /**
     * C5-F3 — a game with BOTH picks and no record.
     *
     * The Cartridge Hero switch used to arbitrate this tie, which was never a C4 breach because both
     * candidates were his own choices. With the switch removed  the tie resolves
     * to COVER unconditionally — the answer it already gave in the switch's shipped OFF position, so no
     * device changes behaviour. It is still self-healing: the first pick under this build writes a record,
     * and the tie is never consulted for that game again, which the first test above proves.
     */
    @Test
    fun `two legacy picks and no record resolve to the cover, once`() {
        assertEquals(WiredFaceKind.COVER, resolve(cover = true, physical = true))

        // And the moment a record exists, the tie is not consulted at all -- in both directions.
        assertEquals(
            WiredFaceKind.COVER,
            resolve(recorded = WiredFaceKind.COVER, cover = true, physical = true),
        )
        assertEquals(
            WiredFaceKind.PHYSICAL,
            resolve(recorded = WiredFaceKind.PHYSICAL, cover = true, physical = true),
        )
    }

    /** Nothing may conjure a wiring out of nothing; this only ever arbitrates picks that exist. */
    @Test
    fun `no picks never wires a face`() {
        assertNull(resolve())
        assertNull(resolve(state = MediaState.LAUNCH_CARD))
    }

    // -- 2026-10-10: the square joins the picks (one shelf face, "Icon art") ----------------------------

    /**
     * No record, and a manually picked icon. This is today's Home precedence verbatim -- the stamped tile
     * outranked every face -- so a pick made before this build keeps showing as it does now (C4).
     */
    @Test
    fun `no record and an icon he picked resolves to the square, ahead of his other picks`() {
        assertEquals(WiredFaceKind.SQUARE, resolve(square = true))
        assertEquals(WiredFaceKind.SQUARE, resolve(square = true, cover = true))
        assertEquals(WiredFaceKind.SQUARE, resolve(square = true, physical = true))
        assertEquals(WiredFaceKind.SQUARE, resolve(square = true, cover = true, physical = true))
    }

    /**
     * The latest explicit choice wins, using each pick's timestamp. Every cover or disc pick writes a
     * record; an icon pick writes none (nothing records SQUARE yet), so a box or disc record identifies
     * only the latest FACE pick. It must not hide a later icon pick: Home previously let that icon win,
     * and a stale record must not permanently outrank it.
     */
    @Test
    fun `a newer icon beats an older recorded cover or disc`() {
        assertEquals(
            WiredFaceKind.SQUARE,
            resolve(recorded = WiredFaceKind.COVER, square = true, cover = true, squareAt = 2_000L, faceAt = 1_000L),
        )
        assertEquals(
            WiredFaceKind.SQUARE,
            resolve(recorded = WiredFaceKind.PHYSICAL, square = true, physical = true, squareAt = 2_000L, faceAt = 1_000L),
        )
    }

    @Test
    fun `a newer cover or disc beats an older icon, recorded or not`() {
        assertEquals(
            WiredFaceKind.COVER,
            resolve(recorded = WiredFaceKind.COVER, square = true, cover = true, squareAt = 1_000L, faceAt = 2_000L),
        )
        assertEquals(
            WiredFaceKind.PHYSICAL,
            resolve(recorded = WiredFaceKind.PHYSICAL, square = true, physical = true, squareAt = 1_000L, faceAt = 2_000L),
        )
        // No record at all (a legacy pick): the newer of the two is still his last choice.
        assertEquals(
            WiredFaceKind.COVER,
            resolve(square = true, cover = true, squareAt = 1_000L, faceAt = 2_000L),
        )
        assertEquals(
            WiredFaceKind.PHYSICAL,
            resolve(square = true, physical = true, squareAt = 1_000L, faceAt = 2_000L),
        )
    }

    /** A missing time cannot say which is newer, so the icon wins: today's Home behaviour, and a tie goes the same way. */
    @Test
    fun `without both times the icon wins, as it always did on Home`() {
        assertEquals(WiredFaceKind.SQUARE, resolve(recorded = WiredFaceKind.COVER, square = true, cover = true))
        assertEquals(
            WiredFaceKind.SQUARE,
            resolve(recorded = WiredFaceKind.COVER, square = true, cover = true, squareAt = 1_000L),
        )
        assertEquals(
            WiredFaceKind.SQUARE,
            resolve(recorded = WiredFaceKind.COVER, square = true, cover = true, faceAt = 1_000L),
        )
        assertEquals(
            WiredFaceKind.SQUARE,
            resolve(recorded = WiredFaceKind.COVER, square = true, cover = true, squareAt = 1_000L, faceAt = 1_000L),
        )
    }

    /** A record naming a kind that has no file behind it leaves the icon as the only pick he holds. */
    @Test
    fun `a record with no file behind it does not hide his icon`() {
        assertEquals(WiredFaceKind.SQUARE, resolve(recorded = WiredFaceKind.PHYSICAL, square = true))
    }

    /** The square's own record is his last choice outright; a later cover or disc pick overwrites it. */
    @Test
    fun `a record naming the square wins whatever the times say`() {
        assertEquals(
            WiredFaceKind.SQUARE,
            resolve(recorded = WiredFaceKind.SQUARE, cover = true, physical = true, squareAt = 1_000L, faceAt = 2_000L),
        )
        assertEquals(WiredFaceKind.SQUARE, resolve(recorded = WiredFaceKind.SQUARE))
    }

    /**
     * For the new kind, a launch card with no record is "never wired", because no launch-card square can
     * exist (the key that would address one has never been written), so the tie-break must not conjure one.
     */
    @Test
    fun `a square pick never wires the launch card by absence`() {
        assertNull(resolve(state = MediaState.LAUNCH_CARD, square = true))
        assertNull(resolve(state = MediaState.LAUNCH_CARD, square = true, cover = true, physical = true))
    }

    @Test
    fun `the launch card never takes the square, whatever the times say`() {
        assertEquals(
            WiredFaceKind.COVER,
            resolve(recorded = WiredFaceKind.COVER, state = MediaState.LAUNCH_CARD, square = true, cover = true, squareAt = 2_000L, faceAt = 1_000L),
        )
    }

    /** The default keeps every caller that reads only the two stores exactly where it was. */
    @Test
    fun `the square defaults off`() {
        assertEquals(WiredFaceKind.COVER, wiredFaceKind(null, MediaState.SHELF, hasCoverPick = true, hasPhysicalPick = true))
        assertNull(wiredFaceKind(null, MediaState.SHELF, hasCoverPick = false, hasPhysicalPick = false))
    }

    // -- 2026-10-11: the square is compared with the pick that WINS, not with the newer of the two --------

    private fun resolveExact(
        recorded: WiredFaceKind? = null,
        cover: Boolean = false,
        physical: Boolean = false,
        squareAt: Long? = null,
        coverAt: Long? = null,
        physicalAt: Long? = null,
        legacyFaceAt: Long? = null,
    ) = wiredFaceKind(
        recorded, MediaState.SHELF, cover, physical,
        hasSquarePick = true,
        squarePickedAtMillis = squareAt,
        facePickedAtMillis = legacyFaceAt,
        coverPickedAtMillis = coverAt,
        physicalPickedAtMillis = physicalAt,
    )

    /**
     * THE TOME_PHYSICAL MARKER EDGE. A disc marker can be newer than the cover it sits beside without that being a pick of the
     * cover's era: an old Cartridge Hero pick adopted when a picker opened (its time is the file's), or a marker an identity replay
     * wrote. The coarse "newer of cover and disc" then made a recorded cover that is OLDER than the selected icon look newer than it, and the
     * old cover beat the newer icon. The icon is compared with the time of the face that would actually win.
     */
    @Test
    fun `a newer disc marker cannot let an older recorded cover beat a newer icon`() {
        // cover 1_000, disc 3_000, icon 2_000. The record names the cover, so the cover's 1_000 is the time to beat.
        assertEquals(
            "the cover is the face that wins without the icon, and it is older than the icon",
            WiredFaceKind.SQUARE,
            resolveExact(recorded = WiredFaceKind.COVER, cover = true, physical = true, squareAt = 2_000L, coverAt = 1_000L, physicalAt = 3_000L),
        )
        // The same facts through the coarse input are the bug, kept answerable for a caller that has not learned the exact times.
        assertEquals(
            WiredFaceKind.COVER,
            resolveExact(recorded = WiredFaceKind.COVER, cover = true, physical = true, squareAt = 2_000L, legacyFaceAt = 3_000L),
        )
    }

    @Test
    fun `with no record the tie-break names the cover, and the icon is compared with the cover`() {
        // Cover and disc, no record: the cover wins the tie (C5-F3), so its time is the one the icon is compared with.
        assertEquals(
            WiredFaceKind.SQUARE,
            resolveExact(cover = true, physical = true, squareAt = 2_000L, coverAt = 1_000L, physicalAt = 3_000L),
        )
        assertEquals(
            "and a cover newer than the icon still wins, whatever the disc says",
            WiredFaceKind.COVER,
            resolveExact(cover = true, physical = true, squareAt = 2_000L, coverAt = 2_500L, physicalAt = 1_000L),
        )
    }

    @Test
    fun `a recorded disc is compared with the disc's time, and its file may be in the cover store`() {
        assertEquals(
            WiredFaceKind.PHYSICAL,
            resolveExact(recorded = WiredFaceKind.PHYSICAL, cover = true, physical = true, squareAt = 2_000L, coverAt = 500L, physicalAt = 3_000L),
        )
        assertEquals(
            "a stale cover marker does not hold a newer disc back from the icon either",
            WiredFaceKind.SQUARE,
            resolveExact(recorded = WiredFaceKind.PHYSICAL, cover = true, physical = true, squareAt = 2_000L, coverAt = 5_000L, physicalAt = 1_000L),
        )
        // A disc he picked through the cover lane is a PHYSICAL record whose file is in the cover store: that file's time counts.
        assertEquals(
            WiredFaceKind.SQUARE,
            resolveExact(recorded = WiredFaceKind.PHYSICAL, cover = true, physical = false, squareAt = 2_000L, coverAt = 1_000L),
        )
        assertEquals(
            WiredFaceKind.PHYSICAL,
            resolveExact(recorded = WiredFaceKind.PHYSICAL, cover = true, physical = false, squareAt = 2_000L, coverAt = 3_000L),
        )
    }

    @Test
    fun `an unknown time on the winning face still lets the icon win, as it always did`() {
        assertEquals(
            WiredFaceKind.SQUARE,
            resolveExact(recorded = WiredFaceKind.COVER, cover = true, physical = true, squareAt = 2_000L, coverAt = null, physicalAt = 9_000L),
        )
        assertEquals(
            WiredFaceKind.SQUARE,
            resolveExact(recorded = WiredFaceKind.COVER, cover = true, squareAt = null, coverAt = 9_000L),
        )
    }

    @Test
    fun `the exact times change nothing for a caller that gives only the coarse one`() {
        assertEquals(
            WiredFaceKind.COVER,
            resolveExact(recorded = WiredFaceKind.COVER, cover = true, squareAt = 1_000L, legacyFaceAt = 2_000L),
        )
        assertEquals(
            WiredFaceKind.SQUARE,
            resolveExact(recorded = WiredFaceKind.COVER, cover = true, squareAt = 3_000L, legacyFaceAt = 2_000L),
        )
    }
}

/**
 * Which face kind a provider media type IS — the fact that decides the LANE.
 *
 * This exists because the face picker offers boxes and the physical article in one grid and saves both
 * through `EnrichMediaKind.COVER`. Without a recorded type, a selected disc was reported as a cover and
 * drawn in a portrait lane instead of the square one: right picture, wrong shape.
 */
class WiredFaceKindOfTest {

    @Test
    fun `the physical article is a disc and every box is a cover`() {
        listOf("support-2d", "support-3d", "support-label", "support-texture").forEach {
            assertEquals(WiredFaceKind.PHYSICAL, wiredFaceKindOf(it))
        }
        listOf("box-2d", "box-3d", "box-texture", "box-2d-back", "box-scan").forEach {
            assertEquals(WiredFaceKind.COVER, wiredFaceKindOf(it))
        }
    }

    /**
     * A PREFIX test rather than a list, on purpose: the provider adds media types over time, and a new
     * `support-…` should be a disc on the day it appears rather than on the day someone remembers to
     * extend a set.
     */
    @Test
    fun `an unseen member of either family is classified on the day it appears`() {
        assertEquals(WiredFaceKind.PHYSICAL, wiredFaceKindOf("support-hologram"))
        assertEquals(WiredFaceKind.COVER, wiredFaceKindOf("box-2d-spine-alt"))
    }

    /**
     * Null for anything that genuinely has no provider type — an upload, a legacy file adopted from disk.
     * Inventing a kind there would be worse than falling back to the store-consult, which is what shipped
     * before this existed.
     */
    @Test
    fun `no provider type means no claim`() {
        listOf(null, "", "   ", "wheel", "fanart").forEach {
            assertNull("must not guess a face kind from $it", wiredFaceKindOf(it))
        }
    }

    /** Case and padding come from provider JSON, not from us. */
    @Test
    fun `classification survives provider casing`() {
        assertEquals(WiredFaceKind.PHYSICAL, wiredFaceKindOf("  Support-2D  "))
        assertEquals(WiredFaceKind.COVER, wiredFaceKindOf("BOX-3D"))
    }
}
