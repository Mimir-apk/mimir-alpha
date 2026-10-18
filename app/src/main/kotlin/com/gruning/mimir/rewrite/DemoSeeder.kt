package com.gruning.mimir.rewrite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File

/**
 * DEV-only demo library seeder. Writes a small tree of representative ROM FILENAMES (real names +
 * extensions, incl. a multi-disc set with its .m3u, and region variants) into the app's OWN external
 * files dir, so the REAL scan → classify → dedup pipeline can be exercised on the device WITHOUT a SAF
 * folder pick or the user's actual library. Recognition is by extension + folder hint + filename; the
 * file CONTENTS are irrelevant, so 1-byte stubs suffice. Returns the absolute path to scan.
 *
 * The folder segment (PSX/SNES/…) is the classifier's container hint for ambiguous extensions
 * (`.chd`/`.m3u`). Everything is under the app's sandbox — read in place, never networked.
 */
object DemoSeeder {

    private val SAMPLE = listOf(
        // Multi-disc PS1 set — the .m3u is the primary; the 3 discs FOLD into ONE tile ("3 discs").
        "PSX/Final Fantasy VII (USA).m3u",
        "PSX/Final Fantasy VII (USA) (Disc 1).chd",
        "PSX/Final Fantasy VII (USA) (Disc 2).chd",
        "PSX/Final Fantasy VII (USA) (Disc 3).chd",
        // 2-disc PS1 — folds to ONE tile ("2 discs").
        "PSX/Metal Gear Solid (USA) (Disc 1).chd",
        "PSX/Metal Gear Solid (USA) (Disc 2).chd",
        // Singletons.
        "PSX/Castlevania - Symphony of the Night (USA).chd",
        "SNES/Chrono Trigger (USA).sfc",
        "SNES/Super Metroid (Japan, USA).sfc",
        "SNES/The Legend of Zelda - A Link to the Past (USA).sfc",
        "GBA/Pokemon - Emerald Version (USA, Europe).gba",
        "GBA/Metroid Fusion (USA).gba",
        // Region variants of one title — fold to ONE tile ("2 versions").
        "GBA/Golden Sun (USA, Europe).gba",
        "GBA/Golden Sun (Germany).gba",
        "N64/Super Mario 64 (USA).z64",
        "N64/The Legend of Zelda - Ocarina of Time (USA).z64",
        "NDS/Mario Kart DS (USA).nds",
        "Dreamcast/Sonic Adventure (USA).chd",
    )

    // A couple of titles that get a generated SIBLING cover ([withArt]) so the offline box-art path is
    // device-verifiable — these tiles render real art, the rest fall back to the honest glyph.
    private val ART_FOR = mapOf(
        "GBA/Golden Sun (USA, Europe).gba" to "G",
        "SNES/Chrono Trigger (USA).sfc" to "C",
    )

    /**
     * Seed the demo ROM tree. When [withArt] is true, also writes a real PNG cover NEXT TO two of the ROMs
     * (same base name), so [com.gruning.mimir.rewrite.core.resolver.library.LocalArtSource] resolves them
     * and Coil renders real art on those tiles — the rest keep the honest glyph. Local files only.
     */
    fun seed(context: Context, withArt: Boolean = false): String {
        val root = File(context.getExternalFilesDir(null), "demo-roms")
        if (root.exists()) root.deleteRecursively()
        root.mkdirs()
        SAMPLE.forEach { rel ->
            val f = File(root, rel)
            f.parentFile?.mkdirs()
            if (!f.exists()) f.writeText("mimir-demo-stub")
        }
        if (withArt) {
            ART_FOR.forEach { (rel, letter) ->
                val art = File(root, rel.substringBeforeLast('.') + ".png")
                writeCoverArt(art, letter)
            }
        }
        return root.absolutePath
    }

    /**
     * Generate a simple 3:4 demo cover and save it as a PNG. Deliberately INVERTED from the honest glyph
     * (a GOLD field with a DARK letter, vs the glyph's dark panel + light letter) so a device screenshot
     * unmistakably shows real art rendered, not the fallback.
     */
    private fun writeCoverArt(target: File, letter: String) {
        val bmp = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(0xd4, 0xaf, 0x69)) // full GOLD #d4af69 field — never how the glyph looks
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x14, 0x10, 0x08) // dark ink
            textSize = 190f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText(letter, 150f, 250f, ink)
        val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x14, 0x10, 0x08)
            textSize = 34f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("COVER ART", 150f, 340f, tag)
        target.parentFile?.mkdirs()
        target.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }
}
