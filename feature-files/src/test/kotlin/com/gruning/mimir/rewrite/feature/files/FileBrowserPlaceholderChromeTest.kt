package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 *
 *
 * The header's brand mark and its three Search/Select/More icons are gated, not deleted. This pins
 * the gate SHUT so it cannot drift open — a placeholder that quietly becomes the default is exactly
 * the "fixed legend would lie" failure the rest of this module refuses.
 *
 * If a future change flips [FileBrowserPlaceholderChrome.ENABLED] to `true`, this test fails and its
 * message says why. That is deliberate: turning the chrome on is a product-design change, and taking it
 * should be a conscious edit to this test, not a silent one somewhere else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class FileBrowserPlaceholderChromeTest {
    private val english = filesEnglish()

    @Test
    fun `the header placeholder chrome ships OFF until explicitly enabled`() {
        assertFalse(
            "FileBrowserPlaceholderChrome.ENABLED must stay false pending a design decision " +
                "(2026-08-14: \"gate the placeholder untill i see it fit to enable\"). Enabling it " +
                "draws a brand-mark tile and three icons that do NOTHING -- on a touch surface that " +
                "is a promise the app cannot keep. If a real Search/Select/More has landed, delete " +
                "the gate rather than defaulting it true.",
            FileBrowserPlaceholderChrome.ENABLED,
        )
    }

    @Test
    fun `search has LEFT the gate - only Select and More are still placeholders`() {
        assertEquals(
            "F-08 shipped, so Search's share of this gate was DELETED (the object's own KDoc: \"When a " +
                "real Search/Select/More lands, delete this gate rather than defaulting it true\"). " +
                "Two remain -- Select (F-01's selection mode) and More (the Storage/Operations/" +
                "Permissions sheet) -- because neither exists in this tree yet. Each deletes its own " +
                "share the same way, and at 0 the whole object goes.",
            2,
            FileBrowserPlaceholderChrome.HEADER_ACTION_COUNT,
        )
    }

    /**
     * The pin that would actually catch a regression: **search must be visible without the gate.**
     *
     * The failure this guards against is subtle and would look fine in review — re-gating the search
     * icon behind [FileBrowserPlaceholderChrome.ENABLED] "for consistency" with its two neighbours. That
     * ships an app where the search feature is invisible and the flag that hides it is
     * `false` by the test above, i.e. two green tests and a missing feature.
     *
     * Asserted through the footer legend because that is the same `searchAvailable` signal the header
     * icon is drawn from, and it is the half a JVM test can reach — see this file's note on what a
     * module with no Compose harness can and cannot prove.
     */
    @Test
    fun `search is advertised on the strength of its provider, never of the placeholder gate`() {
        assertFalse(
            "precondition: the gate is shut, so anything still behind it is invisible",
            FileBrowserPlaceholderChrome.ENABLED,
        )
        assertTrue(
            "with a search provider wired, the footer must advertise Y SEARCH even though the " +
                "placeholder gate is OFF. If this fails, search has been put back behind the gate and " +
                "is unreachable in the shipped build.",
            fileBrowserFooterHints(english, focused = null, atRoot = true, searchAvailable = true)
                .contains("Y" to "Search"),
        )
        assertFalse(
            "with NO provider wired, the hint must be absent -- an advertised button that opens a " +
                "search screen with no engine behind it would answer every query with \"nothing here\"",
            fileBrowserFooterHints(english, focused = null, atRoot = true, searchAvailable = false)
                .contains("Y" to "Search"),
        )
    }
}

/**
 * Contracts A and C, wired 2026-08-14 after the three specialist lanes landed. Both were reported as
 * having player-visible consequences, so both are pinned rather than trusted.
 *
 * The commit affordance is not a tile -- it is a VIRTUAL focus key registered alongside the tiles
 * (policy 6), so its presence is asserted through where focus actually lands, not by hunting a
 * row in the partition.
 */
class FileBrowserContractsTest {

    private fun dir(path: String, name: String) = FileItem(
        name = name, path = path, isDirectory = true,
    )

    private fun controller(
        listing: Map<String, List<FileItem>>,
        root: String,
        commitAllowed: (String) -> Boolean = { true },
        authority: DefaultFocusAuthority = DefaultFocusAuthority(),
    ) = FileBrowserController(
        authority = authority,
        rootPath = root,
        listDirectory = { listing[it].orEmpty() },
        onChoose = {},
        onDismiss = {},
        commitAllowed = commitAllowed,
    )

    @Test
    fun `contract C - the drive chooser does not offer a commit affordance`() {
        val authority = DefaultFocusAuthority()
        val c = controller(
            listing = mapOf("/storage" to listOf(dir("/storage/emulated/0", "Internal Storage"))),
            root = "/storage",
            commitAllowed = { it != "/storage" },
            authority = authority,
        )
        c.open()
        assertNotEquals(
            "focus must not land on \"Use this folder\" at the drive chooser: committing /storage as " +
                "a ROM source is meaningless, and close() runs BEFORE onChoose, so refusing afterwards " +
                "ejects the player and only then says no",
            FileBrowserFocus.commitKey("/storage"),
            authority.state.value.focused,
        )
        c.close()
    }

    @Test
    fun `contract C - the affordance is still there inside a real drive`() {
        val authority = DefaultFocusAuthority()
        val c = controller(
            listing = mapOf("/storage/emulated/0" to emptyList()),
            root = "/storage/emulated/0",
            commitAllowed = { it != "/storage" },
            authority = authority,
        )
        c.open()
        assertEquals(
            "inside a real drive the commit affordance takes index 0 and initial focus",
            FileBrowserFocus.commitKey("/storage/emulated/0"),
            authority.state.value.focused,
        )
        c.close()
    }

    @Test
    fun `contract A - refresh surfaces files that extraction just wrote`() {
        val before = listOf(dir("/d/a", "a"), dir("/d/b", "b"))
        val listing = mutableMapOf("/d" to before)
        val c = FileBrowserController(
            authority = DefaultFocusAuthority(),
            rootPath = "/d",
            mode = FileBrowserMode.MANAGE,
            listDirectory = { listing[it].orEmpty() },
            onChoose = {},
            onDismiss = {},
        )
        c.open()
        val held = c.partition.totalCount
        listing["/d"] = before + dir("/d/c", "c")
        c.refresh()
        assertEquals(
            "without refresh, extracted files stay invisible until the player backs out and returns " +
                "-- which reads as \"extract did nothing\"",
            held + 1,
            c.partition.totalCount,
        )
        c.close()
    }
}
