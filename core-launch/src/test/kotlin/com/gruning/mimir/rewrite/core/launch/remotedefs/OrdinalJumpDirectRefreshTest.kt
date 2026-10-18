package com.gruning.mimir.rewrite.core.launch.remotedefs

import com.gruning.mimir.rewrite.core.launch.remotedefs.TestBundled.FLYCAST
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The ordinal-jump limit is the refresher's, not only the pointer's: a release read directly is held to it too. */
class OrdinalJumpDirectRefreshTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun release(ordinal: Long) = Overlays.build(ordinal, listOf(Rows.alt(FLYCAST, "dreamcast", "com.x.Alt")))

    @Test
    fun a_release_more_than_100_ahead_is_refused_before_anything_is_stored() {
        val far = release(101)
        val rig = Rig(tmp.newFolder(), TestBundled.small(), FakeEgress.always { FakeResponse.ok(far) })

        assertEquals(RefreshResult.Rejected(RejectCode.ORDINAL_JUMP), rig.refresh(far))

        assertEquals(emptySet<String>(), fileNames(rig.dir))
        assertEquals(null, rig.holder.current.overlayOrdinal)
    }

    @Test
    fun a_release_exactly_100_ahead_lands() {
        val edge = release(100)
        val rig = Rig(tmp.newFolder(), TestBundled.small(), FakeEgress.always { FakeResponse.ok(edge) })

        assertTrue(rig.refresh(edge) is RefreshResult.Applied)
        assertEquals(100L, rig.holder.current.overlayOrdinal)
    }
}
