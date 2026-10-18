package com.gruning.mimir.rewrite.core.resolver.capacity

import com.gruning.mimir.rewrite.core.resolver.capacity.Ep03GeneratedFixtures.Order
import com.gruning.mimir.rewrite.core.resolver.scan.SourceTraversalGuard
import com.gruning.mimir.rewrite.core.resolver.scan.SourceTraversalRejectedException
import com.gruning.mimir.rewrite.core.resolver.scan.SourceWalkLimits
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Ep03FixtureDeterminismTest {

    @Test
    fun `100k fixture is bijective and enumeration-order independent`() {
        val forward = Ep03GeneratedFixtures.indices(LIBRARY_SIZE, Order.FORWARD)
        val reverse = Ep03GeneratedFixtures.indices(LIBRARY_SIZE, Order.REVERSE)
        val permuted = Ep03GeneratedFixtures.indices(LIBRARY_SIZE, Order.PERMUTED)

        assertEquals(LIBRARY_SIZE, permuted.toSet().size)
        assertEquals(forwardDigest(forward), canonicalDigest(reverse))
        assertEquals(forwardDigest(forward), canonicalDigest(permuted))
    }

    @Test
    fun `traversal guard admits 100k and rejects N plus one without widening the ceiling`() {
        val exact = SourceTraversalGuard(SourceWalkLimits(maxDepth = 1, maxEntries = LIBRARY_SIZE))
        exact.beginRoot("root")
        repeat(LIBRARY_SIZE) { index ->
            exact.observeNode(
                identity = Ep03GeneratedFixtures.documentId(index),
                relativePath = Ep03GeneratedFixtures.relativePath(index),
                depth = 1,
                isDirectory = false,
            )
        }

        val exceeded = SourceTraversalGuard(SourceWalkLimits(maxDepth = 1, maxEntries = LIBRARY_SIZE))
        exceeded.beginRoot("root")
        var rejected = false
        try {
            repeat(LIBRARY_SIZE + 1) { index ->
                exceeded.observeNode(
                    identity = Ep03GeneratedFixtures.documentId(index),
                    relativePath = Ep03GeneratedFixtures.relativePath(index),
                    depth = 1,
                    isDirectory = false,
                )
            }
        } catch (_: SourceTraversalRejectedException) {
            rejected = true
        }
        assertTrue("100,001st entry must be rejected", rejected)
    }

    private fun canonicalDigest(indices: List<Int>): String = forwardDigest(indices.sorted())

    private fun forwardDigest(indices: List<Int>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        indices.forEach { index ->
            digest.update(Ep03GeneratedFixtures.documentId(index).toByteArray())
            digest.update(0)
            digest.update(Ep03GeneratedFixtures.relativePath(index).toByteArray())
            digest.update('\n'.code.toByte())
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val LIBRARY_SIZE = 100_000
    }
}
