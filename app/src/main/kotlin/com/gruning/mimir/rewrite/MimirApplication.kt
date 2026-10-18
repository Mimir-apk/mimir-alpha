package com.gruning.mimir.rewrite

import android.app.Application
import android.util.Log
import com.gruning.mimir.rewrite.core.audio.SystemEqualizer
import com.gruning.mimir.rewrite.core.model.library.CoverOverrideSource
import com.gruning.mimir.rewrite.core.model.library.EnrichAttemptSource
import com.gruning.mimir.rewrite.core.resolver.library.LibraryStore
import com.gruning.mimir.rewrite.core.storage.MimirDatabaseFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Initializes process-owned companion recovery before either Activity can consume a return Intent. */
class MimirApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // EP-B6: Application startup also runs for a WorkManager-only process wake, before any Activity.
        // Retire the unique periodic catalog request shipped by older builds here; the worker's independent
        // authority gate prevents URL access if cancellation races an already-dispatched legacy invocation.
        DatRefreshWorker.revokeLegacyAutomaticSchedule(this)
        // R01 S4: start every known prefs file's async disk load FIRST — the pending-operation read inside
        // services()/create() below and MainActivity's serial store loads then block far less (or not at
        // all). Non-blocking by contract; see MimirPrefsPrefetch.
        MimirPrefsPrefetch.warm(this)
        // The Equalizer's global engine: the stored switch and curve are re-applied here, at every process start, because an effect on the mix
        // ends with the process that made it. It is held for the process's life by SystemEqualizer (not by the music service,
        // which stops whenever Your music is not the source) and starts before the database gate below: it needs no database.
        // The work is on its own scope, off the main thread; with the switch off nothing is created.
        SystemEqualizer.start(this)
        // Emulator definitions: install the stored copy from disk (never the network; a fetch is the player's press in Settings).
        EmulatorDefinitionsRuntime.start(this)
        // The player's explicitly chosen shelf face (policy 2026-08-25, R5; widened 2026-10-10: one shelf face for
        // EVERY platform). Registered in Application rather than in an Activity because the library projection is
        // built lazily by whatever touches it first -- which on a WorkManager-only process wake is not an Activity at
        // all, and a face picked by hand must not silently revert to the automatic one in a background projection.
        //
        // The answer is [shelfFaceOverride]: his pick (L1) and, only when the game has no cover, the automatic
        // SteamGridDB art (L3), each with its SHAPE. The store answers null for every game he has not picked for and
        // that has no such art, so this is inert until he uses it. It reads files already on the device and fetches
        // nothing (no network on scan or launch).
        LibraryStore.coverOverrideSource = CoverOverrideSource { gameId, hasAutomaticCover ->
            shelfFaceOverride(context = this, gameId = gameId, hasAutomaticCover = hasAutomaticCover)
        }
        // How the last per-game Enrich ended : a finished attempt hides Enrich, a failed one keeps it. Registered
        // here for the same reason as the face above, so a background projection never sees a game as never enriched.
        EnrichAttemptStore.attach(this)
        LibraryStore.enrichAttemptSource = EnrichAttemptSource { gameId -> EnrichAttemptStore.outcomeFor(gameId) }
        // Process-owned Tweaker recovery cannot depend on an Activity being created. This also covers
        // service/WorkManager process starts and runs before a database-version refusal returns below.
        //
        // Lane mem (2026-10-14): unless this start is the lowmemorykiller's doing. On the Thor an Eden load takes
        // Mimir, the daemon and its guardian together; the platform relaunches the display-4 floor at once, and this
        // bind (BIND_AUTO_CREATE) respawned the two Tweaker processes into the same crunch, where they died again
        // within the second (daemon 6, guardian 6 across the day's two loads). A start within a minute of a
        // low-memory exit attaches the bridge
        // now and binds after a bounded deferral, or when MainActivity is created, whichever comes first.
        TweakerBridge.attach(this)
        MimirLowMemoryRecovery.startTweakerBridge(this, MimirLowMemoryRecovery.detect(this))
        // EP-07: Application startup used to touch Room through identity replay before MainActivity
        // could render an incompatible-database refusal. Open once here, before any process service
        // borrows the database. A newer schema leaves every byte untouched and defers to the Close-only
        // Activity gate.
        DatabaseStartupGateState.failure = MimirDatabaseFactory.startupFailure(this)
        // The player's language (Settings > Appearance > Language) into its snapshot state, before the gate's return below: a refused
        // database still shows the Close-only screen, and every Activity reads the stored choice itself in attachBaseContext. On API 33+
        // this reads Android's per-app language back.
        MimirLanguageStore.load(this)
        // Round 5c: the Bottom wears the player's theme from process start, not Default until Main's first push. The
        // seed sits before the gate's return below, because a refused database still has its floor restarted on
        // display 4, and before CompanionProcessRuntime.services, which starts the companion runtime. MimirPrefsPrefetch
        // above has been loading the appearance file since the top of onCreate. Pinned by CompanionThemeSeedTest.
        seedCompanionThemeAtProcessStart(this)
        DatabaseStartupGateState.failure?.let { failure ->
            Log.e(
                "MimirDatabaseGate",
                "startup refused database reason=${failure.reason} " +
                    "schema=${failure.foundSchemaVersion} " +
                    "supported=${failure.supportedSchemaVersion}",
            )
            return
        }
        // A media activation spans renderer bytes, explicit selection, and its Saved catalog. Recover the
        // durable pre-mutation snapshot before any Activity or companion renderer can observe those files.
        val mediaRecovered = runBlocking(Dispatchers.IO) {
            EnrichSavedStore.recoverInterruptedRendererMutation(this@MimirApplication)
        }
        if (mediaRecovered) {
            // Room v9 rewrites only state-adoption-critical documents here; media/cooldown replay stays on IO.
            GameIdentityReplayRuntime.replayBeforeStateLoad(this)
        } else {
            // The renderer read barrier now projects the durable prior snapshot. Do not let a separate
            // identity replay mutate those same media authorities until the rollback is reconciled.
            Log.e("MimirEnrichSaved", "startup media rollback pending; prior media projection is active")
        }
        CompanionProcessRuntime.services(this)
        // the performance card's memory of the games Mimir launched survives a process death. Started after
        // services() because its expiry probe asks the vendor gateway they build; the read and the writes are on IO.
        MimirLaunchedGamesPersistence.start(this)
        if (mediaRecovered) GameIdentityReplayRuntime.start(this)
    }

    /**
     * Lane mem (2026-10-14): the first memory callback this process answers. Coil trims its own cache from
     * TRIM_MEMORY_RUNNING_LOW; the art caches it does not own (shelf heroes, Bottom covers and icons, picker icons)
     * are released here from the same level, so a game's load spike finds less to take.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MimirMemoryRelease.onTrimMemory(this, level)
    }
}

/**
 * Round 5c: process start hands the player's appearance to the Bottom before any component can draw it.
 *
 * `Application.onCreate` is the only code Android runs before every component of this process, and after a death, an
 * install or a boot the platform restarts the display-4 floor (`CompanionSecondaryHomeActivity`) before any MainActivity
 * exists. Only `MainActivity.onCreate` loaded these stores, so everything the Bottom drew before Main's first payload
 * push wore the Default look:
 * - [ThemeStore]: the payload's theme key stayed `""`. The floor's cover, the cold-start and reclaim holds the
 *   Presentation's covers wear, and the restore guard's ground (`CompanionReturnActivity`) all resolve from that key.
 * - [AppearanceOverrideStore]: the accent stop, accent temperatures and ring stops `MimirTheme` applies over the palette.
 *   The Bottom's covers paint the accent tokens they retint.
 *
 * Each store loads into its own process-wide holder, the one MainActivity reloads later, and the payload takes the
 * store's key through [CompanionState.seedTheme], which never replaces a payload Main has pushed. The three stores share
 * one prefs file. The first read waits on this thread for SharedPreferences' loader thread to finish parsing it (the
 * prefetch started that load at the top of onCreate); the other two read it from memory. CompanionThemeSeedTest measures
 * that cold load on the JVM; `readUs` in the log line is what the three loads took on the device.
 */
internal fun seedCompanionThemeAtProcessStart(context: android.content.Context) {
    val readStart = System.nanoTime()
    ThemeStore.load(context)
    AppearanceOverrideStore.load(context)
    val readMicros = (System.nanoTime() - readStart) / 1_000L
    val themeKey = ThemeStore.themeId.value.storageKey
    val seeded = CompanionState.seedTheme(themeKey)
    Log.i(
        "MimirTheme",
        "process start theme=$themeKey payload=${if (seeded) "seeded" else "kept"} readUs=$readMicros",
    )
}
