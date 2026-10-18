package com.gruning.mimir.rewrite.core.metadata

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

data class ParsedLogiqxDat(
    val headerName: String?,
    val entries: List<DatEntry>,
)

/**
 * Streams a logiqx-format DAT (No-Intro / Redump / MAME clrmamepro XML) into [DatEntry]s.
 *
 * Handles the format variants uniformly:
 *  - element is `<game>` (No-Intro / Redump) OR `<machine>` (MAME) — both carry `name` + `cloneof`;
 *  - a game has ONE `<rom>` (a No-Intro cart) or MANY (a Redump disc lists a rom per track) — EVERY rom's
 *    crc indexes the same game, so any track file matches;
 *  - a `<disk>` (MAME / MAME-Redump CHD set) carries a SHA-1 and NO crc — a `.chd`'s identity is that
 *    disk sha1, so disks are indexed by sha1 alone (crc32 = null);
 *  - a rom/disk is indexed if it carries ANY usable hash (crc OR sha1); a pure hash-less `nodump`
 *    placeholder is dropped, as is a MAME `merge=` rom whose bytes physically live in the parent set;
 *  - a MAME BIOS / device / non-runnable pseudo-machine (`isbios` / `isdevice` / `runnable="no"`) is
 *    NOT a playable game — its shared-chip roms are skipped so they never pollute the index;
 *  - `<header>` and unknown nodes are ignored.
 *
 * Data-sourcing note: for a `.chd` platform (ps1/psp/dreamcast/saturn/CHD-arcade) you MUST load a
 * `<disk>`-based (MAME-Redump / CHD) DAT — a standard Redump `.bin/.cue` TRACK DAT catalogues per-track
 * crc/sha1 and will 0-match a whole-disc `.chd` no matter how correct the parser is.
 *
 * SAX (streaming) so a multi-MB DAT never loads fully into memory — and SAX is on both Android and the JVM
 * test runtime (unlike StAX). Real DATs carry the logiqx `<!DOCTYPE … SYSTEM "http://www.logiqx.com/…dtd">`,
 * so external entity resolution is stubbed to an empty source: the DOCTYPE is tolerated but NEVER fetched
 * (no network — posture). Metadata only — a canonical name + hashes, never a content locator.
 */
object LogiqxDatParser {

    fun parse(input: InputStream, platform: String): List<DatEntry> =
        parseWithMetadata(input, platform).entries

    /** Parse entries and retain the blob's own header name so variant choice can be locked by tests. */
    fun parseWithMetadata(input: InputStream, platform: String): ParsedLogiqxDat {
        val out = ArrayList<DatEntry>()
        val handler = object : DefaultHandler() {
            var gameName: String? = null
            var cloneOf: String? = null
            var inHeader = false
            var readingHeaderName = false
            var headerName: String? = null
            val headerNameBuffer = StringBuilder()
            // A MAME BIOS / device / non-runnable machine is not a playable game — skip its roms/disks.
            var skipMachine = false

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                when (qName) {
                    "header" -> inHeader = true
                    "name" -> if (inHeader && headerName == null) {
                        readingHeaderName = true
                        headerNameBuffer.setLength(0)
                    }
                    "game", "machine" -> {
                        gameName = attributes.getValue("name")
                        cloneOf = attributes.getValue("cloneof")
                        val isBios = attributes.getValue("isbios") == "yes"
                        val isDevice = attributes.getValue("isdevice") == "yes"
                        val notRunnable = attributes.getValue("runnable") == "no" // DTD default is "yes"
                        skipMachine = isBios || isDevice || notRunnable
                    }
                    "rom" -> if (!skipMachine) {
                        val name = gameName
                        val merge = attributes.getValue("merge") // MAME: bytes live in the parent set
                        val crc = attributes.getValue("crc")
                        val sha1 = attributes.getValue("sha1")
                        if (name != null && merge.isNullOrBlank() && (!crc.isNullOrBlank() || !sha1.isNullOrBlank())) {
                            out += DatEntry(
                                platform = platform,
                                crc32 = crc?.takeIf { it.isNotBlank() }?.lowercase()?.padStart(8, '0'),
                                canonicalName = name,
                                cloneOf = cloneOf,
                                md5 = attributes.getValue("md5")?.lowercase(),
                                sha1 = sha1?.lowercase(),
                                sizeBytes = attributes.getValue("size")?.toLongOrNull(),
                            )
                        }
                    }
                    "disk" -> if (!skipMachine) {
                        val name = gameName
                        val merge = attributes.getValue("merge")
                        val sha1 = attributes.getValue("sha1") // disks have NO crc — sha1 is the identity
                        if (name != null && merge.isNullOrBlank() && !sha1.isNullOrBlank()) {
                            out += DatEntry(
                                platform = platform,
                                crc32 = null,
                                canonicalName = name,
                                cloneOf = cloneOf,
                                md5 = attributes.getValue("md5")?.lowercase(),
                                sha1 = sha1.lowercase(),
                                sizeBytes = null, // DTD: <disk> has no size attribute
                            )
                        }
                    }
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (readingHeaderName) headerNameBuffer.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (qName) {
                    "name" -> if (readingHeaderName) {
                        headerName = headerNameBuffer.toString().trim().takeIf { it.isNotEmpty() }
                        readingHeaderName = false
                    }
                    "header" -> inHeader = false
                    "game", "machine" -> {
                        gameName = null
                        cloneOf = null
                        skipMachine = false
                    }
                }
            }

            // Tolerate the logiqx DOCTYPE but NEVER fetch the external DTD (no network).
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
                InputSource(StringReader(""))
        }
        val factory = SAXParserFactory.newInstance().apply {
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        input.use { factory.newSAXParser().parse(it, handler) }
        return ParsedLogiqxDat(headerName = handler.headerName, entries = out)
    }
}
