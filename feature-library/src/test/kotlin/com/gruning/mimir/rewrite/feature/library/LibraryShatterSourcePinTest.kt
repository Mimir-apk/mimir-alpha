package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Every hide shatters the card" (walk-32h): the words the player reads, and the source contracts a JVM test can hold
 * for the parts that are Compose. Every anchor is presence-checked first, so a rename fails here loudly and never turns
 * a pin into a vacuous pass. Comments are stripped before a scan, because a KDoc naming a retired call is prose.
 */
class LibraryShatterSourcePinTest {

    private val newFiles = listOf(
        "LibraryShatterGeometry.kt",
        "LibraryShatterOverlay.kt",
        "LibraryShatterCapture.kt",
        "LibraryShatterStage.kt",
        "LibraryHideCopy.kt",
    )

    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "core-ui").isDirectory) return dir
            dir = dir.parentFile
        }
        error("repository root not found from ${File(".").absolutePath}")
    }

    private fun stripComments(text: String): String = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)) { m -> m.value.replace(Regex("[^\n]"), " ") }
        .lineSequence()
        .joinToString("\n") { line -> line.substringBefore("//") }

    /** The text from [start] to the next [end]; fails loudly when either anchor is missing (substringAfter would not). */
    private fun slice(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("anchor present: $start", from >= 0)
        val to = text.indexOf(end, from + start.length)
        assertTrue("anchor present: $end", to >= 0)
        return text.substring(from, to)
    }

    private fun libraryMain(name: String): String =
        File(repoRoot(), "feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name").readText()

    private fun code(name: String): String = stripComments(libraryMain(name))

    /** The text with every run of whitespace made one space, so a call broken over lines is matched as it reads. */
    private fun flat(text: String): String = text.replace(Regex("""\s+"""), " ")

    // ── the words ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a hidden game's receipt and prompt name the place it is restored from`() {
        assertEquals("Settings › Library › Hidden games", LibraryHideCopy.HiddenGamesPlace)
        assertEquals("Hidden · Restore it in Settings › Library › Hidden games", LibraryHideCopy.GameReceipt)
        assertEquals(
            "It stays on your storage. Restore it later from Settings › Library › Hidden games.",
            LibraryHideCopy.GamePromptBody,
        )
        assertFalse("the prompt no longer sends the player to Browse", LibraryHideCopy.GamePromptBody.contains("Browse"))
    }

    @Test
    fun `every word of the place a hidden game returns from is a title the player sees there`() {
        val root = repoRoot()
        val detail = File(root, "feature-settings/src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/SettingsDetail.kt").readText()
        assertTrue("the Settings Library panel titles its section \"Library\"", detail.contains("SettingsSection(title = \"Library\")"))
        assertTrue("the Library panel has a \"Hidden games\" section", detail.contains("SettingsSection(title = \"Hidden games\")"))
        assertTrue(
            "and that section restores a game",
            detail.substringAfter("SettingsSection(title = \"Hidden games\")").take(2500).contains("RowAction(\"Restore\")"),
        )
        val copy = File(root, "core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirCopy.kt").readText()
        assertTrue("the rail says Library", copy.contains("const val RailLibrary = \"Library\""))
        val ia = File(root, "feature-settings/src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/SettingsIa.kt").readText()
        assertTrue("the Library category is the rail's label", ia.contains("label = MimirCopy.RailLibrary"))
        assertTrue(LibraryHideCopy.HiddenGamesPlace.startsWith("Settings › Library › "))
        assertTrue(LibraryHideCopy.HiddenGamesPlace.endsWith("Hidden games"))
    }

    @Test
    fun `a hidden Android game or app's receipt names the one way back, Add game on the Android games shelf`() {
        assertEquals("Library › Android games › Add game", LibraryHideCopy.NativeReturnPlace)
        assertEquals("Hidden · Add it back from Library › Android games › Add game", LibraryHideCopy.NativeReceipt)
        assertEquals("It stays installed. Add it back later from Library › Android games › Add game.", LibraryHideCopy.NativePromptBody)
        // The pill the receipt names, and the shelf it sits on, are the ones the Library really shows.
        assertEquals("Add game", LibraryShelfMode.ANDROID_GAMES.nativeAddOwnerCopy().addLabel)
        assertEquals("Android games", LibraryShelfMode.ANDROID_GAMES.stripLabel.lowercase().replaceFirstChar { it.uppercase() })
        assertFalse("no Add app pill exists any more", LibraryHideCopy.NativePromptBody.contains("Add app"))
        // The way back is real: the picker's choice calls addNativeShelfPackageToKind, the only un-hide there is.
        val root = repoRoot()
        val packages = File(root, "app/src/main/kotlin/com/gruning/mimir/rewrite/NativeShelfPackages.kt").readText()
        assertTrue(packages.contains("fun addNativeShelfPackageToKind("))
        val coordinator = File(root, "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirNativeShelfCoordinator.kt").readText()
        assertEquals(
            "addNativeShelfPackageToKind is called from one place, the picker's choice",
            1,
            Regex("""addNativeShelfPackageToKind\(context""").findAll(stripComments(coordinator)).count(),
        )
    }

    @Test
    fun `the prompts and the receipt come from the one copy, and the old words are gone`() {
        val screen = libraryMain("LibraryScreen.kt")
        assertTrue(screen.contains("LibraryHideCopy.GamePromptBody"))
        assertTrue(screen.contains("LibraryHideCopy.NativePromptBody"))
        assertTrue("the slot posts the game receipt", screen.contains("viewModel.showHideReceipt(LibraryHideCopy.GameReceipt)"))
        assertFalse("the prompt no longer names Browse", screen.contains("Restore it later from Browse"))
        // A hidden Android game or app says where it went from the hide itself: the slot posts it (at once when the card is not
        // broken, as the last shard fades when it is), and the view model posts it when no slot has taken the hide over.
        // Neither confirm path (the touch overlay, the controller's confirm) posts it on its own.
        assertEquals("the native slot posts it", 1, Regex("""showHideReceipt\(LibraryHideCopy\.NativeReceipt\)""").findAll(screen).count())
        assertEquals(0, Regex("""showHideReceipt\(LibraryHideCopy\.NativeReceipt\)""").findAll(libraryMain("LibraryDispatch.kt")).count())
        assertEquals(
            "the view model posts it, and only when no slot has taken the hide over",
            1,
            Regex("""showHideReceipt\(LibraryHideCopy\.NativeReceipt\)""").findAll(code("LibraryViewModel.kt")).count(),
        )
        assertFalse("nor an Add app pill that no longer exists", screen.contains("recoveryAddLabel") || screen.contains("nativeRecoveryAddLabel"))
        val viewModel = libraryMain("LibraryViewModel.kt")
        val receipt = slice(viewModel, "internal fun showHideReceipt(", "\n    }\n")
        assertTrue("the receipt is a confirmation", receipt.contains("MimirMessageClass.Confirmation"))
        assertTrue("on the Library's one toast rail", receipt.contains("_parkedRefusalToast.value"))
    }

    @Test
    fun `only a hide awaits the slot, and only the slot publishes a job, so SELECT's un-favourite keeps the dissolve`() {
        val viewModel = code("LibraryViewModel.kt")
        assertEquals("one place awaits the slot", 1, Regex("""departureSlot\?\.invoke""").findAll(viewModel).count())
        val hideGame = slice(viewModel, "internal fun hideGame(", "private fun markPendingRemoval(")
        assertTrue("and it is hideGame", hideGame.contains("departureSlot?.invoke(game)"))
        val toggle = slice(viewModel, "val leavesTheView", "return nowFavorite")
        assertTrue("the un-favourite path is present", toggle.contains("LibraryRemovalKind.UNFAVORITED"))
        assertFalse("and it neither awaits the slot nor says a game was hidden", toggle.contains("departureSlot") || toggle.contains("showHideReceipt"))
        val stage = code("LibraryShatterStage.kt")
        assertEquals("only the slot's start publishes a job", 1, Regex("""job = LibraryShatterJob\(""").findAll(stage).count())
        val screen = libraryMain("LibraryScreen.kt")
        assertTrue("the tracker asks the stage", screen.contains("shatters = shatterStage::shatters"))
        assertEquals(
            "the screen sets the slot in one place",
            1,
            Regex("""viewModel\.departureSlot = slot""").findAll(stripComments(screen)).count(),
        )
        assertEquals(
            "and the native slot in one place, cleared with it",
            1,
            Regex("""viewModel\.nativeDepartureSlot = nativeSlot""").findAll(stripComments(screen)).count(),
        )
        assertTrue(stripComments(screen).contains("if (viewModel.nativeDepartureSlot === nativeSlot) viewModel.nativeDepartureSlot = null"))
    }

    @Test
    fun `the native hide is one function on both paths, and it awaits the native slot in the one place`() {
        val screen = code("LibraryScreen.kt")
        val dispatch = code("LibraryDispatch.kt")
        assertEquals("the touch overlay confirms through it", 1, Regex("""confirmNativeHide\(""").findAll(screen).count())
        assertEquals("the controller's confirm too (and the definition)", 2, Regex("""confirmNativeHide\(""").findAll(dispatch).count())
        assertTrue(
            "both hand it the coordinator's one write",
            screen.contains("write = onConfirmHideNativePackage") && dispatch.contains("write = onConfirmHideNativePackage"),
        )
        val directCall = Regex("""(?<![\w.])onConfirmHideNativePackage\(\)""")
        assertFalse("neither path calls the write directly any more", directCall.containsMatchIn(screen) || directCall.containsMatchIn(dispatch))
        val viewModel = code("LibraryViewModel.kt")
        assertEquals("one place awaits the native slot", 1, Regex("""val slot = nativeDepartureSlot""").findAll(viewModel).count())
        val hide = slice(viewModel, "internal fun hideNativePackage(", "var nativeDepartureSlot")
        val slot = hide.indexOf("slot(packageName)")
        val before = hide.indexOf("beforeWrite()")
        val write = hide.indexOf("write()")
        assertTrue("the slot, then the hand-off, then the write", slot in 0 until before && before < write)
        assertTrue("undispatched, like hideGame, so a hide with no slot has run when it returns", hide.contains("CoroutineStart.UNDISPATCHED"))
        // What the player sees and what the hide hands focus to are one list.
        assertTrue(
            "the Favorites list is painted from the shared definition",
            screen.contains("nativeFavoriteRowsShown(favoriteSegment, nativeFavoriteGameRows, nativeFavoriteAppRows, searchQuery)"),
        )
        assertTrue("and the hide chooses its survivor from it", dispatch.contains("nativeFavoriteRowsShown("))
    }

    @Test
    fun `the stage lives above both surfaces, and the native tile and rows break from it`() {
        val screen = code("LibraryScreen.kt")
        assertEquals("one stage", 1, Regex("""remember \{ LibraryShatterStage\(\) \}""").findAll(screen).count())
        val stage = screen.indexOf("remember { LibraryShatterStage() }")
        val shelfSurface = screen.indexOf("LibraryPrimarySurfaceKey.Shelf -> {")
        val gamelistSurface = screen.indexOf("LibraryPrimarySurfaceKey.Gamelist -> {")
        assertTrue("the surface anchors are present", shelfSurface > 0 && gamelistSurface > shelfSurface)
        assertTrue("created before either surface, not inside one", stage in 0 until shelfSurface)
        assertEquals("each surface says how many cards it has, and whether it seats a departure", 2, Regex("""shatterStage\.seatsDeparture =""").findAll(screen).count())
        assertEquals(2, Regex("""shatterStage\.gameCount =""").findAll(screen).count())
        assertTrue("the shelf's answer is in the shelf's branch", screen.indexOf("shatterStage.seatsDeparture = false") in shelfSurface until gamelistSurface)
        assertEquals(
            "the shelf's centred tile draws through the layer, inside the tile's corner",
            1,
            Regex("""rememberLibraryShatterPanel\( shatter, active = isCentered, gameId = packageName, corner = PlatformHeroFaceCorner, \)""").findAll(flat(screen)).count(),
        )
        assertEquals(
            "a Favorites Android row draws through it while focused, inside the row's corner",
            1,
            Regex("""rememberLibraryShatterPanel\( shatter, active = focused == key, gameId = row\.packageName, corner = LibraryListRowCorner, \)""").findAll(flat(screen)).count(),
        )
        val favouriteRow = slice(screen, "private fun NativeFavoriteRow(", "private fun NativeFavoriteIcon(")
        assertTrue("its panel is at the head of the row's chain, so the ring and the pin break with it", favouriteRow.contains("modifier = modifier\n            .fillMaxWidth()"))
        val coverflow = code("NativeShelfCoverflow.kt")
        assertEquals("the coverflow asks its caller for each tile's panel", 1, Regex("""faceModifier = tilePanel\(isCentered, stop\.row\.packageName\)""").findAll(coverflow).count())
        val tile = code("PlatformHeroTile.kt")
        assertEquals(
            "the face carries the capture inside the scale it grows into focus with, outside what draws the face",
            1,
            Regex("""graphicsLayer \{ alpha = tileAlphaOf\(\) \}\s*\.then\(faceModifier\)""").findAll(tile).count(),
        )
        val stageCode = code("LibraryShatterStage.kt")
        assertTrue("the place is asked of the live coordinates when the picture is taken", stageCode.contains("centreCoordinates?.takeIf { it.isAttached }?.boundsInWindow() ?: centreBounds"))
        assertTrue("a host that leaves takes its job with it", stageCode.contains("DisposableEffect(job) { onDispose { stage.finish(job) } }"))
    }

    @Test
    fun `the carousel, the list and the Favorites list each play a hide on the stage, and no view plays it twice`() {
        val screen = libraryMain("LibraryScreen.kt")
        assertEquals(
            "the stage is handed to the carousel, the list, the Favorites list and the native shelf",
            4,
            Regex("""shatter = shatterStage,""").findAll(screen).count(),
        )
        assertTrue(
            "the list's focused row draws through the layer, inside the row's own corner",
            flat(screen).contains("rememberLibraryShatterPanel( shatter, active = focused == key, gameId = row.gameId.value, corner = LibraryListRowCorner, )"),
        )
        assertEquals("the list, the native Favorites rows and the shelf each host the overlay once", 3, Regex("""LibraryShatterHost\(shatter\)""").findAll(screen).count())
        assertEquals("the shelf host hosts it once", 1, Regex("""LibraryShatterHost\(shatter\)""").findAll(libraryMain("LibraryGameShelf.kt")).count())
        assertTrue(
            "the shelf's selected tile draws its art box through the same panel, and only while it stays",
            flat(libraryMain("GameShelf.kt")).contains("rememberLibraryShatterPanel( stage = shatter, active = selected && !leaving, gameId = item.stableId,"),
        )
        assertEquals("and hands it to both painters as their art modifier", 2, Regex("""artModifier = shatterPanel""").findAll(libraryMain("GameShelf.kt")).count())
        assertTrue("the Favorites list is the list view", screen.contains("FavoriteSegment.GAMES -> GamelistView("))
    }

    // ── the source ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the overlay reads its one clock in the draw block and nowhere else`() {
        val overlay = code("LibraryShatterOverlay.kt")
        val reads = Regex("""clock\.value""").findAll(overlay).toList()
        assertEquals("one read of the clock", 1, reads.size)
        val canvas = overlay.indexOf("Canvas(")
        assertTrue("the Canvas anchor is present", canvas >= 0)
        assertTrue("the read is inside the Canvas's draw block", reads.single().range.first > canvas)
        for (banned in listOf("clock.asState", "by clock", "collectAsState", "animateFloatAsState", "derivedStateOf", "snapshotFlow")) {
            assertFalse("the clock must not feed composition through $banned", overlay.contains(banned))
        }
        assertTrue("one Animatable is the whole clock", Regex("""Animatable\(""").findAll(overlay).count() == 1)
    }

    @Test
    fun `a frame allocates nothing and reads no state of its own`() {
        val overlay = code("LibraryShatterOverlay.kt")
        for (name in listOf("drawShatterStrike", "drawShatterShards")) {
            val start = overlay.indexOf("private fun DrawScope.$name(")
            assertTrue("$name is present", start >= 0)
            val next = overlay.indexOf("\nprivate fun ", start + 10).let { if (it < 0) overlay.length else it }
            val body = overlay.substring(start, next)
            val allocating = listOf(
                Regex("""\bPath\("""), Regex("""\bStroke\("""), Regex("""\blistOf\("""), Regex("""\barrayOf\("""),
                Regex("""\bmutableListOf"""), Regex("""\bFloatArray\("""), Regex("""\bArray\("""), Regex("""\.toList\("""),
                Regex("""\.map \{"""), Regex("""\bremember\b"""), Regex("""\bmutableStateOf"""), Regex("""\.value\b"""),
            )
            for (banned in allocating) assertFalse("$name must not use ${banned.pattern}", banned.containsMatchIn(body))
        }
        assertTrue(overlay.contains("withTransform("))
        assertTrue(overlay.contains("clipPath(kit.shardPaths[i])"))
    }

    @Test
    fun `the geometry is pure JVM, so a test can hold all of it`() {
        val geometry = libraryMain("LibraryShatterGeometry.kt")
        val imports = geometry.lines().filter { it.startsWith("import ") }
        assertTrue(imports.isNotEmpty())
        for (line in imports) {
            assertTrue("no Android or Compose type in the geometry: $line", !line.startsWith("import android") && !line.startsWith("import androidx"))
        }
    }

    @Test
    fun `the capture draws through its layer on every pass, so the recording is never dropped`() {
        val capture = code("LibraryShatterCapture.kt")
        assertTrue(capture.contains("drawWithContent"))
        assertTrue("the content is recorded into the layer", capture.contains("capture.layer.record {"))
        assertTrue("and the layer is drawn onto the screen by the same node", capture.contains("drawLayer(capture.layer)"))
        assertTrue("a replay outside the pass paints nothing", capture.contains("if (capture.recordingInPass) live.drawContent()"))
        assertTrue("the read is the layer's own", capture.contains("layer.toImageBitmap()"))
        assertTrue("a cancelled capture is cancelled, not swallowed", capture.contains("catch (cancelled: CancellationException)"))
    }

    @Test
    fun `no hex colour, no second focus engine, and no raw clock in the new files`() {
        val hex = Regex("""Color\(\s*0x""")
        val bannedFocus = listOf("focusable(", "focusTarget", "FocusRequester")
        val rawClock = Regex("""tween\s*\(\s*(?:durationMillis\s*=\s*)?\d+|delayMillis\s*=\s*\d+""")
        for (name in newFiles) {
            val text = code(name)
            assertTrue("$name is present and not empty", text.isNotBlank())
            assertFalse("$name holds a hex colour", hex.containsMatchIn(text))
            for (banned in bannedFocus) assertFalse("$name uses $banned", text.contains(banned))
            assertFalse("$name holds a raw clock", rawClock.containsMatchIn(text))
        }
        val overlay = code("LibraryShatterOverlay.kt")
        assertTrue("the flash, the cracks and the sparks are the theme's colours", overlay.contains("palette.accentBright") && overlay.contains("palette.accent"))
    }
}
