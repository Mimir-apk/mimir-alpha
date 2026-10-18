package com.gruning.mimir.rewrite

import android.os.Environment
import java.util.concurrent.atomic.AtomicBoolean

/**
 * When a storage volume coming back must make the art memo forget what it learned while the volume was away.
 *
 * `LocalArtSource` memoises every probe for the life of the process, misses included, and a plain-file probe is
 * always "settled" -- an unmounted card answers "no cover" exactly like a card with none. Mimir is the home app, so at
 * boot the library draws before the SD card has mounted, every cover on it is memoised as absent, and nothing ever
 * asks again: the shelves stay on their glyphs until the process dies (the same boot-time blank cards other frontends
 * report: Pegasus #1125, Cocoon D#275, RetroHrai the earlier update). The availability flip already survives a card coming back
 * (`StorageVolumeMonitor`); the art memo did not.
 *
 * Two things say the memo is stale, and either is enough:
 *  - the reconcile **restored games**, which only happens when a root's volume was absent and is present again; or
 *  - a volume **reported itself mounted** since the last reconcile -- which also covers the games that never left
 *    `AVAILABLE` because the card mounted before the first reconcile ran.
 *
 * It drops the WHOLE memo rather than one volume's. A memo key is a document URI or a path, and attributing one to a
 * volume would mean parsing both shapes; the drop is cheap, happens once per mount storm, and re-resolves lazily.
 */
internal class StorageMountArtRefresh {

    private val mountedSinceLastReconcile = AtomicBoolean(false)

    /** A volume callback arrived. Only the transition INTO mounted can have brought art back. */
    fun onVolumeState(state: String?) {
        if (state == Environment.MEDIA_MOUNTED) mountedSinceLastReconcile.set(true)
    }

    /**
     * Called once a reconcile has committed. Runs [dropArtMemo] when games came back or a volume mounted since the last
     * reconcile, and says whether it did. A failing [dropArtMemo] rethrows, and the mount is remembered for the next pass.
     */
    fun afterReconcile(restoredGames: Int, dropArtMemo: () -> Unit): Boolean {
        val mounted = mountedSinceLastReconcile.getAndSet(false)
        if (restoredGames <= 0 && !mounted) return false
        try {
            dropArtMemo()
        } catch (failure: Throwable) {
            if (mounted) mountedSinceLastReconcile.set(true)
            throw failure
        }
        return true
    }
}
