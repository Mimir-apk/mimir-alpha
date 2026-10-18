package com.gruning.mimir.rewrite.core.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import java.util.Locale

/**
 * Shipping physical-media art for ROM detail previews.
 *
 * The source product maps a platform id to one of a small set of local PNG shells: cart, card,
 * disc, or arcade cabinet. This keeps the focused gamelist detail visually rich when no local box
 * art is available, without pretending there is real cover art and without touching launch/storage
 * truth.
 */
@Composable
fun PlatformMediaArt(
    platformIdValue: String,
    modifier: Modifier = Modifier,
) {
    val drawableRes = remember(platformIdValue) { platformMediaArtDrawableRes(platformIdValue) }
    Image(
        painter = painterResource(drawableRes),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

@DrawableRes
private fun platformMediaArtDrawableRes(platformIdValue: String): Int =
    when (platformMediaArtKey(platformIdValue)) {
        "arcade-mame" -> R.drawable.mimir_media_art_arcade_mame
        "card-ds" -> R.drawable.mimir_media_art_card_ds
        "card-switch" -> R.drawable.mimir_media_art_card_switch
        "cart-gb" -> R.drawable.mimir_media_art_cart_gb
        "cart-gba" -> R.drawable.mimir_media_art_cart_gba
        "cart-md" -> R.drawable.mimir_media_art_cart_md
        "cart-n64" -> R.drawable.mimir_media_art_cart_n64
        "cart-sms" -> R.drawable.mimir_media_art_cart_sms
        "disc-cd" -> R.drawable.mimir_media_art_disc_cd
        "disc-dvd" -> R.drawable.mimir_media_art_disc_dvd
        else -> R.drawable.mimir_media_art_cart_nes
    }

/**
 * Internal (was file-private) so [mimirShelfmarkKind] can share this classifier instead of writing a third
 * per-platform alias table. A platform's shelfmark silhouette and its physical-media shell must agree.
 */
internal fun platformMediaArtKey(raw: String): String = platformMediaArtKeyOrNull(raw) ?: "cart-nes"

/** Nullable classifier for callers that must not present an unknown platform as an NES cartridge. */
internal fun platformMediaArtKeyOrNull(raw: String): String? {
    val platform = raw.trim().lowercase(Locale.US)
    val tokens = platform.split('_', '-', ' ').filter { it.isNotBlank() }
    fun token(vararg values: String): Boolean = values.any { it in tokens }
    fun contains(vararg values: String): Boolean = values.any { platform.contains(it) }

    when (platform) {
        "arcade", "neogeo", "mame", "cps", "fbneo" -> return "arcade-mame"
        "ds", "3ds", "nds" -> return "card-ds"
        "switch", "nsw" -> return "card-switch"
        "gb", "gbc" -> return "cart-gb"
        "gba" -> return "cart-gba"
        "genesis", "megadrive", "md", "32x" -> return "cart-md"
        "n64" -> return "cart-n64"
        "sms", "mastersystem", "gamegear", "gg" -> return "cart-sms"
        "ps1", "psx", "saturn", "sat", "dreamcast", "dc", "segacd", "psp", "pc" -> return "disc-cd"
        "ps2", "ps3", "xbox", "xbx", "xbox360", "gamecube", "gc", "wii" -> return "disc-dvd"
    }

    return when {
        token("arcade", "mame", "fbneo", "hbmame", "naomi", "atomiswave", "chihiro", "triforce") ||
            contains("cp_system", "capcom_cp") -> "arcade-mame"
        contains("neo_geo") -> when {
            contains("pocket") -> "cart-sms"
            token("cd", "cdz") || contains("_cd") -> "disc-cd"
            contains("mini", "_x") -> "disc-dvd"
            else -> "arcade-mame"
        }
        token("ds", "3ds", "dsi") || contains("nintendo_ds", "nintendo_3ds") -> "card-ds"
        token("switch") || contains("n_gage", "_vita", "playstation_vita") -> "card-switch"
        contains("pc_engine", "turbografx", "supergrafx") && !contains("_cd") -> "card-ds"
        token("ps2", "ps3", "ps4", "ps5", "gamecube", "wii") ||
            contains(
                "xbox",
                "x360",
                "playstation_2",
                "playstation_3",
                "playstation_4",
                "playstation_5",
                "game_cube",
                "gamecube",
                "nintendo_wii",
                "wii_u",
            ) -> "disc-dvd"
        token("cd", "cdz", "umd", "psp", "ps1", "saturn", "cdi", "cd32", "cdtv", "pcfx", "laseractive", "playdia", "pippin", "zeebo", "nuon", "copera", "3do") ||
            contains(
                "_cd",
                "mega_cd",
                "sega_cd",
                "jaguar_cd",
                "cd_i",
                "pc_fx",
                "3do",
                "dreamcast",
                "playstation_portable",
                "playstation_scph",
                "playstation_pocketstation",
                "ique",
            ) -> "disc-cd"
        contains("playstation") -> "disc-cd"
        contains("game_boy_advance") || token("gba") -> "cart-gba"
        contains("nintendo_64") || token("n64") -> "cart-n64"
        contains("game_boy") || token("gb", "gbc") -> "cart-gb"
        contains("mega_drive", "megadrive", "genesis", "32x") || token("genesis", "md") -> "cart-md"
        contains("master_system", "mark_iii", "game_gear", "sg_1000", "sg1000") || token("sms", "gg") -> "cart-sms"
        contains("nintendo_entertainment_system", "super_nintendo", "famicom", "sufami", "satellaview") ||
            token("nes", "snes") -> "cart-nes"
        else -> null
    }
}
