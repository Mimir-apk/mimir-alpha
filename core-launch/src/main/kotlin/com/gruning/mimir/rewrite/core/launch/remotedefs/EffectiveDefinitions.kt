package com.gruning.mimir.rewrite.core.launch.remotedefs

import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.PlatformId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One bundled emulator row, reduced to the facts an overlay is checked against. */
data class BundledSeed(
    val packageName: String,
    val capability: EmulatorCapability,
    val platforms: Set<PlatformId>,
    /** Per platform, in registry order: the recommended core first (the Auto default), then the additional cores. */
    val retroArchCores: Map<PlatformId, List<String>> = emptyMap(),
    /**
     * Per platform: file extensions (lowercase, no dot) this emulator lists. A platform with no entry here, and none
     * from an overlay, has no list: every file of it is accepted, which is every emulator today. Overlay rows only add.
     */
    val listedExtensions: Map<PlatformId, Set<String>> = emptyMap(),
    /** Per platform: restricted-dialect file-name rules ([RecognitionRegex]) this emulator lists. Same union rule as [listedExtensions]. */
    val listedRegexes: Map<PlatformId, Set<String>> = emptyMap(),
)

data class PackagePlatform(val packageName: String, val platform: PlatformId)

/**
 * What the APK ships, as plain data, so the merge is a pure function that tests can feed any bundled state.
 * [RegistryBundledDefinitions.build] fills it from the real [com.gruning.mimir.rewrite.core.launch.EmulatorSeedRegistry].
 */
data class BundledDefinitions(
    /** The newest overlay ordinal this build's data already contains: an overlay at or below it is retired. */
    val baselineOrdinal: Long,
    val seeds: List<BundledSeed>,
    val activityAlternates: Map<PackagePlatform, List<String>> = emptyMap(),
    /** Packages Mimir may force-stop and dispatch to. Overlay rows never add to this. */
    val dispatchPackages: List<String> = seeds.filter { it.capability != EmulatorCapability.RECOGNIZE_ONLY }.map { it.packageName },
) {
    private val byPackage: Map<String, BundledSeed> = seeds.associateBy { it.packageName }

    fun seed(packageName: String): BundledSeed? = byPackage[packageName]
}

/** A merged value and where it came from, so diagnostics can say "from overlay". */
data class Sourced(val value: String, val fromOverlay: Boolean)

/**
 * The merged, immutable view: bundled data first in bundled order, overlay additions appended after in a fixed
 * order. The same bundled data and the same overlay CONTENT always give the same view, whatever order the rows
 * came in. [stateKey] identifies that view; the ordinal is deliberately not part of it.
 */
class EffectiveDefinitions internal constructor(
    val baselineOrdinal: Long,
    /** The overlay ordinal in use, or null when only bundled data applies. */
    val overlayOrdinal: Long?,
    /** True when a stored overlay exists but the bundled baseline is at least as new, so it is not used. */
    val overlayRetired: Boolean,
    val dispatchPackages: List<String>,
    private val alternates: Map<PackagePlatform, List<Sourced>>,
    private val cores: Map<PackagePlatform, List<Sourced>>,
    private val extensions: Map<PackagePlatform, List<String>>,
    private val regexes: Map<PackagePlatform, List<String>>,
) {
    // Scan and launch read these on every candidate list, so the plain-string views are built once per snapshot.
    private val alternateNames: Map<PackagePlatform, List<String>> by lazy {
        alternates.mapValues { (_, items) -> items.map { it.value } }
    }
    private val coreNames: Map<PackagePlatform, List<String>> by lazy {
        cores.mapValues { (_, items) -> items.map { it.value } }
    }

    fun alternateItems(packageName: String, platform: PlatformId): List<Sourced> =
        alternates[PackagePlatform(packageName, platform)].orEmpty()

    fun activityAlternatesFor(packageName: String, platform: PlatformId): List<String> =
        alternateNames[PackagePlatform(packageName, platform)].orEmpty()

    fun coreItems(packageName: String, platform: PlatformId): List<Sourced> =
        cores[PackagePlatform(packageName, platform)].orEmpty()

    /** Bundled order first (the first entry is the Auto default), then overlay additions. */
    fun retroArchCoresFor(packageName: String, platform: PlatformId): List<String> =
        coreNames[PackagePlatform(packageName, platform)].orEmpty()

    fun hasCore(packageName: String, platform: PlatformId, core: String): Boolean =
        retroArchCoresFor(packageName, platform).contains(core)

    fun extensionsFor(packageName: String, platform: PlatformId): List<String> =
        extensions[PackagePlatform(packageName, platform)].orEmpty()

    fun regexesFor(packageName: String, platform: PlatformId): List<String> =
        regexes[PackagePlatform(packageName, platform)].orEmpty()

    // A rule that cannot be compiled stays out of this list and so matches nothing; compile itself never throws, and
    // the runCatching keeps a lazy initializer (which would rethrow on every later read) from ever being the place one lands.
    private val compiledRegexes: Map<PackagePlatform, List<RecognitionRegex.Compiled>> by lazy {
        regexes.mapValues { (_, patterns) ->
            patterns.mapNotNull { runCatching { RecognitionRegex.compile(it) }.getOrNull() }
        }
    }

    /** Does a recognition rule (bundled or overlay) accept [fileName] for this package and platform? False when the emulator has no rules. */
    fun recognizes(packageName: String, platform: PlatformId, fileName: String): Boolean {
        if (fileName.length > RecognitionRegex.MAX_NAME_CHARS) return false
        val key = PackagePlatform(packageName, platform)
        val lower = fileName.lowercase()
        val extension = lower.substringAfterLast('.', missingDelimiterValue = "")
        if (extension.isNotEmpty() && extension in extensions[key].orEmpty()) return true
        return compiledRegexes[key].orEmpty().any { it.matches(lower) }
    }

    /** True when [packageName] has any extension or filename rule for [platform]: its acceptance is then a list, not "everything". */
    fun hasAcceptanceList(packageName: String, platform: PlatformId): Boolean {
        val key = PackagePlatform(packageName, platform)
        return !extensions[key].isNullOrEmpty() || !regexes[key].isNullOrEmpty()
    }

    /**
     * Does [packageName]'s list for [platform] include [fileName]?
     *  - null: it has no list, so it is not asking for anything (today's behaviour, and the answer for every emulator
     *    until a bundled or overlay row narrows it);
     *  - true or false: it has a list, and the file is or is not on it.
     * The first row narrows acceptance to a list and later rows only add to it. A rule that does not compile still counts
     * as a row (the list exists) and matches nothing. This only ever LABELS: nothing here picks, hides or launches.
     */
    fun listsFile(packageName: String, platform: PlatformId, fileName: String): Boolean? =
        if (hasAcceptanceList(packageName, platform)) recognizes(packageName, platform, fileName) else null

    /**
     * [listsFile] for a game that has several files Play could start (the regions or versions of one group): null when the
     * emulator has no list, or there is no name to ask about; true when its list includes ANY of [fileNames]; false only
     * when it includes none of them, so a label never says "doesn't list" about a file the player could still pick.
     */
    fun listsAnyFile(packageName: String, platform: PlatformId, fileNames: List<String>): Boolean? =
        if (fileNames.isEmpty() || !hasAcceptanceList(packageName, platform)) null else fileNames.any { recognizes(packageName, platform, it) }

    /** Canonical text of everything that can change behaviour; equal text means equal behaviour. */
    fun render(): String = buildString {
        dispatchPackages.forEach { append("dispatch|").append(it).append('\n') }
        fun keyText(k: PackagePlatform) = k.packageName + "|" + k.platform.value
        val ordered = compareBy<PackagePlatform>({ it.packageName }, { it.platform.value })
        alternates.keys.sortedWith(ordered).forEach { k ->
            alternates.getValue(k).forEach { append("alt|").append(keyText(k)).append('|').append(it.value).append(if (it.fromOverlay) "|O" else "|B").append('\n') }
        }
        // Core order is meaningful (the first is Auto), so it is kept; only the key order is sorted.
        cores.keys.sortedWith(ordered).forEach { k ->
            cores.getValue(k).forEach { append("core|").append(keyText(k)).append('|').append(it.value).append(if (it.fromOverlay) "|O" else "|B").append('\n') }
        }
        extensions.keys.sortedWith(ordered).forEach { k ->
            append("ext|").append(keyText(k)).append('|').append(extensions.getValue(k).joinToString(",")).append('\n')
        }
        regexes.keys.sortedWith(ordered).forEach { k ->
            regexes.getValue(k).forEach { append("re|").append(keyText(k)).append('|').append(it).append('\n') }
        }
    }

    val stateKey: String by lazy { sha256Hex(render().toByteArray(Charsets.US_ASCII)) }

    /**
     * The emulators (by package) whose launch details differ between [previous] and this view. Drives the Settings
     * result line ("Updated: 3 emulators"): an overlay that repeats what is already known changes none.
     */
    fun packagesChangedFrom(previous: EffectiveDefinitions): Set<String> {
        val before = previous.digestByPackage()
        val after = digestByPackage()
        return (before.keys + after.keys).filterTo(java.util.TreeSet<String>()) { before[it] != after[it] }
    }

    private fun digestByPackage(): Map<String, String> = render().lineSequence()
        .filter { it.isNotEmpty() }
        .groupBy({ it.split('|')[1] }, { it })
        .mapValues { (_, lines) -> lines.joinToString("\n") }
}

object DefinitionsMerge {
    /**
     * Pure and total: [overlay] may be null, retired (its ordinal is not above the bundled baseline) or even built
     * for a different bundled state; rows that do not fit [bundled] are skipped, they never create new entries.
     */
    fun merge(bundled: BundledDefinitions, overlay: ValidatedOverlay?): EffectiveDefinitions {
        val usable = overlay != null && overlay.ordinal > bundled.baselineOrdinal

        val alternates = LinkedHashMap<PackagePlatform, MutableList<Sourced>>()
        for ((key, list) in bundled.activityAlternates) {
            val target = alternates.getOrPut(key) { mutableListOf() }
            list.forEach { addIfAbsent(target, it, fromOverlay = false) }
        }
        val cores = LinkedHashMap<PackagePlatform, MutableList<Sourced>>()
        for (seed in bundled.seeds) {
            for ((platform, list) in seed.retroArchCores) {
                val target = cores.getOrPut(PackagePlatform(seed.packageName, platform)) { mutableListOf() }
                list.forEach { addIfAbsent(target, it, fromOverlay = false) }
            }
        }
        // Bundled rows first, then the overlay's on top: the union per package and platform. A row is only ever
        // added, so an overlay can narrow "everything" to a list or lengthen a list, never shorten one.
        val extensions = LinkedHashMap<PackagePlatform, MutableSet<String>>()
        val regexes = LinkedHashMap<PackagePlatform, MutableSet<String>>()
        for (seed in bundled.seeds) {
            for ((platform, listed) in seed.listedExtensions) {
                if (platform !in seed.platforms || listed.isEmpty()) continue
                extensions.getOrPut(PackagePlatform(seed.packageName, platform)) { java.util.TreeSet() }.addAll(listed)
            }
            for ((platform, listed) in seed.listedRegexes) {
                if (platform !in seed.platforms || listed.isEmpty()) continue
                regexes.getOrPut(PackagePlatform(seed.packageName, platform)) { java.util.TreeSet() }.addAll(listed)
            }
        }

        if (overlay != null && usable) {
            val ordered = overlay.rows.sortedWith(
                compareBy<OverlayRow>({ it.platform.value }, { it.packageName }, { detail(it) }),
            )
            for (row in ordered) {
                val seed = bundled.seed(row.packageName) ?: continue
                if (row.platform !in seed.platforms) continue
                val key = PackagePlatform(row.packageName, row.platform)
                when (row) {
                    is ActivityAlternateRow -> {
                        if (seed.capability == EmulatorCapability.RECOGNIZE_ONLY) continue
                        addIfAbsent(alternates.getOrPut(key) { mutableListOf() }, row.activity, fromOverlay = true)
                    }
                    is RetroArchCoreRow -> {
                        if (row.platform !in seed.retroArchCores.keys || seed.capability == EmulatorCapability.RECOGNIZE_ONLY) continue
                        addIfAbsent(cores.getOrPut(key) { mutableListOf() }, row.core, fromOverlay = true)
                    }
                    is ExtensionsRow -> extensions.getOrPut(key) { java.util.TreeSet() }.addAll(row.extensions)
                    is FilenameRegexRow -> regexes.getOrPut(key) { java.util.TreeSet() }.add(row.regex)
                }
            }
        }
        return EffectiveDefinitions(
            baselineOrdinal = bundled.baselineOrdinal,
            overlayOrdinal = if (usable) overlay?.ordinal else null,
            overlayRetired = overlay != null && !usable,
            dispatchPackages = bundled.dispatchPackages,
            alternates = alternates.mapValues { it.value.toList() },
            cores = cores.mapValues { it.value.toList() },
            extensions = extensions.mapValues { it.value.toList() },
            regexes = regexes.mapValues { it.value.toList() },
        )
    }

    private fun addIfAbsent(list: MutableList<Sourced>, value: String, fromOverlay: Boolean) {
        if (list.none { it.value == value }) list += Sourced(value, fromOverlay)
    }

    private fun detail(row: OverlayRow): String = when (row) {
        is ActivityAlternateRow -> row.activity
        is RetroArchCoreRow -> row.core
        is ExtensionsRow -> row.extensions.joinToString(",")
        is FilenameRegexRow -> row.regex
    }
}

/**
 * What scan and launch read. It holds a snapshot and tells listeners ONLY when the merged behaviour changed, so a
 * re-applied or reordered-but-equal overlay makes no noise downstream (note I2). It has no network handle.
 */
class EffectiveDefinitionsHolder(initial: EffectiveDefinitions) {
    @Volatile
    private var state: EffectiveDefinitions = initial
    private val listeners = CopyOnWriteArrayList<(EffectiveDefinitions) -> Unit>()
    private val emitted = AtomicInteger(0)
    private val updateFlag = AtomicBoolean(false)

    val current: EffectiveDefinitions get() = state

    /** How many times a listener was told something. */
    val emissions: Int get() = emitted.get()

    /** Set when a refresh met a schema this build cannot read; shown once as "needs an app update". */
    var needsAppUpdate: Boolean
        get() = updateFlag.get()
        set(value) = updateFlag.set(value)

    fun addListener(listener: (EffectiveDefinitions) -> Unit) {
        listeners += listener
    }

    /** Swaps in [next]; returns true (and notifies) only when the behaviour differs from the current view. */
    @Synchronized
    fun publish(next: EffectiveDefinitions): Boolean {
        val changed = next.stateKey != state.stateKey
        state = next
        if (changed) {
            emitted.incrementAndGet()
            listeners.forEach { it(next) }
        }
        return changed
    }
}
