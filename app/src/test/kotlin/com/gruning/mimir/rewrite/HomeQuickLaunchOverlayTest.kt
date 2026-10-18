package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FUX-D §4.4 — the Quick Launch picker's controller reducer. This is the ONLY controller path to the
 * dock action, so its whole contract is pinned: Q1/Q2/Q3 + General wrap, pinned/General CONFIRM,
 * Y edit, and B/back dismissal.
 */
class HomeQuickLaunchOverlayTest {
    @Test
    fun menuUsesTheCappedRunicHeaderAndControllerLegend() {
        assertEquals("ᛗ · QUICK LAUNCH", HomeQuickLaunchHeader)
        assertEquals("A OPEN · Y EDIT · B BACK", HomeQuickLaunchLegend)
    }

    @Test
    fun generalPickerRestoresLastFocusedAppAndFallsBackWhenItDisappears() {
        val items = listOf(
            pickerApp("com.example.one", "One"),
            pickerApp("com.example.two", "Two"),
            pickerApp("com.example.three", "Three"),
        )

        assertEquals(
            1,
            homeQuickLaunchPickerInitialIndex(
                mode = HomeQuickLaunchPickerMode.General,
                items = items,
                lastGeneralPackageName = "com.example.two",
            ),
        )
        assertEquals(
            0,
            homeQuickLaunchPickerInitialIndex(
                mode = HomeQuickLaunchPickerMode.General,
                items = items,
                lastGeneralPackageName = "com.example.removed",
            ),
        )
    }

    @Test
    fun assignPickerNeverInheritsGeneralNavigationMemory() {
        val items = listOf(
            HomeQuickLaunchPickerItem.Clear,
            pickerApp("com.example.two", "Two"),
        )

        assertEquals(
            0,
            homeQuickLaunchPickerInitialIndex(
                mode = HomeQuickLaunchPickerMode.Assign(slotIndex = 1),
                items = items,
                lastGeneralPackageName = "com.example.two",
            ),
        )
    }

    @Test
    fun verticalFocusWrapsBothWaysAcrossSlotsAndGeneral() {
        val authority = registeredAuthority()
        authority.requestFocus(homeQuickLaunchSlotKey(0))

        assertTrue(dispatch(authority, ControllerAction.NAV_UP))
        assertEquals(homeQuickLaunchSlotKey(QuickLaunchStore.SLOT_COUNT), authority.state.value.focused)
        assertTrue(dispatch(authority, ControllerAction.NAV_DOWN))
        assertEquals(homeQuickLaunchSlotKey(0), authority.state.value.focused)
        assertTrue(dispatch(authority, ControllerAction.NAV_DOWN))
        assertEquals(homeQuickLaunchSlotKey(1), authority.state.value.focused)
    }

    @Test
    fun verticalFocusVisitsAllFivePinsAndGeneralBeforeWrapping() {
        val authority = registeredAuthority()
        authority.requestFocus(homeQuickLaunchSlotKey(0))

        (1..QuickLaunchStore.SLOT_COUNT).forEach { index ->
            assertTrue(dispatch(authority, ControllerAction.NAV_DOWN))
            assertEquals(homeQuickLaunchSlotKey(index), authority.state.value.focused)
        }
        assertTrue(dispatch(authority, ControllerAction.NAV_DOWN))
        assertEquals(homeQuickLaunchSlotKey(0), authority.state.value.focused)
    }

    @Test
    fun degenerateFocusLandsDeterministicallyOnSlotZeroInEitherDirection() {
        // Focus sits on a key that is NOT one of our slots (registration seeding, a stale key): the
        // first press must land on slot 0, never one step past a slot the user could not see focused.
        listOf(ControllerAction.NAV_DOWN, ControllerAction.NAV_UP).forEach { action ->
            val authority = registeredAuthority()
            val foreign = com.gruning.mimir.rewrite.core.input.FocusKey("home/quicklaunch/foreign")
            authority.registerVirtual(foreign, HomeQuickLaunchScope, FocusBounds(0f, 900f, 50f, 950f))
            authority.requestFocus(foreign)
            assertTrue(dispatch(authority, action))
            assertEquals(homeQuickLaunchSlotKey(0), authority.state.value.focused)
        }
    }

    @Test
    fun confirmLaunchesExactlyThePinnedSlotAndNeverDismisses() {
        val authority = registeredAuthority()
        authority.requestFocus(homeQuickLaunchSlotKey(1))
        val launched = mutableListOf<String>()
        var dismissed = 0

        assertTrue(
            dispatchHomeQuickLaunch(
                authority = authority,
                slots = Slots,
                action = ControllerAction.CONFIRM,
                onLaunch = launched::add,
                onDismiss = { dismissed++ },
            ),
        )
        assertEquals(listOf("com.example.two"), launched)
        assertEquals(0, dismissed)
    }

    @Test
    fun confirmOnAnEmptySlotNeitherLaunchesNorDismisses() {
        val authority = registeredAuthority()
        authority.requestFocus(homeQuickLaunchSlotKey(2))
        val launched = mutableListOf<String>()
        var dismissed = 0

        assertTrue(
            dispatchHomeQuickLaunch(
                authority = authority,
                slots = Slots,
                action = ControllerAction.CONFIRM,
                onLaunch = launched::add,
                onDismiss = { dismissed++ },
            ),
        )
        assertTrue(launched.isEmpty())
        assertEquals(0, dismissed)
    }

    @Test
    fun confirmOnGeneralOpensFullPickerWithoutLaunchingAPackage() {
        val authority = registeredAuthority()
        authority.requestFocus(homeQuickLaunchSlotKey(QuickLaunchStore.SLOT_COUNT))
        val launched = mutableListOf<String>()
        var opened = 0

        assertTrue(
            dispatchHomeQuickLaunch(
                authority = authority,
                slots = Slots,
                action = ControllerAction.CONFIRM,
                onLaunch = launched::add,
                onOpenGeneral = { opened++ },
                onDismiss = {},
            ),
        )

        assertTrue(launched.isEmpty())
        assertEquals(1, opened)
    }

    @Test
    fun tertiaryEditsPinnedOrEmptySlotsButNeverGeneral() {
        val authority = registeredAuthority()
        val edited = mutableListOf<Int>()

        listOf(0, 2, QuickLaunchStore.SLOT_COUNT - 1, QuickLaunchStore.SLOT_COUNT).forEach { index ->
            authority.requestFocus(homeQuickLaunchSlotKey(index))
            assertTrue(
                dispatchHomeQuickLaunch(
                    authority = authority,
                    slots = Slots,
                    action = ControllerAction.TERTIARY,
                    onLaunch = {},
                    onEdit = edited::add,
                    onDismiss = {},
                ),
            )
        }

        assertEquals(listOf(0, 2, QuickLaunchStore.SLOT_COUNT - 1), edited)
    }

    @Test
    fun cancelAndSystemBackDismissExactlyOnce() {
        listOf(ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK).forEach { action ->
            val authority = registeredAuthority()
            var dismissed = 0
            assertTrue(
                dispatchHomeQuickLaunch(
                    authority = authority,
                    slots = Slots,
                    action = action,
                    onLaunch = { },
                    onDismiss = { dismissed++ },
                ),
            )
            assertEquals(1, dismissed)
        }
    }

    @Test
    fun hardModalConsumesEveryActionIncludingHorizontalNav() {
        val authority = registeredAuthority()
        authority.requestFocus(homeQuickLaunchSlotKey(0))
        listOf(
            ControllerAction.NAV_LEFT,
            ControllerAction.NAV_RIGHT,
            ControllerAction.SECONDARY,
        ).forEach { action ->
            assertTrue("$action must be consumed", dispatch(authority, action))
        }
        // Horizontal nav is inert, not a wrap: focus stays where it was.
        assertEquals(homeQuickLaunchSlotKey(0), authority.state.value.focused)
    }

    @Test
    fun emptySlotListConsumesEverythingAndOnlyBackDismisses() {
        val authority = DefaultFocusAuthority()
        var dismissed = 0
        assertTrue(
            dispatchHomeQuickLaunch(
                authority = authority,
                slots = emptyList(),
                action = ControllerAction.NAV_DOWN,
                onLaunch = { },
                onDismiss = { dismissed++ },
            ),
        )
        assertEquals(0, dismissed)
        assertTrue(
            dispatchHomeQuickLaunch(
                authority = authority,
                slots = emptyList(),
                action = ControllerAction.CANCEL,
                onLaunch = { },
                onDismiss = { dismissed++ },
            ),
        )
        assertEquals(1, dismissed)
    }

    @Test
    fun slotUiFallsBackFromCatalogLabelToRawPackage() {
        // **Widened with three pinned slots became four.** The General entry
        // is appended AFTER the pinned ones, so its index moves with the count -- written against
        // `QuickLaunchStore.SLOT_COUNT` rather than a literal, so the next change to the count does not
        // need this test edited again. A hardcoded 3 here is what made it fail today.
        val pinned = QuickLaunchStore.SLOT_COUNT
        val slots = homeQuickLaunchSlots(
            slots = listOf("com.known.app", "com.unknown.app") + List(pinned - 2) { null },
            labelFor = { pkg -> if (pkg == "com.known.app") "Known App" else null },
        )
        assertEquals("Known App", slots[0].label)
        assertEquals("com.unknown.app", slots[1].label)
        assertNull("every slot past the two pinned ones is empty", slots[pinned - 1].label)
        assertEquals("All apps", slots[pinned].label)
        assertEquals((0..pinned).toList(), slots.map { it.index })
        assertFalse("the last PINNED slot is not the general one", slots[pinned - 1].general)
        assertTrue("the appended entry is", slots[pinned].general)
    }

    private fun dispatch(authority: DefaultFocusAuthority, action: ControllerAction): Boolean =
        dispatchHomeQuickLaunch(
            authority = authority,
            slots = Slots,
            action = action,
            onLaunch = { },
            onDismiss = { },
        )

    private fun registeredAuthority() = DefaultFocusAuthority().apply {
        pushScope(HomeQuickLaunchScope)
        Slots.forEach { slot ->
            registerVirtual(
                key = homeQuickLaunchSlotKey(slot.index),
                scope = HomeQuickLaunchScope,
                bounds = FocusBounds(0f, slot.index * 100f, 400f, slot.index * 100f + 90f),
            )
        }
    }

    private fun pickerApp(packageName: String, label: String) = HomeQuickLaunchPickerItem.App(
        CompanionAppEntry(packageName = packageName, label = label),
    )

    private companion object {
        val Slots = buildList {
            repeat(QuickLaunchStore.SLOT_COUNT) { index ->
                add(
                    HomeQuickLaunchSlotUi(
                        index = index,
                        packageName = when (index) {
                            0 -> "com.example.one"
                            1 -> "com.example.two"
                            else -> null
                        },
                        label = when (index) {
                            0 -> "One"
                            1 -> "Two"
                            else -> null
                        },
                    ),
                )
            }
            add(
                HomeQuickLaunchSlotUi(
                    index = QuickLaunchStore.SLOT_COUNT,
                    packageName = null,
                    label = "General",
                    general = true,
                ),
            )
        }
    }
}
