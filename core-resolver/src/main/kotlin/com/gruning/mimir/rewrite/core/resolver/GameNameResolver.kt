package com.gruning.mimir.rewrite.core.resolver

import com.gruning.mimir.rewrite.core.model.DisplayTitle
import com.gruning.mimir.rewrite.core.model.PlatformId
import java.text.Normalizer
import java.util.Locale

/** The local role a filename/path proves without consulting any network metadata. */
enum class LocalContentRole {
    BASE,
    UPDATE,
    DLC,
    BIOS,
    SUPPORT,
}

/** Separate name domains: presentation, catalog lookup, and conservative filename identity. */
data class ResolvedGameName(
    val displayTitle: String,
    val catalogBaseName: String,
    val filenameIdentityKey: String,
    val version: String?,
    val contentRole: LocalContentRole,
)

/**
 * Pure, offline name resolver. Presentation cleanup must never erase evidence from filename identity, and
 * normalized catalog input is not itself a grouping key.
 */
class GameNameResolver {

    fun resolve(
        input: ResolutionInput,
        platformId: PlatformId? = null,
        relativePath: String = input.fileName,
    ): ResolvedGameName {
        val base = Normalizer.normalize(baseName(input), Normalizer.Form.NFC).trim()
        val terminalVersion = TRAILING_VERSION.find(base)?.groupValues?.get(1)
        val version = terminalVersion ?: parentheticalVersion(base)
        val withoutTrailingVersion = TRAILING_VERSION.replace(base, "")
        val displayBase = withoutTrailingVersion.replace(SEPARATORS, " ")
        val catalogBase = WHITESPACE.replace(withoutTrailingVersion.replace(SEPARATORS, " "), " ").trim()
        val identityBase = DISC_TOKEN.replace(base, " ")
        val identityKey = WHITESPACE.replace(identityBase.replace(NON_LETTER_OR_NUMBER, " "), " ")
            .trim()
            .lowercase(Locale.ROOT)

        return ResolvedGameName(
            displayTitle = DisplayTitle.clean(displayBase),
            catalogBaseName = catalogBase,
            filenameIdentityKey = identityKey.ifBlank { DisplayTitle.clean(displayBase).lowercase(Locale.ROOT) },
            version = version,
            contentRole = contentRole(input, platformId, relativePath),
        )
    }

    /**
     * Whether [input] is an emulator BIOS or firmware payload -- the same answer [resolve] gives as
     * `contentRole == BIOS`, without resolving a title.
     *
     * [resolve] normalises, version-parses and cleans the name through several regexes. A listing that only
     * needs the BIOS badge asked for all of that once per row on the main thread. This asks the two rules
     * that can ever say BIOS, cheapest first: the folder, then the extension, and only a file whose
     * extension could be a BIOS reaches the name pattern. Both answers come from [earlyRole], so they
     * cannot drift apart.
     */
    fun isBiosPayload(input: ResolutionInput, relativePath: String = input.fileName): Boolean =
        earlyRole(input, relativePath) == LocalContentRole.BIOS

    /**
     * The roles decided by the folder and the file name alone: BIOS, or SUPPORT for a support folder or a
     * disc sidecar. Null when neither applies and the platform-aware rules in [contentRole] have to look.
     */
    private fun earlyRole(input: ResolutionInput, relativePath: String): LocalContentRole? {
        val parents = relativePath.split('/', '\\').filter { it.isNotBlank() }.dropLast(1)
            .map(::normalizedSegment)
        val supportFolder = parents.lastOrNull { it in SUPPORT_FOLDERS }
        if (supportFolder != null) {
            return if (supportFolder == "bios" || supportFolder == "firmware") {
                LocalContentRole.BIOS
            } else {
                LocalContentRole.SUPPORT
            }
        }
        val ext = input.extension.lowercase(Locale.ROOT)
        if (ext in DISC_SIDECAR_EXTENSIONS) return LocalContentRole.SUPPORT
        if (ext !in BIOS_EXTENSIONS) return null
        val lower = Normalizer.normalize(input.fileName, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        return if (lower in EXACT_BIOS_NAMES || BIOS_NAME.containsMatchIn(" $lower")) LocalContentRole.BIOS else null
    }

    private fun contentRole(input: ResolutionInput, platformId: PlatformId?, relativePath: String): LocalContentRole {
        earlyRole(input, relativePath)?.let { return it }
        val lower = Normalizer.normalize(input.fileName, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        val ext = input.extension.lowercase(Locale.ROOT)

        if (platformId?.value == PlatformId.SWITCH.value && ext in SWITCH_PACKAGES) {
            val titleId = SWITCH_TITLE_ID.find(lower)?.groupValues?.get(1)
            val suffix = titleId?.takeLast(3)
            if (BASE_TAG.containsMatchIn(lower) || suffix == "000") return LocalContentRole.BASE
            if (UPDATE_TAG.containsMatchIn(lower) || UPDATE_WORD.containsMatchIn(lower) || suffix == "800") {
                return LocalContentRole.UPDATE
            }
            if (DLC_WORD.containsMatchIn(lower) || (suffix != null && suffix != "000" && suffix != "800")) {
                return LocalContentRole.DLC
            }
            return LocalContentRole.BASE
        }

        if (ext in GENERIC_SUPPORT_PACKAGES) {
            if (UPDATE_WORD.containsMatchIn(lower)) return LocalContentRole.UPDATE
            if (DLC_WORD.containsMatchIn(lower)) return LocalContentRole.DLC
        }
        return LocalContentRole.BASE
    }

    private fun baseName(input: ResolutionInput): String =
        if (input.extension.isBlank()) input.fileName else input.fileName.substringBeforeLast('.', input.fileName)

    private fun parentheticalVersion(base: String): String? = PAREN_CONTENT.findAll(base)
        .map { it.groupValues[1].trim() }
        .firstOrNull { VERSION_TOKEN.containsMatchIn(it) }

    private fun normalizedSegment(segment: String): String =
        WHITESPACE.replace(Normalizer.normalize(segment, Normalizer.Form.NFC).trim(), " ")
            .lowercase(Locale.ROOT)

    companion object {
        private val TRAILING_VERSION = Regex(
            """(?i)(?:\s+|[_-]+)(v\d+(?:[._-]\d+)+)(?=\s*(?:\[[^]]*\]\s*)*$)""",
        )
        private val PAREN_CONTENT = Regex("""\(([^)]*)\)""")
        private val VERSION_TOKEN = Regex("""\b(rev|v|version|beta|proto|demo|alpha)\b""", RegexOption.IGNORE_CASE)
        private val SEPARATORS = Regex("""[._]+""")
        private val WHITESPACE = Regex("""\s+""")
        private val NON_LETTER_OR_NUMBER = Regex("""[^\p{L}\p{N}]+""")
        private val DISC_TOKEN = Regex(
            """[\(\[]?\s*(?:disc|disk|cd|side)(?:\s*[-_]?\s*(?:[0-9]+|[ab])|[\s_-]+(?:viii|vii|vi|iv|iii|ii|ix|x|v|i)\b)(?:\s+of\s+[0-9ivx]+)?\s*[\)\]]?""",
            RegexOption.IGNORE_CASE,
        )

        private val SUPPORT_FOLDERS = setOf(
            "art", "artwork", "bezels", "bios", "boxart", "cache", "cheats", "covers", "dlc", "docs",
            "firmware", "manuals", "media", "patch", "patches", "saves", "screenshots", "states", "texture",
            "textures", "title-updates", "updates",
        )
        private val BIOS_EXTENSIONS = setOf("bin", "rom", "zip", "7z", "ic1")
        private val DISC_SIDECAR_EXTENSIONS = setOf("sbi", "sub", "idx", "mds", "toc")
        private val EXACT_BIOS_NAMES = setOf("neogeo.zip", "naomi.zip", "awbios.zip")
        private val BIOS_NAME = Regex(
            """(?i)(?:[ _-]bios\b|\bscph|\bdc_boot\b|\bdc_flash\b|\bsaturn_bios\b)""",
        )
        private val SWITCH_PACKAGES = setOf("nsp", "xci", "nca")
        private val GENERIC_SUPPORT_PACKAGES = setOf("pkg", "zip", "7z")
        private val SWITCH_TITLE_ID = Regex("""(?i)\[?(0100[0-9a-f]{12})\]?""")
        private val BASE_TAG = Regex("""(?i)\[(?:app|base|v0)\]""")
        private val UPDATE_TAG = Regex("""(?i)\[(?:upd|update)\]""")
        private val UPDATE_WORD = Regex("""(?i)(?:^|[\s_(\[-])(?:update|patch)(?:$|[\s_)\].-])""")
        private val DLC_WORD = Regex("""(?i)(?:^|[\s_(\[-])(?:dlc|add[- ]?on)(?:$|[\s_)\].-])""")
    }
}
