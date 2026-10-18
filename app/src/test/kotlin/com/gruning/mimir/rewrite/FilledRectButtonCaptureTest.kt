package com.gruning.mimir.rewrite

import android.animation.ValueAnimator
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.storage.DatabaseOpenFailure
import com.gruning.mimir.rewrite.core.model.storage.DatabaseOpenFailureReason
import com.gruning.mimir.rewrite.core.resolver.migration.ImportPlan
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.LocalMimirTextLayoutObserver
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of the buttons update converted from filled rounded rectangles to the etched chip (the earlier update's family), on Main's 853 x 480 dp (density 2): the Utility panel's footer (Close
 * and Allow) at rest, with each focused, with Close disabled and at 1.3x font scale; the artwork card, the storage prompt, the import page, the recovery gate, the overlay-permission
 * prompt and the diagnostics screen, each at rest and with a button focused. Drawn the way this repo rasterises (the view hierarchy into a software bitmap,
 * as [EtchedButtonCaptureTest] does), as API 30, the level at which the platform cannot blur. The PNGs land in `app/build/rrect/` and are copied to `build/test-captures/rrect/` when `build/test-captures` exists. Pixel values are the JVM's; the pixels on a handheld are
 * device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w853dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FilledRectButtonCaptureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun resetAnimators() = setAnimatorScale(1f)

    private fun setAnimatorScale(scale: Float) {
        ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
    }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs * 8L)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    private fun writePng(fileName: String, crop: androidx.compose.ui.geometry.Rect? = null) {
        val view = compose.activity.window.decorView
        val whole = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(whole))
        val bitmap = if (crop == null) {
            whole
        } else {
            val left = crop.left.toInt().coerceIn(0, whole.width - 1)
            val top = crop.top.toInt().coerceIn(0, whole.height - 1)
            Bitmap.createBitmap(whole, left, top, (crop.right.toInt().coerceAtMost(whole.width) - left).coerceAtLeast(1), (crop.bottom.toInt().coerceAtMost(whole.height) - top).coerceAtLeast(1))
        }
        val dir = File("build/rrect").apply { mkdirs() }
        val out = File(dir, fileName)
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Capture copy: on the Windows build host `build/test-captures` is a directory, so the pictures also land in `build/test-captures/rrect/`; elsewhere nothing is written outside `build/`.
        val shared = File("build/test-captures")
        if (shared.isDirectory) out.copyTo(File(shared, "rrect/$fileName").apply { parentFile.mkdirs() }, overwrite = true)
        println("RRECT wrote ${out.absolutePath} (${out.length()} bytes, ${bitmap.width}x${bitmap.height} px)")
        assertTrue("the PNG was written: ${out.path}", out.isFile && out.length() > 1_000L)
    }

    /**
     * Every etched chip on screen ([etchedChipPlateTag]'s plates) shows its whole label on one line: no wrap, no word dropped behind `maxLines = 1`, no width overflow (the earlier update's rule, issue
     * the earlier update's review: "Open settings" showed as "Open" at 1.3x). [chips] is how many plates the screen is expected to carry, so a chip that loses its tag cannot slip past.
     */
    private fun assertChipLabelsWhole(fontScale: Float, chips: Int, where: String) {
        val plates = mutableListOf<SemanticsNode>()
        fun findPlates(node: SemanticsNode) {
            if ((node.config.getOrNull(SemanticsProperties.TestTag) ?: "").startsWith("etched-chip-plate/")) plates += node
            node.children.forEach(::findPlates)
        }
        findPlates(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        assertEquals("$where: the chips on screen at ${fontScale}x: ${plates.map { it.config.getOrNull(SemanticsProperties.TestTag) }}", chips, plates.size)
        for (plate in plates) {
            val tag = plate.config.getOrNull(SemanticsProperties.TestTag)
            // The label's layout: a chrome label ([MimirChromeText]) clears its own semantics and reports its layout to the observer under its spoken text; a plain Text answers the semantics action.
            val layouts = mutableListOf<TextLayoutResult>()
            fun findText(node: SemanticsNode) {
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { spoken -> observedLayouts[spoken]?.let(layouts::add) }
                node.children.forEach(::findText)
            }
            findText(plate)
            assertTrue("$where: $tag has a label to measure", layouts.isNotEmpty())
            for (layout in layouts) {
                // Not `hasVisualOverflow`: a centred label with softWrap off is laid out at the row's whole width, so that flag is true for a label that is whole. The three facts that matter:
                // one line, every character on it, and the line's glyphs no wider than the node the label was given.
                val text = layout.layoutInput.text.text
                val lineWidth = layout.getLineRight(0) - layout.getLineLeft(0)
                assertTrue(
                    "$where: $tag's label is one whole line at ${fontScale}x (lines=${layout.lineCount}, visibleEnd=${layout.getLineEnd(0, visibleEnd = true)} of ${text.length}, " +
                        "lineWidth=$lineWidth, nodeWidth=${layout.size.width}, overflowH=${layout.didOverflowHeight}, '$text')",
                    layout.lineCount == 1 &&
                        layout.getLineEnd(0, visibleEnd = true) >= text.trimEnd().length &&
                        lineWidth <= layout.size.width + 1f &&
                        !layout.didOverflowHeight,
                )
            }
        }
    }

    /** The layouts the chrome labels reported, by spoken text (the latest of each). */
    private val observedLayouts = mutableMapOf<String, TextLayoutResult>()

    private fun mount(fontScale: Float = 1f, content: @androidx.compose.runtime.Composable () -> Unit) {
        setAnimatorScale(0f)
        compose.mainClock.autoAdvance = false
        observedLayouts.clear()
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 2f, fontScale = fontScale),
                LocalMimirViewportProfile provides mimirViewportProfile(853f, 480f),
                LocalMimirTextLayoutObserver provides { spoken, layout -> observedLayouts[spoken] = layout },
            ) {
                MimirTheme { content() }
            }
        }
    }

    // -- The Utility panel (update's proven case), as the Bottom draws it: 537.7 x 468.3 dp --

    /** The panel with Allow offered (the write grant is missing), its focus driven by [focus]; the footer's own tags are what the geometry reads. */
    private fun utilityPanel(focus: androidx.compose.runtime.State<BrightnessControlKey?>, fontScale: Float = 1f) {
        val state = calmShineTestBrightnessState().let { it.copy(capabilities = it.capabilities.copy(canWriteSystemSettings = false)) }
        mount(fontScale) {
            val palette = LocalMimirPalette.current
            Box(Modifier.requiredSize(853.dp, 480.dp).background(palette.surfaceBase), contentAlignment = Alignment.Center) {
                Box(Modifier.requiredSize(537.7.dp, 468.3.dp)) {
                    MimirBrightnessOverlay(
                        state = state,
                        focusedControl = focus.value,
                        onSetFraction = { _, _ -> },
                        onSetThemeAwareJoystickLight = {},
                        onRequestWriteAccess = {},
                        onRequestPrivilegedAccess = {},
                        surface = UtilitySurface.UTILITY,
                        onBack = {},
                        onClose = {},
                        controllerHintsAvailable = false,
                        enableBackHandler = false,
                        holdPad = false,
                    )
                }
            }
        }
        settle()
    }

    @Test
    fun `utility panel - the footer's Close and Allow are etched chips, at rest and each focused`() {
        val focus = mutableStateOf<BrightnessControlKey?>(null)
        utilityPanel(focus)
        writePng("rrect-utility-panel-rest.png")
        Snapshot.withMutableSnapshot { focus.value = BrightnessControlKey.ALLOW_SYSTEM_CHANGES }
        settle()
        writePng("rrect-utility-panel-allow-focused.png")
        Snapshot.withMutableSnapshot { focus.value = BrightnessControlKey.CLOSE }
        settle()
        writePng("rrect-utility-panel-close-focused.png")
    }

    private val hintText = MAIN_DISPLAY_ACCESS_MESSAGE

    private fun px(dp: androidx.compose.ui.unit.Dp): Float = with(Density(density = 2f)) { dp.toPx() }

    /**
     * The footer of the real panel at [fontScale]: a focused chip's halo (its plate grown by the ring's whole reach, [UtilityActionRingReach]) must clear the hint's words and the
     * neighbouring chip's plate, at rest and with each chip focused. The pictures are the footer's own band, cropped.
     */
    private fun footerHalosClear(fontScale: Float, suffix: String) {
        val focus = mutableStateOf<BrightnessControlKey?>(null)
        utilityPanel(focus, fontScale)
        assertChipLabelsWhole(fontScale, chips = 2, where = "utility footer")
        val reach = px(UtilityActionRingReach)
        fun plate(label: String) = boundsOf(utilityActionPlateTag(label))
        val hint = compose.onNodeWithText(hintText, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val footer = listOf(hint, plate("Allow"), plate("Close")).reduce { a, b ->
            androidx.compose.ui.geometry.Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
        }.inflate(reach + px(8.dp))
        writePng("rrect-utility-footer$suffix-rest.png", crop = footer)
        for ((key, label, other, name) in listOf(
            Quad(BrightnessControlKey.ALLOW_SYSTEM_CHANGES, "Allow", "Close", "allow-focused"),
            Quad(BrightnessControlKey.CLOSE, "Close", "Allow", "close-focused"),
        )) {
            Snapshot.withMutableSnapshot { focus.value = key }
            settle()
            val halo = plate(label).inflate(reach)
            assertTrue("$label's halo reaches the hint's words at ${fontScale}x: halo=$halo hint=$hint", !halo.overlaps(hint))
            assertTrue("$label's halo reaches $other's plate at ${fontScale}x: halo=$halo plate=${plate(other)}", !halo.overlaps(plate(other)))
            writePng("rrect-utility-footer$suffix-$name.png", crop = footer)
        }
    }

    private data class Quad(val key: BrightnessControlKey, val label: String, val other: String, val name: String)

    private fun boundsOf(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test
    fun `utility footer - at 1x a focused chip's halo clears the hint and the neighbouring chip`() = footerHalosClear(1f, "")

    @Test
    fun `utility footer - at 1_3x a focused chip's halo clears the hint and the neighbouring chip`() = footerHalosClear(1.3f, "-1_3x")

    private class FooterState(val allowFocused: Boolean, val closeFocused: Boolean, val allowEnabled: Boolean = true, val closeEnabled: Boolean = true)

    /**
     * The footer's two actions with the panel's own spacing ([UtilityActionRingReach] between the hint and Allow and between the chips), for the one state the real panel cannot be put in
     * without a Tweaker settlement: Close disabled. The dim is on the plate after the ring, so a disabled chip that holds focus keeps its ring whole.
     */
    @androidx.compose.runtime.Composable
    private fun footer(state: androidx.compose.runtime.State<FooterState>) {
        val palette = LocalMimirPalette.current
        Box(Modifier.requiredSize(853.dp, 480.dp).background(palette.surfaceBase), contentAlignment = Alignment.BottomCenter) {
            Row(
                modifier = Modifier.requiredSize(537.7.dp, 120.dp).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 23.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = hintText,
                    style = MimirTypeScale.Support,
                    color = palette.textMuted,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                )
                Spacer(Modifier.width(UtilityActionRingReach))
                UtilityAction(
                    label = "Allow",
                    primary = true,
                    focused = state.value.allowFocused,
                    palette = palette,
                    modifier = Modifier,
                    enabled = state.value.allowEnabled,
                    onClick = {},
                )
                Spacer(Modifier.width(UtilityActionRingReach))
                UtilityAction(
                    label = "Close",
                    primary = false,
                    focused = state.value.closeFocused,
                    palette = palette,
                    modifier = Modifier,
                    enabled = state.value.closeEnabled,
                    onClick = {},
                )
            }
        }
    }

    @Test
    fun `utility footer - Close disabled (a tweaker write is pending), alone and holding focus`() {
        val state = mutableStateOf(FooterState(allowFocused = false, closeFocused = false, closeEnabled = false))
        mount { footer(state) }
        settle()
        writePng("rrect-utility-footer-close-disabled.png")
        // A disabled chip that still holds focus keeps its ring whole: the dim is on the plate, after the ring.
        Snapshot.withMutableSnapshot { state.value = FooterState(allowFocused = false, closeFocused = true, closeEnabled = false) }
        settle()
        writePng("rrect-utility-footer-close-disabled-focused.png")
    }

    // -- The other converted screens --

    private fun artworkCardCaptures(fontScale: Float, suffix: String) {
        val authority = DefaultFocusAuthority()
        val state = mutableStateOf<ArtworkFetchUi>(ArtworkFetchUi.ConfirmNewGames(count = 12, hasAccount = true))
        mount(fontScale) {
            ArtworkFetchOverlay(
                state = state.value,
                onCancel = {},
                onClose = {},
                onConfirm = {},
                onDismiss = {},
                authority = authority,
            )
        }
        settle()
        assertChipLabelsWhole(fontScale, chips = 2, where = "artwork card (confirm)")
        // The card lands focus on "Not now".
        writePng("rrect-artwork-card$suffix-notnow-focused.png")
        compose.runOnIdle { authority.requestFocus(NewGameEnrichConfirmKey) }
        settle()
        writePng("rrect-artwork-card$suffix-primary-focused.png")
        Snapshot.withMutableSnapshot { state.value = ArtworkFetchUi.Done(total = 12, matched = 9) }
        settle()
        assertChipLabelsWhole(fontScale, chips = 1, where = "artwork card (done)")
        writePng("rrect-artwork-card$suffix-done-close.png")
        Snapshot.withMutableSnapshot { state.value = ArtworkFetchUi.Running(done = 3, total = 12, matched = 2) }
        settle()
        assertChipLabelsWhole(fontScale, chips = 1, where = "artwork card (running)")
        writePng("rrect-artwork-card$suffix-running-cancel.png")
    }

    @Test
    fun `artwork card - the primary answer above the plain one, then the primary focused, and the Done page's Close`() = artworkCardCaptures(1f, "")

    @Test
    fun `artwork card - at 1_3x every label is one whole line`() = artworkCardCaptures(1.3f, "-1_3x")

    private fun storagePromptCaptures(fontScale: Float, suffix: String) {
        val authority = DefaultFocusAuthority()
        val root = StoredRoot(
            rootUri = "content://com.android.externalstorage.documents/tree/7BF4-F932%3AROMs",
            locationKind = "SAF_TREE_GRANT",
            friendlyLabel = "SD card \u203a ROMs",
        )
        mount(fontScale) { StorageGrantPrompt(root = root, onReconnect = {}, onLater = {}, authority = authority) }
        settle()
        assertChipLabelsWhole(fontScale, chips = 2, where = "storage prompt")
        writePng("rrect-storage-prompt$suffix-reconnect-focused.png")
        compose.runOnIdle { authority.requestFocus(StorageGrantLaterKey) }
        settle()
        writePng("rrect-storage-prompt$suffix-later-focused.png")
    }

    @Test
    fun `storage prompt - Reconnect is the primary chip, Later the plain one, each lit only while focused`() = storagePromptCaptures(1f, "")

    @Test
    fun `storage prompt - at 1_3x every label is one whole line`() = storagePromptCaptures(1.3f, "-1_3x")

    private fun recoveryGateCaptures(fontScale: Float, suffix: String) {
        val authority = DefaultFocusAuthority()
        mount(fontScale) {
            DatabaseRecoveryGate(
                failure = DatabaseOpenFailure(
                    reason = DatabaseOpenFailureReason.NEWER_SCHEMA,
                    foundSchemaVersion = 10,
                    supportedSchemaVersion = 9,
                ),
                themeId = MimirThemeId.DEFAULT,
                authority = authority,
                onClose = {},
            )
        }
        settle()
        assertChipLabelsWhole(fontScale, chips = 1, where = "recovery gate")
        writePng("rrect-recovery-gate$suffix-close-focused.png")
    }

    @Test
    fun `recovery gate - the one Close is the plain chip, ringed once focus lands`() = recoveryGateCaptures(1f, "")

    @Test
    fun `recovery gate - at 1_3x the Close label is one whole line`() = recoveryGateCaptures(1.3f, "-1_3x")

    private fun diagnosticsCaptures(fontScale: Float, suffix: String) {
        mount(fontScale) { DiagnosticsScreen(onOpenSettings = {}, onOpenLibrary = {}, onOpenBrowse = {}) }
        settle()
        assertChipLabelsWhole(fontScale, chips = 3, where = "diagnostics")
        writePng("rrect-diagnostics-actions$suffix.png")
    }

    @Test
    fun `diagnostics - the three navigation actions are plain chips`() = diagnosticsCaptures(1f, "")

    @Test
    fun `diagnostics - at 1_3x every label is one whole line`() = diagnosticsCaptures(1.3f, "-1_3x")

    // -- Chips side by side: a focused chip's halo (the ring's whole reach past its plate) must clear what stands beside it --

    private fun importPage(fontScale: Float, authority: DefaultFocusAuthority) {
        mount(fontScale) {
            MigrationImportOverlay(
                state = MigrationUiState.Ready(
                    ImportPlan(
                        favorites = emptyList(),
                        hidden = emptyList(),
                        overrides = emptyList(),
                        theme = null,
                        ignoredThemeId = null,
                        sourceRootHints = emptyList(),
                        profileDisplayName = null,
                    ),
                ),
                authority = authority,
                onConfirm = {},
                onClose = {},
            )
        }
        settle()
        compose.runOnIdle { authority.pushScope(MigrationScope) }
    }

    private fun importHalosClear(fontScale: Float, suffix: String) {
        val authority = DefaultFocusAuthority()
        importPage(fontScale, authority)
        assertChipLabelsWhole(fontScale, chips = 2, where = "import page")
        val reach = px(MimirFocusTileRingReach)
        val bring = { boundsOf(etchedChipPlateTag("import", "Bring them over")) }
        val cancel = { boundsOf(etchedChipPlateTag("import", "Cancel")) }
        compose.runOnIdle { authority.requestFocus(FocusKey("migration/confirm")) }
        settle()
        assertTrue("Bring them over's halo reaches Cancel at ${fontScale}x", !bring().inflate(reach).overlaps(cancel()))
        writePng("rrect-import-page$suffix-confirm-focused.png")
        compose.runOnIdle { authority.requestFocus(FocusKey("migration/cancel")) }
        settle()
        assertTrue("Cancel's halo reaches Bring them over at ${fontScale}x", !cancel().inflate(reach).overlaps(bring()))
        writePng("rrect-import-page$suffix-cancel-focused.png")
    }

    @Test
    fun `import page - at 1x each focused chip's halo clears the other`() = importHalosClear(1f, "")

    @Test
    fun `import page - at 1_3x each focused chip's halo clears the other`() = importHalosClear(1.3f, "-1_3x")

    private fun permissionHalosClear(fontScale: Float, suffix: String) {
        val authority = DefaultFocusAuthority()
        mount(fontScale) { CompanionOverlayPermissionPrompt(authority = authority, onGrant = {}, onDismiss = {}) }
        settle()
        assertChipLabelsWhole(fontScale, chips = 2, where = "overlay permission prompt")
        val reach = px(MimirFocusTileRingReach)
        val notNow = { boundsOf(etchedChipPlateTag("permission", "Not now")) }
        val open = { boundsOf(etchedChipPlateTag("permission", "Open settings")) }
        compose.runOnIdle { authority.requestFocus(CompanionPermissionOpenSettingsKey) }
        settle()
        assertTrue("Open settings' halo reaches Not now at ${fontScale}x", !open().inflate(reach).overlaps(notNow()))
        writePng("rrect-permission-prompt$suffix-open-focused.png")
        compose.runOnIdle { authority.requestFocus(CompanionPermissionDismissKey) }
        settle()
        assertTrue("Not now's halo reaches Open settings at ${fontScale}x", !notNow().inflate(reach).overlaps(open()))
    }

    @Test
    fun `overlay permission prompt - at 1x each focused chip's halo clears the other`() = permissionHalosClear(1f, "")

    @Test
    fun `overlay permission prompt - at 1_3x each focused chip's halo clears the other`() = permissionHalosClear(1.3f, "-1_3x")

    @Test
    fun `import page - the failure page's one Close is the primary chip`() {
        val authority = DefaultFocusAuthority()
        mount { MigrationImportOverlay(state = MigrationUiState.Failed("That file isn’t a Mimir backup."), authority = authority, onConfirm = {}, onClose = {}) }
        settle()
        compose.runOnIdle {
            authority.pushScope(MigrationScope)
            authority.requestFocus(FocusKey("migration/close"))
        }
        settle()
        writePng("rrect-import-page-failed-close.png")
    }
}
