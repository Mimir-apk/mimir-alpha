package com.gruning.mimir.rewrite.feature.library

import androidx.compose.runtime.Composable
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.library.GameMemberRow
import com.gruning.mimir.rewrite.core.ui.select.MimirSelectItem
import com.gruning.mimir.rewrite.core.ui.select.MimirSelectOverlay

/**
 * The disc / version menu —
 *
 * It **replaces** the inline focusable disc rows that shipped earlier the same day. Those were rows on
 * the detail card itself; this is a modal on Play, in the product's own themed select shell — the same
 * [MimirSelectOverlay] the Open-With picker and the theme picker use, so it inherits the runic chrome,
 * the focus scope discipline and the controller grammar rather than re-deriving any of it.
 *
 * **The first entry is always the group default**, and that is load-bearing rather than tidy. Play on a
 * multi-disc group hands the emulator the `.m3u` when one exists — the whole set, with the emulator's own
 * disc-swap list. A menu of bare disc entries with no default would make that handoff unreachable and
 * quietly downgrade Chrono Cross from "swap between 3 discs" to "boot one disc". The default row keeps
 * the old behaviour one press away, exactly where it was.
 */
val LaunchChoiceScope = FocusScope("library/launchChoice")

/** The group-default row: whatever plain Play has always done (playlist / lowest disc / the only file). */
val LaunchChoiceDefaultKey = FocusKey("library/launchChoice/default")

/** A specific physical disc. Crosses the seam as a NUMBER — `LaunchController.launch(preferredDisc =)`. */
fun launchChoiceDiscKey(discNumber: Int) = FocusKey("library/launchChoice/disc/$discNumber")

/**
 * A specific variant member. Crosses the seam as an **[EntryKey]**, not a number: `preferredDisc` cannot
 * express "the PAL copy", and `LaunchController.launch` already takes `launchMemberEntryKey` for exactly
 * this. Discs and versions are different questions and travel on different parameters.
 */
fun launchChoiceVersionKey(entryKey: String) = FocusKey("library/launchChoice/version/$entryKey")

/** What a picked row means. Resolved from a [FocusKey] by [launchChoiceFor]. */
sealed interface LaunchChoice {
    /** Launch the way plain Play always has. */
    data object GroupDefault : LaunchChoice

    /** Start this physical disc. */
    data class Disc(val number: Int) : LaunchChoice

    /** Launch this exact member file. */
    data class Version(val entryKey: EntryKey) : LaunchChoice
}

private const val DISC_PREFIX = "library/launchChoice/disc/"
private const val VERSION_PREFIX = "library/launchChoice/version/"

/** Map a focused row back to the choice it represents, or null when [key] is not a picker row. */
fun launchChoiceFor(key: FocusKey?): LaunchChoice? {
    val raw = key?.value ?: return null
    return when {
        key == LaunchChoiceDefaultKey -> LaunchChoice.GroupDefault
        raw.startsWith(DISC_PREFIX) ->
            raw.removePrefix(DISC_PREFIX).toIntOrNull()?.takeIf { it > 0 }?.let(LaunchChoice::Disc)
        raw.startsWith(VERSION_PREFIX) ->
            raw.removePrefix(VERSION_PREFIX).takeIf { it.isNotBlank() }
                ?.let { LaunchChoice.Version(EntryKey(it)) }
        else -> null
    }
}

/**
 * The variant members of a group: members that are NOT discs, NOT the track files of a disc image, and NOT the only file.
 *
 * **A track set is one version, not several (Thor drive 2026-10-08, Sonic Adventure 2).** The grouper folds a `.cue`/`.gdi`/`.ccd` manifest and its
 * `.bin`/`.img` tracks into one group with the manifest as the launch primary, and the member read returns all of them. A game whose names carry no disc
 * token (`(Track 1).bin` is not one) has `discNumber == 0` on every member, so without this the picker offered "Track 1.bin", "Track 2.bin",
 * "Track 3.bin" and the `.cue` as four versions. A track file is dropped only when it belongs to a manifest in the group: its name, less a trailing
 * `(Track N)` and its extension, equals the manifest's name with every bracketed part kept (`Game (USA) (Track 1).bin` goes with `Game (USA).cue`). The
 * grouper strips brackets and buckets by folder, so a standalone `Game (Europe).bin` can share the group of `Game (USA).cue`; its name matches no
 * manifest, so it stays a version. The manifest alone is the one version, which is below the two a picker needs, so Play goes straight to the game. With NO
 * manifest a bare `.bin` is a dump of its own and stays a version.
 *
 * The extensions mirror the grouper's ([LibraryDetailTrackExtensions] for tracks, the manifest set below), because [GameMemberRow] carries no
 * `contentKind` to read; this only decides what is LISTED, the launch still crosses the seam as an [EntryKey].
 *
 * **Honest scope, stated rather than implied.** Region/version siblings only collapse into one group through a HARD identity key (`ExactHash` or
 * `CatalogTitleId`), `contentHash` is null until a later hashing stage, and no catalog key is resolved, so `(USA)` and `(Europe)` files of a
 * single-file game land in separate groups with separate `gameId`s. That arm is pinned with unit fixtures and is DEVICE-UNVERIFIABLE until catalog
 * identity lands.
 */
internal fun launchChoiceVersions(members: List<GameMemberRow>): List<GameMemberRow> {
    val nonDisc = members.filter { it.discNumber == 0 }
    val manifestNames = nonDisc.filter { it.fileExtension() in LaunchChoiceManifestExtensions }.map { it.nameWithoutExtension() }.toSet()
    val versions = nonDisc.filterNot { it.fileExtension() in LibraryDetailTrackExtensions && it.trackSetName() in manifestNames }
    return if (versions.size > 1) versions else emptyList()
}

/** A trailing `(Track 1)` / `(Track 01)` token: how a split disc names its tracks beside the manifest. */
private val LaunchChoiceTrailingTrackToken = Regex("""\s*\(track\s*\d+\)\s*$""", RegexOption.IGNORE_CASE)

/** The file's full name less its extension, brackets kept, lower-cased: the identity a track and its manifest share. */
private fun GameMemberRow.nameWithoutExtension(): String = displayName.substringBeforeLast('.', displayName).trim().lowercase()

/** A track file's name with its `(Track N)` token taken off, so it can be compared with its manifest's [nameWithoutExtension]. */
private fun GameMemberRow.trackSetName(): String = nameWithoutExtension().replace(LaunchChoiceTrailingTrackToken, "").trim()

/** The track-set manifests the scan indexes as `TRACK_MANIFEST` (`ScanRecognizer.TRACK_MANIFESTS`): the member a launch goes through. */
private val LaunchChoiceManifestExtensions = setOf("cue", "gdi", "ccd")

private fun GameMemberRow.fileExtension(): String = displayName.substringAfterLast('.', "").lowercase()

/**
 * The rows, in operation order: the default first, then discs, then versions.
 *
 * Returns EMPTY when there is nothing to choose between — one disc or fewer and no variants. The caller
 * uses that emptiness as the trigger test, so a single-file game never sees a modal on Play and the
 * common press is unchanged.
 */
internal fun launchChoiceItems(
    discs: List<LibraryDetailDisc>,
    versions: List<GameMemberRow>,
): List<MimirSelectItem> {
    if (discs.size < 2 && versions.isEmpty()) return emptyList()
    return buildList {
        add(
            MimirSelectItem.Option(
                key = LaunchChoiceDefaultKey,
                label = "Play",
                current = true,
                recommended = true,
                subtitle = if (discs.size > 1) "Starts the whole set" else null,
            ),
        )
        if (discs.size > 1) {
            add(MimirSelectItem.Group("Discs"))
            discs.forEach { disc ->
                add(MimirSelectItem.Option(key = launchChoiceDiscKey(disc.number), label = "Disc ${disc.number}"))
            }
        }
        if (versions.isNotEmpty()) {
            add(MimirSelectItem.Group("Versions"))
            versions.forEach { member ->
                add(
                    MimirSelectItem.Option(
                        key = launchChoiceVersionKey(member.entryKey.value),
                        label = member.displayName,
                    ),
                )
            }
        }
    }
}

/**
 * True when [members] offer something to choose between, i.e. Play must ask rather than guess.
 *
 * The PUBLIC test, so Home and Library cannot disagree about whether a menu exists — and it is the same
 * emptiness the overlay itself renders from, not a parallel rule that could drift from it.
 */
fun launchChoiceAvailable(members: List<GameMemberRow>): Boolean =
    launchChoiceItems(libraryDetailDiscs(members), launchChoiceVersions(members)).isNotEmpty()

/**
 * The picker itself — a thin caller over the shared themed shell, exactly as [OpenWithOverlay] is.
 * Input is routed through each host's dispatch, which navigates [LaunchChoiceScope] by these same keys.
 *
 * Takes the group's MEMBERS rather than pre-split discs and versions: splitting them is this file's job,
 * and making a caller do it would have meant exporting three internals to `app` just so Home could
 * repeat the Library's arithmetic.
 */
@Composable
fun LaunchChoiceOverlay(
    authority: FocusAuthority,
    title: String,
    members: List<GameMemberRow>,
    onChoose: (LaunchChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    MimirSelectOverlay(
        authority = authority,
        scope = LaunchChoiceScope,
        title = title,
        items = launchChoiceItems(libraryDetailDiscs(members), launchChoiceVersions(members)),
        onSelect = { key -> launchChoiceFor(key)?.let(onChoose) },
        onDismiss = onDismiss,
        presenceManagedExternally = true,
    )
}
