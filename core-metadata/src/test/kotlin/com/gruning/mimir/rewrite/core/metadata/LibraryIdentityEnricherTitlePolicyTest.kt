package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.model.DuplicateGroupKind
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryIdentityEnricherTitlePolicyTest {

    @Test fun `stamp strips the matched disc only for a persisted multi-disc group`() {
        assertEquals(
            "Chrono Cross (USA, Canada)",
            canonicalTitleForStamp(
                "Chrono Cross (USA, Canada) (Disc 2)",
                DuplicateGroupKind.MULTI_DISC.name,
            ),
        )

        val loneDisc = "Resident Evil - Code Veronica X (USA) (Disc 2)"
        assertEquals(loneDisc, canonicalTitleForStamp(loneDisc, null))
        assertEquals(loneDisc, canonicalTitleForStamp(loneDisc, DuplicateGroupKind.CATALOG_TITLE_ID.name))
        assertEquals(loneDisc, canonicalTitleForStamp(loneDisc, "FUTURE_UNKNOWN_KIND"))
    }
}
