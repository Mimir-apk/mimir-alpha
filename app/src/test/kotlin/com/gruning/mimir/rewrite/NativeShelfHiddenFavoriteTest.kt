package com.gruning.mimir.rewrite

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import com.gruning.mimir.rewrite.feature.library.NativeShelfAddReason
import com.gruning.mimir.rewrite.feature.library.NativeShelfKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A hidden Android game keeps its star, and comes back from Add game with it (a restored game comes
 * back as it was, star included). The hide used to take the package out of the favourites set in the same write, so a
 * game the player added back returned without its star.
 *
 * Driven through the real preferences and the real snapshot loader, with two launcher games installed in Robolectric's
 * PackageManager: every reader of an Android star (the Android games shelf, Favorites' Android segments, the Favorites
 * count, Home's Favorite lane) reads it from the snapshot's rows, so a package missing from those rows is missing from
 * all of them. The loader is called directly, never the coalesced one, whose cache outlives a test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NativeShelfHiddenFavoriteTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun installLauncherGames() {
        // Structurally the intent the loader asks with: MAIN + LAUNCHER, no package and no component.
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packageManager = shadowOf(context.packageManager)
        packageManager.addResolveInfoForIntent(launcher, launcherGame(QUEST, "Quest"))
        packageManager.addResolveInfoForIntent(launcher, launcherGame(RACER, "Racer"))
    }

    @Test
    fun `a hidden Android favourite is on no shelf and in no count, and keeps its star for the way back`() {
        assertTrue(setNativeShelfFavorite(context, QUEST, favorite = true))
        assertTrue(setNativeShelfFavorite(context, RACER, favorite = true))
        val before = loadNativeShelfSnapshot(context)
        assertTrue("starred on the Android games shelf first", before.games.single { it.packageName == QUEST }.isFavorite)

        setNativeShelfExcluded(context, QUEST, excluded = true)

        val hidden = loadNativeShelfSnapshot(context)
        assertTrue("off the Android games shelf", hidden.games.none { it.packageName == QUEST })
        assertTrue("and off the Android apps shelf", hidden.apps.none { it.packageName == QUEST })
        assertEquals("Favorites' Android games and its count", listOf(RACER), hidden.games.filter { it.isFavorite }.map { it.packageName })
        assertTrue("Favorites' Android apps", hidden.apps.none { it.isFavorite })
        assertEquals("Home's Favorite lane", listOf(RACER), homeFavoritePackages(hidden))
        assertEquals(
            "the Add game picker offers it back",
            NativeShelfAddReason.HIDDEN_HERE,
            hidden.addToGames.single { it.packageName == QUEST }.reason,
        )
        val state = loadNativeShelfUserState(context)
        assertTrue(QUEST in state.excluded)
        assertTrue("its star waits for it", QUEST in state.favorites)
    }

    @Test
    fun `a hidden Android favourite added back from Add game comes back starred`() {
        assertTrue(setNativeShelfFavorite(context, QUEST, favorite = true))
        setNativeShelfExcluded(context, QUEST, excluded = true)
        assertTrue("hidden first", loadNativeShelfSnapshot(context).games.none { it.packageName == QUEST })

        // The picker's choice: the only call that un-hides a package.
        addNativeShelfPackageToKind(context, QUEST, NativeShelfKind.GAME)

        val back = loadNativeShelfSnapshot(context)
        assertTrue("back on the Android games shelf, starred", back.games.single { it.packageName == QUEST }.isFavorite)
        assertTrue("no longer offered by the picker", back.addToGames.none { it.packageName == QUEST })
        assertEquals("and back in Home's Favorite lane", listOf(QUEST), homeFavoritePackages(back))
    }

    private fun homeFavoritePackages(snapshot: NativeShelfSnapshot): List<String> =
        favoriteHomeGames(emptyList(), snapshot.games).map { (it.id as FavoriteMemberId.AndroidGame).packageName.value }

    /** A launcher activity the loader buckets as a game by its category; Robolectric keeps a non-null ApplicationInfo. */
    private fun launcherGame(packageName: String, label: String) = ResolveInfo().apply {
        nonLocalizedLabel = label
        activityInfo = ActivityInfo().apply {
            this.packageName = packageName
            name = "$packageName.MainActivity"
            exported = true
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                category = ApplicationInfo.CATEGORY_GAME
            }
        }
    }

    private companion object {
        const val QUEST = "com.example.quest"
        const val RACER = "com.example.racer"
    }
}
