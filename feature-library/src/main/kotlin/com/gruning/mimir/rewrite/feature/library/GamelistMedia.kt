package com.gruning.mimir.rewrite.feature.library

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirCopy
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipLabelStyle
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.mimirEtchedChipSurface
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * What the Library's game views keep from the card-style gamelist carousel that the Home shelf replaced :
 * the media-action seat and its chip (Enrich / Settings, on the LIST view's preview panel), the launch-status chip payload, the
 * flat-logo and activation helpers, and the per-platform cover proportions the LIST view, the launch card and the Bottom's platform
 * shelf still size their covers by. The card geometry, the cards and the destination lane are gone: the CAROUSEL view is
 * [LibraryGameShelf], a [GameShelf].
 */

/** The launch-status chip payload for a card. */
internal data class GameStatusChip(val label: String, val ready: Boolean)

internal enum class GamelistMediaAction(val label: String) {
    ENRICH("Enrich"),
    /**
     * renamed from
     * "Hero" — the case name stays [HERO] (an internal identifier, not player-facing) and
     * [GamelistGetArtKey]'s focus identity is untouched; only the rendered word changed.
     */
    HERO(MimirCopy.GamelistSettingsMenu),
}

/** Provider truth selects one stable action seat: first enrichment, then the Settings menu. */
internal fun gamelistMediaAction(state: ProviderEnrichmentState): GamelistMediaAction =
    if (state.shouldOfferEnrich) GamelistMediaAction.ENRICH else GamelistMediaAction.HERO

internal fun localFlatLogoUri(game: GameRow): String? {
    val value = game.flatArtUri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val scheme = value.substringBefore(':', missingDelimiterValue = "").lowercase()
    return value.takeIf {
        scheme == "content" || scheme == "file" || scheme == "android.resource"
    }
}

internal fun usesGamelistFlatLogo(game: GameRow): Boolean = localFlatLogoUri(game) != null

internal fun activateGamelistGameCard(
    authority: FocusAuthority,
    key: FocusKey,
    onOpen: () -> Unit,
) {
    authority.requestFocus(key)
    onOpen()
}

/**
 * The per-game metadata action has one stable focus seat. It reads ENRICH until a durable provider disposition
 * exists, then becomes HERO — labelled "Settings"  — and opens the top-level Customize
 * media / Add to Tome / Achievements menu without repeating enrichment; Customize media from there reaches the
 * same local-first hero picker this used to open directly. When [enabled]
 * is false (no ScreenScraper
 * account connected) it renders clearly FADED but stays fully focusable/tappable — activating it opens the
 * "Connect for artwork" prompt instead of fetching (the account requirement must be
 * discoverable, never a silent no-op). The click always consumes the tap so it can't reach the card's
 * launch handler.
 */
@Composable
internal fun GamelistMediaActionChip(
    action: GamelistMediaAction,
    focused: Boolean,
    enabled: Boolean,
    mediaContrastRail: Boolean,
    authority: FocusAuthority,
    onActivate: () -> Unit,
    compact: Boolean = false,
) {
    val palette = LocalMimirPalette.current
    val textColor = when {
        !enabled -> palette.textMuted // dimmed label when off
        mediaContrastRail -> palette.materials.readableAccent
        else -> palette.etchedChipInk(warmed = focused)
    }
    // Update: an unselected etched chip, not a tinted capsule. It keeps the height and padding it had: it sits in the detail band's fixed height, which must not grow, so
    // its tap area reaches 48 dp by Compose's own minimum touch target (no layout change), as the band's other small controls do.
    Box(
        modifier = Modifier
            // Not-connected reads "needs setup": clearly faded, not just a hue shift. It stays FULLY interactive
            // though — activating it opens the "connect an account" prompt rather than doing nothing — and the click always consumes the tap so it can't reach the card's launch handler.
            // The one ring (ring migration, 2026-09-28): a pill ring, no grow. The not-connected fade sits AFTER it
            // on a ModulateAlpha layer, so it dims the plate without cutting the ring (a plain alpha layer
            // composites at its own bounds).
            .mimirSelectionRing(focused = focused, identity = GamelistGetArtKey, shape = MimirRingShape.Pill, edgeFlush = true)
            .graphicsLayer {
                alpha = if (enabled) 1f else 0.45f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .mimirEtchedChipSurface(palette, warmed = false, focused = focused, edgeFlush = true)
            .mimirFocusable(authority, GamelistGetArtKey, GamelistScope, autoBringIntoView = false)
            .mimirActivatable(label = action.label, authority = authority, key = GamelistGetArtKey, onActivate = onActivate)
            .padding(
                horizontal = if (compact) MimirSpacing.sm else MimirSpacing.md,
                vertical = if (compact) 2.dp else MimirSpacing.xs,
            ),
        contentAlignment = Alignment.Center,
    ) {
        MimirChromeText(
            text = action.label,
            style = MimirEtchedChipLabelStyle,
            color = textColor,
            maxLines = 1,
        )
    }
}

/**
 * Per-platform cover proportion (width / height), measured from the real ScreenScraper box-2D art each
 * system serves . Uniform WITHIN a platform's gamelist, deliberately different across
 * platforms. Unknown platforms fall back to the common ~3:4 box shape.
 */
private val PlatformCoverAspects: Map<String, Float> = mapOf(
    // portrait box art (~3:4)
    "gamecube" to 0.72f, "wii" to 0.72f, "nes" to 0.73f, "genesis" to 0.71f, "megadrive" to 0.71f,
    "mastersystem" to 0.72f, "arcade" to 0.77f, "ps2" to 0.71f,
    // PROVISIONAL (no ps3 art in the library yet to measure): published
    // Blu-ray keep-case geometry 135×171.5mm. Re-measure from media_art when the first ps3 cover lands.
    "ps3" to 0.78f,
    // tall cases (UMD / Switch cart card)
    "psp" to 0.59f, "switch" to 0.62f,
    // square-ish (GB/GBA carts render ~square on ScreenScraper; disc jewels)
    "gb" to 1.0f, "gbc" to 1.0f, "gba" to 1.0f, "dreamcast" to 1.0f, "saturn" to 1.0f,
    // landscape (handheld cases / N64 / PS jewel / SNES box)
    // GL-RP5 : snes was MISSING and fell back to the 0.72 PORTRAIT cell — the landscape
    // cover Fit-shrank to ~53% of the cell height and the focus ring bloomed around the empty cell above
    // it ("way too small" + "selection focus off"). snes/ps2 measured from the on-device media_art
    // (680×500 and 486×680 PAL box-2D), same discipline as the rest of the map.
    "nds" to 1.11f, "ds" to 1.11f, "3ds" to 1.13f, "ps1" to 1.16f, "psx" to 1.16f, "n64" to 1.46f,
    "snes" to 1.36f,
)

internal const val DefaultCoverAspect = 0.72f

/**
 * Whether the art this game is painted with may teach the platform's cover proportion to [MeasuredCoverAspects].
 *
 * Only the AUTOMATIC cover tier may. [MeasuredCoverAspects.record] is first-write-wins and persists, and the shelf on the Bottom and the gamelist
 * card here both read it back as every game's standard cell for an uncurated platform. A face the player wired by hand is a choice about ONE game
 * (any size, any shape), and a provider's disc render is not a cover: either, arriving first, would have become the whole platform's proportion for good
 * (a changed art must be fitted into the platform's standard size, never set it).
 */
internal fun gamelistArtMayMeasurePlatform(game: GameRow, media: LibraryListPreviewMedia): Boolean =
    media.source == LibraryListPreviewMediaSource.COVER && game.wiredFace?.uri?.isNotBlank() != true

/**
 * The cover proportion for [platformId]. Resolution order: the curated measured map, then the runtime
 * measured store (GL-RP5 rev B safe fallback), then the common ~3:4 box shape. Snapshot-state read —
 * a composable calling this recomposes when a first-cover measure lands.
 */
internal fun platformCoverAspect(platformId: String): Float {
    val key = platformId.lowercase()
    return PlatformCoverAspects[key] ?: MeasuredCoverAspects.aspectFor(key) ?: DefaultCoverAspect
}

/**
 * GL-RP5 rev B: a platform
 * MISSING from [PlatformCoverAspects] gets its cell aspect MEASURED from the first cover that actually
 * decodes, then persisted — so an unmapped platform can never reproduce the snes failure (landscape art
 * crushed inside the portrait fallback cell, ring blooming around the empty cell above it). The curated
 * map ALWAYS wins; this store only fills gaps, clamps to the sane box range, and causes at most ONE
 * reflow ever per platform — at its very first cover fade-in, when the measured cell replaces the
 * default. Revisits land settled from the persisted value (hydrated at carousel mount).
 */
internal object MeasuredCoverAspects {
    // v2 (2026-10-08): v1 could hold an aspect learned from a hand-wired face before the automatic-cover gate; starting fresh drops it.
    private const val PrefsName = "mimir_rewrite_gamelist_aspects_v2"
    private val measured = mutableStateMapOf<String, Float>()
    private val loaded = AtomicBoolean(false)
    private var prefs: SharedPreferences? = null

    /** Snapshot-state read: composables calling this recompose when a first-cover measure lands. */
    fun aspectFor(platformKey: String): Float? = measured[platformKey]

    suspend fun ensureLoaded(context: Context) {
        if (!loaded.compareAndSet(false, true)) return
        val store = withContext(Dispatchers.IO) {
            context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
                .also { it.all } // force the XML parse off the main thread
        }
        prefs = store
        for ((key, value) in store.all) (value as? Float)?.let { measured[key] = it }
    }

    fun record(context: Context, platformId: String, widthPx: Float, heightPx: Float) {
        if (widthPx <= 0f || heightPx <= 0f) return
        val key = platformId.lowercase()
        if (PlatformCoverAspects.containsKey(key) || measured.containsKey(key)) return
        val aspect = (widthPx / heightPx).coerceIn(MeasuredCoverAspectMin, MeasuredCoverAspectMax)
        measured[key] = aspect
        val store = prefs ?: context.applicationContext
            .getSharedPreferences(PrefsName, Context.MODE_PRIVATE).also { prefs = it }
        store.edit().putFloat(key, aspect).apply()
    }
}

/** The sane cover-box range -- one wild first scan can't set a degenerate cell. */
private const val MeasuredCoverAspectMin = 0.5f
private const val MeasuredCoverAspectMax = 1.6f

/**
 * Whether [platformId]'s cover proportion is already settled: curated in [PlatformCoverAspects] or measured and stored. A platform
 * that is not is waiting for its first automatic cover to be measured ([MeasuredCoverAspects.record] is first-write-wins).
 */
internal fun platformCoverAspectIsSettled(platformId: String): Boolean {
    val key = platformId.lowercase()
    return PlatformCoverAspects.containsKey(key) || MeasuredCoverAspects.aspectFor(key) != null
}

