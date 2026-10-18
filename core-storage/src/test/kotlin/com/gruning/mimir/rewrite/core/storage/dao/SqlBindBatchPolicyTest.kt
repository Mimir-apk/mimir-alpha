package com.gruning.mimir.rewrite.core.storage.dao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlBindBatchPolicyTest {

    @Test
    fun `double-list batches stay below the API 30 portable variable ceiling`() {
        assertTrue(DOUBLE_IN_QUERY_CHUNK_SIZE * 2 < SQLITE_API_30_VARIABLE_FLOOR)
        assertEquals(listOf(400, 100), List(500) { it }.chunked(DOUBLE_IN_QUERY_CHUNK_SIZE).map(List<*>::size))
        assertEquals(
            listOf(400, 400, 400, 400, 400, 1),
            List(2_001) { it }.chunked(DOUBLE_IN_QUERY_CHUNK_SIZE).map(List<*>::size),
        )
    }

    @Test
    fun `single-list restore batches pathological group fanout`() {
        assertTrue(SINGLE_IN_QUERY_CHUNK_SIZE < SQLITE_API_30_VARIABLE_FLOOR)
        assertEquals(
            listOf(900, 100),
            List(1_000) { it }.chunked(SINGLE_IN_QUERY_CHUNK_SIZE).map(List<*>::size),
        )
    }

    private companion object {
        const val SQLITE_API_30_VARIABLE_FLOOR = 999
    }
}
