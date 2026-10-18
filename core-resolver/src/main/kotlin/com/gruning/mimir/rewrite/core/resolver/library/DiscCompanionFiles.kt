package com.gruning.mimir.rewrite.core.resolver.library

import java.io.File

/**
 * The files that travel with a disc but are not games: the `.sbi` libcrypt subchannel file and the `.sub` subchannel
 * image a ripper leaves beside a disc under the disc's own name.
 *
 * `ScanRecognizer` skips both on purpose (`SIDECAR_EXTENSIONS`: they cannot be launched), so no library row ever holds
 * one, and a move set built from library rows alone left them behind. A PAL libcrypt game then lost its protection data
 * in the move and DuckStation answered "no SBI file" (Cocoon D#248).
 *
 * ## What counts, and what never does
 *
 * Only a file in the disc's **own folder** whose name is the disc's name with the extension swapped for `sbi` or `sub`.
 * Those are found by exact-name probes -- each disc gets a handful of them, not a directory walk, so a folder of five
 * thousand ROMs costs the same as a folder of three. A `Game.sbi` beside `Game (Disc 1).chd` is another game's file and
 * is not taken; a directory that happens to be called `Game.sbi` is not a file and is not taken.
 *
 * **One exception, and it is a walk:** when a picked `.cue` brings its tracks ([companionSet]), the cue's folder is
 * listed once and every `.cue` and `.gdi` in it that is not part of the operation is read, to find the tracks a sheet
 * that stays behind still needs. That costs the size of the folder plus the sheets in it, and runs off Main.
 *
 * ## One resolver for every verb
 *
 * Public so the file manager asks the same question the disc-set warning does ([withCompanions], [companionsOf],
 * [renamedTo]): a move, a delete or a rename of a disc takes its own subchannel files with it, whether or not the
 * library thinks the disc belongs to a set. [siblingsOf] stays the one place a companion is found.
 */
object DiscCompanionFiles {

    // Both spellings are probed: a ripper's output keeps whatever case it was made with, and a Linux card tells the two
    // apart. A case-insensitive card answers both probes with the same file, which [siblingsOf] keeps once.
    private val SUFFIXES = listOf(".sbi", ".SBI", ".sub", ".SUB")

    /**
     * The companion files of every disc in [discPaths], in disc order, each listed once.
     *
     * @param alreadyInSet paths the set already carries (its playlist), which are never repeated here.
     */
    fun siblingsOf(discPaths: List<String>, alreadyInSet: Collection<String> = emptyList()): List<String> {
        val taken = HashSet<String>()
        discPaths.forEach { taken += it.lowercase() }
        alreadyInSet.forEach { taken += it.lowercase() }
        val found = ArrayList<String>()
        for (disc in discPaths) {
            val base = withoutExtension(disc) ?: continue
            for (suffix in SUFFIXES) {
                val candidate = base + suffix
                if (File(candidate).isFile && taken.add(candidate.lowercase())) found += candidate
            }
        }
        return found
    }

    // The files a libcrypt subchannel file can belong to. Narrower than "has an extension" on purpose: a delete takes
    // its companions with it, and deleting `Game.txt` must never reach for `Game.sbi`.
    private val DISC_IMAGE_EXTENSIONS = setOf("cue", "bin", "img", "iso", "chd", "pbp", "ecm", "mdf", "ccd")

    private val COMPANION_EXTENSIONS = setOf("sbi", "sub")

    private fun extensionOf(path: String): String = File(path).extension.lowercase()

    /** Whether [path] is a disc image on disk -- the only kind of file a companion hangs from. */
    private fun isDiscImageFile(path: String): Boolean =
        extensionOf(path) in DISC_IMAGE_EXTENSIONS && File(path).isFile

    /**
     * Whether a disc image with this disc's own name -- `Game.cue` beside `Game.chd` and `Game.bin` -- stays where it
     * is while [operation] happens. The companions belong to the NAME, not to one file of it, so while any other disc
     * image of that name is staying behind the `.sbi` is staying with it: taking it would strip the libcrypt data
     * from a disc nobody touched. Every disc image extension is probed in both cases, minus the files the operation
     * itself carries.
     */
    private fun otherDiscStaysBehind(disc: String, operation: Collection<String>): Boolean {
        val base = withoutExtension(disc) ?: return false
        val inOperation = operation.mapTo(HashSet()) { it.lowercase() }
        return DISC_IMAGE_EXTENSIONS.any { extension ->
            listOf(".$extension", ".${extension.uppercase()}").any { suffix ->
                val candidate = base + suffix
                candidate.lowercase() !in inOperation && File(candidate).isFile
            }
        }
    }

    /**
     * What travels with a file the player picks: the picked [paths], then the tracks of any `.cue` among them, then the
     * libcrypt companions -- plus who follows whom, for a move.
     *
     * @param followers for a move: each file that must land under a name decided by another file in [paths] -- an
     *   `.sbi` / `.sub` takes its disc's final name, a cue's track keeps the name the cue lists it under. A follower is
     *   left behind rather than renamed on its own, or when what it follows does not land.
     */
    class CompanionSet(val paths: List<String>, val followers: Map<String, CompanionFollower>)

    /** @param keepsOwnName true for a cue's track (its name is what the sheet lists); false for an `.sbi` / `.sub`. */
    class CompanionFollower(val leader: String, val keepsOwnName: Boolean)

    /**
     * [paths] and everything that goes with them. See [CompanionSet]. Reads the filesystem (the disc's name under every
     * disc extension, the cues' text, the folder's other cues), so call it off Main.
     *
     *  - **Tracks :** each `.cue` among [paths] brings the files it lists, in its own folder
     *    ([CueSheet]) -- except a track something that STAYS BEHIND still needs: another `.cue` or `.gdi` in the folder
     *    that lists it (an unreadable or oversized one is assumed to need everything), or a `.ccd` / `.mds` / `.toc` of
     *    the track's own name ([stillNeeded]). The rule that keeps a staying disc's `.sbi` keeps a staying sheet's track.
     *  - **Libcrypt files:** the `.sbi` / `.sub` of every disc image among the picked files and those tracks, minus the
     *    discs whose name another disc image keeps behind ([otherDiscStaysBehind]).
     *
     * Idempotent: anything already in [paths] -- a whole-set move already carries its companions -- is not repeated, and
     * only ever a same-name or listed file in a disc's own folder is added, so a delete built from this stays inside the
     * folders the player chose.
     */
    fun companionSet(paths: List<String>): CompanionSet {
        val picked = paths.mapTo(HashSet()) { it.lowercase() }
        val trackLeader = LinkedHashMap<String, String>() // lowercase track path -> the picked cue that lists it
        val trackPath = LinkedHashMap<String, String>() // lowercase track path -> the track as the sheet's folder spells it
        for (cue in paths.filter { extensionOf(it) == "cue" && File(it).isFile }) {
            for (track in tracksOf(cue)) {
                trackLeader.putIfAbsent(track.lowercase(), cue)
                trackPath.putIfAbsent(track.lowercase(), track)
            }
        }
        val stayingNeeds = HashMap<String, StayingNeeds>()
        val addedTracks = trackPath.filter { (key, track) ->
            key !in picked && !stillNeeded(track, picked, stayingNeeds)
        }.values.toList()
        val expanded = paths + addedTracks
        val discs = expanded.filter { isDiscImageFile(it) && !otherDiscStaysBehind(it, expanded) }
        val companions = if (discs.isEmpty()) emptyList() else siblingsOf(discs, alreadyInSet = expanded)
        val everything = expanded + companions
        val followers = LinkedHashMap<String, CompanionFollower>()
        leadersOf(everything).forEach { (companion, leader) -> followers[companion] = CompanionFollower(leader, keepsOwnName = false) }
        for (path in everything) {
            val cue = trackLeader[path.lowercase()] ?: continue
            if (path !in followers) followers[path] = CompanionFollower(cue, keepsOwnName = true)
        }
        return CompanionSet(everything, followers)
    }

    /** [paths] followed by what goes with them, without who follows whom: what a delete stages. See [companionSet]. */
    fun withCompanions(paths: List<String>): List<String> = companionSet(paths).paths

    /** The files [cue] lists that exist beside it -- see [CueSheet]. */
    fun tracksOf(cue: String): List<String> = CueSheet.listedFiles(File(cue)).map { it.path }

    // What the sheets in one folder that are NOT in the operation -- the ones that stay behind -- still need: the
    // lowercase bare file names they list, or [everything] when one of them cannot be read with confidence.
    private class StayingNeeds(val everything: Boolean, val names: Set<String>)

    // The manifests whose tracks share the manifest's own name (`Game.ccd` + `Game.img`, `Game.mds`, `Game.toc`). Probed
    // by name, and ONLY these: a staying `.chd` or `.iso` of that name is a disc of its own, not a sheet that lists
    // the track, and holding the track back for it would keep too much.
    private val TRACK_MANIFEST_EXTENSIONS = listOf("ccd", "mds", "toc")

    /**
     * Whether something that stays behind still needs [track]: a `.cue` or `.gdi` in its folder that lists it, or a
     * `.ccd` / `.mds` / `.toc` of the track's own name. None of them is in [inOperation], or it would be going too.
     * The folder is read once ([cache]).
     */
    private fun stillNeeded(track: String, inOperation: Set<String>, cache: MutableMap<String, StayingNeeds>): Boolean {
        val folder = File(track).parentFile ?: return false
        val needs = cache.getOrPut(folder.path) { stayingNeeds(folder, inOperation) }
        if (needs.everything || File(track).name.lowercase() in needs.names) return true
        val stem = withoutExtension(track) ?: return false
        return TRACK_MANIFEST_EXTENSIONS.any { extension ->
            listOf(".$extension", ".${extension.uppercase()}").any { suffix ->
                val manifest = stem + suffix
                manifest.lowercase() !in inOperation && File(manifest).isFile
            }
        }
    }

    // The PROTECTING reader (see [CueSheet]): fails open. A folder that cannot be listed, and a sheet that cannot be read
    // with confidence, both mean "assume it needs every file here".
    private fun stayingNeeds(folder: File, inOperation: Set<String>): StayingNeeds {
        val listing = folder.listFiles() ?: return StayingNeeds(everything = true, names = emptySet())
        var everything = false
        val names = HashSet<String>()
        for (file in listing) {
            if (!file.isFile || file.path.lowercase() in inOperation) continue
            val needed = when (file.extension.lowercase()) {
                "cue" -> CueSheet.protectedNames(file)
                "gdi" -> GdiSheet.protectedNames(file)
                else -> continue
            }
            if (needed == null) everything = true else names += needed
        }
        return StayingNeeds(everything, names)
    }

    /**
     * The companions that must be renamed along with [path], or empty when [path] is not a disc image or another disc
     * image of its name (`Game.chd` beside the `Game.cue` being renamed) keeps the `.sbi` where it is. **Only the sheet
     * is renamed**: the tracks a `.cue` lists keep their names and still resolve, so they are neither renamed nor
     * counted as "another disc" that keeps the `.sbi` behind.
     */
    fun companionsOf(path: String): List<String> {
        if (!isDiscImageFile(path)) return emptyList()
        val operation = listOf(path) + if (extensionOf(path) == "cue") tracksOf(path) else emptyList()
        return if (otherDiscStaysBehind(path, operation)) emptyList() else siblingsOf(listOf(path))
    }

    /**
     * Which disc each companion in [paths] follows, by NAME alone (no file is touched): the first disc image in
     * [paths] whose path minus extension matches, case-insensitively. A move uses it to land a companion under its
     * disc's final name, whatever that turned out to be.
     */
    fun leadersOf(paths: List<String>): Map<String, String> {
        val discByStem = LinkedHashMap<String, String>()
        paths.forEach { path ->
            if (extensionOf(path) in DISC_IMAGE_EXTENSIONS) withoutExtension(path)?.let { discByStem.putIfAbsent(it.lowercase(), path) }
        }
        val leaders = LinkedHashMap<String, String>()
        paths.forEach { path ->
            if (extensionOf(path) in COMPANION_EXTENSIONS) {
                withoutExtension(path)?.let { stem -> discByStem[stem.lowercase()]?.let { leaders[path] = it } }
            }
        }
        return leaders
    }

    /**
     * The file name [companionPath] takes when its disc has been renamed to [landedPath]: the disc's new name without
     * its extension, then the companion's own suffix with its case kept (`Game.SBI` stays `.SBI`).
     */
    fun renamedTo(companionPath: String, landedPath: String): String {
        val companionName = File(companionPath).name
        val suffix = companionName.substring(companionName.lastIndexOf('.'))
        val landedName = File(landedPath).name
        val dot = landedName.lastIndexOf('.')
        return (if (dot > 0) landedName.substring(0, dot) else landedName) + suffix
    }

    /** [path] up to, not including, the dot of its FINAL segment's extension, or null when that segment has none. */
    private fun withoutExtension(path: String): String? {
        val name = File(path).name
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return null
        return path.substring(0, path.length - (name.length - dot))
    }
}
