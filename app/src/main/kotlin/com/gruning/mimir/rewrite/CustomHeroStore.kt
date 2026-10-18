package com.gruning.mimir.rewrite

import android.content.Context
import android.net.Uri
import com.gruning.mimir.rewrite.core.launch.GameNativeLaunchRoute
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.posture.MimirSteamGridDbGate
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UXP-01 Home media choices — independent per-emulated-game static hero and preview clip overrides.
 *
 * Contract, straight from the brief:
 *  - **Explicit action only.** Nothing here is reachable from navigation, scan, or launch. The user has
 *    to open the matching Edit picker. There is deliberately no ambient/prefetch entry point.
 *  - **Local media only at render time.** The renderer never sees a remote URL; it reads the file this
 *    store downloaded. That is what keeps Bifröst's no-network-on-navigation contract intact.
 *  - **Keyed by durable [GameId]**, in prefs + a bounded file cache — no Room schema change.
 *  - **Atomic apply.** The override flips only after a valid local asset exists, so the old hero stays
 *    up if a download fails or half-writes.
 */
object CustomHeroStore {

    private const val PrefsName = "mimir_custom_hero"
    private const val HeroKeyPrefix = "hero/"
    private const val ClipKeyPrefix = "clip/"
    private const val HeroSourceKeyPrefix = "hero-source/"

    /** App-internal, sibling to the sgdb tile cache. Deleting it restores automatic heroes. */
    internal const val CacheDirName = "hero"

    /** A single chosen static hero asset is capped; a pathological remote file can't fill the device. */
    internal const val MaxAssetBytes: Long = 12L * 1024L * 1024L

    /** Selected Hero Motion originals (clip or animated WebP/GIF) may be larger than static heroes. */
    internal const val MaxMotionAssetBytes: Long = 75L * 1024L * 1024L

    /** Bounded picker posters and thumbnails stay small even when motion originals are large. */
    internal const val MaxPreviewBytes: Long = 2L * 1024L * 1024L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    internal fun cacheDir(context: Context): File =
        File(context.applicationContext.filesDir, CacheDirName)

    /** Deterministic per-game filename; the extension follows the media kind so decoders pick correctly. */
    internal fun assetFile(context: Context, gameId: GameId, video: Boolean): File =
        File(cacheDir(context), heroFileName(gameId, video))

    internal fun heroFileName(gameId: GameId, video: Boolean): String =
        gameId.value.encodeToByteArray().joinToString("") { "%02x".format(it) } +
            if (video) ".mp4" else ".img"

    /**
     * The chosen hero for [gameId] as a local render URI, or null when the game is on Automatic.
     *
     * Returns null (not a stale path) when the recorded asset has vanished, so a user who cleared app
     * storage silently falls back to the automatic hero instead of rendering a broken source.
     */
    fun overrideUri(context: Context, gameId: GameId): String? {
        when (val projection = EnrichSavedStore.rendererReadProjection(EnrichMediaKind.HERO, gameId.value)) {
            EnrichRendererReadProjection.Automatic -> return null
            is EnrichRendererReadProjection.Override -> {
                val file = projection.file
                return file.takeIf { it.isFile && it.length() > 0L }
                    ?.let { android.net.Uri.fromFile(it).toString() + "#mv=" + it.lastModified() }
            }
            null -> Unit
        }
        if (GameIdentityMediaSelectionStore.state(
                context,
                EnrichMediaKind.HERO,
                gameId.value,
            ) == GameIdentityMediaSelection.AUTOMATIC
        ) {
            return null
        }
        val record = prefs(context).getString(HeroKeyPrefix + gameId.value, null) ?: return null
        // Before Pass 2, clips and still heroes shared this key. A legacy video record is now owned by
        // [previewClipUri], never misreported as a static Custom Hero.
        if (record != StaticMarker) return null
        val file = assetFile(context, gameId, video = false)
        if (!file.isFile || file.length() <= 0L) return null
        // Cache-bust on mtime the same way the tile decorator does, so re-picking the same slot
        // repaints instead of serving Coil's cached bitmap for the old bytes.
        return android.net.Uri.fromFile(file).toString() + "#mv=" + file.lastModified()
    }

    /** True when [gameId] carries an explicit override — drives the "Automatic / Use default" affordance. */
    fun hasOverride(context: Context, gameId: GameId): Boolean = overrideUri(context, gameId) != null

    internal fun hasCanonicalOverride(context: Context, gameId: GameId): Boolean =
        prefs(context).getString(HeroKeyPrefix + gameId.value, null) == StaticMarker &&
            assetFile(context, gameId, video = false).let { it.isFile && it.length() > 0L }

    /** Independent chosen clip, with a read-through for pre-Pass-2 video records. */
    fun previewClipUri(context: Context, gameId: GameId): String? {
        when (val projection = EnrichSavedStore.rendererReadProjection(EnrichMediaKind.CLIP, gameId.value)) {
            EnrichRendererReadProjection.Automatic -> return null
            is EnrichRendererReadProjection.Override -> {
                val file = projection.file
                return file.takeIf { it.isFile && it.length() > 0L }
                    ?.let { android.net.Uri.fromFile(it).toString() + "#mv=" + it.lastModified() }
            }
            null -> Unit
        }
        if (GameIdentityMediaSelectionStore.state(
                context,
                EnrichMediaKind.CLIP,
                gameId.value,
            ) == GameIdentityMediaSelection.AUTOMATIC
        ) {
            return null
        }
        val preferences = prefs(context)
        val explicit = preferences.getString(ClipKeyPrefix + gameId.value, null) == VideoMarker
        val legacy = preferences.getString(HeroKeyPrefix + gameId.value, null) == VideoMarker
        if (!explicit && !legacy) return null
        val file = assetFile(context, gameId, video = true)
        if (!file.isFile || file.length() <= 0L) return null
        return android.net.Uri.fromFile(file).toString() + "#mv=" + file.lastModified()
    }

    fun hasPreviewClipOverride(context: Context, gameId: GameId): Boolean =
        previewClipUri(context, gameId) != null

    internal fun hasCanonicalPreviewClipOverride(context: Context, gameId: GameId): Boolean {
        val preferences = prefs(context)
        val selected = preferences.getString(ClipKeyPrefix + gameId.value, null) == VideoMarker ||
            preferences.getString(HeroKeyPrefix + gameId.value, null) == VideoMarker
        return selected && assetFile(context, gameId, video = true).let { it.isFile && it.length() > 0L }
    }

    /**
     * Records an already-downloaded, already-validated asset as [gameId]'s hero.
     *
     * Separate from the download on purpose: the caller writes to a temp file first and only calls this
     * once the bytes are known good, which is what makes apply atomic from the UI's point of view.
     */
    internal fun commitHero(context: Context, gameId: GameId, uploaded: Boolean = false): Boolean {
        val preferences = prefs(context)
        val key = HeroKeyPrefix + gameId.value
        val edit = preferences.edit()
        // Preserve a legacy chosen clip before the hero key becomes static.
        if (preferences.getString(key, null) == VideoMarker) {
            edit.putString(ClipKeyPrefix + gameId.value, VideoMarker)
        }
        return edit
            .putString(key, StaticMarker)
            .putString(HeroSourceKeyPrefix + gameId.value, if (uploaded) UploadMarker else ProviderMarker)
            .commit()
    }

    internal fun uploadedHeroActive(context: Context, gameId: GameId): Boolean =
        prefs(context).getString(HeroSourceKeyPrefix + gameId.value, null) == UploadMarker

    /**
     * EP-04 identity replay. The replayer has already compared generation timestamps and passes only a
     * winning source transition. Source files and markers remain as alias history; the target renderer
     * may be replaced because a newer source choice (including Automatic) must survive A -> B -> A.
     *
     * Caller must hold [GameIdentityMediaMutationGate].
     */
    internal fun remapGameId(
        context: Context,
        fromGameId: GameId,
        toGameId: GameId,
        heroTransition: GameIdentityMediaSelection? = null,
        clipTransition: GameIdentityMediaSelection? = null,
    ): Boolean {
        if (fromGameId == toGameId) return true
        val preferences = prefs(context)

        fun selectedSource(kind: EnrichMediaKind, video: Boolean): Pair<File, EnrichSavedSource?>? {
            val active = assetFile(context, fromGameId, video)
            val activeMaxBytes = if (video) MaxMotionAssetBytes else MaxAssetBytes
            if (active.isFile && active.length() in 1..activeMaxBytes) return active to null
            val catalog = EnrichSavedStore.load(context, kind, fromGameId.value)
            val saved = catalog.entries.firstOrNull { it.assetId == catalog.activeAssetId }
                ?: return null
            val file = File(saved.localPath)
            val savedMaxBytes = saved.mediaType?.let(::customHeroMaxAssetBytes)
                ?: if (video) MaxMotionAssetBytes else MaxAssetBytes
            return file.takeIf { it.isFile && it.length() in 1..savedMaxBytes }
                ?.let { it to saved.source }
        }

        fun installSelectedHero(): Boolean {
            val (source, savedSource) = selectedSource(EnrichMediaKind.HERO, video = false)
                ?: return false
            val target = assetFile(context, toGameId, video = false)
            val staged = stageHeroIdentityAsset(source, target, MaxAssetBytes) ?: return false
            val sourceOrigin = when {
                savedSource == EnrichSavedSource.UPLOAD -> UploadMarker
                savedSource != null -> ProviderMarker
                else -> preferences.getString(HeroSourceKeyPrefix + fromGameId.value, null)
                    ?: ProviderMarker
            }
            return installHeroAsset(
                temp = staged,
                target = target,
                validate = { file -> CustomHeroFetcher.isDecodableSavedMedia(file, video = false) },
                commit = {
                    val targetHeroKey = HeroKeyPrefix + toGameId.value
                    val editor = preferences.edit()
                    // Preserve a legacy clip marker before assigning the still-hero slot.
                    if (preferences.getString(targetHeroKey, null) == VideoMarker) {
                        editor.putString(ClipKeyPrefix + toGameId.value, VideoMarker)
                    }
                    editor
                        .putString(targetHeroKey, StaticMarker)
                        .putString(HeroSourceKeyPrefix + toGameId.value, sourceOrigin)
                        .commit()
                },
            ) == HeroInstallOutcome.INSTALLED
        }

        fun installSelectedClip(): Boolean {
            val (source, _) = selectedSource(EnrichMediaKind.CLIP, video = true) ?: return false
            val target = assetFile(context, toGameId, video = true)
            val catalog = EnrichSavedStore.load(context, EnrichMediaKind.CLIP, fromGameId.value)
            val saved = catalog.entries.firstOrNull { it.assetId == catalog.activeAssetId }
            val mediaType = saved?.mediaType ?: CustomHeroFetcher.detectSavedMediaType(source)
            val clipMaxBytes = customHeroMaxAssetBytes(mediaType)
            val staged = stageHeroIdentityAsset(source, target, clipMaxBytes) ?: return false
            return installHeroAsset(
                temp = staged,
                target = target,
                validate = { file -> CustomHeroFetcher.isDecodableSavedMedia(file, mediaType) },
                commit = {
                    val targetHeroKey = HeroKeyPrefix + toGameId.value
                    val editor = preferences.edit()
                        .putString(ClipKeyPrefix + toGameId.value, VideoMarker)
                    if (preferences.getString(targetHeroKey, null) == VideoMarker) {
                        editor.remove(targetHeroKey)
                    }
                    editor.commit()
                },
            ) == HeroInstallOutcome.INSTALLED
        }

        fun clearHeroRenderer(): Boolean {
            val targetHeroKey = HeroKeyPrefix + toGameId.value
            val editor = preferences.edit().remove(HeroSourceKeyPrefix + toGameId.value)
            if (preferences.getString(targetHeroKey, null) == StaticMarker) editor.remove(targetHeroKey)
            if (!editor.commit()) return false
            val target = assetFile(context, toGameId, video = false)
            return !target.exists() || target.delete()
        }

        fun clearClipRenderer(): Boolean {
            val targetHeroKey = HeroKeyPrefix + toGameId.value
            val editor = preferences.edit().remove(ClipKeyPrefix + toGameId.value)
            if (preferences.getString(targetHeroKey, null) == VideoMarker) editor.remove(targetHeroKey)
            if (!editor.commit()) return false
            val target = assetFile(context, toGameId, video = true)
            return !target.exists() || target.delete()
        }

        val heroApplied = when (heroTransition) {
            GameIdentityMediaSelection.SELECTED -> installSelectedHero()
            GameIdentityMediaSelection.AUTOMATIC -> clearHeroRenderer()
            GameIdentityMediaSelection.NEVER_CHOSEN,
            null,
            -> true
        }
        if (!heroApplied) return false
        return when (clipTransition) {
            GameIdentityMediaSelection.SELECTED -> installSelectedClip()
            GameIdentityMediaSelection.AUTOMATIC -> clearClipRenderer()
            GameIdentityMediaSelection.NEVER_CHOSEN,
            null,
            -> true
        }
    }

    internal fun commitPreviewClip(context: Context, gameId: GameId): Boolean {
        val preferences = prefs(context)
        val heroKey = HeroKeyPrefix + gameId.value
        val edit = preferences.edit().putString(ClipKeyPrefix + gameId.value, VideoMarker)
        // Migrate the old shared video marker. A real static marker, if present, remains independent.
        if (preferences.getString(heroKey, null) == VideoMarker) edit.remove(heroKey)
        return edit.commit()
    }

    /** Promotes one already-validated Saved asset without any provider/network work. */
    internal fun activateLocalAsset(
        context: Context,
        gameId: GameId,
        source: File,
        video: Boolean,
        uploaded: Boolean,
        mediaType: CustomHeroMediaType = if (video) {
            CustomHeroMediaType.VIDEO
        } else {
            CustomHeroMediaType.STATIC
        },
    ): Boolean {
        if (!source.isFile || source.length() !in 1..customHeroMaxAssetBytes(mediaType)) return false
        val target = assetFile(context, gameId, video)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".saved.part")
        temp.delete()
        val copied = copySyncedHeroFile(source, temp)
        if (!copied) {
            temp.delete()
            return false
        }
        return installHeroAsset(
            temp = temp,
            target = target,
            validate = { file -> CustomHeroFetcher.isDecodableSavedMedia(file, mediaType) },
            commit = {
                if (video) commitPreviewClip(context, gameId) else commitHero(context, gameId, uploaded)
            },
        ) == HeroInstallOutcome.INSTALLED
    }

    /**
     * "Automatic / Use default": drops the override and its own asset.
     *
     * Deletes only this game's hero file. Cover art, tile icons, and the resolver's sibling preview
     * videos live elsewhere and are explicitly not touched — the brief calls that out.
     */
    fun clear(context: Context, gameId: GameId): Boolean {
        val preferences = prefs(context)
        val key = HeroKeyPrefix + gameId.value
        val had = preferences.getString(key, null) == StaticMarker
        if (!had) return false
        // The preference is the authority. Keep the working files when its durable removal fails;
        // deleting them first would turn a transient storage error into a lost user choice.
        if (!preferences.edit().remove(key).remove(HeroSourceKeyPrefix + gameId.value).commit()) return false
        assetFile(context, gameId, video = false).delete()
        return true
    }

    suspend fun clearAsAutomatic(context: Context, gameId: GameId): Boolean =
        withContext(Dispatchers.IO) {
            GameIdentityMediaMutationGate.run(context, blocked = false) {
                val currentGameId = GameIdentityAliasResolver.resolve(context, gameId)
                val priorSelection = GameIdentityMediaSelectionStore.record(
                    context,
                    EnrichMediaKind.HERO,
                    currentGameId.value,
                )
                if (!GameIdentityMediaSelectionStore.set(
                        context,
                        EnrichMediaKind.HERO,
                        currentGameId.value,
                        GameIdentityMediaSelection.AUTOMATIC,
                    )
                ) {
                    return@run false
                }
                val file = assetFile(context, currentGameId, video = false)
                val cleared = if (hasOverride(context, currentGameId)) {
                    clear(context, currentGameId)
                } else {
                    !file.exists() || file.delete()
                }
                if (!cleared) {
                    GameIdentityMediaSelectionStore.write(
                        context,
                        EnrichMediaKind.HERO,
                        currentGameId.value,
                        priorSelection,
                    )
                }
                cleared
            }
        }

    /** Reset only the user's clip choice; a separately chosen still hero is untouched. */
    fun clearPreviewClip(context: Context, gameId: GameId): Boolean {
        val preferences = prefs(context)
        val clipKey = ClipKeyPrefix + gameId.value
        val heroKey = HeroKeyPrefix + gameId.value
        val hadExplicit = preferences.contains(clipKey)
        val hadLegacy = preferences.getString(heroKey, null) == VideoMarker
        if (!hadExplicit && !hadLegacy) return false
        val edit = preferences.edit().remove(clipKey)
        if (hadLegacy) edit.remove(heroKey)
        if (!edit.commit()) return false
        assetFile(context, gameId, video = true).delete()
        return true
    }

    suspend fun clearPreviewClipAsAutomatic(context: Context, gameId: GameId): Boolean =
        withContext(Dispatchers.IO) {
            GameIdentityMediaMutationGate.run(context, blocked = false) {
                val currentGameId = GameIdentityAliasResolver.resolve(context, gameId)
                val priorSelection = GameIdentityMediaSelectionStore.record(
                    context,
                    EnrichMediaKind.CLIP,
                    currentGameId.value,
                )
                if (!GameIdentityMediaSelectionStore.set(
                        context,
                        EnrichMediaKind.CLIP,
                        currentGameId.value,
                        GameIdentityMediaSelection.AUTOMATIC,
                    )
                ) {
                    return@run false
                }
                val file = assetFile(context, currentGameId, video = true)
                val cleared = if (hasPreviewClipOverride(context, currentGameId)) {
                    clearPreviewClip(context, currentGameId)
                } else {
                    !file.exists() || file.delete()
                }
                if (!cleared) {
                    GameIdentityMediaSelectionStore.write(
                        context,
                        EnrichMediaKind.CLIP,
                        currentGameId.value,
                        priorSelection,
                    )
                }
                cleared
            }
        }

    /**
     * Drops only unreferenced scratch/orphan files. A committed hero is USER STATE, not a cache entry:
     * selecting a 25th game's hero must never silently erase the first game's choice while leaving its
     * preference behind.
     *
     * Scoped strictly to this store's own directory, so it can never reach a cover, an icon, or a
     * resolver-owned preview clip.
     */
    internal fun sweep(context: Context) {
        val active = prefs(context).all.mapNotNullTo(linkedSetOf()) { (key, value) ->
            val identity = when {
                key.startsWith(HeroKeyPrefix) -> key.removePrefix(HeroKeyPrefix)
                key.startsWith(ClipKeyPrefix) -> key.removePrefix(ClipKeyPrefix)
                else -> return@mapNotNullTo null
            }.takeIf(String::isNotBlank) ?: return@mapNotNullTo null
            val video = when {
                key.startsWith(ClipKeyPrefix) && value == VideoMarker -> true
                key.startsWith(HeroKeyPrefix) && value == VideoMarker -> true // legacy clip
                key.startsWith(HeroKeyPrefix) && value == StaticMarker -> false
                else -> return@mapNotNullTo null
            }
            runCatching { heroFileName(GameId(identity), video) }.getOrNull()
        }
        sweepHeroDirectory(cacheDir(context), active)
    }

    private const val StaticMarker = "static"
    private const val VideoMarker = "video"
    private const val UploadMarker = "upload"
    private const val ProviderMarker = "provider"
}

private fun stageHeroIdentityAsset(source: File, target: File, maxBytes: Long): File? {
    if (!source.isFile || source.length() !in 1..maxBytes) return null
    target.parentFile?.mkdirs()
    val staged = File(target.parentFile, target.name + ".identity.part")
    staged.delete()
    return runCatching {
        if (!copySyncedHeroFile(source, staged) ||
            staged.length() !in 1..maxBytes
        ) {
            staged.delete()
            null
        } else {
            staged.setLastModified(source.lastModified())
            staged
        }
    }.getOrElse {
        staged.delete()
        null
    }
}

/**
 * Where a hero candidate came from. Shown to the user, so [label] stays human-readable.
 *
 * [allowedHostSuffix] is a per-provider download allowlist — defence in depth on top of the HTTPS check.
 * A candidate URL is provider-attributed data, and a download is the one place a hostile or mangled
 * response could point the client at an arbitrary host, so each source may only ever be fetched from
 * its own domain.
 */
internal enum class CustomHeroSource(val label: String, val allowedHostSuffix: String) {
    STEAMGRIDDB("SteamGridDB", "steamgriddb.com"),
    SCREENSCRAPER("ScreenScraper", "screenscraper.fr"),
}

internal fun EnrichSavedSource.toCustomHeroSource(): CustomHeroSource? = when (this) {
    EnrichSavedSource.STEAMGRIDDB -> CustomHeroSource.STEAMGRIDDB
    EnrichSavedSource.SCREENSCRAPER -> CustomHeroSource.SCREENSCRAPER
    EnrichSavedSource.UPLOAD,
    EnrichSavedSource.LEGACY,
    -> null
}

/** True when [url] is HTTPS and its host belongs to [source]'s own domain. */
internal fun customHeroUrlAllowed(source: CustomHeroSource, url: String): Boolean {
    val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
    if (!parsed.scheme.equals("https", ignoreCase = true) ||
        parsed.rawUserInfo != null ||
        parsed.port !in setOf(-1, 443) ||
        parsed.rawFragment != null
    ) {
        return false
    }
    val host = parsed.host?.lowercase() ?: return false
    return host == source.allowedHostSuffix || host.endsWith("." + source.allowedHostSuffix)
}

/** What the candidate actually is. Static renders as a still backdrop; video reuses the settled engine. */
internal enum class CustomHeroMediaType(val label: String) {
    STATIC("Image"),
    VIDEO("Video"),
    ANIMATED_IMAGE("Motion art"),
}

/**
 * One merged candidate with its provider and media type attached, exactly as the brief requires.
 *
 * [remoteUrl] is never rendered and never logged — ScreenScraper media URLs carry devid/devpassword and
 * ssid/sspassword query parameters, so the URL is credential-bearing by construction. Only [previewPath]
 * (a local file) reaches the UI. See [customHeroSafeLogLabel] for the one shape allowed near a log.
 *
 * [thumbUrl] is the provider's own small preview when it publishes one (SteamGridDB does). Static pickers
 * download that thumbnail. ScreenScraper has no clip thumbnail, so the explicit clip picker downloads at
 * most four bounded clips into transient preview storage, derives a small [posterPath] from each, and plays
 * only the focused clip. The poster is deliberately separate from [previewPath]: Coil renders the former in
 * the search strip while the settled single video writer consumes the latter behind the glass.
 */
internal data class CustomHeroCandidate(
    val source: CustomHeroSource,
    val mediaType: CustomHeroMediaType,
    val remoteUrl: String,
    val thumbUrl: String? = null,
    val previewPath: String? = null,
    val posterPath: String? = null,
    /** One-based position within this provider/type group, for honest no-thumbnail fallback tiles. */
    val ordinal: Int = 1,
    /** Provider-validated alias that may strengthen the explicit ScreenScraper enrichment ladder. */
    val resolvedTitle: String? = null,
    /** Non-secret provider identity when exposed by the API. Never a ScreenScraper signed URL. */
    val providerAssetId: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val durationMillis: Long? = null,
    /** Provider media vocabulary such as fanart, video-normalized, or video. */
    val mediaSubtype: String? = null,
    val region: String? = null,
) {
    /**
     * "SteamGridDB · Image 2" — distinct even when several candidates have no thumbnail.
     *
     * Box candidates name themselves instead ("ScreenScraper · Box texture 4"). That is not
     * decoration: box art reaches the Hero through the player's explicit picker choice,
     * and a candidate that introduces itself as a generic "Image" is exactly the
     * unlabelled substitution the dup-cover ban exists to prevent. Every other candidate is
     * byte-identical to before.
     */
    val displayLabel: String
        get() = buildString {
            resolvedTitle?.takeIf(String::isNotBlank)?.let { append("$it · ") }
            append("${source.label} · ${boxArtLabel ?: mediaType.label} $ordinal")
        }

    /** The honest name for a box-family candidate, or null when this is not one. */
    internal val boxArtLabel: String?
        get() = when (mediaSubtype) {
            "box-texture" -> "Box texture"
            "box-3d" -> "Box (3D)"
            "box-2d" -> "Box (2D)"
            // Extended for the Cover picker (R5-3, 2026-08-25) rather than starting a second labelling
            // scheme beside this one. A back or a scan is a real face of the article, and labelling it as
            // one is exactly what makes offering it legitimate.
            "box-2d-back" -> "Box (back)"
            "box-scan" -> "Box scan"
            // The physical article, labelled as itself. A picker candidate is legitimate because he SEES
            // what it is and chooses it -- so a disc must never arrive wearing a box's name.
            "support-2d" -> "Disc / cart"
            "support-3d" -> "Disc / cart (3D)"
            "support-label" -> "Disc label"
            "support-texture" -> "Disc texture"
            else -> null
        }

    /** A still image that is safe to hand to the picker image renderer; never returns a video path. */
    val pickerArtPath: String?
        get() = posterPath ?: previewPath.takeIf { mediaType != CustomHeroMediaType.VIDEO }

    val stableId: String
        get() = providerAssetId?.let { "${source.name.lowercase()}-$it" }
            ?: "${source.name.lowercase()}-${mediaType.name.lowercase()}-$ordinal"

    /** Data-class defaults would expose [remoteUrl], including ScreenScraper credentials, to logs. */
    override fun toString(): String =
        "CustomHeroCandidate(source=${source.name}, mediaType=${mediaType.name}, ordinal=$ordinal, " +
            "hasThumb=${thumbUrl != null}, hasPreview=${previewPath != null}, hasPoster=${posterPath != null})"
}

/**
 * The only string derived from a candidate that may go near a log: provider and type, never the URL and
 * never its query string. Credential-bearing URLs must not reach logcat, which on a shared device is a
 * real exfiltration path.
 */
internal fun customHeroSafeLogLabel(candidate: CustomHeroCandidate): String =
    "${candidate.source.name}/${candidate.mediaType.name}"

/** One byte ceiling per installed custom-hero payload; motion originals may exceed static heroes. */
internal fun customHeroMaxAssetBytes(mediaType: CustomHeroMediaType): Long = when (mediaType) {
    CustomHeroMediaType.ANIMATED_IMAGE,
    CustomHeroMediaType.VIDEO,
    -> CustomHeroStore.MaxMotionAssetBytes
    CustomHeroMediaType.STATIC -> CustomHeroStore.MaxAssetBytes
}

/**
 * The outcome of installing a downloaded hero asset. Every ordinary non-[INSTALLED] value leaves the
 * old hero up; [ROLLBACK_FAILED] preserves its synced `.bak` for explicit recovery.
 */
internal enum class HeroInstallOutcome {
    INSTALLED,
    INVALID_MEDIA,
    MOVE_FAILED,
    PERSIST_FAILED,
    /** Preference rollback failed; the synced prior asset remains in `.bak` for explicit recovery. */
    ROLLBACK_FAILED,
}

/**
 * Installs a downloaded hero with an atomic live-file replacement and rollback.
 *
 * The three failure modes this exists to prevent, all found in review of the first draft:
 *  1. Moving the existing file aside before promotion creates a process-death window with no live hero.
 *     The previous file is copied to a synced rollback sibling, then the validated candidate atomically
 *     replaces the still-present target.
 *  2. A static hero and preview clip are independent user state; installing either must retain its sibling.
 *  3. Non-empty bytes were treated as a valid asset, so an HTTP-200 error page could be committed as a
 *     hero. [validate] must prove the file actually decodes.
 *
 * Pure enough to unit-test: [validate], [move], and [backupCopy] are injected, so every branch is
 * exercisable on the JVM with plain files.
 */
internal fun installHeroAsset(
    temp: File,
    target: File,
    validate: (File) -> Boolean,
    commit: () -> Boolean,
    move: (File, File) -> Boolean = ::atomicReplaceHeroFile,
    backupCopy: (File, File) -> Boolean = ::copySyncedHeroFile,
): HeroInstallOutcome {
    if (!validate(temp)) {
        temp.delete()
        return HeroInstallOutcome.INVALID_MEDIA
    }
    val backup = File(target.parentFile, target.name + ".bak")
    val hadExisting = target.isFile
    if (hadExisting) {
        backup.delete()
        if (!backupCopy(target, backup)) {
            temp.delete()
            return HeroInstallOutcome.MOVE_FAILED
        }
    }
    if (!move(temp, target)) {
        // Atomic promotion failure leaves the still-present previous target untouched.
        backup.delete()
        temp.delete()
        return HeroInstallOutcome.MOVE_FAILED
    }
    // The override flips only now, with a validated asset already in place. An ordinary durable-preference
    // failure rolls the file swap back too. Cross-store process death remains a separate proof boundary.
    if (!commit()) {
        if (hadExisting) {
            // Restore the synced rollback copy without creating an absent-target window.
            if (!move(backup, target)) return HeroInstallOutcome.ROLLBACK_FAILED
        } else {
            if (!target.delete() && target.exists()) return HeroInstallOutcome.ROLLBACK_FAILED
        }
        return HeroInstallOutcome.PERSIST_FAILED
    }
    backup.delete()
    // The .img and .mp4 are independent user choices now: applying one must never erase the other.
    return HeroInstallOutcome.INSTALLED
}

private fun atomicReplaceHeroFile(from: File, to: File): Boolean = runCatching {
    Files.move(
        from.toPath(),
        to.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
    true
}.getOrDefault(false)

internal fun copySyncedHeroFile(from: File, to: File): Boolean {
    if (!from.isFile) return false
    to.parentFile?.mkdirs()
    to.delete()
    val copied = runCatching {
        val expectedBytes = from.length()
        FileOutputStream(to).use { output ->
            from.inputStream().use { input -> input.copyTo(output) }
            output.fd.sync()
        }
        to.isFile && to.length() == expectedBytes
    }.getOrDefault(false)
    if (!copied) to.delete()
    return copied
}

/** Why a provider produced nothing — so the UI can say which connection or key is missing. */
internal enum class CustomHeroUnavailableReason(val message: String) {
    NEEDS_STEAMGRIDDB_KEY(
        "Add a SteamGridDB key: Settings › Appearance › Enrichment & artwork.",
    ),
    NEEDS_SCREENSCRAPER_ACCOUNT(
        "Connect ScreenScraper: Settings › Appearance › Enrichment & artwork.",
    ),
    NEEDS_BOTH(
        "Connect artwork: Settings › Appearance › Enrichment & artwork.",
    ),
}

/**
 * The result of an explicit Custom Hero request.
 *
 * [Loaded] can carry an empty list with a non-null [Loaded.note]: both providers were reachable but
 * neither had anything for this title. That is a different state from "you are not connected", and the
 * brief requires the UI to distinguish them rather than showing a dead blank strip.
 */
internal sealed interface CustomHeroResult {
    data class Loaded(
        val candidates: List<CustomHeroCandidate>,
        val note: String? = null,
    ) : CustomHeroResult

    data class Unavailable(val reason: CustomHeroUnavailableReason) : CustomHeroResult
}

internal fun customHeroProviderFailureNote(
    steamGridDb: CustomHeroProviderResult?,
    screenScraper: CustomHeroProviderResult?,
): String? = when {
    steamGridDb is CustomHeroProviderResult.NeedsAccess ->
        CustomHeroUnavailableReason.NEEDS_STEAMGRIDDB_KEY.message
    steamGridDb is CustomHeroProviderResult.Unreachable &&
        screenScraper is CustomHeroProviderResult.Unreachable ->
        "Couldn’t reach artwork services. Saved choices are still available."
    steamGridDb is CustomHeroProviderResult.ProviderFailed &&
        screenScraper is CustomHeroProviderResult.ProviderFailed ->
        "Artwork services couldn’t complete this request. Saved choices are still available."
    steamGridDb is CustomHeroProviderResult.Unreachable -> "Couldn’t reach SteamGridDB."
    steamGridDb is CustomHeroProviderResult.ProviderFailed -> "SteamGridDB couldn’t complete this request."
    screenScraper is CustomHeroProviderResult.Unreachable -> "Couldn’t reach ScreenScraper."
    screenScraper is CustomHeroProviderResult.ProviderFailed ->
        "ScreenScraper couldn’t complete this request."
    else -> null
}

/**
 * Merges the two providers into one ordered candidate list.
 *
 * Order is deliberate: SteamGridDB static heroes first (purpose-made key art), then ScreenScraper
 * fanart, then screenshots, then video. Both lists are already bounded by their fetchers; this caps the
 * merged total so the picker strip stays walkable on a handheld.
 */
internal fun mergeCustomHeroCandidates(
    steamGridDb: List<CustomHeroCandidate>,
    screenScraper: List<CustomHeroCandidate>,
    cap: Int = CustomHeroMaxCandidates,
): List<CustomHeroCandidate> = (steamGridDb + screenScraper)
    .distinctBy { it.remoteUrl }
    .take(cap)

internal const val CustomHeroMaxCandidates = 12
internal const val CustomHeroManualMaxCandidates = 24

/**
 * Decides the honest unavailable reason from the two connection states. Only reached when BOTH
 * providers are unusable — if either is connected the picker shows candidates returned by the available provider.
 */
internal fun customHeroUnavailableReason(
    hasSteamGridDbKey: Boolean,
    hasScreenScraperAccount: Boolean,
): CustomHeroUnavailableReason = when {
    !hasSteamGridDbKey && !hasScreenScraperAccount -> CustomHeroUnavailableReason.NEEDS_BOTH
    !hasSteamGridDbKey -> CustomHeroUnavailableReason.NEEDS_STEAMGRIDDB_KEY
    else -> CustomHeroUnavailableReason.NEEDS_SCREENSCRAPER_ACCOUNT
}

/**
 * ScreenScraper media types that make a usable hero, in preference order.
 *
 * `fanart` is the purpose-built backdrop; `sstitle`/`ss` are screenshots (a decent fallback); the two
 * video spellings feed the existing power-aware video engine. Box/label/support art is deliberately
 * excluded — it is cover art, and a cover behind a cover is the dup-cover ban.
 *
 * Scoped to THIS list only. The separate fallback policy adds `box-texture` as a fallback to the separate physical-media
 * ("Customize Media" cartridge/disc) picker — see [CustomPhysicalScreenScraperTypes] below, plus
 * `ScreenScraperMetadataSource.PHYSICAL_MEDIA_TYPES` and `ArtMediaResolver.PHYSICAL_MEDIA_TYPE_PRIORITY`.
 * That picker's slot is a disc/cartridge render, not a backdrop, so it is not the cover-behind-cover case
 * this ban exists for.
 *
 * The explicit picker also admits box textures and 3D/2D covers, presented as labelled choices.
 * The three box types are admitted HERE, ranked last, and nowhere else. The sentence this paragraph
 * replaces ("no box/label/support type is added here") described HEAD before that ruling and is
 * corrected rather than left standing beside the change that overturned it.
 *
 * **The dup-cover ban is not retired -- it is scoped to where it was always aimed: SILENT
 * substitution.** `ArtMediaResolver.HERO_TYPE_PRIORITY`, the AUTOMATIC enrichment ranking, still
 * carries only fanart/sstitle/ss, so Mimir never installs a cover behind a cover on its own. This
 * list feeds the explicit Customize Media picker, where the candidate is labelled (see
 * [CustomHeroCandidate.displayLabel]) and the user chooses it, matching the explicit
 * physical-media picker.
 *
 * `box-texture` leads the three: it is the flattened wrapper, the widest of them and the only one
 * shaped anything like a backdrop. `box-3D`/`box-2D` are portrait covers and rank behind it.
 */
internal val CustomHeroScreenScraperTypes: List<Pair<String, CustomHeroMediaType>> = listOf(
    "fanart" to CustomHeroMediaType.STATIC,
    "sstitle" to CustomHeroMediaType.STATIC,
    "ss" to CustomHeroMediaType.STATIC,
    "video" to CustomHeroMediaType.VIDEO,
    "video-normalized" to CustomHeroMediaType.VIDEO,
    // Policy 2026-08-23, picker-only. Lowercase keys: screenScraperHeroCandidates looks up
    // `type.lowercase()`, so a mixed-case entry here would simply never match a provider media.
    "box-texture" to CustomHeroMediaType.STATIC,
    "box-3d" to CustomHeroMediaType.STATIC,
    "box-2d" to CustomHeroMediaType.STATIC,
)

/**
 * R-23/R-25 : the app id from a parsed PC-games shortcut,
 * ONLY when the shortcut is the one extension with an exact identity — `.steam`. The other four
 * (`.epic`/`.gog`/`.amazon`/`.pcgame`) parse to a real [GameNativeLaunchRoute.ParseResult.Payload] too
 * (they're all valid GameNative sources) but identify by filename/title instead, per R-25 — deliberately
 * NOT by treating their id the same way, since only Steam's id space is what SteamGridDB is keyed on.
 * Pure and independent of I/O so it is unit-testable without a device or a real shortcut file.
 */
internal fun steamAppIdFromShortcutParse(result: GameNativeLaunchRoute.ParseResult): Int? =
    (result as? GameNativeLaunchRoute.ParseResult.Payload)
        ?.takeIf { it.gameSource == "STEAM" }
        ?.appId

/**
 * R-23 : the Steam app id embedded in a `.steam` shortcut's
 * body, for a game whose managed path is [managedPath]. Reuses `core-launch`'s already-tested
 * [GameNativeLaunchRoute.parse] end to end (extension recognition, size cap, BOM/whitespace trim, and
 * the positive-int check) — the same parser R-2b's launch route already trusts, never a second reader.
 * Null for every non-`.steam` extension (R-25: those identify by filename/title instead), a null/blank
 * [managedPath] (a SAF-only entry has none), and whenever the shortcut can't be read
 * (missing/oversized/malformed). The one seam both [pcGameSteamAppId] (has a [GameRow][com.gruning.mimir.rewrite.core.model.library.GameRow],
 * needs a DAO hop) and `ArtworkFetchController`'s Enrich route (already holds a `GameEntity` with the
 * path in hand) resolve an app id through — do not fork a second reader.
 */
internal fun steamAppIdForManagedPath(managedPath: String?): Int? {
    val path = managedPath?.takeIf(String::isNotBlank) ?: return null
    return steamAppIdFromShortcutParse(GameNativeLaunchRoute.parse(File(path)))
}

/**
 * R6-P1-04: the exact Steam app id from a `.steam` shortcut's body, reached through the SAF grant when
 * [managedPath] alone can't resolve one -- a SAF-only row with no managed path, the ENRICH route's own
 * gap (the LAUNCH route already derives a plain path for this same row kind, `RoomLaunchProviders.kt`'s
 * `managed ?: externalStoragePath`). Tries [steamAppIdForManagedPath] first (R-23: an exact key beats a
 * name guess, and this keeps the existing fast path for a managed row exactly as it was). Reads at most
 * [GameNativeLaunchRoute.MAX_SHORTCUT_BYTES] + 1 bytes through [sourceDocumentUri] -- the same oversize
 * bound [GameNativeLaunchRoute.parse] itself reads under -- and parses through
 * [GameNativeLaunchRoute.parseShortcut], the same seam [steamAppIdForManagedPath] uses, never a second
 * reader. Any failure (no `content://` locator, a blank [displayName], an unreadable stream, an
 * unrecognized extension, or a malformed body) resolves to null, same as [steamAppIdForManagedPath] failing
 * does today -> the caller's existing NameSearch fallback. Enrich-press-only; never called from scan or
 * launch.
 */
internal suspend fun steamAppIdForShortcut(
    context: Context,
    managedPath: String?,
    sourceDocumentUri: String?,
    displayName: String?,
): Int? = withContext(Dispatchers.IO) {
    // The whole body -- including steamAppIdForManagedPath's own File I/O -- runs under this dispatcher
    // now, so this function never depends on a caller already being on IO to stay off the main thread.
    steamAppIdForManagedPath(managedPath)?.let { return@withContext it }
    val uriString = sourceDocumentUri?.takeIf { it.startsWith("content://") } ?: return@withContext null
    val extension = displayName?.let(::File)?.extension ?: return@withContext null
    try {
        context.contentResolver.openInputStream(Uri.parse(uriString))?.use { stream ->
            steamAppIdFromShortcutParse(
                GameNativeLaunchRoute.parseShortcut(
                    extension,
                    stream.readAtMost((GameNativeLaunchRoute.MAX_SHORTCUT_BYTES + 1).toInt()),
                ),
            )
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }
}

/** Reads at most [limit] bytes from [this] stream -- never blocks trying to fill more than that. */
private fun InputStream.readAtMost(limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var total = 0
    while (total < limit) {
        val read = read(buffer, total, limit - total)
        if (read < 0) break
        total += read
    }
    return buffer.copyOf(total)
}

/**
 * R-23 : the Steam app id embedded in a `.steam` shortcut's
 * body, for [game] when it is a pc_games title. Null whenever the platform isn't pc_games at all, or
 * [steamAppIdForShortcut] can't resolve one (no managed path or readable SAF body, unrecognized extension,
 * or an unreadable/malformed shortcut). R6-P1-04: reads the primary member's three locator fields through
 * [membersOfGame] instead of the bare path [managedPathForGame] returned, so a SAF-only row can resolve
 * through the same shortcut-body seam the Enrich route itself uses.
 */
internal suspend fun pcGameSteamAppId(
    context: Context,
    game: com.gruning.mimir.rewrite.core.model.library.GameRow,
): Int? {
    if (game.platformId != PlatformId.PC_GAMES) return null
    return try {
        withContext(Dispatchers.IO) {
            val member = com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory.get(context)
                .gameDao()
                .membersOfGame(game.gameId.value)
                .firstOrNull()
            steamAppIdForShortcut(context, member?.managedPath, member?.sourceDocumentUri, member?.displayName)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }
}

/**
 * Builds the picker phase for one explicit Custom Hero action against the EXACT emulated row.
 *
 * Merge policy: whichever provider answers contributes. Only when BOTH are
 * unusable does the user see an unavailable explanation naming the missing key/account; when both are
 * connected but neither had anything, that is a different, honestly-worded state — never a dead blank
 * strip. "Automatic / Use default" always leads; reopen focus follows the active source.
 *
 * R-24 : SteamGridDB owns pc_games outright — ScreenScraper
 * matches ROM files by hash/name and structurally cannot identify a GameNative shortcut, so it is never
 * queried for this platform regardless of connection state, and pc_games' own SteamGridDB lookup uses
 * the exact Steam app id (R-23) in place of a title guess whenever a `.steam` shortcut supplied one.
 */
internal suspend fun loadCustomHeroPhase(
    context: Context,
    game: com.gruning.mimir.rewrite.core.model.library.GameRow,
    steamGridDbKey: String,
    screenScraperConnected: Boolean,
    candidateNames: List<String> = listOf(game.title),
): HomeTileMenuPhase {
    val isPcGames = game.platformId == PlatformId.PC_GAMES
    val screenScraperEligible = screenScraperConnected && !isPcGames
    val steamAppId = if (isPcGames) pcGameSteamAppId(context, game) else null
    val gateClosedWithKey = steamGridDbKey.isNotBlank() && !MimirSteamGridDbGate.isOpen
    val hasKey = steamGridDbKey.isNotBlank() && MimirSteamGridDbGate.isOpen
    val localChoices = buildList {
        add(HomeTileHeroChoice.UseDefault)
        CustomImageImportStore.retainedHeroPath(context, game.gameId)?.let {
            add(HomeTileHeroChoice.UploadedImage(it))
        }
        add(HomeTileHeroChoice.ImportImage)
    }
    if (!hasKey && !screenScraperEligible) {
        return HomeTileMenuPhase.HeroPick(
            choices = localChoices,
            focusedIndex = localHeroFocusedIndex(context, game.gameId, localChoices, providerStart = null),
            note = if (gateClosedWithKey || isPcGames) {
                // pc_games: naming ScreenScraper here (as customHeroUnavailableReason's NEEDS_BOTH /
                // NEEDS_SCREENSCRAPER_ACCOUNT wording would) would blame a provider that was never going
                // to help this platform — SteamGridDB is the only one it ever reaches, so a missing key
                // is the whole story regardless of ScreenScraper's own connection state.
                CustomHeroUnavailableReason.NEEDS_STEAMGRIDDB_KEY.message
            } else {
                customHeroUnavailableReason(steamGridDbKey.isNotBlank(), screenScraperConnected).message
            },
        )
    }
    val sgdbResult = when {
        !steamGridDbKey.isBlank() && !MimirSteamGridDbGate.isOpen -> CustomHeroProviderResult.NeedsAccess
        hasKey && steamAppId != null ->
            CustomHeroFetcher.steamGridDbHeroesForSteamAppId(steamGridDbKey, steamAppId)
        hasKey -> CustomHeroFetcher.steamGridDbHeroes(steamGridDbKey, candidateNames)
        else -> null
    }
    val screenScraperResult = if (screenScraperEligible) {
        CustomHeroFetcher.screenScraperHeroes(game, videoAllowed = false, candidateNames = candidateNames)
    } else {
        null
    }
    val fromSgdb = (sgdbResult as? CustomHeroProviderResult.Loaded)?.candidates.orEmpty()
    val fromScreenScraper = (screenScraperResult as? CustomHeroProviderResult.Loaded)?.candidates.orEmpty()
    val merged = mergeCustomHeroCandidates(fromSgdb, fromScreenScraper)
    val providerFailureNote = customHeroProviderFailureNote(sgdbResult, screenScraperResult)
    if (merged.isEmpty()) {
        val note = providerFailureNote ?: "No hero art found for this game."
        return HomeTileMenuPhase.HeroPick(
            localChoices,
            focusedIndex = localHeroFocusedIndex(context, game.gameId, localChoices, providerStart = null),
            note = note,
        )
    }
    val withPreviews = CustomHeroFetcher.withLocalPreviews(context, merged)
    val choices = buildList {
        addAll(localChoices)
        withPreviews.forEach { add(HomeTileHeroChoice.Hero(it)) }
    }
    // Only worth surfacing when one side was connected and silent — otherwise it is just noise.
    return HomeTileMenuPhase.HeroPick(
        choices,
        focusedIndex = localHeroFocusedIndex(
            context,
            game.gameId,
            choices,
            providerStart = localChoices.size,
        ),
        note = providerFailureNote,
    )
}

private fun localHeroFocusedIndex(
    context: Context,
    gameId: GameId,
    choices: List<HomeTileHeroChoice>,
    providerStart: Int?,
): Int = when {
    CustomHeroStore.uploadedHeroActive(context, gameId) ->
        choices.indexOfFirst { it is HomeTileHeroChoice.UploadedImage }.coerceAtLeast(0)
    CustomHeroStore.hasOverride(context, gameId) && providerStart != null -> providerStart
    else -> 0
}

/** Builds the independent ScreenScraper clip picker for an explicit Edit action. */
internal suspend fun loadCustomPreviewClipPhase(
    context: Context,
    game: com.gruning.mimir.rewrite.core.model.library.GameRow,
    screenScraperConnected: Boolean,
    candidateNames: List<String> = listOf(game.title),
): HomeTileMenuPhase {
    if (!screenScraperConnected) {
        return HomeTileMenuPhase.ProviderUnavailable(
            "Connect ScreenScraper: Settings › Appearance › Enrichment & artwork.",
        )
    }
    val result = CustomHeroFetcher.screenScraperHeroes(
        game,
        videoAllowed = true,
        candidateNames = candidateNames,
    )
    val clips = (result as? CustomHeroProviderResult.Loaded)?.candidates.orEmpty()
        .filter { it.mediaType == CustomHeroMediaType.VIDEO }
        // A clip preview is the media itself (ScreenScraper publishes no thumb). Keep this explicit
        // picker cheap and walkable rather than downloading the generic 12-item hero cap.
        .take(CustomPreviewClipMaxCandidates)
    val hasOverride = CustomHeroStore.hasPreviewClipOverride(context, game.gameId)
    if (clips.isEmpty()) {
        val note = when (result) {
            CustomHeroProviderResult.Unreachable ->
                "Couldn’t reach ScreenScraper. Saved Hero Motion is still available."
            CustomHeroProviderResult.ProviderFailed ->
                "ScreenScraper couldn’t complete this request. Saved Hero Motion is still available."
            else -> "No Hero Motion found for this game."
        }
        if ((result is CustomHeroProviderResult.Unreachable || result is CustomHeroProviderResult.ProviderFailed) &&
            !hasOverride
        ) {
            return HomeTileMenuPhase.ProviderUnavailable(note)
        }
        val choices = if (hasOverride) listOf(HomeTileClipChoice.UseDefault) else emptyList()
        return HomeTileMenuPhase.ClipPick(choices, note = note)
    }
    val withPreviews = CustomHeroFetcher.withLocalPreviews(context, clips)
    val choices = buildList {
        if (hasOverride) add(HomeTileClipChoice.UseDefault)
        withPreviews.forEach { add(HomeTileClipChoice.Clip(it)) }
    }
    val note = if (withPreviews.any { it.previewPath == null }) {
        "Preview unavailable. You can still use this Hero Motion."
    } else {
        null
    }
    return HomeTileMenuPhase.ClipPick(choices, note = note)
}

internal const val CustomPreviewClipMaxCandidates = 4

/**
 * Filters and orders a ScreenScraper `jeu.medias[]` list into hero candidates.
 *
 * [videoAllowed] mirrors the existing independent preview-video opt-in: with it off the user gets
 * static candidates only, matching how covers and clips are already gated separately.
 */
/**
 * One provider media that survived the hero filter, with its rank and its REAL provider type.
 *
 * A named carrier rather than a Triple purely so the type string cannot be dropped again: the field
 * it feeds ([CustomHeroCandidate.mediaSubtype]) spent its whole life reporting "fanart" for every
 * static candidate because the old tuple did not carry the type at all.
 */
private data class RankedHeroMedia(
    val rank: Int,
    val mediaType: CustomHeroMediaType,
    val url: String,
    val subtype: String,
)

internal fun screenScraperHeroCandidates(
    medias: List<Pair<String, String>>,
    videoAllowed: Boolean,
    cap: Int = CustomHeroMaxCandidates,
): List<CustomHeroCandidate> {
    val ranked = CustomHeroScreenScraperTypes.withIndex().associate { (index, pair) ->
        pair.first to (index to pair.second)
    }
    return medias.mapNotNull { (type, url) ->
        val normalizedType = type.lowercase()
        val entry = ranked[normalizedType] ?: return@mapNotNull null
        if (entry.second == CustomHeroMediaType.VIDEO && !videoAllowed) return@mapNotNull null
        if (!customHeroUrlAllowed(CustomHeroSource.SCREENSCRAPER, url)) return@mapNotNull null
        RankedHeroMedia(entry.first, entry.second, url, normalizedType)
    }.sortedBy { it.rank }
        .mapIndexed { index, (_, mediaType, url, subtype) ->
            CustomHeroCandidate(
                source = CustomHeroSource.SCREENSCRAPER,
                mediaType = mediaType,
                remoteUrl = url,
                // ScreenScraper does not publish a separate thumb. A bounded fetch of the static media
                // itself is the preview; video stays a labelled fallback tile and is downloaded only on apply.
                thumbUrl = url.takeIf { mediaType == CustomHeroMediaType.STATIC },
                ordinal = index + 1,
                // Was `CustomHeroScreenScraperTypes.firstOrNull { it.second == mediaType }?.first` -- a
                // lookup by media KIND, which returned "fanart" for every STATIC candidate and "video"
                // for every VIDEO one, whatever the provider actually sent. Harmless while the only
                // reader was a `startsWith("support-")` test that no hero type could satisfy; load-bearing
                // the moment the 2026-08-23 box types arrived, because the picker label reads this field
                // and a box texture would have introduced itself as fan art.
                mediaSubtype = subtype,
            )
        }
        .distinctBy { it.remoteUrl }
        .take(cap)
}

/**
 * Ranks candidates for the explicit "Customize Media" physical-media picker.
 *
 * Separate from `ScreenScraperMetadataSource.PHYSICAL_MEDIA_TYPES`, which only gates the raw provider
 * fetch — this list decides what actually survives to become a picker candidate: a type absent here is
 * dropped by [screenScraperPhysicalMediaCandidates] even if the fetch admitted it.
 *
 * `box-texture` fallback,  Ground truth: ScreenScraper returned 21 media types and zero
 * `support-*` entries for Resident Evil 5 (Xbox 360) in Customize Media, but did carry `box-texture` — the
 * empty result was correct (no disc/cartridge scan exists for that title), so it is ranked LAST, after
 * every real `support-*` type, and only ever shown when none of them are present.
 */
private val CustomPhysicalScreenScraperTypes = listOf(
    "support-2d",
    "support-3d",
    "support-label",
    "support-texture",
    "box-texture",
)

/**
 * Ranks candidates for the explicit COVER picker (policy 2026-08-25, R5).
 *
 * The order is explicit, and each entry self-labels in the picker via
 * [CustomHeroCandidate.boxArtLabel]:
 *
 *  - `box-2d` — the front, and the automatic winner. Present so *"put it back the way it was"* stays
 *    reachable after a pick.
 *  - `box-3d` — **the reason this picker had to exist.** Every title the provider carries also publishes
 *    `box-2D`, and the automatic ladder is `box-2D → box-3D → box-texture`, so a 3D box is unreachable
 *    automatically wherever both box-2D and box-3D are available.
 *  - `box-texture` — the flattened wrapper; already the automatic last resort.
 *  - `box-2d-back` — a real face of the article, labelled as the back and chosen deliberately.
 *  - `box-scan` — a real scan of the physical box.
 *
 * `box-2D-side` is deliberately ABSENT. Not a claim problem — a proportion one: a spine is a long thin
 * strip and cannot fill a portrait cover lane without being stretched or letterboxed into nonsense.
 * Excluded on geometry, and named here so it does not later read as an oversight.
 */
private val CustomCoverScreenScraperTypes = listOf(
    "box-3d",
    "box-2d",
    "box-texture",
    "box-2d-back",
    "box-scan",
    // The physical article, after the boxes. N-3: this picker is the only route to either vocabulary
    // since the face rows replaced the two media rows, so withholding these would remove his ability to
    // choose cartridge art rather than tidy the list.
    "support-2d",
    "support-3d",
    "support-label",
    "support-texture",
)

internal const val CustomCoverMaxCandidates = 40

/** Cover candidates for the explicit picker, ranked by [CustomCoverScreenScraperTypes] then provider order. */
internal fun screenScraperCoverCandidates(
    medias: List<Pair<String, String>>,
    cap: Int = CustomCoverMaxCandidates,
): List<CustomHeroCandidate> {
    val rank = CustomCoverScreenScraperTypes.withIndex().associate { (index, type) -> type to index }
    return medias.mapIndexedNotNull { sourceIndex, (type, url) ->
        val normalizedType = type.lowercase()
        val typeRank = rank[normalizedType] ?: return@mapIndexedNotNull null
        if (!customHeroUrlAllowed(CustomHeroSource.SCREENSCRAPER, url)) return@mapIndexedNotNull null
        Triple(typeRank, sourceIndex, normalizedType to url)
    }.sortedWith(compareBy<Triple<Int, Int, Pair<String, String>>>({ it.first }, { it.second }))
        .distinctBy { it.third.second }
        .take(cap)
        .mapIndexed { index, (_, _, media) ->
            CustomHeroCandidate(
                source = CustomHeroSource.SCREENSCRAPER,
                mediaType = CustomHeroMediaType.STATIC,
                remoteUrl = media.second,
                thumbUrl = media.second,
                ordinal = index + 1,
                providerAssetId = "${media.first}-${index + 1}",
                mediaSubtype = media.first,
            )
        }
}

internal const val CustomPhysicalMediaMaxCandidates = 8

/** ScreenScraper support media is an Icon presentation choice, never a Hero or Library metadata write. */
internal fun screenScraperPhysicalMediaCandidates(
    medias: List<Pair<String, String>>,
    cap: Int = CustomPhysicalMediaMaxCandidates,
): List<CustomHeroCandidate> {
    val rank = CustomPhysicalScreenScraperTypes.withIndex().associate { (index, type) -> type to index }
    return medias.mapIndexedNotNull { sourceIndex, (type, url) ->
        val normalizedType = type.lowercase()
        val typeRank = rank[normalizedType] ?: return@mapIndexedNotNull null
        if (!customHeroUrlAllowed(CustomHeroSource.SCREENSCRAPER, url)) return@mapIndexedNotNull null
        Triple(typeRank, sourceIndex, normalizedType to url)
    }.sortedWith(compareBy<Triple<Int, Int, Pair<String, String>>>({ it.first }, { it.second }))
        .distinctBy { it.third.second }
        .take(cap)
        .mapIndexed { index, (_, _, media) ->
            CustomHeroCandidate(
                source = CustomHeroSource.SCREENSCRAPER,
                mediaType = CustomHeroMediaType.STATIC,
                remoteUrl = media.second,
                thumbUrl = media.second,
                ordinal = index + 1,
                providerAssetId = "${media.first}-${index + 1}",
                mediaSubtype = media.first,
            )
        }
}

/**
 * A COVER picker tile (policy 2026-08-25, R5).
 *
 * Separate from [asPhysicalIconCandidate] because that one labels the SUPPORT vocabulary and answers
 * "Physical media" for anything it does not recognise — which is every box type. Routing cover candidates
 * through it captioned real box art as physical media on device: wrong, and long enough to wrap mid-word
 * in the portrait tile.
 *
 * The caption is the type ALONE — "Box (3D)", not "ScreenScraper · Box (3D)". R5-2 asks that a candidate
 * be labelled for what it actually IS, and on a tile this narrow the provider prefix costs the part that
 * carries that meaning. The provider is already named by the picker's own chrome.
 *
 * `normalizeSquare = false`, unlike the physical tile: a cover is portrait, and squaring it would letterbox
 * every candidate into the shape this picker exists to let him judge.
 */
internal fun CustomHeroCandidate.asCoverCandidate(): SteamGridDbTileFetcher.Candidate =
    SteamGridDbTileFetcher.Candidate(
        url = remoteUrl,
        thumbUrl = thumbUrl ?: remoteUrl,
        previewPath = pickerArtPath,
        normalizeSquare = false,
        providerAssetId = providerAssetId,
        width = width,
        height = height,
        assetKind = SteamGridDbAssetKind.ICON,
        providerLabel = boxArtLabel ?: "Box art",
        providerMediaType = mediaSubtype,
        savedSource = EnrichSavedSource.SCREENSCRAPER,
    )

internal fun CustomHeroCandidate.asPhysicalIconCandidate(): SteamGridDbTileFetcher.Candidate =
    SteamGridDbTileFetcher.Candidate(
        url = remoteUrl,
        thumbUrl = thumbUrl ?: remoteUrl,
        previewPath = pickerArtPath,
        normalizeSquare = true,
        providerAssetId = providerAssetId,
        width = width,
        height = height,
        assetKind = SteamGridDbAssetKind.ICON,
        providerLabel = "ScreenScraper · " + when (mediaSubtype) {
            "support-2d" -> "Physical front"
            "support-3d" -> "Physical case"
            "support-label" -> "Media label"
            "support-texture" -> "Media texture"
            // Fallback type, labelled
            // distinctly so the picker is honest that this is box art standing in, not a real disc scan.
            "box-texture" -> "Box texture (fallback)"
            else -> "Physical media"
        },
        savedSource = EnrichSavedSource.SCREENSCRAPER,
    )

/**
 * Preview mode filters to clips BEFORE applying its cap. The previous shared ranking admitted fanart
 * and screenshots first, so a media-rich verified game could report zero clips even when video entries
 * were present later in the provider list.
 */
internal fun screenScraperPreviewClipCandidates(
    medias: List<Pair<String, String>>,
    cap: Int = CustomPreviewClipMaxCandidates,
): List<CustomHeroCandidate> {
    val videoRanks = CustomHeroScreenScraperTypes
        .filter { it.second == CustomHeroMediaType.VIDEO }
        .mapIndexed { index, pair -> pair.first to index }
        .toMap()
    val posterUrl = medias.mapNotNull { (type, url) ->
        val rank = CustomHeroScreenScraperTypes.indexOfFirst { candidate ->
            candidate.first == type.lowercase() && candidate.second == CustomHeroMediaType.STATIC
        }
        if (rank < 0 || !customHeroUrlAllowed(CustomHeroSource.SCREENSCRAPER, url)) null else rank to url
    }.minByOrNull { it.first }?.second
    return medias.mapNotNull { (type, url) ->
        val rank = videoRanks[type.lowercase()] ?: return@mapNotNull null
        if (!customHeroUrlAllowed(CustomHeroSource.SCREENSCRAPER, url)) return@mapNotNull null
        Triple(rank, type.lowercase(), url)
    }.sortedBy { it.first }
        .distinctBy { it.third }
        .take(cap)
        .mapIndexed { index, (_, type, url) ->
            CustomHeroCandidate(
                source = CustomHeroSource.SCREENSCRAPER,
                mediaType = CustomHeroMediaType.VIDEO,
                remoteUrl = url,
                // ScreenScraper has no clip-specific thumb. Reuse its best verified game still as the
                // low-cost picker poster; the full clip remains untouched until the user applies it.
                thumbUrl = posterUrl,
                ordinal = index + 1,
                mediaSubtype = type,
            )
        }
}

/** Exhaustive ScreenScraper format predicate; an unknown/other-provider value never widens results. */
internal fun screenScraperHeroMotionMatchesFormat(
    candidate: CustomHeroCandidate,
    format: String,
): Boolean = when (format) {
    "All", "Video" -> candidate.mediaType == CustomHeroMediaType.VIDEO
    else -> false
}

/**
 * Deletes only top-level hero scratch/orphan files; active committed selections are never evicted.
 *
 * A `<active>.bak` is the identity-scoped recovery copy from a failed atomic rollback. It must survive an
 * unrelated game's sweep and remains until this identity is successfully replaced or no longer active.
 */
internal fun sweepHeroDirectory(directory: File, activeFileNames: Set<String>) {
    directory.listFiles()?.forEach { file ->
        if (!file.isFile) return@forEach
        val recoverableBackup = file.name.endsWith(".bak") &&
            file.name.removeSuffix(".bak") in activeFileNames
        val managed = file.name.endsWith(".img") || file.name.endsWith(".mp4") ||
            file.name.endsWith(".part") || file.name.endsWith(".bak")
        if (managed && file.name !in activeFileNames && !recoverableBackup) file.delete()
    }
}
