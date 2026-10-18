package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.LaunchDispatchFailure
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchTarget
import com.gruning.mimir.rewrite.core.model.PlatformId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What one fixed, read-only look into a RetroArch build's `cores` folder proved.
 *
 * Android will not let Mimir read another app's private folder ([RetroArchCoreCatalog]), so the only way to know is
 * a privileged listing, and that route is often not there. [Unknown] is the answer to every way of not knowing --
 * no route, no Tweaker, a timeout, a reply that does not parse -- and it is never evidence that a core is absent.
 * Only [Known] can refuse a launch.
 */
sealed interface RetroArchCoreListing {
    /** The folder was listed and every entry was a plain libretro core file name. May be empty. */
    data class Known(val coreFiles: Set<String>) : RetroArchCoreListing

    /** Nothing can be said about the folder. */
    data object Unknown : RetroArchCoreListing

    companion object {
        /**
         * The last word of the privileged reply. The channel carries one line, so the command joins the folder's
         * entries into it and ends on this marker; a reply without the marker was cut short or never ran.
         */
        const val REPLY_END_MARKER = "__MIMIR_CORES_OK__"

        /** A RetroArch Android core: `<stem>_libretro_android.so`, plain characters only. */
        val CoreFileName = Regex("[A-Za-z0-9_.+-]+_libretro_android\\.so")

        /**
         * Reads the privileged reply strictly. Every token before [REPLY_END_MARKER] must be a [CoreFileName];
         * a stray token (an `ls:` complaint, a truncated name, a path) makes the whole reply [Unknown] instead of
         * being filtered out, so a half-read folder can never look like a folder that lacks a core. The marker with
         * nothing before it is a real answer: RetroArch is there and holds no cores.
         */
        fun parse(reply: String?): RetroArchCoreListing {
            val tokens = reply?.trim { it.isWhitespace() || it == '\u0000' }
                ?.split(Regex("\\s+"))
                ?.filter { it.isNotEmpty() }
                ?: return Unknown
            if (tokens.lastOrNull() != REPLY_END_MARKER) return Unknown
            val names = tokens.dropLast(1)
            if (!names.all { CoreFileName.matches(it) }) return Unknown
            return Known(names.toSet())
        }
    }
}

/** The verdict on one RetroArch launch, from the core file its dispatch profile names and a listing. */
internal sealed interface RetroArchCoreVerdict {
    /** Launch exactly as before: the core is there, or nothing proves it is not. */
    data object Proceed : RetroArchCoreVerdict

    /** The folder was listed and [coreFile] is not in it. */
    data class Missing(val coreFile: String) : RetroArchCoreVerdict
}

/** The pure decision: only a [RetroArchCoreListing.Known] listing that lacks [coreFile] is a refusal. */
internal fun decideRetroArchCore(coreFile: String, listing: RetroArchCoreListing): RetroArchCoreVerdict =
    when (listing) {
        RetroArchCoreListing.Unknown -> RetroArchCoreVerdict.Proceed
        is RetroArchCoreListing.Known ->
            if (coreFile in listing.coreFiles) {
                RetroArchCoreVerdict.Proceed
            } else {
                RetroArchCoreVerdict.Missing(coreFile)
            }
    }

private const val CORE_FILE_SUFFIX = "_libretro_android.so"

/**
 * The first core of [platformId]'s catalog, in catalog order, that [listing] proves is installed besides [coreFile]
 * (the one the game wanted). These are exactly the platform's Open With choices, so the player can switch to it.
 * Null when the listing holds none of them: the sentence then sends the player to RetroArch's downloader instead.
 */
internal fun installedCatalogAlternative(
    platformId: PlatformId,
    coreFile: String,
    listing: RetroArchCoreListing.Known,
): String? = RetroArchCoreCatalog.byPlatform[platformId]?.cores
    ?.firstOrNull { core ->
        val file = core.dispatchStem + CORE_FILE_SUFFIX
        file != coreFile && file in listing.coreFiles
    }
    ?.label

/**
 * The core one RetroArch launch will ask for, read back out of the exact `LIBRETRO` extra the dispatch profile
 * carries -- not re-derived -- so the check and the intent can never name different files.
 */
internal data class RetroArchCoreRequest(
    val userId: Int,
    val packageName: String,
    val coreFile: String,
    val label: String,
) {
    companion object {
        /** The RetroArch builds Mimir launches; the privileged listing admits exactly these. */
        val RetroArchPackages = setOf("com.retroarch", "com.retroarch.aarch64", "com.retroarch.ra32")

        private val LibretroPath = Regex(
            "/data/user/([0-9]{1,5})/([A-Za-z0-9._]+)/cores/" +
                "([A-Za-z0-9_.+-]+_libretro_android\\.so)",
        )

        /** Null for anything that is not a RetroArch core launch whose path has the shape the profile writes. */
        fun of(target: LaunchTarget): RetroArchCoreRequest? {
            val packageName = target.emulator.packageName
            if (packageName !in RetroArchPackages) return null
            val path = ShippingLaunchProfiles.profileFor(target)?.stringExtras?.get("LIBRETRO") ?: return null
            val match = LibretroPath.matchEntire(path) ?: return null
            if (match.groupValues[2] != packageName) return null
            val userId = match.groupValues[1].toIntOrNull() ?: return null
            val coreFile = match.groupValues[3]
            val stem = coreFile.removeSuffix(CORE_FILE_SUFFIX)
            return RetroArchCoreRequest(
                userId = userId,
                packageName = packageName,
                coreFile = coreFile,
                label = RetroArchCoreCatalog.displayName(target.platformId, stem) ?: stem,
            )
        }
    }
}

/**
 * Listings kept for [ttlMillis] per (user, package). A cached listing is trusted only toward launching -- see
 * [RetroArchCoreChecker] -- so a core the player has just downloaded is never held against them.
 */
internal class RetroArchCoreListingCache(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    private class Entry(val listing: RetroArchCoreListing, val storedAtMillis: Long)

    private val entries = HashMap<Pair<Int, String>, Entry>()

    @Synchronized
    fun get(userId: Int, packageName: String): RetroArchCoreListing? {
        val entry = entries[userId to packageName] ?: return null
        if (nowMillis() - entry.storedAtMillis >= ttlMillis) {
            entries.remove(userId to packageName)
            return null
        }
        return entry.listing
    }

    @Synchronized
    fun put(userId: Int, packageName: String, listing: RetroArchCoreListing) {
        entries[userId to packageName] = Entry(listing, nowMillis())
    }

    /** A package was replaced, so whatever was listed for it is no longer evidence. */
    @Synchronized
    fun invalidate(packageName: String) {
        entries.keys.removeAll { it.second == packageName }
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 60_000L
    }
}

/**
 * The pre-launch check. It refuses a launch only when a FRESH listing proves the profile's core file is absent;
 * every other branch -- no route, unknown, throws, present -- returns null and the launch goes ahead as before.
 *
 * A cache hit that would refuse is not believed: the folder is listed again first. The player's fix for a missing
 * core is to download it inside RetroArch and come back, which raises no package event, so a minute-old
 * "missing" must never survive that.
 */
internal class RetroArchCoreChecker(
    private val lister: (userId: Int, packageName: String) -> RetroArchCoreListing,
    private val cache: RetroArchCoreListingCache,
    private val log: (String) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun refusalFor(target: LaunchTarget): LaunchOutcome.Failed? {
        val request = RetroArchCoreRequest.of(target) ?: return null
        var listing = cache.get(request.userId, request.packageName)
        var verdict = listing?.let { decideRetroArchCore(request.coreFile, it) }
        if (verdict == null || verdict is RetroArchCoreVerdict.Missing) {
            listing = list(request)
            verdict = decideRetroArchCore(request.coreFile, listing)
        }
        val missing = (verdict as? RetroArchCoreVerdict.Missing)?.coreFile
        log(
            "pkg=${request.packageName} known=${listing is RetroArchCoreListing.Known} " +
                "count=${(listing as? RetroArchCoreListing.Known)?.coreFiles?.size ?: 0} " +
                "missing=${missing ?: "none"}",
        )
        if (missing == null) return null
        val installed = (listing as? RetroArchCoreListing.Known)
            ?.let { installedCatalogAlternative(target.platformId, missing, it) }
        return LaunchOutcome.Failed(
            reason = LaunchDispatchFailure.RETROARCH_CORE_MISSING,
            targetEmulatorId = target.emulator.id,
            nativeMetadata = buildMap {
                put(RetroArchCoreCheck.CORE_FILE_KEY, missing)
                put(RetroArchCoreCheck.CORE_LABEL_KEY, request.label)
                installed?.let { put(RetroArchCoreCheck.CORE_INSTALLED_LABEL_KEY, it) }
            },
        )
    }

    private suspend fun list(request: RetroArchCoreRequest): RetroArchCoreListing {
        val listing = withContext(dispatcher) {
            runCatching { lister(request.userId, request.packageName) }
                .getOrDefault(RetroArchCoreListing.Unknown)
        }
        cache.put(request.userId, request.packageName, listing)
        return listing
    }
}

/**
 * The process-wide check [LaunchController] consults before it dispatches a RetroArch core launch.
 *
 * The app installs [lister] (the privileged listing) and [logger]; with neither installed, which is every host and
 * unit context, nothing is checked and nothing is refused.
 */
object RetroArchCoreCheck {
    /** `LaunchOutcome.Failed.nativeMetadata` keys carried by a [LaunchDispatchFailure.RETROARCH_CORE_MISSING]. */
    const val CORE_LABEL_KEY = "coreLabel"
    const val CORE_FILE_KEY = "coreFile"

    /** Present only when the complete listing holds another core of the game's platform; see [installedCatalogAlternative]. */
    const val CORE_INSTALLED_LABEL_KEY = "installedCoreLabel"

    /** One fixed, read-only listing of a RetroArch build's cores folder; blocking, so it is called off-main. */
    @Volatile
    var lister: ((userId: Int, packageName: String) -> RetroArchCoreListing)? = null

    /** Receives `pkg=<pkg> known=<bool> count=<n> missing=<core|none>`; the app logs it as `MimirRetroArchCores`. */
    @Volatile
    var logger: (String) -> Unit = {}

    private val cache = RetroArchCoreListingCache()

    /** Forget what was listed for [packageName], e.g. when Android reports it was replaced. */
    fun invalidate(packageName: String) = cache.invalidate(packageName)

    internal suspend fun refusalFor(target: LaunchTarget): LaunchOutcome.Failed? {
        val listing = lister ?: return null
        return try {
            RetroArchCoreChecker(listing, cache, logger).refusalFor(target)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
    }
}
