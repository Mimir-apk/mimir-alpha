package com.gruning.mimir.rewrite.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * EM01 — the LC01 spec's seven-state derivation, pinned as a matrix. The load-bearing truths:
 * certification can only arrive as an explicit input (never inferred from installed/configured facts),
 * unsupported closes before everything, and installed-but-unwired is distinct from nothing-installed.
 */
class EmulatorRouteStateTest {

    private fun derive(
        hasCandidates: Boolean = true,
        hasResolvedTarget: Boolean = true,
        anyCandidateInstalled: Boolean = true,
        certified: Boolean = false,
        openOnly: Boolean = false,
        requiresCoreProof: Boolean = false,
    ) = deriveEmulatorRouteState(
        hasCandidates = hasCandidates,
        hasResolvedTarget = hasResolvedTarget,
        anyCandidateInstalled = anyCandidateInstalled,
        certified = certified,
        openOnly = openOnly,
        requiresCoreProof = requiresCoreProof,
    )

    @Test fun no_candidates_is_unsupported_regardless_of_other_facts() {
        assertEquals(
            EmulatorRouteState.UNSUPPORTED,
            derive(hasCandidates = false, certified = true, openOnly = true),
        )
    }

    @Test fun no_resolved_target_splits_on_any_installed_candidate() {
        assertEquals(
            EmulatorRouteState.NOT_WIRED,
            derive(hasResolvedTarget = false, anyCandidateInstalled = true),
        )
        assertEquals(
            EmulatorRouteState.INSTALL_NEEDED,
            derive(hasResolvedTarget = false, anyCandidateInstalled = false),
        )
    }

    @Test fun certified_only_from_the_explicit_projection_flag() {
        assertEquals(EmulatorRouteState.CERTIFIED, derive(certified = true))
        // Installed + dispatch-ready alone NEVER promotes to certified.
        assertEquals(EmulatorRouteState.DISPATCH_READY, derive())
    }

    @Test fun certified_route_wins_over_its_own_qualifiers() {
        // A VISUAL_CERTIFIED route has, by definition, already proven its core/shell posture.
        assertEquals(
            EmulatorRouteState.CERTIFIED,
            derive(certified = true, requiresCoreProof = true),
        )
    }

    @Test fun open_only_and_core_proof_tiers() {
        assertEquals(EmulatorRouteState.OPEN_ONLY, derive(openOnly = true))
        assertEquals(EmulatorRouteState.CORE_PROOF_NEEDED, derive(requiresCoreProof = true))
        // Shell-only outranks the core qualifier (a NONE-locator route sends no core payload at all).
        assertEquals(
            EmulatorRouteState.OPEN_ONLY,
            derive(openOnly = true, requiresCoreProof = true),
        )
    }

    @Test fun unsupported_copy_uses_exact_reason_and_never_invents_one() {
        val reason = "The official Cemu project does not publish an Android target."
        assertEquals(reason, EmulatorRouteState.UNSUPPORTED.primaryCopy(reason))
        // OR-7: a missing emulator is named when the seed table knows one.
        assertEquals(
            "Install DuckStation to play these games.",
            EmulatorRouteState.INSTALL_NEEDED.primaryCopy(null, recommendedEmulator = "DuckStation"),
        )
        assertEquals(
            "No supported emulator route for this platform yet.",
            EmulatorRouteState.UNSUPPORTED.primaryCopy(null),
        )
    }

    @Test fun open_only_copy_never_says_play_or_launch_game() {
        val copy = EmulatorRouteState.OPEN_ONLY.primaryCopy(null)
        // LC01 verb rule: shell-only rows use "open", never a direct game-launch verb.
        assert(copy.contains("open", ignoreCase = true))
        assert(!copy.contains("play", ignoreCase = true))
        assert(!copy.contains("launch game", ignoreCase = true))
    }

    @Test fun every_state_has_a_chip_label() {
        EmulatorRouteState.entries.forEach { state ->
            assert(state.chipLabel().isNotBlank())
        }
    }

    @Test fun release_copy_never_exposes_engineering_gate_jargon() {
        assertEquals("Ready", EmulatorRouteState.CERTIFIED.chipLabel())
        assertEquals("Set up", EmulatorRouteState.DISPATCH_READY.chipLabel())
        assertEquals("Needs setup", EmulatorRouteState.CORE_PROOF_NEEDED.chipLabel())
        assertEquals("Needs emulator", EmulatorRouteState.INSTALL_NEEDED.chipLabel())
        assertEquals("Not supported yet", EmulatorRouteState.NOT_WIRED.chipLabel())

        EmulatorRouteState.entries.forEach { state ->
            val copy = "${state.chipLabel()} ${state.primaryCopy(null)}".lowercase()
            assert(!copy.contains("visual certification"))
            assert(!copy.contains("verified launch contract"))
            assert(!copy.contains("route configured"))
            assert(!copy.contains("core proof"))
        }
    }
}
