package com.gruning.mimir.rewrite.feature.library.data

import com.gruning.mimir.rewrite.core.model.DuplicateGroupKind
import com.gruning.mimir.rewrite.core.model.EntryKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameMemberRow
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.LibraryRepository
import com.gruning.mimir.rewrite.core.model.library.LibrarySummary
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.model.library.ScanResult
import com.gruning.mimir.rewrite.core.model.library.SourceRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * ⚠️ SAMPLE / PLACEHOLDER DATA — NOT WIRED TO ANY STORAGE OR SCANNER ⚠️
 *
 * A stand-in [LibraryRepository] that emits representative, DEDUP-COLLAPSED product-truth so the whole
 * Library UI (platforms shelf → gamelist → detail stub) can be previewed and tested offline.
 * It follows the production repository's presentation shape:
 *
 *  - Only platforms with games are emitted ; [observePlatforms]
 *    already filters `gameCount > 0`.
 *  - Games are the COLLAPSED groups (one tile per group); a group with `memberCount > 1` is a multi-disc
 *    set or a region/version fold  — the UI draws the "N discs / N
 *    versions" badge from that.
 *  - Sources carry FRIENDLY paths only, never a raw UUID/codename .
 *  - Approved vocabulary only (local-only policy): "N games", never intake-era wording.
 *
 * [rescan] is a no-op that returns an honest [ScanResult] describing what a pass over this fake corpus
 * *would* see — it never touches a folder, a network, or a device.
 *
 * Production uses the Room-backed `LibraryStore.repository(context)` through `LibraryInjection` in `:app`.
 * This implementation is for previews and tests that explicitly choose sample data.
 */
class FakeLibraryRepository : LibraryRepository {

    // A stable in-memory corpus. Kept as StateFlows so a (fake) rescan can push a new snapshot and the
    // collecting UI recomposes exactly as it will against the real reactive Room-backed impl.
    private val _platforms = MutableStateFlow(SEED_PLATFORMS)
    private val _sources = MutableStateFlow(SEED_SOURCES)
    // User state, so the preview/demo actually toggles favorites + hide (matches the real reactive impl). ONE state
    // holds both marks. A hide is a single write that leaves the star alone, as the real tables do: every list here
    // drops hidden games, so a hidden favourite shows nowhere and a restore brings it back starred.
    private data class UserMarks(
        val favorites: Set<String> = emptySet(),
        val hidden: Set<String> = emptySet(),
    )

    private val _marks = MutableStateFlow(UserMarks())
    // Manual title overrides , keyed by gameId — mirrors the real precedence:
    // an override, when present, replaces the seed title outright.
    private val _titleOverrides = MutableStateFlow<Map<String, String>>(emptyMap())

    override fun observeSummary(): Flow<LibrarySummary> =
        _platforms.map { platforms ->
            LibrarySummary(
                gameCount = platforms.sumOf { it.gameCount },
                platformCount = platforms.count { it.gameCount > 0 },
            )
        }

    /** Only platforms that HAVE games reach the shelf . */
    override fun observePlatforms(): Flow<List<PlatformRow>> =
        _platforms.map { platforms -> platforms.filter { it.gameCount > 0 } }

    override fun observeGames(platformId: PlatformId): Flow<List<GameRow>> =
        combine(_marks, _titleOverrides) { marks, overrides ->
            SEED_GAMES[platformId].orEmpty()
                .filterNot { it.gameId.value in marks.hidden }
                .map { it.copy(isFavorite = it.gameId.value in marks.favorites) }
                .map { it.applyTitleOverride(overrides) }
                .sortedWith(
                    compareBy<GameRow> { it.lastPlayedMillis == null }
                        .thenByDescending { it.lastPlayedMillis }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                        .thenBy { it.gameId.value },
                )
        }

    override fun observeGame(gameId: GameId): Flow<GameRow?> =
        combine(_marks.map { it.favorites }.distinctUntilChanged(), _titleOverrides) { favorites, overrides ->
            SEED_GAMES.values.flatten().firstOrNull { it.gameId == gameId }
                ?.let { it.copy(isFavorite = it.gameId.value in favorites) }
                ?.applyTitleOverride(overrides)
        }

    override fun observeGameMembers(gameId: GameId): Flow<List<GameMemberRow>> {
        val game = SEED_GAMES.values.flatten().firstOrNull { it.gameId == gameId }
        return flowOf(
            List(game?.memberCount ?: 0) { index ->
                GameMemberRow(
                    entryKey = EntryKey("${gameId.value}/member/${index + 1}"),
                    discNumber = if ((game?.memberCount ?: 0) > 1) index + 1 else 0,
                    displayName = game?.title.orEmpty(),
                    sizeBytes = 0L,
                )
            },
        )
    }

    override fun observeFavorites(): Flow<List<GameRow>> =
        combine(_marks, _titleOverrides) { marks, overrides ->
            SEED_GAMES.values.flatten()
                .filter { it.gameId.value in marks.favorites && it.gameId.value !in marks.hidden }
                .map { it.copy(isFavorite = true) }
                .map { it.applyTitleOverride(overrides) }
                .sortedBy { it.title }
        }

    override fun observeSources(): Flow<List<SourceRow>> = _sources.asStateFlow()

    override fun observeResolvingCount(): Flow<Int> = kotlinx.coroutines.flow.flowOf(0)

    override fun observeHiddenGames(): Flow<List<GameRow>> =
        combine(_marks.map { it.hidden }.distinctUntilChanged(), _titleOverrides) { hid, overrides ->
            SEED_GAMES.values.flatten().filter { it.gameId.value in hid }.map { it.applyTitleOverride(overrides) }
        }

    override suspend fun setFavorite(gameId: GameId, favorite: Boolean) {
        _marks.update { marks ->
            marks.copy(favorites = if (favorite) marks.favorites + gameId.value else marks.favorites - gameId.value)
        }
    }

    override suspend fun setHidden(gameId: GameId, hidden: Boolean) {
        _marks.update { marks ->
            marks.copy(hidden = if (hidden) marks.hidden + gameId.value else marks.hidden - gameId.value)
        }
    }

    override suspend fun setTitleOverride(gameId: GameId, title: String) {
        _titleOverrides.value = _titleOverrides.value + (gameId.value to title)
    }

    override suspend fun clearTitleOverride(gameId: GameId) {
        _titleOverrides.value = _titleOverrides.value - gameId.value
    }

    /** a manual override, when present, IS the title — verbatim, no re-derivation. */
    private fun GameRow.applyTitleOverride(overrides: Map<String, String>): GameRow {
        val override = overrides[gameId.value] ?: return copy(hasTitleOverride = false)
        return copy(title = override, hasTitleOverride = true)
    }

    override suspend fun hasGame(gameId: GameId): Boolean =
        SEED_GAMES.values.flatten().any { it.gameId == gameId }

    /**
     * A fake pass: reports what scanning this corpus *would* have seen, without touching anything. The
     * real impl (Eng A) recognizes → dedups → persists; here we simply echo an honest, consistent count.
     */
    override suspend fun rescan(rootUri: String): ScanResult {
        val allGames = SEED_GAMES.values.flatten()
        val filesSeen = allGames.sumOf { it.memberCount } // every collapsed member is a file on disk
        return ScanResult(
            filesSeen = filesSeen,
            recognized = filesSeen,
            groups = allGames.size,
            skipped = 0,
        )
    }

    private companion object {
        // ── Platforms (only those with games appear on the shelf) ──
        val SEED_PLATFORMS: List<PlatformRow> = listOf(
            PlatformRow(PlatformId.PS1, "PlayStation", gameCount = 3),
            PlatformRow(PlatformId.SNES, "Super Nintendo", gameCount = 3),
            PlatformRow(PlatformId.GBA, "Game Boy Advance", gameCount = 2),
            PlatformRow(PlatformId.N64, "Nintendo 64", gameCount = 2),
            PlatformRow(PlatformId.DREAMCAST, "Dreamcast", gameCount = 1),
        )

        // ── Dedup-collapsed games per platform (one row per GROUP) ──
        // memberCount>1 => multi-disc or region/version fold => the tile shows a badge.
        val SEED_GAMES: Map<PlatformId, List<GameRow>> = mapOf(
            PlatformId.PS1 to listOf(
                // Multi-disc set (2 discs behind an .m3u) → "2 discs" badge.
                GameRow(GameId("ps1/final-fantasy-vii"), "Final Fantasy VII", PlatformId.PS1, memberCount = 3, DuplicateGroupKind.MULTI_DISC, discCount = 2),
                // Region/version fold (USA + PAL) → "2 versions" badge.
                GameRow(GameId("ps1/crash-bandicoot"), "Crash Bandicoot", PlatformId.PS1, memberCount = 2, DuplicateGroupKind.CATALOG_TITLE_ID),
                // Singleton (no badge).
                GameRow(GameId("ps1/tekken-3"), "Tekken 3", PlatformId.PS1, memberCount = 1, null),
            ),
            PlatformId.SNES to listOf(
                GameRow(GameId("snes/super-metroid"), "Super Metroid", PlatformId.SNES, memberCount = 1, null),
                GameRow(GameId("snes/chrono-trigger"), "Chrono Trigger", PlatformId.SNES, memberCount = 1, null),
                // Cross-storage duplicate collapsed (SD copy wins, internal hidden) → "2 versions".
                GameRow(GameId("snes/zelda-a-link-to-the-past"), "The Legend of Zelda: A Link to the Past", PlatformId.SNES, memberCount = 2, DuplicateGroupKind.CROSS_STORAGE),
            ),
            PlatformId.GBA to listOf(
                // Region fold (Ruby USA + EUR).
                GameRow(GameId("gba/pokemon-ruby"), "Pokémon Ruby", PlatformId.GBA, memberCount = 2, DuplicateGroupKind.CATALOG_TITLE_ID),
                GameRow(GameId("gba/metroid-fusion"), "Metroid Fusion", PlatformId.GBA, memberCount = 1, null),
            ),
            PlatformId.N64 to listOf(
                GameRow(GameId("n64/ocarina-of-time"), "The Legend of Zelda: Ocarina of Time", PlatformId.N64, memberCount = 1, null),
                GameRow(GameId("n64/mario-64"), "Super Mario 64", PlatformId.N64, memberCount = 1, null),
            ),
            PlatformId.DREAMCAST to listOf(
                // Multi-disc (4 discs behind an .m3u) → "4 discs".
                GameRow(GameId("dreamcast/skies-of-arcadia"), "Skies of Arcadia", PlatformId.DREAMCAST, memberCount = 4, DuplicateGroupKind.MULTI_DISC, discCount = 4),
            ),
        )

        // ── Source folders (friendly paths only) ──
        val SEED_SOURCES: List<SourceRow> = listOf(
            SourceRow(
                friendlyPath = "SD card › Mimir › Roms",
                rootUri = "content://com.android.externalstorage.documents/tree/fake-sd",
                gameCount = 9,
                available = true,
                isPrimary = true,
            ),
            SourceRow(
                friendlyPath = "Internal › Mimir › Roms",
                rootUri = "content://com.android.externalstorage.documents/tree/fake-internal",
                gameCount = 2,
                available = true,
            ),
        )
    }
}
