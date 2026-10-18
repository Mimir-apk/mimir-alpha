package com.gruning.mimir.rewrite.core.model

/**
 * How sure Mimir is that a required BIOS file is present. The middle state matters: under scoped
 * storage Mimir often *cannot read* the emulator's private BIOS folder, so it must be honest that
 * presence is UNKNOWN rather than falsely claim "missing".
 */
enum class BiosPresence {
    /** Confirmed present (found + optionally hash-matched). */
    PRESENT,

    /** Confirmed absent (we can read the location and it is not there). */
    MISSING,

    /** Cannot determine — scoped storage blocks reading the emulator's BIOS directory. */
    UNKNOWN,
}

/**
 * A BIOS/firmware file a platform (or a specific emulator) needs to run, plus whether it is present.
 *
 * Mimir Alpha models BIOS as first-class truth because it drives [LaunchReadiness.NeedsBios] and the
 * "BIOS is placed when required" honesty the console posture demands. It NEVER points the user at a
 * download — Mimir does no BIOS sourcing (posture manifest); it only reports presence and, at most,
 * tells the user which file to place where.
 *
 * BIOS truth is per-emulator/package, not platform-only (native launch seam contract): PS2
 * forks (NetherSX2 / NetherSX2 Turnip / ArmSX2) keep BIOS in different package/app-data folders,
 * DuckStation PS1 imports are checksum-named, and melonDS DS is HLE-optional — so the same platform's
 * BIOS presence differs by the selected emulator. [emulatorId] / [packageName] carry that dimension; a
 * null [emulatorId] means "platform-wide / emulator-agnostic" (applies to any emulator for the platform).
 *
 * @property platformId the platform this BIOS serves.
 * @property fileName the canonical BIOS file name (e.g. "scph5501.bin"); the identity, not a path.
 * @property required whether the platform cannot run at all without it (vs optional/enhancement).
 * @property presence current presence knowledge.
 * @property emulatorId the emulator/fork this BIOS truth is scoped to; null = platform-wide (any emulator).
 * @property packageName the emulator package whose (private) BIOS folder this presence was read from; null when platform-wide/unknown.
 * @property expectedHash optional known-good hash for a confirming match; null = presence-only check.
 * @property expectedRelativePath optional emulator-relative location hint ("place it here"), never a source.
 */
data class BiosProfile(
    val platformId: PlatformId,
    val fileName: String,
    val required: Boolean = true,
    val presence: BiosPresence = BiosPresence.UNKNOWN,
    val emulatorId: EmulatorId? = null,
    val packageName: String? = null,
    val expectedHash: String? = null,
    val expectedRelativePath: String? = null,
) {
    /**
     * Whether this profile's truth applies to a launch that resolved to [targetEmulatorId]. A
     * platform-wide profile ([emulatorId] == null) applies to every emulator; an emulator-scoped
     * profile applies only to its own [emulatorId]. A null [targetEmulatorId] request (emulator not
     * yet resolved) matches everything, so readiness never silently drops a requirement.
     */
    fun appliesTo(targetEmulatorId: EmulatorId?): Boolean =
        emulatorId == null || targetEmulatorId == null || emulatorId == targetEmulatorId

    /** True when this required BIOS is confirmed absent (a hard [LaunchReadiness.NeedsBios] cause). */
    val isBlockingMissing: Boolean get() = required && presence == BiosPresence.MISSING

    /** True when required-ness matters but we can't confirm presence (honest "unknown", not a hard block). */
    val isBlockingUnknown: Boolean get() = required && presence == BiosPresence.UNKNOWN
}

/** How a local BIOS candidate is identified without reading or hashing its contents. */
enum class BiosCandidateKind {
    FILENAME,
    EXTENSION,
}

/** One required or optional filename pattern from the shipping BIOS-detection registry. */
data class BiosCandidate(
    val kind: BiosCandidateKind,
    val value: String,
    val required: Boolean,
    /**
     * ALTERNATIVES share a group: a group is satisfied when ANY member is present, and distinct groups
     * must EACH be satisfied.
     *
     * This exists because `scph5500/5501/5502` are the JP/US/EU REGION VARIANTS of one PS1 BIOS — a user
     * needs exactly one of them. Marking all three `required` with no grouping made the readiness engine
     * (which blocks when ANY required row is MISSING) report "needs BIOS" for a perfectly working PS1
     * setup. The legacy app avoided this by rolling presence up per platform (`anyTrue -> present`);
     * grouping IS that rollup, but precise enough to still express a genuine all-of — DS needs bios7 AND
     * bios9 AND firmware, so those stay in separate groups.
     *
     * Defaults to the candidate's own identity, i.e. its own group, i.e. all-of.
     */
    val group: String = "",
) {
    /** Stable per-file persistence/display identity for extension candidates. */
    val fileName: String
        get() = if (kind == BiosCandidateKind.EXTENSION) "*.$value" else value

    /** The alternative-set this candidate belongs to. */
    val groupKey: String
        get() = group.ifEmpty { fileName }
}

/** Candidate rows plus the destination-only folder hint used by the existing Settings projection. */
data class BiosPlatformCandidates(
    val platformId: PlatformId,
    val candidates: List<BiosCandidate>,
    val placementHint: String,
    /**
     * Friendly noun for surfaces that must NOT print match patterns. PS2 is identified by globs
     * (`scph*.bin`, any `.bin` in the right size band), and "Needs scph*.bin, *.bin" is developer-speak
     * leaking into user copy — the legacy UI filtered globs out for the same reason. Used only when a
     * platform has no literal filename to name.
     */
    val consoleBiosLabel: String? = null,
)

/**
 * Single source of truth for LG01 BIOS detection. These are names and size-checked patterns only:
 * Mimir never obtains, copies, moves, installs, or hashes a BIOS file.
 */
object BiosCandidateRegistry {
    // Alternative-set keys. Members of one key are interchangeable; separate keys must EACH be satisfied.
    private const val PS1_BIOS = "ps1-console-bios"
    private const val PS2_BIOS = "ps2-console-bios"
    private const val PS2_AUX = "ps2-aux-rom"
    private const val SATURN_BIOS = "saturn-console-bios"
    private const val DREAMCAST_BOOT = "dreamcast-boot"
    private const val DREAMCAST_AUX = "dreamcast-aux"
    private const val ARCADE_NEOGEO = "arcade-neogeo"
    private const val SEGACD_BIOS = "segacd-console-bios"
    private const val PCECD_BIOS = "pcenginecd-system-card"

    private fun filename(value: String, required: Boolean, group: String = "") =
        BiosCandidate(BiosCandidateKind.FILENAME, value, required, group)

    private fun extension(value: String, required: Boolean, group: String = "") =
        BiosCandidate(BiosCandidateKind.EXTENSION, value, required, group)

    val platforms: List<BiosPlatformCandidates> = listOf(
        BiosPlatformCandidates(
            platformId = PlatformId.PS1,
            candidates = listOf(
                // One group: 5500/5501/5502 are the JP/US/EU region variants and 1000/1001/psxonpsp660
                // are older/alternate dumps. ANY single one of them is a working PS1 BIOS.
                filename("scph5500.bin", true, PS1_BIOS),
                filename("scph5501.bin", true, PS1_BIOS),
                filename("scph5502.bin", true, PS1_BIOS),
                filename("scph1000.bin", false, PS1_BIOS),
                filename("scph1001.bin", false, PS1_BIOS),
                filename("psxonpsp660.bin", false, PS1_BIOS),
            ),
            placementHint = "your PS1 emulator's BIOS folder",
        ),
        BiosPlatformCandidates(
            platformId = PlatformId.PS2,
            candidates = listOf(
                // The two required patterns describe the SAME console BIOS image two ways.
                filename("scph*.bin", true, PS2_BIOS),
                extension("bin", true, PS2_BIOS),
                // Aux ROM images: optional, so they never gate a launch on their own.
                filename("rom1.bin", false, PS2_AUX),
                filename("rom2.bin", false, PS2_AUX),
                filename("erom.bin", false, PS2_AUX),
                extension("rom", false, PS2_AUX),
            ),
            placementHint = "your PS2 emulator's BIOS folder",
            consoleBiosLabel = "the PS2 console BIOS",
        ),
        BiosPlatformCandidates(
            platformId = PlatformId.DS,
            candidates = listOf(
                filename("bios7.bin", true),
                filename("bios9.bin", true),
                filename("firmware.bin", true),
            ),
            placementHint = "your DS emulator's BIOS folder",
        ),
        BiosPlatformCandidates(
            platformId = PlatformId.SATURN,
            candidates = listOf(
                // Alternatives: sega_101 / mpr-17933 / mpr-18811-mx are region dumps of the same BIOS.
                filename("saturn_bios.bin", true, SATURN_BIOS),
                filename("sega_101.bin", false, SATURN_BIOS),
                filename("mpr-17933.bin", false, SATURN_BIOS),
                filename("mpr-18811-mx.ic1", false, SATURN_BIOS),
            ),
            placementHint = "your Saturn emulator's BIOS folder",
        ),
        BiosPlatformCandidates(
            platformId = PlatformId.DREAMCAST,
            candidates = listOf(
                filename("dc_boot.bin", true, DREAMCAST_BOOT),
                filename("dc_flash.bin", false, DREAMCAST_AUX),
                filename("naomi.zip", false, DREAMCAST_AUX),
                filename("awbios.zip", false, DREAMCAST_AUX),
                filename("hod2bios.zip", false, DREAMCAST_AUX),
            ),
            placementHint = "your Dreamcast emulator's data folder",
        ),
        BiosPlatformCandidates(
            platformId = PlatformId.ARCADE,
            candidates = listOf(
                filename("neogeo.zip", true, ARCADE_NEOGEO),
                filename("neocdz.zip", false, ARCADE_NEOGEO),
                filename("qsound.zip", false),
                filename("pgm.zip", false),
                filename("awbios.zip", false),
                filename("naomi.zip", false),
                filename("hod2bios.zip", false),
                filename("konamigx.zip", false),
                filename("cvs.zip", false),
                filename("stvbios.zip", false),
                filename("taitogn.zip", false),
            ),
            placementHint = "your arcade emulator's ROM or BIOS folder",
        ),
        // LG01 coverage restore: the legacy DETECTION table only emitted 6 platformIds, but the Settings
        // requirement list carried 9. Projecting Settings from this registry therefore silently dropped
        // Sega CD / PC Engine CD / GBA / Lynx rows that users used to see. They are restored here — which
        // also extends real detection to them rather than leaving them guide-text-only as the legacy did.
        BiosPlatformCandidates(
            platformId = PlatformId("segacd"),
            candidates = listOf(
                // Region variants of one Sega CD BIOS — any single one works.
                filename("bios_CD_U.bin", true, SEGACD_BIOS),
                filename("bios_CD_E.bin", false, SEGACD_BIOS),
                filename("bios_CD_J.bin", false, SEGACD_BIOS),
            ),
            placementHint = "your Sega CD emulator's BIOS folder",
        ),
        BiosPlatformCandidates(
            platformId = PlatformId("pcenginecd"),
            candidates = listOf(
                // Successive System Card revisions; the newest a user owns satisfies the requirement.
                filename("syscard3.pce", true, PCECD_BIOS),
                filename("syscard2.pce", false, PCECD_BIOS),
                filename("syscard1.pce", false, PCECD_BIOS),
                filename("gexpress.pce", false, PCECD_BIOS),
            ),
            placementHint = "your PC Engine CD emulator's BIOS folder",
        ),
        BiosPlatformCandidates(
            platformId = PlatformId.GBA,
            // Optional: Android GBA cores run on a HLE BIOS, so this only improves accuracy.
            candidates = listOf(filename("gba_bios.bin", false)),
            placementHint = "your GBA emulator's BIOS folder",
        ),
        BiosPlatformCandidates(
            platformId = PlatformId("atarilynx"),
            candidates = listOf(filename("lynxboot.img", false)),
            placementHint = "your Lynx emulator's system folder",
        ),
    )

    private val byPlatform = platforms.associateBy { it.platformId }

    fun forPlatform(platformId: PlatformId): BiosPlatformCandidates? = byPlatform[platformId]
}
