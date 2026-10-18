package com.gruning.mimir.rewrite

/**
 * The tuning session, in a form that outlives the process that started it.
 *
 * **Why this type exists at all .** `MimirTunerWrite` holds its capture in
 * `@Volatile` process memory, so killing Mimir with a cap held destroys the only record of what the
 * device was holding before. The cap itself survives — it is a privileged sysfs write and nothing in
 * Mimir restores it on death — so what a kill actually costs is the WAY BACK, not the cap. The ruling
 * is therefore narrow and exact: **the record moves into the daemon; the write stays where it is.**
 * Moving the write instead would satisfy the words and fix nothing.
 *
 * **It is the whole session, not just the capture, and that is the part that is easy to get wrong.**
 * The CPU QoS node has a second writer — recorded on the device, CPU 7 at `INT_MAX` at 16:44 and
 * `2956800` at 17:14 with no Mimir write in between (`MimirTunerWrite.kt:183-195`) — which is why
 * withdrawal restores only the CPUs still holding OUR value and needs [cpuRequested] to know which
 * those are. A record carrying stock alone would come back after a kill as a blind stamp over whatever
 * the other writer had done since: Mimir undoing a change it never made and cannot see, which is
 * exactly the failure `requestedCpus` was added to prevent. Carrying stock without requested would
 * reintroduce it across an IPC instead of within a process, where it is harder to see, not easier.
 *
 * **No sysfs path is in this type, and that is deliberate.** The daemon is `signature`-protected and
 * privileged; handing it a caller-supplied node path would let an unprivileged process choose what a
 * privileged one writes. Because the ruling leaves the WRITE in Mimir, nothing here has to name a node
 * for the daemon to act on — the shape is fixed to the two axes this file already knows, so the API
 * *cannot* carry a path rather than merely declining to. That is stronger than validating one.
 */
data class MimirTunerSnapshot(
    /**
     * Which boot this session belongs to.
     *
     * Boot re-establishes the axis, so a record that outlived a reboot is describing a machine state
     * that no longer exists and must be discarded rather than adopted. The ours-check in
     * `MimirTunerWrite.withdraw` would defuse most stale adoptions by failing to match, but "most" is
     * a coincidence, not a guard.
     */
    val bootIdentity: String,
    /** The QoS row the device was holding before Mimir wrote anything, in the node's own spelling. */
    val cpuStockRow: String?,
    /** Exactly what Mimir asked each CPU for, so a later withdrawal can tell ours from somebody else's. */
    val cpuRequested: Map<Int, Int>,
    /** The Adreno's floor before Mimir touched it. Null when no GPU ceiling was ever requested. */
    val gpuStockMinLevel: Int? = null,
    /** The Adreno's ceiling before Mimir touched it. */
    val gpuStockMaxLevel: Int? = null,
    /** The floor Mimir wrote, for the same ours-check the CPU half does. */
    val gpuRequestedMinLevel: Int? = null,
    /** The ceiling Mimir wrote. */
    val gpuRequestedMaxLevel: Int? = null,
) {
    /** A record naming nothing is not worth crossing an IPC for, and must never be adopted. */
    fun isEmpty(): Boolean = (cpuStockRow == null || cpuRequested.isEmpty()) &&
        (gpuStockMinLevel == null || gpuRequestedMinLevel == null)
}

/**
 * What the daemon said when asked whether it is holding a session for us.
 *
 * **Three answers, not two, and collapsing the last two is the bug this type exists to prevent.**
 * "The daemon holds nothing" and "I could not ask the daemon" look identical at a call site that
 * models them as `null`, and they demand opposite behaviour at cold start: the first means there is
 * genuinely no session to adopt, the second means the evidence is simply not in yet and destroying it
 * would be irreversible.
 */
sealed interface MimirTunerRecordRead {
    /**
     * The daemon is not bound, not installed, not eligible, or did not answer in time.
     *
     * **Never read this as "no record".** Cold start must still take its safety action, but it must
     * not treat a silent transport as proof that nothing was held.
     */
    data object Unavailable : MimirTunerRecordRead

    /** The daemon answered, and it is holding nothing. This is the ordinary clean-boot case. */
    data object Absent : MimirTunerRecordRead

    /** The daemon answered and handed back a session a previous Mimir process left behind. */
    data class Held(val snapshot: MimirTunerSnapshot) : MimirTunerRecordRead
}

/**
 * The daemon-side record, as `MimirTunerWrite` sees it.
 *
 * Injected exactly the way `nodes` and `host` already are, for the same reason: the device path and
 * the test path must differ in one argument and nothing else. The default is [NoTunerRecordStore], so
 * every existing caller and every existing test keeps the behaviour it had before this seam existed.
 */
interface MimirTunerRecordStore {
    fun read(): MimirTunerRecordRead

    /** Upsert the whole session. Whole snapshots only — a delta cannot be applied to a record whose
     *  previous state the writer cannot see. */
    fun write(snapshot: MimirTunerSnapshot): Boolean

    /** Forget the session. Called when Mimir has given the device back and owes nothing. */
    fun clear(): Boolean
}

/**
 * No daemon: the discovered path on its own.
 *
 * **This is the default and it must stay the default.** Discovery is the half that works on every
 * handheld nobody wrote a manifest for; the daemon is the half that survives being killed. A device
 * with no Tweaker installed keeps precisely today's behaviour — a cap it can set, and a cold-start
 * widen if a previous process died holding one — and reports [MimirTunerRecordRead.Unavailable]
 * rather than [MimirTunerRecordRead.Absent], because "there is no daemon" is not the daemon saying it
 * holds nothing.
 */
object NoTunerRecordStore : MimirTunerRecordStore {
    override fun read(): MimirTunerRecordRead = MimirTunerRecordRead.Unavailable
    override fun write(snapshot: MimirTunerSnapshot): Boolean = false
    override fun clear(): Boolean = false
}
