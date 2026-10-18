package com.gruning.mimir.rewrite.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dedup grouping derivations grounded in dedup-grouping-rulings. */
class GameGroupTest {

    private val ps1 = PlatformId.PS1
    private val gc = PlatformId.GAMECUBE

    private fun file(
        key: String,
        kind: ContentKind = ContentKind.GAME,
        disc: Int = 0,
        size: Long = 100L,
        location: ResidentLocationKind = ResidentLocationKind.SAF_TREE_GRANT,
    ) = ResidentFile(
        entryKey = EntryKey(key),
        platformId = ps1,
        contentKind = kind,
        displayName = key,
        location = location,
        sizeBytes = size,
        discNumber = disc,
    )

    private fun identity(gid: String, title: String) =
        GameIdentity(gameId = GameId(gid), title = title, source = IdentitySource.CATALOG)

    @Test
    fun singleFileGroupIsNotCollapsedAndHasNoBadge() {
        val g = GameGroup(
            gameId = GameId("g:lone"),
            groupKind = DuplicateGroupKind.EXACT_HASH,
            identity = identity("g:lone", "Lone Game"),
            primary = file("lone.iso"),
        )
        assertFalse(g.isCollapsed)
        assertEquals(1, g.memberCount)
        assertNull(g.collapseBadge)
    }

    @Test
    fun multiDiscM3uPrimaryFoldsToOneTileWithDiscBadge() {
        // Playlist plumbing is not a physical disc: m3u + Disc1 + Disc2 → ONE tile "2 discs".
        val m3u = file("chrono.m3u", kind = ContentKind.MULTI_DISC_PLAYLIST)
        val disc1 = file("chrono_d1.chd", kind = ContentKind.DISC_MEMBER, disc = 1, size = 500L)
        val disc2 = file("chrono_d2.chd", kind = ContentKind.DISC_MEMBER, disc = 2, size = 480L)
        val g = GameGroup(
            gameId = GameId("g:chrono"),
            groupKind = DuplicateGroupKind.MULTI_DISC,
            identity = identity("g:chrono", "Chrono Cross"),
            primary = m3u,
            hiddenMembers = listOf(disc1, disc2),
        )
        assertTrue(g.isCollapsed)
        assertTrue(g.isMultiDisc)
        assertEquals(3, g.memberCount)
        assertEquals("2 discs", g.collapseBadge)
        // Launch starts from the .m3u playlist (emulator handles swap).
        assertEquals(EntryKey("chrono.m3u"), g.launchMember.entryKey)
    }

    @Test
    fun multiDiscWithoutM3uLaunchesLowestDiscPresent() {
        val disc1 = file("re2_d1.chd", kind = ContentKind.DISC_MEMBER, disc = 1)
        val disc2 = file("re2_d2.chd", kind = ContentKind.DISC_MEMBER, disc = 2)
        val g = GameGroup(
            gameId = GameId("g:re2"),
            groupKind = DuplicateGroupKind.MULTI_DISC,
            identity = identity("g:re2", "Resident Evil 2"),
            primary = disc1,
            hiddenMembers = listOf(disc2),
        )
        assertEquals(EntryKey("re2_d1.chd"), g.launchMember.entryKey)
        assertEquals("2 discs", g.collapseBadge)
    }

    @Test
    fun loneDiscTwoStaysLaunchable() {
        // Edge case: RE Code Veronica X has ONLY Disc 2 — the lone disc is the primary and launches.
        val disc2 = file("cvx_d2.chd", kind = ContentKind.DISC_MEMBER, disc = 2)
        val g = GameGroup(
            gameId = GameId("g:cvx"),
            groupKind = DuplicateGroupKind.MULTI_DISC,
            identity = identity("g:cvx", "RE Code Veronica X"),
            primary = disc2,
        )
        assertFalse(g.isCollapsed)
        assertEquals(EntryKey("cvx_d2.chd"), g.launchMember.entryKey)
    }

    @Test
    fun regionVersionVariantsFoldWithVersionsBadge() {
        // Pokémon Diamond USA (Rev 5) + Europe (Rev 13) → ONE tile "2 versions".
        val usa = file("diamond_usa.nds")
        val eu = file("diamond_eu.nds")
        val g = GameGroup(
            gameId = GameId("g:diamond"),
            groupKind = DuplicateGroupKind.CATALOG_TITLE_ID,
            identity = GameIdentity(GameId("g:diamond"), "Pokémon Diamond", region = "USA", version = "Rev 5"),
            primary = usa,
            hiddenMembers = listOf(eu),
        )
        assertTrue(g.isCollapsed)
        assertFalse(g.isMultiDisc)
        assertEquals("2 versions", g.collapseBadge)
    }

    @Test
    fun crossStorageLoserIsHiddenAsCrossStorageDuplicateNotRestorable() {
        // Same game on SD + internal → collapse, prefer SD; internal hidden non-destructively.
        val sd = file("sonic_sd.iso", location = ResidentLocationKind.SAF_TREE_GRANT)
        val internal = file("sonic_internal.iso", location = ResidentLocationKind.MANAGED_PATH)
        val g = GameGroup(
            gameId = GameId("g:sonic"),
            groupKind = DuplicateGroupKind.CROSS_STORAGE,
            identity = identity("g:sonic", "Sonic Adventure 2"),
            primary = sd,
            hiddenMembers = listOf(internal),
        )
        val primaryState = g.hiddenStateFor(sd)
        val loserState = g.hiddenStateFor(internal)

        assertFalse(primaryState.hidden)
        assertTrue(primaryState.isPrimaryMember)

        assertTrue(loserState.hidden)
        assertFalse(loserState.isPrimaryMember)
        assertEquals(HideReason.CROSS_STORAGE_DUPLICATE, loserState.reason)
        // A dedup loser must NOT show in Browse restore (dedup-grouping-rulings N-DEDUP-1).
        assertFalse(loserState.eligibleForRestore)
    }

    @Test
    fun groupIdentityMustMatchGameId() {
        val ex = runCatching {
            GameGroup(
                gameId = GameId("g:a"),
                groupKind = DuplicateGroupKind.EXACT_HASH,
                identity = identity("g:b", "Mismatch"),
                primary = file("x.iso"),
            )
        }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
    }
}
