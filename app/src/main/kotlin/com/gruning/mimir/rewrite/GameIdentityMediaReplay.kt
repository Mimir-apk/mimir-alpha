package com.gruning.mimir.rewrite

import android.content.Context
import com.gruning.mimir.rewrite.core.model.GameId
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * File-only v9 identity replay. The journal supplies only a newer source transition; SELECTED replaces
 * the target and AUTOMATIC clears it. Source bytes remain recoverable, making recurring regrouping safe.
 */
internal object GameIdentityMediaReplay {

    fun remapActiveAndRetained(
        context: Context,
        fromGameId: GameId,
        toGameId: GameId,
        iconTransition: GameIdentityMediaSelection? = null,
        physicalTransition: GameIdentityMediaSelection? = null,
        /**
         * The COVER pick's transition. Added 2026-08-25 to close a real gap: when the Cover picker landed
         * in `96cd3ce7` it added a fourth explicitly-chosen medium and **no remap path for it**. ICON and
         * TOME_PHYSICAL are replayed below, HERO and CLIP by `CustomHeroStore.remapGameId`, and COVER by
         * nothing at all -- so a re-identified game kept its cover GALLERY and silently lost the cover he
         * had actually chosen, reverting to automatic with no error.
         */
        coverTransition: GameIdentityMediaSelection? = null,
    ): Boolean {
        if (fromGameId == toGameId) return true
        val from = fromGameId.value
        val to = toGameId.value

        val sourceIcon = selectedSourceFile(
            context = context,
            kind = EnrichMediaKind.ICON,
            cacheKey = from,
            renderer = SteamGridDbTileFetcher.canonicalTileIconFile(context, from),
        )
        val targetIcon = SteamGridDbTileFetcher.canonicalTileIconFile(context, to)
        val sourceIconValid = sourceIcon?.file?.let { it.isFile && it.length() > 0L } == true
        val sourceIconAdopted = iconTransition == GameIdentityMediaSelection.SELECTED &&
            sourceIconValid &&
            copyIdentityAsset(requireNotNull(sourceIcon).file, targetIcon, overwrite = true) &&
            targetIcon.isFile &&
            targetIcon.length() > 0L
        if (iconTransition == GameIdentityMediaSelection.SELECTED && sourceIconValid && !sourceIconAdopted) {
            return false
        }
        if (iconTransition == GameIdentityMediaSelection.AUTOMATIC &&
            !deleteActiveWithSidecar(targetIcon)
        ) {
            return false
        }
        if (sourceIconAdopted) {
            val adoptedIconSource = requireNotNull(sourceIcon)
            val activeSourceIcon = SteamGridDbTileFetcher.canonicalTileIconFile(context, from)
            val sourceIndex = File(activeSourceIcon.parentFile, activeSourceIcon.name + ".idx")
            val targetIndex = File(targetIcon.parentFile, targetIcon.name + ".idx")
            val indexReplayed = when {
                adoptedIconSource.catalogAssetId != null ->
                    replaceIdentityMarker(targetIndex, "saved:${adoptedIconSource.catalogAssetId}")
                sourceIndex.isFile && sourceIndex.length() > 0L ->
                    copyIdentityAsset(sourceIndex, targetIndex, overwrite = true)
                else -> !targetIndex.exists() || targetIndex.delete()
            }
            if (!indexReplayed) {
                return false
            }
        }

        val sourcePhysical = selectedSourceFile(
            context = context,
            kind = EnrichMediaKind.TOME_PHYSICAL,
            cacheKey = from,
            renderer = TomeCartridgeArtStore.activeFile(context, from),
        )
        val targetPhysical = TomeCartridgeArtStore.activeFile(context, to)
        when (physicalTransition) {
            GameIdentityMediaSelection.SELECTED -> {
                val selected = sourcePhysical ?: return false
                if (!copyIdentityAsset(selected.file, targetPhysical, overwrite = true)) return false
            }
            GameIdentityMediaSelection.AUTOMATIC -> {
                if (targetPhysical.exists() && !targetPhysical.delete()) return false
            }
            GameIdentityMediaSelection.NEVER_CHOSEN,
            null,
            -> Unit
        }
        // Deliberately the same shape as the TOME_PHYSICAL block above -- same selected-source lookup,
        // same SELECTED/AUTOMATIC/NEVER_CHOSEN handling. The two are the same transaction against
        // different stores, and diverging here would be two ways to lose a pick.
        val sourceCover = selectedSourceFile(
            context = context,
            kind = EnrichMediaKind.COVER,
            cacheKey = from,
            renderer = CustomCoverStore.activeFile(context, from),
        )
        val targetCover = CustomCoverStore.activeFile(context, to)
        when (coverTransition) {
            GameIdentityMediaSelection.SELECTED -> {
                val selected = sourceCover ?: return false
                if (!copyIdentityAsset(selected.file, targetCover, overwrite = true)) return false
            }
            GameIdentityMediaSelection.AUTOMATIC -> {
                if (targetCover.exists() && !targetCover.delete()) return false
            }
            GameIdentityMediaSelection.NEVER_CHOSEN,
            null,
            -> Unit
        }
        if (!copyNewestRetained(
                CustomImageImportStore.retainedIconFile(context, from),
                CustomImageImportStore.retainedIconFile(context, to),
            )
        ) {
            return false
        }
        if (!copyNewestRetained(
                CustomImageImportStore.retainedHeroFile(context, fromGameId),
                CustomImageImportStore.retainedHeroFile(context, toGameId),
            )
        ) {
            return false
        }
        return true
    }
}

private data class SelectedReplaySource(
    val file: File,
    val catalogAssetId: String?,
)

private fun selectedSourceFile(
    context: Context,
    kind: EnrichMediaKind,
    cacheKey: String,
    renderer: File,
): SelectedReplaySource? {
    if (renderer.isFile && renderer.length() > 0L) return SelectedReplaySource(renderer, null)
    val catalog = EnrichSavedStore.load(context, kind, cacheKey)
    val active = catalog.entries.firstOrNull { it.assetId == catalog.activeAssetId } ?: return null
    return File(active.localPath)
        .takeIf { it.isFile && it.length() > 0L }
        ?.let { SelectedReplaySource(it, active.assetId) }
}

private fun replaceIdentityMarker(target: File, marker: String): Boolean {
    target.parentFile?.mkdirs()
    val staged = File(target.parentFile, target.name + ".identity.part")
    staged.delete()
    return runCatching {
        staged.writeText(marker)
        Files.move(
            staged.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
        target.isFile && target.readText().trim() == marker
    }.getOrDefault(false).also { staged.delete() }
}

private fun copyIdentityAsset(
    source: File,
    target: File,
    overwrite: Boolean,
    requireSource: Boolean = false,
): Boolean {
    if (!overwrite && target.isFile && target.length() > 0L) return true
    if (!source.isFile || source.length() <= 0L) return !requireSource
    if (source.length() > CustomHeroStore.MaxAssetBytes) return false
    target.parentFile?.mkdirs()
    val staged = File(target.parentFile, target.name + ".identity.part")
    staged.delete()
    return runCatching {
        source.inputStream().use { input ->
            staged.outputStream().use { output -> input.copyTo(output) }
        }
        if (!staged.isFile || staged.length() <= 0L || staged.length() > CustomHeroStore.MaxAssetBytes) {
            false
        } else {
            if (target.exists() && !overwrite && target.length() > 0L) return@runCatching true
            if (target.exists() && target.length() == 0L) target.delete()
            runCatching {
                Files.move(
                    staged.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.recoverCatching {
                Files.move(
                    staged.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.isSuccess && target.isFile && target.length() > 0L
        }.also { copied ->
            if (copied) target.setLastModified(source.lastModified())
        }
    }.getOrDefault(false).also { staged.delete() }
}

private fun copyNewestRetained(source: File, target: File): Boolean {
    if (!source.isFile || source.length() <= 0L) return true
    if (target.isFile &&
        target.length() > 0L &&
        target.lastModified() >= source.lastModified()
    ) {
        return true
    }
    return copyIdentityAsset(source, target, overwrite = true)
}

private fun deleteActiveWithSidecar(target: File): Boolean {
    val sidecar = File(target.parentFile, target.name + ".idx")
    return (!target.exists() || target.delete()) &&
        (!sidecar.exists() || sidecar.delete())
}
