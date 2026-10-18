#!/usr/bin/env python3
"""Hermetic self-tests for tools/mimir_connected_posture_gate.py."""

from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
GATE = TOOLS / "mimir_connected_posture_gate.py"
UI_GATE = TOOLS / "mimir_ui_invariants_gate.py"


def load_gate_module(root: Path) -> None:
    source = GATE.read_text(encoding="utf-8").replace(
        "ROOT = Path(__file__).resolve().parent.parent",
        f"ROOT = Path({str(root)!r})",
    )
    gate_path = root / "tools" / "mimir_connected_posture_gate.py"
    gate_path.parent.mkdir(parents=True, exist_ok=True)
    gate_path.write_text(source, encoding="utf-8")


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(textwrap.dedent(content).lstrip("\n"), encoding="utf-8")


PASSING_EGRESS = '''
    object MimirProviderEgress {
        private val liveOpener = MimirProviderEgressOpener { url, connectTimeoutMs, readTimeoutMs, requestHeaders ->
            val connection = (java.net.URL(url).openConnection() as HttpURLConnection)
            connection.disconnect()
            null
        }
        fun open(
            route: MimirProviderEgressRoute,
            url: String,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
            requestHeaders: Map<String, String> = emptyMap(),
            opener: MimirProviderEgressOpener = liveOpener,
        ): MimirProviderEgressResponse? {
            if (!urlAllowed(route, url)) return null
            val host = parseUrlHost(url) ?: return null
            if (!gateAllowsRequestTo(route, host)) return null
            return opener.open(url, connectTimeoutMs, readTimeoutMs, requestHeaders)
        }
        private fun urlAllowed(route: MimirProviderEgressRoute, url: String): Boolean = true
        private fun parseUrlHost(url: String): String? = java.net.URI(url).host
        private fun gateAllowsRequestTo(route: MimirProviderEgressRoute, host: String): Boolean = when (route) {
            // Submitting a key is the explicit opt-in action. This route is restricted above to one
            // harmless autocomplete URL, so validation can happen before the durable-key gate is opened.
            MimirProviderEgressRoute.STEAMGRIDDB_KEY_VALIDATION ->
                MimirSteamGridDbGate.isAllowedHost(host)
            MimirProviderEgressRoute.STEAMGRIDDB_API,
            MimirProviderEgressRoute.STEAMGRIDDB_MEDIA,
            MimirProviderEgressRoute.STEAM_STORE_API,
            -> MimirSteamGridDbGate.allowsRequestTo(host)
            MimirProviderEgressRoute.SCREENSCRAPER_MEDIA ->
                MimirConnectedArtGate.allowsRequestTo(host)
            MimirProviderEgressRoute.EMULATOR_DEFINITIONS ->
                MimirEmulatorDefinitionsGate.allowsRequestTo(host)
        }
    }
'''

# The grouped arm from PASSING_EGRESS, isolated so route-scoping attack fixtures can replace it
# with a minimal, targeted mutation instead of hand-duplicating the whole object body.
_PASSING_GROUPED_ARM = '''\
            MimirProviderEgressRoute.STEAMGRIDDB_API,
            MimirProviderEgressRoute.STEAMGRIDDB_MEDIA,
            MimirProviderEgressRoute.STEAM_STORE_API,
            -> MimirSteamGridDbGate.allowsRequestTo(host)'''

# The whole gateAllowsRequestTo `when` function, isolated so the unparseable-block fixture can
# swap it wholesale for an equivalent if-chain — changing only the control-flow shape while
# every other line of PASSING_EGRESS (open(), urlAllowed, parseUrlHost, liveOpener) stays green.
_PASSING_GATE_ALLOWS_BLOCK = '''\
        private fun gateAllowsRequestTo(route: MimirProviderEgressRoute, host: String): Boolean = when (route) {
            // Submitting a key is the explicit opt-in action. This route is restricted above to one
            // harmless autocomplete URL, so validation can happen before the durable-key gate is opened.
            MimirProviderEgressRoute.STEAMGRIDDB_KEY_VALIDATION ->
                MimirSteamGridDbGate.isAllowedHost(host)
            MimirProviderEgressRoute.STEAMGRIDDB_API,
            MimirProviderEgressRoute.STEAMGRIDDB_MEDIA,
            MimirProviderEgressRoute.STEAM_STORE_API,
            -> MimirSteamGridDbGate.allowsRequestTo(host)
            MimirProviderEgressRoute.SCREENSCRAPER_MEDIA ->
                MimirConnectedArtGate.allowsRequestTo(host)
            MimirProviderEgressRoute.EMULATOR_DEFINITIONS ->
                MimirEmulatorDefinitionsGate.allowsRequestTo(host)
        }'''

assert _PASSING_GROUPED_ARM in PASSING_EGRESS, "fixture anchor drifted from PASSING_EGRESS"
assert _PASSING_GATE_ALLOWS_BLOCK in PASSING_EGRESS, "fixture anchor drifted from PASSING_EGRESS"

LIFECYCLE_PATH = "core-metadata/src/main/kotlin/com/gruning/mimir/rewrite/core/metadata/online/CancellableBlockingCall.kt"
CATALOG_PATH = "mimir-tweaker/app/src/main/kotlin/com/gruning/mimir/tweaker/FamilyCatalogFetcher.kt"
MUSIC_STATE_PATH = "app/src/main/kotlin/com/gruning/mimir/rewrite/CompanionMusicPlayerState.kt"
MUSIC_CALLER_PATH = "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt"
PASSING_LIFECYCLE = '''
    class ConnectionAbort {
        private var current: HttpURLConnection? = null
        fun attach(connection: HttpURLConnection): Boolean {
            current = connection
            if (aborted.get()) { connection.disconnect(); return false }
            return true
        }
        fun abort() { current?.disconnect() }
    }
    suspend fun <T> runAbortingOnCancel(abort: () -> Unit, block: () -> T): T {
        try { awaitCancellation() } finally { abort() }
        currentCoroutineContext().ensureActive()
        return block()
    }
'''
PASSING_CATALOG = '''
    class FamilyCatalogFetcher {
        private val publicKey = FamilyCatalogSignature.decodePublicKey(publicKeyBase64)
        private val origin = Origin.parse(catalogUrl)
        private fun fetchLocked(): FamilyCatalogFetchOutcome {
            val key = publicKey ?: return FamilyCatalogFetchOutcome.DISABLED
            if (origin == null) return FamilyCatalogFetchOutcome.REFUSED_HOST
            val catalog = get(catalogUrl, null, MAX_CATALOG_BYTES, deadline)
            val signatureFile = get(signatureUrl, null, FamilyCatalogSignature.MAX_SIGNATURE_FILE_BYTES, deadline)
            val signature = FamilyCatalogSignature.decodeSignatureFile(signatureFile.bytes)
                ?: return FamilyCatalogFetchOutcome.BAD_SIGNATURE
            if (!FamilyCatalogSignature.verify(key, catalog.bytes, signature)) {
                return FamilyCatalogFetchOutcome.BAD_SIGNATURE
            }
            if (!sink.promoteVerified(catalog.bytes, signature)) return FamilyCatalogFetchOutcome.DECLINED_BY_SINK
            return FamilyCatalogFetchOutcome.ACCEPTED
        }
        private fun get(url: String, etag: String?, capBytes: Int, deadline: Long): Step {
            val response = transport.get(url, etag, userAgent, capBytes, remainingMillis)
            if (origin?.owns(response.finalUrl) != true) return Step.Stop(FamilyCatalogFetchOutcome.REFUSED_HOST)
            if (response.body.isEmpty() || response.body.size > capBytes) {
                return Step.Stop(FamilyCatalogFetchOutcome.BAD_SIZE)
            }
            return Step.Body(response.body)
        }
        private class Origin {
            fun parse(url: String): Origin? {
                val uri = URI(url)
                if (!"https".equals(uri.scheme, ignoreCase = true)) return null
                if (uri.rawUserInfo != null) return null
                if (uri.port != -1 && uri.port != 443) return null
                if (uri.rawQuery != null || uri.rawFragment != null) return null
                return Origin()
            }
        }
        companion object {
            const val MAX_CATALOG_BYTES = 64 * 1024
            const val DEADLINE_MILLIS = 10_000L
        }
    }
    class HttpsCatalogTransport {
        fun get(url: String, maxBodyBytes: Int, timeoutMillis: Int, deadlineNanos: Long) {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = minOf(timeoutMillis, READ_STALL_LIMIT_MILLIS)
            try { connection.inputStream.use { readCapped(it, maxBodyBytes, deadlineNanos) } }
            finally { connection.disconnect() }
        }
        fun readCapped(input: InputStream, maxBodyBytes: Int, deadlineNanos: Long) {
            val limit = maxBodyBytes + 1
            while (out.size() < limit) {
                if (System.nanoTime() - deadlineNanos > 0L) throw SocketTimeoutException("deadline")
                out.write(input.read())
            }
        }
    }
'''


class ConnectedPostureGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="mimir-posture-gate-"))
        self.addCleanup(lambda: shutil.rmtree(self.tmp, ignore_errors=True))
        (self.tmp / "tools").mkdir(parents=True, exist_ok=True)
        shutil.copy2(UI_GATE, self.tmp / "tools" / "mimir_ui_invariants_gate.py")
        self.evidence = self.tmp / "tools" / "connected-posture"
        self.evidence.mkdir(parents=True)
        (self.evidence / "BINDING.md").write_text(
            "tools/mimir_connected_posture_gate.py binding\n", encoding="utf-8"
        )
        self._seed_minimal_pass_tree()

    def _seed_minimal_pass_tree(self) -> None:
        write(self.tmp / LIFECYCLE_PATH, PASSING_LIFECYCLE)
        write(self.tmp / CATALOG_PATH, PASSING_CATALOG)
        write(
            self.tmp / "core-model/src/main/kotlin/com/gruning/mimir/rewrite/core/model/posture/MimirConnectedArtGate.kt",
            '''
            object MimirConnectedArtGate {
                private val ALLOWED_HOSTS = setOf("screenscraper.fr", "www.screenscraper.fr")
                fun allowsRequestTo(host: String?): Boolean = isOpen && host != null
                fun setOpen(value: Boolean) {}
                val isOpen: Boolean get() = false
            }
            ''',
        )
        write(
            self.tmp / "core-model/src/main/kotlin/com/gruning/mimir/rewrite/core/model/posture/MimirSteamGridDbGate.kt",
            '''
            object MimirSteamGridDbGate {
                private val ALLOWED_HOSTS = setOf("steamgriddb.com", "www.steamgriddb.com")
                fun allowsRequestTo(host: String?): Boolean = isOpen && host != null
                fun setOpen(value: Boolean) {}
                val isOpen: Boolean get() = false
            }
            ''',
        )
        write(
            self.tmp / "core-model/src/main/kotlin/com/gruning/mimir/rewrite/core/model/posture/MimirEmulatorDefinitionsGate.kt",
            '''
            object MimirEmulatorDefinitionsGate {
                private val ALLOWED_HOSTS = setOf("raw.githubusercontent.com")
                fun allowsRequestTo(host: String?): Boolean = isArmed && host != null
                fun <T> whileUserStartedRefresh(block: () -> T): T = block()
                val isArmed: Boolean get() = false
            }
            ''',
        )
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/ConnectedArtStore.kt",
            "object ConnectedArtStore { fun x() { MimirConnectedArtGate.setOpen(true) } }",
        )
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/SteamGridDbKeyStore.kt",
            "object SteamGridDbKeyStore { fun x() { MimirSteamGridDbGate.setOpen(false) } }",
        )
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirProviderEgress.kt",
            PASSING_EGRESS,
        )
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/ArtworkFetchController.kt",
            '''
            object ArtworkFetchController {
                fun fetch() {
                    if (!MimirConnectedArtGate.isOpen) return
                    val parsed = java.net.URI("https://www.screenscraper.fr/x")
                    if (!MimirConnectedArtGate.allowsRequestTo(parsed.host)) return
                    val c = (java.net.URL("https://www.screenscraper.fr/x").openConnection() as HttpURLConnection)
                    c.disconnect()
                }
            }
            ''',
        )
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/DatRefreshWorker.kt",
            '''
            class DatRefreshWorker {
                fun go() {
                    if (!catalogRefreshAuthorized(BuildConfig.DEBUG, input)) return
                    val c = (java.net.URL("https://x").openConnection() as HttpURLConnection)
                    c.disconnect()
                }
            }
            ''',
        )
        write(
            self.tmp
            / "core-metadata/src/main/kotlin/com/gruning/mimir/rewrite/core/metadata/online/ScreenScraperMetadataSource.kt",
            '''
            class ScreenScraperMetadataSource(private val gateOpen: () -> Boolean) {
                fun load() {
                    if (!gateOpen()) return
                    val c = (java.net.URL("https://x").openConnection() as HttpURLConnection)
                    c.disconnect()
                }
            }
            ''',
        )
        write(
            self.tmp
            / "core-metadata/src/main/kotlin/com/gruning/mimir/rewrite/core/metadata/online/RetroAchievementsSource.kt",
            '''
            class RetroAchievementsSource(private val gateOpen: () -> Boolean) {
                private val hashLibraryGet = ::defaultRetroAchievementsHashLibraryGet
                fun load() {
                    if (!gateOpen()) return
                    hashLibraryGet()
                    val c = (java.net.URL("https://x").openConnection() as HttpURLConnection)
                    c.disconnect()
                }
            }
            ''',
        )
        write(
            self.tmp
            / "core-metadata/src/main/kotlin/com/gruning/mimir/rewrite/core/metadata/online/RetroAchievementsHashLibrary.kt",
            '''
            internal suspend fun defaultRetroAchievementsHashLibraryGet() {
                val c = (java.net.URL("https://x").openConnection() as HttpURLConnection)
                c.disconnect()
            }
            ''',
        )

        write(
            self.tmp / "core-metadata/src/main/kotlin/com/gruning/mimir/rewrite/core/metadata/online/IgdbMetadataSource.kt",
            """
            class IgdbMetadataSource(private val gateOpen: () -> Boolean) {
                fun load() {
                    if (!gateOpen()) return
                    val c = (java.net.URL("https://x").openConnection() as HttpURLConnection)
                    c.disconnect()
                }
            }
            """,
        )

    def run_gate(self) -> subprocess.CompletedProcess[str]:
        load_gate_module(self.tmp)
        return subprocess.run(
            [sys.executable, str(self.tmp / "tools" / "mimir_connected_posture_gate.py")],
            cwd=self.tmp,
            capture_output=True,
            text=True,
        )

    def assert_pass(self, result: subprocess.CompletedProcess[str]) -> None:
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def assert_fail(self, result: subprocess.CompletedProcess[str], needle: str) -> None:
        self.assertNotEqual(0, result.returncode)
        self.assertIn(needle, result.stdout + result.stderr)

    def test_real_repo_passes(self) -> None:
        result = subprocess.run([sys.executable, str(GATE)], cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("POSTURE GATE: PASS", result.stdout)

    def test_fixture_passes(self) -> None:
        self.assert_pass(self.run_gate())

    def test_missing_evidence_dir_fails(self) -> None:
        shutil.rmtree(self.evidence)
        self.assert_fail(self.run_gate(), "R7-evidence")

    def test_missing_binding_file_fails(self) -> None:
        (self.evidence / "BINDING.md").unlink()
        self.assert_fail(self.run_gate(), "R7-evidence")

    def test_unregistered_primitive_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/EvilFetcher.kt",
            "class EvilFetcher { fun x() { java.net.URL(\"https://x\").openConnection() } }",
        )
        self.assert_fail(self.run_gate(), "R3-unregistered-primitive")

    def test_nested_worktree_contamination_ignored_while_canonical_still_fails(self) -> None:
        nested_primitive = (
            ".worktrees/agent-a/Mimir-rewrite/app/src/main/kotlin/"
            "com/gruning/mimir/rewrite/NestedEvilFetcher.kt"
        )
        write(
            self.tmp / nested_primitive,
            "class NestedEvilFetcher { fun x() { java.net.URL(\"https://x\").openConnection() } }",
        )
        write(
            self.tmp
            / ".cursor/worktrees/agent-b/Mimir-rewrite/app/src/main/kotlin/com/gruning/mimir/rewrite/NestedEvilFetcher.kt",
            "class NestedEvilFetcher { fun x() { java.net.URL(\"https://x\").openStream() } }",
        )
        self.assert_pass(self.run_gate())

        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/EvilFetcher.kt",
            "class EvilFetcher { fun x() { java.net.URL(\"https://x\").openConnection() } }",
        )
        self.assert_fail(self.run_gate(), "R3-unregistered-primitive")

    def test_nested_worktree_vendor_leak_ignored_while_canonical_still_fails(self) -> None:
        write(
            self.tmp / ".worktrees/agent-a/Mimir-rewrite/app/src/main/assets/nested-leak.txt",
            "steamgrid marker\n",
        )
        write(
            self.tmp / ".cursor/worktrees/agent-b/Mimir-rewrite/app/src/main/res/values/nested-leak.xml",
            "<resources><string>screenscraper marker</string></resources>\n",
        )
        write(
            self.tmp
            / ".worktrees/agent-a/Mimir-rewrite/app/src/main/java/com/gruning/mimir/rewrite/NestedVendor.java",
            "class NestedVendor { String x = \"steamgrid\"; }\n",
        )
        self.assert_pass(self.run_gate())

        write(self.tmp / "app/src/main/assets/leak.txt", "steamgrid marker\n")
        self.assert_fail(self.run_gate(), "R8-vendor-leak")

    def test_nested_worktree_cleartext_manifest_ignored_while_canonical_still_fails(self) -> None:
        write(
            self.tmp / ".worktrees/agent-a/Mimir-rewrite/app/src/main/AndroidManifest.xml",
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'android:usesCleartextTraffic="true" />\n',
        )
        write(
            self.tmp / ".cursor/worktrees/agent-b/Mimir-rewrite/app/src/main/AndroidManifest.xml",
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'android:usesCleartextTraffic="true" />\n',
        )
        self.assert_pass(self.run_gate())

        write(
            self.tmp / "app/src/main/AndroidManifest.xml",
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'android:usesCleartextTraffic="true" />\n',
        )
        self.assert_fail(self.run_gate(), "R9-cleartext")

    def test_symlink_alias_to_worktrees_ignored_while_canonical_still_fails(self) -> None:
        write(
            self.tmp
            / ".worktrees/agent-a/Mimir-rewrite/app/src/main/kotlin/com/gruning/mimir/rewrite/NestedEvilFetcher.kt",
            "class NestedEvilFetcher { fun x() { java.net.URL(\"https://x\").openConnection() } }",
        )
        write(
            self.tmp / ".worktrees/agent-a/Mimir-rewrite/app/src/main/assets/nested-leak.txt",
            "steamgrid marker\n",
        )
        write(
            self.tmp / ".worktrees/agent-a/Mimir-rewrite/app/src/main/AndroidManifest.xml",
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'android:usesCleartextTraffic="true" />\n',
        )
        (self.tmp / "alias").symlink_to(self.tmp / ".claude" / "worktrees", target_is_directory=True)
        self.assert_pass(self.run_gate())

        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/EvilFetcher.kt",
            "class EvilFetcher { fun x() { java.net.URL(\"https://x\").openConnection() } }",
        )
        self.assert_fail(self.run_gate(), "R3-unregistered-primitive")

    def test_symlink_loop_to_root_terminates_without_duplicate_scan(self) -> None:
        (self.tmp / "loop").symlink_to(self.tmp, target_is_directory=True)
        self.assert_pass(self.run_gate())

    def test_unregistered_open_stream_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/EvilFetcher.kt",
            "class EvilFetcher { fun x() { java.net.URL(\"https://x\").openStream() } }",
        )
        self.assert_fail(self.run_gate(), "R3-unregistered-primitive")

    def test_missing_chokepoint_file_fails(self) -> None:
        path = self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirProviderEgress.kt"
        path.unlink()
        self.assert_fail(self.run_gate(), "R2-missing-chokepoint")

    def test_stale_chokepoint_without_live_open_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirProviderEgress.kt",
            '''
            object MimirProviderEgress {
                fun open(route: MimirProviderEgressRoute, url: String): Boolean {
                    return MimirSteamGridDbGate.allowsRequestTo(java.net.URI(url).host)
                }
            }
            ''',
        )
        self.assert_fail(self.run_gate(), "R2-stale-chokepoint")

    def test_token_only_chokepoint_with_unused_import_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirProviderEgress.kt",
            '''
            import java.net.HttpURLConnection
            object MimirProviderEgress {
                fun gateAllows(route: MimirProviderEgressRoute): Boolean {
                    return MimirSteamGridDbGate.isOpen || MimirConnectedArtGate.isOpen
                }
            }
            ''',
        )
        self.assert_fail(self.run_gate(), "R2-stale-chokepoint")

    def test_is_open_or_route_authorization_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirProviderEgress.kt",
            '''
            object MimirProviderEgress {
                fun open(route: MimirProviderEgressRoute, url: String): Boolean {
                    return MimirSteamGridDbGate.isOpen || MimirConnectedArtGate.isOpen
                }
                private fun liveOpen() {
                    val c = (java.net.URL("https://x").openConnection() as HttpURLConnection)
                    c.disconnect()
                }
            }
            ''',
        )
        self.assert_fail(self.run_gate(), "R2-route-auth")

    def test_is_open_only_route_gate_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirProviderEgress.kt",
            '''
            object MimirProviderEgress {
                private fun gateAllows(route: MimirProviderEgressRoute): Boolean = when (route) {
                    MimirProviderEgressRoute.STEAMGRIDDB_API -> MimirSteamGridDbGate.isOpen
                    else -> MimirConnectedArtGate.isOpen
                }
                private fun liveOpen() {
                    val c = (java.net.URL("https://x").openConnection() as HttpURLConnection)
                    c.disconnect()
                }
            }
            ''',
        )
        self.assert_fail(self.run_gate(), "R2-route-auth")

    def test_missing_gate_binding_in_chokepoint_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirProviderEgress.kt",
            '''
            object MimirProviderEgress {
                fun liveOpen() {
                    val c = (java.net.URL("https://x").openConnection() as HttpURLConnection)
                    c.disconnect()
                }
            }
            ''',
        )
        self.assert_fail(self.run_gate(), "R2-gate-binding")

    def test_mutable_global_opener_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirProviderEgress.kt",
            PASSING_EGRESS.replace(
                "private val liveOpener",
                "var opener = MimirProviderEgressOpener { _, _, _, _ -> null }\n        private val liveOpener",
            ),
        )
        self.assert_fail(self.run_gate(), "R2-egress-opener")

    def test_dat_refresh_missing_debug_binding_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/DatRefreshWorker.kt",
            '''
            class DatRefreshWorker {
                fun go() {
                    val c = (java.net.URL("https://x").openConnection() as HttpURLConnection)
                    c.disconnect()
                }
            }
            ''',
        )
        self.assert_fail(self.run_gate(), "R2-binding")

    def test_cross_owner_setopen_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/ConnectedArtStore.kt",
            "object ConnectedArtStore { fun x() { MimirSteamGridDbGate.setOpen(true) } }",
        )
        self.assert_fail(self.run_gate(), "R1-setter-owner")

    def test_foreign_setopen_owner_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/EvilStore.kt",
            "object EvilStore { fun x() { MimirConnectedArtGate.setOpen(true) } }",
        )
        self.assert_fail(self.run_gate(), "R1-setter-owner")

    def test_vendor_leak_in_assets_fails(self) -> None:
        write(self.tmp / "app/src/main/assets/leak.txt", "steamgrid marker\n")
        self.assert_fail(self.run_gate(), "R8-vendor-leak")

    def test_cleartext_literal_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/Cleartext.kt",
            'val x = "http://example.com"\n',
        )
        self.assert_fail(self.run_gate(), "R9-cleartext")

    def test_credential_logging_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/Logger.kt",
            'fun x() { Log.e("tag", "api_key leaked") }\n',
        )
        self.assert_fail(self.run_gate(), "R10-logging")

    def test_multiline_credential_logging_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/Logger.kt",
            '''
            fun x() {
                Log.e(
                    "tag",
                    "Bearer secret-token",
                )
            }
            ''',
        )
        self.assert_fail(self.run_gate(), "R10-logging")

    def test_multiline_url_logging_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/Logger.kt",
            '''
            fun x() {
                Log.w(
                    "tag",
                    "fetch failed for https://example.com/path",
                )
            }
            ''',
        )
        self.assert_fail(self.run_gate(), "R10-logging")

    # ── R10 identifier arm: interpolation-aware, and scoped to REMOTE identifiers ────────────
    #
    # Pinned 2026-09-01. The arm used to read `strip_string_literals(payload)`, which blanks a
    # literal INCLUDING its `${...}` bodies, so it graded splice syntax instead of sensitivity:
    # `+ url +` was caught and `"$url"` — the ordinary way to log in Kotlin — was invisible. The
    # same blindness passed a SAF `content://` URI interpolated and failed it concatenated, six
    # lines apart in one function in MainActivity. Both directions are pinned below; deleting
    # either half restores a rule that grades punctuation.

    def test_interpolated_url_variable_logging_fails(self) -> None:
        # THE HOLE THIS CLOSES: a real remote endpoint reaching logcat, invisible before.
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/Logger.kt",
            'fun x() { Log.d("tag", "fetching $url") }\n',
        )
        self.assert_fail(self.run_gate(), "R10-logging")

    def test_interpolated_endpoint_expression_logging_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/Logger.kt",
            'fun x() { Log.d("tag", "hitting ${route.endpoint}") }\n',
        )
        self.assert_fail(self.run_gate(), "R10-logging")

    def test_interpolated_api_key_expression_logging_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/Logger.kt",
            'fun x() { Log.d("tag", "key=${creds.apiKey}") }\n',
        )
        self.assert_fail(self.run_gate(), "R10-logging")

    def test_interpolated_auth_token_logging_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/Logger.kt",
            'fun x() { Log.d("tag", "sending ${session.authToken}") }\n',
        )
        self.assert_fail(self.run_gate(), "R10-logging")

    def test_nested_quote_interpolation_does_not_smuggle_a_url_past_the_scan(self) -> None:
        # `${"%.3f".format(x)}` truncates under a naive `\\$\\{[^}]*\\}`; the brace matcher must
        # keep scanning past it rather than losing the rest of the payload.
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/Logger.kt",
            'fun x() { Log.d("tag", "v=${"%.3f".format(x)} at $url") }\n',
        )
        self.assert_fail(self.run_gate(), "R10-logging")

    def test_local_saf_content_uri_logging_passes_in_both_syntaxes(self) -> None:
        # A SAF grant is a LOCAL content locator, not a remote endpoint. Both splices agree now.
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/Probe.kt",
            '''
            fun x() {
                Log.i("tag", "RESCAN try ${grant.uri} -> $result")
                Log.i("tag", "candidates=[" + grants.joinToString { it.uri.toString() } + "]")
            }
            ''',
        )
        self.assert_pass(self.run_gate())

    def test_playback_generation_token_logging_passes(self) -> None:
        # The frontend bed's `token` is a monotonic deck counter, not a credential.
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/Deck.kt",
            '''
            fun x() {
                Log.d("tag", "start token=${deck.token}")
                Log.d("tag", "audible-owned token=$token volume=${"%.3f".format(volume)}")
            }
            ''',
        )
        self.assert_pass(self.run_gate())

    def test_ra_hash_library_external_reference_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/RaLeak.kt",
            "fun x() { defaultRetroAchievementsHashLibraryGet() }\n",
        )
        self.assert_fail(self.run_gate(), "R5-ra-hash-library")

    def test_ra_hash_library_double_binding_fails(self) -> None:
        write(
            self.tmp
            / "core-metadata/src/main/kotlin/com/gruning/mimir/rewrite/core/metadata/online/RetroAchievementsSource.kt",
            '''
            class RetroAchievementsSource(private val gateOpen: () -> Boolean) {
                private val first = ::defaultRetroAchievementsHashLibraryGet
                private val second = ::defaultRetroAchievementsHashLibraryGet
                fun load() {
                    if (!gateOpen()) return
                    first()
                    second()
                    val c = (java.net.URL("https://x").openConnection() as HttpURLConnection)
                    c.disconnect()
                }
            }
            ''',
        )
        self.assert_fail(self.run_gate(), "R5-ra-hash-library")

    def test_unapproved_primitive_in_egress_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirProviderEgress.kt",
            PASSING_EGRESS.replace(
                "object MimirProviderEgress {",
                "object MimirProviderEgress {\n        private val rogueClient = OkHttpClient()\n",
            ),
        )
        self.assert_fail(self.run_gate(), "R4-unknown-family")

    def test_unknown_primitive_family_in_registered_file_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirProviderEgress.kt",
            '''
            object MimirProviderEgress {
                fun open(route: MimirProviderEgressRoute, url: String): Boolean {
                    val host = java.net.URI(url).host
                    return MimirSteamGridDbGate.allowsRequestTo(host)
                }
                fun liveOpen() {
                    val client = OkHttpClient()
                    client.newCall(null)
                }
            }
            ''',
        )
        self.assert_fail(self.run_gate(), "R4-unknown-family")

    def test_opener_before_gate_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirProviderEgress.kt",
            '''
            object MimirProviderEgress {
                private val liveOpener = MimirProviderEgressOpener { _, _, _, _ -> null }
                fun open(
                    route: MimirProviderEgressRoute,
                    url: String,
                    connectTimeoutMs: Int,
                    readTimeoutMs: Int,
                    requestHeaders: Map<String, String> = emptyMap(),
                    opener: MimirProviderEgressOpener = liveOpener,
                ): MimirProviderEgressResponse? {
                    val response = opener.open(url, connectTimeoutMs, readTimeoutMs, requestHeaders)
                    val host = java.net.URI(url).host ?: return null
                    if (!urlAllowed(route, url)) return null
                    if (!MimirSteamGridDbGate.allowsRequestTo(host)) return null
                    if (!MimirConnectedArtGate.allowsRequestTo(host)) return null
                    return response
                }
                private fun urlAllowed(route: MimirProviderEgressRoute, url: String): Boolean = true
                private fun liveOpen() {
                    val c = (java.net.URL("https://x").openConnection() as HttpURLConnection)
                    c.disconnect()
                }
            }
            ''',
        )
        self.assert_fail(self.run_gate(), "R2-route-auth")

    def test_dynamic_host_variable_fails(self) -> None:
        write(
            self.tmp / "core-model/src/main/kotlin/com/gruning/mimir/rewrite/core/model/posture/MimirSteamGridDbGate.kt",
            '''
            object MimirSteamGridDbGate {
                private val foreign = "evil.com"
                private val ALLOWED_HOSTS = setOf(foreign)
                fun allowsRequestTo(host: String?): Boolean = false
                fun setOpen(value: Boolean) {}
            }
            ''',
        )
        self.assert_fail(self.run_gate(), "R6-hosts")

    def test_ra_dead_hash_library_binding_fails(self) -> None:
        write(
            self.tmp
            / "core-metadata/src/main/kotlin/com/gruning/mimir/rewrite/core/metadata/online/RetroAchievementsSource.kt",
            '''
            class RetroAchievementsSource(private val gateOpen: () -> Boolean) {
                private val hashLibraryGet = ::defaultRetroAchievementsHashLibraryGet
                fun load() {
                    if (!gateOpen()) return
                    val c = (java.net.URL("https://x").openConnection() as HttpURLConnection)
                    c.disconnect()
                }
            }
            ''',
        )
        self.assert_fail(self.run_gate(), "R5-ra-hash-library")

    def test_sensitive_log_variable_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/Logger.kt",
            '''
            fun x(remoteUrl: String) {
                Log.e("tag", remoteUrl)
            }
            ''',
        )
        self.assert_fail(self.run_gate(), "R10-logging")

    def test_tag_only_log_identifier_allowed(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/Logger.kt",
            'fun x() { Log.e("api_key", "safe message") }\n',
        )
        self.assert_pass(self.run_gate())

    def test_println_url_logging_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/Logger.kt",
            'fun x() { println("fetch failed for https://example.com/path") }\n',
        )
        self.assert_fail(self.run_gate(), "R10-logging")

    def test_print_credential_logging_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/Logger.kt",
            'fun x() { print("apiKey=secret-value credential leaked") }\n',
        )
        self.assert_fail(self.run_gate(), "R10-logging")

    def test_println_bearer_interpolation_fails(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/Logger.kt",
            'fun x(token: String) { println("Authorization: Bearer $token") }\n',
        )
        self.assert_fail(self.run_gate(), "R10-logging")

    def test_benign_println_allowed(self) -> None:
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/Logger.kt",
            'fun x() { println("catalog refresh finished") }\n',
        )
        self.assert_pass(self.run_gate())

    def test_disjoint_host_suffix_violation_fails(self) -> None:
        write(
            self.tmp / "core-model/src/main/kotlin/com/gruning/mimir/rewrite/core/model/posture/MimirSteamGridDbGate.kt",
            '''
            object MimirSteamGridDbGate {
                private val ALLOWED_HOSTS = setOf("screenscraper.fr")
                fun allowsRequestTo(host: String?): Boolean = false
                fun setOpen(value: Boolean) {}
            }
            ''',
        )
        self.assert_fail(self.run_gate(), "R6-hosts")

    def test_host_concatenation_fails(self) -> None:
        write(
            self.tmp / "core-model/src/main/kotlin/com/gruning/mimir/rewrite/core/model/posture/MimirSteamGridDbGate.kt",
            '''
            object MimirSteamGridDbGate {
                private val ALLOWED_HOSTS = setOf("cdn2." + "steamgriddb.com")
                fun allowsRequestTo(host: String?): Boolean = false
                fun setOpen(value: Boolean) {}
            }
            ''',
        )
        self.assert_fail(self.run_gate(), "R6-hosts")

    def test_steam_store_api_route_missing_from_route_gate_table_fails(self) -> None:
        # 2026-08-19: ROUTE_GATE_HOST_CHECKS must know about STEAM_STORE_API or the posture
        # gate stays blind to that route entirely — this is the fixture that is actually
        # sensitive to that table entry. Omitting the route's label from the grouped arm
        # entirely (as below) leaves no arm at all for STEAM_STORE_API, which
        # check_route_gate_scoping (2026-08-19 fix) reports as a missing route branch.
        # (Before that fix, ROUTE_GATE_HOST_CHECKS's enforcement was a whole-file substring
        # test, not scoped to the `when` arm for a route — see
        # test_route_gate_check_is_scoped_to_its_own_arm_not_whole_file below for the fixture
        # that actually exploited that hole: a route split into its own weaker arm while the
        # required gate-check substring still appeared elsewhere in the file for other routes.)
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirProviderEgress.kt",
            PASSING_EGRESS.replace(
                "            MimirProviderEgressRoute.STEAM_STORE_API,\n", ""
            ),
        )
        result = self.run_gate()
        self.assert_fail(result, "R2-route-auth")
        self.assertIn(
            "missing route branch for MimirProviderEgressRoute.STEAM_STORE_API",
            result.stdout + result.stderr,
        )

    def test_route_gate_check_is_scoped_to_its_own_arm_not_whole_file(self) -> None:
        # THE EXACT ATTACK (2026-08-19 R2-route-auth scoping hole): split STEAM_STORE_API into
        # its own arm that authorizes unconditionally, while the other two routes keep a
        # legitimate MimirSteamGridDbGate.allowsRequestTo(host) arm. Before the scoping fix this
        # PASSED, because ROUTE_GATE_HOST_CHECKS's enforcement was a whole-file substring test:
        # "MimirSteamGridDbGate.allowsRequestTo" still appears in the file for STEAMGRIDDB_API /
        # STEAMGRIDDB_MEDIA, so the required substring never disappeared even though
        # STEAM_STORE_API itself never calls it.
        attack_arm = '''\
            MimirProviderEgressRoute.STEAMGRIDDB_API,
            MimirProviderEgressRoute.STEAMGRIDDB_MEDIA,
            -> MimirSteamGridDbGate.allowsRequestTo(host)
            MimirProviderEgressRoute.STEAM_STORE_API -> true'''
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirProviderEgress.kt",
            PASSING_EGRESS.replace(_PASSING_GROUPED_ARM, attack_arm),
        )
        result = self.run_gate()
        self.assert_fail(result, "R2-route-auth")
        self.assertIn(
            "route MimirProviderEgressRoute.STEAM_STORE_API does not call "
            "MimirSteamGridDbGate.allowsRequestTo",
            result.stdout + result.stderr,
        )

    def test_route_gate_check_rejects_isallowedhost_downgrade_in_scoped_arm(self) -> None:
        # Same shape as the attack above, but the weak arm calls isAllowedHost(host) instead of
        # allowsRequestTo(host) — isAllowedHost only checks host-suffix membership, not whether
        # the gate is open, so this is a real authorization downgrade. isAllowedHost is also
        # legitimately used by STEAMGRIDDB_KEY_VALIDATION's own arm (see PASSING_EGRESS), so the
        # check must be scoped per-route and must not ban the string globally.
        attack_arm = '''\
            MimirProviderEgressRoute.STEAMGRIDDB_API,
            MimirProviderEgressRoute.STEAMGRIDDB_MEDIA,
            -> MimirSteamGridDbGate.allowsRequestTo(host)
            MimirProviderEgressRoute.STEAM_STORE_API -> MimirSteamGridDbGate.isAllowedHost(host)'''
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirProviderEgress.kt",
            PASSING_EGRESS.replace(_PASSING_GROUPED_ARM, attack_arm),
        )
        result = self.run_gate()
        self.assert_fail(result, "R2-route-auth")
        self.assertIn(
            "route MimirProviderEgressRoute.STEAM_STORE_API does not call "
            "MimirSteamGridDbGate.allowsRequestTo",
            result.stdout + result.stderr,
        )

    def test_real_grouped_arm_shape_and_key_validation_isallowedhost_both_pass(self) -> None:
        # The real production layout (app/src/main/kotlin/com/gruning/mimir/rewrite/
        # MimirProviderEgress.kt:66-76): one grouped arm sharing
        # MimirSteamGridDbGate.allowsRequestTo(host) across three route labels, plus a separate
        # STEAMGRIDDB_KEY_VALIDATION arm that legitimately calls
        # MimirSteamGridDbGate.isAllowedHost(host) — must not false-positive now that
        # isAllowedHost is scoped per-arm rather than treated as a banned global substring.
        self.assert_pass(self.run_gate())

    def test_gateallowsrequesto_unparseable_when_block_fails(self) -> None:
        # gateAllowsRequestTo rewritten as an if-chain instead of `when (route) { ... }`. Every
        # other line of PASSING_EGRESS is untouched (open(), urlAllowed, parseUrlHost,
        # liveOpener all still present and correctly ordered), so this fixture would be fully
        # green except for the one thing under test: the scoped parser must fail loudly when it
        # cannot find/parse gateAllowsRequestTo's when-block, not silently report no findings.
        unparseable_block = '''\
        private fun gateAllowsRequestTo(route: MimirProviderEgressRoute, host: String): Boolean {
            if (route == MimirProviderEgressRoute.STEAMGRIDDB_KEY_VALIDATION) {
                return MimirSteamGridDbGate.isAllowedHost(host)
            }
            if (route == MimirProviderEgressRoute.SCREENSCRAPER_MEDIA) {
                return MimirConnectedArtGate.allowsRequestTo(host)
            }
            return MimirSteamGridDbGate.allowsRequestTo(host)
        }'''
        write(
            self.tmp / "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirProviderEgress.kt",
            PASSING_EGRESS.replace(_PASSING_GATE_ALLOWS_BLOCK, unparseable_block),
        )
        result = self.run_gate()
        self.assert_fail(result, "R2-route-auth")
        self.assertIn(
            "could not locate a parseable gateAllowsRequestTo",
            result.stdout + result.stderr,
        )

    def test_known_music_state_and_caller_pass(self) -> None:
        write(self.tmp / MUSIC_STATE_PATH, "internal object CompanionMusicPlayerState { fun setOpen(value: Boolean) { mutableOpen.value = value } }")
        write(self.tmp / MUSIC_CALLER_PATH, "fun x() { CompanionMusicPlayerState.setOpen(true) }")
        self.assert_pass(self.run_gate())

    def test_music_exception_does_not_allow_gate_or_alias_setters(self) -> None:
        for path in (MUSIC_STATE_PATH, MUSIC_CALLER_PATH):
            for call in ("MimirConnectedArtGate.setOpen(true)", "MimirSteamGridDbGate . setOpen (true)",
                         "alias.setOpen(true)", "setOpen (true)", "other.CompanionMusicPlayerState.setOpen(true)",
                         "CompanionMusicPlayerStateDebug.setOpen(true)"):
                with self.subTest(path=path, call=call):
                    if path == MUSIC_STATE_PATH:
                        source = "internal object CompanionMusicPlayerState { fun setOpen(value: Boolean) {} fun x() { " + call + " } }"
                    else:
                        source = "fun x() { CompanionMusicPlayerState.setOpen(true); " + call + " }"
                    write(self.tmp / path, source)
                    self.assert_fail(self.run_gate(), "R1-setter-owner")
            (self.tmp / path).unlink()

    def test_music_receiver_exception_is_path_bound(self) -> None:
        write(self.tmp / "app/src/main/kotlin/Evil.kt", "fun x() { CompanionMusicPlayerState.setOpen(true) }")
        self.assert_fail(self.run_gate(), "R1-setter-owner")

    def test_music_declaration_requires_exact_owner(self) -> None:
        for source in ("object Other { fun setOpen(value: Boolean) {} }",
                       "object CompanionMusicPlayerState { object Nested { fun setOpen(value: Boolean) {} } }"):
            with self.subTest(source=source):
                write(self.tmp / MUSIC_STATE_PATH, source)
                self.assert_fail(self.run_gate(), "R1-setter-owner")

    def test_lifecycle_only_connection_management_passes(self) -> None:
        self.assert_pass(self.run_gate())

    def test_lifecycle_new_network_families_fail(self) -> None:
        for added in ('fun x() { URL("https://x").openConnection() }',
                      'val stream = URL("https://x").openStream()', 'val client = OkHttpClient()',
                      'val secure: HttpsURLConnection? = null'):
            with self.subTest(added=added):
                write(self.tmp / LIFECYCLE_PATH, PASSING_LIFECYCLE + added)
                self.assert_fail(self.run_gate(), "R4-unknown-family")

    def test_lifecycle_missing_cancel_bindings_fail(self) -> None:
        for marker in ("connection.disconnect()", "current?.disconnect()", "fun abort(",
                       "awaitCancellation()", "ensureActive()"):
            with self.subTest(marker=marker):
                write(self.tmp / LIFECYCLE_PATH, PASSING_LIFECYCLE.replace(marker, "removed()"))
                self.assert_fail(self.run_gate(), "R2-binding")

    def test_family_catalog_registered_transport_passes(self) -> None:
        self.assert_pass(self.run_gate())

    def test_family_catalog_new_network_families_fail(self) -> None:
        for added in ('val client = OkHttpClient()', 'val stream = URL("https://x").openStream()',
                      'val socket = java.net.Socket()', 'val tls: HttpsURLConnection? = null'):
            with self.subTest(added=added):
                write(self.tmp / CATALOG_PATH, PASSING_CATALOG + added)
                self.assert_fail(self.run_gate(), "R4-unknown-family")

    def test_family_catalog_required_security_bindings_fail_closed(self) -> None:
        mutations = (
            ("publicKey ?: return FamilyCatalogFetchOutcome.DISABLED", "publicKey!!"),
            ("!FamilyCatalogSignature.verify(key, catalog.bytes, signature)", "false"),
            ("!sink.promoteVerified(catalog.bytes, signature)", "false"),
            ("origin?.owns(response.finalUrl) != true", "false"),
            ("origin == null", "false"),
            ("response.body.size > capBytes", "false"),
            ("MAX_CATALOG_BYTES = 64 * 1024", "MAX_CATALOG_BYTES = Int.MAX_VALUE"),
            ("FamilyCatalogSignature.MAX_SIGNATURE_FILE_BYTES", "Int.MAX_VALUE"),
            ("DEADLINE_MILLIS = 10_000L", "DEADLINE_MILLIS = Long.MAX_VALUE"),
            ("connection.instanceFollowRedirects = false", "connection.instanceFollowRedirects = true"),
            ("connection.useCaches = false", "connection.useCaches = true"),
            ("val limit = maxBodyBytes + 1", "val limit = Int.MAX_VALUE"),
            ("!\"https\".equals(uri.scheme, ignoreCase = true)", "false"),
            ("uri.rawUserInfo != null", "false"),
            ("uri.port != -1 && uri.port != 443", "false"),
        )
        for before, after in mutations:
            with self.subTest(binding=before):
                self.assertIn(before, PASSING_CATALOG)
                write(self.tmp / CATALOG_PATH, PASSING_CATALOG.replace(before, after))
                self.assert_fail(self.run_gate(), "R2-")

    def test_family_catalog_comment_cannot_replace_signature_check(self) -> None:
        check = "if (!FamilyCatalogSignature.verify(key, catalog.bytes, signature)) {\n                return FamilyCatalogFetchOutcome.BAD_SIGNATURE\n            }"
        self.assertIn(check, PASSING_CATALOG)
        write(self.tmp / CATALOG_PATH, PASSING_CATALOG.replace(check, "/* " + check + " */"))
        self.assert_fail(self.run_gate(), "R2-family-catalog-binding")

    def test_family_catalog_key_guard_must_precede_fetch(self) -> None:
        guard = "val key = publicKey ?: return FamilyCatalogFetchOutcome.DISABLED"
        source = PASSING_CATALOG.replace(guard, "").replace("val catalog = get(", 'val bad = get("https://x")\n' + guard + "\nval catalog = get(")
        write(self.tmp / CATALOG_PATH, source)
        self.assert_fail(self.run_gate(), "public-key guard must precede fetch")

    def test_family_catalog_origin_guard_must_precede_fetch(self) -> None:
        guard = "if (origin == null) return FamilyCatalogFetchOutcome.REFUSED_HOST"
        source = PASSING_CATALOG.replace(guard, "").replace("val signatureFile = get(", guard + "\nval signatureFile = get(")
        write(self.tmp / CATALOG_PATH, source)
        self.assert_fail(self.run_gate(), "origin guard must precede fetch")

    def test_family_catalog_signature_check_must_precede_promotion(self) -> None:
        source = PASSING_CATALOG.replace("val signature = FamilyCatalogSignature.decodeSignatureFile(",
                                        "sink.promoteVerified(catalog.bytes, signature)\nval signature = FamilyCatalogSignature.decodeSignatureFile(")
        write(self.tmp / CATALOG_PATH, source)
        self.assert_fail(self.run_gate(), "signature check must precede promotion")

    def test_legal_notice_exception_is_exact(self) -> None:
        write(self.tmp / "app/src/main/assets/legal/NOTICE", "ScreenScraper, SteamGridDB and IGDB credits")
        self.assert_pass(self.run_gate())
        for path in ("app/src/main/assets/legal/NOTICE.txt", "app/src/main/assets/NOTICE",
                     "app/src/main/assets/legal/NOTICE-copy", "core-ui/src/main/assets/legal/NOTICE"):
            with self.subTest(path=path):
                write(self.tmp / path, "ScreenScraper credits")
                self.assert_fail(self.run_gate(), "R8-vendor-leak")
                (self.tmp / path).unlink()

    def test_exact_onboarding_keys_pass_in_app_string_locales(self) -> None:
        keys = (
            "app_onboarding_metadata_screenscraper_title", "app_onboarding_metadata_screenscraper_detail",
            "app_onboarding_metadata_screenscraper_connected", "app_onboarding_metadata_steamgriddb_title",
            "app_onboarding_metadata_steamgriddb_with_key", "app_onboarding_metadata_steamgriddb_without_key",
            "app_onboarding_metadata_steamgriddb_added", "app_onboarding_metadata_steamgriddb_update_key",
            "app_onboarding_metadata_steamgriddb_add_key", "app_onboarding_reason_details_art_key",
            "app_onboarding_reason_art_key",
        )
        xml = '<resources>' + ''.join(f'<string name="{key}">ScreenScraper SteamGridDB</string>' for key in keys) + '</resources>'
        for locale in ("values", "values-es", "values-b+es+419"):
            write(self.tmp / f"app/src/main/res/{locale}/strings.xml", xml)
        self.assert_pass(self.run_gate())

    def test_new_onboarding_keys_and_other_vendor_content_fail(self) -> None:
        key = "app_onboarding_metadata_screenscraper_title"
        good = f'<string name="{key}">ScreenScraper</string>'
        bad_nodes = (
            '<string name="app_onboarding_metadata_screenscraper_new">copy</string>',
            '<string name="unknown">SteamGridDB</string>',
            '<string name="unknown">&#73;GDB</string>',
            '<!-- Twitch comment outside an exempt entry -->',
            'IGDB tail text',
            f'<string name="{key}">duplicate</string>',
            f'<string-array name="{key}"><item>ScreenScraper</item></string-array>',
        )
        for bad in bad_nodes:
            with self.subTest(content=bad):
                write(self.tmp / "app/src/main/res/values/strings.xml", '<resources>' + good + bad + '</resources>')
                self.assert_fail(self.run_gate(), "R8-vendor-leak")

    def test_onboarding_exception_does_not_apply_to_other_paths(self) -> None:
        xml = '<resources><string name="app_onboarding_metadata_screenscraper_title">ScreenScraper</string></resources>'
        for path in ("app/src/main/res/values/copy.xml", "app/src/main/res/xml/strings.xml",
                     "core-ui/src/main/res/values/strings.xml", "app/src/main/assets/strings.xml"):
            with self.subTest(path=path):
                write(self.tmp / path, xml)
                self.assert_fail(self.run_gate(), "R8-vendor-leak")
                (self.tmp / path).unlink()

    def test_malformed_onboarding_xml_fails_closed(self) -> None:
        write(self.tmp / "app/src/main/res/values/strings.xml", '<resources><string name="app_onboarding_metadata_screenscraper_title">ScreenScraper')
        self.assert_fail(self.run_gate(), "invalid resource XML")

    def test_vendor_xml_namespace_and_doctype_do_not_disappear(self) -> None:
        for xml in ('<resources xmlns:vendor="https://igdb.com"><string name="normal">copy</string></resources>',
                    '<!DOCTYPE resources [<!ENTITY vendor "ScreenScraper">]><resources/>'):
            with self.subTest(xml=xml):
                write(self.tmp / "app/src/main/res/values/strings.xml", xml)
                self.assert_fail(self.run_gate(), "R8-vendor-leak")

    def test_exceptions_do_not_disable_cleartext_or_logging_guards(self) -> None:
        for path, source, rule in (
            (MUSIC_CALLER_PATH, 'fun x() { val plain = "http://example.com" }', 'R9-cleartext'),
            (LIFECYCLE_PATH, PASSING_LIFECYCLE + 'fun log() { println("https://secret.example") }', 'R10-logging'),
            (CATALOG_PATH, PASSING_CATALOG + 'fun log() { println("password=secret") }', 'R10-logging'),
        ):
            with self.subTest(rule=rule, path=path):
                write(self.tmp / path, source)
                self.assert_fail(self.run_gate(), rule)
                if path == LIFECYCLE_PATH:
                    write(self.tmp / path, PASSING_LIFECYCLE)
                elif path == CATALOG_PATH:
                    write(self.tmp / path, PASSING_CATALOG)
                else:
                    (self.tmp / path).unlink()

    def test_ui_gate_import_is_required(self) -> None:
        broken = GATE.read_text(encoding="utf-8").replace("mimir_ui_invariants_gate", "missing_ui_gate")
        script = self.tmp / "tools" / "broken_gate.py"
        script.write_text(broken, encoding="utf-8")
        result = subprocess.run([sys.executable, str(script)], cwd=self.tmp, capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)


if __name__ == "__main__":
    unittest.main(verbosity=2)
