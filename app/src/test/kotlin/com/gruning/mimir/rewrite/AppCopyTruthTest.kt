package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Test

class AppCopyTruthTest {
    @Test
    fun `backup import never exposes migration or parser language`() {
        assertEquals("IMPORT BACKUP", ImportBackupEyebrowCopy)
        assertEquals("Review this Mimir backup", ReviewBackupTitleCopy)
        assertEquals("Choose a Mimir backup file.", BackupFileReadFailureCopy)
        assertEquals("That file isn’t a supported Mimir backup.", UnsupportedBackupCopy)
    }

    @Test
    fun `artwork failures state only facts Mimir knows`() {
        assertEquals(
            "Mimir couldn’t save beside one or more games. Choose the affected folder again in " +
                "Settings › Library, then retry.",
            ArtworkWriteBlockedCopy,
        )
        assertEquals(
            "ScreenScraper isn’t available in this build. Your account wasn’t changed.",
            ScreenScraperUnavailableCopy,
        )
    }

    @Test
    fun `tabs description uses Android recent apps vocabulary`() {
        assertEquals(
            "Uses this device’s built-in bridge to open Android’s recent apps screen.",
            TabsSupportingCopy,
        )
    }
}
