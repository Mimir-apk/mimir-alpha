package com.gruning.mimir.tweaker

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * SoC families as data (design `TWEAKER_FAMILIES_AS_DATA_DESIGN_2026-10-13.md`, D1).
 *
 * This file is the model, the strict parser, the validator, the alias index and the pure
 * [FamilyTables] view. It is JVM-pure on purpose (only `org.json`): nothing here touches the
 * Android framework, the filesystem, a network or a clock, so the same bytes always give the same
 * [FamilyCatalog]. Persistence is [FamilyCatalogStore]; fetching and signatures are not here at
 * all.
 *
 * ## What an entry may and may not carry
 *
 * Data only: ids, a label, aliases and per-profile numbers. No paths, no keys, no commands. A
 * catalog entry never decides *whether* a mutation is allowed to run -- the live validators
 * (`certifiedFamilyCpuTargetsKhz`, `PlanTargetValidator`) still refuse off-table targets and
 * out-of-range levels on every apply. The one authority an entry does carry is `treatment`
 * (`SupportedUncertifiedProfiles` reads it), which is why a catalog is signed before it is staged
 * and why the three reserved ids below are mandatory.
 *
 * ## Matching is aliases, never regular expressions
 *
 * `match.soc` holds exact uppercase aliases with an optional trailing `*` (prefix). A pattern
 * language from an untrusted file is a backtracking-DoS surface on the daemon; aliases reproduce
 * today's `when` exactly. `match.platform` is the same shape in lower case, for `ro.board.platform`.
 *
 * ## Numbers are integers
 *
 * Anchor ratios are `[numerator, denominator]` integer pairs and GPU positions are
 * `level / (anchorLevels - 1)`. [Ratio.toDouble] and [FamilyGpuTable.Transposed.fraction] perform
 * the very same IEEE division the compiled planner performs (`Int.toDouble() / Int`), so the
 * rounding in `(fraction * liveMax).roundToInt()` cannot flip on a truncated decimal.
 */
internal object FamilyCatalogLimits {
    const val SCHEMA = 1
    const val MAX_BYTES = 64 * 1024
    const val MAX_NESTING = 12
    const val MAX_FAMILIES = 64
    const val MAX_SOC_ALIASES = 16
    const val MAX_PLATFORM_ALIASES = 8
    const val MAX_LABEL_LENGTH = 64
    const val MIN_KHZ = 100_000
    const val MAX_KHZ = 6_000_000
    const val MAX_POLICY_ID = 31
    const val MAX_POLICIES_PER_PROFILE = 16
    const val MAX_GPU_LEVEL = 63
    const val MIN_GPU_ANCHOR_LEVELS = 2
    const val MAX_GPU_ANCHOR_LEVELS = 64
    const val MIN_VENDOR_MODE = 0
    const val MAX_VENDOR_MODE = 2
    const val MAX_FACTOR_DENOMINATOR = 1_000

    /** The profiles a table may tabulate. `stock` is never tabulated: it is the player's own state. */
    val PROFILE_ORDER: List<String> = listOf("quiet", "balanced", "performance")
    val PROFILE_IDS: Set<String> = PROFILE_ORDER.toSet()

    /**
     * Compiled fallback ids (`HardwareClassifier`'s `qualcomm-adreno-candidate` and
     * `general-live-opp`). A device that matches no entry gets these -- never data -- so a data
     * entry may not claim them.
     */
    val COMPILED_FALLBACK_IDS: Set<String> = setOf("qualcomm-adreno-candidate", "general-live-opp")

    /**
     * `HandheldDeviceProfiles.profileId` compares these literally and the id is hashed into
     * `HardwareFingerprint` and journal identity, so all three must be present as
     * `mimir-certified-reference` or the whole catalog is rejected.
     */
    val RESERVED_IDS: List<String> = listOf(
        TweakerHardwareFamilies.SNAPDRAGON_8_ELITE_FAMILY_ID,
        TweakerHardwareFamilies.SNAPDRAGON_8_GEN_2_FAMILY_ID,
        TweakerHardwareFamilies.SM8250_FAMILY_ID,
    )

    val TREATMENTS: Set<String> = setOf(
        TweakerHardwareFamilies.MIMIR_CERTIFIED_REFERENCE,
        TweakerHardwareFamilies.GENERAL_LIVE_OPP,
    )

    val ID = Regex("[a-z0-9-]{3,40}")

    /**
     * An id must start with its SoC vendor, then name something (`qualcomm-8gen2`). The id becomes the
     * `"${id}-pserver"` device profile id for any chassis that matches no named one, and
     * `HandheldDeviceProfiles.supportsFanMode` grants the manual fan by the `ayn-`/`retroid-` prefix: with
     * no rule here a signed `retroid-x` would hand MANUAL fan authority to unmeasured cooling. A fixed vendor
     * list keeps every catalog id out of the chassis namespace (F-P2-2, 2026-10-13 review). The list can only
     * grow with a compiled change, like [FamilyTransport]; `tools/family-catalog/draft.py` emits `qualcomm-`.
     */
    val VENDOR_PREFIXES: List<String> = listOf(
        "qualcomm-", "mediatek-", "unisoc-", "samsung-", "google-", "rockchip-", "allwinner-", "amlogic-", "nvidia-",
    )

    /**
     * The compiled device profile ids ([HandheldDeviceProfiles.RP5_PROFILE_ID] and the profiles
     * [HandheldDeviceProfiles.profileId] mints for the Odin 3, the Thor and the Nova).
     */
    val COMPILED_PROFILE_IDS: Set<String> = setOf(
        HandheldDeviceProfiles.RP5_PROFILE_ID,
        CpuCeilingTransport.ODIN_3_PROFILE,
        HandheldDeviceProfiles.THOR_PROFILE_ID,
        HandheldDeviceProfiles.NOVA_PROFILE_ID,
    )

    /** True when [id], or the `-pserver` profile id minted from it, is one of the [COMPILED_PROFILE_IDS]. */
    fun claimsCompiledProfile(id: String): Boolean = id in COMPILED_PROFILE_IDS || "$id-pserver" in COMPILED_PROFILE_IDS

    /** True when [id] starts with one of the [VENDOR_PREFIXES] and names something after it. */
    fun hasVendorPrefix(id: String): Boolean = VENDOR_PREFIXES.any { id.startsWith(it) && id.length > it.length }
    val SOC_ALIAS_EXACT = Regex("[A-Z0-9_-]{2,32}")
    val SOC_ALIAS_PREFIX = Regex("[A-Z0-9_-]{3,31}\\*")
    val PLATFORM_ALIAS_EXACT = Regex("[a-z0-9_-]{2,32}")
    val PLATFORM_ALIAS_PREFIX = Regex("[a-z0-9_-]{3,31}\\*")
    val POLICY_ID = Regex("0|[1-9][0-9]?")
}

/**
 * Informational "may probe" names. The node stays the gate (the 2026-08-25 ruling,
 * `CpuCeilingTransport.selectedPath`): nothing is enabled or required because an entry lists one.
 * An unknown name rejects the entry, so the set can only grow with a compiled change.
 */
internal enum class FamilyTransport(val wire: String) {
    CPU_SCALING_MAX("cpu-scaling-max"),
    CPU_MSM_PERFORMANCE_QOS("cpu-msm-performance-qos"),
    GPU_KGSL_MAX_PWRLEVEL("gpu-kgsl-max-pwrlevel"),
    GPU_DEVFREQ_MAX("gpu-devfreq-max"),
    VENDOR_PERFORMANCE_MODE("vendor-performance-mode"),
    FAN_PWM("fan-pwm"),
    ;

    companion object {
        fun fromWire(wire: String): FamilyTransport? = entries.firstOrNull { it.wire == wire }
    }
}

/** An exact integer ratio; [toDouble] is the same IEEE division the compiled planner performs. */
internal data class Ratio(val numerator: Int, val denominator: Int) {
    fun toDouble(): Double = numerator.toDouble() / denominator
}

/** One profile's Odin-anchored little/prime pair: the Odin's own target over the Odin's own live max. */
internal data class AnchorRow(val little: Ratio, val prime: Ratio)

/** The CPU half of [FamilyTables]. Exactly one shape per family. */
internal sealed interface FamilyCpuTable {
    /**
     * Literal kHz per profile per policy (the 8 Elite row, today's `MIMIR_CERTIFIED_FAMILY_CPU_TARGETS_KHZ`).
     * The planner still requires the policy set to match the live topology and every value to be a
     * live OPP; this only supplies the numbers.
     */
    data class Khz(val byProfile: Map<String, Map<Int, Int>>) : FamilyCpuTable {
        fun targets(profileId: String): Map<Int, Int>? = byProfile[profileId]
    }

    /**
     * The Odin-anchored transposition (today's `ODIN_TRANSPOSITION_ELIGIBLE_FAMILY_IDS` plus
     * `ODIN_ANCHOR_CPU_FRACTIONS`): little/prime fractions of the anchor's own live maxima.
     */
    data class Anchor(val byProfile: Map<String, AnchorRow>) : FamilyCpuTable {
        /** (little, prime) as the planner's `Pair<Double, Double>`, or null for a profile with no row. */
        fun fractions(profileId: String): Pair<Double, Double>? =
            byProfile[profileId]?.let { it.little.toDouble() to it.prime.toDouble() }
    }

    /**
     * Tier factors for the generic live-OPP path (today's `LARGE_UNDERCLOCK_CPU_FACTOR` /
     * `MEDIUM_UNDERCLOCK_CPU_FACTOR`). Quiet takes [large], balanced [medium]; performance is the
     * nearest live OPP below maximum and has no factor.
     */
    data class General(val large: Ratio, val medium: Ratio) : FamilyCpuTable {
        companion object {
            val DEFAULT = General(large = Ratio(55, 100), medium = Ratio(78, 100))
        }
    }
}

/** The GPU half of [FamilyTables]. */
internal sealed interface FamilyGpuTable {
    /** No tabulated ceiling: the planner takes the full live table (level 0 through `FULL_GPU_CEILING_FACTOR`). */
    data object Full : FamilyGpuTable

    /**
     * Explicit `max_pwrlevel` indices (the 8 Elite's `odin3ExplicitGpuMaxPowerLevel`). A profile
     * with no entry, `stock` included, returns null and the planner falls through.
     */
    data class Levels(val byProfile: Map<String, Int>) : FamilyGpuTable {
        fun levelFor(profileId: String): Int? = byProfile[profileId]
    }

    /**
     * Position fractions against the anchor's own level count (today's
     * `ODIN_ANCHOR_GPU_POSITION_FRACTIONS` and `TRANSPOSED_UNCAPPED_GPU_PROFILE_IDS`).
     *
     * **Only [capped] profiles do arithmetic.** An [uncapped] profile short-circuits to level 0
     * before any fraction is computed -- exactly the planner's order: the `sorted.size < 2` guard
     * first, then `profileId in uncapped`, then the fraction. The compiled map also carries
     * fractions for balanced and performance, but they are unreachable; they are deliberately not
     * encoded so nobody mistakes them for planned numbers.
     */
    data class Transposed(
        val anchorLevels: Int,
        val capped: Map<String, Int>,
        val uncapped: Set<String>,
    ) : FamilyGpuTable {
        /** `level / (anchorLevels - 1)`, the same IEEE operation as the compiled constant. */
        fun fraction(profileId: String): Double? =
            capped[profileId]?.let { it.toDouble() / (anchorLevels - 1) }
    }
}

/**
 * Exactly what the planner reads today for one family, and nothing it does not: the CPU kind and
 * numbers, the GPU levels or fractions, the vendor mode per profile and the informational
 * transports.
 *
 * [vendorMode] is the per-profile `performance_mode` (the in-flight `VendorModeTable`). An absent
 * profile means "take the profile default"; `stock` is never present (never written).
 */
internal data class FamilyTables(
    val cpu: FamilyCpuTable,
    val gpu: FamilyGpuTable,
    val vendorMode: Map<String, Int>,
    val transports: Set<FamilyTransport>,
)

internal data class FamilyEntry(
    val id: String,
    val label: String,
    val treatment: String,
    val socAliases: List<String>,
    val platformAliases: List<String>,
    val tables: FamilyTables,
)

/**
 * A validated, immutable, pre-indexed catalog. Built only by [FamilyCatalogParser]; the index
 * is derived from [families], so two catalogs parsed from the same bytes are equal.
 */
internal class FamilyCatalog internal constructor(
    val revision: Int,
    val families: List<FamilyEntry>,
) {
    private val byId: Map<String, FamilyEntry> = families.associateBy(FamilyEntry::id)
    private val socExact: Map<String, FamilyEntry>
    private val socPrefixes: List<Pair<String, FamilyEntry>>
    private val platformExact: Map<String, FamilyEntry>
    private val platformPrefixes: List<Pair<String, FamilyEntry>>

    init {
        val socExactBuilder = LinkedHashMap<String, FamilyEntry>()
        val socPrefixBuilder = ArrayList<Pair<String, FamilyEntry>>()
        val platformExactBuilder = LinkedHashMap<String, FamilyEntry>()
        val platformPrefixBuilder = ArrayList<Pair<String, FamilyEntry>>()
        families.forEach { entry ->
            entry.socAliases.forEach { alias -> index(alias, entry, socExactBuilder, socPrefixBuilder) }
            entry.platformAliases.forEach { alias ->
                index(alias, entry, platformExactBuilder, platformPrefixBuilder)
            }
        }
        socExact = socExactBuilder
        platformExact = platformExactBuilder
        // Longest prefix first. A validated catalog has no overlapping aliases, so there are no
        // ties to break; the sort only makes the order independent of file order.
        socPrefixes = socPrefixBuilder.sortedByDescending { it.first.length }
        platformPrefixes = platformPrefixBuilder.sortedByDescending { it.first.length }
    }

    fun entry(familyId: String): FamilyEntry? = byId[familyId]

    /** The pure view the planner's table reads go through, or null for an id the catalog does not carry. */
    fun tables(familyId: String): FamilyTables? = byId[familyId]?.tables

    /**
     * The entry for [socModel] (`ro.soc.model`, case-insensitive, trimmed), else for [boardPlatform]
     * (`ro.board.platform`). Exact beats prefix; SoC beats platform. Null means "no catalog match":
     * the compiled `qualcomm-adreno-candidate` / `general-live-opp` fallbacks are the caller's, never data.
     */
    fun resolve(socModel: String, boardPlatform: String? = null): FamilyEntry? {
        lookup(socExact, socPrefixes, socModel.trim().uppercase(Locale.US))?.let { return it }
        val platform = boardPlatform?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: return null
        return lookup(platformExact, platformPrefixes, platform)
    }

    private fun lookup(
        exact: Map<String, FamilyEntry>,
        prefixes: List<Pair<String, FamilyEntry>>,
        key: String,
    ): FamilyEntry? {
        if (key.isEmpty()) return null
        exact[key]?.let { return it }
        return prefixes.firstOrNull { key.startsWith(it.first) }?.second
    }

    override fun equals(other: Any?): Boolean =
        other is FamilyCatalog && revision == other.revision && families == other.families

    override fun hashCode(): Int = 31 * revision + families.hashCode()

    override fun toString(): String = "FamilyCatalog(revision=$revision, families=${families.map { it.id }})"

    private companion object {
        fun index(
            alias: String,
            entry: FamilyEntry,
            exact: MutableMap<String, FamilyEntry>,
            prefixes: MutableList<Pair<String, FamilyEntry>>,
        ) {
            if (alias.endsWith("*")) prefixes += alias.dropLast(1) to entry else exact[alias] = entry
        }
    }
}

/** Why a whole catalog was refused. Every one leaves the previously active catalog in place. */
internal enum class CatalogRejection {
    TOO_LARGE,
    ENCODING,
    SYNTAX,
    SCHEMA,
    REVISION,
    FAMILIES,
    DUPLICATE_ID,
    RESERVED_ID,
    ALIAS_OVERLAP,
}

/** An invalid entry that was dropped, with a stable machine-readable [reason] (and its id, when readable). */
internal data class DroppedEntry(val index: Int, val id: String?, val reason: String)

/** The result of [FamilyCatalogParser.parseStandaloneEntry]. */
internal sealed interface FamilyEntryParse {
    data class Valid(val entry: FamilyEntry) : FamilyEntryParse
    data class Invalid(val reason: String) : FamilyEntryParse
}

internal sealed interface FamilyCatalogParse {
    data class Accepted(val catalog: FamilyCatalog, val dropped: List<DroppedEntry>) : FamilyCatalogParse
    data class Rejected(val reason: CatalogRejection, val detail: String) : FamilyCatalogParse
}

/**
 * The strict parser and validator. Never throws: any input yields [FamilyCatalogParse.Accepted] or
 * [FamilyCatalogParse.Rejected].
 *
 * Whole-catalog refusals (size, encoding, syntax, schema, revision, duplicate ids, a missing
 * reserved id, an alias claimed by two entries) keep the previous catalog. A single invalid entry
 * is dropped with a named reason and the rest are kept. Unknown fields are ignored everywhere.
 *
 * Types are strict: a number must be a JSON integer (`"5"`, `5.0` and `true` are refused), because
 * `org.json`'s `optInt` would otherwise coerce them silently.
 */
internal object FamilyCatalogParser {
    private class Problem(val reason: String) : RuntimeException(reason, null, false, false)

    fun parse(bytes: ByteArray): FamilyCatalogParse {
        if (bytes.size > FamilyCatalogLimits.MAX_BYTES) {
            return reject(CatalogRejection.TOO_LARGE, "${bytes.size} bytes")
        }
        if (bytes.isEmpty()) return reject(CatalogRejection.SYNTAX, "empty")
        scanBytes(bytes)?.let { return it }
        val root = try {
            val tokener = JSONTokener(String(bytes, Charsets.US_ASCII))
            val value = tokener.nextValue()
            if (value !is JSONObject) return reject(CatalogRejection.SYNTAX, "the document is not an object")
            if (tokener.nextClean() != '\u0000') {
                return reject(CatalogRejection.SYNTAX, "trailing content after the document")
            }
            value
        } catch (e: Exception) {
            return reject(CatalogRejection.SYNTAX, e.javaClass.simpleName)
        }

        if (root.opt("schema").strictInt() != FamilyCatalogLimits.SCHEMA) {
            return reject(CatalogRejection.SCHEMA, "schema=${root.opt("schema")}")
        }
        val revision = root.opt("revision").strictInt()
        if (revision == null || revision < 1) return reject(CatalogRejection.REVISION, "revision=${root.opt("revision")}")
        val families = root.opt("families") as? JSONArray
            ?: return reject(CatalogRejection.FAMILIES, "families is not an array")
        if (families.length() < 1 || families.length() > FamilyCatalogLimits.MAX_FAMILIES) {
            return reject(CatalogRejection.FAMILIES, "families length ${families.length()}")
        }

        val entries = ArrayList<FamilyEntry>()
        val dropped = ArrayList<DroppedEntry>()
        for (index in 0 until families.length()) {
            val raw = families.opt(index)
            try {
                entries += parseEntry(raw as? JSONObject ?: throw Problem("entry.not-object"))
            } catch (problem: Problem) {
                dropped += DroppedEntry(index, (raw as? JSONObject)?.readableId(), problem.reason)
            }
        }

        entries.groupBy(FamilyEntry::id).entries.firstOrNull { it.value.size > 1 }?.let {
            return reject(CatalogRejection.DUPLICATE_ID, it.key)
        }
        FamilyCatalogLimits.RESERVED_IDS.forEach { reserved ->
            val present = entries.firstOrNull { it.id == reserved }
            if (present == null) {
                val why = dropped.firstOrNull { it.id == reserved }?.let { " (dropped: ${it.reason})" }.orEmpty()
                return reject(CatalogRejection.RESERVED_ID, "$reserved is missing$why")
            }
            if (present.treatment != TweakerHardwareFamilies.MIMIR_CERTIFIED_REFERENCE) {
                return reject(CatalogRejection.RESERVED_ID, "$reserved must be ${TweakerHardwareFamilies.MIMIR_CERTIFIED_REFERENCE}")
            }
        }
        overlap(entries, FamilyEntry::socAliases, "soc")?.let { return it }
        overlap(entries, FamilyEntry::platformAliases, "platform")?.let { return it }

        return FamilyCatalogParse.Accepted(FamilyCatalog(revision, entries.toList()), dropped.toList())
    }

    private fun reject(reason: CatalogRejection, detail: String) = FamilyCatalogParse.Rejected(reason, detail)

    /**
     * One catalog entry on its own: the same byte rules as a whole catalog (ASCII, shallow nesting, no
     * lenient `org.json` tokens) and the same per-entry validation ([parseEntry]), but none of the
     * catalog-level rules (reserved ids, duplicate ids, alias overlap, revision). That is what the
     * host-side corpus (`tools/family-catalog/corpus`) is: drafts that reuse reserved ids and the
     * bundled aliases, which a catalog containing them would rightly refuse. Never throws.
     */
    fun parseStandaloneEntry(bytes: ByteArray): FamilyEntryParse {
        if (bytes.size > FamilyCatalogLimits.MAX_BYTES) return FamilyEntryParse.Invalid("too-large")
        if (bytes.isEmpty()) return FamilyEntryParse.Invalid("empty")
        scanBytes(bytes)?.let { return FamilyEntryParse.Invalid("${it.reason}: ${it.detail}") }
        return try {
            val tokener = JSONTokener(String(bytes, Charsets.US_ASCII))
            val value = tokener.nextValue()
            when {
                value !is JSONObject -> FamilyEntryParse.Invalid("not-object")
                tokener.nextClean() != '\u0000' -> FamilyEntryParse.Invalid("trailing-content")
                else -> FamilyEntryParse.Valid(parseEntry(value))
            }
        } catch (problem: Problem) {
            FamilyEntryParse.Invalid(problem.reason)
        } catch (e: Exception) {
            FamilyEntryParse.Invalid(e.javaClass.simpleName)
        }
    }

    /**
     * ASCII-only, no control characters, shallow nesting, and no token `org.json` would accept
     * leniently (single quotes, comments, `=`, `;`). Returns the rejection, or null when the bytes pass.
     */
    private fun scanBytes(bytes: ByteArray): FamilyCatalogParse.Rejected? {
        var depth = 0
        var inString = false
        var escaped = false
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            val control = value < 0x20 && value != 0x09 && value != 0x0A && value != 0x0D
            if (value >= 0x80 || value == 0x7F || control) {
                return reject(CatalogRejection.ENCODING, "byte 0x%02x".format(value))
            }
            val c = value.toChar()
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                    // A raw control character inside a string is a JSON error and a smuggling vector.
                    c.code < 0x20 -> return reject(CatalogRejection.ENCODING, "control character inside a string")
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> {
                    depth++
                    if (depth > FamilyCatalogLimits.MAX_NESTING) {
                        return reject(CatalogRejection.SYNTAX, "nesting deeper than ${FamilyCatalogLimits.MAX_NESTING}")
                    }
                }
                '}', ']' -> depth--
                '\'', '#', '/', '=', ';' -> return reject(CatalogRejection.SYNTAX, "token '$c' is not JSON")
            }
        }
        return null
    }

    private fun overlap(
        entries: List<FamilyEntry>,
        aliases: (FamilyEntry) -> List<String>,
        namespace: String,
    ): FamilyCatalogParse.Rejected? {
        for (i in entries.indices) {
            for (j in i + 1 until entries.size) {
                for (a in aliases(entries[i])) {
                    for (b in aliases(entries[j])) {
                        if (aliasesOverlap(a, b)) {
                            return reject(
                                CatalogRejection.ALIAS_OVERLAP,
                                "$namespace alias '$a' (${entries[i].id}) and '$b' (${entries[j].id}) both match",
                            )
                        }
                    }
                }
            }
        }
        return null
    }

    /** True when some string would match both aliases. */
    internal fun aliasesOverlap(a: String, b: String): Boolean {
        val aPrefix = a.endsWith("*")
        val bPrefix = b.endsWith("*")
        val aText = if (aPrefix) a.dropLast(1) else a
        val bText = if (bPrefix) b.dropLast(1) else b
        return when {
            !aPrefix && !bPrefix -> aText == bText
            aPrefix && !bPrefix -> bText.startsWith(aText)
            !aPrefix && bPrefix -> aText.startsWith(bText)
            else -> aText.startsWith(bText) || bText.startsWith(aText)
        }
    }

    private fun JSONObject.readableId(): String? =
        (opt("id") as? String)?.takeIf { FamilyCatalogLimits.ID.matches(it) }

    private fun parseEntry(entry: JSONObject): FamilyEntry {
        val id = entry.opt("id") as? String ?: throw Problem("id.missing")
        if (!FamilyCatalogLimits.ID.matches(id)) throw Problem("id.invalid")
        if (id in FamilyCatalogLimits.COMPILED_FALLBACK_IDS) throw Problem("id.compiled-fallback")
        if (FamilyCatalogLimits.claimsCompiledProfile(id)) throw Problem("id.compiled-profile")
        if (!FamilyCatalogLimits.hasVendorPrefix(id)) throw Problem("id.vendor-prefix")

        val label = entry.opt("label") as? String ?: throw Problem("label.missing")
        if (label.isEmpty() || label.length > FamilyCatalogLimits.MAX_LABEL_LENGTH) throw Problem("label.length")
        if (label.any { it.code !in 0x20..0x7E }) throw Problem("label.not-printable-ascii")
        if (label.isBlank()) throw Problem("label.blank")

        val treatment = entry.opt("treatment") as? String ?: throw Problem("treatment.missing")
        if (treatment !in FamilyCatalogLimits.TREATMENTS) throw Problem("treatment.unknown")

        val match = entry.opt("match") as? JSONObject ?: throw Problem("match.missing")
        val soc = aliasList(
            match.opt("soc"),
            required = true,
            max = FamilyCatalogLimits.MAX_SOC_ALIASES,
            exact = FamilyCatalogLimits.SOC_ALIAS_EXACT,
            prefix = FamilyCatalogLimits.SOC_ALIAS_PREFIX,
            name = "match.soc",
        )
        val platform = aliasList(
            match.opt("platform"),
            required = false,
            max = FamilyCatalogLimits.MAX_PLATFORM_ALIASES,
            exact = FamilyCatalogLimits.PLATFORM_ALIAS_EXACT,
            prefix = FamilyCatalogLimits.PLATFORM_ALIAS_PREFIX,
            name = "match.platform",
        )

        val tables = FamilyTables(
            cpu = parseCpu(entry.opt("cpu") as? JSONObject ?: throw Problem("cpu.missing")),
            gpu = parseGpu(entry.opt("gpu")),
            vendorMode = parseVendorMode(entry.opt("vendorMode")),
            transports = parseTransports(entry.opt("transports")),
        )
        return FamilyEntry(id, label, treatment, soc, platform, tables)
    }

    private fun aliasList(
        value: Any?,
        required: Boolean,
        max: Int,
        exact: Regex,
        prefix: Regex,
        name: String,
    ): List<String> {
        if (value == null) {
            if (required) throw Problem("$name.missing")
            return emptyList()
        }
        val array = value as? JSONArray ?: throw Problem("$name.not-array")
        if (array.length() > max) throw Problem("$name.too-many")
        if (required && array.length() == 0) throw Problem("$name.empty")
        val aliases = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            val alias = array.opt(i) as? String ?: throw Problem("$name.alias-invalid")
            if (!exact.matches(alias) && !prefix.matches(alias)) throw Problem("$name.alias-invalid")
            aliases += alias
        }
        return aliases.toList()
    }

    private fun parseCpu(cpu: JSONObject): FamilyCpuTable = when (cpu.opt("kind") as? String) {
        "khz" -> parseKhz(cpu)
        "anchor" -> parseAnchor(cpu)
        "general" -> parseGeneral(cpu)
        else -> throw Problem("cpu.kind.unknown")
    }

    private fun parseKhz(cpu: JSONObject): FamilyCpuTable.Khz {
        val profiles = profileObject(cpu.opt("profiles"), "cpu.khz.profiles")
        val byProfile = LinkedHashMap<String, Map<Int, Int>>()
        for (profileId in FamilyCatalogLimits.PROFILE_ORDER) {
            val row = profiles.opt(profileId) ?: continue
            val policies = row as? JSONObject ?: throw Problem("cpu.khz.row-invalid")
            if (policies.length() < 1 || policies.length() > FamilyCatalogLimits.MAX_POLICIES_PER_PROFILE) {
                throw Problem("cpu.khz.policy-count")
            }
            val targets = java.util.TreeMap<Int, Int>()
            for (key in policies.keys()) {
                if (!FamilyCatalogLimits.POLICY_ID.matches(key)) throw Problem("cpu.khz.policy-id")
                val policyId = key.toInt()
                if (policyId > FamilyCatalogLimits.MAX_POLICY_ID) throw Problem("cpu.khz.policy-id")
                val khz = policies.opt(key).strictInt() ?: throw Problem("cpu.khz.not-integer")
                if (khz !in FamilyCatalogLimits.MIN_KHZ..FamilyCatalogLimits.MAX_KHZ) {
                    throw Problem("cpu.khz.out-of-bounds")
                }
                targets[policyId] = khz
            }
            byProfile[profileId] = targets.toMap()
        }
        if (byProfile.values.map { it.keys }.distinct().size > 1) throw Problem("cpu.khz.policy-sets-differ")
        return FamilyCpuTable.Khz(byProfile)
    }

    private fun parseAnchor(cpu: JSONObject): FamilyCpuTable.Anchor {
        val profiles = profileObject(cpu.opt("profiles"), "cpu.anchor.profiles")
        val byProfile = LinkedHashMap<String, AnchorRow>()
        for (profileId in FamilyCatalogLimits.PROFILE_ORDER) {
            val row = profiles.opt(profileId) ?: continue
            val pair = row as? JSONObject ?: throw Problem("cpu.anchor.row-invalid")
            byProfile[profileId] = AnchorRow(
                little = anchorRatio(pair.opt("little")),
                prime = anchorRatio(pair.opt("prime")),
            )
        }
        return FamilyCpuTable.Anchor(byProfile)
    }

    /** `[target kHz, anchor max kHz]`: both in the kHz bounds and the target never above the max. */
    private fun anchorRatio(value: Any?): Ratio {
        val (numerator, denominator) = intPair(value, "cpu.anchor.pair")
        if (numerator !in FamilyCatalogLimits.MIN_KHZ..FamilyCatalogLimits.MAX_KHZ ||
            denominator !in FamilyCatalogLimits.MIN_KHZ..FamilyCatalogLimits.MAX_KHZ
        ) {
            throw Problem("cpu.anchor.out-of-bounds")
        }
        if (numerator > denominator) throw Problem("cpu.anchor.above-max")
        return Ratio(numerator, denominator)
    }

    private fun parseGeneral(cpu: JSONObject): FamilyCpuTable.General {
        val factors = cpu.opt("factors") ?: return FamilyCpuTable.General.DEFAULT
        val obj = factors as? JSONObject ?: throw Problem("cpu.general.factors-invalid")
        return FamilyCpuTable.General(
            large = factorRatio(obj.opt("large")),
            medium = factorRatio(obj.opt("medium")),
        )
    }

    /** A tier factor strictly between 0 and 1. */
    private fun factorRatio(value: Any?): Ratio {
        val (numerator, denominator) = intPair(value, "cpu.general.factor")
        if (denominator !in 2..FamilyCatalogLimits.MAX_FACTOR_DENOMINATOR) throw Problem("cpu.general.factor-bounds")
        if (numerator < 1 || numerator >= denominator) throw Problem("cpu.general.factor-bounds")
        return Ratio(numerator, denominator)
    }

    private fun parseGpu(gpu: Any?): FamilyGpuTable {
        if (gpu == null) return FamilyGpuTable.Full
        val obj = gpu as? JSONObject ?: throw Problem("gpu.not-object")
        return when (obj.opt("kind") as? String) {
            "levels" -> {
                val levels = profileObject(obj.opt("levels"), "gpu.levels")
                FamilyGpuTable.Levels(profileLevels(levels, FamilyCatalogLimits.MAX_GPU_LEVEL, "gpu.levels"))
            }
            "transposed" -> {
                val anchorLevels = obj.opt("anchorLevels").strictInt() ?: throw Problem("gpu.anchor-levels")
                if (anchorLevels !in FamilyCatalogLimits.MIN_GPU_ANCHOR_LEVELS..FamilyCatalogLimits.MAX_GPU_ANCHOR_LEVELS) {
                    throw Problem("gpu.anchor-levels")
                }
                val capped = obj.opt("capped")?.let {
                    profileLevels(profileObject(it, "gpu.capped", allowEmpty = true), anchorLevels - 1, "gpu.capped")
                } ?: emptyMap()
                val uncapped = profileSet(obj.opt("uncapped"), "gpu.uncapped")
                if (capped.keys.any { it in uncapped }) throw Problem("gpu.profile-capped-and-uncapped")
                if (capped.isEmpty() && uncapped.isEmpty()) throw Problem("gpu.transposed-empty")
                FamilyGpuTable.Transposed(anchorLevels, capped, uncapped)
            }
            else -> throw Problem("gpu.kind.unknown")
        }
    }

    private fun parseVendorMode(value: Any?): Map<String, Int> {
        if (value == null) return emptyMap()
        val obj = value as? JSONObject ?: throw Problem("vendorMode.not-object")
        for (key in obj.keys()) if (key !in FamilyCatalogLimits.PROFILE_IDS) throw Problem("vendorMode.profile-unknown")
        val modes = LinkedHashMap<String, Int>()
        for (profileId in FamilyCatalogLimits.PROFILE_ORDER) {
            val raw = obj.opt(profileId) ?: continue
            val mode = raw.strictInt() ?: throw Problem("vendorMode.not-integer")
            if (mode !in FamilyCatalogLimits.MIN_VENDOR_MODE..FamilyCatalogLimits.MAX_VENDOR_MODE) {
                throw Problem("vendorMode.out-of-range")
            }
            modes[profileId] = mode
        }
        return modes
    }

    private fun parseTransports(value: Any?): Set<FamilyTransport> {
        if (value == null) return emptySet()
        val array = value as? JSONArray ?: throw Problem("transports.not-array")
        if (array.length() > FamilyTransport.entries.size) throw Problem("transports.too-many")
        val transports = LinkedHashSet<FamilyTransport>()
        for (i in 0 until array.length()) {
            val name = array.opt(i) as? String ?: throw Problem("transports.unknown")
            transports += FamilyTransport.fromWire(name) ?: throw Problem("transports.unknown")
        }
        return transports
    }

    /** A JSON object whose keys are all profile ids and which carries at least one (unless [allowEmpty]). */
    private fun profileObject(value: Any?, name: String, allowEmpty: Boolean = false): JSONObject {
        val obj = value as? JSONObject ?: throw Problem("$name.missing")
        for (key in obj.keys()) if (key !in FamilyCatalogLimits.PROFILE_IDS) throw Problem("$name.profile-unknown")
        if (obj.length() == 0 && !allowEmpty) throw Problem("$name.empty")
        return obj
    }

    private fun profileLevels(obj: JSONObject, maxLevel: Int, name: String): Map<String, Int> {
        val levels = LinkedHashMap<String, Int>()
        for (profileId in FamilyCatalogLimits.PROFILE_ORDER) {
            val raw = obj.opt(profileId) ?: continue
            val level = raw.strictInt() ?: throw Problem("$name.not-integer")
            if (level !in 0..maxLevel) throw Problem("$name.out-of-range")
            levels[profileId] = level
        }
        return levels
    }

    private fun profileSet(value: Any?, name: String): Set<String> {
        if (value == null) return emptySet()
        val array = value as? JSONArray ?: throw Problem("$name.not-array")
        val ids = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            val id = array.opt(i) as? String ?: throw Problem("$name.profile-unknown")
            if (id !in FamilyCatalogLimits.PROFILE_IDS) throw Problem("$name.profile-unknown")
            ids += id
        }
        return FamilyCatalogLimits.PROFILE_ORDER.filter { it in ids }.toSet()
    }

    private fun intPair(value: Any?, name: String): Pair<Int, Int> {
        val array = value as? JSONArray ?: throw Problem("$name.not-array")
        if (array.length() != 2) throw Problem("$name.length")
        val first = array.opt(0).strictInt() ?: throw Problem("$name.not-integer")
        val second = array.opt(1).strictInt() ?: throw Problem("$name.not-integer")
        return first to second
    }

    /** A JSON integer that fits an Int; strings, doubles and booleans are not integers here. */
    private fun Any?.strictInt(): Int? = when (this) {
        is Int -> this
        is Long -> if (this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) toInt() else null
        else -> null
    }
}
