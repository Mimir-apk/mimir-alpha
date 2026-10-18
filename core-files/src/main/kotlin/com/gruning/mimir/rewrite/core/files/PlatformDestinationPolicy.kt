package com.gruning.mimir.rewrite.core.files

/**
 * Lists platform folders across drives, including empty folders, for explicit destination selection.
 *
 * ## What this replaces, and why the single guess had to go
 *
 * [ExtractDestinationPolicy] answers **one** smart destination or none, fail-closed, on the rule that
 * *"offering a WRONG platform folder is worse than offering none"*. That rule is right for a guess and
 * wrong for a list: when the player picks the row themselves, nothing is being guessed, so declining to
 * show a folder buys no safety and costs the whole feature.
 *
 * It cost it in three measured ways (probe against the live registry, 2026-08-16):
 *
 *  1. **A zipped PS2 game got nothing.** `Roms/ps2/Final Fantasy X.zip` resolves to `null`, because the
 *     PS2 content contract lists `bin, chd, ciso, cso, dump, elf, gz, img, iso, isz, m3u, mdf, nrg, zso`
 *     and **not `zip`**. The folder says PS2, the extension says one of 59 other platforms, they
 *     conflict, and fail-closed answers nothing. Same for PS3, Xbox, 3DS and Switch.
 *  2. **A freshly downloaded archive got nothing**, on every platform. `.zip` alone is ambiguous across
 *     59 platforms, so recognition needs the *folder* to name the platform — which means the smart
 *     destination appeared mainly when the archive was already inside the right folder, i.e. exactly
 *     where the player did not need it.
 *  3. **Two source folders for one platform got nothing**, by `singleOrNull`: correct when guessing,
 *     absurd when the player could simply have been shown both.
 *
 * ## What this does NOT change
 *
 * **Recognition still decides what is PRESELECTED, and it is still fail-closed.** [recognised] marks at
 * most one platform — the one the classifier named — and nothing here invents that answer or relaxes it.
 * The policy of 2026-08-16 on the folder-versus-extension veto stands untouched: a `.gbc` under
 * `gb/` is still unrecognised, and still draws no glyph. The list simply does not need recognition to be
 * useful, which is the whole point.
 *
 * ## Deliberately not consulted
 *
 * **Nothing is read from inside the archive.** [ExtractDestinationPolicy]'s own note applies unchanged: a
 * ZIP is an archive, not a game, and reading its entries to guess at the platform would be slow and a
 * different claim than the one the tile makes.
 *
 * **This object decides; it does not look anything up.** The library's real folders live in Room and the
 * folders that exist on disk need a filesystem walk — neither is reachable from this module, and
 * duplicating either would drift. `app/` supplies both as plain values.
 */
object PlatformDestinationPolicy {

    /**
     * Every platform folder [subjectPath] could be placed into, best first.
     *
     * @param subjectPath the archive being extracted, or the file being moved/copied. Used for two
     *   things only: its own parent is excluded (see below), and its volume decides
     *   [PlatformDestination.onSubjectVolume].
     * @param recognisedPlatformId what the classifier made of the subject, or null. Marks at most one
     *   platform [PlatformDestination.recognised]; never adds a destination by itself.
     * @param libraryFolders `platformId -> the folders the library actually sources that platform from`.
     *   Authoritative: the library's own rows cannot be wrong about where its games are.
     * @param discovered platform-named folders found on the drives, including empty ones —
     *   the only way a platform folder made this morning can be offered at all.
     * @param volumeRootOf which volume a path is on. **Cross-volume destinations are kept, deliberately**
     *   — downloading to internal storage and extracting to the SD card is the single most common shape
     *   of this whole task, and [ExtractDestinationPolicy]'s same-volume refusal existed to stop a *guess*
     *   crossing drives, not to stop the player choosing to. The row is marked instead of dropped.
     */
    fun destinations(
        subjectPath: String,
        recognisedPlatformId: String?,
        libraryFolders: Map<String, List<String>>,
        discovered: List<PlatformFolderCandidate> = emptyList(),
        volumeRootOf: (String) -> String? = { null },
        /**
         * Where the recognised platform's folder would live if it existed.
         *
         * A `.steam` shortcut can be badged `PG`, labelled "Recognised" and annotated "Runs on PG"
         * while no `pcgames` folder exists. Existing library and filesystem folders alone would omit
         * the very platform already identified, so this supplies its prospective destination.
         *
         * Only the recognised platform's own folder is offered, rather than all missing platforms:
         * a destination list of 226 rows would overwhelm the small screen.
         *
         * Null when nothing was recognised or the caller has no root under which to create it.
         * The policy remains pure: it decides that a row should exist without filesystem access;
         * creating the folder belongs to the commit.
         */
        proposedForRecognised: PlatformFolderCandidate? = null,
    ): List<PlatformDestination> {
        val subjectParent = parentOf(subjectPath)
        val subjectVolume = volumeRootOf(subjectPath)
        val fromLibrary = libraryFolders.asSequence()
            .flatMap { (platformId, folders) -> folders.map { PlatformFolderCandidate(platformId, it) } }
            .map { it to true }
        val rest = discovered.asSequence().map { it to false }

        return (fromLibrary + rest)
            .mapNotNull { (candidate, library) ->
                val path = normalise(candidate.path) ?: return@mapNotNull null
                if (candidate.platformId.isBlank()) return@mapNotNull null
                // A file's own folder is not somewhere to put it. Excluded rather than drawn dead,
                // because a row that names a real folder and finishes as a no-op is worse than absent:
                // Move would report success having moved nothing.
                if (path == subjectParent) return@mapNotNull null
                PlatformDestination(
                    platformId = candidate.platformId,
                    path = path,
                    fromLibrary = library,
                    recognised = recognisedPlatformId != null && candidate.platformId == recognisedPlatformId,
                    onSubjectVolume = subjectVolume == null || volumeRootOf(path) == subjectVolume,
                )
            }
            // Same folder reached from both sources is ONE destination, and the library wins the tie:
            // `fromLibrary` is what the ordering below trusts, and a duplicate row would also give the
            // focus ring two identities for one place.
            .groupBy { it.path }
            .map { (_, sameFolder) -> sameFolder.firstOrNull { it.fromLibrary } ?: sameFolder.first() }
            .sortedWith(
                // Best first, and every tier is a fact rather than a taste: the platform the classifier
                // named, then folders the library provably uses, then everything else that exists. Ties
                // break on the folder's own name so the order is stable across runs -- an unstable list
                // under a focus ring means the row that was under the player's thumb moves.
                compareByDescending<PlatformDestination> { it.recognised }
                    .thenByDescending { it.fromLibrary }
                    .thenByDescending { it.onSubjectVolume }
                    .thenBy { folderNameOf(it.path).lowercase() }
                    .thenBy { it.path },
            )
            // Appended AFTER the sort and then re-hoisted, rather than folded into the sequence
            // above, because it is the one row whose folder does not exist: putting it through the same
            // `mapNotNull` would mean the existence-shaped checks above (the subject's own parent,
            // duplicate folding) silently deciding a case they were never written for.
            //
            // Skipped entirely when the recognised platform ALREADY has a real folder -- the whole point
            // is to fill a gap, and offering a second, non-existent row beside a real one would require choosing to
            // choose between a folder and the idea of it.
            .let { existing ->
                val proposal = proposedForRecognised
                    ?.takeIf { recognisedPlatformId != null && it.platformId == recognisedPlatformId }
                    ?.takeIf { candidate -> existing.none { it.platformId == candidate.platformId } }
                    ?.let { candidate -> normalise(candidate.path)?.let { candidate.platformId to it } }
                    ?.takeIf { (_, path) -> path != subjectParent }
                if (proposal == null) {
                    existing
                } else {
                    val (platformId, path) = proposal
                    listOf(
                        PlatformDestination(
                            platformId = platformId,
                            path = path,
                            fromLibrary = false,
                            recognised = true,
                            onSubjectVolume = subjectVolume == null || volumeRootOf(path) == subjectVolume,
                            exists = false,
                        ),
                    ) + existing
                }
            }
    }

    /** The folder's own name, which is what a row shows — never the raw platform id. */
    fun folderNameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

    private fun parentOf(path: String): String? = normalise(path)?.substringBeforeLast('/', "")?.ifBlank { null }

    private fun normalise(path: String): String? =
        path.trim().trimEnd('/').takeIf { it.isNotBlank() }
}

/**
 * A platform-named folder that exists on a drive — included whether empty or populated.
 *
 * Carries no "is it empty" flag on purpose: whether the folder has anything in it changes nothing about
 * whether it is a valid destination, and asking would cost a second directory read per candidate.
 */
data class PlatformFolderCandidate(
    val platformId: String,
    val path: String,
)

/**
 * One row of the destination list, for Extract and for Move/Copy alike.
 *
 * @param recognised this is the platform the classifier named for the subject — the row a surface should
 *   land focus on. At most one destination in a list can be recognised, and often none is.
 * @param fromLibrary the library sources games from this folder. Distinct from [recognised]: it is about
 *   the FOLDER's standing, not the subject's identity, and it is what lets a surface separate "your PS2
 *   folder" from "a folder called ps2 that exists".
 * @param onSubjectVolume false when this destination is on a different drive than the subject — kept, and
 *   marked so a surface can say so, because the move will be a copy across storage rather than a rename.
 */
data class PlatformDestination(
    val platformId: String,
    val path: String,
    val fromLibrary: Boolean,
    val recognised: Boolean,
    val onSubjectVolume: Boolean,
    /**
     * Whether this folder is already on disk. **False only for the proposed row**, which names the
     * recognised platform's folder before it exists so a recognised game is never left with nowhere to go.
     *
     * The row that carries it must say so, and the commit that takes it must create the folder. Both are
     * the caller's job: a policy that answered "this folder exists" by looking would be a policy that does
     * IO, and every other row here is decided from data the caller already had.
     */
    val exists: Boolean = true,
)
