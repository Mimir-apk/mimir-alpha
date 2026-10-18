package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the focus ring and the chip's rim are ONE band growing outward, never two lines. That takes both halves: the ring's bright stroke flush on the edge
 * (`mimirSelectionRing(edgeFlush = true)`) AND the rim taking the ring's colour (`mimirEtchedChipSurface(edgeFlush = true)`, or `MimirEtchedChipPlate(edgeFlush = true)`, which hands it on).
 * Either half alone is wrong: a flush ring over a fading rim doubles the line again, and a tinted rim under the default ring still stands half a dp from it. So at every call site
 * that ring and surface are opted in together, file by file.
 *
 * **The scan reads call expressions, not lines** (audit wave 2, the chip pass): a call is found by its name and read to its matching parenthesis, so a plate whose ring rides in its
 * own `modifier = ...` argument and whose `edgeFlush = true,` stands on a line by itself counts once, as a plate. A file whose rings reach their surfaces through a passed-in
 * modifier (a `plateModifier`, a ring helper) cannot pair one to one; those are named in [fanIn] with their counts, and the total is pinned.
 *
 * **Every chip that takes `focused` must opt in or be named** ([stillCrossfading]): a new focusable chip that forgets `edgeFlush` shows two lines on every focus move, and this fails
 * for it. The list can only shrink.
 */
class EdgeFlushChipPairingTest {

    private val root: File = listOf(File(".."), File(".")).first { File(it, "core-ui/src/main/kotlin").isDirectory }

    private fun sources(): List<File> = (root.listFiles() ?: emptyArray())
        .filter { File(it, "src/main/kotlin").isDirectory }
        .flatMap { module -> File(module, "src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    /** Block and line comments out, newlines kept (prose quotes `edgeFlush = true` freely). `://` in a string is not a comment. */
    private fun code(file: File): String = file.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)) { m -> m.value.replace(Regex("[^\n]"), "") }
        .replace(Regex("(?<!:)//[^\n]*"), "")

    /** The argument text of every call of [name] in [text], each read to its matching parenthesis. A declaration (`fun Modifier.name(`) is not a call. */
    private fun calls(text: String, name: String): List<String> {
        val found = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = text.indexOf("$name(", from)
            if (at < 0) break
            from = at + name.length + 1
            if (at > 0 && (text[at - 1].isLetterOrDigit() || text[at - 1] == '_')) continue
            if (Regex("""fun\s+(?:[\w.<>]+\.)?$""").containsMatchIn(text.substring(maxOf(0, at - 60), at))) continue
            var depth = 1
            var i = from
            while (i < text.length && depth > 0) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            found += text.substring(from, i - 1)
        }
        return found
    }

    /** `edgeFlush = true`, or a value that is neither `false` nor the wrapper's own pass-through (`edgeFlush = accent`: the Done key opts in only when it wears the etched plate). */
    private val optedIn = Regex("""\bedgeFlush\s*=\s*(?!false\b)(?!edgeFlush\b)\w""")

    private val takesFocus = Regex("""\bfocused\s*=\s*(?!false\b)\w""")

    private class Counts(val rings: Int, val sinks: Int, val unflipped: Int)

    private fun counts(file: File): Counts {
        val text = code(file)
        val rings = calls(text, "mimirSelectionRing")
        val sinks = calls(text, "mimirEtchedChipSurface") + calls(text, "MimirEtchedChipPlate")
        return Counts(
            rings = rings.count { optedIn.containsMatchIn(it) },
            sinks = sinks.count { optedIn.containsMatchIn(it) },
            unflipped = sinks.count { !optedIn.containsMatchIn(it) && takesFocus.containsMatchIn(it) },
        )
    }

    /**
     * Files whose flush rings and flush surfaces do not pair one to one, with (surfaces, rings) and why.
     *  - the Activity filter header draws its three chip surfaces ([ActivityChipPlate] and the two search chips), and every ring that reaches them is built where the chip is placed
     *    (search twice, Show, Console, Clear filters);
     *  - the Customize media top row has one ring helper (`customMediaChipRing`) that serves its three direct surfaces and, through `plateModifier`, two chips whose surface is the
     *    header's.
     *  - the Library page has three: the shelf's Add pill (a direct ring and surface) and the gamelist search bar's full and small pills, which share one ring helper (`controllerModifier`) (wave 2 L8b, Q8).
     */
    private val fanIn = mapOf(
        "CompanionActivityAchievementFilterHeader.kt" to (3 to 5),
        "CustomMediaTopRow.kt" to (3 to 1),
        "LibraryScreen.kt" to (3 to 2),
    )

    /**
     * The focusable chips that still hand over by crossfade, two lines on a focus move. Each is a count of surfaces. The list only shrinks, and it is empty: the last entry was
     * `CompactUnifiedBrowser.kt`, deleted by the Q10 ruling.
     */
    private val stillCrossfading = emptyMap<String, Int>()

    @Test
    fun `every chip surface that opts into edge-flush has its ring opted in beside it, and the other way round`() {
        val problems = mutableListOf<String>()
        var surfaces = 0
        var rings = 0
        for (file in sources()) {
            if (file.name == "MimirEtchedChip.kt") continue
            val c = counts(file)
            surfaces += c.sinks
            rings += c.rings
            val expected = fanIn[file.name] ?: (c.sinks to c.sinks)
            if (c.sinks != expected.first || c.rings != expected.second) {
                problems += "${file.name}: ${c.sinks} surface(s) and ${c.rings} ring(s) opted in, expected ${expected.first} and ${expected.second}"
            }
        }
        assertTrue("edge-flush must be opted in on the ring and the surface together: $problems", problems.isEmpty())
        // The direct ring-plus-surface chips of the first pass: the launch card's actions, Browse's search bar, the file browser's queue and search controls, the gamelist's Get art,
        // the shelf's Open library and Add folder, Library's search field, Settings' two buttons and the onboarding pill (12). The chip pass of wave 2 adds the Get-art prompt's
        // answers, the nine plates that carry their ring in their modifier (artwork, Utility, the overlay permission, Customize media's footer, the recovery gate, the tile menu's
        // confirm, the import, the music Library chip, the storage grant), the Customize media top row (3), the Activity filter header (3), the keyboard's Done key and the gamelist search bar's two pills (31 in all).
        assertEquals("the chips that opted in", 31, surfaces)
    }

    @Test
    fun `no focusable chip hands over by crossfade except the ones named here`() {
        val found = mutableMapOf<String, Int>()
        for (file in sources()) {
            if (file.name == "MimirEtchedChip.kt") continue // the plate hands its own `focused` to the surface it wraps
            val c = counts(file)
            if (c.unflipped > 0) found[file.name] = c.unflipped
        }
        assertEquals("a focusable etched chip must opt into edgeFlush (ring and surface), or be named in stillCrossfading", stillCrossfading, found)
    }

    @Test
    fun `the surface keeps the default fade and the ring the default shape where nothing opted in`() {
        val chip = File(root, "core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirEtchedChip.kt").readText()
        val ring = File(root, "core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirSelectionRing.kt").readText()
        assertTrue("the surface's edgeFlush defaults off", Regex("""edgeFlush: Boolean = false""").containsMatchIn(chip))
        assertTrue("the ring's edgeFlush defaults off", Regex("""edgeFlush: Boolean = false""").containsMatchIn(ring))
    }
}
