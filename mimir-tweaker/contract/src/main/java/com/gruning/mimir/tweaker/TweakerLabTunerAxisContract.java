package com.gruning.mimir.tweaker;

import java.util.Collections;
import java.util.List;

/**
 * The synthetic tuner axis both halves of the deterministic adoption proof have to agree about.
 *
 * <p><b>This module is the only one both Gradle builds mount, and that is the whole reason the numbers
 * live here.</b> The app fabricates the axis; the daemon admits records against it. If the two sides
 * ever disagreed about a cpu id, a published frequency or the stock row, the disagreement would surface
 * as an {@code UNDISCOVERED_CPU} or {@code UNDISCOVERED_FREQUENCY} refusal — indistinguishable, from the
 * outside, from the daemon correctly refusing a genuinely bad record. A proof whose failure mode is
 * "looks exactly like the thing it is testing for" is not a proof, so the constants are shared rather
 * than mirrored.
 *
 * <p><b>Why 240 and 241.</b> cpufreq policy ids are cpu ids, and no shipping SoC has a CPU 240. The
 * synthetic cluster therefore cannot collide with real hardware on any device the proof runs on, which
 * is what lets the daemon's union carry it permanently in the lab posture with no arming protocol.
 *
 * <p><b>Why the stock row is {@code INT_MAX} on both cpus.</b> The QoS node spells "no ceiling
 * requested" as {@code 2147483647}, and admission explicitly legalises it on every cpu. Choosing it as
 * the synthetic stock makes three outcomes three DISTINCT file contents — a cap ({@code 1800000}), an
 * exact-stock restore ({@code 2147483647}) and a widen-to-ceiling ({@code 2400000}) — so a single
 * {@code cat} of the axis file tells adopted from withdrawn from widened from discarded, with no log
 * reading required. A stock row equal to the table ceiling would have collapsed the last two.
 *
 * <p>Constants only. Nothing here is privileged, nothing here names a real sysfs path, and this class
 * is inert in every posture that does not compose it in.
 */
public final class TweakerLabTunerAxisContract {

    private TweakerLabTunerAxisContract() {
    }

    /**
     * The directory, under the app's own {@code filesDir}, whose EXISTENCE is the armed flag.
     *
     * A directory rather than a preference or a process flag because arming has to survive
     * {@code am force-stop} — the proof's whole shape is "kill the app and see what the next process
     * does", and an arming signal that died with the process would disarm the axis at exactly the
     * moment under test.
     */
    public static final String LAB_AXIS_DIRECTORY_NAME = "lab-tuner-axis";

    /** The synthetic cpufreq policy directory, named the way the kernel names one. */
    public static final String LAB_POLICY_DIRECTORY_NAME = "policy240";

    /** The synthetic policy id, which is also its first cpu id. */
    public static final int LAB_POLICY_ID = 240;

    /** The second cpu in the synthetic cluster, so the per-cluster write path is genuinely exercised. */
    public static final int LAB_SECOND_CPU_ID = 241;

    /** The file the app's axis substitutes for the real QoS request node. */
    public static final String LAB_QOS_FILE_NAME = "cpu_max_freq";

    /** A pristine copy of {@link #LAB_STOCK_ROW}, so a device script can restore it without typing it. */
    public static final String LAB_STOCK_ROW_FILE_NAME = "stock_row";

    /** What the synthetic policy publishes, in the cpufreq node's own ascending-kHz spelling. */
    public static final String LAB_AVAILABLE_FREQUENCIES_ROW = "600000 1200000 1800000 2400000";

    /** What the synthetic policy's {@code affected_cpus} publishes. */
    public static final String LAB_AFFECTED_CPUS_ROW = "240 241";

    /** The synthetic axis at rest: no ceiling requested on either cpu. */
    public static final String LAB_STOCK_ROW = "240:2147483647 241:2147483647";

    /** The top of {@link #LAB_AVAILABLE_FREQUENCIES_ROW}; a request at this value restricts nothing. */
    public static final int LAB_CEILING_KHZ = 2400000;

    /** A step strictly below the ceiling, so a cap is observable. The proof's cap value. */
    public static final int LAB_CAP_KHZ = 1800000;

    private static final int[] FREQUENCIES_KHZ = {600000, 1200000, 1800000, 2400000};

    private static final List<Integer> CPU_IDS =
            Collections.unmodifiableList(java.util.Arrays.asList(LAB_POLICY_ID, LAB_SECOND_CPU_ID));

    /** The published table, as a fresh array — never the backing one, so no caller can edit the axis. */
    public static int[] frequenciesKhz() {
        return FREQUENCIES_KHZ.clone();
    }

    /** The cpus this synthetic cluster governs. */
    public static List<Integer> cpuIds() {
        return CPU_IDS;
    }
}
