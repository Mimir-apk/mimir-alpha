package com.gruning.mimir.rewrite

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Size
import com.gruning.mimir.rewrite.core.metadata.online.RetroAchievementsIcons
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirBoxArt
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.rememberMimirArtWired
import java.io.File
import java.net.URI
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val AchievementHubArtTag = "achievement-hub-art"
internal const val AchievementHubIconTileTag = "achievement-hub-icon-tile"

/** What art a card may wear, in the order it is tried: the matched library game's hero, then RA's title or in-game image for it, then RA's icon for it, then the theme alone. */
internal enum class ActivityAchievementArtKind { HERO, ART, ICON, THEME }

/**
 * The fallback order, as one pure decision. [heroWired] is the verdict of the same off-main art check every other hero consumer uses (a hero whose file is
 * gone is not wired); [iconFile] is the staged RA icon on disk, or null when there is none. The theme backdrop is what every other page already has behind
 * it, so [ActivityAchievementArtKind.THEME] draws nothing of its own.
 */
internal fun activityAchievementArtKind(heroWired: Boolean, iconFile: File?, artFile: File? = null): ActivityAchievementArtKind = when {
    heroWired -> ActivityAchievementArtKind.HERO
    artFile != null -> ActivityAchievementArtKind.ART
    iconFile != null -> ActivityAchievementArtKind.ICON
    else -> ActivityAchievementArtKind.THEME
}

/** The two local sources a card's art may come from. Both are already on disk (or null); resolving them never touches the network for a hero. */
internal data class ActivityAchievementArtSources(
    val heroUri: String?,
    val iconFile: File?,
    /** RA's own title or in-game image for the game, staged on disk: the full-bleed art of a card whose game has no library hero. */
    val artFile: File? = null,
    /** Frees the icon's hold on the cache when the art leaves composition; a file that is held is never evicted. */
    val release: () -> Unit = {},
)

/** Resolves a card's art sources off the main thread, for one snapshot. */
internal fun interface ActivityAchievementArtResolver {
    suspend fun sources(snapshot: CompanionActivityAchievementSnapshot): ActivityAchievementArtSources
}

/** No art at all: previews, tests, and a hub with no resolver wired. */
internal val NoActivityAchievementArt = ActivityAchievementArtResolver { ActivityAchievementArtSources(null, null) }

/** A snapshot is a library game's (so it may have a hero) unless it carries the neutral `ra:<id>` identity of an unmatched RA game. */
internal fun activityAchievementIsLibraryGame(gameId: String): Boolean = !gameId.startsWith("ra:")

/**
 * Whether a hero URI names a file that is there to paint, without the network and without touching the main thread (the caller is on IO). A `content:` URI
 * cannot be statted here and counts as present (the composable's own wired check still gates it); anything else (http, https, unknown) never paints,
 * because the app's image loader is local-only.
 */
internal fun activityHeroUriOnDisk(uri: String?): Boolean {
    val clean = uri?.trim()?.substringBefore('#')?.takeIf { it.isNotEmpty() } ?: return false
    return when {
        clean.startsWith("file:") -> runCatching { File(URI(clean)).let { it.isFile && it.length() > 0L } }.getOrDefault(false)
        clean.startsWith("/") -> File(clean).let { it.isFile && it.length() > 0L }
        clean.startsWith("content:") -> true
        else -> false
    }
}

/**
 * The real resolver. A matched library game's hero comes first (its Customize-media override, else the resolver's hero), and only when that file is
 * missing does the RA icon get looked for, so a game with a hero never costs an icon fetch. [heroUriFor] is the library's hero including the player's
 * custom override; it runs on IO.
 */
internal class LibraryActivityAchievementArtResolver(
    private val heroUriFor: suspend (gameId: String) -> String?,
    private val icons: ActivityIconStore,
) : ActivityAchievementArtResolver {
    override suspend fun sources(snapshot: CompanionActivityAchievementSnapshot): ActivityAchievementArtSources {
        val hero = if (activityAchievementIsLibraryGame(snapshot.gameId)) heroUriFor(snapshot.gameId) else null
        if (activityHeroUriOnDisk(hero)) return ActivityAchievementArtSources(heroUri = hero, iconFile = null)
        // No hero: RA's title or in-game image (its path arrives with the card's detail), else RA's icon, else the theme.
        val art = snapshot.artPath?.let { icons.acquire(it) }
        if (art != null) return ActivityAchievementArtSources(heroUri = null, iconFile = null, artFile = art.file, release = { art.release() })
        val lease = snapshot.iconPath?.let { icons.acquire(it) }
        return ActivityAchievementArtSources(heroUri = null, iconFile = lease?.file, release = { lease?.release() })
    }
}

/**
 * The icon cache's bounds. BYTES govern: 192 MiB holds several full sets at once (a 500-game library's icons and title art, and the badge sets of the games a
 * player opens, at a few KiB per 64 or 96 px PNG), so a cold start finds them on disk instead of fetching them again. The file count is only a safety ceiling
 * for a folder full of tiny files.
 */
internal const val ACTIVITY_ICON_CACHE_MAX_FILES = 32_768

/** At most this many bytes in all (see [ACTIVITY_ICON_CACHE_MAX_FILES]). */
internal const val ACTIVITY_ICON_CACHE_MAX_BYTES: Long = 192L * 1024L * 1024L

/** One icon held for reading. The file stays on disk until [release], however full the cache gets; release it when the art leaves composition. */
internal class ActivityIconLease(val file: File, private val onRelease: () -> Unit) {
    private var released = false

    @Synchronized
    fun release() {
        if (released) return
        released = true
        onRelease()
    }
}

/**
 * RA icons kept on local disk, one PNG per icon, under [dir]. An icon is fetched lazily (only for a card the hub is actually composing), at most one at a
 * time, and kept: a file that is there is never fetched again, and the disk is looked at BEFORE the fetch, so a cold start over a folder an earlier run
 * filled asks the network for nothing. A failed fetch is remembered for [retryAfterMillis], so a page scrolled away from and back to does not ask again at
 * once. [stage] writes the bytes to the file it is given and says whether it did; it is the RetroAchievements client's keyless, gated, bounded icon fetch in
 * the app. The names come from [RetroAchievementsIcons.fileName], so nothing here can be steered outside [dir].
 *
 * The cache is BOUNDED: at most [maxBytes] bytes (the real bound) and [maxFiles] files (a safety ceiling). Every read moves the file to the young end of an
 * in-memory least-recently-used order (and touches its modified time, so the order survives a restart), and after each successful write the oldest files are
 * evicted until both bounds hold. The order lives in memory, so a write costs a few map operations rather than a listing and a sort of the folder. A file that
 * is leased ([acquire]) is never evicted or cleared while it is held; a [clear] that meets one removes it when its last lease is released. All file work runs
 * on the caller's (IO) thread under the store's lock.
 */
internal class ActivityIconStore(
    private val dir: File,
    private val stage: suspend (path: String, target: File) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryAfterMillis: Long = 5L * 60L * 1000L,
    private val maxFiles: Int = ACTIVITY_ICON_CACHE_MAX_FILES,
    private val maxBytes: Long = ACTIVITY_ICON_CACHE_MAX_BYTES,
) {
    private val mutex = Mutex()
    private val failedAt = HashMap<String, Long>()
    private val leases = HashMap<String, Int>()
    private val deleteWhenReleased = HashSet<String>()

    /** File name to size in bytes, least recently used first. Built from the folder once (oldest modified time first) and then kept up to date; guarded by [mutex]. */
    private var order: LinkedHashMap<String, Long>? = null
    private var orderBytes = 0L

    /** The staged file for [path] when there already is one. No fetch, no network, no touch. */
    fun staged(path: String): File? {
        val name = RetroAchievementsIcons.fileName(path) ?: return null
        return File(dir, name).takeIf { it.isFile && it.length() > 0L }
    }

    /** The icon for [path] (fetched if absent) held for reading, or null. The caller releases the lease when it is done with the file. */
    suspend fun acquire(path: String): ActivityIconLease? {
        val name = RetroAchievementsIcons.fileName(path) ?: return null
        return mutex.withLock {
            val file = ensureLocked(path, name) ?: return@withLock null
            file.setLastModified(clock())
            noteUsedLocked(name, file.length())
            synchronized(leases) { leases[name] = (leases[name] ?: 0) + 1 }
            ActivityIconLease(file) { releaseLease(name) }
        }
    }

    /** The icon for [path] without holding it (for callers that only need to know it is there). */
    suspend fun ensure(path: String): File? = acquire(path)?.let { lease -> lease.release(); lease.file }

    /** Remove every icon (sign-out, opt-out). A leased one goes when its last lease is released. */
    suspend fun clear() {
        mutex.withLock {
            failedAt.clear()
            for (file in cachedFiles()) {
                synchronized(leases) { if ((leases[file.name] ?: 0) > 0) deleteWhenReleased += file.name else file.delete() }
            }
            dir.listFiles()?.filter { it.name.endsWith(PARTIAL_SUFFIX) }?.forEach(File::delete)
            // What is left (a leased file) is found again when the order is next needed.
            order = null
            orderBytes = 0L
        }
    }

    private fun releaseLease(name: String) {
        synchronized(leases) {
            val left = (leases[name] ?: 0) - 1
            if (left > 0) {
                leases[name] = left
            } else {
                leases.remove(name)
                if (deleteWhenReleased.remove(name)) File(dir, name).delete()
            }
        }
    }

    private suspend fun ensureLocked(path: String, name: String): File? {
        staged(path)?.let { return it }
        val failed = failedAt[name]
        if (failed != null && clock() - failed < retryAfterMillis) return null
        val target = File(dir, name)
        return if (stage(path, target) && target.isFile && target.length() > 0L) {
            failedAt.remove(name)
            synchronized(leases) { deleteWhenReleased.remove(name) }
            target.setLastModified(clock())
            noteUsedLocked(name, target.length())
            evictLocked(keep = name)
            target
        } else {
            failedAt[name] = clock()
            null
        }
    }

    private fun cachedFiles(): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(ICON_SUFFIX) }.orEmpty()

    /** The least-recently-used order, built from the folder (oldest modified time first) the first time it is needed. */
    private fun orderLocked(): LinkedHashMap<String, Long> =
        order ?: LinkedHashMap<String, Long>().also { built ->
            var bytes = 0L
            for (file in cachedFiles().sortedWith(compareBy<File>({ it.lastModified() }, { it.name }))) {
                val size = file.length()
                built[file.name] = size
                bytes += size
            }
            order = built
            orderBytes = bytes
        }

    /** [name] was just read or written: it is now the most recently used, at [size] bytes. */
    private fun noteUsedLocked(name: String, size: Long) {
        val held = orderLocked()
        held.remove(name)?.let { orderBytes -= it }
        held[name] = size
        orderBytes += size
    }

    /** Oldest first, until both bounds hold; a leased file and the one just written are skipped, so a full cache of held icons stops short, never blocks. */
    private fun evictLocked(keep: String) {
        val held = orderLocked()
        val oldest = held.entries.iterator()
        while ((held.size > maxFiles || orderBytes > maxBytes) && oldest.hasNext()) {
            val entry = oldest.next()
            if (entry.key == keep || synchronized(leases) { (leases[entry.key] ?: 0) > 0 }) continue
            val file = File(dir, entry.key)
            if (!file.exists() || file.delete()) {
                orderBytes -= entry.value
                oldest.remove()
            }
        }
    }

    private companion object {
        const val ICON_SUFFIX = ".png"
        const val PARTIAL_SUFFIX = ".partial"
    }
}

/**
 * The seam between the achievements lane (which owns the RetroAchievements client) and the Bottom's hub (which paints). The lane installs the staging
 * function while it lives and removes it when it closes; with none installed, [stage] fetches nothing and says so. It also holds the process's one icon
 * store, so the hub that fills it and the lane that clears it (sign-out, opt-out) talk about the same folder.
 */
internal object CompanionActivityIcons {
    private class Installed(val owner: Any, val stage: suspend (String, File) -> Boolean)

    @Volatile
    private var installed: Installed? = null

    @Volatile
    private var store: ActivityIconStore? = null

    /** [owner] installs [stage]; a later owner replaces it, and [release] by an owner that was replaced removes nothing. */
    fun install(owner: Any, stage: suspend (String, File) -> Boolean) {
        installed = Installed(owner, stage)
    }

    fun release(owner: Any) {
        if (installed?.owner === owner) installed = null
    }

    suspend fun stage(path: String, target: File): Boolean = installed?.stage?.invoke(path, target) ?: false

    /** The process's icon store, under the app's files directory. */
    fun storeFor(context: android.content.Context): ActivityIconStore =
        store ?: synchronized(this) {
            store ?: ActivityIconStore(File(context.applicationContext.filesDir, "ra_icons"), { path, target -> stage(path, target) }).also { store = it }
        }
}


/**
 * Art behind one achievement card: the matched game's hero under a palette scrim; else RA's 96 x 96 icon as a crisp tile over a blurred, theme-tinted wash;
 * else nothing of its own (the theme backdrop stands). Decorative: hidden from accessibility, never focusable, no animation, nothing read on the main thread.
 */
@Composable
internal fun ActivityAchievementArt(
    snapshot: CompanionActivityAchievementSnapshot,
    resolver: ActivityAchievementArtResolver,
    modifier: Modifier = Modifier,
    /** Where the resolver runs: IO in the app, so no stat or fetch is ever on the main thread. Tests pass a direct context. */
    workContext: CoroutineContext = Dispatchers.IO,
) {
    // The hold on the art is tied to THIS producer's life (see the badge in the panel): acquired, published, then released exactly once in the finally when
    // the producer is cancelled, so a card that leaves between the producer finishing and the next recomposition cannot leak it.
    val sources by produceState<ActivityAchievementArtSources?>(null, snapshot.gameId, snapshot.iconPath, snapshot.artPath, resolver, workContext) {
        var made: ActivityAchievementArtSources? = null
        try {
            withContext(workContext) {
                try {
                    made = resolver.sources(snapshot)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (ignored: Exception) {
                    made = null
                }
            }
            value = made
            awaitDispose { }
        } finally {
            made?.release?.invoke()
        }
    }
    val shown = sources
    val hero = shown?.heroUri
    val heroWired = rememberMimirArtWired(hero)
    val icon = shown?.iconFile
    val art = shown?.artFile
    Box(modifier.clearAndSetSemantics { }.testTag(AchievementHubArtTag)) {
        when (activityAchievementArtKind(heroWired = hero != null && heroWired, iconFile = icon, artFile = art)) {
            ActivityAchievementArtKind.HERO -> ActivityHeroArt(hero.orEmpty(), snapshot.gameTitle.orEmpty())
            ActivityAchievementArtKind.ART -> art?.let { ActivityHeroArt(it, snapshot.gameTitle.orEmpty()) }
            ActivityAchievementArtKind.ICON -> icon?.let { ActivityIconArt(it) }
            ActivityAchievementArtKind.THEME -> Unit
        }
    }
}

@Composable
private fun ActivityHeroArt(hero: Any, title: String) {
    val palette = LocalMimirPalette.current
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(MimirSpacing.radiusCard))) {
        MimirBoxArt(model = hero, title = title, focused = false, contentScale = ContentScale.Crop)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(palette.surfaceBase.copy(alpha = 0.60f), palette.surfaceBase.copy(alpha = 0.88f))),
            ),
        )
    }
}

/** RA's icon is 96 x 96 pixels: it is drawn at a whole multiple of that, unfiltered, so it stays crisp; it is never stretched to fill the page. */
internal const val RA_ICON_SOURCE_PX = 96

internal fun activityIconTileScale(targetPx: Float): Int = (targetPx / RA_ICON_SOURCE_PX).toInt().coerceAtLeast(1)

@Composable
private fun ActivityIconArt(file: File) {
    val palette = LocalMimirPalette.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val request = remember(file, context) {
        ImageRequest.Builder(context).data(file).size(Size.ORIGINAL).crossfade(false).build()
    }
    val tilePainter = rememberAsyncImagePainter(model = request, filterQuality = FilterQuality.None)
    val tileDp = with(density) { (RA_ICON_SOURCE_PX * activityIconTileScale(112.dp.toPx())).toDp() }
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(MimirSpacing.radiusCard))) {
        ActivityIconWash(request)
        Box(Modifier.fillMaxSize().background(palette.accent.copy(alpha = 0.18f)))
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(palette.surfaceBase.copy(alpha = 0.55f), palette.surfaceBase.copy(alpha = 0.88f))),
            ),
        )
        Image(
            painter = tilePainter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = MimirSpacing.lg)
                .size(tileDp)
                .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                .testTag(AchievementHubIconTileTag),
        )
    }
}

private val WashBlur = 32.dp

/**
 * The wash: the icon, blurred past recognition, only where the platform can blur (API 31+). Below that the wash is the theme tint alone, never the 96 px bitmap
 * stretched across the page. Its own function so the platform branch sits in a full composable body, not a layout content lambda.
 */
@Composable
private fun ActivityIconWash(request: ImageRequest) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val washPainter = rememberAsyncImagePainter(model = request)
        Image(
            painter = washPainter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(WashBlur),
        )
    }
}
