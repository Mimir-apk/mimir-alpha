package com.gruning.mimir.rewrite.core.resolver.library

import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.platform.IdentityNamespace
import com.gruning.mimir.rewrite.core.platform.PlatformRegistry
import com.gruning.mimir.rewrite.core.resolver.ExtensionPlatformClassifier

/**
 * Friendly display labels for the built-in platform ids (local-only policy). Used when a
 * scan first meets a platform and no catalog display name is on the row yet. A later catalog slice can
 * override these with the canonical catalog name; this is only the sensible fallback so the shelf never
 * shows a raw id like "megadrive". A platform this table has no row for takes the platform registry's
 * name ([registryName]).
 */
object PlatformLabels {

    fun labelFor(platformId: PlatformId): String =
        LABELS[platformId.value] ?: registryName(platformId) ?: prettify(platformId.value)

    /**
     * The id the LIBRARY (and so the shelf's lattice, with its names and its line-art) knows [platformId] by: the registry's `REWRITE_LEGACY` alias for it (`genesis` is
     * `megadrive` here), or [platformId] itself when it already is one or the registry has none. A page that speaks the registry's canonical ids (RetroAchievements' console map
     * does) asks this before [labelFor], so a platform is called by the same name on every page. An id this table already names is answered without the registry.
     */
    fun libraryIdFor(platformId: PlatformId): PlatformId {
        if (platformId.value in LABELS) return platformId
        return runCatching {
            val loaded = ExtensionPlatformClassifier.lockedRegistry
            val canonical = loaded.canonicalize(IdentityNamespace.REWRITE_LEGACY, platformId.value) ?: platformId
            loaded.definition(canonical)
                ?.aliases
                ?.firstOrNull { it.namespace == IdentityNamespace.REWRITE_LEGACY }
                ?.normalizedValue
                ?.let(::PlatformId)
        }.getOrNull() ?: platformId
    }

    /**
     * The platform registry's name for [platformId], or null when the registry has none.
     *
     * User-facing platform names are "PlayStation 3" and "Xbox 360". This table has no `ps3`,
     * `xbox360` or `xbox` row, so those went to [prettify] and the
     * library called them "Ps3" and "Xbox360", while the registry the scanner recognised them by has a
     * name for every platform it knows. The table still answers first: its names are the product's own
     * choices, and some differ from the registry's ("NES", "PSP" and "Arcade" against "Nintendo
     * Entertainment System", "PlayStation Portable" and "Arcade / MAME").
     *
     * A library id is the resolver's short id, which is not always the registry's own (`pcengine` is
     * `nec_pc_engine_turbografx_16` there), so it is looked up in the legacy namespace first. The registry
     * is the one the scanner loaded to recognise these files, so this is no second parse. One that cannot
     * be read costs a platform its name, never the library: the answer is null and the id is title-cased.
     */
    internal fun registryName(
        platformId: PlatformId,
        registry: () -> PlatformRegistry = { ExtensionPlatformClassifier.lockedRegistry },
    ): String? = runCatching {
        val loaded = registry()
        val id = loaded.canonicalize(IdentityNamespace.REWRITE_LEGACY, platformId.value) ?: platformId
        loaded.definition(id)?.display?.name?.takeIf(String::isNotBlank)
    }.getOrNull()

    /** Title-case an unknown id token as a last resort ("some_system" → "Some System"). */
    private fun prettify(id: String): String =
        id.split('_', '-', ' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    private val LABELS: Map<String, String> = buildMap {
        put("gb", "Game Boy")
        put("gbc", "Game Boy Color")
        put("gba", "Game Boy Advance")
        put("nes", "NES")
        put("snes", "Super Nintendo")
        put("n64", "Nintendo 64")
        put("ds", "Nintendo DS")
        put("3ds", "Nintendo 3DS")
        put("gamecube", "GameCube")
        put("wii", "Wii")
        put("switch", "Switch")
        put("ps1", "PlayStation")
        put("ps2", "PlayStation 2")
        put("psp", "PSP")
        put("dreamcast", "Dreamcast")
        put("saturn", "Saturn")
        put("megadrive", "Genesis / Mega Drive")
        put("mastersystem", "Master System")
        put("gamegear", "Game Gear")
        put("sega32x", "Sega 32X")
        put("segacd", "Sega CD")
        put("sg1000", "SG-1000")
        put("pcengine", "PC Engine / TurboGrafx-16")
        put("pcenginecd", "PC Engine CD")
        put("atari2600", "Atari 2600")
        put("atari7800", "Atari 7800")
        put("atarilynx", "Atari Lynx")
        put("atarijaguar", "Atari Jaguar")
        put("wonderswan", "WonderSwan")
        put("wonderswancolor", "WonderSwan Color")
        put("ngp", "Neo Geo Pocket")
        put("ngpc", "Neo Geo Pocket Color")
        put("virtualboy", "Virtual Boy")
        put("pokemini", "Pokémon mini")
        put("arcade", "Arcade")
    }
}
