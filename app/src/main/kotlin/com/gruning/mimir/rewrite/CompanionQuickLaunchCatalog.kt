package com.gruning.mimir.rewrite

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.gruning.mimir.rewrite.core.launch.EmulatorSeedRegistry
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Process-owned local package catalog shared by every companion surface and foreground-scene follower. */
internal object CompanionQuickLaunchCatalog {
    private val mutableEntries = MutableStateFlow<List<CompanionAppEntry>>(emptyList())
    val entries: StateFlow<List<CompanionAppEntry>> = mutableEntries.asStateFlow()

    private val mutableHasLoaded = MutableStateFlow(false)

    /**
     * Whether [refresh] has ever COMPLETED in this process — i.e. whether [entries] is an answer or just
     * its starting value. Every consumer of [entries] previously had to treat those two as one: the flow
     * starts empty in every process, so "no apps installed" and "nobody has swept yet" were the same
     * observation. `HomeQuickLaunchPickerOverlay` was the only surface that cared, and it had to derive
     * the fact by calling [refresh] itself and treating the return as the signal — which meant it could
     * not answer without doing work the dock had usually already done. The knowledge belongs here, where
     * the sweep is; deriving it downstream is what made it cost a second sweep.
     *
     * Write ordering is load-bearing: [entries] is assigned BEFORE this flips, so anyone who observes
     * `true` is guaranteed to read the swept list and not the starting value. Stays `false` if [refresh]
     * throws — unknown must never be readable as "there is nothing", which is the honesty property the
     * picker's three-state empty handling depends on.
     */
    val hasLoaded: StateFlow<Boolean> = mutableHasLoaded.asStateFlow()

    suspend fun refresh(context: Context) {
        val next = withContext(Dispatchers.IO) {
            // R01 S2 (SPINE-5): coalesced — at cold start this fuses with the shelf coordinator's scan
            // instead of running the identical PackageManager sweep a second time.
            val snapshot = loadNativeShelfSnapshotCoalesced(context.applicationContext)
            buildList {
                snapshot.games.forEach { add(CompanionAppEntry(it.packageName, it.label, it.iconPath)) }
                snapshot.apps.forEach { add(CompanionAppEntry(it.packageName, it.label, it.iconPath)) }
                snapshot.addToGames.forEach { add(CompanionAppEntry(it.packageName, it.label, it.iconPath)) }
                snapshot.addToApps.forEach { add(CompanionAppEntry(it.packageName, it.label, it.iconPath)) }
            }.distinctBy(CompanionAppEntry::packageName)
                // Round-5 triage 7a. Still inside this IO block, so the label lookup never runs on the main
                // thread, and it asks PackageManager (local, no network) only about labels that collide.
                .let { swept ->
                    companionAppEntriesWithDistinctLabels(
                        entries = swept,
                        installedLabelFor = { packageName ->
                            installedApplicationLabel(context.applicationContext.packageManager, packageName)
                        },
                        variantFor = { packageName -> EmulatorSeedRegistry.seedForPackage(packageName)?.variant },
                    )
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, CompanionAppEntry::label))
        }
        mutableEntries.value = next
        // Ordered after the entries write on purpose — see [hasLoaded].
        mutableHasLoaded.value = true
    }

    fun entry(packageName: String): CompanionAppEntry? {
        val clean = packageName.trim()
        return entries.value.firstOrNull { it.packageName == clean }
    }
}

/**
 * Round-5 triage 7a (walkthrough 253, AYN Thor): the Bottom Quick Launch picker drew two tiles reading
 * "Azahar". Catalog labels come from the native shelf row (`toNativeShelfRow` in NativeShelfPackages.kt),
 * which prefers the curated seed name from `EmulatorSeedRegistry.displayNameFor`, and the registry then named
 * both `io.github.lime3ds.android` and `org.azahar_emu.azahar` "Azahar". Since 2026-10-08 the registry gives the
 * lime3ds package its own name ("Azahar (Lime3DS build)"), so that pair no longer collides; this still resolves
 * any other collision (a registry pair or an app outside the registry), where every tile's label is set.
 *
 * An entry whose label (trimmed, ignoring case) no other entry shares is returned exactly as given, and
 * [installedLabelFor] is never asked about it. Entries that do share a label:
 * 1. take their installed application label when it is non-blank and differs;
 * 2. if that still leaves a match, get " · " plus a suffix: the registry variant when every entry in the
 *    group has a distinct one, otherwise the shortest run of trailing package-name segments that tells the
 *    group apart. The variant comes first because it is the registry's own name for how package variants
 *    differ ("play", "vanilla"); a last package segment alone cannot split this very family, since
 *    `io.github.lime3ds.android` and `io.github.azaharplus.android` both end in "android", and apps outside
 *    the registry have no variant at all.
 * An entry that never collided keeps its label even when step 1 moves another entry onto it; the moved
 * entry takes the suffix instead.
 */
internal fun companionAppEntriesWithDistinctLabels(
    entries: List<CompanionAppEntry>,
    installedLabelFor: (packageName: String) -> String?,
    variantFor: (packageName: String) -> String?,
): List<CompanionAppEntry> {
    val colliding = entries
        .groupBy { companionLabelKey(it.label) }
        .values
        .filter { it.size > 1 }
        .flatMapTo(HashSet()) { group -> group.map(CompanionAppEntry::packageName) }
    if (colliding.isEmpty()) return entries
    val relabelled = entries.map { entry ->
        if (entry.packageName !in colliding) return@map entry
        val installed = installedLabelFor(entry.packageName)?.trim()?.takeIf(String::isNotEmpty)
        if (installed == null || installed == entry.label) entry else entry.copy(label = installed)
    }
    val suffixes = relabelled
        .groupBy { companionLabelKey(it.label) }
        .values
        .filter { it.size > 1 }
        .flatMap { group ->
            companionLabelSuffixes(group.filter { it.packageName in colliding }, variantFor).entries
        }
        .associate { it.key to it.value }
    return relabelled.map { entry ->
        suffixes[entry.packageName]?.let { suffix -> entry.copy(label = "${entry.label} · $suffix") } ?: entry
    }
}

private fun companionLabelKey(label: String): String = label.trim().lowercase(Locale.ROOT)

/**
 * One suffix per package in [group]: the registry variants when each entry has one and they all differ,
 * otherwise the shortest run of trailing package-name segments that differs across the group.
 */
private fun companionLabelSuffixes(
    group: List<CompanionAppEntry>,
    variantFor: (packageName: String) -> String?,
): Map<String, String> {
    if (group.isEmpty()) return emptyMap()
    val packages = group.map(CompanionAppEntry::packageName)
    val variants = packages.mapNotNull { variantFor(it)?.trim()?.takeIf(String::isNotEmpty) }
    if (variants.size == packages.size &&
        variants.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }.size == packages.size
    ) {
        return packages.zip(variants).toMap()
    }
    val segments = packages.map { it.split('.') }
    val deepest = segments.maxOf { it.size }
    // Distinct package names always differ at full depth, so the search ends by `deepest` at the latest.
    val depth = (1..deepest).firstOrNull { tail ->
        segments.mapTo(HashSet()) { it.takeLast(tail) }.size == packages.size
    } ?: deepest
    return packages.zip(segments) { packageName, parts ->
        packageName to parts.takeLast(depth).joinToString(".")
    }.toMap()
}

/** The package's own application label from PackageManager, or null. A local read with no network. */
@Suppress("DEPRECATION")
private fun installedApplicationLabel(packageManager: PackageManager, packageName: String): String? =
    runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            packageManager.getApplicationInfo(packageName, 0)
        }
        packageManager.getApplicationLabel(info).toString()
    }.getOrNull()
        // An app that declares no label is answered with its package name, which is not a label.
        ?.takeIf { it != packageName }
