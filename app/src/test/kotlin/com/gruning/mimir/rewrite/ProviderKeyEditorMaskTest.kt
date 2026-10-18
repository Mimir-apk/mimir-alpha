package com.gruning.mimir.rewrite

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusScope
import com.gruning.mimir.rewrite.core.input.dispatchMimirKeyboardInput
import com.gruning.mimir.rewrite.core.input.mimirKeyboardFocusKey
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.settings.RetroAchievementsConnectUi
import com.gruning.mimir.rewrite.feature.settings.RetroAchievementsKeyEditOverlay
import com.gruning.mimir.rewrite.feature.settings.SteamGridDbKeyEditOverlay
import com.gruning.mimir.rewrite.feature.settings.clearRetroAchievementsConnectDraft
import com.gruning.mimir.rewrite.feature.settings.clearSteamGridDbKeyDraft
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * the RetroAchievements and SteamGridDB API-key editors showed their keys in plain text. They
 * are masked like the ScreenScraper password (MimirKeyboardSecretTest): one dot per character while typing or pasting,
 * and a saved key is never drawn in full.
 *
 * Every step goes through the real shared keyboard and the FocusAuthority controller path the app host wires, so the
 * keyboard and controller flow is pinned as unchanged too: Paste and Done still act, and the value that reaches the
 * host is the raw key, not the dots.
 *
 * Keys here are obviously fake fixtures. The leak assertion counts matches and never prints what it found.
 *
 * The screen is tall on purpose: the preview line above the keyboard only exists above settingsKeyboardPreviewCompact's
 * 420dp gate, and the dots assertion reads that line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w2000dp-h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProviderKeyEditorMaskTest {

    @get:Rule
    val compose = createComposeRule()

    private val authority = DefaultFocusAuthority()
    private var host: MimirKeyboardHost? = null
    private var clipboard: ClipboardManager? = null

    // Both draft stashes are process-lifetime objects; a leftover draft would seed the next test's editor.
    @Before
    fun resetDraftsBefore() {
        clearSteamGridDbKeyDraft()
        clearRetroAchievementsConnectDraft()
    }

    @After
    fun resetDraftsAfter() {
        clearSteamGridDbKeyDraft()
        clearRetroAchievementsConnectDraft()
    }

    @Test
    fun `a saved SteamGridDB key opens as dots and Done still keeps it`() {
        var saved: String? = null
        showSteamGridDbEditor(currentKey = SavedKey) { saved = it }

        assertNeverShown(SavedKey)
        assertDrawnAsDots(SavedKey.length)

        press("action/done")
        assertEquals("Done without an edit keeps the saved key", SavedKey, saved)
    }

    @Test
    fun `a stray Space then B keeps the saved SteamGridDB key`() {
        val saves = mutableListOf<String>()
        showSteamGridDbEditor(currentKey = SavedKey) { saves += it }
        press("action/space")
        assertDrawnAsDots(SavedKey.length)
        cancel()
        // OR-10 : B cancels, so nothing is written and the saved key is never cleared.
        assertEquals("B cancels without writing, never a blank clear", emptyList<String>(), saves)
    }

    @Test
    fun `one Backspace then B keeps the saved SteamGridDB key`() {
        val saves = mutableListOf<String>()
        showSteamGridDbEditor(currentKey = SavedKey) { saves += it }
        press("action/back")
        assertNeverShown(SavedKey)
        assertDrawnAsDots(SavedKey.length)
        cancel()
        // OR-10 : B cancels, so nothing is written and the saved key is never cleared.
        assertEquals("B cancels without writing, never a blank clear", emptyList<String>(), saves)
    }

    @Test
    fun `two Backspaces clear the saved SteamGridDB key`() {
        val cleared = mutableListOf<String>()
        showSteamGridDbEditor(currentKey = SavedKey) { cleared += it }
        press("action/back")
        press("action/back")
        press("action/done")
        assertEquals("a deliberate second delete still clears", listOf(""), cleared)
    }

    @Test
    fun `a key typed after one Backspace replaces the saved SteamGridDB key`() {
        val replaced = mutableListOf<String>()
        showSteamGridDbEditor(currentKey = SavedKey) { replaced += it }
        press("action/back")
        press("char/t")
        press("action/done")
        assertEquals("typing replaces the selected key", 1, replaced.size)
        assertTrue("typing replaces the selected key", replaced.single().equals("t", ignoreCase = true))
    }

    @Test
    fun `a pasted SteamGridDB key is drawn as dots and saved unchanged`() {
        var saved: String? = null
        showSteamGridDbEditor(currentKey = "") { saved = it }
        assertEquals("the key field is masked before anything is typed", 1, passwordFields().size)

        pasteThroughController(PastedKey)
        assertNeverShown(PastedKey)
        assertDrawnAsDots(PastedKey.length)

        press("action/done")
        assertEquals("the host receives the raw key, not the dots", PastedKey, saved)
    }

    @Test
    fun `a key pasted over a saved one replaces it, since the saved key is only dots`() {
        var saved: String? = null
        showSteamGridDbEditor(currentKey = SavedKey) { saved = it }
        pasteThroughController(PastedKey)
        assertNeverShown(PastedKey)
        assertDrawnAsDots(PastedKey.length)
        press("action/done")
        assertEquals("the pasted key alone, not appended to the saved one", PastedKey, saved)
    }

    @Test
    fun `a typed SteamGridDB key is drawn as dots`() {
        var saved: String? = null
        showSteamGridDbEditor(currentKey = "") { saved = it }

        listOf("t", "e", "s", "t").forEach { press("char/$it") }
        assertDrawnAsDots(4)

        press("action/done")
        assertTrue("the typed characters reach the host", saved.orEmpty().equals("test", ignoreCase = true))
    }

    @Test
    fun `a pasted RetroAchievements key is drawn as dots and validated unchanged`() {
        var submitted: Pair<String, String>? = null
        showRetroAchievementsEditor(RetroAchievementsConnectUi.EnterKey(Username)) { user, key ->
            submitted = user to key
        }
        assertEquals("the key field is masked before anything is typed", 1, passwordFields().size)

        pasteThroughController(PastedKey)
        assertNeverShown(PastedKey)
        assertDrawnAsDots(PastedKey.length)

        press("action/done")
        assertEquals("the host receives the raw key, not the dots", Username to PastedKey, submitted)
    }

    @Test
    fun `the RetroAchievements username step stays readable`() {
        showRetroAchievementsEditor(RetroAchievementsConnectUi.EnterUsername(Username)) { _, _ -> }

        assertEquals("the username is not a password", 0, passwordFields().size)
        assertTrue("the username is shown as typed", everythingShown().any { it.contains(Username) })
    }

    private fun showSteamGridDbEditor(currentKey: String, onSave: (String) -> Unit) {
        compose.setContent {
            MimirTheme {
                val keyboardHost = rememberMimirKeyboardHost(
                    authority = authority,
                    active = true,
                    owner = MimirOverlayKind.STEAMGRIDDB_KEY,
                )
                val clip = LocalClipboardManager.current
                SideEffect {
                    host = keyboardHost
                    clipboard = clip
                }
                SteamGridDbKeyEditOverlay(
                    currentKey = currentKey,
                    onSave = onSave,
                    onCancel = {},
                    controller = keyboardHost?.controller,
                    keyDecorator = keyboardHost?.keyDecorator,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun showRetroAchievementsEditor(
        state: RetroAchievementsConnectUi,
        onValidate: (String, String) -> Unit,
    ) {
        compose.setContent {
            MimirTheme {
                val keyboardHost = rememberMimirKeyboardHost(
                    authority = authority,
                    active = true,
                    owner = MimirOverlayKind.RETROACHIEVEMENTS_KEY,
                )
                val clip = LocalClipboardManager.current
                SideEffect {
                    host = keyboardHost
                    clipboard = clip
                }
                RetroAchievementsKeyEditOverlay(
                    state = state,
                    onUsernameEntered = {},
                    onValidate = onValidate,
                    onRetry = {},
                    onClose = {},
                    controller = keyboardHost?.controller,
                    keyDecorator = keyboardHost?.keyDecorator,
                )
            }
        }
        compose.waitForIdle()
    }

    /** The controller's A on one key, through the same dispatch the app host uses. */
    private fun press(keyId: String) {
        val key = mimirKeyboardFocusKey(keyId)
        authority.requestFocus(key)
        assertEquals("the controller reaches $keyId", key, authority.state.value.focused)
        val handled = dispatchMimirKeyboardInput(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            action = ControllerAction.CONFIRM,
            binding = requireNotNull(host).binding,
            onDismiss = {},
        )
        assertTrue("CONFIRM on $keyId is handled", handled)
        compose.waitForIdle()
    }

    /** The controller's B, through the same dispatch the app host uses: it focuses and presses Done. */
    private fun cancel() {
        val handled = dispatchMimirKeyboardInput(
            authority = authority,
            scope = MimirKeyboardFocusScope,
            action = ControllerAction.CANCEL,
            binding = requireNotNull(host).binding,
            onDismiss = {},
        )
        assertTrue("CANCEL is handled", handled)
        compose.waitForIdle()
    }

    private fun pasteThroughController(value: String) {
        compose.runOnIdle { requireNotNull(clipboard).setText(AnnotatedString(value)) }
        press("action/paste")
    }

    private fun allNodes(): List<SemanticsNode> =
        compose.onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true).fetchSemanticsNodes()

    private fun passwordFields(): List<SemanticsNode> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password), useUnmergedTree = true)
            .fetchSemanticsNodes()

    /** Drawn text, field text and spoken descriptions. MimirIntentText replaces its text with a description. */
    private fun everythingShown(): List<String> = allNodes().flatMap { node ->
        val config = node.config
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            listOfNotNull(config.getOrNull(SemanticsProperties.EditableText)?.text) +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            listOfNotNull(config.getOrNull(SemanticsProperties.StateDescription))
    }

    private fun assertNeverShown(key: String) {
        val leaks = everythingShown().count { it.contains(key) }
        assertEquals("text, field or spoken description that contains the key", 0, leaks)
    }

    /** One masked field of dots, and the preview line above it draws the same dots. */
    private fun assertDrawnAsDots(length: Int) {
        val fields = passwordFields()
        assertEquals("exactly one masked field", 1, fields.size)
        val drawn = fields.single().config[SemanticsProperties.EditableText].text
        assertEquals("one dot per character in the field", Dot.repeat(length), drawn)
        val spoken = allNodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        assertTrue("the preview line draws the same dots", spoken.contains(Dot.repeat(length)))
    }

    private companion object {
        /** PasswordVisualTransformation's mask character, which ArtworkConnectOverlay's preview line also uses. */
        const val Dot = "•"
        const val SavedKey = "test-key-0000"
        const val PastedKey = "test-key-0000-pasted"
        const val Username = "player1"
    }
}
