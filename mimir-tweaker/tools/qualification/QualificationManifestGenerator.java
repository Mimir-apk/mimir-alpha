package com.gruning.mimir.tweaker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Console;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.EdECPublicKey;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.security.auth.Destroyable;

/**
 * Host-only MQMP/MQMS v1 signer. This file is registered as an Android unit-test source only and
 * must never enter a shipping source set or APK.
 */
public final class QualificationManifestGenerator {
    public static final int PAYLOAD_MAGIC = 0x4D514D50; // MQMP
    public static final int SIGNED_MAGIC = 0x4D514D53; // MQMS
    public static final int WIRE_VERSION = 1;
    // WAVE2 Stage B: bumped 2 -> 3 for qualified thermal-derate rungs (see RungPlan / the
    // ProfilePlan.derateRungs component).
    public static final int MANIFEST_SCHEMA = 3;
    public static final int SERVICE_PLAN_SCHEMA = 3;
    /**
     * WAVE2 Stage B: fixed schema-v2 threshold, deliberately DECOUPLED from {@link #MANIFEST_SCHEMA}
     * / {@link #SERVICE_PLAN_SCHEMA} (now 3, "current") -- mirrors
     * {@code QUALIFICATION_MANIFEST_SCHEMA_V2} on the Kotlin runtime side exactly.
     *
     * <p>This generator does not decode already-signed historical bytes the way the Kotlin runtime
     * does, but it MINTS a v2-shaped artifact every time it re-signs an unchanged
     * {@code MIMIR_QUALIFICATION_REQUEST_V2} request file -- re-running the tool after a lost
     * artifact, or under a rotated key, must reproduce the same schema-2 bytes for the same
     * (sequence, content), or the on-device sequence store (which binds one sequence to one
     * payload hash) rejects the re-issued manifest as {@code REJECTED_CONFLICT}. Collapsing "has
     * manual fields" (true from v2 onward) into "is current" (bumped to 3 by Stage B) silently
     * upgraded every V2 request to schema-3 bytes; this fixed threshold is what SIGNING.md's
     * "legacy v1 / schema-v2 / schema-v3" trio actually requires the tool to keep producing.
     */
    public static final int MANIFEST_SCHEMA_V2 = 2;
    public static final int SERVICE_PLAN_SCHEMA_V2 = 2;
    public static final int LEGACY_MANIFEST_SCHEMA = 1;
    public static final int LEGACY_SERVICE_PLAN_SCHEMA = 1;
    public static final long MANUAL_FAN_OVERRIDE_MIN_SEQUENCE = 5L;
    public static final int MAX_PAYLOAD_BYTES = 32 * 1024;
    public static final int MAX_SIGNED_BYTES = MAX_PAYLOAD_BYTES + 128;

    private static final int ED25519_SPKI_BYTES = 44;
    private static final int ED25519_SIGNATURE_BYTES = 64;
    private static final int MAX_REQUEST_BYTES = 32 * 1024;
    private static final int MAX_CPU_POLICIES = 32;
    /** Mirrors QualifiedProfilePlan.MAX_DERATE_RUNGS on the Kotlin side exactly. */
    private static final int MAX_DERATE_RUNGS = 16;
    private static final Set<String> PROFILE_IDS = Set.of("quiet", "balanced", "performance");
    private static final String ODIN_3_PROFILE = "ayn-odin3-cq8725s-pserver";
    private static final int FAN_MANUAL = 6;
    // Base profiles may never own manual PWM. Mode 6 exists only in a schema-v2,
    // sequence-5+ manual override bound to the same Smart profile.
    private static final Set<Integer> FAN_MODES = Set.of(1, 4, 5);
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE
    );
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE
    );
    private static final Comparator<String> UNSIGNED_UTF8 = (left, right) -> {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        int count = Math.min(a.length, b.length);
        for (int index = 0; index < count; index++) {
            int comparison = Integer.compare(Byte.toUnsignedInt(a[index]), Byte.toUnsignedInt(b[index]));
            if (comparison != 0) return comparison;
        }
        return Integer.compare(a.length, b.length);
    };

    private QualificationManifestGenerator() {}

    /**
     * WAVE2 Stage B / schema v3: one qualified thermal-derate rung, mirroring
     * QualifiedDerateRung on the Kotlin side. There is deliberately no `fanMode` component here --
     * rule 1 (enforced in ProfilePlan's compact constructor below) forces a rung's fan mode to
     * always equal its enclosing profile's own `fanMode`, so the author-facing type simply cannot
     * express a mismatch; [planSha256] takes the enclosing profile's fan mode explicitly for the
     * same reason.
     */
    public record RungPlan(
        Map<Integer, Integer> cpuMaxKhz,
        Integer gpuMaxPowerLevel,
        String evidenceIndexSha256,
        String guardianRollbackEvidenceSha256
    ) {
        public RungPlan {
            require(cpuMaxKhz != null && !cpuMaxKhz.isEmpty(), "DERATE_RUNG_CPU_TARGETS_EMPTY");
            require(cpuMaxKhz.size() <= MAX_CPU_POLICIES, "DERATE_RUNG_CPU_TARGETS_TOO_MANY");
            LinkedHashMap<Integer, Integer> pinned = new LinkedHashMap<>();
            int previous = -1;
            for (Map.Entry<Integer, Integer> entry : cpuMaxKhz.entrySet()) {
                Integer id = entry.getKey();
                Integer khz = entry.getValue();
                require(id != null && id >= 0 && id > previous, "DERATE_RUNG_CPU_TARGET_ORDER_INVALID");
                require(khz != null && khz > 0, "DERATE_RUNG_CPU_TARGET_VALUE_INVALID");
                previous = id;
                pinned.put(id, khz);
            }
            cpuMaxKhz = Collections.unmodifiableMap(pinned);
            require(gpuMaxPowerLevel == null || gpuMaxPowerLevel >= 0, "DERATE_RUNG_GPU_TARGET_INVALID");
            requireDigest(evidenceIndexSha256, "DERATE_RUNG_EVIDENCE_DIGEST_INVALID");
            requireDigest(guardianRollbackEvidenceSha256, "DERATE_RUNG_GUARDIAN_DIGEST_INVALID");
            require(!isZeroDigest(evidenceIndexSha256), "DERATE_RUNG_EVIDENCE_DIGEST_ZERO");
            require(!isZeroDigest(guardianRollbackEvidenceSha256), "DERATE_RUNG_GUARDIAN_DIGEST_ZERO");
        }

        String planSha256(Integer fanMode) {
            return planIdentityHash(cpuMaxKhz, gpuMaxPowerLevel, fanMode);
        }
    }

    public record ProfilePlan(
        String profileId,
        Map<Integer, Integer> cpuMaxKhz,
        Integer gpuMaxPowerLevel,
        Integer fanMode,
        String evidenceIndexSha256,
        String guardianRollbackEvidenceSha256,
        Integer manualFanMinimumDutyPercent,
        String manualFanEvidenceIndexSha256,
        String manualFanGuardianRollbackEvidenceSha256,
        List<RungPlan> derateRungs
    ) {
        public ProfilePlan(
            String profileId,
            Map<Integer, Integer> cpuMaxKhz,
            Integer gpuMaxPowerLevel,
            Integer fanMode,
            String evidenceIndexSha256,
            String guardianRollbackEvidenceSha256
        ) {
            this(
                profileId,
                cpuMaxKhz,
                gpuMaxPowerLevel,
                fanMode,
                evidenceIndexSha256,
                guardianRollbackEvidenceSha256,
                null,
                null,
                null,
                List.of()
            );
        }

        public ProfilePlan(
            String profileId,
            Map<Integer, Integer> cpuMaxKhz,
            Integer gpuMaxPowerLevel,
            Integer fanMode,
            String evidenceIndexSha256,
            String guardianRollbackEvidenceSha256,
            Integer manualFanMinimumDutyPercent,
            String manualFanEvidenceIndexSha256,
            String manualFanGuardianRollbackEvidenceSha256
        ) {
            this(
                profileId,
                cpuMaxKhz,
                gpuMaxPowerLevel,
                fanMode,
                evidenceIndexSha256,
                guardianRollbackEvidenceSha256,
                manualFanMinimumDutyPercent,
                manualFanEvidenceIndexSha256,
                manualFanGuardianRollbackEvidenceSha256,
                List.of()
            );
        }

        public ProfilePlan {
            require(PROFILE_IDS.contains(profileId), "PROFILE_ID_INVALID");
            require(cpuMaxKhz != null && !cpuMaxKhz.isEmpty(), "CPU_TARGETS_EMPTY");
            require(cpuMaxKhz.size() <= MAX_CPU_POLICIES, "CPU_TARGETS_TOO_MANY");
            LinkedHashMap<Integer, Integer> pinned = new LinkedHashMap<>();
            int previous = -1;
            for (Map.Entry<Integer, Integer> entry : cpuMaxKhz.entrySet()) {
                Integer id = entry.getKey();
                Integer khz = entry.getValue();
                require(id != null && id >= 0 && id > previous, "CPU_TARGET_ORDER_INVALID");
                require(khz != null && khz > 0, "CPU_TARGET_VALUE_INVALID");
                previous = id;
                pinned.put(id, khz);
            }
            cpuMaxKhz = Collections.unmodifiableMap(pinned);
            require(gpuMaxPowerLevel == null || gpuMaxPowerLevel >= 0, "GPU_TARGET_INVALID");
            require(fanMode == null || FAN_MODES.contains(fanMode), "FAN_TARGET_INVALID");
            requireDigest(evidenceIndexSha256, "EVIDENCE_DIGEST_INVALID");
            requireDigest(guardianRollbackEvidenceSha256, "GUARDIAN_DIGEST_INVALID");
            require(!isZeroDigest(evidenceIndexSha256), "EVIDENCE_DIGEST_ZERO");
            require(!isZeroDigest(guardianRollbackEvidenceSha256), "GUARDIAN_DIGEST_ZERO");
            if (manualFanMinimumDutyPercent == null) {
                require(manualFanEvidenceIndexSha256 == null, "MANUAL_EVIDENCE_UNEXPECTED");
                require(manualFanGuardianRollbackEvidenceSha256 == null, "MANUAL_GUARDIAN_UNEXPECTED");
            } else {
                require(fanMode != null && fanMode == 4, "MANUAL_AUTO_TARGET_INVALID");
                // the 20% floor is retired for every profile, so this
                // manual-fan-floor manifest field is unconditionally 0 now. This specific field
                // was already 0 for "quiet" (unchanged for quiet here); balanced/performance
                // move from 20 to 0. Quiet's separate CONTROLLER floor
                // (FanPwmBounds.MIN_SPIN_PERCENT / FanThermalProfile.minSpinPercent in
                // FanThermalControl.kt) was still 20 before this floor change and changes too -- do
                // not read this as "quiet's behavior is unchanged".
                int requiredFloor = 0;
                require(manualFanMinimumDutyPercent == requiredFloor, "MANUAL_FAN_FLOOR_INVALID");
                requireDigest(manualFanEvidenceIndexSha256, "MANUAL_EVIDENCE_DIGEST_INVALID");
                requireDigest(manualFanGuardianRollbackEvidenceSha256, "MANUAL_GUARDIAN_DIGEST_INVALID");
                require(!isZeroDigest(manualFanEvidenceIndexSha256), "MANUAL_EVIDENCE_DIGEST_ZERO");
                require(!isZeroDigest(manualFanGuardianRollbackEvidenceSha256), "MANUAL_GUARDIAN_DIGEST_ZERO");
            }
            // WAVE2 Stage B / schema v3 admission -- mirrors QualifiedProfilePlan's rules 1-2
            // exactly, including keeping them as two INDEPENDENT checks (rules 3-4 are already
            // enforced by RungPlan's own compact constructor above: planSha256 identity is
            // computed, never carried, and evidence is mandatory).
            require(derateRungs != null, "DERATE_RUNGS_NULL");
            require(derateRungs.size() <= MAX_DERATE_RUNGS, "DERATE_RUNGS_TOO_MANY");
            List<RungPlan> pinnedRungs = List.copyOf(derateRungs);
            // Rule 1: every rung dominated by the BASE PLAN ITSELF, checked independently against
            // the fixed base (not the previous rung) so a faster-than-nominal rung and a merely
            // out-of-order rung stay independently attributable failures.
            for (RungPlan rung : pinnedRungs) {
                require(rung != null, "DERATE_RUNG_NULL");
                require(rung.cpuMaxKhz().keySet().equals(cpuMaxKhz.keySet()), "DERATE_RUNG_CPU_POLICY_MISMATCH");
                for (Map.Entry<Integer, Integer> entry : rung.cpuMaxKhz().entrySet()) {
                    require(entry.getValue() <= cpuMaxKhz.get(entry.getKey()), "DERATE_RUNG_CPU_NOT_DOMINATED");
                }
                require(
                    (gpuMaxPowerLevel == null) == (rung.gpuMaxPowerLevel() == null),
                    "DERATE_RUNG_GPU_AXIS_MISMATCH"
                );
                if (rung.gpuMaxPowerLevel() != null) {
                    require(rung.gpuMaxPowerLevel() >= gpuMaxPowerLevel, "DERATE_RUNG_GPU_NOT_DOMINATED");
                }
            }
            // Rule 2: rungs ordered and monotonically slower -- each rung no faster than the ONE
            // immediately before it. Deliberately compares ONLY adjacent pairs WITHIN pinnedRungs
            // itself and never chains from the base plan: seeding the first comparison from the
            // base would transitively re-derive rule 1's own guarantee by induction, making rule 1
            // mathematically redundant and impossible to revert independently of rule 2 (mirrors
            // QualifiedProfilePlan's kdoc on the Kotlin side exactly).
            for (int index = 1; index < pinnedRungs.size(); index++) {
                RungPlan previousRung = pinnedRungs.get(index - 1);
                RungPlan rung = pinnedRungs.get(index);
                for (Map.Entry<Integer, Integer> entry : rung.cpuMaxKhz().entrySet()) {
                    require(
                        entry.getValue() <= previousRung.cpuMaxKhz().get(entry.getKey()),
                        "DERATE_RUNG_CPU_NOT_DOMINATED"
                    );
                }
                if (rung.gpuMaxPowerLevel() != null) {
                    require(
                        rung.gpuMaxPowerLevel() >= previousRung.gpuMaxPowerLevel(),
                        "DERATE_RUNG_GPU_NOT_DOMINATED"
                    );
                }
            }
            derateRungs = pinnedRungs;
        }

        public String planSha256() {
            return planIdentityHash(cpuMaxKhz, gpuMaxPowerLevel, fanMode);
        }

        public String manualFanPlanSha256() {
            return manualFanMinimumDutyPercent == null
                ? null
                : planIdentityHash(cpuMaxKhz, gpuMaxPowerLevel, FAN_MANUAL);
        }
    }

    public record Request(
        int manifestSchema,
        long sequence,
        String hardwareFingerprint,
        String deviceProfile,
        List<ProfilePlan> profiles
    ) {
        public Request(
            long sequence,
            String hardwareFingerprint,
            String deviceProfile,
            List<ProfilePlan> profiles
        ) {
            this(MANIFEST_SCHEMA, sequence, hardwareFingerprint, deviceProfile, profiles);
        }

        public Request {
            require(
                manifestSchema == LEGACY_MANIFEST_SCHEMA
                    || manifestSchema == MANIFEST_SCHEMA_V2
                    || manifestSchema == MANIFEST_SCHEMA,
                "MANIFEST_SCHEMA_INVALID"
            );
            require(sequence > 0, "SEQUENCE_INVALID");
            requireDigest(hardwareFingerprint, "HARDWARE_FINGERPRINT_INVALID");
            require(
                deviceProfile != null && deviceProfile.matches("[A-Za-z0-9._-]{1,128}"),
                "DEVICE_PROFILE_INVALID"
            );
            require(profiles != null && !profiles.isEmpty(), "PROFILES_EMPTY");
            require(profiles.size() <= PROFILE_IDS.size(), "PROFILES_TOO_MANY");
            require(
                sequence >= MANUAL_FAN_OVERRIDE_MIN_SEQUENCE ||
                    profiles.stream().noneMatch(profile -> profile.manualFanMinimumDutyPercent() != null),
                "MANUAL_SEQUENCE_INVALID"
            );
            // Fixed at the v2 threshold, not "current" -- see MANIFEST_SCHEMA_V2. Manual fan
            // fields are legal starting at schema v2 and remain legal at v3; only a legacy v1
            // request may never carry one.
            require(
                manifestSchema >= MANIFEST_SCHEMA_V2 ||
                    profiles.stream().noneMatch(profile -> profile.manualFanMinimumDutyPercent() != null),
                "MANUAL_SCHEMA_INVALID"
            );
            require(
                ODIN_3_PROFILE.equals(deviceProfile) ||
                    profiles.stream().noneMatch(profile -> profile.manualFanMinimumDutyPercent() != null),
                "MANUAL_DEVICE_INVALID"
            );
            // WAVE2 Stage B: derateRungs is a schema-v3 field, mirroring QualificationManifest's
            // own "schemaVersion <= QUALIFICATION_MANIFEST_SCHEMA_V2 -> no rungs" gate -- and, like
            // that gate, pinned to "current" (>=, not ==) rather than a fixed threshold, since
            // derateRungs is the NEW field a future schema bump would extend rather than freeze.
            // Unlike manual fan override, rungs carry no device-profile restriction here: nothing
            // in the schema contract ties the SCHEMA (as opposed to today's Odin-only ladder
            // DATA) to one device, and the runtime already restricts a rung to the exact hardware
            // it was signed for via hardwareFingerprint/deviceProfile matching.
            require(
                manifestSchema >= MANIFEST_SCHEMA ||
                    profiles.stream().allMatch(profile -> profile.derateRungs().isEmpty()),
                "DERATE_RUNGS_SCHEMA_INVALID"
            );
            List<ProfilePlan> pinned = List.copyOf(profiles);
            String previous = null;
            for (ProfilePlan profile : pinned) {
                require(profile != null, "PROFILE_NULL");
                require(profile.fanMode() == null || profile.fanMode() != FAN_MANUAL, "FAN_TARGET_INVALID");
                if (previous != null) {
                    require(UNSIGNED_UTF8.compare(previous, profile.profileId()) < 0, "PROFILE_ORDER_INVALID");
                }
                previous = profile.profileId();
            }
            profiles = pinned;
        }
    }

    public static final class GeneratedArtifacts {
        private final byte[] payload;
        private final byte[] signedManifest;
        private final byte[] publicKeySpki;
        private final String keyId;

        private GeneratedArtifacts(byte[] payload, byte[] signedManifest, byte[] publicKeySpki, String keyId) {
            this.payload = payload.clone();
            this.signedManifest = signedManifest.clone();
            this.publicKeySpki = publicKeySpki.clone();
            this.keyId = keyId;
        }

        public byte[] payload() { return payload.clone(); }
        public byte[] signedManifest() { return signedManifest.clone(); }
        public byte[] publicKeySpki() { return publicKeySpki.clone(); }
        public String keyId() { return keyId; }
        public String payloadSha256() { return sha256Hex(payload); }
        public String manifestSha256() { return sha256Hex(signedManifest); }
        public String manifestBase64() { return Base64.getEncoder().encodeToString(signedManifest); }
        public String trustAnchorBase64() { return Base64.getEncoder().encodeToString(publicKeySpki); }
    }

    static final class GeneratorException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final String code;

        GeneratorException(String code) {
            super(code);
            this.code = code;
        }

        String code() { return code; }
    }

    @FunctionalInterface
    interface PasswordReader {
        char[] readPassword();
    }

    @FunctionalInterface
    interface BeforeOutputReservation {
        void run(Path output) throws IOException;
    }

    public static Request parseRequest(Path requestPath) {
        try {
            byte[] encoded = Files.readAllBytes(requestPath);
            require(encoded.length > 0 && encoded.length <= MAX_REQUEST_BYTES, "REQUEST_SIZE_INVALID");
            String text = decodeUtf8(encoded);
            require(text.endsWith("\n") && text.indexOf('\r') < 0 && text.indexOf('\0') < 0, "REQUEST_TEXT_INVALID");
            String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
            Cursor cursor = new Cursor(lines);
            String requestHeader = cursor.next();
            // WAVE2 Stage B: V3 requires the same manual-fan fields V2 does (schema v3's wire
            // format is a strict superset of v2's -- see QUALIFICATION_MANIFEST_SCHEMA_V2 on the
            // Kotlin side) PLUS the new repeated rung= blocks below.
            boolean rungFields = "MIMIR_QUALIFICATION_REQUEST_V3".equals(requestHeader);
            boolean manualFields = rungFields || "MIMIR_QUALIFICATION_REQUEST_V2".equals(requestHeader);
            require(manualFields || "MIMIR_QUALIFICATION_REQUEST_V1".equals(requestHeader), "REQUEST_ORDER_INVALID");
            long sequence = parsePositiveLong(cursor.value("sequence"), "SEQUENCE_INVALID");
            String fingerprint = cursor.value("hardwareFingerprint");
            requireDigest(fingerprint, "HARDWARE_FINGERPRINT_INVALID");
            String deviceProfile = cursor.value("deviceProfile");
            require(deviceProfile.matches("[A-Za-z0-9._-]{1,128}"), "DEVICE_PROFILE_INVALID");

            List<ProfilePlan> profiles = new ArrayList<>();
            while (!cursor.peek("end")) {
                String profileId = cursor.value("profile");
                LinkedHashMap<Integer, Integer> cpu = new LinkedHashMap<>();
                while (cursor.peekPrefix("cpu.")) {
                    String line = cursor.next();
                    int separator = line.indexOf('=');
                    require(separator > "cpu.".length() && separator == line.lastIndexOf('='), "CPU_TARGET_INVALID");
                    String idText = line.substring("cpu.".length(), separator);
                    String khzText = line.substring(separator + 1);
                    int id = parseNonNegativeInt(idText, "CPU_POLICY_ID_INVALID");
                    int khz = parsePositiveInt(khzText, "CPU_TARGET_VALUE_INVALID");
                    require(cpu.put(id, khz) == null, "CPU_TARGET_DUPLICATE");
                }
                Integer gpu = parseNullableNonNegativeInt(cursor.value("gpuMaxPowerLevel"), "GPU_TARGET_INVALID");
                Integer fan = parseNullableNonNegativeInt(cursor.value("fanMode"), "FAN_TARGET_INVALID");
                String evidence = cursor.value("evidenceIndexSha256");
                String guardian = cursor.value("guardianRollbackEvidenceSha256");
                Integer manualFloor = null;
                String manualEvidence = null;
                String manualGuardian = null;
                if (manualFields) {
                    manualFloor = parseNullableNonNegativeInt(
                        cursor.value("manualFanMinimumDutyPercent"),
                        "MANUAL_FAN_FLOOR_INVALID"
                    );
                    String candidateEvidence = cursor.value("manualFanEvidenceIndexSha256");
                    String candidateGuardian = cursor.value("manualFanGuardianRollbackEvidenceSha256");
                    if (manualFloor == null) {
                        require("null".equals(candidateEvidence), "MANUAL_EVIDENCE_UNEXPECTED");
                        require("null".equals(candidateGuardian), "MANUAL_GUARDIAN_UNEXPECTED");
                    } else {
                        manualEvidence = candidateEvidence;
                        manualGuardian = candidateGuardian;
                    }
                }
                // WAVE2 Stage B: repeated rung= blocks, V3 requests only. Strictly ordered
                // (1, 2, 3, ...) and no duplicates by construction -- a skipped or repeated index
                // is rejected rather than silently reordered. No fanMode field here: rule 1 forces
                // a rung's fan mode to always equal the profile's own, so encodePayload derives it
                // below instead of letting the request author diverge from it.
                List<RungPlan> rungs = new ArrayList<>();
                if (rungFields) {
                    int expectedRungIndex = 1;
                    while (cursor.peekPrefix("rung=")) {
                        int rungIndex = parseNonNegativeInt(cursor.value("rung"), "DERATE_RUNG_INDEX_INVALID");
                        require(rungIndex == expectedRungIndex, "DERATE_RUNG_ORDER_INVALID");
                        expectedRungIndex++;
                        LinkedHashMap<Integer, Integer> rungCpu = new LinkedHashMap<>();
                        while (cursor.peekPrefix("cpu.")) {
                            String rungLine = cursor.next();
                            int rungSeparator = rungLine.indexOf('=');
                            require(
                                rungSeparator > "cpu.".length() && rungSeparator == rungLine.lastIndexOf('='),
                                "CPU_TARGET_INVALID"
                            );
                            String rungIdText = rungLine.substring("cpu.".length(), rungSeparator);
                            String rungKhzText = rungLine.substring(rungSeparator + 1);
                            int rungPolicyId = parseNonNegativeInt(rungIdText, "CPU_POLICY_ID_INVALID");
                            int rungKhz = parsePositiveInt(rungKhzText, "CPU_TARGET_VALUE_INVALID");
                            require(rungCpu.put(rungPolicyId, rungKhz) == null, "CPU_TARGET_DUPLICATE");
                        }
                        Integer rungGpu = parseNullableNonNegativeInt(
                            cursor.value("gpuMaxPowerLevel"),
                            "GPU_TARGET_INVALID"
                        );
                        String rungEvidence = cursor.value("evidenceIndexSha256");
                        String rungGuardian = cursor.value("guardianRollbackEvidenceSha256");
                        cursor.expect("endRung");
                        rungs.add(new RungPlan(rungCpu, rungGpu, rungEvidence, rungGuardian));
                    }
                }
                cursor.expect("endProfile");
                profiles.add(new ProfilePlan(
                    profileId,
                    cpu,
                    gpu,
                    fan,
                    evidence,
                    guardian,
                    manualFloor,
                    manualEvidence,
                    manualGuardian,
                    rungs
                ));
            }
            cursor.expect("end");
            require(cursor.finished(), "REQUEST_TRAILING_CONTENT");
            // Three-way, matching the three legal request headers exactly: V1 stays LEGACY, V2
            // mints true schema-v2 bytes (MANIFEST_SCHEMA_V2, not "current"), V3 mints "current".
            // Collapsing this to a v1/current binary previously upgraded every V2 request to
            // whatever "current" happened to be -- see MANIFEST_SCHEMA_V2's kdoc.
            int manifestSchema = rungFields
                ? MANIFEST_SCHEMA
                : manualFields
                    ? MANIFEST_SCHEMA_V2
                    : LEGACY_MANIFEST_SCHEMA;
            return new Request(
                manifestSchema,
                sequence,
                fingerprint,
                deviceProfile,
                profiles
            );
        } catch (GeneratorException error) {
            throw error;
        } catch (IOException error) {
            throw new GeneratorException("REQUEST_READ_FAILED");
        }
    }

    public static GeneratedArtifacts generate(Request request, PrivateKey privateKey, PublicKey publicKey) {
        require(request != null, "REQUEST_NULL");
        validateEd25519(privateKey, publicKey);
        byte[] publicSpki = publicKey.getEncoded().clone();
        String keyId = sha256Hex(publicSpki);
        byte[] payload = encodePayload(request, keyId);
        byte[] signature = null;
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(payload);
            signature = signer.sign();
            require(signature.length == ED25519_SIGNATURE_BYTES, "SIGNATURE_LENGTH_INVALID");
            require(verify(payload, signature, publicKey), "SIGNATURE_SELF_CHECK_FAILED");
            byte[] signed = encodeEnvelope(payload, signature);
            ParsedEnvelope parsed = parseEnvelope(signed);
            require(Arrays.equals(parsed.payload(), payload), "ENVELOPE_PAYLOAD_MISMATCH");
            require(verify(parsed.payload(), parsed.signature(), publicKey), "ENVELOPE_SIGNATURE_INVALID");
            return new GeneratedArtifacts(payload, signed, publicSpki, keyId);
        } catch (GeneratorException error) {
            throw error;
        } catch (Exception error) {
            throw new GeneratorException("SIGNING_FAILED");
        } finally {
            if (signature != null) Arrays.fill(signature, (byte) 0);
            Arrays.fill(publicSpki, (byte) 0);
        }
    }

    static void writeOwnerLocal(GeneratedArtifacts artifacts, Path outputDirectory, Path repositoryRoot) {
        writeOwnerLocal(artifacts, outputDirectory, repositoryRoot, ignored -> {});
    }

    static void writeOwnerLocal(
        GeneratedArtifacts artifacts,
        Path outputDirectory,
        Path repositoryRoot,
        BeforeOutputReservation beforeReservation
    ) {
        require(artifacts != null, "ARTIFACTS_NULL");
        require(beforeReservation != null, "OUTPUT_RESERVATION_INVALID");
        Path repo = realDirectory(repositoryRoot, "REPOSITORY_ROOT_INVALID");
        require(outputDirectory != null && outputDirectory.isAbsolute(), "OUTPUT_NOT_ABSOLUTE");
        Path rawParent = outputDirectory.getParent();
        require(rawParent != null, "OUTPUT_PARENT_INVALID");
        Path parent = realDirectory(rawParent, "OUTPUT_PARENT_INVALID");
        Path output = parent.resolve(outputDirectory.getFileName()).normalize();
        require(!output.startsWith(repo), "OUTPUT_INSIDE_REPOSITORY");
        require(!Files.exists(output, LinkOption.NOFOLLOW_LINKS), "OUTPUT_EXISTS");

        boolean published = false;
        boolean outputOwned = false;
        try {
            beforeReservation.run(output);
            try {
                Files.createDirectory(output);
            } catch (FileAlreadyExistsException error) {
                throw new GeneratorException("OUTPUT_EXISTS");
            }
            outputOwned = true;
            setPermissions(output, DIRECTORY_PERMISSIONS);
            writeAtomic(
                output,
                ".incomplete",
                "MIMIR_QUALIFICATION_PUBLICATION_INCOMPLETE\n".getBytes(StandardCharsets.US_ASCII)
            );
            writeAtomic(output, "qualification-manifest.mqms", artifacts.signedManifest());
            writeAtomic(
                output,
                "qualification-manifest.base64",
                artifacts.manifestBase64().getBytes(StandardCharsets.US_ASCII)
            );
            writeAtomic(
                output,
                "qualification-trust-anchor.base64",
                artifacts.trustAnchorBase64().getBytes(StandardCharsets.US_ASCII)
            );
            forceDirectory(output);
            verifyPublishedFiles(output, artifacts);
            Files.delete(output.resolve(".incomplete"));
            forceDirectory(output);
            verifyPublishedFiles(output, artifacts);
            forceDirectory(parent);
            published = true;
        } catch (GeneratorException error) {
            throw error;
        } catch (Exception error) {
            throw new GeneratorException("OUTPUT_WRITE_FAILED");
        } finally {
            if (!published) {
                if (outputOwned) deleteTree(output);
            }
        }
    }

    static int runCli(
        String[] args,
        PasswordReader passwordReader,
        Path workingDirectory,
        PrintStream standardOut,
        PrintStream standardError
    ) {
        char[] password = null;
        PrivateKey privateKey = null;
        try {
            require(args != null && args.length == 6, "ARGUMENTS_INVALID");
            require("--request".equals(args[0]), "ARGUMENTS_INVALID");
            require("--keystore".equals(args[2]), "ARGUMENTS_INVALID");
            require("--output-dir".equals(args[4]), "ARGUMENTS_INVALID");
            require(passwordReader != null, "CONSOLE_REQUIRED");

            Path request = absolutePath(args[1], "REQUEST_NOT_ABSOLUTE").toRealPath();
            Path keyStorePath = absolutePath(args[3], "KEYSTORE_NOT_ABSOLUTE").toRealPath();
            Path output = absolutePath(args[5], "OUTPUT_NOT_ABSOLUTE");
            Path repository = findRepositoryRoot(workingDirectory);
            require(!keyStorePath.startsWith(repository), "KEYSTORE_INSIDE_REPOSITORY");
            require(!output.normalize().startsWith(repository), "OUTPUT_INSIDE_REPOSITORY");

            password = passwordReader.readPassword();
            require(password != null && password.length > 0, "PASSWORD_UNAVAILABLE");
            LoadedKey loaded = loadKeyStore(keyStorePath, password);
            privateKey = loaded.privateKey();
            Request parsed = parseRequest(request);
            GeneratedArtifacts artifacts = generate(parsed, privateKey, loaded.publicKey());
            writeOwnerLocal(artifacts, output, repository);
            standardOut.println("QUALIFICATION_GENERATOR_V1=PASS");
            standardOut.println("key_id_sha256=" + artifacts.keyId());
            standardOut.println("payload_sha256=" + artifacts.payloadSha256());
            standardOut.println("manifest_sha256=" + artifacts.manifestSha256());
            return 0;
        } catch (GeneratorException error) {
            standardError.println("QUALIFICATION_GENERATOR_ERROR=" + error.code());
            return 2;
        } catch (Exception error) {
            standardError.println("QUALIFICATION_GENERATOR_ERROR=INTERNAL_FAILURE");
            return 2;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            destroy(privateKey);
        }
    }

    public static void main(String[] args) {
        Console console = System.console();
        PasswordReader reader = console == null
            ? null
            : () -> console.readPassword("Mimir qualification keystore password: ");
        int result = runCli(
            args,
            reader,
            Path.of("").toAbsolutePath().normalize(),
            System.out,
            System.err
        );
        if (result != 0) System.exit(result);
    }

    private static LoadedKey loadKeyStore(Path keyStorePath, char[] password) {
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(keyStorePath, StandardOpenOption.READ)) {
                store.load(input, password);
            }
            Enumeration<String> aliases = store.aliases();
            String selected = null;
            int keyEntries = 0;
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (store.isKeyEntry(alias)) {
                    selected = alias;
                    keyEntries++;
                }
            }
            require(keyEntries == 1 && selected != null, "KEYSTORE_ENTRY_COUNT_INVALID");
            Key key = store.getKey(selected, password);
            Certificate certificate = store.getCertificate(selected);
            require(key instanceof PrivateKey && certificate != null, "KEYSTORE_ENTRY_INVALID");
            return new LoadedKey((PrivateKey) key, certificate.getPublicKey());
        } catch (GeneratorException error) {
            throw error;
        } catch (Exception error) {
            throw new GeneratorException("KEYSTORE_READ_FAILED");
        }
    }

    private static byte[] encodePayload(Request request, String keyId) {
        try {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(sink)) {
                out.writeInt(PAYLOAD_MAGIC);
                out.writeInt(WIRE_VERSION);
                out.writeInt(request.manifestSchema());
                writeUtf8(out, keyId);
                out.writeLong(request.sequence());
                writeUtf8(out, request.hardwareFingerprint());
                writeUtf8(out, request.deviceProfile());
                out.writeInt(
                    request.manifestSchema() == LEGACY_MANIFEST_SCHEMA
                        ? LEGACY_SERVICE_PLAN_SCHEMA
                        : request.manifestSchema() == MANIFEST_SCHEMA_V2
                            ? SERVICE_PLAN_SCHEMA_V2
                            : SERVICE_PLAN_SCHEMA
                );
                out.writeInt(request.profiles().size());
                for (ProfilePlan profile : request.profiles()) {
                    writeUtf8(out, profile.profileId());
                    out.writeInt(profile.cpuMaxKhz().size());
                    for (Map.Entry<Integer, Integer> cpu : profile.cpuMaxKhz().entrySet()) {
                        out.writeInt(cpu.getKey());
                        out.writeInt(cpu.getValue());
                    }
                    writeNullableInt(out, profile.gpuMaxPowerLevel());
                    writeNullableInt(out, profile.fanMode());
                    writeUtf8(out, profile.planSha256());
                    writeUtf8(out, profile.evidenceIndexSha256());
                    writeUtf8(out, profile.guardianRollbackEvidenceSha256());
                    // Fixed at the v2 threshold, not "current" -- see MANIFEST_SCHEMA_V2. A
                    // schema-v2 payload's manualFanOverride bytes must never change shape just
                    // because "current" moved on to v3.
                    if (request.manifestSchema() >= MANIFEST_SCHEMA_V2) {
                        boolean hasManual = profile.manualFanMinimumDutyPercent() != null;
                        out.writeBoolean(hasManual);
                        if (hasManual) {
                            out.writeInt(FAN_MANUAL);
                            out.writeInt(profile.manualFanMinimumDutyPercent());
                            writeUtf8(out, profile.manualFanPlanSha256());
                            writeUtf8(out, profile.manualFanEvidenceIndexSha256());
                            writeUtf8(out, profile.manualFanGuardianRollbackEvidenceSha256());
                        }
                    }
                    // WAVE2 Stage B / schema v3: appended after the manualFanOverride block so a
                    // schema-v2-shaped payload's bytes are unaffected -- mirrors the Kotlin codec's
                    // own field order exactly (MANIFEST_SCHEMA_V2 gate, then MANIFEST_SCHEMA gate).
                    if (request.manifestSchema() >= MANIFEST_SCHEMA) {
                        out.writeInt(profile.derateRungs().size());
                        for (RungPlan rung : profile.derateRungs()) {
                            out.writeInt(rung.cpuMaxKhz().size());
                            for (Map.Entry<Integer, Integer> cpu : rung.cpuMaxKhz().entrySet()) {
                                out.writeInt(cpu.getKey());
                                out.writeInt(cpu.getValue());
                            }
                            writeNullableInt(out, rung.gpuMaxPowerLevel());
                            writeNullableInt(out, profile.fanMode());
                            writeUtf8(out, rung.planSha256(profile.fanMode()));
                            writeUtf8(out, rung.evidenceIndexSha256());
                            writeUtf8(out, rung.guardianRollbackEvidenceSha256());
                        }
                    }
                }
            }
            byte[] payload = sink.toByteArray();
            require(payload.length > 0 && payload.length <= MAX_PAYLOAD_BYTES, "PAYLOAD_SIZE_INVALID");
            return payload;
        } catch (IOException error) {
            throw new GeneratorException("PAYLOAD_ENCODING_FAILED");
        }
    }

    private static byte[] encodeEnvelope(byte[] payload, byte[] signature) {
        try {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(sink)) {
                out.writeInt(SIGNED_MAGIC);
                out.writeInt(WIRE_VERSION);
                out.writeInt(payload.length);
                out.write(payload);
                out.writeInt(signature.length);
                out.write(signature);
            }
            byte[] signed = sink.toByteArray();
            require(signed.length > 0 && signed.length <= MAX_SIGNED_BYTES, "ENVELOPE_SIZE_INVALID");
            return signed;
        } catch (IOException error) {
            throw new GeneratorException("ENVELOPE_ENCODING_FAILED");
        }
    }

    private static ParsedEnvelope parseEnvelope(byte[] bytes) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            require(bytes.length > 0 && bytes.length <= MAX_SIGNED_BYTES, "ENVELOPE_SIZE_INVALID");
            require(input.readInt() == SIGNED_MAGIC, "ENVELOPE_MAGIC_INVALID");
            require(input.readInt() == WIRE_VERSION, "ENVELOPE_VERSION_INVALID");
            int payloadCount = input.readInt();
            require(payloadCount > 0 && payloadCount <= MAX_PAYLOAD_BYTES, "PAYLOAD_SIZE_INVALID");
            byte[] payload = input.readNBytes(payloadCount);
            require(payload.length == payloadCount, "ENVELOPE_TRUNCATED");
            int signatureCount = input.readInt();
            require(signatureCount == ED25519_SIGNATURE_BYTES, "SIGNATURE_LENGTH_INVALID");
            byte[] signature = input.readNBytes(signatureCount);
            require(signature.length == signatureCount && input.read() == -1, "ENVELOPE_TRAILING_CONTENT");
            require(Arrays.equals(encodeEnvelope(payload, signature), bytes), "ENVELOPE_NONCANONICAL");
            return new ParsedEnvelope(payload, signature);
        } catch (GeneratorException error) {
            throw error;
        } catch (IOException error) {
            throw new GeneratorException("ENVELOPE_PARSE_FAILED");
        }
    }

    private static void verifyPublishedFiles(Path directory, GeneratedArtifacts artifacts) throws IOException {
        byte[] signed = Files.readAllBytes(directory.resolve("qualification-manifest.mqms"));
        ParsedEnvelope parsed = parseEnvelope(signed);
        PublicKey publicKey = decodeEd25519(artifacts.publicKeySpki());
        require(verify(parsed.payload(), parsed.signature(), publicKey), "PUBLISHED_SIGNATURE_INVALID");
        require(Arrays.equals(signed, artifacts.signedManifest()), "PUBLISHED_MANIFEST_MISMATCH");
        byte[] manifestBase64 = Files.readAllBytes(directory.resolve("qualification-manifest.base64"));
        byte[] anchorBase64 = Files.readAllBytes(directory.resolve("qualification-trust-anchor.base64"));
        require(indexOf(manifestBase64, (byte) '\n') < 0 && indexOf(anchorBase64, (byte) '\n') < 0, "BASE64_NEWLINE_INVALID");
        require(
            Arrays.equals(Base64.getDecoder().decode(manifestBase64), artifacts.signedManifest()),
            "PUBLISHED_BASE64_MISMATCH"
        );
        require(
            Arrays.equals(Base64.getDecoder().decode(anchorBase64), artifacts.publicKeySpki()),
            "PUBLISHED_ANCHOR_MISMATCH"
        );
        try (var entries = Files.list(directory)) {
            List<String> names = entries.map(path -> path.getFileName().toString()).sorted().toList();
            List<String> complete = List.of(
                "qualification-manifest.base64",
                "qualification-manifest.mqms",
                "qualification-trust-anchor.base64"
            );
            List<String> inProgress = List.of(
                ".incomplete",
                "qualification-manifest.base64",
                "qualification-manifest.mqms",
                "qualification-trust-anchor.base64"
            );
            require(names.equals(complete) || names.equals(inProgress), "PUBLISHED_FILE_SET_INVALID");
        }
    }

    private static void validateEd25519(PrivateKey privateKey, PublicKey publicKey) {
        require(privateKey instanceof EdECPrivateKey, "PRIVATE_KEY_NOT_ED25519");
        require(publicKey instanceof EdECPublicKey, "PUBLIC_KEY_NOT_ED25519");
        EdECPrivateKey privateEd = (EdECPrivateKey) privateKey;
        EdECPublicKey publicEd = (EdECPublicKey) publicKey;
        require("Ed25519".equals(privateEd.getParams().getName()), "PRIVATE_KEY_NOT_ED25519");
        require("Ed25519".equals(publicEd.getParams().getName()), "PUBLIC_KEY_NOT_ED25519");
        require(publicKey.getEncoded() != null && publicKey.getEncoded().length == ED25519_SPKI_BYTES, "PUBLIC_KEY_ENCODING_INVALID");
    }

    private static PublicKey decodeEd25519(byte[] spki) {
        try {
            return java.security.KeyFactory.getInstance("Ed25519")
                .generatePublic(new java.security.spec.X509EncodedKeySpec(spki));
        } catch (Exception error) {
            throw new GeneratorException("PUBLIC_KEY_DECODING_FAILED");
        }
    }

    private static boolean verify(byte[] payload, byte[] signature, PublicKey publicKey) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(payload);
            return verifier.verify(signature);
        } catch (Exception error) {
            return false;
        }
    }

    private static String planIdentityHash(Map<Integer, Integer> cpu, Integer gpu, Integer fan) {
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<Integer, Integer> entry : cpu.entrySet()) {
            canonical.append("|cpu").append(entry.getKey()).append('=').append(entry.getValue());
        }
        canonical.append("|gpu=").append(gpu == null ? -1 : gpu);
        canonical.append("|fan=").append(fan == null ? -1 : fan);
        return sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder encoded = new StringBuilder(64);
            for (byte item : digest) encoded.append(String.format("%02x", Byte.toUnsignedInt(item)));
            return encoded.toString();
        } catch (Exception error) {
            throw new GeneratorException("SHA256_UNAVAILABLE");
        }
    }

    private static void writeUtf8(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        require(bytes.length > 0 && bytes.length <= 256, "STRING_SIZE_INVALID");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void writeNullableInt(DataOutputStream output, Integer value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) output.writeInt(value);
    }

    private static String decodeUtf8(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value))
                .toString();
        } catch (CharacterCodingException error) {
            throw new GeneratorException("REQUEST_UTF8_INVALID");
        }
    }

    private static long parsePositiveLong(String value, String code) {
        require(value != null && value.matches("[1-9][0-9]*"), code);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw new GeneratorException(code);
        }
    }

    private static int parsePositiveInt(String value, String code) {
        long parsed = parsePositiveLong(value, code);
        require(parsed <= Integer.MAX_VALUE, code);
        return (int) parsed;
    }

    private static int parseNonNegativeInt(String value, String code) {
        require(value != null && value.matches("0|[1-9][0-9]*"), code);
        try {
            long parsed = Long.parseLong(value);
            require(parsed <= Integer.MAX_VALUE, code);
            return (int) parsed;
        } catch (NumberFormatException error) {
            throw new GeneratorException(code);
        }
    }

    private static Integer parseNullableNonNegativeInt(String value, String code) {
        return "null".equals(value) ? null : parseNonNegativeInt(value, code);
    }

    private static void requireDigest(String value, String code) {
        require(value != null && value.matches("[0-9a-f]{64}"), code);
    }

    private static boolean isZeroDigest(String value) {
        return value.chars().allMatch(character -> character == '0');
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new GeneratorException(code);
    }

    private static Path absolutePath(String value, String code) {
        try {
            Path path = Path.of(value);
            require(path.isAbsolute(), code);
            return path;
        } catch (GeneratorException error) {
            throw error;
        } catch (Exception error) {
            throw new GeneratorException(code);
        }
    }

    private static Path realDirectory(Path directory, String code) {
        try {
            Path real = directory.toRealPath();
            require(Files.isDirectory(real), code);
            return real;
        } catch (GeneratorException error) {
            throw error;
        } catch (Exception error) {
            throw new GeneratorException(code);
        }
    }

    private static Path findRepositoryRoot(Path workingDirectory) {
        try {
            Path cursor = workingDirectory.toRealPath();
            while (cursor != null) {
                if (Files.exists(cursor.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) return cursor;
                cursor = cursor.getParent();
            }
            throw new GeneratorException("REPOSITORY_ROOT_UNAVAILABLE");
        } catch (GeneratorException error) {
            throw error;
        } catch (Exception error) {
            throw new GeneratorException("REPOSITORY_ROOT_UNAVAILABLE");
        }
    }

    private static void writeAtomic(Path directory, String name, byte[] value) throws IOException {
        Path temporary = Files.createTempFile(directory, "." + name + ".", ".tmp");
        try {
            setPermissions(temporary, FILE_PERMISSIONS);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(value);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            moveAtomic(temporary, directory.resolve(name));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveAtomic(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            throw new GeneratorException("ATOMIC_MOVE_UNAVAILABLE");
        }
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (Exception error) {
            throw new GeneratorException("POSIX_PERMISSIONS_UNAVAILABLE");
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A failed cleanup is still a terminal generator failure; no artifact is accepted.
                }
            });
        } catch (IOException ignored) {
            // See above. The caller already receives a terminal failure.
        }
    }

    private static void destroy(PrivateKey privateKey) {
        if (privateKey instanceof Destroyable) {
            try {
                ((Destroyable) privateKey).destroy();
            } catch (Exception ignored) {
                // JCA providers may expose an immutable key handle. No private bytes are extracted.
            }
        }
    }

    private static int indexOf(byte[] values, byte target) {
        for (int index = 0; index < values.length; index++) if (values[index] == target) return index;
        return -1;
    }

    private record LoadedKey(PrivateKey privateKey, PublicKey publicKey) {}
    private record ParsedEnvelope(byte[] payload, byte[] signature) {}

    private static final class Cursor {
        private final String[] lines;
        private int index;

        Cursor(String[] lines) {
            this.lines = lines;
        }

        boolean finished() { return index == lines.length; }
        boolean peek(String value) { return index < lines.length && value.equals(lines[index]); }
        boolean peekPrefix(String value) { return index < lines.length && lines[index].startsWith(value); }

        String next() {
            require(index < lines.length, "REQUEST_TRUNCATED");
            String value = lines[index++];
            require(!value.isEmpty(), "REQUEST_EMPTY_LINE");
            return value;
        }

        void expect(String value) {
            require(value.equals(next()), "REQUEST_ORDER_INVALID");
        }

        String value(String key) {
            String line = next();
            String prefix = key + "=";
            require(line.startsWith(prefix) && line.indexOf('=') == prefix.length() - 1, "REQUEST_FIELD_INVALID");
            String value = line.substring(prefix.length());
            require(!value.isEmpty() && value.indexOf('=') < 0, "REQUEST_FIELD_INVALID");
            return value;
        }
    }
}
