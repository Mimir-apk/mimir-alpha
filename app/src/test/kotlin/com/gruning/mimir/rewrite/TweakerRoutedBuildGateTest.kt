package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.tweaker.TweakerUtilityAvailability
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A build must offer the Tweaker route and restrict CPU-ceiling writes to the intended callers.
 *
 * Mimir Tuner discovers the kernel interfaces exposed by the SoC, without a manifest or qualification
 * gate. The bundled Tweaker is a separate app and Gradle build (`mimir-tweaker/`,
 * `com.gruning.mimir.tweaker`), also discovered without a manifest gate. Its daemon lets a held cap
 * survive Mimir being killed. The Tuner uses that service when present and retains direct writes on
 * devices without a daemon.
 *
 * Previously both wrote `cpu_max_freq`, with the daemon issuing 21 privileged writes during Mimir
 * startup while `MimirTunerWrite` also owned the node. The daemon now owns it whenever present.
 * `MimirTunerRoute.kt` decides the route centrally, and `MimirTunerWrite` obeys that decision at every
 * entry point, including cold start.
 *
 * The writer set below identifies the two callers allowed to request a ceiling. Adding a third caller
 * expands that set. These tests fail if the route is removed or a new caller reaches the write path.
 * They run in `:app:testDebugUnitTest`, already included in `tools/mimir-quality-gate.sh`'s JVM ladder.
 */
class TweakerRoutedBuildGateTest {

    /**
     * The route is offered by the SURFACE, not merely decidable by a predicate.
     *
     * `TweakerRoutePolicyTest` already pins `tweakerRouteDecision` in isolation, and that test would stay
     * green if someone deleted the one line in `AppBrightnessHost` that puts the row on the rail. This
     * asserts the rail itself: for every availability with a destination, `OPEN_TWEAKER` is actually in
     * the control list a build renders.
     */
    @Test
    fun `every availability with a destination puts the tweaker route on the utility rail`() {
        TweakerUtilityAvailability.entries.forEach { availability ->
            val controls = brightnessControls(
                state = utilityState(),
                tweakerAvailability = availability,
            )
            val routed = BrightnessControlKey.OPEN_TWEAKER in controls
            assertEquals(
                "availability=$availability: tweakerRoutePaired says " +
                    "${tweakerRoutePaired(availability)} but the Utility rail " +
                    "${if (routed) "offers" else "does NOT offer"} OPEN_TWEAKER. The rail and the route " +
                    "policy must not disagree — that disagreement is how the Tweaker came to read as " +
                    "missing on a device that had one.",
                tweakerRoutePaired(availability),
                routed,
            )
        }
    }

    /**
     * A new availability constant must be CLASSIFIED, not defaulted.
     *
     * `tweakerRoutePaired` is an exhaustive `when` so the compiler already forces a decision there. This
     * pins the count as well, so each added availability requires an explicit update to this coverage.
     */
    @Test
    fun `the set of availabilities is pinned, so adding one is a deliberate act`() {
        assertEquals(
            "TweakerUtilityAvailability changed. Classify the new value in tweakerRoutePaired AND decide " +
                "whether the Utility rail should route to it, then update this count.",
            5,
            TweakerUtilityAvailability.entries.size,
        )
        assertTrue(
            "at least one availability must route, or no build can reach the Tweaker at all",
            TweakerUtilityAvailability.entries.any(::tweakerRoutePaired),
        )
    }

    /**
     * **ONE writer for the CPU ceiling.**
     *
     * This is the half a route check cannot see, and it is about ARBITRATION, not legitimacy.
     * `cpu_max_freq` has two legitimate writers — the Tweaker daemon's
     * `selectSystemProfileOnWorker:4723`, issuing 21 privileged writes during Mimir's startup, and
     * `MimirTunerWrite`. **the daemon owns the node whenever it is present**;
     * the Tuner routes through it and writes directly only on a device with no daemon.
     *
     * A source pin rather than a runtime one, because the property is "who is allowed to call this",
     * which has no runtime seam — the same reason `MimirRimPairingTest` reads source.
     *
     * **The route landed on 2026-09-11 and this set did not change, exactly as this test predicted.**
     * Both call sites changed SHAPE — they now consume a typed outcome and, in the coordinator's case,
     * make the call off the main thread because the daemon arm is a Binder round trip — and neither
     * disappeared, because the Tuner is still the writer on every handheld with no Tweaker installed.
     * What the set forbids is unchanged: a THIRD caller, which would be a third way to move this node
     * and would have to be routed, tested and refused all over again.
     */
    @Test
    fun `nothing new may write the cpu ceiling now that the route decides who does`() {
        val allowed = setOf(
            // The dev proof hooks — `--ei mimir.capPolicy` / `--ez mimir.capWithdraw`. Not a product
            // path. Both hooks now dispatch to Dispatchers.IO, because the daemon arm is a Binder call.
            "MainActivity.kt",
            // The product path, now BEHIND the route: it asks MimirTunerWrite, which asks
            // MimirTunerDaemonRoute who may write. Still here, because a daemonless device still writes.
            "AppBrightnessCoordinator.kt",
        )
        val mutators = Regex("""MimirTunerWrite\.(requestCeiling|withdraw)\s*\(""")
        val offenders = sourceFiles()
            .filter { file ->
                file.readLines()
                    .map { it.substringBefore("//") }
                    .any(mutators::containsMatchIn)
            }
            .map { it.name }
            .toSortedSet()

        assertEquals(
            "The set of files that ASK for a CPU ceiling changed.\n" +
                "cpu_max_freq has two legitimate writers -- the Tweaker daemon and MimirTunerWrite. " +
                "The daemon owns the node whenever it is " +
                "present; the Tuner routes through it (MimirTunerRoute.kt decides, MimirTunerWrite " +
                "obeys) and writes directly only where there is no daemon.\n" +
                "The route landing did NOT empty this set and was never going to: the Tuner is still " +
                "the writer on every handheld with no Tweaker installed, and these two files are the " +
                "two that may ask it for a ceiling.\n" +
                "A third name here is a third way to move this node. Route it, test it and refuse it " +
                "the same way before you add it to this set.",
            allowed.toSortedSet(),
            offenders,
        )
    }

    /**
     * The route policy must stay the ONE decision, because the reason it is a policy is that a macro, the
     * M button and the Utility row all have to reach the same answer — *"or one of them becomes a silent
     * way to hand over control"*, which is the file's own stated purpose.
     */
    @Test
    fun `no call site decides the tweaker route for itself`() {
        val decidesLocally = Regex(
            """(privileged|status)\s*==\s*PrivilegedUtilityStatus\.READY\s*&&.*[Tt]weaker""",
        )
        val offenders = sourceFiles()
            .filter { it.name != "TweakerRoutePolicy.kt" }
            .filter { file ->
                file.readLines().map { it.substringBefore("//") }.any(decidesLocally::containsMatchIn)
            }
            .map { it.name }
        assertEquals(
            "A call site is re-deriving the Tweaker route instead of asking tweakerRouteDecision. " +
                "Two answers to one question is how an activation path becomes a quiet handover of " +
                "device control: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    private fun sourceFiles(): List<File> {
        val root = File("src/main/kotlin/com/gruning/mimir/rewrite")
        assertTrue(
            "could not locate app sources at ${root.absolutePath} — if the module moved, move this gate " +
                "with it rather than deleting it.",
            root.isDirectory,
        )
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** The minimum state the rail needs; every row this gate looks at is availability-driven. */
    private fun utilityState() = BrightnessState(
        capabilities = BrightnessCapabilities(
            canWriteSystemSettings = true,
            mainSystemBrightnessActive = true,
            secondaryWindowAvailable = false,
            secondarySupport = SecondaryBrightnessSupport.UNAVAILABLE,
            joystickLightSupported = false,
            joystickLightColorSupported = false,
            mimirHapticsSupported = false,
            mimirHapticAmplitudeControl = false,
            mediaVolumeSupported = false,
            mediaVolumeFixed = false,
            mainWriteAccessUseful = true,
            privilegedUtilityPosture = PrivilegedUtilityPosture.Unsupported,
            ambientSoundSupported = false,
        ),
        primaryFraction = 0.5f,
        primaryWindowFallback = null,
        secondaryFraction = null,
        secondaryWindowFallback = null,
        joystickLightFraction = null,
        joystickColorFraction = null,
        joystickColorArgb = null,
        themeAwareJoystickLight = false,
        mimirHapticFraction = null,
        mediaVolumeFraction = null,
        themeAwareJoystickLightRequested = false,
        themeAwareJoystickLightRestorePending = false,
        ambientSoundFraction = null,
    )
}
