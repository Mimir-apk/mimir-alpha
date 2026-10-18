package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.graphics.Color

data class MimirMoltenSeparatorArt(
    val crestStops: List<Pair<Float, Color>>,
    val coreStops: List<Pair<Float, Color>>,
    val floor: Color,
)

/** Shipping-authored shelf art colors that are intentionally not derived from a live theme palette. */
object MimirShelfArt {
    val BookBindingGold = Color(0xFFD8B24E)

    val NiflheimMoltenCrestStops = arrayOf(
        0.00f to Color(0xFF020103), 0.10f to Color(0xFF0E0616), 0.22f to Color(0xFF1F0F33),
        0.34f to Color(0xFF3A1A52), 0.46f to Color(0xFF5B2A6E), 0.50f to Color(0xFF7C3A7E),
        0.54f to Color(0xFF5B2A6E), 0.66f to Color(0xFF3A1A52), 0.78f to Color(0xFF1F0F33),
        0.90f to Color(0xFF0E0616), 1.00f to Color(0xFF020103),
    )

    val SurtrMoltenCrestStops = arrayOf(
        0.00f to Color(0xFF060505), 0.08f to Color(0xFF3A0000), 0.18f to Color(0xFF8A0B0B),
        0.30f to Color(0xFFFF2D2D), 0.42f to Color(0xFFFF6A00), 0.50f to Color(0xFFFFCF6B),
        0.58f to Color(0xFFFF6A00), 0.70f to Color(0xFFFF2D2D), 0.82f to Color(0xFF8A0B0B),
        0.92f to Color(0xFF3A0000), 1.00f to Color(0xFF060505),
    )

    val SurtrMoltenFloor = Color(0xFFFF6A00)
    val NiflheimMoltenFloor = Color(0xFF7C3A7E)

    private val SurtrMoltenCore = Color(0xFFFFE7C8)
    private val NiflheimMoltenCoreEdge = Color(0xFF966EB4)
    private val NiflheimMoltenCore = Color(0xFFC8B4DC)

    val SurtrMoltenCoreStops = arrayOf(
        0.00f to Color.Transparent,
        0.20f to SurtrMoltenCore.copy(alpha = 0.90f),
        0.50f to Color.White,
        0.80f to SurtrMoltenCore.copy(alpha = 0.90f),
        1.00f to Color.Transparent,
    )

    val NiflheimMoltenCoreStops = arrayOf(
        0.00f to Color.Transparent,
        0.20f to NiflheimMoltenCoreEdge.copy(alpha = 0.35f),
        0.50f to NiflheimMoltenCore.copy(alpha = 0.60f),
        0.80f to NiflheimMoltenCoreEdge.copy(alpha = 0.35f),
        1.00f to Color.Transparent,
    )

    /**
     * The authoritative A2 molten lookup covers ALL NINE themes; the seven formerly parked
     * legacy stop sets are part of the supported contract.
     */
    fun moltenSeparatorFor(id: MimirThemeId): MimirMoltenSeparatorArt = when (id) {
        MimirThemeId.SURTR -> MimirMoltenSeparatorArt(
            crestStops = SurtrMoltenCrestStops.asList(),
            coreStops = SurtrMoltenCoreStops.asList(),
            floor = SurtrMoltenFloor,
        )
        MimirThemeId.NIFLHEIM -> MimirMoltenSeparatorArt(
            crestStops = NiflheimMoltenCrestStops.asList(),
            coreStops = NiflheimMoltenCoreStops.asList(),
            floor = NiflheimMoltenFloor,
        )
        MimirThemeId.DEFAULT -> MimirMoltenSeparatorArt(
            crestStops = DefaultMoltenCrestStops.asList(),
            coreStops = DefaultMoltenCoreStops.asList(),
            floor = DefaultMoltenFloor,
        )
        MimirThemeId.FREYR -> MimirMoltenSeparatorArt(
            crestStops = FreyrMoltenCrestStops.asList(),
            coreStops = FreyrMoltenCoreStops.asList(),
            floor = FreyrMoltenFloor,
        )
        MimirThemeId.MANI -> MimirMoltenSeparatorArt(
            crestStops = ManiMoltenCrestStops.asList(),
            coreStops = ManiMoltenCoreStops.asList(),
            floor = ManiMoltenFloor,
        )
        MimirThemeId.HEL -> MimirMoltenSeparatorArt(
            crestStops = HelMoltenCrestStops.asList(),
            coreStops = HelMoltenCoreStops.asList(),
            floor = HelMoltenFloor,
        )
        MimirThemeId.YGGDRASIL -> MimirMoltenSeparatorArt(
            crestStops = YggdrasilMoltenCrestStops.asList(),
            coreStops = YggdrasilMoltenCoreStops.asList(),
            floor = YggdrasilMoltenFloor,
        )
        MimirThemeId.FOLKVANGR -> MimirMoltenSeparatorArt(
            crestStops = FolkvangrMoltenCrestStops.asList(),
            coreStops = FolkvangrMoltenCoreStops.asList(),
            floor = FolkvangrMoltenFloor,
        )
        MimirThemeId.BALDR -> MimirMoltenSeparatorArt(
            crestStops = BaldrMoltenCrestStops.asList(),
            coreStops = BaldrMoltenCoreStops.asList(),
            floor = BaldrMoltenFloor,
        )
    }

    // Legacy compatibility constants below remain until the active feature-library shelf lane switches its
    // private rule lookup to moltenSeparatorFor(). They are not part of the supported material contract.

    // DEFAULT — gold on the deep-navy ground.
    val DefaultMoltenCrestStops = arrayOf(
        0.00f to Color(0xFF05080E), 0.10f to Color(0xFF0C1120), 0.22f to Color(0xFF1A2036),
        0.34f to Color(0xFF42381F), 0.46f to Color(0xFFA9843E), 0.50f to Color(0xFFEBC77A),
        0.54f to Color(0xFFA9843E), 0.66f to Color(0xFF42381F), 0.78f to Color(0xFF1A2036),
        0.90f to Color(0xFF0C1120), 1.00f to Color(0xFF05080E),
    )
    val DefaultMoltenFloor = Color(0xFFD4AF69)
    private val DefaultMoltenCoreEdge = Color(0xFFF0DCA8)
    val DefaultMoltenCoreStops = arrayOf(
        0.00f to Color.Transparent, 0.20f to DefaultMoltenCoreEdge.copy(alpha = 0.55f),
        0.50f to Color(0xFFFFF3D8).copy(alpha = 0.95f), 0.80f to DefaultMoltenCoreEdge.copy(alpha = 0.55f),
        1.00f to Color.Transparent,
    )

    // FREYR — warm forged gold.
    val FreyrMoltenCrestStops = arrayOf(
        0.00f to Color(0xFF080503), 0.10f to Color(0xFF1C1206), 0.22f to Color(0xFF3A2608),
        0.34f to Color(0xFF6E4A12), 0.46f to Color(0xFFC2902E), 0.50f to Color(0xFFFFD36F),
        0.54f to Color(0xFFC2902E), 0.66f to Color(0xFF6E4A12), 0.78f to Color(0xFF3A2608),
        0.90f to Color(0xFF1C1206), 1.00f to Color(0xFF080503),
    )
    val FreyrMoltenFloor = Color(0xFFC89A3E)
    private val FreyrMoltenCoreEdge = Color(0xFFFFE9B8)
    val FreyrMoltenCoreStops = arrayOf(
        0.00f to Color.Transparent, 0.20f to FreyrMoltenCoreEdge.copy(alpha = 0.85f),
        0.50f to Color(0xFFFFF6E0), 0.80f to FreyrMoltenCoreEdge.copy(alpha = 0.85f),
        1.00f to Color.Transparent,
    )

    // MANI — moonlit cool blue.
    val ManiMoltenCrestStops = arrayOf(
        0.00f to Color(0xFF02040A), 0.10f to Color(0xFF070C1A), 0.22f to Color(0xFF10203A),
        0.34f to Color(0xFF24406E), 0.46f to Color(0xFF4E6EB0), 0.50f to Color(0xFFC5D4FF),
        0.54f to Color(0xFF4E6EB0), 0.66f to Color(0xFF24406E), 0.78f to Color(0xFF10203A),
        0.90f to Color(0xFF070C1A), 1.00f to Color(0xFF02040A),
    )
    val ManiMoltenFloor = Color(0xFF6E86C8)
    private val ManiMoltenCoreEdge = Color(0xFFD4E0FF)
    val ManiMoltenCoreStops = arrayOf(
        0.00f to Color.Transparent, 0.20f to ManiMoltenCoreEdge.copy(alpha = 0.70f),
        0.50f to Color.White, 0.80f to ManiMoltenCoreEdge.copy(alpha = 0.70f),
        1.00f to Color.Transparent,
    )

    // HEL — dread blood-red; kept grim (dim rose core, never a cheery white).
    val HelMoltenCrestStops = arrayOf(
        0.00f to Color(0xFF030101), 0.08f to Color(0xFF1A0304), 0.18f to Color(0xFF3A060A),
        0.30f to Color(0xFF6E0C14), 0.42f to Color(0xFFA81020), 0.50f to Color(0xFFE3384D),
        0.58f to Color(0xFFA81020), 0.70f to Color(0xFF6E0C14), 0.82f to Color(0xFF3A060A),
        0.92f to Color(0xFF1A0304), 1.00f to Color(0xFF030101),
    )
    val HelMoltenFloor = Color(0xFF8A1020)
    private val HelMoltenCoreEdge = Color(0xFFC82837)
    val HelMoltenCoreStops = arrayOf(
        0.00f to Color.Transparent, 0.20f to HelMoltenCoreEdge.copy(alpha = 0.55f),
        0.50f to Color(0xFFF096A0).copy(alpha = 0.90f), 0.80f to HelMoltenCoreEdge.copy(alpha = 0.55f),
        1.00f to Color.Transparent,
    )

    // YGGDRASIL — bronze/amber sap with green-shadow roots at the edges.
    val YggdrasilMoltenCrestStops = arrayOf(
        0.00f to Color(0xFF040A06), 0.10f to Color(0xFF0E1A0E), 0.22f to Color(0xFF24301A),
        0.34f to Color(0xFF5A4A22), 0.46f to Color(0xFFA8863A), 0.50f to Color(0xFFE3B063),
        0.54f to Color(0xFFA8863A), 0.66f to Color(0xFF5A4A22), 0.78f to Color(0xFF24301A),
        0.90f to Color(0xFF0E1A0E), 1.00f to Color(0xFF040A06),
    )
    val YggdrasilMoltenFloor = Color(0xFFB8893E)
    private val YggdrasilMoltenCoreEdge = Color(0xFFF0D49A)
    val YggdrasilMoltenCoreStops = arrayOf(
        0.00f to Color.Transparent, 0.20f to YggdrasilMoltenCoreEdge.copy(alpha = 0.75f),
        0.50f to Color(0xFFFFF0D0), 0.80f to YggdrasilMoltenCoreEdge.copy(alpha = 0.75f),
        1.00f to Color.Transparent,
    )

    // FOLKVANGR (dark metallic) — a COLD seam: ash-white at the edges, worn bone at the core, so the
    // separator reads as the light coming off a field of shields rather than as hot metal.
    //
    // This was Brísingamen's rose until 2026-09-22 (floor #FF7896, a hot pink), the one part of Freyja's
    // necklace the rename missed.
    //
    // **It was never painted, and that is the point of changing it.** Both consumers gate first: the
    // header rule in `MimirHeaderRule.moltenRuleSpec` ships for Surtr and Niflheim only, and the Quick
    // Rail's plinth draws on `dockTreatment == MOLTEN`, which only those same two themes carry --
    // Fólkvangr's is QUIET, so its dock takes the ordinary contrast hairline. A dormant entry is still
    // worth correcting: it is loaded and ready for whoever gives this theme a molten dock, and a hot pink
    // seam under a field of the slain is not a discovery anyone should make at that moment. The crest now
    // reads the palette's own accent family and the core its counter-accent, so the seam belongs to the
    // theme rather than surviving it.
    val FolkvangrMoltenCrestStops = arrayOf(
        0.00f to Color(0xFFE8ECF0), 0.12f to Color(0xFFC9D1D9), 0.26f to Color(0xFFA8B2BC),
        0.40f to Color(0xFF8C97A2), 0.50f to Color(0xFF7A848F), 0.60f to Color(0xFF8C97A2),
        0.74f to Color(0xFFA8B2BC), 0.88f to Color(0xFFC9D1D9), 1.00f to Color(0xFFE8ECF0),
    )
    val FolkvangrMoltenFloor = Color(0xFFB6BEC7)
    val FolkvangrMoltenCoreStops = arrayOf(
        0.00f to Color.Transparent, 0.30f to Color(0xFFC7B183).copy(alpha = 0.50f),
        0.50f to Color(0xFFEDE3CC).copy(alpha = 0.85f), 0.70f to Color(0xFFC7B183).copy(alpha = 0.50f),
        1.00f to Color.Transparent,
    )

    // BALDR (dark metallic) — a clean sky-blue seam, saturated at centre, fading into the field.
    val BaldrMoltenCrestStops = arrayOf(
        0.00f to Color(0xFFCFE6FA), 0.12f to Color(0xFFA6D0F4), 0.26f to Color(0xFF6FB2ED),
        0.40f to Color(0xFF3E92DE), 0.50f to Color(0xFF1F6BB5), 0.60f to Color(0xFF3E92DE),
        0.74f to Color(0xFF6FB2ED), 0.88f to Color(0xFFA6D0F4), 1.00f to Color(0xFFCFE6FA),
    )
    val BaldrMoltenFloor = Color(0xFF5A96DC)
    val BaldrMoltenCoreStops = arrayOf(
        0.00f to Color.Transparent, 0.30f to Color.White.copy(alpha = 0.60f),
        0.50f to Color(0xFFEAF4FF).copy(alpha = 0.90f), 0.70f to Color.White.copy(alpha = 0.60f),
        1.00f to Color.Transparent,
    )
}
