package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.EmulatorId
import com.gruning.mimir.rewrite.core.model.EmulatorProfile
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformEmulatorStatusTest {

    private val ps1 = PlatformId.PS1
    private val duck = EmulatorId.fromNativeLauncherKey("com.github.stenzek.duckstation")
    private val retroarch = EmulatorId.fromNativeLauncherKey("com.retroarch")

    private fun emu(id: EmulatorId, installed: Boolean) = EmulatorProfile(
        id = id,
        packageName = id.value,
        displayName = id.value,
        retroArchCore = if (id == retroarch) "pcsx_rearmed" else null,
        installed = installed,
        capability = EmulatorCapability.DIRECT_PAYLOAD,
        supportedPlatforms = setOf(ps1),
    )

    @Test
    fun installedPlatformDefaultWinsOverAuto() {
        val provider = InstalledEmulatorProvider {
            listOf(emu(duck, installed = true), emu(retroarch, installed = true))
        }

        val status = provider.resolvePlatformEmulatorStatus(ps1, defaultEmulatorId = retroarch)

        assertEquals(retroarch, status.resolved?.id)
    }

    @Test
    fun stalePlatformDefaultFallsBackToInstalledAuto() {
        val provider = InstalledEmulatorProvider {
            listOf(emu(duck, installed = true), emu(retroarch, installed = false))
        }

        val status = provider.resolvePlatformEmulatorStatus(ps1, defaultEmulatorId = retroarch)

        assertEquals(duck, status.resolved?.id)
    }

    @Test
    fun noInstalledCandidateNeedsEmulator() {
        val provider = InstalledEmulatorProvider {
            listOf(emu(duck, installed = false), emu(retroarch, installed = false))
        }

        val status = provider.resolvePlatformEmulatorStatus(ps1)

        assertNull(status.resolved)
        assertTrue(status.needsEmulator)
    }

    @Test
    fun noCandidatesIsUnsupported() {
        val provider = InstalledEmulatorProvider { emptyList() }

        val status = provider.resolvePlatformEmulatorStatus(ps1)

        assertNull(status.resolved)
        assertTrue(status.unsupported)
    }

    // ── PS3 Auto: payload-capable must outrank an honest open-shell candidate ────────────────
    // Device evidence 2026-08-11: TARGET LAUNCH 'Shadows Of The Damned' emulatorOverride=auto resolved
    // to com.armsx3 (EmulatorCapability.OPEN_ONLY, LaunchPathStrategy.NO_CONTENT — can only open the
    // shell) instead of aenu.aps3e (DIRECT_PAYLOAD, carries iso_uri). Seed order alone (EmulatorSeedRegistry
    // declares aps3e before armsx3) did not save this: the old ladder's first tier accepted the first
    // INSTALLED dispatch-supported candidate with no payload-awareness, so if only armsx3 was installed
    // — or a caller reordered candidates — nothing prevented an OPEN_ONLY shell from outranking a
    // DIRECT_PAYLOAD emulator whenever it happened to sort first.

    private val ps3 = PlatformId("ps3")
    private val aps3e = EmulatorId.fromNativeLauncherKey("aenu.aps3e")
    private val armsx3 = EmulatorId.fromNativeLauncherKey("com.armsx3")

    private fun ps3Emu(
        id: EmulatorId,
        installed: Boolean,
        capability: EmulatorCapability,
        locatorForm: LaunchLocatorForm,
    ) = EmulatorProfile(
        id = id,
        packageName = id.value,
        displayName = id.value,
        installed = installed,
        capability = capability,
        locatorForm = locatorForm,
        supportedPlatforms = setOf(ps3),
    )

    @Test
    fun autoPrefersPayloadCapableOverOpenOnlyShell() {
        // Deliberately list the OPEN_ONLY shell FIRST so this cannot pass by seed-order accident alone —
        // the preference tier, not candidate order, must be what picks aps3e.
        val provider = InstalledEmulatorProvider {
            listOf(
                ps3Emu(armsx3, installed = true, capability = EmulatorCapability.OPEN_ONLY, locatorForm = LaunchLocatorForm.NONE),
                ps3Emu(aps3e, installed = true, capability = EmulatorCapability.DIRECT_PAYLOAD, locatorForm = LaunchLocatorForm.EITHER),
            )
        }

        val status = provider.resolvePlatformEmulatorStatus(ps3)

        assertEquals(aps3e, status.resolved?.id)
    }

    @Test
    fun autoStillReturnsOpenOnlyShellWhenItIsTheOnlyCandidateInstalled() {
        val provider = InstalledEmulatorProvider {
            listOf(
                ps3Emu(armsx3, installed = true, capability = EmulatorCapability.OPEN_ONLY, locatorForm = LaunchLocatorForm.NONE),
                ps3Emu(aps3e, installed = false, capability = EmulatorCapability.DIRECT_PAYLOAD, locatorForm = LaunchLocatorForm.EITHER),
            )
        }

        val status = provider.resolvePlatformEmulatorStatus(ps3)

        assertEquals(armsx3, status.resolved?.id)
        assertTrue(!status.needsEmulator)
    }

    @Test
    fun explicitOverrideStillHonoursAnOpenOnlyChoiceEvenWithAPayloadCandidateInstalled() {
        // Pin: the "Open With" per-game/per-platform override must NOT be redirected by the new Auto
        // preference tier. A player who deliberately picked ARMSX3 keeps getting ARMSX3.
        val provider = InstalledEmulatorProvider {
            listOf(
                ps3Emu(aps3e, installed = true, capability = EmulatorCapability.DIRECT_PAYLOAD, locatorForm = LaunchLocatorForm.EITHER),
                ps3Emu(armsx3, installed = true, capability = EmulatorCapability.OPEN_ONLY, locatorForm = LaunchLocatorForm.NONE),
            )
        }

        val status = provider.resolvePlatformEmulatorStatus(ps3, defaultEmulatorId = armsx3)

        assertEquals(armsx3, status.resolved?.id)
    }
}
