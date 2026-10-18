#!/usr/bin/env python3
"""Tests for the family-catalog host tools. Run from this directory:

    python -m unittest

Covers draft.py against corpus/ (each *.capture.txt must draft to the *.json beside it), the
planner arithmetic draft.py replicates (cross-checked against numbers pinned in the Kotlin
tests), the read-only guarantee of capture.sh, and the keygen -> sign -> verify round trip.
The signing tests need bash and OpenSSL 3 and skip themselves without them. They write only
into a temp dir that is removed afterwards. Nothing here touches a device.
"""
import base64
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import draft  # noqa: E402

CORPUS = os.path.join(HERE, "corpus")


def read(path):
    with open(path, encoding="utf-8", newline="") as fh:
        return fh.read()


def synth_capture(policies, gpu_hz=(), soc="QCS8550", platform="kalama"):
    """policies: {id: (related_cpus, available kHz list, cpuinfo_max or None)}."""
    lines = ["### getprop", f"ro.soc.model={soc}", f"ro.board.platform={platform}", "### cpufreq"]
    for pid, (cpus, avail, cmax) in policies.items():
        lines.append(f"policy{pid}.related_cpus={' '.join(map(str, cpus))}")
        lines.append(f"policy{pid}.scaling_available_frequencies={' '.join(map(str, avail))}")
        lines.append(f"policy{pid}.cpuinfo_max_freq={cmax if cmax else '<absent>'}")
    lines += ["### kgsl", f"gpu_available_frequencies={' '.join(str(g) for g in gpu_hz) if gpu_hz else '<absent>'}", "### end"]
    return "\n".join(lines) + "\n"


class CorpusTest(unittest.TestCase):
    def test_every_capture_drafts_to_its_expected_entry(self):
        captures = sorted(glob.glob(os.path.join(CORPUS, "*.capture.txt")))
        self.assertGreaterEqual(len(captures), 3)
        for path in captures:
            name = os.path.basename(path)[: -len(".capture.txt")]
            expected_path = os.path.join(CORPUS, name + ".json")
            with self.subTest(name):
                self.assertTrue(os.path.isfile(expected_path), f"missing expected entry {name}.json")
                entry, _notes, _owed, _snapped = draft.draft(read(path))
                self.assertEqual(json.loads(read(expected_path)), entry)
                # the committed file is exactly what the tool writes: LF, one trailing newline
                self.assertEqual(draft.dumps(entry) + "\n", read(expected_path))

    def test_entries_meet_the_id_and_label_rules_of_d1(self):
        for path in glob.glob(os.path.join(CORPUS, "*.json")):
            entry = json.loads(read(path))
            with self.subTest(os.path.basename(path)):
                self.assertRegex(entry["id"], r"^[a-z0-9-]{3,40}$")
                # the Tweaker's FamilyCatalogLimits.VENDOR_PREFIXES: an id starts with its SoC vendor and names a chip
                self.assertRegex(
                    entry["id"],
                    r"^(qualcomm|mediatek|unisoc|samsung|google|rockchip|allwinner|amlogic|nvidia)-[a-z0-9-]+$",
                )
                self.assertRegex(entry["label"], r"^[\x20-\x7e]{1,64}$")
                self.assertIn(entry["treatment"], ("mimir-certified-reference", "general-live-opp"))
                self.assertTrue(all(a == a.upper() for a in entry["match"]["soc"]))

    def test_the_shape_is_the_one_the_bundled_catalog_uses(self):
        # tweaker-families.json (lane fam-catalog) spells the transposed families this way; the
        # CPU anchor and the GPU block are the same data for every family that transposes.
        thor = json.loads(read(os.path.join(CORPUS, "thor-qcs8550.json")))
        self.assertEqual("anchor", thor["cpu"]["kind"])
        self.assertEqual(
            {"little": [1996800, 3532800], "prime": [3072000, 4320000]},
            thor["cpu"]["profiles"]["performance"],
        )
        self.assertEqual(
            {"kind": "transposed", "anchorLevels": 14, "capped": {"quiet": 5}, "uncapped": ["balanced", "performance"]},
            thor["gpu"],
        )

    def test_vendor_mode_only_where_the_key_exists(self):
        by_name = {n: json.loads(read(os.path.join(CORPUS, n + ".json"))) for n in ("odin3-cq8725s", "thor-qcs8550", "rp5-sm8250")}
        self.assertEqual({"quiet": 0, "balanced": 1, "performance": 2}, by_name["odin3-cq8725s"]["vendorMode"])
        self.assertEqual({"quiet": 0, "balanced": 1, "performance": 2}, by_name["thor-qcs8550"]["vendorMode"])
        self.assertNotIn("vendorMode", by_name["rp5-sm8250"])

    def test_transports_follow_node_presence(self):
        thor = json.loads(read(os.path.join(CORPUS, "thor-qcs8550.json")))
        self.assertEqual(
            [
                "cpu-scaling-max", "cpu-msm-performance-qos", "gpu-kgsl-max-pwrlevel",
                "gpu-devfreq-max", "vendor-performance-mode", "fan-pwm",
            ],
            thor["transports"],
        )
        odin = json.loads(read(os.path.join(CORPUS, "odin3-cq8725s.json")))
        self.assertEqual(
            ["cpu-scaling-max", "cpu-msm-performance-qos", "gpu-kgsl-max-pwrlevel", "vendor-performance-mode"],
            odin["transports"],
        )
        self.assertNotIn("transports", json.loads(read(os.path.join(CORPUS, "rp5-sm8250.json"))))


class ArithmeticTest(unittest.TestCase):
    """draft.py replicates TuningPlanner's anchor arithmetic; pin it to numbers the Kotlin tests pin."""

    @staticmethod
    def policies(text):
        return draft.policies_of(draft.Capture(text))

    def test_round_half_up_is_not_pythons_banker_rounding(self):
        self.assertEqual(3, draft.round_half_up(2.5))
        self.assertEqual(2, round(2.5))  # the trap
        self.assertEqual(4, draft.round_half_up(3.5))

    def test_the_anchor_family_reproduces_its_own_signed_vector(self):
        # Sequence 9/10 signed Odin 3 row: MIMIR_CERTIFIED_FAMILY_CPU_TARGETS_KHZ.
        pol = self.policies(read(os.path.join(CORPUS, "odin3-cq8725s.capture.txt")))
        self.assertEqual({0: 1785600, 6: 2246400}, draft.anchored_cpu_targets("quiet", pol))
        self.assertEqual({0: 1996800, 6: 2438400}, draft.anchored_cpu_targets("balanced", pol))
        self.assertEqual({0: 1996800, 6: 3072000}, draft.anchored_cpu_targets("performance", pol))

    def test_sm8250_matches_the_kotlin_test_that_pins_the_transposition(self):
        # TuningPlannerTest "SM8250 (RP5) CPU targets are the Odin-anchored transposition ...":
        # the sparse policy4/7 stand-ins are fine here because this is arithmetic, not a fixture.
        pol = self.policies(
            synth_capture(
                {
                    0: ([0, 1, 2, 3], [300000, 403200, 518400, 614400, 691200, 787200, 883200, 979200, 1075200, 1171200, 1248000, 1344000, 1420800, 1516800, 1612800, 1708800, 1804800], None),
                    4: ([4, 5, 6], [710400, 1286400, 1862400, 2342400, 2419200], None),
                    7: ([7], [844800, 1516800, 2169600, 2745600, 2841600], None),
                },
                soc="SM8250",
                platform="kona",
            )
        )
        self.assertEqual({0: 883200, 4: 710400, 7: 844800}, draft.anchored_cpu_targets("quiet", pol))
        self.assertEqual({0: 979200, 4: 1286400, 7: 1516800}, draft.anchored_cpu_targets("balanced", pol))
        self.assertEqual({0: 979200, 4: 1286400, 7: 1516800}, draft.anchored_cpu_targets("performance", pol))

    def test_the_thor_balanced_vector_equals_the_clamp_that_was_standing_on_it(self):
        # The live capture was taken under a Tweaker Balanced clamp; its msm_performance map is
        # exactly what the anchor arithmetic derives from the same device's tables.
        cap = draft.Capture(read(os.path.join(CORPUS, "thor-qcs8550.capture.txt")))
        targets = draft.anchored_cpu_targets("balanced", draft.policies_of(cap))
        clamp = {int(c): int(v) for c, v in (t.split(":") for t in cap.value("msm_performance", "cpu_max_freq").split())}
        self.assertEqual({0: clamp[0], 3: clamp[3], 7: clamp[7]}, targets)

    def test_gpu_levels(self):
        # TuningPlannerTest: Odin 14 levels -> 5; Nova 9 levels -> 3; RP5 8 levels -> 3.
        self.assertEqual(5, draft.anchored_gpu_level("quiet", 14))
        self.assertEqual(3, draft.anchored_gpu_level("quiet", 9))
        self.assertEqual(3, draft.anchored_gpu_level("quiet", 8))
        for n in (8, 9, 14):
            self.assertEqual(0, draft.anchored_gpu_level("balanced", n))
            self.assertEqual(0, draft.anchored_gpu_level("performance", n))
        self.assertIsNone(draft.anchored_gpu_level("quiet", 1))


class DraftBehaviourTest(unittest.TestCase):
    def test_incomplete_capture_is_refused(self):
        text = read(os.path.join(CORPUS, "thor-qcs8550.capture.txt")).replace("### end", "")
        with self.assertRaises(draft.CaptureError):
            draft.draft(text)

    def test_capture_without_a_soc_is_refused(self):
        with self.assertRaises(draft.CaptureError):
            draft.draft("### getprop\nro.soc.model=<absent>\nro.vendor.qti.soc_model=<absent>\n### end\n")

    def test_crlf_capture_drafts_the_same_entry(self):
        text = read(os.path.join(CORPUS, "thor-qcs8550.capture.txt"))
        lf = draft.draft(text)[0]
        crlf = draft.draft(text.replace("\n", "\r\n"))[0]
        self.assertEqual(lf, crlf)

    def test_thor_owes_the_daemon_reads_and_the_reserved_alias_warning(self):
        _entry, notes, owed, snapped = draft.draft(read(os.path.join(CORPUS, "thor-qcs8550.capture.txt")))
        owed_text = "\n".join(owed)
        self.assertIn("daemon read owed", owed_text)
        for key in ("policy0.scaling_min_freq", "policy3.cpuinfo_min_freq", "policy7.scaling_min_freq"):
            self.assertIn(key, owed_text)
        self.assertIn("cpugpumode", owed_text)  # no cadence lines in the buffer
        self.assertTrue(any("qualcomm-8gen2" in n for n in notes))
        self.assertTrue(any("clamp" in n for n in notes))
        self.assertTrue(any(line.startswith("balanced") and "policy7 1593600" in line for line in snapped))

    def test_rp5_marks_the_missing_policy_tables_as_owed_and_derives_no_cpu_vector(self):
        _entry, _notes, owed, snapped = draft.draft(read(os.path.join(CORPUS, "rp5-sm8250.capture.txt")))
        owed_text = "\n".join(owed)
        self.assertIn("policy4.scaling_available_frequencies", owed_text)
        self.assertIn("policy7.scaling_available_frequencies", owed_text)
        self.assertTrue(all("not derivable" in line for line in snapped if line.startswith(("quiet", "balanced", "performance"))))

    def test_catalog_overlap_uses_trailing_star_aliases(self):
        with tempfile.TemporaryDirectory() as tmp:
            catalog = os.path.join(tmp, "families.json")
            with open(catalog, "w", encoding="utf-8", newline="") as fh:
                json.dump({"schema": 1, "revision": 1, "families": [{"id": "qualcomm-newchip", "match": {"soc": ["QCS85*"]}}]}, fh)
            _e, notes, owed, _s = draft.draft(read(os.path.join(CORPUS, "thor-qcs8550.capture.txt")), catalog)
        self.assertTrue(any("qualcomm-newchip" in n for n in notes))
        self.assertFalse(any("only checked against the built-in" in o for o in owed))

    def test_cli_writes_lf_json_to_stdout_and_notes_to_stderr(self):
        proc = subprocess.run(
            [sys.executable, os.path.join(HERE, "draft.py"), os.path.join(CORPUS, "thor-qcs8550.capture.txt")],
            capture_output=True,
        )
        self.assertEqual(0, proc.returncode)
        self.assertNotIn(b"\r", proc.stdout)
        self.assertEqual("qualcomm-qcs8550", json.loads(proc.stdout)["id"])
        self.assertIn(b"owed", proc.stderr)

    def test_cli_exit_code_2_on_an_unusable_capture(self):
        with tempfile.TemporaryDirectory() as tmp:
            bad = os.path.join(tmp, "bad.txt")
            with open(bad, "w", newline="") as fh:
                fh.write("### getprop\n")
            proc = subprocess.run([sys.executable, os.path.join(HERE, "draft.py"), bad], capture_output=True)
        self.assertEqual(2, proc.returncode)
        self.assertEqual(b"", proc.stdout)


class CaptureScriptTest(unittest.TestCase):
    """capture.sh is read-only by construction; these pin it so a write cannot slip in."""

    @classmethod
    def setUpClass(cls):
        text = read(os.path.join(HERE, "capture.sh"))
        cls.script = text
        m = re.search(r"<<'DEVICE_SH'\n(.*?)\nDEVICE_SH\n", text, re.S)
        assert m, "on-device block not found"
        cls.device = m.group(1)

    def test_on_device_block_has_no_write_or_state_changing_verb(self):
        forbidden = r"\b(setprop|reboot|install|uninstall|rm|mv|cp|chmod|chown|tee|dd|mkdir|touch|ln|kill|killall|input|monkey|am|pm|svc|wm|cmd|start|stop|su|sh|eval)\b"
        for n, line in enumerate(self.device.splitlines(), 1):
            code = re.sub(r'"[^"]*"', '""', line)  # quoted text may contain <, > and words
            code = code.split("#", 1)[0]
            code = re.sub(r"\b[a-z0-9_]+(\.[a-z0-9_]+)+\b", "", code)  # property names such as init.svc.pservice
            with self.subTest(line=n):
                self.assertIsNone(re.search(forbidden, code), line)
                self.assertNotRegex(code, r"\bsettings\s+(put|delete|reset)\b")
                self.assertNotIn(">", code.replace("2>&1", ""), f"redirect in: {line}")

    def test_settings_and_logcat_are_filtered_on_the_device(self):
        self.assertIn("settings list system", self.device)
        self.assertRegex(self.device, r"grep -E 'performance_mode\|fan'")
        self.assertRegex(self.device, r"logcat -d .*\| grep cpugpumode \| tail -n 3")

    def test_host_side_only_asks_adb_for_state_and_one_shell(self):
        uses = re.findall(r"\badb\s+-s\s+\"\$SERIAL\"\s+(\S+)", self.script)
        self.assertEqual({"get-state", "shell"}, set(uses))
        code = [ln for ln in self.script.splitlines() if not ln.lstrip().startswith(("#", "echo"))]
        self.assertNotRegex("\n".join(code), r"\badb\s+(?!-s)\w")

    def test_no_serial_or_account_is_captured(self):
        self.assertNotIn("ro.serialno", self.device)
        self.assertNotIn("account", self.device.lower())
        self.assertNotIn("wifi", self.device.lower())


def find_bash():
    if os.name == "nt":
        for cand in (r"C:\Program Files\Git\bin\bash.exe", r"C:\Program Files (x86)\Git\bin\bash.exe"):
            if os.path.isfile(cand):
                return cand
        git = shutil.which("git")
        if git:
            cand = os.path.join(os.path.dirname(os.path.dirname(git)), "bin", "bash.exe")
            if os.path.isfile(cand):
                return cand
        return None  # never trust System32\bash.exe, which is WSL
    return shutil.which("bash")


class SigningRoundTripTest(unittest.TestCase):
    """keygen -> sign -> verify with throwaway keys in a temp dir (never the repo), then removed.

    Process starts are slow on Windows, so the keys and one signature are made once for the class.
    """

    @classmethod
    def run_script(cls, name, args=(), stdin=""):
        script = os.path.join(HERE, name).replace("\\", "/")
        return subprocess.run(
            [cls.bash, script, *[str(a).replace("\\", "/") for a in args]],
            input=stdin, capture_output=True, text=True,
        )

    @classmethod
    def setUpClass(cls):
        cls.bash = find_bash()
        if not cls.bash:
            raise unittest.SkipTest("no bash available")
        probe = subprocess.run([cls.bash, "-c", "openssl version"], capture_output=True, text=True)
        if probe.returncode != 0 or not re.match(r"OpenSSL [3-9]\.", probe.stdout):
            raise unittest.SkipTest(f"OpenSSL 3 is required: {probe.stdout.strip() or probe.stderr.strip()}")

        cls.tmp = tempfile.mkdtemp(prefix="famtools-")
        cls.addClassCleanup(shutil.rmtree, cls.tmp, True)  # the throwaway keys go with it
        cls.key = os.path.join(cls.tmp, "signing-key.pem")
        cls.keygen = cls.run_script("keygen.sh", stdin=cls.key + "\n")
        if "inside a git work tree" in cls.keygen.stderr:
            raise unittest.SkipTest("the temp directory is inside a git work tree on this machine")
        assert cls.keygen.returncode == 0, cls.keygen.stderr
        cls.pub = cls.keygen.stdout.strip()

        cls.key2 = os.path.join(cls.tmp, "other-key.pem")
        cls.pub2 = cls.run_script("keygen.sh", stdin=cls.key2 + "\n").stdout.strip()

        cls.original = b'{"schema":1,"revision":7,"families":[]}\n'
        cls.catalog = os.path.join(cls.tmp, "tweaker-families.json")
        with open(cls.catalog, "wb") as fh:
            fh.write(cls.original)
        cls.signed = cls.run_script("sign.sh", [cls.catalog], stdin=cls.key + "\n")
        with open(cls.catalog + ".sig", "rb") as fh:
            cls.sig = fh.read()

    def variant(self, name, body=None, sig=None):
        """A copy of the catalog (optionally altered) next to its signature (optionally altered)."""
        path = os.path.join(self.tmp, name)
        with open(path, "wb") as fh:
            fh.write(self.original if body is None else body)
        with open(path + ".sig", "wb") as fh:
            fh.write(self.sig if sig is None else sig)
        return path

    def test_keygen_prints_only_the_raw_32_byte_public_key(self):
        self.assertEqual(44, len(self.pub))
        self.assertEqual(32, len(base64.b64decode(self.pub, validate=True)))
        self.assertEqual(1, len(self.keygen.stdout.strip().splitlines()))
        with open(self.key, encoding="ascii") as fh:
            pem = fh.read()
        self.assertTrue(pem.startswith("-----BEGIN PRIVATE KEY-----"))
        body = "".join(pem.splitlines()[1:-1])
        self.assertNotIn(body, self.keygen.stdout + self.keygen.stderr)
        self.assertNotIn("BEGIN PRIVATE KEY", self.keygen.stdout + self.keygen.stderr)

    def test_keygen_does_not_overwrite_an_existing_key(self):
        again = self.run_script("keygen.sh", stdin=self.key + "\n")
        self.assertNotEqual(0, again.returncode)
        self.assertEqual(b"", again.stdout.encode())

    def test_the_signature_is_one_line_of_base64_of_64_bytes_with_no_newline(self):
        self.assertEqual(0, self.signed.returncode, self.signed.stderr)
        self.assertEqual(88, len(self.sig))
        self.assertEqual(64, len(base64.b64decode(self.sig, validate=True)))
        self.assertNotIn(b"\n", self.sig)
        self.assertIn(self.pub, self.signed.stderr)  # it reports the PUBLIC half it signed with

    def test_verify_accepts_the_untouched_file_and_rejects_a_tampered_byte(self):
        self.assertEqual(0, self.run_script("verify.sh", [self.catalog, self.pub]).returncode)
        changed = self.variant("tampered.json", body=self.original.replace(b'"revision":7', b'"revision":8'))
        self.assertEqual(1, self.run_script("verify.sh", [changed, self.pub]).returncode)

    def test_verify_rejects_a_damaged_or_reformatted_signature(self):
        bad = bytearray(self.sig)
        bad[3] = ord("A") if bad[3] != ord("A") else ord("B")
        self.assertEqual(1, self.run_script("verify.sh", [self.variant("badsig.json", sig=bytes(bad)), self.pub]).returncode)
        # a trailing newline is not the specified format
        self.assertEqual(1, self.run_script("verify.sh", [self.variant("nl.json", sig=self.sig + b"\n"), self.pub]).returncode)

    def test_verify_rejects_the_wrong_or_malformed_public_key(self):
        self.assertEqual(1, self.run_script("verify.sh", [self.catalog, self.pub2]).returncode)
        self.assertEqual(1, self.run_script("verify.sh", [self.catalog, "not-a-key"]).returncode)

    def test_keys_inside_a_git_work_tree_are_refused(self):
        fake_repo = os.path.join(self.tmp, "repo")
        os.makedirs(os.path.join(fake_repo, ".git"))
        refused = self.run_script("keygen.sh", stdin=os.path.join(fake_repo, "k.pem") + "\n")
        self.assertNotEqual(0, refused.returncode)
        self.assertFalse(os.path.exists(os.path.join(fake_repo, "k.pem")))

        inside = os.path.join(fake_repo, "copied.pem")
        shutil.copy(self.key, inside)
        out = os.path.join(self.tmp, "refused.json")
        with open(out, "wb") as fh:
            fh.write(b"{}\n")
        proc = self.run_script("sign.sh", [out], stdin=inside + "\n")
        self.assertNotEqual(0, proc.returncode)
        self.assertFalse(os.path.exists(out + ".sig"))

    def test_a_key_in_a_cloud_synced_folder_is_refused_by_sign_as_well_as_keygen(self):
        synced = os.path.join(self.tmp, "OneDrive", "Documents")
        os.makedirs(synced)
        refused = self.run_script("keygen.sh", stdin=os.path.join(synced, "k.pem") + "\n")
        self.assertNotEqual(0, refused.returncode)
        self.assertFalse(os.path.exists(os.path.join(synced, "k.pem")))

        inside = os.path.join(synced, "copied.pem")
        shutil.copy(self.key, inside)
        out = os.path.join(self.tmp, "refused-synced.json")
        with open(out, "wb") as fh:
            fh.write(b"{}\n")
        proc = self.run_script("sign.sh", [out], stdin=inside + "\n")
        self.assertNotEqual(0, proc.returncode)
        self.assertIn("cloud-synced", proc.stderr)
        self.assertFalse(os.path.exists(out + ".sig"))

    def test_the_key_only_ever_arrives_through_the_prompt(self):
        for name in ("sign.sh", "keygen.sh"):
            text = read(os.path.join(HERE, name))
            with self.subTest(name):
                self.assertIn("prompt_path", text)
                without_prompt_var = re.sub(r"\bKEYPATH\b", "", text)
                self.assertNotRegex(without_prompt_var, r"\$\{?(KEY|PRIVATE|PASS|SIGN)[A-Z_]*")
                self.assertNotRegex(text, r"-passin|-passout|--key\b|--password")


if __name__ == "__main__":
    unittest.main()
