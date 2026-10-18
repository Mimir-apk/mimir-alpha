package com.gruning.mimir.rewrite

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import com.gruning.mimir.rewrite.core.launch.EmulatorSeedRegistry
import com.gruning.mimir.rewrite.feature.library.NativeShelfAddCandidate
import com.gruning.mimir.rewrite.feature.library.NativeShelfAddReason
import com.gruning.mimir.rewrite.feature.library.NativeShelfIconCacheDirName
import com.gruning.mimir.rewrite.feature.library.NativeShelfKind
import com.gruning.mimir.rewrite.feature.library.NativeShelfRow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NativeShelfSnapshot(
    val games: List<NativeShelfRow> = emptyList(),
    val apps: List<NativeShelfRow> = emptyList(),
    val addToGames: List<NativeShelfAddCandidate> = emptyList(),
    val addToApps: List<NativeShelfAddCandidate> = emptyList(),
)

data class NativeShelfUserState(
    val favorites: Set<String> = emptySet(),
    val excluded: Set<String> = emptySet(),
    val pinOverrides: Map<String, NativeShelfKind> = emptyMap(),
)

/**
 * R01 S2 (adversarial review SPINE-5, CONFIRMED P1): at process cold start the quick-launch catalog
 * (Application.onCreate) and the app-root shelf coordinator each ran a FULL independent
 * [loadNativeShelfSnapshot] — the same O(N) PackageManager IPC + per-package icon-cache work, twice, racing
 * on Dispatchers.IO. This single-flight, short-window cache coalesces the COLD callers into ONE scan.
 * Anything event-driven (package add/remove receivers, resume refreshes, explicit reloads) keeps calling the
 * raw loader, so freshness contracts are untouched; the window only exists to fuse near-simultaneous
 * cold-start loads.
 */
private const val SnapshotCoalesceWindowMs = 5_000L
private val snapshotCoalesceMutex = Mutex()

@Volatile
private var coalescedSnapshot: Pair<Long, NativeShelfSnapshot>? = null

suspend fun loadNativeShelfSnapshotCoalesced(context: Context): NativeShelfSnapshot {
    val now = android.os.SystemClock.uptimeMillis()
    coalescedSnapshot?.let { (at, snap) -> if (now - at < SnapshotCoalesceWindowMs) return snap }
    return snapshotCoalesceMutex.withLock {
        val again = android.os.SystemClock.uptimeMillis()
        coalescedSnapshot?.let { (at, snap) -> if (again - at < SnapshotCoalesceWindowMs) return@withLock snap }
        loadNativeShelfSnapshot(context).also {
            coalescedSnapshot = android.os.SystemClock.uptimeMillis() to it
        }
    }
}

fun loadNativeShelfSnapshot(context: Context): NativeShelfSnapshot {
    val appContext = context.applicationContext
    val pm = appContext.packageManager
    val self = appContext.packageName
    val userState = loadNativeShelfUserState(appContext)
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val iconCacheDir = File(appContext.cacheDir, NativeShelfIconCacheDirName).apply { mkdirs() }
    val rows = pm.queryLauncherActivitiesCompat(launcherIntent)
        .asSequence()
        .mapNotNull { info -> info.toNativeShelfRow(pm, iconCacheDir) }
        .filterNot { nativeShelfIsHardHiddenPackage(it.packageName, self) }
        .distinctBy { it.packageName }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        .toList()

    return NativeShelfSnapshot(
        games = rows.visibleRowsFor(NativeShelfKind.GAME, userState),
        apps = rows.visibleRowsFor(NativeShelfKind.APP, userState),
        addToGames = rows.addCandidatesFor(NativeShelfKind.GAME, userState),
        addToApps = rows.addCandidatesFor(NativeShelfKind.APP, userState),
    )
}

fun loadNativeShelfUserState(context: Context): NativeShelfUserState {
    val prefs = nativeShelfPrefs(context)
    migrateNativePinOverrideContract(prefs)
    return NativeShelfUserState(
        favorites = prefs.getStringSet(NativeShelfFavoritesKey, emptySet()).orEmpty().toSet(),
        excluded = prefs.getStringSet(NativeShelfExcludedKey, emptySet()).orEmpty().toSet(),
        pinOverrides = loadNativePinOverrides(prefs),
    )
}

fun toggleNativeShelfFavorite(context: Context, packageName: String): Boolean {
    val clean = packageName.trim()
    if (clean.isBlank()) return false
    val prefs = nativeShelfPrefs(context)
    val favorites = prefs.getStringSet(NativeShelfFavoritesKey, emptySet()).orEmpty().toMutableSet()
    val nowFavorite = if (clean in favorites) {
        favorites.remove(clean)
        false
    } else {
        favorites.add(clean)
        true
    }
    prefs.edit().putStringSet(NativeShelfFavoritesKey, favorites).apply()
    return nowFavorite
}

/** Idempotent desired-state write. `true` acknowledges a durable SharedPreferences commit. */
fun setNativeShelfFavorite(context: Context, packageName: String, favorite: Boolean): Boolean {
    val clean = packageName.trim()
    if (clean.isBlank()) return false
    val prefs = nativeShelfPrefs(context)
    val favorites = prefs.getStringSet(NativeShelfFavoritesKey, emptySet()).orEmpty().toMutableSet()
    val changed = if (favorite) favorites.add(clean) else favorites.remove(clean)
    if (!changed) return true
    return prefs.edit().putStringSet(NativeShelfFavoritesKey, favorites).commit()
}

/** Interactive desired-state write: idempotent and non-blocking; migration must use the durable setter above. */
fun setNativeShelfFavoriteInteractive(context: Context, packageName: String, favorite: Boolean): Boolean {
    val clean = packageName.trim()
    if (clean.isBlank()) return false
    val prefs = nativeShelfPrefs(context)
    val favorites = prefs.getStringSet(NativeShelfFavoritesKey, emptySet()).orEmpty().toMutableSet()
    val changed = if (favorite) favorites.add(clean) else favorites.remove(clean)
    if (changed) prefs.edit().putStringSet(NativeShelfFavoritesKey, favorites).apply()
    return true
}

/**
 * Hide [packageName] from its shelf, or bring it back. The Favorite mark is left where it is : every
 * reader of the star goes through [visibleRowsFor], which drops a hidden package first, so a hidden favourite shows
 * nowhere, and one added back from Add game ([addNativeShelfPackageToKind]) returns starred.
 */
fun setNativeShelfExcluded(context: Context, packageName: String, excluded: Boolean) {
    val clean = packageName.trim()
    if (clean.isBlank()) return
    val prefs = nativeShelfPrefs(context)
    val excludedPackages = prefs.getStringSet(NativeShelfExcludedKey, emptySet()).orEmpty().toMutableSet()
    if (excluded) {
        excludedPackages.add(clean)
    } else {
        excludedPackages.remove(clean)
    }
    prefs.edit()
        .putStringSet(NativeShelfExcludedKey, excludedPackages)
        .apply()
}

fun setNativeShelfPinKind(context: Context, packageName: String, kind: NativeShelfKind?) {
    val clean = packageName.trim()
    if (clean.isBlank()) return
    val prefs = nativeShelfPrefs(context)
    val overrides = loadNativePinOverrides(prefs).toMutableMap()
    if (kind == null) {
        overrides.remove(clean)
    } else {
        overrides[clean] = kind
    }
    prefs.edit()
        .putStringSet(NativeShelfPinOverridesKey, encodeNativePinOverrides(overrides))
        .putInt(NativeShelfPinOverrideContractVersionKey, NativeShelfPinOverrideContractVersion)
        .apply()
}

fun addNativeShelfPackageToKind(context: Context, packageName: String, kind: NativeShelfKind) {
    val clean = packageName.trim()
    if (clean.isBlank()) return
    setNativeShelfExcluded(context, clean, excluded = false)
    setNativeShelfPinKind(context, clean, kind)
}

/**
 * Two structurally distinct ways [launchNativeShelfPackageResult] can fail: no launchable intent was
 * found for the package at all, versus a launchable intent existed but starting its activity threw. N5
 * (2026-08-12): the sole caller (`PrimaryMButtonPanelHost.launchPackage`) toasted one undifferentiated
 * message across both from a single shared call site, and the no-intent case had no diagnostic trail at
 * all (the activity-start case already logged via [startNativeShelfLaunchIntent]). The player-facing
 * sentence stays the same for both — matching this exact product's own precedent for the identical
 * distinction on the native shelf's other launch path (`MimirNativeShelfCoordinator.kt`'s B1/B3 arms, N2
 * 2026-08-12, both toast the same verbatim "App could not be launched") — but the two reasons are no
 * longer collapsed onto one call site: each now has its own log line and its own `Toast.makeText` call
 * (the no-intent arm reports here; the caller reports `ACTIVITY_NOT_STARTED` separately, so a real
 * launch failure is never toasted twice), and callers get a typed result instead of a bare `Boolean`.
 */
internal enum class NativeShelfLaunchResult {
    LAUNCHED,
    NO_LAUNCHABLE_INTENT,
    ACTIVITY_NOT_STARTED,
}

internal fun launchNativeShelfPackageResult(context: Context, packageName: String): NativeShelfLaunchResult {
    val clean = cleanNativeShelfPackageName(packageName) ?: run {
        Log.w("MimirNativeShelf", "No launchable intent for native package '$packageName' (blank name)")
        showMimirMessage(context, "Mimir couldn’t open that app.")
        return NativeShelfLaunchResult.NO_LAUNCHABLE_INTENT
    }
    val target = resolveNativeShelfLaunchIntent(context, clean) ?: run {
        Log.w("MimirNativeShelf", "No launchable intent for native package $clean")
        showMimirMessage(context, "Mimir couldn’t open that app.")
        return NativeShelfLaunchResult.NO_LAUNCHABLE_INTENT
    }
    return if (startNativeShelfLaunchIntent(context, clean, target)) {
        NativeShelfLaunchResult.LAUNCHED
    } else {
        // startNativeShelfLaunchIntent already logs the thrown exception (:197) — no duplicate log here.
        NativeShelfLaunchResult.ACTIVITY_NOT_STARTED
    }
}

fun resolveNativeShelfLaunchIntent(context: Context, packageName: String): Intent? {
    val clean = cleanNativeShelfPackageName(packageName) ?: return null
    return resolveNativeLaunchIntent(context.packageManager, clean)
}

fun startNativeShelfLaunchIntent(
    context: Context,
    packageName: String,
    target: Intent,
    /** Optional explicit display; null preserves the caller's existing task-placement policy. */
    launchDisplayId: Int? = null,
    /**  only SWAP's Main start asks for no window animation; every other launch keeps it. */
    noAnimation: Boolean = false,
): Boolean {
    val clean = cleanNativeShelfPackageName(packageName) ?: return false
    val launchIntent = Intent(target).applyNativeShelfLaunchFlags(noAnimation)
    return runCatching {
        if (launchDisplayId == null) {
            context.startActivity(launchIntent)
        } else {
            val options = ActivityOptions.makeBasic().apply {
                this.launchDisplayId = launchDisplayId
            }
            context.startActivity(launchIntent, options.toBundle())
        }
        true
    }.getOrElse { error ->
        Log.w("MimirNativeShelf", "Unable to launch native package $clean", error)
        false
    }
}

internal fun cleanNativeShelfPackageName(packageName: String): String? =
    packageName.trim().takeIf { it.isNotBlank() }

internal fun nativeShelfLaunchFlags(noAnimation: Boolean = false): Int =
    Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
        Intent.FLAG_ACTIVITY_SINGLE_TOP or
        (if (noAnimation) Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_NO_USER_ACTION else 0)

private fun Intent.applyNativeShelfLaunchFlags(noAnimation: Boolean): Intent {
    addFlags(nativeShelfLaunchFlags(noAnimation))
    return this
}

@Suppress("DEPRECATION")
private fun ResolveInfo.toNativeShelfRow(pm: PackageManager, iconCacheDir: File): NativeShelfRow? {
    val activity = activityInfo ?: return null
    val packageName = activity.packageName ?: return null
    // Prefer the curated emulator name (Citron, Azahar, Dolphin…) over the app's raw PackageManager label,
    // which is unpolished or rebranded ("citron", "AzaharPlus") — consistent with the launch picker (UXCI-01.2).
    // Non-emulator apps aren't seeded, so they keep their own label.
    val label = EmulatorSeedRegistry.displayNameFor(packageName)
        ?: loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { packageName }
    val appInfo = activity.applicationInfo
    val bucketsAsGame = bucketsAsAndroidGame(packageName, appInfo)
    val packageEpoch = pm.nativeIconPackageEpoch(packageName)
    return NativeShelfRow(
        packageName = packageName,
        label = label,
        launchActivity = activity.name,
        isGame = bucketsAsGame,
        iconPath = cacheLauncherIcon(
            pm = pm,
            packageName = packageName,
            activityName = activity.name,
            iconCacheDir = iconCacheDir,
            packageEpoch = packageEpoch,
        ),
        heroArtUri = nativeShelfHeroArtUri(
            packageName = packageName,
            activityBannerResId = activity.banner,
            applicationBannerResId = appInfo?.banner ?: 0,
            packageEpoch = packageEpoch,
        ),
        rowKindLabel = nativeShelfAutoKindLabel(
            packageName = packageName,
            pmGameCategory = appInfo?.category == ApplicationInfo.CATEGORY_GAME,
            legacyFlagGame = appInfo?.flags?.and(ApplicationInfo.FLAG_IS_GAME) != 0,
            intentGameCategory = filter?.hasCategory(AndroidIntentCategoryGame) == true,
        ),
    )
}

/**
 * Publish package-owned banner truth without decoding it during package discovery. Activity art wins over
 * application art. The fragment changes Coil identity after an app update while leaving the resource path
 * intact for Android's resource loader.
 */
internal fun nativeShelfHeroArtUri(
    packageName: String,
    activityBannerResId: Int,
    applicationBannerResId: Int,
    packageEpoch: Long,
): String? {
    val cleanPackage = packageName.trim().takeIf(String::isNotEmpty) ?: return null
    val resourceId = activityBannerResId.takeIf { it > 0 }
        ?: applicationBannerResId.takeIf { it > 0 }
        ?: return null
    val base = "android.resource://$cleanPackage/$resourceId"
    return if (packageEpoch > NativeIconUnknownPackageEpoch) "$base#v=$packageEpoch" else base
}

@Suppress("DEPRECATION")
private fun ResolveInfo.bucketsAsAndroidGame(
    packageName: String,
    appInfo: ApplicationInfo?,
): Boolean {
    val legacyFlagGame = appInfo?.flags?.and(ApplicationInfo.FLAG_IS_GAME) != 0
    val intentGameCategory = filter?.hasCategory(AndroidIntentCategoryGame) == true
    return nativeShelfBucketsAsAndroidGame(
        packageName = packageName,
        pmGameCategory = appInfo?.category == ApplicationInfo.CATEGORY_GAME,
        legacyFlagGame = legacyFlagGame,
        intentGameCategory = intentGameCategory,
    )
}

internal fun nativeShelfBucketsAsAndroidGame(
    packageName: String,
    pmGameCategory: Boolean,
    legacyFlagGame: Boolean,
    intentGameCategory: Boolean,
): Boolean =
    packageName in NativeShelfEmulatorPackages ||
        packageName in NativeShelfGamingPackages ||
        pmGameCategory ||
        legacyFlagGame ||
        intentGameCategory

internal fun nativeShelfAutoKindLabel(
    packageName: String,
    pmGameCategory: Boolean,
    legacyFlagGame: Boolean,
    intentGameCategory: Boolean,
): String =
    nativeShelfGamingSubtypeLabel(
        packageName = packageName,
        pmGameCategory = pmGameCategory,
        legacyFlagGame = legacyFlagGame,
        intentGameCategory = intentGameCategory,
    ) ?: "Android app"

private fun nativeShelfGamingSubtypeLabel(
    packageName: String,
    pmGameCategory: Boolean,
    legacyFlagGame: Boolean,
    intentGameCategory: Boolean,
): String? = when {
    packageName in NativeShelfStreamingPackages -> "Streaming"
    packageName in NativeShelfGamingLauncherPackages -> "Launcher"
    packageName in NativeShelfEmulatorPackages -> "Emulator"
    pmGameCategory || legacyFlagGame || intentGameCategory -> "Game"
    else -> null
}

internal fun nativeShelfIsHardHiddenPackage(packageName: String, selfPackageName: String): Boolean =
    packageName == selfPackageName ||
        packageName == "com.gruning.mimir" ||
        packageName == "com.gruning.thorshop" ||
        packageName in NativeShelfHiddenFrontendPackages

private fun List<NativeShelfRow>.visibleRowsFor(
    kind: NativeShelfKind,
    userState: NativeShelfUserState,
): List<NativeShelfRow> = asSequence()
    .filterNot { it.packageName in userState.excluded }
    .filter { it.effectiveKind(userState) == kind }
    .map { it.asEffectiveShelfRow(kind, userState) }
    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    .toList()

private fun List<NativeShelfRow>.addCandidatesFor(
    targetKind: NativeShelfKind,
    userState: NativeShelfUserState,
): List<NativeShelfAddCandidate> = asSequence()
    .mapNotNull { row ->
        val sourceKind = row.effectiveKind(userState)
        val isHidden = row.packageName in userState.excluded
        when {
            sourceKind == targetKind && isHidden -> row.toAddCandidate(
                targetKind = targetKind,
                sourceKind = sourceKind,
                reason = NativeShelfAddReason.HIDDEN_HERE,
                isHidden = true,
            )
            sourceKind != targetKind -> row.toAddCandidate(
                targetKind = targetKind,
                sourceKind = sourceKind,
                reason = NativeShelfAddReason.MOVE_FROM_OTHER_SHELF,
                isHidden = isHidden,
            )
            else -> null
        }
    }
    .distinctBy { it.packageName }
    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    .toList()

private fun NativeShelfRow.effectiveKind(userState: NativeShelfUserState): NativeShelfKind =
    userState.pinOverrides[packageName] ?: if (isGame) NativeShelfKind.GAME else NativeShelfKind.APP

private fun NativeShelfRow.asEffectiveShelfRow(
    kind: NativeShelfKind,
    userState: NativeShelfUserState,
): NativeShelfRow = copy(
    isGame = kind == NativeShelfKind.GAME,
    isFavorite = packageName in userState.favorites,
    rowKindLabel = when (kind) {
        NativeShelfKind.GAME -> if (isGame) rowKindLabel ?: "Game" else "Game"
        NativeShelfKind.APP -> "Android app"
    },
)

private fun NativeShelfRow.toAddCandidate(
    targetKind: NativeShelfKind,
    sourceKind: NativeShelfKind,
    reason: NativeShelfAddReason,
    isHidden: Boolean,
): NativeShelfAddCandidate = NativeShelfAddCandidate(
    packageName = packageName,
    label = label,
    iconPath = iconPath,
    targetKind = targetKind,
    sourceKind = sourceKind,
    reason = reason,
    isHidden = isHidden,
    rowKindLabel = when (sourceKind) {
        NativeShelfKind.GAME -> rowKindLabel ?: "Game"
        NativeShelfKind.APP -> "Android app"
    },
)

private fun ResolveInfo.cacheLauncherIcon(
    pm: PackageManager,
    packageName: String,
    activityName: String?,
    iconCacheDir: File,
    packageEpoch: Long = pm.nativeIconPackageEpoch(packageName),
): String? {
    findVersionedNativeIcon(
        iconCacheDir = iconCacheDir,
        packageName = packageName,
        activityName = activityName,
        packageEpoch = packageEpoch,
    )?.let { cached -> return cached.absolutePath }

    return runCatching {
        val drawable = loadIcon(pm) ?: pm.getApplicationIcon(packageName)
        val bitmap = drawable.toBitmap(
            width = NativeIconCacheSizePx,
            height = NativeIconCacheSizePx,
            config = Bitmap.Config.ARGB_8888,
        )
        val pngBytes = ByteArrayOutputStream().use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw IOException("Unable to encode launcher icon for $packageName")
            }
            stream.toByteArray()
        }
        writeVersionedNativeIcon(
            iconCacheDir = iconCacheDir,
            packageName = packageName,
            activityName = activityName,
            packageEpoch = packageEpoch,
            pngBytes = pngBytes,
        ).absolutePath
    }.getOrElse { error ->
        Log.w("MimirNativeShelf", "Unable to cache launcher icon for $packageName", error)
        null
    }
}

private fun nativeIconCacheStem(packageName: String, activityName: String?): String =
    "$packageName-${activityName.orEmpty()}"
        .map { char -> if (char.isLetterOrDigit() || char == '.' || char == '_' || char == '-') char else '_' }
        .joinToString("")

/**
 * Returns a cache entry only when it belongs to the package version currently reported by
 * PackageManager. A cold process therefore cannot reuse an icon written before an app update.
 * Unknown epochs deliberately miss so the rendered content digest remains authoritative.
 */
private fun findVersionedNativeIcon(
    iconCacheDir: File,
    packageName: String,
    activityName: String?,
    packageEpoch: Long,
): File? {
    if (packageEpoch <= NativeIconUnknownPackageEpoch) return null
    val prefix = "${nativeIconCacheStem(packageName, activityName)}-e$packageEpoch-"
    return iconCacheDir.listFiles()
        ?.firstOrNull { file ->
            file.isFile && file.length() > 0L && file.name.startsWith(prefix) && file.name.endsWith(".png")
        }
}

/**
 * Installs immutable, content-addressed icon bytes under a package-update epoch. Both inputs are in
 * the model path consumed by Coil, so PACKAGE_CHANGED/REPLACED can never rewrite bytes behind an
 * already-successful painter identity. The same bytes and epoch deterministically reuse one path.
 */
internal fun writeVersionedNativeIcon(
    iconCacheDir: File,
    packageName: String,
    activityName: String?,
    packageEpoch: Long,
    pngBytes: ByteArray,
): File {
    require(pngBytes.isNotEmpty()) { "Native icon PNG must not be empty" }
    check(iconCacheDir.exists() || iconCacheDir.mkdirs()) {
        "Unable to create native icon cache ${iconCacheDir.absolutePath}"
    }

    val stem = nativeIconCacheStem(packageName, activityName)
    val digest = nativeIconContentDigest(pngBytes)
    val target = File(iconCacheDir, "$stem-e$packageEpoch-$digest.png")
    if (!target.isFile || target.length() <= 0L) {
        val temporary = File.createTempFile("mimir-native-icon-", ".tmp", iconCacheDir)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(pngBytes)
                output.fd.sync()
            }
            if (!temporary.renameTo(target) && (!target.isFile || target.length() <= 0L)) {
                throw IOException("Unable to atomically install ${target.name}")
            }
        } finally {
            temporary.delete()
        }
    }

    iconCacheDir.listFiles()
        ?.filter { file ->
            file != target &&
                (file.name == "$stem.png" || file.name.startsWith("$stem-e"))
        }
        ?.forEach { file -> runCatching { file.delete() } }
    return target
}

internal fun nativeIconContentDigest(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val alphabet = "0123456789abcdef"
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(alphabet[value ushr 4])
            append(alphabet[value and 0x0f])
        }
    }
}

@Suppress("DEPRECATION")
private fun PackageManager.nativeIconPackageEpoch(packageName: String): Long =
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L)).lastUpdateTime
        } else {
            getPackageInfo(packageName, 0).lastUpdateTime
        }
    }.getOrDefault(NativeIconUnknownPackageEpoch)

fun evictNativeShelfIcon(context: Context, packageName: String) {
    val clean = packageName.trim()
    if (clean.isBlank()) return
    val iconCacheDir = File(context.applicationContext.cacheDir, NativeShelfIconCacheDirName)
    iconCacheDir.listFiles()
        ?.filter { file -> file.name.startsWith("$clean-") }
        ?.forEach { file -> runCatching { file.delete() } }
}

private fun resolveNativeLaunchIntent(pm: PackageManager, packageName: String): Intent? {
    pm.getLaunchIntentForPackage(packageName)?.let { return it }
    val query = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        setPackage(packageName)
    }
    val activityName = pm.queryLauncherActivitiesCompat(query).firstOrNull()?.activityInfo?.name ?: return null
    return Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        setPackage(packageName)
        component = ComponentName(packageName, activityName)
    }
}

@Suppress("DEPRECATION")
private fun PackageManager.queryLauncherActivitiesCompat(intent: Intent): List<ResolveInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
    } else {
        queryIntentActivities(intent, 0)
    }

private fun nativeShelfPrefs(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(NativeShelfPrefsName, Context.MODE_PRIVATE)

private fun migrateNativePinOverrideContract(prefs: SharedPreferences) {
    if (prefs.getInt(NativeShelfPinOverrideContractVersionKey, 0) >= NativeShelfPinOverrideContractVersion) {
        return
    }
    val edit = prefs.edit()
        .putInt(NativeShelfPinOverrideContractVersionKey, NativeShelfPinOverrideContractVersion)
    if (!prefs.getStringSet(NativeShelfPinOverridesKey, emptySet()).isNullOrEmpty()) {
        edit.putStringSet(NativeShelfPinOverridesKey, emptySet())
    }
    edit.apply()
}

private fun loadNativePinOverrides(prefs: SharedPreferences): Map<String, NativeShelfKind> =
    prefs.getStringSet(NativeShelfPinOverridesKey, emptySet()).orEmpty()
        .mapNotNull { entry ->
            val parts = entry.split("=", limit = 2)
            val packageName = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val kindValue = parts.getOrNull(1) ?: return@mapNotNull null
            val kind = NativeShelfKind.values().firstOrNull { it.storageValue == kindValue } ?: return@mapNotNull null
            packageName to kind
        }
        .toMap()

private fun encodeNativePinOverrides(overrides: Map<String, NativeShelfKind>): Set<String> =
    overrides.entries
        .filter { it.key.isNotBlank() }
        .map { (packageName, kind) -> "$packageName=${kind.storageValue}" }
        .toSet()

private const val NativeShelfPrefsName = "mimir_native_shelf"
private const val NativeShelfFavoritesKey = "nativeFavorites"
private const val NativeShelfExcludedKey = "nativeExcluded"
private const val NativeShelfPinOverridesKey = "nativePinOverrides"
private const val NativeShelfPinOverrideContractVersionKey = "nativePinOverrideContractVersion"
private const val NativeShelfPinOverrideContractVersion = 2
private const val NativeIconCacheSizePx = 192
private const val NativeIconUnknownPackageEpoch = 0L
private const val AndroidIntentCategoryGame = "android.intent.category.GAME"

private val NativeShelfEmulatorPackages: Set<String> =
    EmulatorSeedRegistry.allSeedPackages().toSet() + setOf(
        "com.retroarch.ra32",
        "io.recompiled.redream",
        "com.seleuco.mame4d2024",
        "com.seleuco.mame4droid",
        "org.ppsspp.ppssppgold",
        "com.dsemu.drastic",
        "xyz.aethersx2.cturnip",
        "dev.eden.eden_emulator",
        "org.citron.citron_emu",
        "io.github.lime3ds.android",
        "org.azahar_emu.azahar",
        "io.github.azaharplus.android",
        "org.citra.citra_emu.canary",
        "org.citra.emu",
        "io.github.mandarine3ds.mandarine",
        "com.panda3ds.pandroid",
        "net.rpcsx",
        "aenu.aps3e",
        "emu.x360.mobile",
        "emu.x360mobile.com",
        "xendroid.compose",
        "jp.xenia.emulator",
        "aenu.ax360e.free",
        "com.izzy2lost.x1box",
        "org.mupen64plusae.v3.fzurita",
        "org.mupen64plusae.v3.fzurita.pro",
        "org.mupen64plusae.v3.alpha",
        "org.devmiyax.yabasanshioro2",
        "org.devmiyax.yabasanshioro2.pro",
        "com.explusalpha.SaturnEmu",
    )

private val NativeShelfHiddenFrontendPackages = setOf(
    "org.es_de.frontend",
    "com.esde.frontend",
    "com.esde.companion",
    "com.magneticchen.daijishou",
    "com.cocoon.shell",
    "com.cocoon.launcher",
    "xyz.blacksheep.mjolnir",
)

private val NativeShelfStreamingPackages = setOf(
    "com.limelight.noir",
    "com.limelight",
    "com.limelight.shield",
    "psplay.grill.com",
    "com.valvesoftware.steamlink",
)

private val NativeShelfGamingLauncherPackages = setOf(
    "com.xiaoji.egggame",
    "gamehub.lite",
    "emuready.gamehub.lite",
    "app.gamenative",
    "app.gamenative.gold",
)

private val NativeShelfGamingPackages: Set<String> =
    NativeShelfStreamingPackages + NativeShelfGamingLauncherPackages
