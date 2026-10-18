package com.gruning.mimir.rewrite.feature.library

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertThrows
import org.junit.Test

/** Strict downstream solvers must see the decision dimensions, never a fabricated zero substitute. */
class ViewportSolverStrictTest {
    @Test
    fun libraryShelfRejectsZeroWidth() {
        assertThrows(IllegalArgumentException::class.java) {
            solveLibraryShelfGeometry(widthDp = 0f, heightDp = 400f)
        }
    }

    @Test
    fun homeShelfRejectsZeroWidthAndSubFloorInputs() {
        assertThrows(IllegalArgumentException::class.java) {
            solveHomeShelfGeometry(widthDp = 0f, heightDp = 400f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            solveHomeShelfGeometry(widthDp = 319f, heightDp = 300f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            solveHomeShelfGeometry(widthDp = 320f, heightDp = 299f)
        }
    }

    @Test
    fun tomePartitionsRejectZeroWidthOrHeight() {
        assertThrows(IllegalArgumentException::class.java) {
            solveHomeTomeFolioVerticalPartitions(panelHeight = 0f, fontScale = 1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            solveHomeTomeFolioHorizontalPartitions(
                width = 0f,
                outer = 0f,
                emblemWidth = 0f,
                titleInset = 8f,
                headerGap = 12f,
                footerGap = 8f,
                requestedCloseWidth = 40f,
            )
        }
    }

    @Test
    fun launchCardWideSkeletonRejectsZeroViewportEvenForCompactLayout() {
        val compactLayout = homeLaunchCardLayout(width = 320.dp, height = 300.dp)
        assertThrows(IllegalArgumentException::class.java) {
            homeLaunchCardUsesWideSkeleton(
                width = 0.dp,
                height = 0.dp,
                layout = compactLayout,
            )
        }
    }
}
