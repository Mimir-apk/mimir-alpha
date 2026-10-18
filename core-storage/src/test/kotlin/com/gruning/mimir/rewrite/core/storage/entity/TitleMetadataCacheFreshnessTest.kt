package com.gruning.mimir.rewrite.core.storage.entity

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the boundary rule for [TitleMetadataCacheEntity.freshOrNull] (2026-08-20 read-enforcement of
 * [TitleMetadataCacheEntity.cacheExpiresAt]) in isolation from any DAO/Room plumbing — a pure function of
 * its two inputs, so every case here is deterministic regardless of wall-clock time.
 *
 * The `null`-passthrough case is the every-row-today case: IGDB (the only source this field was written
 * for) is not wired up, and ScreenScraper/Steam never set it, so every real row in the product has
 * `cacheExpiresAt = null` right now. If that case ever returns anything but the same row unchanged, the
 * product breaks.
 */
class TitleMetadataCacheFreshnessTest {

    @Test fun `null cacheExpiresAt always reads as fresh, at any clock value`() {
        val row = row(cacheExpiresAt = null)

        assertSame(row, row.freshOrNull(nowMillis = 0L))
        assertSame(row, row.freshOrNull(nowMillis = Long.MAX_VALUE))
        assertSame(row, row.freshOrNull(nowMillis = 1_755_648_000_000L)) // 2026-08-20, real "now"
    }

    @Test fun `a future cacheExpiresAt reads as fresh`() {
        val row = row(cacheExpiresAt = 1_000L)

        assertSame(row, row.freshOrNull(nowMillis = 999L))
    }

    @Test fun `a past cacheExpiresAt reads as absent`() {
        val row = row(cacheExpiresAt = 1_000L)

        assertNull(row.freshOrNull(nowMillis = 1_001L))
    }

    @Test fun `cacheExpiresAt exactly equal to now reads as absent, the conservative boundary`() {
        val row = row(cacheExpiresAt = 1_000L)

        assertNull(row.freshOrNull(nowMillis = 1_000L))
    }

    @Test fun `the same row flips fresh to absent purely by which nowMillis is passed, never by wall time`() {
        val row = row(cacheExpiresAt = 150L)

        assertSame(row, row.freshOrNull(nowMillis = 100L))
        assertNull(row.freshOrNull(nowMillis = 200L))
    }

    private fun row(cacheExpiresAt: Long?) = TitleMetadataCacheEntity(
        catalogKey = "game:g1",
        platformId = "dreamcast",
        canonicalTitle = "Sonic Adventure",
        region = "USA",
        version = null,
        cachedAtMillis = 1L,
        cacheExpiresAt = cacheExpiresAt,
    )
}
