package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidContentOpenerRelatedTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `managed related path reaches only the requested local PARAM SFO`() {
        val root = temporary.newFolder("game")
        val usrDir = File(root, "PS3_GAME/USRDIR").apply { mkdirs() }
        val primary = File(usrDir, "EBOOT.BIN").apply { writeBytes(byteArrayOf(1)) }
        val sfo = File(root, "PS3_GAME/PARAM.SFO").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(2))
        }

        assertEquals(sfo.canonicalFile, resolveRelatedFile(primary, "../PARAM.SFO"))
        assertNull(resolveRelatedFile(primary, "../MISSING.SFO"))
    }

    @Test
    fun `SAF related id preserves volume and resolves parent segments`() {
        val primary = "ABCD-1234:ROMs/PS3/Game/PS3_GAME/USRDIR/EBOOT.BIN"
        assertEquals(
            "ABCD-1234:ROMs/PS3/Game/PS3_GAME/PARAM.SFO",
            resolveRelatedDocumentId(primary, "../PARAM.SFO"),
        )
        assertNull(resolveRelatedDocumentId("ABCD-1234:EBOOT.BIN", "../../PARAM.SFO"))
        assertNull(resolveRelatedDocumentId(primary, "/PARAM.SFO"))
    }
}
