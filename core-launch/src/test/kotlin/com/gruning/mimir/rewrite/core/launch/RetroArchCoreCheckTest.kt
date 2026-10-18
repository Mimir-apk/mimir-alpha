package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchTarget
import com.gruning.mimir.rewrite.core.model.PlatformId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * a RetroArch launch whose libretro core is not installed
 * shows a black screen Mimir cannot tell from a slow start. The privileged listing may refuse that launch, but only
 * when it positively proves the core file absent -- every other branch launches exactly as before.
 *
 * The expected replies, commands and sentences are written out here, not read from the constants.
 */
class RetroArchCoreCheckTest {
    private val marker = "__MIMIR_CORES_OK__"
    private val mgba = "mgba_libretro_android.so"
    private val gambatte = "gambatte_libretro_android.so"
    private val aarch64 = "com.retroarch.aarch64"
    private val seenUsers = mutableListOf<Int>()

    // --- Parsing: strict, and unparseable means unknown ---

    @Test
    fun `a normal listing is read into its core file names`() {
        assertEquals(
            RetroArchCoreListing.Known(setOf(gambatte, mgba)),
            RetroArchCoreListing.parse("$gambatte $mgba $marker"),
        )
        // The channel's trailing newline and NUL padding are not part of the answer.
        assertEquals(
            RetroArchCoreListing.Known(setOf(gambatte, mgba)),
            RetroArchCoreListing.parse("$gambatte $mgba $marker\n\u0000"),
        )
        // Every shape RetroArch's own downloader names a core by.
        val awkward = setOf(
            "mupen64plus_next_gles3_libretro_android.so",
            "genesis_plus_gx_wide_libretro_android.so",
            "mesen-s_libretro_android.so",
            "fbalpha2012_libretro_android.so",
            "vice_x64sc_libretro_android.so",
        )
        assertEquals(
            RetroArchCoreListing.Known(awkward),
            RetroArchCoreListing.parse(awkward.joinToString(" ") + " $marker"),
        )
    }

    @Test
    fun `the marker alone is a real answer - RetroArch is there and holds no cores`() {
        assertEquals(RetroArchCoreListing.Known(emptySet()), RetroArchCoreListing.parse(marker))
        assertEquals(RetroArchCoreListing.Known(emptySet()), RetroArchCoreListing.parse("$marker\n"))
    }

    @Test
    fun `nothing, blank and garbage replies are unknown`() {
        for (reply in listOf(null, "", "   ", "\n", "\u0000", "garbage", "OK", "__MIMIR_VENDOR_OK__")) {
            assertEquals("reply=$reply", RetroArchCoreListing.Unknown, RetroArchCoreListing.parse(reply))
        }
    }

    @Test
    fun `error output is unknown`() {
        val errors = listOf(
            "ls: /data/user/0/com.retroarch.aarch64/cores: No such file or directory",
            "ls: /data/user/0/com.retroarch.aarch64/cores: Permission denied",
            "/system/bin/sh: /system/bin/ls: not found",
            "sh: syntax error: unexpected 'fi'",
            "Permission denied $marker",
        )
        for (reply in errors) {
            assertEquals("reply=$reply", RetroArchCoreListing.Unknown, RetroArchCoreListing.parse(reply))
        }
    }

    @Test
    fun `a reply cut short is unknown even when every name in it is valid`() {
        assertEquals(RetroArchCoreListing.Unknown, RetroArchCoreListing.parse(gambatte))
        assertEquals(RetroArchCoreListing.Unknown, RetroArchCoreListing.parse("$gambatte $mgba"))
        assertEquals(RetroArchCoreListing.Unknown, RetroArchCoreListing.parse("$gambatte mgba_libretro_andr"))
        assertEquals(RetroArchCoreListing.Unknown, RetroArchCoreListing.parse("$gambatte __MIMIR_CORES"))
    }

    @Test
    fun `one token that is not a plain core file name makes the whole reply unknown`() {
        val stray = listOf(
            "../x_libretro_android.so",
            "a/b_libretro_android.so",
            "/data/x_libretro_android.so",
            "readme.txt",
            "x_libretro_android.so.bak",
            "a;b_libretro_android.so",
            "a\$b_libretro_android.so",
            "_libretro_android.so",
            "x_libretro.so",
            "X_LIBRETRO_ANDROID.SO",
        )
        for (token in stray) {
            assertEquals(
                "token=$token",
                RetroArchCoreListing.Unknown,
                RetroArchCoreListing.parse("$gambatte $token $marker"),
            )
        }
        // Not filtered out: a half-read folder must not look like a folder that lacks a core.
        assertEquals(RetroArchCoreListing.Unknown, RetroArchCoreListing.parse("$mgba readme.txt $marker"))
    }

    @Test
    fun `the marker must be last`() {
        assertEquals(RetroArchCoreListing.Unknown, RetroArchCoreListing.parse("$marker $mgba"))
        assertEquals(RetroArchCoreListing.Unknown, RetroArchCoreListing.parse("$gambatte $marker $mgba"))
    }

    @Test
    fun `duplicate names collapse`() {
        assertEquals(RetroArchCoreListing.Known(setOf(mgba)), RetroArchCoreListing.parse("$mgba $mgba $marker"))
    }

    // --- The decision: only a Known listing without the core refuses ---

    @Test
    fun `known and missing is the only refusal`() {
        assertEquals(
            RetroArchCoreVerdict.Missing(mgba),
            decideRetroArchCore(mgba, RetroArchCoreListing.Known(setOf(gambatte))),
        )
        assertEquals(
            RetroArchCoreVerdict.Missing(mgba),
            decideRetroArchCore(mgba, RetroArchCoreListing.Known(emptySet())),
        )
    }

    @Test
    fun `known and present launches`() {
        assertEquals(
            RetroArchCoreVerdict.Proceed,
            decideRetroArchCore(mgba, RetroArchCoreListing.Known(setOf(gambatte, mgba))),
        )
    }

    @Test
    fun `unknown launches`() {
        assertEquals(RetroArchCoreVerdict.Proceed, decideRetroArchCore(mgba, RetroArchCoreListing.Unknown))
    }

    // --- The request: read back out of the exact path the intent will carry ---

    @Test
    fun `the request names the core file the dispatch profile carries`() {
        val request = assertNotNull(RetroArchCoreRequest.of(target(aarch64, "mgba", PlatformId.GBA)))
        assertEquals(0, request.userId)
        assertEquals(aarch64, request.packageName)
        assertEquals(mgba, request.coreFile)
        assertEquals("mGBA", request.label)
    }

    @Test
    fun `every RetroArch build is rebased onto its own cores folder`() {
        val paths = RetroArchCoreRequest.RetroArchPackages.sorted().map { pkg ->
            val profile = ShippingLaunchProfiles.profileFor(target(pkg, "mgba", PlatformId.GBA))
            pkg to profile?.stringExtras?.get("LIBRETRO")
        }
        assertEquals(
            listOf(
                "com.retroarch" to "/data/user/0/com.retroarch/cores/mgba_libretro_android.so",
                "com.retroarch.aarch64" to "/data/user/0/com.retroarch.aarch64/cores/mgba_libretro_android.so",
                "com.retroarch.ra32" to "/data/user/0/com.retroarch.ra32/cores/mgba_libretro_android.so",
            ),
            paths,
        )
        for (pkg in RetroArchCoreRequest.RetroArchPackages) {
            val request = RetroArchCoreRequest.of(target(pkg, "mgba", PlatformId.GBA))
            assertEquals(pkg, request?.packageName)
            assertEquals(mgba, request?.coreFile)
        }
    }

    @Test
    fun `the allowlist is exactly the RetroArch packages the seed registry knows`() {
        assertEquals(
            EmulatorSeedRegistry.allSeedPackages().filter { it.startsWith("com.retroarch") }.toSet(),
            RetroArchCoreRequest.RetroArchPackages,
        )
    }

    @Test
    fun `a core the catalog does not name falls back to the file stem`() {
        val request = RetroArchCoreRequest.of(target(aarch64, "mystery_core", PlatformId.GBA))
        assertEquals("mystery_core", request?.label)
        assertEquals("mystery_core_libretro_android.so", request?.coreFile)
    }

    @Test
    fun `anything that is not a RetroArch core launch has no request`() {
        assertNull(RetroArchCoreRequest.of(target("org.dolphinemu.dolphinemu", null, PlatformId.GAMECUBE)))
        assertNull(RetroArchCoreRequest.of(target("com.retroarch.evil", "mgba", PlatformId.GBA)))
        // A RetroArch build with no core chosen and no legacy fallback for the platform has no core file to check.
        assertNull(RetroArchCoreRequest.of(target(aarch64, null, PlatformId.PS2)))
    }

    // --- The checker: block, launch, unknown, cache ---

    @Test
    fun `a known listing without the core refuses with the core named and logs it`() = runBlocking {
        val logs = mutableListOf<String>()
        val checker = checker(logs, listing = { RetroArchCoreListing.Known(setOf(gambatte)) })

        val refusal = checker.refusalFor(target(aarch64, "mgba", PlatformId.GBA))

        assertEquals(LaunchDispatchFailure.RETROARCH_CORE_MISSING, refusal?.reason)
        assertEquals(EmulatorId(aarch64), refusal?.targetEmulatorId)
        assertEquals(
            mapOf("coreFile" to mgba, "coreLabel" to "mGBA"),
            refusal?.nativeMetadata,
        )
        assertEquals(listOf("pkg=$aarch64 known=true count=1 missing=$mgba"), logs)
        assertEquals("the profile's own path names user 0", listOf(0), seenUsers)
    }

    @Test
    fun `a refusal names the first catalog core of the platform the complete listing holds`() = runBlocking {
        // GBA's catalog order is mGBA, gpSP, VBA Next, VBA-M ...; Gambatte is a Game Boy core, not a GBA choice.
        val checker = checker(
            mutableListOf(),
            listing = {
                RetroArchCoreListing.Known(
                    setOf(gambatte, "vbam_libretro_android.so", "gpsp_libretro_android.so"),
                )
            },
        )

        val refusal = checker.refusalFor(target(aarch64, "mgba", PlatformId.GBA))

        assertEquals(
            mapOf("coreFile" to mgba, "coreLabel" to "mGBA", "installedCoreLabel" to "gpSP"),
            refusal?.nativeMetadata,
        )
    }

    @Test
    fun `the alternative is the first in catalog order and never the core that was wanted`() {
        val n64 = PlatformId.N64
        val gles3 = "mupen64plus_next_gles3_libretro_android.so"
        val gles2 = "mupen64plus_next_gles2_libretro_android.so"
        val parallel = "parallel_n64_libretro_android.so"

        assertEquals(
            "Mupen64Plus-Next GLES2",
            installedCatalogAlternative(n64, gles3, RetroArchCoreListing.Known(setOf(parallel, gles2))),
        )
        assertEquals(
            "ParaLLEl N64",
            installedCatalogAlternative(n64, gles3, RetroArchCoreListing.Known(setOf(parallel))),
        )
        // The wanted core is excluded even if the listing somehow holds it.
        assertEquals(
            "ParaLLEl N64",
            installedCatalogAlternative(n64, gles2, RetroArchCoreListing.Known(setOf(gles2, parallel))),
        )
        // A core of another platform, or of no platform, is not a choice for this one.
        assertNull(installedCatalogAlternative(n64, gles3, RetroArchCoreListing.Known(setOf(mgba, gambatte))))
        assertNull(installedCatalogAlternative(n64, gles3, RetroArchCoreListing.Known(emptySet())))
        assertNull(installedCatalogAlternative(PlatformId("not_a_platform"), gles3, RetroArchCoreListing.Known(setOf(gles2))))
    }

    @Test
    fun `a complete listing with no other catalog core offers no alternative`() = runBlocking {
        val empty = checker(mutableListOf(), listing = { RetroArchCoreListing.Known(emptySet()) })
            .refusalFor(target(aarch64, "mgba", PlatformId.GBA))
        assertEquals(mapOf("coreFile" to mgba, "coreLabel" to "mGBA"), empty?.nativeMetadata)
    }

    /**
     * The Home launch card's status band is two lines at 9sp and ends in an ellipsis, and the sentence names these labels.
     * `HomeLaunchCardCoreMissingStatusFitTest` renders the longest of them; if the catalog gains a longer label, or a platform
     * gains a longer pair, this fails so that test's strings are re-measured instead of silently going stale.
     */
    @Test
    fun `the longest labels and the longest pair the status sentence can name are the Mupen64Plus-Next cores`() {
        val labels = RetroArchCoreCatalog.byPlatform.values.flatMap { platform -> platform.cores.map { it.label } }.distinct()
        val longest = labels.maxOf { it.length }
        assertEquals(22, longest)
        assertEquals(
            setOf("Mupen64Plus-Next GLES3", "Mupen64Plus-Next GLES2"),
            labels.filter { it.length == longest }.toSet(),
        )
        assertEquals(
            "the next-longest label",
            "Genesis Plus GX Wide",
            labels.filter { it.length < longest }.maxBy { it.length },
        )
        val worstPair = RetroArchCoreCatalog.byPlatform.values.maxOf { platform ->
            platform.cores.map { it.label.length }.sortedDescending().take(2).sum()
        }
        assertEquals(44, worstPair)
    }

    @Test
    fun `a known listing with the core launches`() = runBlocking {
        val logs = mutableListOf<String>()
        val checker = checker(logs, listing = { RetroArchCoreListing.Known(setOf(gambatte, mgba)) })

        assertNull(checker.refusalFor(target(aarch64, "mgba", PlatformId.GBA)))
        assertEquals(listOf("pkg=$aarch64 known=true count=2 missing=none"), logs)
    }

    @Test
    fun `an unknown listing launches`() = runBlocking {
        val logs = mutableListOf<String>()
        val checker = checker(logs, listing = { RetroArchCoreListing.Unknown })

        assertNull(checker.refusalFor(target(aarch64, "mgba", PlatformId.GBA)))
        assertEquals(listOf("pkg=$aarch64 known=false count=0 missing=none"), logs)
    }

    @Test
    fun `a lister that throws launches`() = runBlocking {
        val checker = checker(mutableListOf(), listing = { error("binder died") })

        assertNull(checker.refusalFor(target(aarch64, "mgba", PlatformId.GBA)))
    }

    @Test
    fun `a launch that is not a RetroArch core is never listed`() = runBlocking {
        var lists = 0
        val checker = checker(mutableListOf(), listing = { lists++; RetroArchCoreListing.Known(emptySet()) })

        assertNull(checker.refusalFor(target("org.dolphinemu.dolphinemu", null, PlatformId.GAMECUBE)))
        assertEquals(0, lists)
    }

    @Test
    fun `a listing is reused for sixty seconds and then read again`() = runBlocking {
        var now = 1_000L
        var lists = 0
        val checker = checker(mutableListOf(), clock = { now }, listing = {
            lists++
            RetroArchCoreListing.Known(setOf(mgba))
        })
        val gba = target(aarch64, "mgba", PlatformId.GBA)

        assertNull(checker.refusalFor(gba))
        now += 59_999L
        assertNull(checker.refusalFor(gba))
        assertEquals("inside the minute the cache answers", 1, lists)

        now += 1L
        assertNull(checker.refusalFor(gba))
        assertEquals("at sixty seconds the folder is read again", 2, lists)
    }

    @Test
    fun `the cache is per package`() = runBlocking {
        val asked = mutableListOf<String>()
        val checker = checker(mutableListOf(), listing = { pkg ->
            asked += pkg
            RetroArchCoreListing.Known(setOf(mgba))
        })

        assertNull(checker.refusalFor(target(aarch64, "mgba", PlatformId.GBA)))
        assertNull(checker.refusalFor(target("com.retroarch", "mgba", PlatformId.GBA)))
        assertNull(checker.refusalFor(target(aarch64, "mgba", PlatformId.GBA)))
        assertEquals(listOf(aarch64, "com.retroarch"), asked)
    }

    @Test
    fun `a replaced package is read again at once`() = runBlocking {
        var lists = 0
        val cache = RetroArchCoreListingCache()
        val checker = checker(mutableListOf(), cache = cache, listing = {
            lists++
            RetroArchCoreListing.Known(setOf(mgba))
        })
        val gba = target(aarch64, "mgba", PlatformId.GBA)

        assertNull(checker.refusalFor(gba))
        cache.invalidate("com.retroarch")
        assertNull(checker.refusalFor(gba))
        assertEquals("another package's replacement leaves this listing alone", 1, lists)

        cache.invalidate(aarch64)
        assertNull(checker.refusalFor(gba))
        assertEquals(2, lists)
    }

    @Test
    fun `a cached missing is never believed - a core downloaded since is launched`() = runBlocking {
        // The player's fix is to download the core inside RetroArch and come back. That raises no package event,
        // so a listing that said "missing" a few seconds ago must be read again before it can refuse anything.
        var coreInstalled = false
        var lists = 0
        val checker = checker(mutableListOf(), listing = {
            lists++
            RetroArchCoreListing.Known(if (coreInstalled) setOf(mgba) else emptySet())
        })
        val gba = target(aarch64, "mgba", PlatformId.GBA)

        assertNotNull(checker.refusalFor(gba))
        assertEquals(1, lists)

        coreInstalled = true
        assertNull("the next try, seconds later, launches", checker.refusalFor(gba))
        assertEquals(2, lists)

        // A cached "present" answer launches without asking, for the rest of the minute.
        coreInstalled = false
        assertNull("the cached present answer launches without asking", checker.refusalFor(gba))
        assertEquals(2, lists)
    }

    @Test
    fun `an unknown answer is cached too, so a missing route costs one try a minute`() = runBlocking {
        var lists = 0
        val checker = checker(mutableListOf(), listing = {
            lists++
            RetroArchCoreListing.Unknown
        })
        val gba = target(aarch64, "mgba", PlatformId.GBA)

        assertNull(checker.refusalFor(gba))
        assertNull(checker.refusalFor(gba))
        assertEquals(1, lists)
    }

    // --- The process-wide hook LaunchController consults ---

    @Test
    fun `with nothing installed the process-wide check refuses nothing`() = runBlocking {
        val previous = RetroArchCoreCheck.lister
        RetroArchCoreCheck.lister = null
        try {
            assertNull(RetroArchCoreCheck.refusalFor(target(aarch64, "mgba", PlatformId.GBA)))
        } finally {
            RetroArchCoreCheck.lister = previous
        }
    }

    @Test
    fun `the process-wide check contains a lister that throws`() = runBlocking {
        val previous = RetroArchCoreCheck.lister
        RetroArchCoreCheck.invalidate(aarch64)
        RetroArchCoreCheck.lister = { _, _ -> throw IllegalStateException("binder died") }
        try {
            assertNull(RetroArchCoreCheck.refusalFor(target(aarch64, "mgba", PlatformId.GBA)))
        } finally {
            RetroArchCoreCheck.lister = previous
            RetroArchCoreCheck.invalidate(aarch64)
        }
    }

    @Test
    fun `the allowlist matches what ShippingLaunchProfiles treats as RetroArch`() {
        // Every allowlisted package yields a LIBRETRO path under its own cores folder, and a package outside the list
        // yields none the check would act on.
        for (pkg in RetroArchCoreRequest.RetroArchPackages) {
            val path = ShippingLaunchProfiles.profileFor(target(pkg, "mgba", PlatformId.GBA))
                ?.stringExtras?.get("LIBRETRO")
            assertTrue("pkg=$pkg path=$path", path?.startsWith("/data/user/0/$pkg/cores/") == true)
        }
        assertFalse("com.retroarch.evil" in RetroArchCoreRequest.RetroArchPackages)
    }

    private fun checker(
        logs: MutableList<String>,
        clock: () -> Long = { 0L },
        cache: RetroArchCoreListingCache = RetroArchCoreListingCache(nowMillis = clock),
        listing: (String) -> RetroArchCoreListing,
    ) = RetroArchCoreChecker(
        lister = { userId, pkg ->
            seenUsers += userId
            listing(pkg)
        },
        cache = cache,
        log = { logs += it },
        dispatcher = Dispatchers.Unconfined,
    )

    private fun target(pkg: String, core: String?, platform: PlatformId) = LaunchTarget(
        gameId = GameId("game:${platform.value}:sample"),
        platformId = platform,
        emulator = EmulatorProfile(
            id = EmulatorId(pkg),
            packageName = pkg,
            displayName = pkg,
            retroArchCore = core,
            installed = true,
            capability = EmulatorCapability.DIRECT_PAYLOAD,
            locatorForm = LaunchLocatorForm.FILE_PATH,
            supportedPlatforms = setOf(platform),
        ),
        locator = "/storage/emulated/0/ROMs/${platform.value}/sample.bin",
        locatorForm = LaunchLocatorForm.FILE_PATH,
        title = "Sample",
    )

    private fun <T : Any> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }
}
