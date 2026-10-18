package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Update (): a pressable that is a capsule filled with a glass or a tint is the species the Customize media Upload button was, and every
 * pressable is the etched chip instead (`mimirEtchedChipSurface`, its padding and label; a warm one for a primary or a selection). `radiusPill` is for chips only; this is the source guard that keeps a fill from coming back. It reads the production sources of every UI module
 * (comments blanked) and fails on:
 *
 *  1. a `mimirSelectionRing(...)` call that names `MimirRingShape.Pill` and hands the ring a `fill =` (the ring paints that fill itself under the wash);
 *  2. a pill clip (`.clip(RoundedCornerShape(MimirSpacing.radiusPill))`, a 999 dp one, or a `val` of either) that is directly followed by `.background(` while the surrounding
 *     chain is pressable (it taps, or it takes focus);
 *  3. a `.background(<fill>, <pill shape>)` in the same pressable chain;
 *  4. a Pill ring (`mimirSelectionRing` or `activityPadTarget` with `MimirRingShape.Pill`) that takes an `inset =`. A Thor photo showed that a ring drawn around the
 *     48 dp hit box with an inset reads as a double rim, the plate's etched outline inside and a fatter ring outside it with a gap between. The ring goes on the PLATE (the element
 *     that carries `mimirEtchedChipSurface`) with no inset, so it becomes the plate's own edge, and the 48 dp hit box is an invisible wrapper around it;
 *  5. a `mimirSelectionRing` later in the same modifier chain than `mimirEtchedChipSurface`: the ring then paints its wash OVER the plate and the plate reads as a tinted fill.
 *     The ring goes before the surface (it paints outside the plate, and the plate paints over the wash).
 *
 * Update (the Utility footer's Close and Allow were filled rounded RECTANGLES, which none of the above sees: `NoFilledPillButtonGuardTest` only knew capsules) widens it to every
 * rounded shape, with three more rules, each read over the whole modifier chain (the `.`-continued lines around the call, with their argument lists), not a few lines either side:
 *
 *  6. `.clip(<any RoundedCornerShape, or a val that is one>)` directly followed by `.background(` in a pressable chain: the `UtilityAction` shape, a button drawn as a filled tile;
 *  7. `.background(<fill>, <any RoundedCornerShape>)` in a pressable chain;
 *  8. a `mimirSelectionRing(...)` with `fill =` or `filled = <not false>` in a pressable chain on any shape but Pill (the ring then paints the button's fill itself: the import page's,
 *     the recovery gate's and the permission prompt's buttons were drawn that way).
 *
 * The pill test also knows `RoundedCornerShape(percent = 50)` and `RoundedCornerShape(50)` now (the game page's filled capsule slipped through on `isPill` knowing only the radius token).
 *
 * A rounded rectangle is also what every list row, card, tile, field and tab is, and the source cannot tell a Close from a Settings row, so rules 6-8 are held by an allowlist
 * ([roundedAllowed]) keyed by file and composable, each entry with the reason it is not a labelled button. A NEW pressable filled rounded rectangle fails until it is converted to the
 * etched chip, or is added there with a reason. Every entry must still match something ([every roundedAllowed entry still matches a source]), so the list cannot rot.
 *
 * What it does not read: a pill that does not press (a badge, a status chip, a meter) may be filled, and is not matched because it carries no tap in its chain; a button whose tap is
 * attached by its CALLER (a `modifier` parameter carries the `clickable`) is invisible to it. The preserved controls (`GlassMenuCapsule`, the Quick Launch
 * grab handle in `CompanionSurface.kt`, `LedgerSetupAction`) are not shaped like any of the rules, so they are not matched today; the first and the last have their files in [allowed]
 * with their exceptions documented, and the grab handle shares `CompanionSurface.kt` with the converted picker Close, so its file is NOT allowed (a fill on that Close must fail): if the handle is ever
 * rewritten into one of these shapes, allow it by moving it to a file of its own. Pixel truth is the render tests'; this is only the door.
 */
class NoFilledPillButtonGuardTest {

    /** File name to the reason it may hold a rule-1-3 match. Keep it short; every entry is a debt or a ruling. */
    private val allowed: Map<String, String> = mapOf(
        "HomeCustomEnrichPicker.kt" to "Customize media (#263) is another lane's, in flight: its Upload and Retry/Stop chips are converted there",
        "MimirGlassMenuParts.kt" to "GlassMenuCapsule, the retained menu focus capsule",
        "MimirAchievementCard.kt" to "LedgerSetupAction: retained setup action",
    )

    /**
     * File name to the reason it may hold a rule-4 match (a Pill ring with an inset). None: Customize media's top row, the one entry, now rings its plates with no inset
     * (the 10-09 round that ended the double rim), so the guard covers it too.
     */
    private val insetAllowed: Map<String, String> = emptyMap()

    /**
     * `File.kt:composable` to the reason a pressable rounded rectangle that is filled is NOT a labelled button (rules 6-8). Short; every entry is a ruling or a different kind of
     * control. Add a button here and the guard has stopped guarding.
     */
    private val roundedAllowed: Map<String, String> = buildMap {
        fun allow(reason: String, vararg keys: String) = keys.forEach { put(it, reason) }
        allow(
            "a tab selector: the tint marks the SELECTED tab, not a button's fill",
            "AppShellScaffold.kt:ShellTabChip",
        )
        allow(
            "a panel or dialog card: its tap only absorbs touches so the scrim behind does not dismiss; its buttons are etched chips inside it",
            "MigrationImport.kt:MigrationImportOverlay",
            "MimirMainAchievementPage.kt:MimirMainAchievementPage",
            "OnboardingFlow.kt:OnboardingConfirmLayer",
            "LibraryScreen.kt:HideConfirmOverlay",
            "LibraryScreen.kt:NativeHideConfirmOverlay",
            "LibraryScreen.kt:NativeAddAppPickerOverlay",
            "MimirColorDial.kt:MimirColorDialOverlay",
            "MusicVolumeDial.kt:MimirVolumeDialOverlay",
            "CompanionSurface.kt:CompanionFolderPage",
            "CompanionActivityAchievementFilterHeader.kt:ActivityChoicePicker",
        )
        allow(
            "a tile or cell (a game, platform, picker item or an empty slot): a tile is a card, not a labelled button",
            "HomeQuickLaunchPickerOverlay.kt:HomeQuickLaunchPickerTile",
            "LibraryComponents.kt:PlatformTile",
            "AppearanceImagePicker.kt:AppearancePickerTileCard",
            "HomeTomeFolio.kt:HomeTomeEmptyFolioCell",
            "HomeTomeFolio.kt:HomeTomeTeachingPlate",
        )
        allow(
            "an icon tile or a glyph badge (the recent app's icon, a close cross): no label, so not a labelled button",
            "CompanionWorkspaceSurface.kt:setMusicPanelOpen",
            "CompanionRecentsDrawer.kt:RecentAppTile",
        )
        allow(
            "a text field (a name or a search box): an input with its own glass, not a button",
            "OnboardingFlow.kt:OnboardingNameField",
            "OnboardingFlow.kt:OnboardingFavoritesSearchField",
            "LibraryScreen.kt:NativeAddPickerSearchField",
        )
        allow(
            "a list row (a game, an option, a result, a destination): the row is the control and its ring paints the row's ground",
            "LibraryComponents.kt:GamelistRow",
            "LibraryScreen.kt:NativeFavoriteRow",
            "LibraryScreen.kt:NativeAddPickerRow",
            "LibrarySearchOverlay.kt:LibrarySearchResultRow",
            "LibrarySearchOverlay.kt:LibrarySearchPickerRow",
            "FileBrowserDestinationSurface.kt:FileBrowserDestinationRowView",
            "FileBrowserDiscSetSurface.kt:FileBrowserDiscSetOptionRow",
            "FileBrowserExtractSurface.kt:FileBrowserExtractOptionRow",
            "FileBrowserSearchSurface.kt:FileBrowserSearchResultRow",
            "FileBrowserTransferSurface.kt:FileBrowserTransferOptionRow",
            "BrowseScreen.kt:HiddenRow",
            "FileBrowserActionOverlaySurface.kt:FileBrowserActionOverlayOptionRow",
        )
        allow(
            "leather rows, not buttons",
            "FileBrowserSurface.kt:FileBrowserCommitRow",
            "FileBrowserSurface.kt:FileBrowserBackToOriginRow",
        )
    }

    private val modules = listOf(
        "app",
        "core-ui",
        "feature-library",
        "feature-settings",
        "feature-files",
        "feature-browse",
        "feature-companion",
    )

    private val pressable = Regex("""mimirActivatable|clickable|mimirFocusable|detectTapGestures|companionShelfTap|companionChoiceTap|pointerInput""")

    private data class Match(val file: String, val line: Int, val rule: String, val fn: String = "")

    private val insetRule = "pill ring with an inset"
    private val orderRule = "ring after the etched surface"
    private val roundedClipRule = "rounded clip then background"
    private val roundedShapedRule = "rounded-shaped background"
    private val ringFillRule = "ring with a fill"
    private val roundedRules = setOf(roundedClipRule, roundedShapedRule, ringFillRule)

    @Test
    fun `no pressable is a capsule filled with a glass or a tint`() {
        val sources = productionSources()
        assertTrue("the guard reads the production sources, not nothing: ${sources.size}", sources.size > 150)
        val found = sources.flatMap { (name, text) -> scan(name, text) }
        val unexpected = found.filter(::isUnexpected)
        assertEquals(
            "a pressable button with a fill, or a Pill ring with an inset (issue #265/#269: make it mimirEtchedChipSurface / MimirEtchedChipPlate, warmed when selected or armed, primary for the " +
                "screen's main action, the ring on the plate itself and a 48 dp hit box around it; a list row, card, tile, field or tab is added to roundedAllowed WITH a reason): " +
                unexpected.joinToString { "${it.file}:${it.line} ${it.fn} (${it.rule})" },
            emptyList<Match>(),
            unexpected,
        )
    }

    @Test
    fun `every roundedAllowed entry still matches a source`() {
        val matched = productionSources().flatMap { (name, text) -> scan(name, text) }
            .filter { it.rule in roundedRules }
            .map { "${it.file}:${it.fn}" }
            .toSet()
        val stale = roundedAllowed.keys.filterNot { it in matched }
        assertEquals("roundedAllowed entries that match nothing now (delete them): $stale", emptyList<String>(), stale)
    }

    private fun isUnexpected(m: Match): Boolean = when (m.rule) {
        insetRule -> m.file !in insetAllowed
        in roundedRules -> m.file !in allowed && "${m.file}:${m.fn}" !in roundedAllowed
        else -> m.file !in allowed
    }

    @Test
    fun `the guard does see the shape it guards against`() {
        val upload = """
            Box(
                Modifier
                    .clip(RoundedCornerShape(MimirSpacing.radiusPill))
                    .background(MimirColors.GoldGlow)
                    .clickable(onClick = onClick)
                    .padding(horizontal = MimirSpacing.xl, vertical = MimirSpacing.md),
            )
        """.trimIndent()
        assertEquals(listOf("pill clip then background"), scan("Upload.kt", upload).map { it.rule })
        val named = """
            val shape = RoundedCornerShape(MimirSpacing.radiusPill)
            Box(Modifier.mimirActivatable(label = "x", onActivate = {}).clip(shape).background(fill))
        """.trimIndent()
        assertEquals(listOf("pill clip then background"), scan("Named.kt", named).map { it.rule })
        val ringed = """
            Modifier.mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Pill, fill = SolidColor(tint))
        """.trimIndent()
        assertEquals(listOf("pill ring with a fill"), scan("Ringed.kt", ringed).map { it.rule })
        val shaped = """
            Box(Modifier.pointerInput(Unit) { detectTapGestures { } }.background(tint, RoundedCornerShape(MimirSpacing.radiusPill)))
        """.trimIndent()
        assertEquals(listOf("pill-shaped background"), scan("Shaped.kt", shaped).map { it.rule })
        val inset = """
            Modifier.mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Pill, inset = MimirSpacing.xs)
        """.trimIndent()
        assertEquals(listOf(insetRule), scan("Inset.kt", inset).map { it.rule })
        val padTarget = """
            Modifier.activityPadTarget(ActivityPadKeys.Search, MimirRingShape.Pill, inset = MimirSpacing.xs)
        """.trimIndent()
        assertEquals(listOf(insetRule), scan("Pad.kt", padTarget).map { it.rule })
        // A Row ring may take an inset (a row inside a panel); a Pill ring with none is the plate's own edge.
        val rowInset = """
            Modifier.mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Row(MimirSpacing.xs), inset = MimirSpacing.xs)
        """.trimIndent()
        assertEquals(emptyList<Match>(), scan("RowInset.kt", rowInset))
        val bare = """
            Modifier.mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Pill)
        """.trimIndent()
        assertEquals(emptyList<Match>(), scan("Bare.kt", bare))
        val washed = """
            Box(
                Modifier
                    .mimirEtchedChipSurface(palette, warmed = false)
                    .mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Pill)
                    .padding(8.dp),
            )
        """.trimIndent()
        assertEquals(listOf(orderRule), scan("Washed.kt", washed).map { it.rule })
        val onPlate = """
            Box(
                Modifier
                    .mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Pill)
                    .mimirEtchedChipSurface(palette, primary = true)
                    .padding(8.dp),
            )
            Row(Modifier.mimirEtchedChipSurface(palette)) { Text("x") }
            Modifier.mimirSelectionRing(focused = false, identity = other, shape = MimirRingShape.Pill)
        """.trimIndent()
        assertEquals(emptyList<Match>(), scan("OnPlate.kt", onPlate))
        // The etched chip, a badge that does not press, and a card-cornered fill are not matched.
        val chip = """
            Box(Modifier.mimirActivatable(label = "x", onActivate = {}).mimirEtchedChipSurface(palette, warmed = false).padding(8.dp))
        """.trimIndent()
        assertEquals(emptyList<Match>(), scan("Chip.kt", chip))
        val badge = """
            Box(Modifier.clip(RoundedCornerShape(MimirSpacing.radiusPill)).background(tint).padding(2.dp)) { Text("12") }
        """.trimIndent()
        assertEquals(emptyList<Match>(), scan("Badge.kt", badge))
        // A card-cornered fill that presses is matched since the earlier update (a rounded rectangle is what a Close was too): a card, row or tile is exempted by name in roundedAllowed, with its reason.
        val card = """
            @Composable
            fun SomeCard() {
                val shape = RoundedCornerShape(10.dp)
                Box(Modifier.mimirActivatable(label = "x", onActivate = {}).clip(shape).background(fill))
            }
        """.trimIndent()
        assertEquals(listOf(roundedClipRule), scan("Card.kt", card).map { it.rule })
        assertEquals(listOf("SomeCard"), scan("Card.kt", card).map { it.fn })
        // Not pressable (a panel, a badge), not rounded (a scrim), and a ring that paints no fill: none matched.
        val panel = """
            Column(Modifier.clip(RoundedCornerShape(MimirSpacing.radiusCard)).background(MimirColors.SurfaceRaised).padding(MimirSpacing.xl)) { Text("x") }
            Box(Modifier.fillMaxSize().background(scrim).clickable(onClick = {}))
            Box(Modifier.mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Row(6.dp), filled = false).mimirActivatable(label = "x", onActivate = {}))
            Box(Modifier.mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Row(6.dp), restingOutline = true).mimirActivatable(label = "x", onActivate = {}))
        """.trimIndent()
        assertEquals(emptyList<Match>(), scan("Panel.kt", panel))
        // The Utility footer's Close and Allow as they stood at a1c644324 (update): the guard fails on exactly this source, by both rules that see a filled rounded button.
        val old = scan("BrightnessOverlay.kt", OldUtilityActionSource)
        assertEquals(listOf(ringFillRule, roundedClipRule), old.map { it.rule })
        assertEquals(listOf("UtilityAction", "UtilityAction"), old.map { it.fn })
        assertTrue("the old UtilityAction is unexpected: BrightnessOverlay.kt is allowed nothing", old.all(::isUnexpected))
        // The import page's and the permission prompt's buttons were filled by the RING (a Row shape and a fill brush); the compact toolbar's by an accent gradient.
        val ringFilled = """
            @Composable
            private fun ActionButton() {
                Box(
                    modifier
                        .mimirSelectionRing(
                            focused = focused,
                            identity = key,
                            shape = MimirRingShape.Row(MimirSpacing.radiusCard),
                            fill = SolidColor(if (primary) MimirColors.GoldGlow else MimirColors.SurfaceHigh),
                            restingOutline = true,
                        )
                        .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                        .mimirActivatable(label = label, onActivate = onClick)
                        .padding(horizontal = MimirSpacing.lg, vertical = MimirSpacing.md),
                ) { Text(label) }
            }
        """.trimIndent()
        assertEquals(listOf(ringFillRule), scan("Ringed.kt", ringFilled).map { it.rule })
        val gradient = """
            Box(
                modifier = Modifier
                    .mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Row(MimirBoxArtCorner), filled = true)
                    .clip(RoundedCornerShape(MimirBoxArtCorner))
                    .background(Brush.verticalGradient(palette.id.ctaMaterial()))
                    .border(BorderStroke(1.dp, palette.outline), RoundedCornerShape(MimirBoxArtCorner))
                    .mimirFocusable(authority, key, scope)
                    .mimirActivatable(label, onActivate = onActivate),
            )
        """.trimIndent()
        assertEquals(listOf(ringFillRule, roundedClipRule), scan("Gradient.kt", gradient).map { it.rule })
        val shapedRounded = """
            val shape = RoundedCornerShape(12.dp)
            Box(Modifier.background(if (focused) a else b, shape).clickable(onClick = onClick))
            Box(Modifier.background(tint, RoundedCornerShape(8.dp)).mimirActivatable(label = "x", onActivate = {}))
        """.trimIndent()
        assertEquals(listOf(roundedShapedRule, roundedShapedRule), scan("Shaped2.kt", shapedRounded).map { it.rule })
        // A filled capsule written as a half-height corner (the game page's button), not as the pill radius token.
        val half = """
            val shape = RoundedCornerShape(percent = 50)
            Box(Modifier.clip(shape).background(fill).mimirFocusable(authority, key, scope).clickable(onClick = onClick))
            Box(Modifier.clip(RoundedCornerShape(50)).background(fill).clickable(onClick = onClick))
        """.trimIndent()
        assertEquals(listOf("pill clip then background", "pill clip then background"), scan("Half.kt", half).map { it.rule })
    }

    // ── the scan ────────────────────────────────────────────────────────────────────────────────────

    private fun scan(file: String, raw: String): List<Match> {
        val text = blankComments(raw)
        val lines = text.lines()
        val out = mutableListOf<Match>()
        fun add(at: Int, rule: String) {
            out += Match(file, lineOf(text, at), rule, enclosingFunction(text, at))
        }
        Regex("""\.mimirSelectionRing\(""").findAll(text).forEach { m ->
            val end = balancedEnd(text, m.range.last + 1)
            val args = text.substring(m.range.last + 1, end - 1)
            val fills = Regex("""\bfill\s*=""").containsMatchIn(args)
            if ("MimirRingShape.Pill" in args && fills) {
                add(m.range.first, "pill ring with a fill")
            } else if ("MimirRingShape.Pill" !in args &&
                (fills || Regex("""\bfilled\s*=(?!\s*false\b)""").containsMatchIn(args)) &&
                pressableChain(lines, text, m.range.first)
            ) {
                add(m.range.first, ringFillRule)
            }
        }
        Regex("""\.(mimirSelectionRing|activityPadTarget)\(""").findAll(text).forEach { m ->
            val end = balancedEnd(text, m.range.last + 1)
            val args = text.substring(m.range.last + 1, end - 1)
            if ("MimirRingShape.Pill" in args && Regex("""\binset\s*=""").containsMatchIn(args)) {
                add(m.range.first, insetRule)
            }
        }
        Regex("""\.mimirEtchedChipSurface\(""").findAll(text).forEach { m ->
            val end = balancedEnd(text, m.range.last + 1)
            val after = text.substring(end).lines()
            val first = after.first().trim()
            if (first.isNotEmpty() && !first.startsWith(".")) return@forEach
            val chain = StringBuilder(first)
            for (line in after.drop(1)) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (!trimmed.startsWith(".")) break
                chain.append('\n').append(trimmed)
            }
            if (".mimirSelectionRing(" in chain) add(m.range.first, orderRule)
        }
        Regex("""\.clip\(""").findAll(text).forEach { m ->
            val end = balancedEnd(text, m.range.last + 1)
            val arg = text.substring(m.range.last + 1, end - 1).trim()
            // A comment between the two calls is blanked to spaces, so skip however much whitespace there is before the next call.
            var after = end
            while (after < text.length && text[after].isWhitespace()) after++
            if (!text.startsWith(".background(", after)) return@forEach
            if (isPill(arg, text, m.range.first)) {
                if (pressableAround(text, m.range.first) || pressableChain(lines, text, m.range.first)) add(m.range.first, "pill clip then background")
            } else if (isRounded(arg, text, m.range.first) && pressableChain(lines, text, m.range.first)) {
                add(m.range.first, roundedClipRule)
            }
        }
        Regex("""\.background\(""").findAll(text).forEach { m ->
            val end = balancedEnd(text, m.range.last + 1)
            val args = text.substring(m.range.last + 1, end - 1).trim().trimEnd(',').trim()
            val shapeArg = args.substringAfterLast(',', "").trim()
            if (shapeArg.isEmpty() || ',' !in args) return@forEach
            if (isPill(shapeArg, text, m.range.first)) {
                if (pressableAround(text, m.range.first) || pressableChain(lines, text, m.range.first)) add(m.range.first, "pill-shaped background")
            } else if (isRounded(shapeArg, text, m.range.first) && pressableChain(lines, text, m.range.first)) {
                add(m.range.first, roundedShapedRule)
            }
        }
        return out
    }

    /** A pill: the pill radius, a 999 dp one, a half-height one (`percent = 50`), or a name whose nearest `val` above is one. A card's 10 dp or 14 dp corner is not. */
    private fun isPill(arg: String, text: String, at: Int): Boolean {
        if (pillValue(arg)) return true
        if (!Regex("""\w+""").matches(arg)) return false
        val declaration = Regex("""val\s+${Regex.escape(arg)}\s*=\s*([^\n]+)""").findAll(text.substring(0, at)).lastOrNull() ?: return false
        return pillValue(declaration.groupValues[1])
    }

    private fun pillValue(value: String): Boolean =
        "radiusPill" in value || "999" in value ||
            Regex("""RoundedCornerShape\(\s*(percent\s*=\s*)?50\s*\)""").containsMatchIn(value)

    /** Any rounded rectangle: a `RoundedCornerShape(...)` argument, or a name whose nearest `val` above is one. */
    private fun isRounded(arg: String, text: String, at: Int): Boolean {
        if ("RoundedCornerShape(" in arg) return true
        if (!Regex("""\w+""").matches(arg)) return false
        val declaration = Regex("""val\s+${Regex.escape(arg)}\s*=\s*([^\n]+)""").findAll(text.substring(0, at)).lastOrNull() ?: return false
        return "RoundedCornerShape(" in declaration.groupValues[1]
    }

    /** The function the call at [at] sits in: the nearest `fun name(` above it. */
    private fun enclosingFunction(text: String, at: Int): String =
        Regex("""\bfun\s+(?:[\w.<>?]+\.)?(\w+)\s*\(""").findAll(text.substring(0, at)).lastOrNull()?.groupValues?.get(1) ?: "?"

    /** Its chain presses: a tap or a focus registration within a few lines either side. */
    private fun pressableAround(text: String, at: Int): Boolean {
        val lines = text.lines()
        val line = lineOf(text, at) - 1
        val window = lines.subList(maxOf(0, line - 6), minOf(lines.size, line + 7)).joinToString("\n")
        return pressable.containsMatchIn(window)
    }

    /**
     * Its OWN modifier chain presses: the `.`-continued lines around the call at [at] (and the argument lists they open or close), from the line that heads the chain to the
     * line after its last call. A card or tile whose tap is a few calls away still reads as pressable; a neighbour's tap in the next statement does not.
     */
    private fun pressableChain(lines: List<String>, text: String, at: Int): Boolean {
        val index = lineOf(text, at) - 1
        val chain = StringBuilder(lines[index])
        var depth = 0
        var j = index - 1
        while (j >= 0) {
            val code = lines[j].trim()
            if (code.isEmpty()) {
                j--
                continue
            }
            depth += closes(lines[j]) - opens(lines[j])
            chain.append('\n').append(lines[j])
            if (depth > 0 || code.startsWith(".")) j-- else break
        }
        depth = opens(lines[index]) - closes(lines[index])
        j = index + 1
        while (j < lines.size) {
            val code = lines[j].trim()
            if (code.isEmpty()) {
                j++
                continue
            }
            if (depth > 0 || code.startsWith(".")) {
                depth += opens(lines[j]) - closes(lines[j])
                chain.append('\n').append(lines[j])
                j++
            } else {
                break
            }
        }
        return pressable.containsMatchIn(chain)
    }

    private fun opens(line: String): Int = line.count { it == '(' || it == '{' || it == '[' }

    private fun closes(line: String): Int = line.count { it == ')' || it == '}' || it == ']' }

    private fun balancedEnd(text: String, start: Int): Int {
        var depth = 1
        var i = start
        while (depth > 0 && i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return i
    }

    private fun lineOf(text: String, index: Int): Int = text.substring(0, index).count { it == '\n' } + 1

    private fun blankComments(raw: String): String {
        val noBlocks = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).replace(raw) { it.value.replace(Regex("[^\n]"), " ") }
        return Regex("""//[^\n]*""").replace(noBlocks) { " ".repeat(it.value.length) }
    }

    // ── the sources ─────────────────────────────────────────────────────────────────────────────────

    private fun productionSources(): List<Pair<String, String>> {
        val base = listOf(File("."), File("..")).first { File(it, "feature-files/src/main").isDirectory }
        return modules.flatMap { module ->
            File(base, "$module/src/main").walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { it.name to it.readText() }
                .toList()
        }
    }

    /** `UtilityAction` as it stood at a1c644324, before update made it the etched chip: a `surfaceHigh` / accent filled rounded rectangle with a Row ring that painted its fill. */
    private val OldUtilityActionSource = """
@Composable
private fun UtilityAction(
    label: String,
    primary: Boolean,
    focused: Boolean,
    palette: MimirThemePalette,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(UtilityRowCorner)
    // Utility lives on authored dark glass. When Folkvangr and Baldr were LIGHT they carried pale
    // `surfaceHigh` roles, so pairing that field fill with their on-glass ink made the fixed Close
    // control nearly disappear. Both are dark METALLIC now and no palette sets a bright field, so this
    // branch is currently inert — kept because `usesBrightField` remains an authored posture.
    val secondaryFill = if (palette.usesBrightField) palette.materials.hero.coverMiddle else palette.surfaceHigh
    val secondaryOutline = if (palette.usesBrightField) {
        palette.textSecondary.copy(alpha = 0.35f)
    } else {
        palette.outline
    }
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            // The one ring on an action (ring migration, 2026-09-28): a row ring on the action's corner that flares on
            // arrival (a footer of at most two). The primary keeps its lit-vs-resting fill pair (owner 2026-08-12) and
            // takes no wash over it; the secondary keeps its outline at rest. The disabled dim sits after the ring.
            .mimirSelectionRing(
                focused = focused,
                identity = label,
                shape = MimirRingShape.Row(UtilityRowCorner),
                filled = primary,
                flare = true,
            )
            .graphicsLayer {
                alpha = if (enabled) 1f else 0.46f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .clip(shape)
            .background(
                when {
                    primary && focused -> utilityPrimaryActionFocusedFill(palette)
                    primary -> utilityPrimaryActionRestingFill(palette)
                    else -> secondaryFill
                },
            )
            .border(
                width = 1.dp,
                color = if (primary) palette.accentBright else secondaryOutline,
                shape = shape,
            )
            // mimirActivatable, not clickable: `modifier` carries the host's mimirFocusable (one focus node).
            .mimirActivatable(label = label, enabled = enabled, onActivate = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        MimirChromeText(
            text = label,
            style = MimirTypeScale.MenuAction,
            color = if (primary) palette.onAccent else palette.textPrimary,
            maxLines = 1,
        )
    }
}
"""
}
