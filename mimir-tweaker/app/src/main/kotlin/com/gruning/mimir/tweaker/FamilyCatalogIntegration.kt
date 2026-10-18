package com.gruning.mimir.tweaker

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the family catalog comes alive in a running process: the one [FamilyCatalogStore] per
 * directory, the sink the fetcher stages into, and the promotion the service calls at an idle apply.
 *
 * Three callers share one store instance on purpose. The store's methods are `@Synchronized` per
 * instance, so the fetcher thread (staging), the application (loading) and the service thread
 * (promoting) must not each hold their own over the same files.
 */
internal object FamilyCatalogStores {
    private val stores = ConcurrentHashMap<String, FamilyCatalogStore>()

    /**
     * The process's store, rooted at `<device-protected filesDir>/family_catalog`. The daemon is
     * direct-boot aware, so the directory must be readable before first unlock: it is the same
     * device-protected area the stock-ceiling baseline and the vendor-mode origin records use.
     * The bundled catalog is the APK asset.
     */
    fun forContext(context: Context): FamilyCatalogStore {
        val base = context.applicationContext ?: context
        val protectedContext = if (base.isDeviceProtectedStorage) base else base.createDeviceProtectedStorageContext()
        return forDirectory(
            directory = File(protectedContext.filesDir, FamilyCatalogStore.DIRECTORY),
            bundledBytes = FamilyCatalogStore.assetReader(base),
            publicKeyBase64 = BuildConfig.FAMILY_CATALOG_PUBLIC_KEY_BASE64,
        )
    }

    /** The store for [directory], created once; tests pass a temp directory and their own key. */
    internal fun forDirectory(
        directory: File,
        bundledBytes: () -> ByteArray?,
        publicKeyBase64: String,
    ): FamilyCatalogStore = stores.computeIfAbsent(directory.absoluteFile.path) {
        FamilyCatalogStore(directory, bundledBytes, activeGate = activeGate(publicKeyBase64))
    }

    /**
     * The load-time trust gate for an `active.json`. `treatment` is an admission authority, so an
     * active catalog is used only if its stored `.sig` verifies, under the compiled-in key, against
     * the exact bytes on disk. With **no key compiled in** (live fetch disabled) the gate refuses
     * every active catalog: nothing could have been staged legitimately without a key, so a file
     * found there is not trusted and the bundled catalog (trusted by the APK's own signature) wins.
     */
    internal fun activeGate(publicKeyBase64: String): (ByteArray, ByteArray?) -> Boolean {
        val publicKey = FamilyCatalogSignature.decodePublicKey(publicKeyBase64)
        return { bytes, signature ->
            publicKey != null && signature != null && FamilyCatalogSignature.verify(publicKey, bytes, signature)
        }
    }

    internal fun clearForTest() = stores.clear()
}

/**
 * [VerifiedCatalogSink] over the store. Downloads **stage**, they never promote: promotion is
 * [FamilyCatalogPromotion]'s, at an idle apply.
 */
internal class FamilyCatalogStoreSink(
    private val store: FamilyCatalogStore,
    private val log: (String) -> Unit = { line -> runCatching { Log.i(FamilyCatalogFetcher.TAG, line) } },
) : VerifiedCatalogSink {
    /** The hash of the bytes [FamilyCatalogStore.load] actually chose (the bundled asset when no active file beats it). */
    override fun activeSha256Hex(): String? = store.load().sha256Hex

    override fun promoteVerified(bytes: ByteArray, signature: ByteArray): Boolean =
        when (val result = store.stageVerified(bytes, signature)) {
            is FamilyCatalogStore.StageResult.Staged -> {
                log("family catalog revision ${result.revision} staged; it takes effect at the next idle apply")
                true
            }
            is FamilyCatalogStore.StageResult.Unchanged -> true
            is FamilyCatalogStore.StageResult.NotNewer -> {
                log("family catalog declined: not newer than revision ${result.floorRevision}")
                false
            }
            is FamilyCatalogStore.StageResult.Invalid -> {
                log("family catalog declined: ${result.reason} ${result.detail}")
                false
            }
            FamilyCatalogStore.StageResult.WriteFailed -> {
                log("family catalog declined: the staging write failed")
                false
            }
        }
}

/**
 * The `:guardian` process's half of the shared catalog. The main process mints its device profile from the
 * catalog in force and may promote a downloaded one at an idle apply; the guardian restores a manual fan only
 * through a profile minted the same way, so it must read the same catalog. It never fetches and never promotes
 * (a promotion needs the idle proof only the main process has); it re-reads the store when it is armed, which
 * is always after any promotion for that session (promotion happens only while idle, before an arm).
 *
 * When the revision moved since the last install, the probe's cached static topology (which holds the device
 * profile) is dropped so the next probe resolves again. Unchanged, nothing is invalidated: the topology
 * rediscovery costs seconds the restore path would pay. Never throws: an arm must not fail over an optional catalog.
 */
internal object GuardianFamilyCatalog {
    /** True when the store was read and what it chose installed (changed or not); false only on an unexpected failure. */
    fun installAtArm(
        store: FamilyCatalogStore,
        invalidate: () -> Unit,
        log: (String) -> Unit = { line -> runCatching { Log.i(FamilyCatalogFetcher.TAG, line) } },
    ): Boolean = try {
        val before = FamilyCatalogRegistry.current()?.revision
        val loaded = FamilyCatalogRegistry.installFrom(store)
        val after = loaded.catalog?.revision
        if (before != after) {
            runCatching { invalidate() }
            log("guardian family catalog: revision ${before ?: "none"} -> ${after ?: "none"} from ${loaded.source}")
        }
        true
    } catch (t: Throwable) {
        runCatching { Log.w(FamilyCatalogFetcher.TAG, "guardian family catalog not refreshed: ${t.javaClass.simpleName}") }
        false
    }

    /** [installAtArm] over the process's store, for the guardian service. */
    fun installAtArm(context: Context, invalidate: () -> Unit): Boolean {
        val store = try {
            FamilyCatalogStores.forContext(context)
        } catch (t: Throwable) {
            runCatching { Log.w(FamilyCatalogFetcher.TAG, "family catalog store unavailable: ${t.javaClass.simpleName}") }
            return false
        }
        return installAtArm(store, invalidate)
    }
}

/**
 * Promotes a staged catalog at the next idle apply, then makes the process use it.
 *
 * The service calls this ahead of an apply: never while a
 * session is active, because a family id is hashed into the hardware fingerprint and journal identity
 * and must not change under a live session. After a promotion, in this order:
 * 1. the registry takes the freshly loaded catalog (so any re-resolution sees it);
 * 2. [SupportedUncertifiedProfiles]' memo is cleared (it is bound to the catalog instance it was filled under,
 *    so the new catalog is already a miss; the explicit reset keeps the contract plain);
 * 3. [invalidate] runs (`probe.invalidateStaticTopology()`), which drops the cached
 *    `hardwareFamilyId` / `profileTreatment` so the next probe resolves again.
 *
 * Cheap when nothing is staged (one `isFile`). Never throws into the caller: an apply must not fail
 * because of an optional catalog refresh.
 */
internal object FamilyCatalogPromotion {
    /**
     * Idle means no live session AND no journal on disk. A journal that awaits restore (a crashed daemon's
     * session, or one that cannot be read) still owns a hardware fingerprint and plan hash minted from the
     * family ids in force when it was written, and the restore and the Guardian replay compare against them: a
     * promotion under it would change the ids that identity was hashed from. This is the same "a journal exists"
     * truth [TransactionCoordinator] answers `SESSION_ACTIVE` with, so a corrupt journal blocks promotion too
     * (F-P3-5, 2026-10-13 review).
     */
    fun isIdle(sessionActive: Boolean, journal: JournalLoadResult): Boolean =
        !sessionActive && journal == JournalLoadResult.Empty

    /** True only when a staged catalog was promoted and installed in this call. */
    fun promoteIfIdle(
        store: FamilyCatalogStore,
        isIdle: () -> Boolean,
        invalidate: () -> Unit,
        clearMemos: () -> Unit = SupportedUncertifiedProfiles::clearComputeMemo,
        log: (String) -> Unit = { line -> runCatching { Log.i(FamilyCatalogFetcher.TAG, line) } },
    ): Boolean = try {
        when (val result = store.promoteStagedIfIdle(isIdle)) {
            is FamilyCatalogStore.PromoteResult.Promoted -> {
                val loaded = FamilyCatalogRegistry.installFrom(store)
                // Each step is independent: a failing memo clear must not skip the invalidation.
                runCatching { clearMemos() }
                runCatching { invalidate() }
                log("family catalog revision ${result.revision} promoted; now in force from ${loaded.source}")
                true
            }
            else -> false
        }
    } catch (t: Throwable) {
        runCatching { Log.w(FamilyCatalogFetcher.TAG, "family catalog promotion failed: ${t.javaClass.simpleName}") }
        false
    }

    /** [promoteIfIdle] over the process's store, for the service. */
    fun promoteIfIdle(context: Context, isIdle: () -> Boolean, invalidate: () -> Unit): Boolean {
        val store = try {
            FamilyCatalogStores.forContext(context)
        } catch (t: Throwable) {
            runCatching { Log.w(FamilyCatalogFetcher.TAG, "family catalog store unavailable: ${t.javaClass.simpleName}") }
            return false
        }
        return promoteIfIdle(store, isIdle, invalidate)
    }
}
