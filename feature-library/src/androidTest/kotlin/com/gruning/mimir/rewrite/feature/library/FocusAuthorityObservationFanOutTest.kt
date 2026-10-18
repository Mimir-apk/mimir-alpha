package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PS-02B Phase 1 differential probe: global FocusAuthority emissions must not recompose
 * untouched [mimirFocusable] bystanders. Expected to FAIL on pre-patch collectAsState fan-out
 * because untouched C recomposes when focus moves A→B / B→C.
 */
@RunWith(AndroidJUnit4::class)
class FocusAuthorityObservationFanOutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun focusMoveRecomposesOnlyOutgoingAndIncomingTargets() {
        val authority = DefaultFocusAuthority()
        val keyA = FocusKey("probe/a")
        val keyB = FocusKey("probe/b")
        val keyC = FocusKey("probe/c")
        val compositionsA = AtomicInteger(0)
        val compositionsB = AtomicInteger(0)
        val compositionsC = AtomicInteger(0)
        val events = mutableListOf<Pair<String, Boolean>>()

        // Independently restartable mount gates for A/B/C.
        var showA by mutableStateOf(true)
        var showB by mutableStateOf(true)
        var showC by mutableStateOf(true)

        compose.setContent {
            Row {
                if (showA) {
                    FocusProbe(
                        authority = authority,
                        key = keyA,
                        tag = TagA,
                        compositions = compositionsA,
                        events = events,
                    )
                }
                if (showB) {
                    FocusProbe(
                        authority = authority,
                        key = keyB,
                        tag = TagB,
                        compositions = compositionsB,
                        events = events,
                    )
                }
                if (showC) {
                    FocusProbe(
                        authority = authority,
                        key = keyC,
                        tag = TagC,
                        compositions = compositionsC,
                        events = events,
                    )
                }
            }
        }

        compose.waitForIdle()
        compose.onNodeWithTag(TagA).assertExists()
        compose.onNodeWithTag(TagB).assertExists()
        compose.onNodeWithTag(TagC).assertExists()
        compose.runOnIdle {
            assertEquals(keyA, authority.state.value.focused)
        }

        // Initial layout / auto-seed settled — differential window starts clean.
        compose.runOnIdle {
            compositionsA.set(0)
            compositionsB.set(0)
            compositionsC.set(0)
            events.clear()
        }
        compose.waitForIdle()

        compose.runOnIdle { authority.requestFocus(keyB) }
        compose.waitForIdle()

        assertEquals(
            "A→B must leave untouched C at zero compositions " +
                "(A=${compositionsA.get()}, B=${compositionsB.get()}, C=${compositionsC.get()})",
            0,
            compositionsC.get(),
        )
        assertTrue(
            "outgoing A must recompose on A→B (A=${compositionsA.get()})",
            compositionsA.get() > 0,
        )
        assertTrue(
            "incoming B must recompose on A→B (B=${compositionsB.get()})",
            compositionsB.get() > 0,
        )
        assertFocusCallbacks(
            events = events.toList(),
            expectedOrdered = listOf(keyA.value to false, keyB.value to true),
            moveLabel = "A→B",
        )

        compose.runOnIdle {
            compositionsA.set(0)
            compositionsB.set(0)
            compositionsC.set(0)
            events.clear()
        }
        compose.waitForIdle()

        compose.runOnIdle { authority.requestFocus(keyC) }
        compose.waitForIdle()

        assertEquals(
            "B→C must leave untouched A at zero compositions " +
                "(A=${compositionsA.get()}, B=${compositionsB.get()}, C=${compositionsC.get()})",
            0,
            compositionsA.get(),
        )
        assertTrue(
            "outgoing B must recompose on B→C (B=${compositionsB.get()})",
            compositionsB.get() > 0,
        )
        assertTrue(
            "incoming C must recompose on B→C (C=${compositionsC.get()})",
            compositionsC.get() > 0,
        )
        assertFocusCallbacks(
            events = events.toList(),
            expectedOrdered = listOf(keyB.value to false, keyC.value to true),
            moveLabel = "B→C",
        )

        // Prove each FocusProbe remains independently restartable.
        compose.runOnIdle { showA = false }
        compose.waitForIdle()
        compose.onNodeWithTag(TagA).assertDoesNotExist()
        compose.runOnIdle { showA = true }
        compose.waitForIdle()
        compose.onNodeWithTag(TagA).assertExists()

        compose.runOnIdle { showB = false }
        compose.waitForIdle()
        compose.onNodeWithTag(TagB).assertDoesNotExist()
        compose.runOnIdle { showB = true }
        compose.waitForIdle()
        compose.onNodeWithTag(TagB).assertExists()

        compose.runOnIdle { showC = false }
        compose.waitForIdle()
        compose.onNodeWithTag(TagC).assertDoesNotExist()
        compose.runOnIdle { showC = true }
        compose.waitForIdle()
        compose.onNodeWithTag(TagC).assertExists()
    }

    @Test
    fun mountingAlreadyFocusedKeyYieldsTrueWithoutFalseToTrueFlash() {
        val authority = DefaultFocusAuthority()
        val key = FocusKey("probe/prefocused")
        // Pre-register + focus before the composable mounts so first observation can be true.
        authority.register(
            key = key,
            scope = FocusScope.Root,
            bounds = FocusBounds(left = 0f, top = 0f, right = 40f, bottom = 40f),
        )
        authority.requestFocus(key)
        assertEquals(key, authority.state.value.focused)

        val compositions = AtomicInteger(0)
        val events = mutableListOf<Pair<String, Boolean>>()

        compose.setContent {
            FocusProbe(
                authority = authority,
                key = key,
                tag = TagPrefocused,
                compositions = compositions,
                events = events,
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag(TagPrefocused).assertExists()

        compose.runOnIdle {
            assertEquals(key, authority.state.value.focused)
            assertTrue("expected at least one onFocusChanged event, got $events", events.isNotEmpty())
            assertFalse(
                "already-focused mount must not flash false→true; events=$events",
                events.zipWithNext().any { (prev, next) ->
                    prev == (key.value to false) && next == (key.value to true)
                },
            )
            assertEquals(
                "first callback for an already-focused key must be true; events=$events",
                key.value to true,
                events.first(),
            )
            assertTrue(
                "every recorded callback must be true for the prefocused key; events=$events",
                events.all { it == (key.value to true) },
            )
        }
    }

    private fun assertFocusCallbacks(
        events: List<Pair<String, Boolean>>,
        expectedOrdered: List<Pair<String, Boolean>>,
        moveLabel: String,
    ) {
        if (events == expectedOrdered) return
        assertEquals(
            "$moveLabel callbacks must match exact set when order is non-deterministic; events=$events",
            expectedOrdered.toSet(),
            events.toSet(),
        )
        assertEquals(
            "$moveLabel must not emit extra duplicate callbacks; events=$events",
            expectedOrdered.size,
            events.size,
        )
    }

    companion object {
        private const val TagA = "focus-probe-a"
        private const val TagB = "focus-probe-b"
        private const val TagC = "focus-probe-c"
        private const val TagPrefocused = "focus-probe-prefocused"
    }
}

@Composable
private fun FocusProbe(
    authority: FocusAuthority,
    key: FocusKey,
    tag: String,
    compositions: AtomicInteger,
    events: MutableList<Pair<String, Boolean>>,
) {
    SideEffect {
        compositions.incrementAndGet()
    }
    Box(
        Modifier
            .size(48.dp)
            .testTag(tag)
            .mimirFocusable(
                authority = authority,
                key = key,
                autoBringIntoView = false,
                onFocusChanged = { focused ->
                    synchronized(events) {
                        events.add(key.value to focused)
                    }
                },
            ),
    )
}
