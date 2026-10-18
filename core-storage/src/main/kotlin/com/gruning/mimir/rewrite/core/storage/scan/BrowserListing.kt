package com.gruning.mimir.rewrite.core.storage.scan

/**
 * The pure model behind Mimir's own folder browser.
 *
 * WHY THIS EXISTS (measured, not taste). "Add games" hands off to the system SAF picker, and every hop
 * between Mimir and `com.android.documentsui` slams the Odin's fan to 50% of the PWM period — worst case
 * 10.2 s before this week's repair work, ~0.6 s mean after it. HOME is provably immune; the picker hop is
 * the discriminator. Owning the surface removes the hop, and therefore removes the excursion, whichever
 * vendor process turns out to be doing the writing.
 *
 * The scan pipeline already walks real paths — `core-storage` contains no `DocumentFile` usage at all — so
 * SAF was only ever a *chooser*. This replaces the chooser, not the storage layer.
 *
 * Deliberately free of Android and of any extension table: platform knowledge already exists in
 * `core-resolver` (`ExtensionPlatformClassifier`, `ScanRecognizer`) and is passed in. A second table here
 * would drift from the first, and the drift would be invisible.
 */
object BrowserListing {

    /** What a row IS, which decides how it reads and what may be done to it. */
    enum class RowKind {
        /** A directory. Always first, always enterable. */
        DIRECTORY,

        /** Recognised game content for some platform. */
        GAME,

        /** An archive that could be extracted in place. */
        ARCHIVE,

        /** BIOS-ish payload. Shown honestly rather than offered as a game. */
        BIOS,

        /** Saves, states, sidecars, artwork: real files, de-emphasised, never hidden. */
        SUPPORT,

        /** Anything else on disk. */
        OTHER,
    }

    data class Row(
        val name: String,
        val kind: RowKind,
        /** Platform label from the injected classifier, or null when it recognises nothing. */
        val platformHint: String? = null,
        /**
         * Dimmed, not removed. A file manager that hides what is on disk is worse than none: the player
         * cannot debug a library they cannot see.
         */
        val deEmphasised: Boolean = false,
    ) {
        /** Only archives offer extraction, and only when the caller can actually read them. */
        val extractable: Boolean get() = kind == RowKind.ARCHIVE
    }

    /**
     * Archives Mimir can offer to extract.
     *
     * `zip` alone ships first: `java.util.zip` is in the platform, and there is no archive-aware scanning
     * anywhere in this tree today. `7z` is listed because commons-compress reads it and is Apache-2.0
     * (see the root `NOTICE`); an archive library whose license does not combine with GPL-3.0-or-later
     * cannot go in. `rar` is deliberately ABSENT: the reference unrar licence forbids reimplementing RAR
     * compression and is not a normal open licence.
     */
    val EXTRACTABLE_EXTENSIONS = setOf("zip", "7z")

    /** Payloads that are support material rather than content. Kept visible, shown quietly. */
    private val SUPPORT_EXTENSIONS = setOf(
        "srm", "sav", "state", "st0", "st1", "st2", "st3", "st4", "st5", "st6", "st7", "st8", "st9",
        "txt", "dat", "xml", "cfg", "ini", "log", "nfo", "db", "png", "jpg", "jpeg", "webp",
    )

    /** Directory names that are machinery, not library. Dimmed so a player can still find their saves. */
    private val SUPPORT_DIRECTORIES = setOf(
        "saves", "savestates", "states", "screenshots", "media", "cheats", "system", "cache",
        "downloads", "artwork", "gamelists", ".mimir",
    )

    /**
     * Build the ordered rows for one directory.
     *
     * @param entries what the filesystem returned: name + whether it is a directory.
     * @param classifyPlatform the EXISTING resolver classifier, injected. Returns a display label for a
     *   file name, or null when it recognises nothing.
     *
     * Ordering is directories first, then everything else, each case-insensitively by name — the order a
     * person expects, not filesystem order, which is arbitrary and differs between volumes.
     */
    fun rows(
        entries: List<Entry>,
        classifyPlatform: (String) -> String? = { null },
    ): List<Row> = entries
        .map { entry -> row(entry, classifyPlatform) }
        .sortedWith(compareBy({ it.kind != RowKind.DIRECTORY }, { it.name.lowercase() }))

    private fun row(entry: Entry, classifyPlatform: (String) -> String?): Row {
        if (entry.isDirectory) {
            return Row(
                name = entry.name,
                kind = RowKind.DIRECTORY,
                deEmphasised = entry.name.lowercase() in SUPPORT_DIRECTORIES || entry.name.startsWith("."),
            )
        }
        val extension = entry.name.substringAfterLast('.', "").lowercase()
        val platform = classifyPlatform(entry.name)
        return when {
            extension in EXTRACTABLE_EXTENSIONS -> Row(entry.name, RowKind.ARCHIVE, platform)
            entry.isBios -> Row(entry.name, RowKind.BIOS, platform)
            platform != null -> Row(entry.name, RowKind.GAME, platform)
            extension in SUPPORT_EXTENSIONS || entry.name.startsWith(".") ->
                Row(entry.name, RowKind.SUPPORT, deEmphasised = true)
            else -> Row(entry.name, RowKind.OTHER)
        }
    }

    /**
     * One filesystem entry, as the browser needs it.
     *
     * [isBios] is supplied by the caller because BIOS knowledge lives in `core-resolver`
     * (`GameNameResolver.BIOS_EXTENSIONS`) and must not be duplicated here.
     */
    data class Entry(
        val name: String,
        val isDirectory: Boolean,
        val isBios: Boolean = false,
    )
}
