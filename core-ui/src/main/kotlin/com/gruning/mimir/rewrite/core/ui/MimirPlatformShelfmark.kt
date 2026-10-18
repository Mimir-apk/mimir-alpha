package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * Mimir Alpha :core-ui — the PLATFORM SHELFMARK.
 *
 * WHAT THIS IS. A library does not identify a book by reprinting the publisher's logo on the spine — it
 * applies its OWN shelfmark: a small struck plate carrying a call number. That is exactly this component, and
 * exactly the semantic we want. Today a cover only carries a console identifier when the SCAN happens to be
 * branded box art, so the mark is accidental: inconsistent in position, size, and style, and absent entirely
 * on flat art or a clear logo. The shelfmark is Mimir cataloguing the item in its own hand, so the mark is
 * always present, always the same size, always in the same corner.
 *
 * WHAT THIS IS NOT. It is NOT a vendor logo and contains no vendor-authored geometry. It draws no
 * PlayStation mark, no Nintendo wordmark, no Sega logotype, no Xbox sphere. It is also NOT a second copy of the
 * per-platform icon pipeline: [PlatformConsoleArt] draws the MACHINE at hero scale on a PLATFORM tile; the
 * shelfmark captions a GAME COVER at ~18dp.
 *
 * WHERE IT IS MOUNTED : **the Home shelf's game tiles, and nowhere else.**
 * `HomeShelfScreen.HomeShelfInteractiveTile` passes it into [MimirLedgeCoverArt] / [MimirSwitchTileArt] as
 * their `shelfmark` slot. Home is the product's one MIXED-platform lane — Editor Choice / Last Played
 * interleaves machines, and a custom collection is authored across them — so the plate is doing real
 * identification work there. It is deliberately absent from the gamelist, the cover picker, and the detail
 * card (all one-platform-by-construction, and their covers already carry the platform's own nomenclature),
 * so the shipped placement is Home only.
 *
 * `CompanionSurface` omits platform names on PLATFORM tiles, where art supplies the identity.
 * This is scoped to a PLATFORM tile: an object whose whole subject IS the platform, wearing full
 * console art, on the companion's 3×3 grid. Putting a code on it would be labelling a label, and this
 * component still must never appear there. A Home shelf tile is a different object — it is a GAME, and the
 * question the plate answers ("which machine is this copy for?") is one the art cannot answer and the tile
 * asks constantly, because its neighbours are other machines. The two requirements govern two
 * different surfaces. The Home tile also carries no platform NAME — `NS` is a call
 * number, not "Nintendo Switch".
 *
 * ONE CONSTRUCTION, EVERY PLATFORM. There are 225 platforms in the registry. The shelfmark does not draw 225
 * bespoke marks; it draws ONE plate whose four corners are cut differently per MEDIA KIND
 * ([MimirShelfmarkKind]) and whose ink is a short code ([mimirPlatformShelfmarkCode]). Silhouette is the
 * channel that survives small size, so the kind reads even when the code is too small to parse.
 *
 * COLOR LAW. There is no accent gold and no green here. Green is success only, and gold is
 * accent/focus/action — a platform identifier is neither, it is an identity marker. The plate is the bound
 * volume's own leather ([MimirHeroMaterial.coverTop]/[MimirHeroMaterial.coverBottom]) rimmed in its
 * metalwork ([MimirHeroMaterial.hardware]), the same material authority [MimirAchievementMedal] is struck
 * from. All 9 shipping identities are therefore covered with no per-theme asset.
 */
@Composable
fun MimirPlatformShelfmark(
    platformIdValue: String,
    modifier: Modifier = Modifier,
    /** The platform's human label, used only when [platformIdValue] is not in the curated code table. */
    displayLabel: String = "",
    tier: MimirShelfmarkTier = MimirShelfmarkTier.FOCUSED,
    palette: MimirThemePalette = LocalMimirPalette.current,
) {
    val code = remember(platformIdValue, displayLabel) {
        mimirPlatformShelfmarkCode(platformIdValue, displayLabel)
    }
    val kind = remember(platformIdValue) { mimirShelfmarkKind(platformIdValue) }
    // Honest fallback (MediaArtFrame's discipline): an unnameable platform gets NO plate rather than a
    // meaningless one. A blank or "?" shelfmark would be worse than the absence it is standing in for.
    if (code != null) {
        ShelfmarkPlate(
            code = code,
            kind = kind,
            emphasis = mimirShelfmarkEmphasis(tier),
            palette = palette,
            modifier = modifier,
        )
    }
}

/**
 * How loudly the shelfmark is struck. The focused tile is the full-strength reference and
 * carries full material; resting recedes rather than disappearing, and the picker — where the whole point of
 * the surface is telling candidate scans apart — is the loudest.
 */
enum class MimirShelfmarkTier {
    /** The cover-art picker's candidate cells: always on, largest, full material. */
    PICKER,

    /** The focused game cover. */
    FOCUSED,

    /** A resting neighbour, if a surface ever wants it. Recessed: no sheen, dimmed rim and ink. */
    RESTING,
}

/**
 * The physical medium a platform's games shipped on. This is the shelfmark's silhouette channel.
 *
 * Media is chosen deliberately over manufacturer or generation for three reasons: it is a FACT about the
 * object rather than a brand (no trademark surface at all), it collapses 225 platforms onto a handful of
 * shapes without anything feeling arbitrary, and it is a distinction the user can actually see at 18dp.
 */
enum class MimirShelfmarkKind {
    /** A slot cartridge: NES, SNES, N64, Mega Drive, Game Boy. Plate keyed at the top-leading corner. */
    CARTRIDGE,

    /** A flash card: DS, 3DS, Switch, Vita. Plate keyed at the bottom-leading corner. */
    CARD,

    /** Optical media: PS1/PS2, Saturn, Dreamcast, GameCube, Xbox, UMD. Plate is a stadium. */
    DISC,

    /** A coin-op upright. Plate tapers at the top like a marquee. */
    CABINET,

    /** Home-computer media — tape, floppy, disk image. Plate is a near-square shell. */
    MAGNETIC,

    /** Unclassified. A neutral rounded plate; never a broken or empty mark. */
    UNKNOWN,
}

/** Per-tier draw parameters. Pure, so the emphasis ladder is pinned by a JVM test. */
data class MimirShelfmarkEmphasis(
    val height: Dp,
    val fontSize: TextUnit,
    val plateAlpha: Float,
    val rimAlpha: Float,
    val inkAlpha: Float,
    val sheen: Boolean,
)

/** The tallest plate the tier ladder strikes (the picker's), in dp: what a book seating a plate keeps its embossed Mannaz clear of ([MimirShelfmarkBandDp]). */
internal const val MimirShelfmarkTallestPlateDp = 20f

/** The tier ladder. Resting stays visible (0.72) — it recedes, it does not vanish. */
fun mimirShelfmarkEmphasis(tier: MimirShelfmarkTier): MimirShelfmarkEmphasis = when (tier) {
    MimirShelfmarkTier.PICKER -> MimirShelfmarkEmphasis(
        height = MimirShelfmarkTallestPlateDp.dp, fontSize = 11.sp,
        plateAlpha = 0.94f, rimAlpha = 0.95f, inkAlpha = 1f, sheen = true,
    )
    MimirShelfmarkTier.FOCUSED -> MimirShelfmarkEmphasis(
        height = 18.dp, fontSize = 10.sp,
        plateAlpha = 0.90f, rimAlpha = 0.85f, inkAlpha = 0.96f, sheen = true,
    )
    MimirShelfmarkTier.RESTING -> MimirShelfmarkEmphasis(
        height = 16.dp, fontSize = 9.sp,
        plateAlpha = 0.72f, rimAlpha = 0.45f, inkAlpha = 0.70f, sheen = false,
    )
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────────
// THE CODE (label derivation)
// ─────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The short code struck into the plate, or null when the platform cannot be named honestly.
 *
 * CONSOLIDATION, NOT A FOURTH VOCABULARY. Short-code derivation already existed three times in this
 * codebase and the three DISAGREED (`companionCode("PlayStation")` = "PLA" while the registry says "PS1").
 * This function is the canonical home: [CuratedShelfmarkCodes] covers every platform the registry emits, and
 * the fallback is the shipped `companionCode` algorithm carried over unchanged. `app`'s two copies should
 * delegate here and be deleted — that migration is a follow-up in a module this lane does not own.
 *
 * The table now uses abbreviated codes such as NS, GB and GBC instead of spelled-out product
 * name in it. See [CuratedShelfmarkCodes] for the rules and the rewritten entries.
 *
 * The one deliberate difference from `companionCode`: that function returns "M" for an unnameable label
 * because a companion tile needs a non-empty string. A cover does not — see the null contract above.
 */
fun mimirPlatformShelfmarkCode(platformIdValue: String, displayLabel: String = ""): String? {
    // Identity folding is shared with the console-art pipeline so the two can never disagree about what
    // "nintendo_ds" or "playstation_2" is.
    val id = normalizePlatformArtId(platformIdValue)
    CuratedShelfmarkCodes[id]?.let { return it }
    val source = displayLabel.ifBlank { platformIdValue }
    return shelfmarkInitials(source).takeIf { it.isNotBlank() }
}

/**
 * THE CODE TABLE — every platform the registry emits, plus the shipping aliases that fold onto them.
 *
 *  The three
 * examples ARE the contract, and they encode one rule: **a code is an ABBREVIATION, never the product name
 * spelled out.** "Switch" is the trademark; `NS` is a call number. This replaces earlier
 * spelled-out entries ("Switch", "Saturn", "Vita", "Arcade", "Neo Geo", "Sega CD", "Wii U", "Jaguar", …),
 * which are all rewritten below; the terse entries from that table (`GCN`, `MD`, `DC`, `PS1`, `SNES`) were
 * already compliant and are preserved verbatim.
 *
 * THE RULES, so a future addition is not a coin flip:
 *  1. UPPERCASE alphanumeric, no spaces, hyphens, or periods — "CD-i" becomes `CDI`, "Sega CD" becomes `SCD`.
 *     Uppercasing is deliberate: it strips the vendor's own typographic styling (the lowercase-ii in "Wii",
 *     the Game Boy logotype), leaving a plain call number in Mimir's type.
 *  2. Prefer the ESTABLISHED community/retail abbreviation where one exists: `NES`, `N64`, `PS1`, `GBA`,
 *     `SMS`, `PCE`, `MD`, `DC`, `SAT`.
 *  3. Otherwise take the initials of the MACHINE, never the manufacturer — "Super Cassette Vision" is `SCV`,
 *     not `EPO`. A shelfmark names the machine a game runs on; the maker is not the user's question.
 *  4. Where the machine's proper name is ALREADY ≤4 characters and has no shorter established form, the code
 *     is that name uppercased (`WII`, `MSX`, `C64`, `3DO`, `NUON`). This is the one case where code == name,
 *     and rule 1's uppercasing is what keeps it a code rather than a wordmark.
 *  5. Codes are UNIQUE — two platforms sharing a code would be two platforms the plate cannot tell apart.
 *     Pinned by test.
 *  6. Never a logotype, wordmark, monogram, or vendor lockup. The plate is type on leather, nothing else.
 *
 * Not capped at 3-4 characters: the plate sizes to its ink, so `PCECD` and `NGPC` spell out as far as they
 * need to. The test caps them at 5, which is what still reads at 16dp.
 *
 * Keys are POST-[normalizePlatformArtId], so both the registry's canonical id (`snk_neo_geo`) and the
 * shipping short id (`neogeo`) are listed where the normalizer does not already fold one onto the other.
 */
private val CuratedShelfmarkCodes: Map<String, String> = mapOf(
    // ── Nintendo ────────────────────────────────────────────────────────────────────────────────────
    "nes" to "NES",
    "snes" to "SNES",
    "n64" to "N64",
    "gamecube" to "GCN",
    "nintendowii" to "WII", "wii" to "WII",
    "nintendowiiu" to "WIIU", "wiiu" to "WIIU",
    // Example: the product name is "Switch", the CODE is NS.
    "switch" to "NS",
    "gb" to "GB", "gbc" to "GBC", "gba" to "GBA",
    "ds" to "DS", "3ds" to "3DS",
    "nintendonintendodsi" to "DSI",
    "nintendovirtualboy" to "VB", "virtualboy" to "VB",
    "nintendofamilycomputerdisksystem" to "FDS", "fds" to "FDS",
    "nintendonintendo64dd" to "64DD",
    "nintendosatellaview" to "BSX",
    "nintendosufamiturbo" to "SFT",
    "nintendopokemonmini" to "PM",
    "iqueique" to "IQUE",
    "nintendocolortvgamectg6" to "CTG6",
    "nintendocolortvgamectg15v" to "CTG15",
    "nintendocolortvgamectgbk6" to "CTGB6",
    "nintendocolortvgamectgcr112" to "CTGCR",
    "nintendocolortvgamectghc10" to "CTGHC",

    // ── Sony ────────────────────────────────────────────────────────────────────────────────────────
    "ps1" to "PS1", "ps2" to "PS2", "ps3" to "PS3",
    "sonyplaystation4" to "PS4", "ps4" to "PS4",
    "sonyplaystation5" to "PS5",
    "psp" to "PSP",
    "sonyplaystationvita" to "PSV", "psvita" to "PSV",

    // ── Microsoft ───────────────────────────────────────────────────────────────────────────────────
    "xbox" to "XB", "xbox360" to "X360", "xboxone" to "XB1",

//  Uncurated, `pc_games` fell
    // through to the initials fallback, which reads "pc" + "games" and answers PG -- a call number for a
    // platform nobody calls that. Curated here rather than by teaching the fallback about multi-word ids:
    // the fallback is right for the long tail, and this table exists precisely because some platforms have
    // a name people actually use. It also now agrees with the registry record's own `shortName: "PC"`.
    // **Keyed on the NORMALISED id, which is the whole lesson here.** An earlier entry was keyed as `pc_games`: `normalizePlatformArtId` strips underscores before the lookup
    // (PlatformConsoleArt.kt), so the entry could never match and the badge went on reading PG. It
    // compiled, every test passed, and only the device showed it -- the table is a map, and a key that
    // matches nothing is indistinguishable from an absent one to everything except a screen.
    "pcgames" to "PC",
    "microsoftxboxseriesx" to "XSX",
    "microsoftmsx" to "MSX", "msx" to "MSX",
    "microsoftmsx2" to "MSX2", "msx2" to "MSX2",

    // ── Sega ────────────────────────────────────────────────────────────────────────────────────────
    "segasg1000" to "SG1K",
    "segamastersystemmarkiii" to "SMS", "mastersystem" to "SMS",
    "genesis" to "MD", "gmd" to "MD",
    "sega32x" to "32X",
    "segamegacdsegacd" to "SCD", "segacd" to "SCD",
    "saturn" to "SAT",
    "dreamcast" to "DC",
    "segagamegear" to "GG", "gamegear" to "GG",
    "segapico" to "PICO",
    "segabeena" to "BEENA",
    "seganaomi" to "NAOMI", "seganaomi2" to "NAOM2",
    "segachihiro" to "CHI",
    "segavideodriver" to "SVD",
    "atomiswave" to "AW",

    // ── NEC ─────────────────────────────────────────────────────────────────────────────────────────
    "necpcengineturbografx16" to "PCE", "pcengine" to "PCE",
    "necpcenginecdturbografxcd" to "PCECD", "pcenginecd" to "PCECD",
    "necpcenginesupergrafx" to "SGFX",
    "necpc98" to "PC98", "pc98" to "PC98",
    "necpcfx" to "PCFX",

    // ── SNK ─────────────────────────────────────────────────────────────────────────────────────────
    "snkneogeo" to "NG", "neogeo" to "NG",
    "snkneogeocd" to "NGCD",
    "snkneogeopocket" to "NGP", "neogeopocket" to "NGP",
    "snkneogeopocketcolor" to "NGPC",

    // ── Atari ───────────────────────────────────────────────────────────────────────────────────────
    "atari2600" to "2600", "atari5200" to "5200", "atari7800" to "7800",
    "atari8bitfamily" to "A8", "atari8bit" to "A8",
    "atarist" to "ST",
    "atarilynx" to "LYNX",
    "atarijaguar" to "JAG", "atarijaguarcd" to "JAGCD",
    "atarixegs" to "XEGS",
    "atarivcs" to "VCS",
    "ataripong" to "PONG",

    // ── Commodore / Sinclair / Amstrad / Acorn / Apple ──────────────────────────────────────────────
    "c64" to "C64",
    "commodore128" to "C128",
    "commodorevic20" to "VIC20", "vic20" to "VIC20",
    "commodoreplus4" to "PLUS4",
    "commodorepet" to "PET",
    "commodoreamiga" to "AMI", "amiga" to "AMI",
    "commodorecd32" to "CD32", "amigacd32" to "CD32",
    "commodorecdtv" to "CDTV",
    "sinclairzxspectrum" to "ZX", "zxspectrum" to "ZX",
    "sinclairzx81" to "ZX81", "zx81" to "ZX81",
    "amstradcpc" to "CPC",
    "amstradgx4000" to "GX4K",
    "acornbbcmicro" to "BBC", "bbcmicro" to "BBC",
    "acornarchimedes" to "ARCH", "archimedes" to "ARCH",
    "acornatom" to "ATOM",
    "acornriscpc" to "RPC",
    "acornelectron" to "ELEC",
    "applei" to "A1",
    "appleii" to "A2", "appleiie" to "A2E", "appleiigs" to "A2GS",
    "applemacintosh" to "MAC", "macintosh" to "MAC",
    "applebandaipippin" to "PIP",

    // ── Japanese home computers ─────────────────────────────────────────────────────────────────────
    "sharpx1" to "X1",
    "sharpx68000" to "X68K", "x68000" to "X68K",
    "fujitsufm7" to "FM7", "fm7" to "FM7",
    "fujitsufmtowns" to "FMT", "fmtowns" to "FMT",
    "thomsonmoto" to "MOTO", "thomson" to "MOTO",
    "hitachis1" to "S1",
    "spectravideosv" to "SV", "spectravideo" to "SV",
    "tomytutor" to "TUTOR",
    "actapricotpcxi" to "APXI",
    "luxorabc800" to "ABC8",
    "pc" to "DOS",
    "trs80" to "TRS80", "ti994a" to "TI99", "dragon32" to "DR32",

    // ── Philips / Magnavox / Coleco / Mattel / Fairchild / early consoles ───────────────────────────
    "cdi" to "CDI",
    "philipsvideopac" to "VPAC",
    "philipstelegame" to "TELE",
    "magnavoxodyssey" to "ODY",
    "magnavoxodyssey2" to "ODY2", "odyssey2" to "ODY2",
    "colecocolecovision" to "CV", "colecovision" to "CV",
    "colecocolecovisionadam" to "ADAM", "colecoadam" to "ADAM",
    "colecotelstar6030" to "T6030", "colecotelstar6040" to "T6040",
    "colecotelstar6045" to "T6045", "colecotelstar6065" to "T6065",
    "colecotelstar6071" to "T6071", "colecotelstar6135" to "T6135",
    "mattelintellivision" to "INTV", "intellivision" to "INTV",
    "intellivisionamico" to "AMICO",
    "mattelhyperscan" to "HSCAN",
    "fairchildchannelf" to "CHF",
    "gcevectrex" to "VEC", "vectrex" to "VEC",
    "ballyastrocade" to "ASTRO",
    "rcastudioii" to "STU2",
    "miltonbradleymicrovision" to "MVIS",
    "emersonarcadia2001" to "A2001",
    "intertonvc4000" to "VC4K",
    "intertonvideo2000" to "V2000",
    "unisonicchampion2711" to "C2711",
    "1292advancedprogrammablevideosystem" to "1292",
    "the3docompany3do" to "3DO", "3do" to "3DO",
    "pioneerlaseractive" to "LACT",
    "vmlabsnuon" to "NUON",
    "tandyvideoinformationsystem" to "VIS",
    "umtechvideobrain" to "VBRN",
    "apfmp1000" to "MP1K",
    "apfimaginationmachine" to "APFI",
    "palmtexsupermicro" to "SMIC",
    "handheldelectronicgame" to "LCD",

    // ── Bandai / Epoch / Entex / Casio / Tiger / Watara and other JP makers ─────────────────────────
    "bandaiwonderswan" to "WS", "wonderswan" to "WS",
    "bandaiwonderswancolor" to "WSC",
    "bandaiplaydia" to "PLD",
    "bandaigundamrx78" to "RX78",
    "bandaisupervision8000" to "SV8K",
    "bandaiterebikko" to "TBK",
    "bandaidesignmasterdenshimangajuku" to "DMDM",
    "epochcassettevision" to "ECV",
    "epochsupercassettevision" to "SCV",
    "epochgamepocketcomputer" to "GPC",
    "epochtvtenniselectrotennis" to "TVT",
    "entexadventurevision" to "ADVN",
    "entexselectagame" to "SAG",
    "casioloopy" to "LOOPY",
    "casiopv1000" to "PV1K",
    "tigergamecom" to "GCOM",
    "tigergizmondo" to "GIZ",
    "tigerrzone" to "RZONE",
    "watarasupervision" to "WSV",
    "welbackmegaduck" to "MDK",
    "bitcorporationgamate" to "GMT",
    "hartunggamemaster" to "HGM",
    "funtechsuperacan" to "ACAN",
    "nichibutsumyvision" to "MYV",
    "gakkencompactvisiontvboy" to "TVB",
    "konamipicno" to "PICNO",
    "takaravideochallenger" to "VCH",
    "ssdxavixport" to "XAVI",
    "fukutakepublishingstudybox" to "SBOX",
    "benessepocketchallengev2" to "PCV2",
    "benessepocketchallengew" to "PCW",
    "yamahacopera" to "COP",
    "nokiangage" to "NGAGE",
    "tapwavezodiac" to "ZOD",
    "panicplaydate" to "PDT",

    // ── VTech / LeapFrog / LJN / educational ────────────────────────────────────────────────────────
    "vtechcreativision" to "CRV",
    "vtechsocrates" to "SOC",
    "vtechvflash" to "VFL",
    "vtechvsmile" to "VSM",
    "vtech3dgamate" to "3DGM",
    "leapfrogexplorer" to "LFEX",
    "leapfrogleappad" to "LPAD",
    "leapfrogmyfirstleappad" to "MFLP",
    "leapfrogleapsterlearninggamesystem" to "LSTR",
    "ljnvideoart" to "VART",
    "connorvideosmarts" to "VSMT",
    "viewmasterinteractivevision" to "IVIS",
    "worldsofwonderactionmax" to "AMAX",
    "bbcbridgecompanion" to "BRC",

    // ── Arcade boards + the arcade catch-all ────────────────────────────────────────────────────────
    "arcade" to "ARC",
    "capcomcpsystemi" to "CPS1", "capcomcpsystemii" to "CPS2", "capcomcpsystemiii" to "CPS3",

    // ── Modern / set-top / handheld PCs ─────────────────────────────────────────────────────────────
    "valvesteamdeck" to "DECK",
    "valvesteamlink" to "SLNK",
    "nvidiashieldportable" to "SHP",
    "nvidiashieldtv" to "SHTV",
    "googlestadia" to "STA",
    "googlenexusplayer" to "NXP",
    "amazonfiretv" to "FTV",
    "amazonluna" to "LUNA",
    "ouyaouya" to "OUYA",
    "zeebozeebo" to "ZEEBO",
    "playjamgamestick" to "GSTK",
    "zapitgamewave" to "GWV",
    "gameparkgp32" to "GP32",
    "gameparkgp2x" to "GP2X",
    "gameparkcaanoo" to "CNO",
    "digitalmediacartridgefirecore" to "FIRE",

    // ── Fantasy consoles + engines (their own marks, not a hardware vendor's) ───────────────────────
    "tic80" to "TIC80",
    "wasm4" to "WASM4",
    "uzebox" to "UZE",
    "vircon32" to "VIR32",
    "lowresnx" to "LRNX",
    "microw8" to "MW8",
    "chip8" to "CHIP8",
    "lutro" to "LUTRO",
    "arduboyincarduboy" to "ARDU",
    "rpgmaker" to "RPGM",
    "scummvm" to "SVM",
    "infocomzmachine" to "ZM",

    // ── PC-50x cartridge clones (three distinct registry entries, so three distinct codes) ──────────
    "pc50xpalladiumtelecassettengame825530" to "PTG30",
    "pc50xpalladiumtelecassettengame825581" to "PTG81",
    "pc50xshgblackpointfs1003" to "BPFS",
)

private val ShelfmarkTokenSplit = Regex("[\\s_\\-/]+")

/**
 * The algorithmic fallback for the long tail, carried over from the shipped `companionCode` so the two
 * cannot drift: multi-word labels give their initials (max 3 words), a single word gives its first 3
 * characters, and the whole thing caps at 4. Pure.
 */
internal fun shelfmarkInitials(label: String): String {
    val tokens = label
        .split(ShelfmarkTokenSplit)
        .map { it.filter(Char::isLetterOrDigit) }
        .filter { it.isNotBlank() }
    val code = if (tokens.size > 1) {
        tokens.take(3).mapNotNull { it.firstOrNull()?.toString() }.joinToString("")
    } else {
        label.filter(Char::isLetterOrDigit).take(3)
    }
    return code.uppercase(Locale.US).take(4)
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────────
// THE KIND (silhouette mapping)
// ─────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Home-computer ids, checked before the media classifier. [platformMediaArtKey] has no computer bucket and
 * falls all the way through to a NES cartridge for DOS/Amiga/Spectrum, which would be a lie on the plate.
 */
private val MagneticMediaIds: Set<String> = setOf(
    "pc", "dos", "msdos", "ibmpc", "amiga", "amigacd32", "c64", "commodore64", "vic20", "commodoreplus4",
    "zxspectrum", "zx81", "spectrum", "msx", "msx2", "amstradcpc", "appleii", "atarist", "atari8bit",
    "bbcmicro", "archimedes", "macintosh", "mac", "x68000", "pc98", "fmtowns", "fm7", "sharpx1", "thomson",
    "spectravideo", "colecoadam", "acornelectron", "dragon32", "ti994a", "trs80",
)

/**
 * Fold a platform id to its plate silhouette.
 *
 * Reuses [platformMediaArtKey] — the cart/card/disc/cabinet classifier that already backs the ROM-detail
 * physical-media shells — rather than writing a third alias table. That classifier has been exercised across
 * the whole shipping alias space, and sharing it means a platform's shelfmark can never contradict its
 * media art.
 */
fun mimirShelfmarkKind(platformIdValue: String): MimirShelfmarkKind {
    if (platformIdValue.isBlank()) return MimirShelfmarkKind.UNKNOWN
    if (normalizePlatformArtId(platformIdValue) in MagneticMediaIds) return MimirShelfmarkKind.MAGNETIC
    return when (platformMediaArtKey(platformIdValue).substringBefore('-')) {
        "cart" -> MimirShelfmarkKind.CARTRIDGE
        "card" -> MimirShelfmarkKind.CARD
        "disc" -> MimirShelfmarkKind.DISC
        "arcade" -> MimirShelfmarkKind.CABINET
        else -> MimirShelfmarkKind.UNKNOWN
    }
}

/**
 * The four corner treatments that make one plate read as five different objects. Extents are FRACTIONS OF
 * PLATE HEIGHT so the mark is scale-invariant; a chamfered corner is a straight cut, a non-chamfered one is
 * a quarter-round. Pure, so the geometry contract is pinned by a JVM test without touching a Canvas.
 */
internal data class MimirShelfmarkProfile(
    val topLeft: Float,
    val topLeftChamfer: Boolean,
    val topRight: Float,
    val topRightChamfer: Boolean,
    val bottomRight: Float,
    val bottomRightChamfer: Boolean,
    val bottomLeft: Float,
    val bottomLeftChamfer: Boolean,
)

internal fun mimirShelfmarkProfile(kind: MimirShelfmarkKind): MimirShelfmarkProfile = when (kind) {
    // The keyed corner of a slot cartridge — the notch that stops you inserting it backwards.
    MimirShelfmarkKind.CARTRIDGE -> MimirShelfmarkProfile(
        topLeft = 0.36f, topLeftChamfer = true,
        topRight = 0.18f, topRightChamfer = false,
        bottomRight = 0.18f, bottomRightChamfer = false,
        bottomLeft = 0.18f, bottomLeftChamfer = false,
    )
    // A game card's key sits on the opposite corner from a cartridge's, which is what separates the two
    // silhouettes at a glance.
    MimirShelfmarkKind.CARD -> MimirShelfmarkProfile(
        topLeft = 0.14f, topLeftChamfer = false,
        topRight = 0.14f, topRightChamfer = false,
        bottomRight = 0.14f, bottomRightChamfer = false,
        bottomLeft = 0.32f, bottomLeftChamfer = true,
    )
    // Fully round ends: the only stadium in the set, so optical media is unmistakable even blurred.
    MimirShelfmarkKind.DISC -> MimirShelfmarkProfile(
        topLeft = 0.5f, topLeftChamfer = false,
        topRight = 0.5f, topRightChamfer = false,
        bottomRight = 0.5f, bottomRightChamfer = false,
        bottomLeft = 0.5f, bottomLeftChamfer = false,
    )
    // Both top corners cut: the plate tapers into a marquee.
    MimirShelfmarkKind.CABINET -> MimirShelfmarkProfile(
        topLeft = 0.30f, topLeftChamfer = true,
        topRight = 0.30f, topRightChamfer = true,
        bottomRight = 0.10f, bottomRightChamfer = false,
        bottomLeft = 0.10f, bottomLeftChamfer = false,
    )
    // A tape or floppy shell is a hard-cornered box.
    MimirShelfmarkKind.MAGNETIC -> MimirShelfmarkProfile(
        topLeft = 0.08f, topLeftChamfer = false,
        topRight = 0.08f, topRightChamfer = false,
        bottomRight = 0.08f, bottomRightChamfer = false,
        bottomLeft = 0.08f, bottomLeftChamfer = false,
    )
    // Neutral: reads as a plate, claims no medium.
    MimirShelfmarkKind.UNKNOWN -> MimirShelfmarkProfile(
        topLeft = 0.22f, topLeftChamfer = false,
        topRight = 0.22f, topRightChamfer = false,
        bottomRight = 0.22f, bottomRightChamfer = false,
        bottomLeft = 0.22f, bottomLeftChamfer = false,
    )
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────────
// THE DRAW
// ─────────────────────────────────────────────────────────────────────────────────────────────────────

private val ShelfmarkRimWidth: Dp = 1.dp
private val ShelfmarkInkPadding: Dp = 6.dp

/**
 * The struck plate.
 *
 * COST: no bitmap decode, no resource lookup, no new dependency, and
 * — the part that matters on a coverflow we have fought for frames on — NO PER-FRAME ALLOCATION. The [Path]
 * and both brushes are built inside [drawWithCache], which re-runs only when the plate's size or the theme
 * changes, never per frame. Steady-state work is 3 draw ops (fill, rim, sheen) plus one text node whose
 * layout Compose measures once and reuses. Contrast with reusing [PlatformConsoleArt], which inflates a
 * VectorDrawable through `painterResource` — correct once on a hero tile, wrong per-card on a moving row.
 */
@Composable
private fun ShelfmarkPlate(
    code: String,
    kind: MimirShelfmarkKind,
    emphasis: MimirShelfmarkEmphasis,
    palette: MimirThemePalette,
    modifier: Modifier,
) {
    val hero = palette.materials.hero
    val sheenColor = palette.materials.glassSheen
    val profile = remember(kind) { mimirShelfmarkProfile(kind) }
    // A struck stamp, not running text: the plate is a fixed 18 dp high and its code is set at the size its tier names, in dp, whatever the system font scale. In sp it grew with the font
    // (a plate 46 dp wide at 1x was 75 dp at 2x and could not be held to its height), and the book's embossed Mannaz was covered (combined33 sweep, the Home shelf at font 1.3).
    val density = LocalDensity.current
    val inkStyle = remember(emphasis.fontSize, hero.onCover, emphasis.inkAlpha, density) {
        with(density) {
            TextStyle(
                color = hero.onCover.copy(alpha = emphasis.inkAlpha),
                fontSize = emphasis.fontSize.value.dp.toSp(),
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.7f.dp.toSp(),
            )
        }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(emphasis.height)
            // A one-character code must still read as a plate, not a dot.
            .defaultMinSize(minWidth = emphasis.height * 1.4f)
            .drawWithCache {
                val plate = shelfmarkPlatePath(size.width, size.height, profile)
                // The bound volume's leather, lit top to bottom like every other Mimir surface.
                val fill = Brush.verticalGradient(
                    listOf(hero.coverTop, hero.coverBottom),
                    startY = 0f,
                    endY = size.height,
                )
                // The binding's metalwork. NOTE this is materials.hero.hardware, NOT palette.accent — on the
                // Default identity the two happen to share a value, but the semantics differ and the color
                // law is explicit: gold is accent/focus/action, and this is neither.
                val rim = lerp(hero.hardware, hero.coverBottom, 0.10f)
                val rimWidth = ShelfmarkRimWidth.toPx()
                val sheenY = rimWidth * 1.6f
                onDrawBehind {
                    drawPath(plate, brush = fill, alpha = emphasis.plateAlpha)
                    drawPath(
                        path = plate,
                        color = rim,
                        alpha = emphasis.rimAlpha,
                        style = Stroke(width = rimWidth),
                    )
                    if (emphasis.sheen) {
                        drawLine(
                            color = sheenColor,
                            start = Offset(size.width * 0.24f, sheenY),
                            end = Offset(size.width * 0.76f, sheenY),
                            strokeWidth = rimWidth,
                            alpha = 0.65f,
                        )
                    }
                }
            }
            .padding(horizontal = ShelfmarkInkPadding)
            // The platform is announced once by the surface (the gamelist header, the picker's own label),
            // not forty times down a row where every card is the same platform.
            .clearAndSetSemantics { },
    ) {
        BasicText(text = code, style = inkStyle, maxLines = 1)
    }
}

/**
 * Build the plate outline clockwise from the top-left corner. One traversal, four parameterised corners —
 * this single function is what lets ~20 shipping platforms (and 225 registry ones) share a mark without
 * becoming 225 bespoke drawings.
 */
private fun shelfmarkPlatePath(w: Float, h: Float, profile: MimirShelfmarkProfile): Path {
    // Clamp so a short code (a narrow plate) can never produce inverted or overlapping corner geometry.
    val limit = minOf(w, h) / 2f
    val tl = (profile.topLeft * h).coerceIn(0f, limit)
    val tr = (profile.topRight * h).coerceIn(0f, limit)
    val br = (profile.bottomRight * h).coerceIn(0f, limit)
    val bl = (profile.bottomLeft * h).coerceIn(0f, limit)

    val path = Path()
    path.moveTo(tl, 0f)

    // top edge → top-right corner
    path.lineTo(w - tr, 0f)
    if (profile.topRightChamfer) path.lineTo(w, tr)
    else path.arcTo(Rect(w - 2f * tr, 0f, w, 2f * tr), 270f, 90f, false)

    // right edge → bottom-right corner
    path.lineTo(w, h - br)
    if (profile.bottomRightChamfer) path.lineTo(w - br, h)
    else path.arcTo(Rect(w - 2f * br, h - 2f * br, w, h), 0f, 90f, false)

    // bottom edge → bottom-left corner
    path.lineTo(bl, h)
    if (profile.bottomLeftChamfer) path.lineTo(0f, h - bl)
    else path.arcTo(Rect(0f, h - 2f * bl, 2f * bl, h), 90f, 90f, false)

    // left edge → top-left corner
    path.lineTo(0f, tl)
    if (profile.topLeftChamfer) path.lineTo(tl, 0f)
    else path.arcTo(Rect(0f, 0f, 2f * tl, 2f * tl), 180f, 90f, false)

    path.close()
    return path
}
