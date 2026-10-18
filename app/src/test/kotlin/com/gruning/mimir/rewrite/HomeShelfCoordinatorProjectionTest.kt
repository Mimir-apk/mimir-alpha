package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfMembership
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.feature.library.HomeShelfEntry
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import com.gruning.mimir.rewrite.feature.library.HomeShelfTome
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioFocusMemory
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioFocusTarget
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioMode
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioPageSize
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioUiState
import com.gruning.mimir.rewrite.feature.library.HomeTomeMember
import com.gruning.mimir.rewrite.feature.library.homeTomeMemberFocusKey
import com.gruning.mimir.rewrite.feature.library.NativeShelfRow
import com.gruning.mimir.rewrite.feature.library.TomeId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeShelfCoordinatorProjectionTest {
    private val snes = PlatformId("snes")
    private val psx = PlatformId("psx")

    @Test
    fun `custom membership copies a Favorite into the Tome and keeps it on loose Home`() {
        val custom = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000099"))
        val games = favoriteHomeGames(listOf(game("alpha", snes), game("beta", psx)), emptyList())
        val member = FavoriteMemberId.Emulated(GameId("alpha"))
        val withTome = projectFavoriteHome(
            games,
            HomeTomeDocument(customTomes = listOf(CustomHomeTome(custom, "Cabinet", listOf(member)))),
        )

        // OR-1 : copy-like. The member stays on Editor's Choice, in the same order as with no Tome.
        assertEquals(listOf("alpha", "beta"), orderedLooseHomeGames(withTome).map { it.row.title })
        assertEquals(member, favoriteHomeTomeCatalog(games, withTome.document).eligibleMembersByTome.getValue(custom).single())
        assertEquals(
            listOf("alpha", "beta"),
            orderedLooseHomeGames(projectFavoriteHome(games, HomeTomeDocument())).map { it.row.title },
        )
    }

    @Test
    fun `latest played remains the first loose Home tile while also filed in a Tome`() {
        val custom = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000098"))
        val games = favoriteHomeGames(
            listOf(game("alpha", snes, 50L), game("beta", psx, 10L)),
            emptyList(),
        )
        val recent = FavoriteMemberId.Emulated(GameId("alpha"))

        val projection = projectFavoriteHome(
            games,
            HomeTomeDocument(customTomes = listOf(CustomHomeTome(custom, "Cabinet", listOf(recent)))),
        )

        assertEquals(listOf("alpha", "beta"), orderedLooseHomeGames(projection).map { it.row.title })
    }

    @Test
    fun `stale platform pins never reappear in custom Tome mode`() {
        val custom = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000097"))
        val entries = listOf(
            HomeShelfEntry.Tome(TomeId.Platform(snes)),
            HomeShelfEntry.Tome(custom),
        )

        assertEquals(listOf(HomeShelfEntry.Tome(custom)), customPinnedHomeTomeEntries(entries))
    }

    @Test
    fun `loose Home promotes only the newest game and jumps occupied normalized buckets without wrap`() {
        val games = favoriteHomeGames(
            listOf(game("zulu", snes, 10L), game("Éclair", psx, 20L), game("9 lives", snes), game("beta", psx, 5L)),
            emptyList(),
        )
        val projection = projectFavoriteHome(games, HomeTomeDocument())
        val ordered = orderedLooseHomeGames(projection)

        assertEquals(listOf("Éclair", "9 lives", "beta", "zulu"), ordered.map { it.row.title })
        assertEquals("E", looseHomeAlphabetBucket("Éclair"))
        assertEquals("#", looseHomeAlphabetBucket("9 lives"))
        assertEquals("#", looseHomeAlphabetBucket("Ωmega"))
        assertEquals(1, looseHomeAlphabetJumpIndex(ordered, 0, forward = true, recentItemId = ordered.first().id))
        assertEquals(0, looseHomeAlphabetJumpIndex(ordered, 3, forward = true, recentItemId = ordered.first().id))
    }

    @Test
    fun `alphabet jump reverses across occupied buckets and stops at both boundaries`() {
        val ordered = orderedLooseHomeGames(
            projectFavoriteHome(
                favoriteHomeGames(
                    listOf(
                        game("zulu", snes, 10L),
                        game("Éclair", psx, 20L),
                        game("9 lives", snes),
                        game("beta", psx, 5L),
                    ),
                    emptyList(),
                ),
                HomeTomeDocument(),
            ),
        )
        val recentId = ordered.first().id

        assertEquals(2, looseHomeAlphabetJumpIndex(ordered, 3, forward = false, recentItemId = recentId))
        assertEquals(1, looseHomeAlphabetJumpIndex(ordered, 2, forward = false, recentItemId = recentId))
        assertEquals(0, looseHomeAlphabetJumpIndex(ordered, 1, forward = false, recentItemId = recentId))
        assertEquals(0, looseHomeAlphabetJumpIndex(ordered, 3, forward = true, recentItemId = recentId))
    }

    @Test
    fun `alphabet feedback expiry is latest wins`() {
        val feedback = HomeAlphabetJumpFeedback()

        feedback.publish("B")
        val firstEpoch = feedback.notice!!.epoch
        feedback.publish("Z")
        val latestEpoch = feedback.notice!!.epoch
        feedback.expire(firstEpoch)

        assertEquals(HomeAlphabetJumpNotice("Z", latestEpoch), feedback.notice)
        feedback.expire(latestEpoch)
        assertNull(feedback.notice)
    }

    @Test
    fun `most recent explicit Hero or Hero Motion choice owns Home presentation`() {
        val heroOld = GameIdentityMediaSelectionRecord(GameIdentityMediaSelection.SELECTED, 10L)
        val heroNew = heroOld.copy(changedAtMillis = 30L)
        val motion = GameIdentityMediaSelectionRecord(GameIdentityMediaSelection.SELECTED, 20L)

        assertTrue(homeChosenMotionWins(heroOld, motion))
        assertFalse(homeChosenMotionWins(heroNew, motion))
        assertFalse(homeChosenMotionWins(heroNew, motion.copy(changedAtMillis = 30L)))
        assertTrue(
            homeChosenMotionWins(
                heroNew,
                motion.copy(changedAtMillis = 31L),
            ),
        )
        assertFalse(
            homeChosenMotionWins(
                heroNew,
                motion.copy(selection = GameIdentityMediaSelection.AUTOMATIC),
            ),
        )
    }

    @Test
    fun `hasOwnCoverArt -- any emulated row's own non-blank artworkUri counts, an Android icon does not`() {
        val pcRow = HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("pc/alpha")),
            rank = 0L,
            addedAtMillis = 0L,
            title = "Alpha",
            platformId = PlatformId.PC_GAMES,
        )

        assertTrue(pcRow.copy(artworkUri = "file:///rom/pc/alpha.png").hasOwnCoverArt())
        assertFalse("null artworkUri is absent, not a cover", pcRow.copy(artworkUri = null).hasOwnCoverArt())
        assertFalse("blank/whitespace-only artworkUri is absent, matching the Library idiom", pcRow.copy(artworkUri = "   ").hasOwnCoverArt())
        // A scraped box is a cover for every emulated row, regardless of platform.
        assertTrue(
            "a scraped box on any platform is a cover an automatic tile must not displace",
            pcRow.copy(platformId = snes, artworkUri = "file:///scraped/cover.png").hasOwnCoverArt(),
        )
        // Unchanged, and the reason the predicate reads the item KIND rather than artworkUri alone: an
        // Android row's artworkUri is its launcher icon. Treat that as a cover and every app tile blanks.
        assertFalse(
            "an Android row's launcher icon is not a cover",
            pcRow.copy(
                itemId = HomeShelfItemId.AndroidGame(AndroidPackageName("com.example.app")),
                platformId = null,
                artworkUri = "content://launcher/icon",
            ).hasOwnCoverArt(),
        )
    }

    @Test
    fun `input projection preserves a HAT move made before destination composition settles`() {
        val first = HomeShelfEntry.Game(HomeShelfItemId.Emulated(GameId("first")))
        val second = HomeShelfEntry.Game(HomeShelfItemId.Emulated(GameId("second")))
        val authority = HomeInputProjectionAuthority()

        authority.publish(
            mode = HomeViewMode.PINNED_TOMES,
            entries = listOf(first, second),
            focusKey = first.focusKey,
            epoch = 7,
        )
        authority.updateFocus(second.focusKey)
        // Compose arrives with the originally published key. The same-epoch reconcile must not pull
        // logical input backward after the user's press has already committed.
        authority.reconcile(
            mode = HomeViewMode.PINNED_TOMES,
            entries = listOf(first, second),
            focusKey = first.focusKey,
            epoch = 7,
        )

        assertEquals(second.focusKey, authority.snapshot().focusKey)
        assertEquals(7, authority.snapshot().epoch)
    }

    @Test
    fun `alphabet burst synchronously keeps the latest focus in the same projection epoch`() {
        val alpha = HomeShelfEntry.Game(HomeShelfItemId.Emulated(GameId("alpha")))
        val beta = HomeShelfEntry.Game(HomeShelfItemId.Emulated(GameId("beta")))
        val zulu = HomeShelfEntry.Game(HomeShelfItemId.Emulated(GameId("zulu")))
        val entries = listOf(alpha, beta, zulu)
        val authority = HomeInputProjectionAuthority()

        authority.publish(HomeViewMode.EDITORS_CHOICE, entries, alpha.focusKey, epoch = 12)
        authority.updateFocus(zulu.focusKey)
        assertEquals(zulu.focusKey, authority.snapshot().focusKey)
        assertEquals(12, authority.snapshot().epoch)

        // A composition write from the start of the burst cannot pull logical input backward.
        authority.reconcile(HomeViewMode.EDITORS_CHOICE, entries, alpha.focusKey, epoch = 12)
        assertEquals(zulu.focusKey, authority.snapshot().focusKey)

        authority.updateFocus(beta.focusKey)
        assertEquals(beta.focusKey, authority.snapshot().focusKey)
        assertEquals(12, authority.snapshot().epoch)
    }

    @Test
    fun `newer input projection rejects stale composition and adopts its own valid identity`() {
        val editor = HomeShelfEntry.Game(HomeShelfItemId.Emulated(GameId("editor")))
        val pinned = HomeShelfEntry.Tome(TomeId.Platform(snes))
        val authority = HomeInputProjectionAuthority()

        authority.publish(HomeViewMode.PINNED_TOMES, listOf(pinned), pinned.focusKey, epoch = 9)
        authority.reconcile(HomeViewMode.EDITORS_CHOICE, listOf(editor), editor.focusKey, epoch = 8)

        assertEquals(HomeViewMode.PINNED_TOMES, authority.snapshot().mode)
        assertEquals(pinned.focusKey, authority.snapshot().focusKey)
    }

    @Test
    fun `pinned tomes lead and overlapping members never remain loose`() {
        val platform = TomeId.Platform(snes)
        val custom = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000010"))
        val games = favoriteHomeGames(listOf(game("a", snes), game("b", psx)), emptyList())
        val projected = projectFavoriteHome(
            games,
            HomeTomeDocument(
                pinOrder = listOf(platform, custom),
                customTomes = listOf(
                    CustomHomeTome(custom, "Overlap", listOf(FavoriteMemberId.Emulated(GameId("a")))),
                ),
            ),
        )

        assertEquals(
            listOf(HomeShelfEntry.Tome(platform), HomeShelfEntry.Tome(custom), HomeShelfEntry.Game(HomeShelfItemId.Emulated(GameId("b")))),
            projected.entries,
        )
    }

    @Test
    fun homeRootEntriesForViewModeSelectsLiveRootListPerMode() {
        val editor = HomeShelfEntry.Game(HomeShelfItemId.Emulated(GameId("editor")))
        val pinned = HomeShelfEntry.Tome(TomeId.Platform(snes))
        val editorsChoice = listOf(editor)
        val pinnedTomes = listOf(pinned)

        assertEquals(
            editorsChoice,
            homeRootEntriesForViewMode(HomeViewMode.EDITORS_CHOICE, editorsChoice, pinnedTomes),
        )
        assertEquals(
            pinnedTomes,
            homeRootEntriesForViewMode(HomeViewMode.PINNED_TOMES, editorsChoice, pinnedTomes),
        )
    }

    @Test
    fun `final platform favorite normalizes and unpins before root emit`() {
        val smart = TomeId.Platform(snes)
        val projected = projectFavoriteHome(emptyList(), HomeTomeDocument(pinOrder = listOf(smart)))

        assertFalse(smart in projected.document.pinOrder)
        assertFalse(smart in projected.tomes)
        assertTrue(projected.entries.isEmpty())
    }

    @Test
    fun `pinned empty custom remains authored while dormant identities cannot launch`() {
        val custom = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000011"))
        val dormant = FavoriteMemberId.Emulated(GameId("missing"))
        val projected = projectFavoriteHome(
            games = listOf(),
            document = HomeTomeDocument(
                pinOrder = listOf(custom),
                customTomes = listOf(CustomHomeTome(custom, "Dormant", listOf(dormant))),
            ),
        )

        assertEquals(listOf(HomeShelfEntry.Tome(custom)), projected.entries)
        assertEquals(0, projected.tomes.getValue(custom).memberCount)
        assertTrue(projected.eligibleMembersByTome.getValue(custom).isEmpty())
        assertNull(projected.launchableRow(dormant))
    }

    @Test
    fun `platform overlay leads and new favorites append without play recency reshuffle`() {
        val games = favoriteHomeGames(
            listOf(
                game("a", snes, lastPlayed = 900L),
                game("b", snes, lastPlayed = 1L),
                game("c", snes, lastPlayed = 5_000L),
            ),
            emptyList(),
        )
        val smart = TomeId.Platform(snes)
        val projected = projectFavoriteHome(
            games,
            HomeTomeDocument(
                pinOrder = listOf(smart),
                platformOrderOverlays = listOf(PlatformTomeOrderOverlay(snes, listOf(GameId("b"), GameId("a")))),
            ),
        )

        assertEquals(
            listOf("b", "a", "c"),
            projected.eligibleMembersByTome.getValue(smart).map { (it as FavoriteMemberId.Emulated).gameId.value },
        )
    }

    @Test
    fun `unarranged custom Tome follows canonical Favorite order in root and browser`() {
        val custom = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000012"))
        val games = favoriteHomeGames(listOf(game("a", snes), game("b", snes), game("c", snes)), emptyList())
        val document = HomeTomeDocument(
            pinOrder = listOf(custom),
            customTomes = listOf(
                CustomHomeTome(
                    custom,
                    "Fresh",
                    listOf(
                        FavoriteMemberId.Emulated(GameId("c")),
                        FavoriteMemberId.Emulated(GameId("a")),
                    ),
                    manualOrderCommitted = false,
                ),
            ),
        )

        val expected = listOf("a", "c")
        assertEquals(expected, projectFavoriteHome(games, document).memberNames(custom))
        assertEquals(expected, favoriteHomeTomeCatalog(games, document).memberNames(custom))
    }

    @Test
    fun `arranged custom Tome preserves manual order and filters dormant members`() {
        val custom = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000013"))
        val games = favoriteHomeGames(listOf(game("a", snes), game("b", snes), game("c", snes)), emptyList())
        val document = HomeTomeDocument(
            pinOrder = listOf(custom),
            customTomes = listOf(
                CustomHomeTome(
                    custom,
                    "Arranged",
                    listOf(
                        FavoriteMemberId.Emulated(GameId("c")),
                        FavoriteMemberId.Emulated(GameId("missing")),
                        FavoriteMemberId.Emulated(GameId("a")),
                        FavoriteMemberId.Emulated(GameId("b")),
                    ),
                    manualOrderCommitted = true,
                ),
            ),
        )

        val expected = listOf("c", "a", "b")
        assertEquals(expected, projectFavoriteHome(games, document).memberNames(custom))
        assertEquals(expected, favoriteHomeTomeCatalog(games, document).memberNames(custom))
        assertEquals(4, document.customTomes.single().members.size)
    }

    @Test
    fun `favorite feed includes android games and excludes android apps`() {
        val native = listOf(
            NativeShelfRow("com.game", "Game", null, isGame = true, isFavorite = true),
            NativeShelfRow("com.app", "App", null, isGame = false, isFavorite = true),
        )
        val games = favoriteHomeGames(emptyList(), native)

        assertEquals(listOf(FavoriteMemberId.AndroidGame(AndroidPackageName("com.game"))), games.map(FavoriteHomeGame::id))
    }

    @Test
    fun `a pinned custom Tome holds an Android game that also stays loose`() {
        val custom = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000015"))
        val android = FavoriteMemberId.AndroidGame(AndroidPackageName("com.game"))
        val games = favoriteHomeGames(
            emulatedFavorites = emptyList(),
            nativeGames = listOf(NativeShelfRow("com.game", "Game", null, isGame = true, isFavorite = true)),
        )
        val tome = CustomHomeTome(custom, "Android", members = listOf(android))

        val unpinned = projectFavoriteHome(games, HomeTomeDocument(customTomes = listOf(tome)))
        val pinned = projectFavoriteHome(
            games,
            HomeTomeDocument(pinOrder = listOf(custom), customTomes = listOf(tome)),
        )

        assertEquals(listOf(HomeShelfEntry.Game(android.toHomeShelfItemId())), unpinned.entries)
        assertEquals(listOf(HomeShelfEntry.Tome(custom)), pinned.entries)
        // OR-1 : copy-like. Pinning the Tome no longer takes the game off Editor's Choice.
        assertEquals(listOf(HomeShelfEntry.Game(android.toHomeShelfItemId())), favoriteEditorChoiceEntries(pinned))
        assertEquals(listOf(android), favoriteHomeTomeCatalog(games, pinned.document).eligibleMembersByTome[custom])
    }

    @Test
    fun `Editors Choice orders played favorites by recency without changing Tome order`() {
        val androidPackage = AndroidPackageName("com.example.recent")
        val games = favoriteHomeGames(
            emulatedFavorites = listOf(
                game("older", snes, lastPlayed = 100L),
                game("never", snes),
                game("newer", snes, lastPlayed = 300L),
                game("middle", snes, lastPlayed = 200L),
            ),
            nativeGames = listOf(
                NativeShelfRow(
                    packageName = androidPackage.value,
                    label = "Recent Android game",
                    launchActivity = null,
                    isGame = true,
                    isFavorite = true,
                ),
            ),
            nativeLastPlayedMillis = mapOf(androidPackage to 400L),
        )
        val platformTome = TomeId.Platform(snes)
        val projection = projectFavoriteHome(games, HomeTomeDocument())

        assertEquals(
            listOf("android:${androidPackage.value}", "middle", "never", "newer", "older"),
            favoriteEditorChoiceEntries(projection).map { entry ->
                when (val itemId = (entry as HomeShelfEntry.Game).itemId) {
                    is HomeShelfItemId.Emulated -> itemId.gameId.value
                    is HomeShelfItemId.AndroidGame -> "android:${itemId.packageName.value}"
                    is HomeShelfItemId.AndroidApp -> error("Android apps cannot enter Editor's Choice")
                }
            },
        )
        assertEquals(
            listOf("older", "never", "newer", "middle"),
            favoriteHomeTomeCatalog(games, HomeTomeDocument()).eligibleMembersByTome.getValue(platformTome)
                .map { (it as FavoriteMemberId.Emulated).gameId.value },
        )
    }

    @Test
    fun `Tome previews preserve typed emulated platform and leave Android untyped`() {
        val custom = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000016"))
        val emulated = game("zelda", snes).copy(artUri = "file:///zelda.png")
        val android = NativeShelfRow(
            "com.game",
            "Game",
            launchActivity = null,
            isGame = true,
            iconPath = "file:///android.png",
            isFavorite = true,
        )
        val games = favoriteHomeGames(listOf(emulated), listOf(android))
        val document = HomeTomeDocument(
            customTomes = listOf(CustomHomeTome(custom, "Mixed", games.map(FavoriteHomeGame::id))),
        )

        val previews = favoriteHomeTomeCatalog(games, document).tomes.getValue(custom).previews

        assertEquals(snes, previews.first().platformId)
        assertNull(previews.last().platformId)
    }

    @Test
    fun `legacy promotion uses resolved canonical id and only proven native game membership`() {
        val memberships = listOf(
            HomeShelfMembership(HomeShelfItemId.Emulated(GameId("raw-loser")), 0L, 0L),
            HomeShelfMembership(HomeShelfItemId.Emulated(GameId("raw-second")), 1L, 0L),
            HomeShelfMembership(HomeShelfItemId.Emulated(GameId("raw-second-duplicate")), 2L, 0L),
            HomeShelfMembership(HomeShelfItemId.AndroidGame(AndroidPackageName("com.game")), 3L, 0L),
            HomeShelfMembership(HomeShelfItemId.AndroidApp(AndroidPackageName("com.app")), 4L, 0L),
        )
        val resolved = listOf(
            com.gruning.mimir.rewrite.core.model.home.HomeShelfEntry.Emulated(game("second", snes), 2_048L, 2L),
            com.gruning.mimir.rewrite.core.model.home.HomeShelfEntry.Emulated(game("canonical", snes), 0L, 1L),
            com.gruning.mimir.rewrite.core.model.home.HomeShelfEntry.Emulated(game("second", snes), 3_072L, 3L),
        )
        val plan = planLegacyHomeFavoritePromotion(
            memberships,
            resolved,
            listOf(NativeShelfRow("com.game", "Game", null, isGame = true)),
        )

        assertEquals(
            listOf(
                LegacyEmulatedFavoritePromotion(GameId("canonical"), -1L),
                LegacyEmulatedFavoritePromotion(GameId("second"), -2L),
            ),
            plan.emulatedFavorites,
        )
        assertEquals(listOf("com.game"), plan.androidGamePackages)
    }

    @Test
    fun `platform Tome projection titles a platform with its full label in authored case`() {
        val smart = TomeId.Platform(snes)
        val projected = projectFavoriteHome(
            favoriteHomeGames(listOf(game("a", snes)), emptyList()),
            HomeTomeDocument(pinOrder = listOf(smart)),
            platformLabels = emptyMap(),
        )

        // No label given: the product's own PlatformLabels name, not an acronym and not upper-cased (policy R1).
        assertEquals("Super Nintendo", projected.tomes.getValue(smart).title)
        assertEquals("Nintendo GameCube", platformTomeTitle(PlatformId("gamecube"), "Nintendo GameCube"))
        assertEquals("Sega Dreamcast", platformTomeTitle(PlatformId("dreamcast"), "Sega Dreamcast"))
        assertEquals("Nintendo Switch", platformTomeTitle(PlatformId("switch"), "Nintendo Switch"))
    }

    @Test
    fun `pending Favorite removal fails closed until projection observes absence`() {
        val removed = HomeShelfItemId.Emulated(GameId("removed"))
        val membership = setOf(removed)

        assertFalse(canActivateFavoriteItem(removed, membership, setOf(removed)))
        assertEquals(setOf(removed), reconcilePendingFavoriteRemovals(setOf(removed), membership))
        assertTrue(reconcilePendingFavoriteRemovals(setOf(removed), emptySet()).isEmpty())

        val platformTome = TomeId.Platform(snes)
        val optimisticProjection = projectFavoriteHome(
            games = visibleFavoriteHomeGames(
                durableGames = favoriteHomeGames(listOf(game("removed", snes)), emptyList()),
                pendingRemovalIds = setOf(removed),
            ),
            document = HomeTomeDocument(pinOrder = listOf(platformTome)),
        )
        assertTrue(optimisticProjection.entries.isEmpty())
        assertFalse(platformTome in optimisticProjection.document.pinOrder)
    }

    @Test
    fun `optimistic Tome order filters removals and appends concurrent Favorites without snapping`() {
        val a = HomeShelfItemId.Emulated(GameId("a"))
        val b = HomeShelfItemId.Emulated(GameId("b"))
        val c = HomeShelfItemId.Emulated(GameId("c"))
        val added = HomeShelfItemId.Emulated(GameId("added"))

        assertEquals(
            listOf(c, a, added),
            visibleOptimisticTomeOrder(
                committedOrder = listOf(c, b, a),
                durableVisibleOrder = listOf(a, c, added),
            ),
        )
    }

    @Test
    fun `sparse Tome projection and optimistic commit retain exact empty wells`() {
        val custom = TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-000000000014"))
        val a = FavoriteMemberId.Emulated(GameId("a"))
        val b = FavoriteMemberId.Emulated(GameId("b"))
        val projected = projectFavoriteHome(
            games = favoriteHomeGames(listOf(game("a", snes), game("b", snes)), emptyList()),
            document = HomeTomeDocument(
                pinOrder = listOf(custom),
                customTomes = listOf(
                    CustomHomeTome(
                        id = custom,
                        label = "Sparse",
                        members = listOf(a, b),
                        manualOrderCommitted = true,
                        memberSlots = listOf(a, null, null, b),
                    ),
                ),
            ),
        )

        val projectedSlots = projected.eligibleMemberSlotsByTome.getValue(custom)
        assertEquals(a, projectedSlots[0])
        assertNull(projectedSlots[1])
        assertEquals(b, projectedSlots[3])
        assertEquals(HomeTomeFolioPageSize, projectedSlots.size)

        val aId = a.toHomeShelfItemId()
        val bId = b.toHomeShelfItemId()
        val added = HomeShelfItemId.Emulated(GameId("added"))
        val optimistic = visibleOptimisticTomeSlots(
            committedSlots = listOf(aId, null, null, bId) + List(HomeTomeFolioPageSize - 4) { null },
            durableVisibleSlots = listOf(aId, bId, added) + List(HomeTomeFolioPageSize - 3) { null },
        )
        assertEquals(aId, optimistic[0])
        assertEquals(added, optimistic[1])
        assertEquals(bId, optimistic[3])
    }

    @Test
    fun `buffered arrange confirm is bound to exact target carry and transaction`() {
        val target = HomeTomeFolioFocusTarget(3, FocusKey("tome/member/a"))
        val carried = HomeShelfItemId.Emulated(GameId("a"))
        val pending = PendingFolioArrangeActivation(
            target = target,
            action = ControllerAction.CONFIRM,
            mode = HomeTomeFolioMode.ARRANGE,
            carriedItemId = carried,
            transactionId = 17L,
        )
        fun current(
            liveTarget: HomeTomeFolioFocusTarget? = target,
            liveCarried: HomeShelfItemId? = carried,
            transaction: Long = 17L,
            scopeIsFolio: Boolean = true,
        ) = pendingFolioArrangeActivationIsCurrent(
            pending = pending,
            liveTarget = liveTarget,
            liveMode = HomeTomeFolioMode.ARRANGE,
            liveCarriedItemId = liveCarried,
            liveActiveTransactionId = transaction,
            liveNextTransactionId = 18L,
            scopeIsFolio = scopeIsFolio,
            focusedKey = target.key,
        )

        assertTrue(current())
        assertFalse(current(liveTarget = HomeTomeFolioFocusTarget(4, target.key)))
        assertFalse(current(liveCarried = null))
        assertFalse(current(transaction = 18L))
        assertFalse(current(scopeIsFolio = false))

        assertTrue(
            homeTomeFolioTargetIsSettledForActivation(
                liveTarget = target,
                settledTarget = target,
                scopeIsFolio = true,
                focusedKey = target.key,
            ),
        )
        assertFalse(
            homeTomeFolioTargetIsSettledForActivation(
                liveTarget = HomeTomeFolioFocusTarget(4, target.key),
                settledTarget = target,
                scopeIsFolio = true,
                focusedKey = target.key,
            ),
        )
    }

    @Test
    fun `rapid close captures current authoritative target before visual settlement callback`() {
        val tomeId = TomeId.Platform(snes)
        val ids = (0 until 8).map { HomeShelfItemId.Emulated(GameId("focus-$it")) }
        val members = ids.mapIndexed { index, itemId -> HomeTomeMember(itemId, "Game $index") }
        val slots = members.map<HomeTomeMember, HomeTomeMember?> { it } +
            List(HomeTomeFolioPageSize - members.size) { null }
        val state = HomeTomeFolioUiState(
            tome = HomeShelfTome(tomeId, "SNES", emptyList(), members.size),
            members = members,
            memberSlots = slots,
            focusedIndex = 7,
            focusSettled = false,
        )
        val target = requireNotNull(state.focusTarget)

        val capture = requireNotNull(
            authoritativeHomeTomeFocusCapture(
                state = state,
                scopeIsFolio = true,
                focusedKey = target.key,
            ),
        )

        assertEquals(tomeId, capture.tomeId)
        assertEquals(ids[7], capture.memory.itemId)
        assertEquals(7, capture.memory.seatIndex)
        val priorAuthority = requireNotNull(
            authoritativeHomeTomeFocusCapture(
                state = state,
                scopeIsFolio = true,
                focusedKey = homeTomeMemberFocusKey(ids[6]),
            ),
        )
        assertEquals(ids[6], priorAuthority.memory.itemId)
        assertEquals(6, priorAuthority.memory.seatIndex)

        val reordered = ids.toMutableList().also {
            val remembered = it.removeAt(7)
            it.add(2, remembered)
        }
        assertEquals(
            2,
            openingHomeTomeFocusIndex(
                memory = capture.memory,
                memberSlots = reordered,
                preferredIndex = 0,
            ),
        )
    }

    @Test
    fun `fixed Home Shelf Tome restores authoritative empty seat and removed game falls back nearest`() {
        val ids = listOf(
            HomeShelfItemId.Emulated(GameId("home-a")),
            HomeShelfItemId.Emulated(GameId("home-b")),
            HomeShelfItemId.Emulated(GameId("home-c")),
        )
        val members = ids.mapIndexed { index, itemId -> HomeTomeMember(itemId, "Home $index") }
        val slots = List<HomeTomeMember?>(HomeTomeFolioPageSize) { index ->
            when (index) {
                0 -> members[0]
                2 -> members[1]
                5 -> members[2]
                else -> null
            }
        }
        val state = HomeTomeFolioUiState(
            tome = HomeShelfTome(TomeId.HomeOverview, "HOME SHELF", emptyList(), members.size),
            members = members,
            memberSlots = slots,
            focusedIndex = 3,
            focusSettled = false,
        )
        val capture = requireNotNull(
            authoritativeHomeTomeFocusCapture(
                state = state,
                scopeIsFolio = true,
                focusedKey = requireNotNull(state.focusTarget).key,
            ),
        )
        val identitySlots = slots.map { it?.itemId }
        assertTrue(capture.memory.wasEmptySeat)
        assertEquals(
            3,
            openingHomeTomeFocusIndex(capture.memory, identitySlots, preferredIndex = 0),
        )

        val removed = HomeTomeFolioFocusMemory(itemId = ids[1], seatIndex = 2)
        val afterRemoval = identitySlots.map { it?.takeIf { itemId -> itemId != ids[1] } }
        assertEquals(
            0,
            openingHomeTomeFocusIndex(removed, afterRemoval, preferredIndex = 5),
        )
    }

    private fun game(id: String, platform: PlatformId, lastPlayed: Long? = null) = GameRow(
        gameId = GameId(id),
        title = id,
        platformId = platform,
        memberCount = 1,
        duplicateKind = null,
        isFavorite = true,
        lastPlayedMillis = lastPlayed,
    )

    private fun FavoriteHomeProjection.memberNames(tomeId: TomeId): List<String> =
        eligibleMembersByTome.getValue(tomeId).map { (it as FavoriteMemberId.Emulated).gameId.value }

    private fun FavoriteHomeTomeCatalog.memberNames(tomeId: TomeId): List<String> =
        eligibleMembersByTome.getValue(tomeId).map { (it as FavoriteMemberId.Emulated).gameId.value }
}
