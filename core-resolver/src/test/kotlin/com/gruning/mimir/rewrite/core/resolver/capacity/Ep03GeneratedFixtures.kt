package com.gruning.mimir.rewrite.core.resolver.capacity

import com.gruning.mimir.rewrite.core.resolver.scan.WalkedFile

internal object Ep03GeneratedFixtures {
    const val SEED: Long = 0x4D49_4D49_525F_4550L
    const val PLATFORM = "nes"

    enum class Order {
        FORWARD,
        REVERSE,
        PERMUTED,
    }

    fun walkedFiles(count: Int, order: Order = Order.FORWARD): List<WalkedFile> =
        indices(count, order).map { index ->
            val relativePath = relativePath(index)
            WalkedFile(
                displayName = relativePath.substringAfterLast('/'),
                relativePath = relativePath,
                sizeBytes = 32_768L + index,
                documentUri = "content://ep03.generated/document/game-$index",
                filePath = null,
            )
        }

    fun indices(count: Int, order: Order): List<Int> {
        require(count >= 0)
        if (count == 0) return emptyList()
        return when (order) {
            Order.FORWARD -> List(count) { it }
            Order.REVERSE -> List(count) { count - 1 - it }
            Order.PERMUTED -> {
                val step = coprimeStep(count)
                val offset = Math.floorMod(SEED, count.toLong()).toInt()
                List(count) { position ->
                    ((position.toLong() * step.toLong() + offset.toLong()) % count.toLong()).toInt()
                }
            }
        }
    }

    fun documentId(index: Int): String = "game-$index"

    fun relativePath(index: Int): String =
        "NES/${index / 1_000}/${index.toString().padStart(6, '0')}.nes"

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
}
