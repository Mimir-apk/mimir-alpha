package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2026-10-14 audit's fixes in the app module's Settings files, which a frame test cannot see: file work off the key press (RC-29), the exit sheet's scope opened from its
 * command (RC-50), the Equalizer editor's draw-phase reads (M-04). Anchored source pins, the idiom of this package, so a rename or a reflow reddens instead of passing silently.
 */
class SettingsMotionAuditAppSourcePinTest {

    private fun source(name: String): String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
    ).first(File::isFile).readText()

    private fun slice(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("anchor missing: $from", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("anchor missing: $to", end >= 0)
        return text.substring(start, end)
    }

    @Test
    fun `the pickers' remove and save-placement run their file work off the key press and land on the live state`() {
        val coordinator = source("MimirSettingsCoordinator.kt")
        val background = slice(coordinator, "is BackgroundPickerCommand.Remove -> scope.launch {", "BackgroundPickerCommand.Import ->")
        assertTrue(background.contains("withContext(Dispatchers.IO) { AppearanceOverrideStore.removeSavedBackground(context, command.id) }"))
        assertTrue("the picker is updated from its live state after the delete", background.contains("state.backgroundPicker?.let { live ->"))
        assertTrue(
            "and focus lands after the state is written",
            background.indexOf("state.backgroundPicker = live.copy(") < background.indexOf("landPickerFocus(BackgroundAtmosphereKey)"),
        )

        val adjusted = slice(coordinator, "is MimirButtonPickerCommand.SaveAdjusted -> scope.launch {", "is MimirButtonPickerCommand.LeaveAdjust ->")
        assertTrue(adjusted.contains("withContext(Dispatchers.IO) {"))
        val ioBlock = slice(adjusted, "withContext(Dispatchers.IO) {", "stored?.let")
        assertTrue("only the file half runs on IO", ioBlock.contains("AppearanceOverrideStore.saveMarkPlacement(context, command.mark.id, command.mark.markPlacement())"))
        assertFalse("the commit is not on IO", ioBlock.contains("setButtonMark"))
        val commit = adjusted.indexOf("stored?.let { AppearanceOverrideStore.setButtonMark(context, it) }")
        val pickerWrite = adjusted.indexOf("state.mimirButtonPicker?.let { live ->")
        assertTrue("the commit is on the main thread, just before the block that writes the picker's own state (W1A #4)", commit in 0 until pickerWrite)
        assertTrue(adjusted.indexOf("live.copy(saved = mimirButtonPickerMarks(), current = stored)") < adjusted.indexOf("landPickerFocus(mimirButtonTileKey(command.mark.id))"))

        val removeMark = slice(coordinator, "is MimirButtonPickerCommand.Remove -> scope.launch {", "MimirButtonPickerCommand.Import ->")
        assertTrue(removeMark.contains("withContext(Dispatchers.IO) { AppearanceOverrideStore.removeSavedMark(context, command.id) }"))
        assertTrue(removeMark.indexOf("live.copy(saved = mimirButtonPickerMarks())") < removeMark.indexOf("landPickerFocus(MimirButtonRuneKey)"))
        assertFalse(
            "no store call is left on the key's own thread",
            coordinator.lines().any { line ->
                (line.contains("AppearanceOverrideStore.removeSavedBackground(") || line.contains("AppearanceOverrideStore.removeSavedMark(")) &&
                    !line.contains("withContext(Dispatchers.IO)")
            },
        )
        assertFalse("the combined save-and-commit is not called from the coordinator", coordinator.contains("saveMarkPlacementAndUse("))

        val store = source("AppearanceOverrideStore.kt")
        assertTrue("the revision tick the commit takes is atomic", slice(store, "fun setButtonMark(", "fun saveMarkPlacementAndUse(").contains("_revision.update { it + 1 }"))
        assertFalse("the file half does not commit", slice(store, "fun saveMarkPlacement(", "/** Re-read both saved lists").contains("setButtonMark("))
    }

    @Test
    fun `the backup is read and parsed off the main thread`() {
        val launcher = slice(source("MimirMigrationCoordinator.kt"), "val openBundleLauncher = rememberLauncherForActivityResult(", "val createBundleLauncher =")
        val read = slice(launcher, "val parsed = withContext(Dispatchers.IO) {", "val next = if (parsed == null)")
        assertTrue(read.contains("openInputStream(uri)"))
        assertTrue(read.contains("MigrationBundleCodec.parse(text)"))
        assertFalse("the read is not outside the IO block", launcher.substringBefore("val parsed = withContext(Dispatchers.IO) {").contains("openInputStream"))
    }

    @Test
    fun `a dirty mapping opens its exit scope from the close command`() {
        val close = slice(source("MimirSettingsCoordinator.kt"), "val closeControllerMapping = {", "val discardControllerMapping = {")
        assertTrue(close.contains("openControllerMappingExitScope(authority)"))
        assertTrue("only from the mapper's own scope", close.contains("authority.state.value.scope == ControllerMappingScope"))
    }

    @Test
    fun `the Equalizer editor animates a band in the band's own scope and builds its edge masks once per height`() {
        val overlay = source("EqualizerEditorOverlay.kt")
        val card = slice(overlay, "private fun EqualizerEditorCard(", "private fun EqualizerBandRow(")
        assertFalse("the card's body no longer reads a band's glide", card.contains("val gliding by animateFloatAsState"))
        assertTrue(card.contains("EqualizerBandRow("))
        val band = slice(overlay, "private fun EqualizerBandRow(", "private fun Modifier.equalizerEdgeFade(")
        assertTrue(band.contains("val gliding by animateFloatAsState("))
        assertTrue(band.contains("fraction = shownFraction,"))

        val fade = slice(overlay, "private fun Modifier.equalizerEdgeFade(", "private fun Modifier.dimmedUnless(")
        assertTrue(fade.contains("val masks = remember { EqualizerEdgeMasks() }"))
        assertTrue(fade.contains("masks.fit(edge, size.height)"))
        assertFalse("no gradient is built per drawn frame", slice(fade, "drawWithContent {", "canvas.restore()").contains("Brush.verticalGradient"))
        // Policy 10-14, Q6: the editor's focus-follow scroll is on the focus clock and the house ease, not the default spring.
        assertTrue(overlay.contains("scroll.animateScrollTo(settled, tween(MimirMotion.FocusMs, easing = MimirMotion.Easing))"))
    }

    /** Audit 2026-10-15, F6: Cancel went to the neighbour's row (right for a removal, wrong for a folder that is still listed). The two exits read two different rows of one pure answer. */
    @Test
    fun `cancelling the removal returns to the row it came from and a removal to its neighbour`() {
        val coordinator = source("MimirSettingsCoordinator.kt")
        val cancel = slice(coordinator, "fun cancelSourceRemoval() {", "fun openSourceRemoval(")
        assertTrue("Cancel and B land on the pressed REMOVE row", cancel.contains("state.sourceRemovalReturn?.onCancel ?: LibraryAddRowId"))
        assertFalse("not the neighbour's row", cancel.contains("onRemoved"))
        val open = slice(coordinator, "fun openSourceRemoval(", "fun confirmSourceRemoval() {")
        assertTrue("both rows come from the one pure function", open.contains("state.sourceRemovalReturn = librarySourceRemovalReturn(library.readModel?.sources.orEmpty(), source)"))
        val confirm = slice(coordinator, "fun confirmSourceRemoval() {", "fun beginControllerShortcutCapture(")
        assertTrue("a removed folder hands the ring to its neighbour", confirm.contains("state.sourceRemovalReturn?.onRemoved ?: LibraryAddRowId"))
        assertFalse("not to the row that has just gone", confirm.contains("onCancel"))
        assertFalse("the single shared return row is retired", coordinator.contains("sourceRemovalReturnRowId"))
    }
}
