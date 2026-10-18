package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Competitor-tracker row 39 (second half): "a same-name replacement is not rehashed" (iiSU the earlier update).
 *
 * RetroAchievements file verdicts are cached under [retroAchievementsFileKey], which carries the file's size
 * and modified time next to its identity, so a ROM replaced under the same name reads as a different key and
 * is resolved afresh. A file whose modified time is unknown has no key at all (never one made of its size alone), so it is hashed afresh each time. The zipped-versus-loose hash itself is pinned by
 * RetroAchievementsTest.singleEntryZipHashesIdenticallyToTheLooseFile in core-metadata.
 */
class TrackerRaFileKeyTest {

    private val path = "/storage/6A7F-FCFE/ROMs/SNES/Mario.sfc"
    private val document =
        "content://com.android.externalstorage.documents/tree/6A7F-FCFE%3AROMs/" +
            "document/6A7F-FCFE%3AROMs%2FSNES%2FMario.sfc"

    @Test
    fun aSameNameReplacementWithANewSizeOrANewTimeIsADifferentKey() {
        val original = retroAchievementsFileKey(null, path, 524_288L, 1_700_000_000_000L)

        assertEquals("an unchanged file keeps its key", original, retroAchievementsFileKey(null, path, 524_288L, 1_700_000_000_000L))
        assertNotEquals("a bigger replacement", original, retroAchievementsFileKey(null, path, 1_048_576L, 1_700_000_000_000L))
        assertNotEquals("same size, rewritten later", original, retroAchievementsFileKey(null, path, 524_288L, 1_700_000_999_000L))
    }

    @Test
    fun aSameSizeReplacementOfAFileWhoseTimeIsUnknownIsNeverKeyedBySizeAlone() {
        // A provider that reports no modified time (0, or a failed query's -1) used to leave "identity|size": the replacement kept the old file's key and its cached verdict.
        assertNull(retroAchievementsFileKey(null, path, 524_288L, 0L))
        assertNull(retroAchievementsFileKey(document, path, 524_288L, -1L))

        // So the lane's cache is never consulted for it, and the file is hashed afresh; a file with a time is cached as before.
        val cache = RetroAchievementsVerdictCache()
        val known = retroAchievementsFileKey(null, path, 524_288L, 1_700_000_000_000L)!!
        cache.put(known, RetroAchievementsVerdict.Matched(7))
        val replaced = retroAchievementsFileKey(null, path, 524_288L, 1_700_000_999_000L)!!
        assertNull("a same-size replacement with a new time is not served the old verdict", cache.get(replaced))
        assertEquals(RetroAchievementsVerdict.Matched(7), cache.get(known))
    }

    @Test
    fun theSafDocumentIsTheIdentityWhenThereIsOneAndNoLocatorMeansNoKey() {
        val viaSaf = requireNotNull(retroAchievementsFileKey(document, path, 524_288L, 1L))
        assertEquals(true, viaSaf.startsWith(document))

        assertNull(retroAchievementsFileKey(null, null, 524_288L, 1L))
        assertNull(retroAchievementsFileKey("", " ".trim(), 524_288L, 1L))
    }
}
