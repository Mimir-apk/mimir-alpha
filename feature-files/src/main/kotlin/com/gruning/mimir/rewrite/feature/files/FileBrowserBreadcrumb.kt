package com.gruning.mimir.rewrite.feature.files

import java.io.File

/** One header breadcrumb segment: [label] shown, [path] navigated to on tap. */
internal data class FileBrowserCrumb(val label: String, val path: String)

/**
 * The header's breadcrumb trail, built purely from [rootPath] (the floor this browser cannot navigate
 * above -- [FileBrowserController]'s own invariant) and [currentPath].
 *
 * A crumb's label is [crumbLabels]`[its path]` when a caller supplies one, and otherwise that path's own
 * basename (e.g. `/storage/emulated/0/Roms` -> `"Roms"`). Every crumb after the root is one path
 * segment, each navigable back to that ancestor.
 *
 * **[crumbLabels] is the seam for friendly volume names, and nothing here ever guesses one.** The
 * design's own mockup starts its trail from a volume ("SD card › Roms"); this module cannot derive that
 * -- [com.gruning.mimir.rewrite.core.files.StorageVolumeHelper] needs an Android `Context` that neither
 * this function nor [FileBrowserController] has (see that class's KDoc: "deliberately ignorant of the
 * filesystem beyond `FileItem`"), and inferring "SD card" from a path string would be exactly the
 * unverified assertion the Context-backed lookup avoids. So labels are *threaded in*, not
 * invented: an empty map reproduces the pure-basename behaviour exactly.
 *
 * **Why a path-keyed map rather than a single `rootLabel`.** A `rootLabel` parameter was built first and
 * was not enough, because `core-files`' in-flight `StorageVolumeListing` (2026-08-14) roots
 * the browser in three different places and TWO of them produce a raw crumb:
 *
 * | `StorageVolumeListing.browserRoot(...)` | today's trail | the crumb at fault |
 * |---|---|---|
 * | `/storage` -- more than one volume | `storage › 1A2B-3C4D › Roms` | the root AND the volume beneath it |
 * | `rows.single().path` -- one volume | `0 › Roms › GBA` | the root (`/storage/emulated/0`'s basename is `0`) |
 * | `PRIMARY_FALLBACK_PATH` -- no volumes | `0 › Roms` | the root |
 *
 * In the multi-volume case -- for example an Odin with an SD card -- the volume is a *descendant*
 * crumb, not the root, so no root-only override can reach it. Keying by path covers both positions with
 * one seam and one lookup rule.
 *
 * **The contract for whoever fills it.** Keys are absolute paths, matched after trailing slashes are
 * trimmed from both sides. `app/` already holds everything needed and the whole map is one expression
 * over the volume listing output -- `StorageVolumeListing.volumeRows(volumes).associate { it.path to it.name }`
 * -- plus, when the chooser is the root, one entry naming `/storage` itself (that word is a copy
 * decision for the caller, not one this module should guess). A blank value falls back to the basename,
 * because a `Context`-backed volume lookup that finds nothing returns `""` at least as readily as null.
 */
internal fun fileBrowserBreadcrumbs(
    rootPath: String,
    currentPath: String,
    crumbLabels: Map<String, String> = emptyMap(),
): List<FileBrowserCrumb> {
    // BOTH sides are normalised, not just the lookup path. Trimming only the path silently drops every
    // entry whose KEY carries a trailing slash, and the filler of this map is a caller reading mount
    // points out of `StorageVolume` -- a source that can hand back either form. A key-side miss is
    // invisible: the crumb simply falls back to its basename, which is exactly what it would have shown
    // if no label had been supplied at all.
    val normalised = if (crumbLabels.isEmpty()) crumbLabels else crumbLabels.mapKeys { it.key.trimEnd('/') }

    fun labelFor(path: String, fallback: String): String =
        normalised[path.trimEnd('/')]?.takeIf { it.isNotBlank() } ?: fallback

    val rootFallback = File(rootPath).name.ifBlank { rootPath }
    val crumbs = mutableListOf(FileBrowserCrumb(labelFor(rootPath, rootFallback), rootPath))
    if (currentPath == rootPath) return crumbs

    val relative = currentPath.removePrefix(rootPath).trim('/')
    if (relative.isEmpty()) return crumbs

    var accumulated = rootPath.trimEnd('/')
    relative.split('/').forEach { segment ->
        if (segment.isEmpty()) return@forEach
        accumulated = "$accumulated/$segment"
        crumbs.add(FileBrowserCrumb(labelFor(accumulated, segment), accumulated))
    }
    return crumbs
}

/**
 * The design's own `friendly(path)` helper (`Mimir Files.dc.html:619` -- `p.join(' › ')`), which its
 * inspector uses for the **Where** row by default (`pathStyle` prop defaults to `'friendly'`, line 810).
 *
 * Deliberately derived from [fileBrowserBreadcrumbs] rather than re-split from the path string, so the
 * inspector's Where row and the header's crumb trail can never disagree about what the player is looking
 * at -- including about the root's label, which is exactly the part a caller may override.
 *
 * The design's other mode (`pathStyle = 'raw'`, line 620-624: a mono-spaced `/storage/691E-7BEA/...`) is
 * NOT ported. It is a design-review affordance -- one of the mockup's authoring props, alongside
 * `density` and `showShelfmarks` -- not a player-facing setting this tree has anywhere to hang.
 */
internal fun fileBrowserFriendlyPath(
    rootPath: String,
    currentPath: String,
    crumbLabels: Map<String, String> = emptyMap(),
): String = fileBrowserBreadcrumbs(rootPath, currentPath, crumbLabels).joinToString(" › ") { it.label }

/** The leading marker shown when [fileBrowserVisibleCrumbs] has dropped ancestors. */
internal const val FileBrowserCrumbEllipsis = "…"

/**
 * The crumbs the HEADER should draw: the last [maxTail], with an ellipsis crumb in front when any were
 * dropped.
 *
 * **The current folder is the crumb that must never be lost.** The trail lost its current location because the header's crumb Row takes `weight(1f)` and runs underneath a
 * telemetry pill that a different layer centres on top of it — so the crumb that got covered was the
 * TAIL, which is the only one carrying where you actually are. The trail therefore elides from the left:
 * `… › Internal Storage › Podcasts`.
 *
 * **A count, not a measurement.** Eliding on real width needs `SubcomposeLayout` or a
 * `BoxWithConstraints` round trip, and it would still have to be told where the pill is — geometry owned
 * by another layer, which is exactly the coupling this fix exists to avoid. Trimming by depth is pure,
 * unit-testable, and cannot rot when the pill moves or grows.
 *
 * The dropped crumbs are not navigational losses: every one of them is reachable by walking up, and the
 * trail was never a control surface — `FileBrowserSurface`'s header draws it as text, not as buttons.
 */
internal fun fileBrowserVisibleCrumbs(
    crumbs: List<FileBrowserCrumb>,
    maxTail: Int = 2,
): List<FileBrowserCrumb> {
    if (maxTail < 1 || crumbs.size <= maxTail) return crumbs
    // The ellipsis carries the path of the deepest crumb it stands for, so anything that later wants to
    // make the trail navigable has the target already rather than a decorative string.
    val hidden = crumbs[crumbs.size - maxTail - 1]
    return listOf(FileBrowserCrumb(FileBrowserCrumbEllipsis, hidden.path)) + crumbs.takeLast(maxTail)
}

