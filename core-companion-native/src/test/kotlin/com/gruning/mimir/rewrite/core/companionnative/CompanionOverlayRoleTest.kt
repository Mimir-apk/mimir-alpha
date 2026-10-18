package com.gruning.mimir.rewrite.core.companionnative

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionOverlayRoleTest {

    @Test
    fun `both roles wear one material, and only one of them asks the compositor`() {
        //  The drawn book -- cover, page, spine rail, rune -- is retired,
        // and the glass the primary already wore is now simply the menu's material.
        CompanionOverlayRole.entries.forEach { role ->
            assertTrue("$role must wear the glass", companionMenuUsesLiquidGlass(role))
        }
        // The primary's menu is glass: translucent, denser at its top than its foot. The Bottom's card is opaque (a shelf label showed through its header), top and foot.
        assertTrue(companionMenuGlassTopAlpha(CompanionOverlayRole.PRIMARY_SINGLE) < 255)
        assertTrue(companionMenuGlassBottomAlpha(CompanionOverlayRole.PRIMARY_SINGLE) < companionMenuGlassTopAlpha(CompanionOverlayRole.PRIMARY_SINGLE))
        assertEquals(255, companionMenuGlassTopAlpha(CompanionOverlayRole.SECONDARY_COMPANION))
        assertEquals(255, companionMenuGlassBottomAlpha(CompanionOverlayRole.SECONDARY_COMPANION))

        // **THE SPLIT IS THE POINT, AND IT IS WHY THIS TEST EXISTS.** Material and cross-window blur
        // were ONE predicate until today, so giving the companion the glass would have silently given
        // it `FLAG_BLUR_BEHIND` too -- an effect never observed to execute on this dual-screen hardware,
        // shipped on top of the in-app blur that IS measured. Collapsing them back is the regression.
        assertTrue(companionMenuRequestsCrossWindowBlur(CompanionOverlayRole.PRIMARY_SINGLE))
        assertTrue(companionMenuBlurRadiusDp(CompanionOverlayRole.PRIMARY_SINGLE) > 0)
        assertFalse(companionMenuRequestsCrossWindowBlur(CompanionOverlayRole.SECONDARY_COMPANION))
        assertEquals(0, companionMenuBlurRadiusDp(CompanionOverlayRole.SECONDARY_COMPANION))
    }

    @Test
    fun `every command wears its own rune, and the reused ones are the rail's`() {
        //  The captions came off
        // the face and each command took a mark instead. A mark only works as identity if it is UNIQUE
        // within the menu -- two commands under one rune is worse than no rune, because the player
        // learns the wrong thing and it takes a wrong press to unlearn.
        CompanionOverlayRole.entries.forEach { role ->
            val runes = companionOverlayMenuRows(role).map { it.rune }
            assertTrue("$role has a command with no mark", runes.none { it.isBlank() })
            assertEquals("$role repeats a rune: $runes", runes.size, runes.distinct().size)
            assertFalse("ᛗ is the header's brand mark, never a command's", "ᛗ" in runes)
            // Codepoint-uniqueness is NOT enough and this pin cannot fix that. Ehwaz (ᛖ) passed every
            // assertion here and still shipped a SWAP coin that draws as a capital M under the ᛗ in the
            // header. Whether two marks collide is a question about the typeface at 24 sp, and it is
            // answered by photographing the menu — not by comparing strings.
        }

        // Pinned by NAME so a change is deliberate. Tiwaz and Sowilo are not chosen here -- they are
        // copied off MimirQuickRailAction, which already spends them on exactly these two commands.
        // core-ui is not on this module's classpath, so the values are restated rather than referenced;
        // if that rail ever re-letters them, this pin is the thing that has to be read and updated.
        val secondary = companionOverlayMenuRows(
            CompanionOverlayRole.SECONDARY_COMPANION,
            secondaryReturnAvailable = true,
        )
            .associate { it.titleRes to it.rune }
        assertEquals("ᛏ", secondary[R.string.companion_menu_quick_launch])   // Tiwaz — the rail's own quick-launch rune
        assertEquals("ᛋ", secondary[R.string.companion_menu_utility])        // Sowilo — the rail's own Utility rune
        assertEquals("ᛒ", secondary[R.string.companion_menu_tabs])           // Berkanan — the upright that holds two panes
        assertEquals("ᛞ", secondary[R.string.companion_menu_swap])           // Dagaz — the turning point; an exchange
        assertEquals("ᚱ", secondary[R.string.companion_menu_trackpad])       // Raido — a ride, a journey: the pointer's travel
        assertFalse("the Bottom M menu leaves Tweaker to the shelf", R.string.companion_menu_tweaker in secondary)

        // The single-display menu shares the cross-surface commands and must not drift from them.
        val primary = companionOverlayMenuRows(CompanionOverlayRole.PRIMARY_SINGLE)
            .associate { it.titleRes to it.rune }
        listOf(
            "Tabs" to R.string.companion_menu_tabs,
            "Quick launch" to R.string.companion_menu_quick_launch,
            "Utility" to R.string.companion_menu_utility,
        ).forEach { (command, title) ->
            assertEquals(
                "$command must wear one mark on both roles",
                secondary[title],
                primary[title],
            )
        }
    }

    @Test
    fun `primary requires only M while secondary transaction still requires edge catcher`() {
        val primary = CompanionOverlayTarget(CompanionOverlayRole.PRIMARY_SINGLE, 0)
        val secondary = CompanionOverlayTarget(CompanionOverlayRole.SECONDARY_COMPANION, 7)

        assertTrue(OverlayWindowSnapshot(mButtonDisplayId = 0).hasRequiredWindowsOn(primary))
        assertFalse(OverlayWindowSnapshot(mButtonDisplayId = 7).hasRequiredWindowsOn(secondary))
        assertTrue(
            OverlayWindowSnapshot(mButtonDisplayId = 7, edgeDisplayId = 7)
                .hasRequiredWindowsOn(secondary),
        )
    }

    @Test
    fun `edge catcher admits foreign host or visible own drawer, but idle mirror stays touch-through`() {
        assertFalse(companionEdgeCatcherIsTouchable(dimmed = true, ownMirrorDrawerEdgeBackEnabled = false))
        assertTrue(companionEdgeCatcherIsTouchable(dimmed = true, ownMirrorDrawerEdgeBackEnabled = true))
        assertTrue(companionEdgeCatcherIsTouchable(dimmed = false, ownMirrorDrawerEdgeBackEnabled = false))
    }

    @Test
    fun `edge catcher admits the idle mirror while its own folder is open`() {
        // Bottom-shelf Back could mirror onto Main. Before this fix
        // the idle own-mirror shelf stayed touch-through with a Tome/folder open — the drawer was the
        // only scoped exception — so a right-edge swipe could never close it. Defect at the base commit;
        // fixed once `companionEdgeCatcherIsTouchable` also reads an open folder as a closable layer.
        assertTrue(
            companionEdgeCatcherIsTouchable(
                dimmed = true,
                ownMirrorDrawerEdgeBackEnabled = false,
                ownMirrorFolderEdgeBackEnabled = true,
            ),
        )
    }

    @Test
    fun `edge catcher is touch-through while a dual-screen game owns the Bottom`() {
        // The DS handoff lease is the one state where Mimir
        // knows the emulator's lower screen is up; over it the strip stays untouchable even though no mirror is
        // active, which would otherwise admit it as a foreign host.
        assertFalse(
            companionEdgeCatcherIsTouchable(
                dimmed = false,
                ownMirrorDrawerEdgeBackEnabled = false,
                dualScreenGameOwnsBottom = true,
            ),
        )
    }

    @Test
    fun `role positions cannot alias when vendor display ids reconnect`() {
        assertEquals("button_primary_single_x", companionOverlayPositionKey("button", CompanionOverlayRole.PRIMARY_SINGLE, "x"))
        assertEquals(
            "button_secondary_companion_x",
            companionOverlayPositionKey("button", CompanionOverlayRole.SECONDARY_COMPANION, "x"),
        )
    }

    @Test
    fun `single-display menu has exact order and no dual-display jargon`() {
        val rows = companionOverlayMenuRows(CompanionOverlayRole.PRIMARY_SINGLE)
        // The words are string resources: this pins which ones, in what order. CompanionOverlayMenuTextTest pins what they say, in both languages,
        // and that the single-display menu's text carries no dual-display jargon.
        assertEquals(
            listOf(
                R.string.companion_menu_tabs,
                R.string.companion_menu_quick_launch,
                R.string.companion_menu_utility,
                R.string.companion_menu_tweaker,
            ),
            rows.map { it.titleRes },
        )
        assertEquals(R.string.companion_menu_tweaker_subtitle, rows.last().subtitleRes)
        assertEquals(R.string.companion_menu_tabs_system_subtitle, rows.first().subtitleRes)
        assertEquals(
            listOf(
                QuickMenuItem.SYSTEM_TABS,
                QuickMenuItem.QUICK_LAUNCH,
                QuickMenuItem.UTILITY,
                QuickMenuItem.TWEAKER,
            ),
            rows.map { it.item },
        )
        val secondaryOnly = setOf(
            R.string.companion_menu_tabs_secondary_subtitle,
            R.string.companion_menu_quick_launch_secondary_subtitle,
            R.string.companion_menu_utility_secondary_subtitle,
        )
        assertTrue("no Bottom-only wording on the single-display menu", rows.none { it.subtitleRes in secondaryOnly })
    }

    @Test
    fun `a Tabs long-press maps to Android Recents on either menu role`() {
        assertEquals(
            QuickMenuItem.SYSTEM_TABS,
            companionOverlayMenuLongPressItem(QuickMenuItem.SYSTEM_TABS),
        )
        assertEquals(
            QuickMenuItem.SYSTEM_TABS,
            companionOverlayMenuLongPressItem(QuickMenuItem.SECONDARY_TABS),
        )
        assertEquals(null, companionOverlayMenuLongPressItem(QuickMenuItem.SECONDARY_SWAP))
    }

    @Test
    fun `secondary menu keeps explicit topology in the same runic header lane`() {
        // The header's words (ᛗ · Bottom screen) are a string resource: CompanionOverlayMenuTextTest pins them in both languages.
        assertEquals(
            listOf(
                R.string.companion_menu_tabs_secondary_subtitle,
                R.string.companion_menu_return_subtitle,
                R.string.companion_menu_quick_launch_secondary_subtitle,
                // on the two-screen role a row names its display, and
                // Utility reaches BOTH. PRIMARY_SINGLE keeps the plain subtitle -- pinned above.
                R.string.companion_menu_utility_secondary_subtitle,
                R.string.companion_menu_swap_subtitle,
                // the full-screen trackpad is also a Bottom menu row.
                R.string.companion_menu_trackpad_subtitle,
            ),
            companionOverlayMenuRows(
                CompanionOverlayRole.SECONDARY_COMPANION,
                secondaryReturnAvailable = true,
            ).map { it.subtitleRes },
        )
        // The same rule for Quick launch: the two-screen menu names where it opens, and the single-display menu,
        // with one screen, keeps its plain subtitle.
        assertEquals(
            R.string.companion_menu_quick_launch_subtitle,
            companionOverlayMenuRows(CompanionOverlayRole.PRIMARY_SINGLE)
                .single { it.item == QuickMenuItem.QUICK_LAUNCH }
                .subtitleRes,
        )
        assertEquals(
            listOf(
                QuickMenuItem.SECONDARY_TABS,
                QuickMenuItem.SECONDARY_RETURN,
                QuickMenuItem.QUICK_LAUNCH,
                QuickMenuItem.UTILITY,
                QuickMenuItem.SECONDARY_SWAP,
                QuickMenuItem.TRACKPAD,
            ),
            companionOverlayMenuRows(
                CompanionOverlayRole.SECONDARY_COMPANION,
                secondaryReturnAvailable = true,
            ).map { it.item },
        )
    }

    @Test
    fun `an open primary menu owns window focus, and the secondary menu does exactly while a pad owner is set`() {
        assertFalse(primaryQuickMenuOwnsWindowFocus(CompanionOverlayRole.PRIMARY_SINGLE, menuOpen = false))
        assertTrue(primaryQuickMenuOwnsWindowFocus(CompanionOverlayRole.PRIMARY_SINGLE, menuOpen = true))
        // PRIMARY is unmoved by the Bottom's owner: it never carries one.
        assertTrue(primaryQuickMenuOwnsWindowFocus(CompanionOverlayRole.PRIMARY_SINGLE, true, secondaryPadOwner = null))
        // SECONDARY (R9a): no owner, no focus, whether or not the menu is open. The default is the pre-R9a rule.
        assertFalse(primaryQuickMenuOwnsWindowFocus(CompanionOverlayRole.SECONDARY_COMPANION, menuOpen = false))
        assertFalse(primaryQuickMenuOwnsWindowFocus(CompanionOverlayRole.SECONDARY_COMPANION, menuOpen = true))
        assertFalse(primaryQuickMenuOwnsWindowFocus(CompanionOverlayRole.SECONDARY_COMPANION, true, secondaryPadOwner = null))
        // With an owner set, focus follows the owner alone: every owner, and menu openness does not enter into it.
        BottomPadOwner.entries.forEach { owner ->
            assertTrue(primaryQuickMenuOwnsWindowFocus(CompanionOverlayRole.SECONDARY_COMPANION, true, owner))
            assertTrue(primaryQuickMenuOwnsWindowFocus(CompanionOverlayRole.SECONDARY_COMPANION, false, owner))
        }
    }

    @Test
    fun `slice 1 sets only the menu owner and names the panel owners slice 2 adds`() {
        assertEquals(
            listOf("MENU", "UTILITY_TWEAKER", "QUICK_LAUNCH", "TABS"),
            BottomPadOwner.entries.map { it.name },
        )
    }

    @Test
    fun `idle surface shrinks to one pixel without replacing its input window`() {
        assertEquals(1, PrimaryIdleWatcherSizePx)
    }

    @Test
    fun `idle watcher reveals on outside observation and on its one pixel direct hit`() {
        assertTrue(primaryIdleWatcherShouldReveal(MotionEvent.ACTION_OUTSIDE))
        assertTrue(primaryIdleWatcherShouldReveal(MotionEvent.ACTION_DOWN))
        assertFalse(primaryIdleWatcherShouldReveal(MotionEvent.ACTION_MOVE))
        assertFalse(primaryIdleWatcherShouldReveal(MotionEvent.ACTION_UP))
    }

    @Test
    fun `primary menu consumes navigation and dismisses only after cancel release`() {
        listOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
        ).forEach { keyCode ->
            assertEquals(
                PrimaryQuickMenuKeyResult.CONSUME,
                primaryQuickMenuKeyResult(keyCode, KeyEvent.ACTION_DOWN),
            )
            assertEquals(
                PrimaryQuickMenuKeyResult.CONSUME,
                primaryQuickMenuKeyResult(keyCode, KeyEvent.ACTION_UP),
            )
        }

        listOf(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK).forEach { keyCode ->
            assertEquals(
                PrimaryQuickMenuKeyResult.CONSUME,
                primaryQuickMenuKeyResult(keyCode, KeyEvent.ACTION_DOWN),
            )
            assertEquals(
                PrimaryQuickMenuKeyResult.DISMISS_AFTER_RELEASE,
                primaryQuickMenuKeyResult(keyCode, KeyEvent.ACTION_UP),
            )
        }
    }

    @Test
    fun `primary menu routes volume to Android and leaves power to system policy`() {
        listOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
        ).forEach { keyCode ->
            assertEquals(
                PrimaryQuickMenuKeyResult.ROUTE_VOLUME_TO_SYSTEM,
                primaryQuickMenuKeyResult(keyCode, KeyEvent.ACTION_DOWN),
            )
            assertEquals(
                PrimaryQuickMenuKeyResult.ROUTE_VOLUME_TO_SYSTEM,
                primaryQuickMenuKeyResult(keyCode, KeyEvent.ACTION_UP),
            )
        }
        assertEquals(
            PrimaryQuickMenuKeyResult.PASS_TO_SYSTEM,
            primaryQuickMenuKeyResult(KeyEvent.KEYCODE_POWER, KeyEvent.ACTION_DOWN),
        )
        assertEquals(
            PrimaryQuickMenuKeyResult.PASS_TO_SYSTEM,
            primaryQuickMenuKeyResult(KeyEvent.KEYCODE_POWER, KeyEvent.ACTION_UP),
        )
    }

    @Test
    fun `secondary outside ownership latches from down through terminal pointer sequence`() {
        val capture = SecondaryMenuOutsideTouchCapture()

        assertEquals(
            SecondaryMenuTouchRoute.CONSUME,
            capture.dispatch(MotionEvent.ACTION_DOWN, downInsideCard = false),
        )
        assertTrue(capture.isActive)
        assertEquals(
            SecondaryMenuTouchRoute.CONSUME,
            capture.dispatch(MotionEvent.ACTION_POINTER_DOWN, downInsideCard = true),
        )
        assertEquals(
            SecondaryMenuTouchRoute.CONSUME,
            capture.dispatch(MotionEvent.ACTION_MOVE, downInsideCard = true),
        )
        assertEquals(
            SecondaryMenuTouchRoute.CONSUME,
            capture.dispatch(MotionEvent.ACTION_POINTER_UP, downInsideCard = true),
        )
        assertEquals(
            SecondaryMenuTouchRoute.TERMINATE,
            capture.dispatch(MotionEvent.ACTION_UP, downInsideCard = true),
        )
        assertFalse(capture.isActive)
    }

    @Test
    fun `secondary inside drag remains delegated after crossing card edge`() {
        val capture = SecondaryMenuOutsideTouchCapture()

        assertEquals(
            SecondaryMenuTouchRoute.DELEGATE,
            capture.dispatch(MotionEvent.ACTION_DOWN, downInsideCard = true),
        )
        assertEquals(
            SecondaryMenuTouchRoute.DELEGATE,
            capture.dispatch(MotionEvent.ACTION_MOVE, downInsideCard = false),
        )
        assertEquals(
            SecondaryMenuTouchRoute.DELEGATE,
            capture.dispatch(MotionEvent.ACTION_CANCEL, downInsideCard = false),
        )
        assertFalse(capture.isActive)
    }
}
