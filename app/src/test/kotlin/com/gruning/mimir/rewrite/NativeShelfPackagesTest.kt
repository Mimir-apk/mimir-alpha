package com.gruning.mimir.rewrite

import android.content.Intent
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.feature.library.FavoriteSegment
import com.gruning.mimir.rewrite.feature.library.LibraryLaunchReturnTarget
import com.gruning.mimir.rewrite.feature.library.LibraryShelfMode
import com.gruning.mimir.rewrite.feature.library.NativeShelfAddCandidate
import com.gruning.mimir.rewrite.feature.library.NativeShelfAddReason
import com.gruning.mimir.rewrite.feature.library.NativeShelfKind
import com.gruning.mimir.rewrite.feature.library.NativeShelfRow
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeShelfPackagesTest {

    @Test
    fun nativeHeroUsesActivityBannerThenApplicationBannerAndPackageEpoch() {
        assertEquals(
            "android.resource://com.example.game/101#v=9000",
            nativeShelfHeroArtUri(
                packageName = "com.example.game",
                activityBannerResId = 101,
                applicationBannerResId = 202,
                packageEpoch = 9000L,
            ),
        )
        assertEquals(
            "android.resource://com.example.game/202#v=9000",
            nativeShelfHeroArtUri(
                packageName = "com.example.game",
                activityBannerResId = 0,
                applicationBannerResId = 202,
                packageEpoch = 9000L,
            ),
        )
        assertNull(nativeShelfHeroArtUri("com.example.game", 0, 0, 9000L))
        assertNull(nativeShelfHeroArtUri(" ", 101, 202, 9000L))
    }

    @Test
    fun launchedRowTruthControlsEditorsChoiceRecencyAcrossFavoriteSegments() {
        val game = NativeShelfRow(
            packageName = "com.example.game",
            label = "Game",
            launchActivity = null,
            isGame = true,
        )
        val app = game.copy(packageName = "com.example.app", label = "App", isGame = false)

        assertTrue(shouldRecordAndroidGameRecency(game))
        assertFalse(shouldRecordAndroidGameRecency(app))
        assertFalse(shouldRecordAndroidGameRecency(null))
    }

    @Test
    fun nativeLaunchCleansPackageNamesBeforeResolution() {
        assertEquals("com.example.app", cleanNativeShelfPackageName("  com.example.app  "))
        assertNull(cleanNativeShelfPackageName("   "))
    }

    @Test
    fun nativeLaunchFlagsMatchShippingPrimaryTaskPolicy() {
        val flags = nativeShelfLaunchFlags()

        assertTrue(flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertFalse(flags and Intent.FLAG_ACTIVITY_NO_ANIMATION != 0)
    }

    @Test
    fun nativeIconCacheUsesImmutableContentAndPackageEpochPaths() {
        val cacheDir = Files.createTempDirectory("mimir-native-icon-test").toFile()
        try {
            val firstBytes = byteArrayOf(1, 2, 3, 4)
            val first = writeVersionedNativeIcon(
                iconCacheDir = cacheDir,
                packageName = "com.example.game",
                activityName = "com.example.game.MainActivity",
                packageEpoch = 101L,
                pngBytes = firstBytes,
            )
            val same = writeVersionedNativeIcon(
                iconCacheDir = cacheDir,
                packageName = "com.example.game",
                activityName = "com.example.game.MainActivity",
                packageEpoch = 101L,
                pngBytes = firstBytes,
            )

            assertEquals(first.absolutePath, same.absolutePath)
            assertArrayEquals(firstBytes, same.readBytes())

            val changedContent = writeVersionedNativeIcon(
                iconCacheDir = cacheDir,
                packageName = "com.example.game",
                activityName = "com.example.game.MainActivity",
                packageEpoch = 101L,
                pngBytes = byteArrayOf(9, 8, 7, 6),
            )
            assertNotEquals(first.absolutePath, changedContent.absolutePath)
            assertFalse(first.exists())

            val changedPackageEpoch = writeVersionedNativeIcon(
                iconCacheDir = cacheDir,
                packageName = "com.example.game",
                activityName = "com.example.game.MainActivity",
                packageEpoch = 102L,
                pngBytes = byteArrayOf(9, 8, 7, 6),
            )
            assertNotEquals(changedContent.absolutePath, changedPackageEpoch.absolutePath)
            assertFalse(changedContent.exists())
            assertTrue(changedPackageEpoch.exists())
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun emulatorAndGamingRegistryPackagesBucketAsAndroidGames() {
        assertTrue(
            nativeShelfBucketsAsAndroidGame(
                packageName = "org.ppsspp.ppsspp",
                pmGameCategory = false,
                legacyFlagGame = false,
                intentGameCategory = false,
            ),
        )
        assertTrue(
            nativeShelfBucketsAsAndroidGame(
                packageName = "com.limelight.noir",
                pmGameCategory = false,
                legacyFlagGame = false,
                intentGameCategory = false,
            ),
        )
    }

    @Test
    fun packageManagerGameSignalsStillBucketAsAndroidGames() {
        assertTrue(
            nativeShelfBucketsAsAndroidGame(
                packageName = "com.example.sideloaded",
                pmGameCategory = true,
                legacyFlagGame = false,
                intentGameCategory = false,
            ),
        )
        assertTrue(
            nativeShelfBucketsAsAndroidGame(
                packageName = "com.example.legacygame",
                pmGameCategory = false,
                legacyFlagGame = true,
                intentGameCategory = false,
            ),
        )
        assertTrue(
            nativeShelfBucketsAsAndroidGame(
                packageName = "com.example.intentgame",
                pmGameCategory = false,
                legacyFlagGame = false,
                intentGameCategory = true,
            ),
        )
    }

    @Test
    fun genericLaunchersRemainAndroidAppsUntilPinned() {
        assertFalse(
            nativeShelfBucketsAsAndroidGame(
                packageName = "com.example.notes",
                pmGameCategory = false,
                legacyFlagGame = false,
                intentGameCategory = false,
            ),
        )
    }

    @Test
    fun nativeShelfKindLabelsMatchShippingSubtypes() {
        assertEquals(
            "Emulator",
            nativeShelfAutoKindLabel(
                packageName = "org.ppsspp.ppsspp",
                pmGameCategory = false,
                legacyFlagGame = false,
                intentGameCategory = false,
            ),
        )
        assertEquals(
            "Streaming",
            nativeShelfAutoKindLabel(
                packageName = "com.limelight.noir",
                pmGameCategory = false,
                legacyFlagGame = false,
                intentGameCategory = false,
            ),
        )
        assertEquals(
            "Launcher",
            nativeShelfAutoKindLabel(
                packageName = "gamehub.lite",
                pmGameCategory = false,
                legacyFlagGame = false,
                intentGameCategory = false,
            ),
        )
        assertEquals(
            "Game",
            nativeShelfAutoKindLabel(
                packageName = "com.example.categorygame",
                pmGameCategory = true,
                legacyFlagGame = false,
                intentGameCategory = false,
            ),
        )
        assertEquals(
            "Android app",
            nativeShelfAutoKindLabel(
                packageName = "com.example.notes",
                pmGameCategory = false,
                legacyFlagGame = false,
                intentGameCategory = false,
            ),
        )
    }

    @Test
    fun productFrontendsAreHardHiddenButEmulatorsAreNot() {
        assertTrue(nativeShelfIsHardHiddenPackage("com.gruning.mimir.rewrite", "com.gruning.mimir.rewrite"))
        assertTrue(nativeShelfIsHardHiddenPackage("org.es_de.frontend", "com.gruning.mimir.rewrite"))
        assertTrue(nativeShelfIsHardHiddenPackage("com.magneticchen.daijishou", "com.gruning.mimir.rewrite"))
        assertFalse(nativeShelfIsHardHiddenPackage("org.ppsspp.ppsspp", "com.gruning.mimir.rewrite"))
    }

    @Test
    fun nativeAddReturnFocusPrefersCurrentThenRememberedThenFirstVisibleTile() {
        val rows = listOf(
            nativeRow("com.example.alpha"),
            nativeRow("com.example.beta"),
            nativeRow("com.example.gamma"),
        )

        assertEquals(
            "com.example.beta",
            selectNativeAddReturnPackage(
                currentPackage = "com.example.beta",
                rememberedPackage = "com.example.gamma",
                rows = rows,
            ),
        )
        assertEquals(
            "com.example.gamma",
            selectNativeAddReturnPackage(
                currentPackage = "com.example.missing",
                rememberedPackage = "com.example.gamma",
                rows = rows,
            ),
        )
        assertEquals(
            "com.example.alpha",
            selectNativeAddReturnPackage(
                currentPackage = "com.example.missing",
                rememberedPackage = "com.example.also.missing",
                rows = rows,
            ),
        )
        assertNull(selectNativeAddReturnPackage(currentPackage = null, rememberedPackage = null, rows = emptyList()))
    }

    @Test
    fun nativeLaunchReturnFocusFallsBackToFirstVisibleTileWhenPackageIsGone() {
        val rows = listOf(
            nativeRow("com.example.alpha"),
            nativeRow("com.example.beta"),
        )

        assertEquals("com.example.beta", selectNativeReturnPackage("com.example.beta", rows))
        assertEquals("com.example.alpha", selectNativeReturnPackage("com.example.removed", rows))
        assertNull(selectNativeReturnPackage("com.example.removed", emptyList()))
    }

    @Test
    fun libraryLaunchReturnFocusKeysMapStableTargetsToRegisteredFocusIds() {
        assertEquals(
            FocusKey("library/native/com.example.game"),
            libraryLaunchReturnFocusKey(
                LibraryLaunchReturnTarget.shelf(
                    shelfMode = LibraryShelfMode.ANDROID_GAMES,
                    shelfKey = "com.example.game",
                ),
            ),
        )
        assertEquals(
            FocusKey("library/gamelist/ps1/final-fantasy-vii"),
            libraryLaunchReturnFocusKey(
                LibraryLaunchReturnTarget.gamelistRom(
                    openPlatformId = PlatformId.PS1,
                    romPlatformId = PlatformId.PS1,
                    gameId = GameId("ps1/final-fantasy-vii"),
                    shelfMode = LibraryShelfMode.EMULATION,
                    favoriteSegment = FavoriteSegment.GAMES,
                    searchQuery = "final",
                    favoritesOnly = false,
                ),
            ),
        )
        assertEquals(
            FocusKey("library/native/com.example.mail"),
            libraryLaunchReturnFocusKey(
                LibraryLaunchReturnTarget.gamelistNative(
                    openPlatformId = PlatformId("__favorites__"),
                    nativePackageName = "com.example.mail",
                    shelfMode = LibraryShelfMode.ANDROID_GAMES,
                    favoriteSegment = FavoriteSegment.ANDROID_APPS,
                    searchQuery = "mail",
                ),
            ),
        )
    }

    @Test
    fun nativeAddFocusRingTraversesSearchCandidatesAndCloseWithoutWrapping() {
        val ring = nativeAddFocusRing(
            listOf(
                nativeAddCandidate("com.example.alpha"),
                nativeAddCandidate("com.example.beta"),
            ),
        )
        val search = FocusKey("library/nativeAdd/search")
        val alpha = FocusKey("library/nativeAdd/com.example.alpha")
        val beta = FocusKey("library/nativeAdd/com.example.beta")
        val close = FocusKey("library/nativeAdd/close")

        assertEquals(listOf(search, alpha, beta, close), ring)
        assertEquals(alpha, nextNativeAddFocus(ring, search, ControllerAction.NAV_DOWN))
        assertEquals(beta, nextNativeAddFocus(ring, alpha, ControllerAction.NAV_RIGHT))
        assertEquals(close, nextNativeAddFocus(ring, beta, ControllerAction.NAV_DOWN))
        assertEquals(close, nextNativeAddFocus(ring, close, ControllerAction.NAV_DOWN))
        assertEquals(beta, nextNativeAddFocus(ring, close, ControllerAction.NAV_UP))
        assertEquals(search, nextNativeAddFocus(ring, search, ControllerAction.NAV_UP))
    }

    @Test
    fun nativeAddFocusRingFallsBackWhenFilteredListRemovesFocusedCandidate() {
        val filteredRing = nativeAddFocusRing(emptyList())
        val removedFocus = FocusKey("library/nativeAdd/com.example.removed")

        assertEquals(
            FocusKey("library/nativeAdd/close"),
            nextNativeAddFocus(filteredRing, removedFocus, ControllerAction.NAV_DOWN),
        )
        assertEquals(
            FocusKey("library/nativeAdd/search"),
            nextNativeAddFocus(filteredRing, removedFocus, ControllerAction.NAV_UP),
        )
    }

    @Test
    fun nativeLaunchReturnRestoringSwallowsFirstInputUntilSettled() {
        assertTrue(nativeLaunchReturnShouldSwallowInput(restoring = true, action = ControllerAction.NAV_RIGHT))
        assertTrue(nativeLaunchReturnShouldSwallowInput(restoring = true, action = ControllerAction.CONFIRM))
        assertFalse(nativeLaunchReturnShouldSwallowInput(restoring = false, action = ControllerAction.NAV_RIGHT))
    }

    @Test
    fun nativeLaunchReturnAcknowledgementCannotClearNewerAnchor() {
        val older = MimirNativeLaunchReturnRequest(
            mode = LibraryShelfMode.ANDROID_GAMES,
            packageName = "com.example.older",
            token = "older",
        )
        val newer = MimirNativeLaunchReturnRequest(
            mode = LibraryShelfMode.ANDROID_GAMES,
            packageName = "com.example.newer",
            token = "newer",
        )

        assertTrue(canAcknowledgeMimirNativeLaunchReturn(older, older))
        assertFalse(canAcknowledgeMimirNativeLaunchReturn(older, newer))
        assertFalse(canAcknowledgeMimirNativeLaunchReturn(older, null))
    }

    private fun nativeRow(packageName: String): NativeShelfRow =
        NativeShelfRow(
            packageName = packageName,
            label = packageName.substringAfterLast('.'),
            launchActivity = null,
            isGame = false,
        )

    private fun nativeAddCandidate(packageName: String): NativeShelfAddCandidate =
        NativeShelfAddCandidate(
            packageName = packageName,
            label = packageName.substringAfterLast('.'),
            targetKind = NativeShelfKind.APP,
            sourceKind = NativeShelfKind.GAME,
            reason = NativeShelfAddReason.MOVE_FROM_OTHER_SHELF,
            isHidden = false,
        )
}
