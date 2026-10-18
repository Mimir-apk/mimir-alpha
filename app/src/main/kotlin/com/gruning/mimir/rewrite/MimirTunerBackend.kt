package com.gruning.mimir.rewrite

/**
 * The one place the discovered tuner decides WHICH kernel it is talking to.
 *
 * **Why an indirection exists at all, when every function here was already injectable.** [MimirTunerNodes]
 * and the `host` lambda have been parameters since the axis was written, and every unit test already
 * uses them. What no test could reach is the DEVICE path: `MainActivity`'s dev hooks, `TweakerBridge`'s
 * cold-start reconcile and `MimirCompanionPresentation` all take the defaults, so on hardware there was
 * no seam at all. A deterministic adoption proof has to run the real call sites — the same
 * `requestCeiling`, the same `reconcileColdStart`, the same Binder round trip — against an axis whose
 * every value is known in advance, and that means the DEFAULTS have to be substitutable, not just the
 * parameters.
 *
 * **Process-scoped rather than threaded through, for the reason `MimirTunerWrite.recordStore` already
 * is.** The alternative is a new parameter on a dozen signatures whose call sites disagree about
 * positional order (`host` is last at some, fourth at others), which would break existing callers and
 * existing tests to express something that is genuinely one process-wide binding.
 *
 * **The defaults are the real device, and nothing in `src/main` ever changes them.** The only writer is
 * `LabTunerAxisInstaller`, which is compiled into debuggable builds only and acts only when the owner has
 * explicitly armed a directory in the app's own storage. On a release build the installer is a no-op stub
 * and these three fields are unreachable, so the shipping product's behaviour is byte-identical to the
 * behaviour it had before this object existed. `MimirTunerBackendDefaultsTest` pins that.
 *
 * **Reads happen at call time, never at class-init time.** Each field is read fresh inside the default
 * argument expression it feeds, so arming after a class has loaded still takes effect — which matters,
 * because arming happens in `onCreate` and `MimirTunerWrite` is an object that may already be resident.
 */
object MimirTunerBackend {

    /** Where the frequency tables are read from. Real cpufreq and kgsl on a device. */
    @Volatile
    var nodes: () -> MimirTunerNodes = { RealMimirTunerNodes() }

    /** How a privileged command is delivered. The vendor's PServer on a device. */
    @Volatile
    var host: (String) -> Boolean = PServerHost::run

    /**
     * Whether a privileged host is present at all.
     *
     * **The load-bearing one.** `MimirTunerWrite.releaseAnyOrphanedCap` returns immediately when this is
     * false, before the cold-start reconcile can run — so on a device with no PServer, no substitution
     * anywhere else could make adoption observable. Routing this is what makes the proof runnable on ANY
     * device rather than only on silicon that happens to ship a performance server.
     */
    @Volatile
    var hostAvailable: () -> Boolean = PServerHost::available
}
