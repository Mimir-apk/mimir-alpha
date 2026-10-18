package com.gruning.mimir.rewrite.core.metadata

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.ByteArrayInputStream
import org.junit.Test

class DatIngestionTest {

    private fun parse(xml: String, platform: String = "gb") =
        LogiqxDatParser.parse(ByteArrayInputStream(xml.trimIndent().toByteArray()), platform)

    // ── No-Intro cart DAT (single rom per game, cloneof parent) ──────────────
    private val noIntroGb = """
        <?xml version="1.0"?>
        <datafile>
          <header><name>Nintendo - Game Boy</name></header>
          <game name="Pokemon - Blue Version (USA, Europe) (SGB Enhanced)">
            <rom name="Pokemon - Blue.gb" size="1048576" crc="D5CD9FF2" md5="50a1089600603524b6c384da3f7cf19f" sha1="EA9BCAE617FDF159B045185467AE58B2E4A4B9B0"/>
          </game>
          <game name="Pokemon - Red Version (USA, Europe) (SGB Enhanced)" cloneof="Pokemon - Blue Version (USA, Europe) (SGB Enhanced)">
            <rom name="Pokemon - Red.gb" size="1048576" crc="A6924CE1"/>
          </game>
        </datafile>
    """

    @Test
    fun `parses No-Intro games incl clone parent + upper-case crc normalized`() {
        val entries = parse(noIntroGb)
        assertEquals(2, entries.size)
        val red = entries.first { it.canonicalName.contains("Red") }
        assertEquals("a6924ce1", red.crc32) // lower-cased
        assertEquals("Pokemon - Blue Version (USA, Europe) (SGB Enhanced)", red.cloneOf)
        val blue = entries.first { it.canonicalName.contains("Blue") }
        assertEquals("ea9bcae617fdf159b045185467ae58b2e4a4b9b0", blue.sha1)
        assertNull(blue.cloneOf) // the parent
    }

    @Test
    fun `parse metadata retains and trims the header name across SAX character chunks`() {
        val parsed = LogiqxDatParser.parseWithMetadata(
            ByteArrayInputStream(
                """
                <datafile>
                  <header><name>  Nintendo <![CDATA[- Game Boy]]>  </name></header>
                  <game name="Game"><rom name="game.gb" crc="12345678"/></game>
                </datafile>
                """.trimIndent().toByteArray(),
            ),
            "gb",
        )

        assertEquals("Nintendo - Game Boy", parsed.headerName)
        assertEquals(1, parsed.entries.size)
    }

    @Test
    fun `parse metadata reports a missing header name honestly`() {
        val parsed = LogiqxDatParser.parseWithMetadata(
            ByteArrayInputStream(
                "<datafile><game name=\"Game\"><rom name=\"game.gb\" crc=\"12345678\"/></game></datafile>"
                    .toByteArray(),
            ),
            "gb",
        )

        assertNull(parsed.headerName)
        assertEquals(1, parsed.entries.size)
    }

    @Test
    fun `DatStore matches by crc + sha1 and gives an authoritative region-collapse key`() {
        val store = DatStore(parse(noIntroGb))
        val red = store.match(ContentHash(crc32 = "A6924CE1")) // upper-case query still matches
        assertNotNull(red)
        assertEquals("Pokemon - Red Version (USA, Europe) (SGB Enhanced)", red!!.canonicalName)
        // Red is a clone of Blue → both collapse under the parent, no filename normalization needed.
        val blue = store.byCrc32("d5cd9ff2")!!
        assertEquals(store.collapseKey(red), store.collapseKey(blue))
        // sha1 lookup works too.
        assertEquals(blue, store.bySha1("EA9BCAE617FDF159B045185467AE58B2E4A4B9B0"))
    }

    @Test
    fun `DatIdentitySource resolves a game by its fingerprint`() = runBlocking {
        val src = DatIdentitySource(DatStore(parse(noIntroGb)))
        val r = src.lookup(MetadataQuery(com.gruning.mimir.rewrite.core.model.PlatformId.GB, contentHash = ContentHash(crc32 = "a6924ce1")))
        assertEquals("Pokemon - Red Version (USA, Europe) (SGB Enhanced)", r?.canonicalTitle)
        assertEquals(MetadataOrigin.NO_INTRO, r?.origin)
        // a fingerprint not in the DAT → null (never guesses)
        assertNull(src.lookup(MetadataQuery(com.gruning.mimir.rewrite.core.model.PlatformId.GB, contentHash = ContentHash(crc32 = "deadbeef"))))
    }

    // ── MAME (<machine>) + Redump (multi-track <rom>) variants ───────────────

    @Test
    fun `parses MAME machine elements`() {
        val entries = parse(
            """
            <datafile>
              <machine name="pacman" cloneof="puckman">
                <rom name="pacman.6e" size="4096" crc="C1E6AB10"/>
              </machine>
            </datafile>
            """,
            platform = "arcade",
        )
        assertEquals(1, entries.size)
        assertEquals("pacman", entries[0].canonicalName)
        assertEquals("puckman", entries[0].cloneOf)
        assertEquals("c1e6ab10", entries[0].crc32)
    }

    @Test
    fun `parses a Redump disc with multiple track roms — every track crc indexes the one game`() {
        val store = DatStore(
            parse(
                """
                <datafile>
                  <game name="Chrono Cross (USA) (Disc 1)">
                    <rom name="Chrono Cross (USA) (Disc 1) (Track 1).bin" size="100" crc="11111111"/>
                    <rom name="Chrono Cross (USA) (Disc 1) (Track 2).bin" size="200" crc="22222222"/>
                  </game>
                </datafile>
                """,
                platform = "ps1",
            ),
        )
        assertEquals(2, store.size)
        assertEquals("Chrono Cross (USA) (Disc 1)", store.byCrc32("11111111")?.canonicalName)
        assertEquals("Chrono Cross (USA) (Disc 1)", store.byCrc32("22222222")?.canonicalName)
    }

    @Test
    fun `a rom without a crc is skipped, header is ignored`() {
        val entries = parse(
            """
            <datafile>
              <header><name>x</name><version>1</version></header>
              <game name="Broken"><rom name="no-crc.bin" size="1"/></game>
              <game name="Good"><rom name="ok.bin" size="1" crc="0000abcd"/></game>
            </datafile>
            """,
        )
        assertEquals(1, entries.size)
        assertTrue(entries[0].canonicalName == "Good")
    }

    // ── Adversarial format edges (verified vs the real logiqx + MAME -listxml DTDs) ──────────

    @Test
    fun `disk-only game resolves by sha1 — CHD sets carry NO crc`() {
        val store = DatStore(
            parse(
                """
                <datafile>
                  <game name="Sonic Adventure (USA)">
                    <disk name="sonic adventure (usa)" sha1="9F2Be1C4aa11bb22cc33dd44ee55ff6600112233"/>
                  </game>
                </datafile>
                """,
                platform = "dreamcast",
            ),
        )
        // <disk> was silently dropped before the fix; a .chd identifies by disk sha1 (no crc anywhere).
        val hit = store.bySha1("9f2be1c4aa11bb22cc33dd44ee55ff6600112233")
        assertEquals("Sonic Adventure (USA)", hit?.canonicalName)
        assertNull(hit!!.crc32)
        // resolves through a sha1-only ContentHash too
        assertEquals(
            "Sonic Adventure (USA)",
            store.match(ContentHash(sha1 = "9F2Be1C4aa11bb22cc33dd44ee55ff6600112233"))?.canonicalName,
        )
    }

    @Test
    fun `MAME bios + device machines contribute no entries, real game still indexed`() {
        val store = DatStore(
            parse(
                """
                <datafile>
                  <machine name="z80" isdevice="yes" runnable="no"><description>Zilog Z80</description></machine>
                  <machine name="neogeo" isbios="yes">
                    <rom name="sp-s2.sp1" size="131072" crc="9036d879" sha1="4f5ed7105b7128794654ce82b51723e16e389543"/>
                  </machine>
                  <machine name="mslug" romof="neogeo">
                    <rom name="201-p1.p1" size="1048576" crc="08d8daa5" sha1="aaaabbbbccccddddeeeeffff0000111122223333"/>
                  </machine>
                </datafile>
                """,
                platform = "arcade",
            ),
        )
        assertNull(store.byCrc32("9036d879")) // BIOS chip rom no longer polluting the index
        assertEquals("mslug", store.byCrc32("08d8daa5")?.canonicalName) // real game survives
        assertEquals(1, store.size)
    }

    @Test
    fun `MAME clone with only merged roms yields no phantom entries`() {
        val store = DatStore(
            parse(
                """
                <datafile>
                  <machine name="pacmanm" cloneof="pacman" romof="pacman">
                    <rom name="pacman.6e" size="4096" crc="c1e6ab10" sha1="bbbb" merge="pacman.6e"/>
                  </machine>
                </datafile>
                """,
                platform = "arcade",
            ),
        )
        assertEquals(0, store.size) // merge rom's bytes live in the parent — no duplicate pacman entry
    }

    @Test
    fun `rom with sha1 but no crc is indexed while a pure nodump placeholder is not`() {
        val store = DatStore(
            parse(
                """
                <datafile>
                  <game name="Some Game (Japan) (Rev 1)">
                    <rom name="x.gb" size="131072" sha1="aa11bb22cc33dd44ee55ff6600112233aabbccdd" status="baddump"/>
                  </game>
                  <game name="Unreleased Proto (USA) (Proto)">
                    <rom name="p.gb" size="0" status="nodump"/>
                  </game>
                </datafile>
                """,
            ),
        )
        assertEquals(
            "Some Game (Japan) (Rev 1)",
            store.bySha1("aa11bb22cc33dd44ee55ff6600112233aabbccdd")?.canonicalName,
        )
        assertEquals(1, store.size) // hash-less nodump row contributes nothing
    }

    @Test
    fun `sha1 wins when crc points at a contradictory entry`() {
        val alpha = DatEntry("gb", "11111111", "Alpha", sha1 = "a".repeat(40), sizeBytes = 100)
        val beta = DatEntry("gb", "22222222", "Beta", sha1 = "b".repeat(40), sizeBytes = 200)
        val store = DatStore(listOf(alpha, beta))
        assertEquals(
            "Beta",
            store.match(ContentHash(crc32 = "11111111", sha1 = "b".repeat(40), sizeBytes = 200))?.canonicalName,
        )
    }

    @Test
    fun `crc collision resolves only by exact positive size`() {
        val store = DatStore(
            listOf(
                DatEntry("ps1", "deadbeef", "Disc A", sizeBytes = 100),
                DatEntry("ps1", "deadbeef", "Disc B", sizeBytes = 200),
            ),
        )
        assertNull(store.byCrc32("deadbeef"))
        assertNull(store.match(ContentHash(crc32 = "deadbeef")))
        assertNull(store.match(ContentHash(crc32 = "deadbeef", sizeBytes = 0)))
        assertEquals("Disc B", store.match(ContentHash(crc32 = "deadbeef", sizeBytes = 200))?.canonicalName)
        assertNull(store.match(ContentHash(crc32 = "deadbeef", sizeBytes = 300)))
    }
}
