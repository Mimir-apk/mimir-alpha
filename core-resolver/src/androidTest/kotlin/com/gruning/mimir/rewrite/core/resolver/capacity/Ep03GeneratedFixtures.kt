package com.gruning.mimir.rewrite.core.resolver.capacity

import com.gruning.mimir.rewrite.core.resolver.scan.WalkedFile

internal object Ep03GeneratedFixtures {
    const val SEED: Long = 0x4D49_4D49_525F_4550L

    enum class Order {
        FORWARD,
        REVERSE,
        PERMUTED,
    }

    fun walkedFiles(count: Int, order: Order): List<WalkedFile> =
        List(count) { position ->
            val index = indexAt(position, count, order)
            val relativePath = relativePath(index)
            WalkedFile(
                displayName = displayName(index),
                relativePath = relativePath,
                sizeBytes = 32_768L + index,
                documentUri = "content://$AUTHORITY/document/${documentId(index)}",
                filePath = null,
            )
        }

    fun indexAt(position: Int, count: Int, order: Order): Int {
        require(position in 0 until count)
        return when (order) {
            Order.FORWARD -> position
            Order.REVERSE -> count - 1 - position
            Order.PERMUTED -> {
                val step = coprimeStep(count)
                val offset = Math.floorMod(SEED, count.toLong()).toInt()
                ((position.toLong() * step.toLong() + offset.toLong()) % count.toLong()).toInt()
            }
        }
    }

    fun documentId(index: Int): String = "game-$index"

    fun displayName(index: Int): String = "${index.toString().padStart(6, '0')}.nes"

    fun relativePath(index: Int): String = displayName(index)

    private fun coprimeStep(count: Int): Int {
        var candidate = Math.floorMod(SEED ushr 16, count.toLong()).toInt().coerceAtLeast(1)
        while (greatestCommonDivisor(candidate, count) != 1) {
            candidate += 1
            if (candidate == count) candidate = 1
        }
        return candidate
    }

    private fun greatestCommonDivisor(left: Int, right: Int): Int {
        var a = left
        var b = right
        while (b != 0) {
            val remainder = a % b
            a = b
            b = remainder
        }
        return a
    }

    const val AUTHORITY = "com.gruning.mimir.rewrite.core.resolver.ep03.documents"
}
