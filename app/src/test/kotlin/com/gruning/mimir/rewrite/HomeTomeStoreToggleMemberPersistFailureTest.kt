package com.gruning.mimir.rewrite

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.gruning.mimir.rewrite.core.model.home.AndroidPackageName
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * N5 site #1 (`app/HomeCollectionStore.kt:186`):
 * `toggleMember` used to discard `persist(...)`'s Boolean with an explicit trailing `Unit`, unlike
 * every sibling mutator on `HomeTomeStore` (`create`/`rename`/`delete`/`setPinned`/...), all of which
 * surface that Boolean to their caller. N5 made it log a warning on a refused write instead of
 * silently discarding it, but left it returning `Unit` (no caller-visible result — its only call site
 * was a fire-and-forget `scope.launch {}` with no callback).
 *
 * O-7  finishes the job: `toggleMember` now returns
 * the same `Boolean` every sibling mutator does, so `HomeShelfCoordinator.toggleTomeMembership` and the
 * Home tile menu's Tome checkbox can revert an optimistic flip the save never actually persisted.
 *
 * `persist()`'s only real failure mode is `SharedPreferences.Editor.commit()` returning false — forced
 * here with a fake `Context`/`SharedPreferences` (the same `getApplicationContext()`-must-return-`this`
 * trick `EnrichSavedStoreAnimatedClipPreviewTest`'s neighbors use for Context-requiring Robolectric
 * tests), not mocked at a Kotlin function boundary.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class HomeTomeStoreToggleMemberPersistFailureTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        app.getSharedPreferences("mimir_home_tomes", Context.MODE_PRIVATE).edit().clear().commit()
        ShadowLog.clear()
    }

    /**
     * Kotlin interface delegation (`by delegate`) forwards each call to the real editor, but a fluent
     * `putX(...)` returns *the delegate's own* return value (the real editor), not this wrapper — so
     * without overriding every `putX`/`remove`/`clear` to return `this`, a chained
     * `.edit().putString(...).commit()` would silently commit on the real editor and this fake would
     * never be reached. Only `commit()` lies; every mutation still really happens underneath.
     */
    private class FailingCommitEditor(
        private val delegate: SharedPreferences.Editor,
    ) : SharedPreferences.Editor by delegate {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            delegate.putString(key, value)
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            delegate.putStringSet(key, values)
            return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            delegate.putInt(key, value)
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            delegate.putLong(key, value)
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            delegate.putFloat(key, value)
            return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            delegate.putBoolean(key, value)
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            delegate.remove(key)
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            delegate.clear()
            return this
        }
        override fun commit(): Boolean = false
    }

    private class FailingCommitPrefs(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor = FailingCommitEditor(delegate.edit())
    }

    /**
     * Only the Tome prefs file fails to commit; everything else (reads, other prefs files) passes
     * through untouched. `getApplicationContext()` must resolve back to `this`, not the real
     * `Application`, because `persist()` reaches `SharedPreferences` through
     * `context.applicationContext`.
     */
    private class FailingTomePersistContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            val real = baseContext.getSharedPreferences(name, mode)
            return if (name == "mimir_home_tomes") FailingCommitPrefs(real) else real
        }
    }

    @Test
    fun `a refused Tome membership write is logged, not silently discarded`() = runTest {
        val tomeId = (HomeTomeStore.create(app, "Cabinet") as HomeTomeEditResult.Saved).tomeId
        val member = FavoriteMemberId.AndroidGame(AndroidPackageName("com.example.game"))
        val failing = FailingTomePersistContext(app)

        val saved = HomeTomeStore.toggleMember(failing, tomeId, member)

        assertFalse("a refused commit() must surface false to the caller", saved)
        val logged = ShadowLog.getLogs().any { entry ->
            entry.tag == "MimirHomeTomeStore" &&
                entry.msg == "toggleMember: Tome membership change for $tomeId was not saved"
        }
        assertTrue(
            "expected a warning that the Tome membership write was refused; logs were: " +
                ShadowLog.getLogs().map { "${it.tag}: ${it.msg}" },
            logged,
        )
        // Healthy-path truth must not have moved: a commit() that returned false must not be reflected
        // in the in-memory document either.
        val tome = HomeTomeStore.document.value.customTomes.first { it.id == tomeId }
        assertFalse(member in tome.members)
    }

    @Test
    fun `the healthy path still toggles membership and logs nothing`() = runTest {
        val tomeId = (HomeTomeStore.create(app, "Cabinet") as HomeTomeEditResult.Saved).tomeId
        val member = FavoriteMemberId.AndroidGame(AndroidPackageName("com.example.game"))

        val saved = HomeTomeStore.toggleMember(app, tomeId, member)

        assertTrue("a successful commit() must surface true to the caller", saved)
        val tome = HomeTomeStore.document.value.customTomes.first { it.id == tomeId }
        assertTrue(member in tome.members)
        assertTrue(ShadowLog.getLogs().none { it.tag == "MimirHomeTomeStore" })
    }
}
