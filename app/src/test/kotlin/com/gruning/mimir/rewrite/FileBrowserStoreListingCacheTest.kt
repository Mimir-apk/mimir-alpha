package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The cache may never outlive a change to the files it describes.**
 *
 * `FileBrowserStore` remembers each directory's rows so that backing out of a folder costs nothing —
 * avoiding a measured ~90 ms of parked main thread on
 * the Odin. A memo over a filesystem is only safe while something evicts it, and the failure mode is not
 * a slow browser: it is a browser that shows the player a file they just deleted, or hides one they just
 * restored. That is a lie about their files, which this project ranks above every performance property
 * it has.
 *
 * **Source-level, following `FileBrowserStorePickSafetyTest`'s own idiom and carrying the same disclosed
 * gap:** [FileBrowserStore] is an `object` with process-lifetime state and a `Context` requirement, so a
 * JVM unit test cannot drive it through a real session without a Robolectric harness this module does not
 * have. **This proves the eviction is written, not that it executes.** What makes that worth pinning
 * anyway is the shape of the risk: every eviction site here is a line that a future author could delete
 * while every test in the tree stayed green, because nothing else in the system can see a stale memo.
 *
 * The executable half of this seam lives in `feature-files`' `FileBrowserAsyncLoadTest`, which drives the
 * controller's async load end to end against a gated fake filesystem.
 */
class FileBrowserStoreListingCacheTest {

    /**
     * The store's source with every comment removed.
     *
     * Not tidiness: this file's own KDoc names `listingCache` in prose several times, and a body that
     * runs up to the next function's signature swallows that function's doc comment. Matching against
     * stripped source is what makes every assertion below a statement about CODE.
     */
    private val source: String by lazy {
        val stripped = appSource("FileBrowserStore.kt").readText()
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("(?m)//.*$"), "")
        // FileBrowserStore.kt also declares FileBrowserChecksumRequestCoordinator (the checksum-verify
        // request coordinator, added 2026-09-13) ahead of the object, at the same 4-space member indent
        // this file's function scan uses. Left unscoped, that sibling class's last function absorbs
        // every property declared before the object's own first function -- including `listingCache`
        // itself -- into its "body", which is a parsing artifact, not a real toucher of the cache. Start
        // the scan at the object so this pin is only ever about FileBrowserStore's own functions.
        val objectStart = stripped.indexOf("internal object FileBrowserStore")
        assertTrue("FileBrowserStore object not found in FileBrowserStore.kt", objectStart >= 0)
        stripped.substring(objectStart)
    }

    /**
     * Every function that changes the filesystem, or that publishes such a change, paired with why its
     * eviction cannot be dropped.
     *
     * `enqueue` and `setState` are the two choke points: every stage, completion, conflict resolution and
     * failure in this store lands on one of them, which is why the delete/move/copy/extract entry points
     * are NOT listed individually — they route here, and a test that listed them all would be pinning the
     * call graph rather than the property.
     */
    private val evictionSites = mapOf(
        "private fun enqueue(" to
            "staging moves the payload the moment an operation starts, so the directory is already " +
                "different by the time the card says 'working'",
        "private fun setState(" to
            "every completion, failure and conflict resolution passes through here",
        "fun undo(" to
            "an undo puts files BACK; a cache still holding the post-delete listing would hand them " +
                "straight back out as missing",
        "fun releaseUndo(" to
            "finalising empties .mimir-staging, which lives inside a directory this cache may describe",
        "private fun sweepInterruptedOperations(" to
            "a recovery restores or removes real files and then calls refresh() -- evicting after that " +
                "refresh would re-serve exactly the listing the recovery invalidated",
        "private fun clearSession(" to
            "between two browser sessions the player has been away for an unbounded time, in other apps",
    )

    @Test
    fun `every filesystem-changing site in the store evicts the listing cache`() {
        val missing = evictionSites.keys.filterNot { site -> bodyOf(site).contains("evictListings()") }

        assertTrue(
            "these sites change the player's files and leave the cache describing the old ones:\n" +
                missing.joinToString("\n") { "  $it -- ${evictionSites[it]}" },
            missing.isEmpty(),
        )
    }

    @Test
    fun `the recovery sweep evicts BEFORE it publishes, not after`() {
        val body = bodyOf("private fun sweepInterruptedOperations(")
        val evict = body.indexOf("evictListings()")
        val refresh = body.indexOf("refresh()")

        assertTrue("both must be present for the ordering to mean anything", evict >= 0 && refresh >= 0)
        assertTrue(
            "refresh() re-reads THROUGH the cache. Evicting after it publishes the stale listing and " +
                "then quietly fixes the memo nobody is looking at any more.",
            evict < refresh,
        )
    }

    @Test
    fun `undo evicts before it refreshes, for the same reason`() {
        val body = bodyOf("fun undo(")
        val evict = body.indexOf("evictListings()")
        val refresh = body.indexOf("refresh()")

        assertTrue(evict >= 0 && refresh >= 0)
        assertTrue("a restored file must not be re-served as deleted", evict < refresh)
    }

    @Test
    fun `the synchronous first listing populates the cache, so the very first Up is already a hit`() {
        val body = bodyOf("private fun listDirectory(")

        assertTrue(
            "the first load is deliberately synchronous (see FileBrowserController.hasListed). If it " +
                "did not write what it read, the first back-out would pay the full read this whole " +
                "change exists to remove.",
            body.contains("listingCache[path] = it"),
        )
    }

    @Test
    fun `the async listing is the only one allowed to serve from memory, and it revalidates when it does`() {
        val body = bodyOf("private suspend fun listDirectoryAsync(")

        assertTrue("a hit must be served", body.contains("listingCache[path]"))
        assertTrue(
            "serving from memory without a background re-read would make changes made by anything " +
                "outside this process -- a companion app, a USB host, the player's PC -- permanently " +
                "invisible, which is a correctness regression sold as a perf win",
            body.contains("revalidate(path)"),
        )
        assertTrue("a miss must write what it read", body.contains("listingCache[path] = it"))
    }

    @Test
    fun `no other function may touch the cache`() {
        val allowed = setOf(
            "private fun listDirectory(",
            "private suspend fun listDirectoryAsync(",
            "private fun revalidate(",
            "private fun evictListings(",
        )
        val touching = functionStarts()
            .filter { (signature, _) -> bodyOf(signature).contains("listingCache") }
            .map { it.first }
            .filterNot { it in allowed }

        assertEquals(
            "the cache is main-confined by construction -- every reader and writer runs on the store's " +
                "Main scope. A new toucher is not necessarily wrong, but it is a threading decision, and " +
                "this pin is what makes it a deliberate one.",
            emptyList<String>(),
            touching,
        )
    }

    /** Source text from a function's signature to the start of the next top-level `fun`/`val` in the file. */
    private fun bodyOf(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature not found in FileBrowserStore.kt", start >= 0)
        val next = functionStarts()
            .map { it.second }
            .filter { it > start }
            .minOrNull() ?: source.length
        return source.substring(start, next)
    }

    /** Every `fun` declaration in the file, as (signature-prefix, offset), in source order. */
    private fun functionStarts(): List<Pair<String, Int>> =
        Regex("(?m)^\\s{4}(?:internal |private |public )?(?:suspend )?fun [A-Za-z]+\\(")
            .findAll(source)
            .map { it.value.trim() to it.range.first }
            .toList()

    private fun appSource(fileName: String): File = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
    ).firstOrNull { it.isFile }
        ?: throw AssertionError("$fileName not reachable from the test working directory")
}
