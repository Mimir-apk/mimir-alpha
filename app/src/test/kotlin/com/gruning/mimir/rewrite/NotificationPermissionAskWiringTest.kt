package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The call sites that make O13  reach the player. [NotificationPermissionAskTest] and
 * [NotificationPermissionAskRobolectricTest] pin what the ask decides; this pins where it is asked from, what Android
 * is told, and what it is never allowed to touch.
 *
 * Source characterization, not behaviour: no JVM harness here can construct MainActivity, post a foreground-service
 * notification or put Android's permission dialog on screen (the gap [StorageGrantAuditWiringTest] documents).
 * Comments are stripped first, so prose can neither satisfy nor break a pin.
 */
class NotificationPermissionAskWiringTest {

    @Test
    fun `the main manifest declares POST_NOTIFICATIONS, the lab target removes it, and devlab inherits it`() {
        assertTrue(
            "Android 13+ shows no dialog for an undeclared permission, and the services' notices stay out of the shade",
            Regex("""<uses-permission android:name="android\.permission\.POST_NOTIFICATIONS" ?/>""")
                .containsMatchIn(xml("src/main/AndroidManifest.xml")),
        )
        assertTrue(
            "the lab target removes both foreground services, so it must not inherit their notice permission",
            Regex("""<uses-permission android:name="android\.permission\.POST_NOTIFICATIONS" tools:node="remove" ?/>""")
                .containsMatchIn(xml("src/lab/AndroidManifest.xml")),
        )
        assertFalse(
            "devlab is a faithful stand-in for debug and must keep the permission the product declares",
            xml("src/devlab/AndroidManifest.xml").contains("POST_NOTIFICATIONS"),
        )
    }

    @Test
    fun `MainActivity is the only request call site, and Android's answer goes only to the ask`() {
        val activity = code(MainActivityPath)
        assertTrue(
            "the launcher must be a field registered before STARTED, like pickStorageRegrant, and hand the answer to " +
                "the ask and nowhere else",
            activity.contains(
                "private val notificationPermissionRequest = " +
                    "registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> " +
                    "MimirNotificationPermissionAsk.onPermissionResult(this, granted) }",
            ),
        )
        assertEquals(
            "exactly one launch, for exactly POST_NOTIFICATIONS",
            listOf("notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)"),
            Regex("""notificationPermissionRequest\.launch\([^)]*\)""").findAll(activity).map { it.value }.toList(),
        )
        assertTrue(
            "in front on Main means RESUMED on display 0: a paused or stopped MainActivity is covered by a game, an app " +
                "or a Settings screen",
            activity.contains(
                "lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && " +
                    "window?.decorView?.display?.displayId == Display.DEFAULT_DISPLAY",
            ),
        )
        assertEquals(
            "no second place may raise a permission dialog",
            listOf("MainActivity.kt"),
            mainKotlinFiles()
                .filter { file -> code(file).let { "RequestPermission()" in it || "requestPermissions(" in it } }
                .map { it.name },
        )
        assertEquals(
            listOf("MainActivity.kt", "MimirNotificationPermissionAsk.kt"),
            mainKotlinFiles().filter { "POST_NOTIFICATIONS" in code(it) }.map { it.name }.sorted(),
        )
    }

    @Test
    fun `MainActivity attaches after the database gate, re-reads the ledger while resumed, and detaches only itself`() {
        val activity = code(MainActivityPath)
        val onCreate = activity.substringAfter("override fun onCreate(savedInstanceState: Bundle?) {")
            .substringBefore("override fun onSaveInstanceState(")
        val gate = onCreate.indexOf("DatabaseStartupGateState.failure?.let { failure ->")
        val attach = onCreate.indexOf("MimirNotificationPermissionAsk.attachHost(notificationAskHost)")
        val resumed = onCreate.indexOf("repeatOnLifecycle(Lifecycle.State.RESUMED)", maxOf(attach, 0))
        val ledger = onCreate.indexOf("MimirSessionAuthority.state,", maxOf(resumed, 0))
        val pending = onCreate.indexOf("MimirNotificationPermissionAsk.pending(this@MainActivity)", maxOf(resumed, 0))
        val home = onCreate.indexOf("MimirNotificationPermissionAsk.onHomeInFront(this@MainActivity)", maxOf(resumed, 0))
        assertTrue("the database gate must still be in onCreate", gate >= 0)
        assertTrue(
            "the host attaches only after the database gate, so the recovery screen can never raise the dialog " +
                "(gate=$gate attach=$attach)",
            attach > gate,
        )
        assertTrue(
            "while RESUMED, every session revision and every pending change must reach onHomeInFront: a game that just " +
                "quit is still RETURNING when Home resumes (attach=$attach resumed=$resumed ledger=$ledger " +
                "pending=$pending home=$home)",
            resumed > attach && ledger > resumed && pending > resumed && home > ledger && home > pending,
        )
        val onDestroy = activity.substringAfter("override fun onDestroy() {").substringBefore("override fun ")
        assertTrue(
            "onDestroy must hand back its own host (the ask guards the identity)",
            onDestroy.contains("MimirNotificationPermissionAsk.detachHost(notificationAskHost)"),
        )
    }

    @Test
    fun `the overlay asks from setPerformanceOverlayVisibility, after its service start and only on that branch`() {
        val service = code(PerformanceOverlayServicePath)
        val body = service.substringAfter("internal fun setPerformanceOverlayVisibility(")
            .substringBefore("internal const val PerformanceTelemetryPollMs")
        val disabled = body.indexOf("return PerformanceOverlayChangeResult.DISABLED")
        val start = body.indexOf("return if (PerformanceOverlayService.start(context)) {")
        val ask = body.indexOf(
            "MimirNotificationPermissionAsk.onFeatureTurnedOn(context, NotificationAskFeature.PERFORMANCE_OVERLAY)",
        )
        val enabled = body.indexOf("PerformanceOverlayChangeResult.ENABLED", maxOf(start, 0))
        val failure = body.indexOf("} else {", maxOf(start, 0))
        assertTrue("turning the overlay on must reach the ask (ask=$ask)", ask >= 0)
        assertTrue(
            "the ask belongs to the started-service branch only: after OFF has returned and the start succeeded, " +
                "before ENABLED, never in the failure branch " +
                "(disabled=$disabled start=$start ask=$ask enabled=$enabled failure=$failure)",
            disabled in 0 until start && start < ask && ask < enabled && enabled < failure,
        )
        assertEquals(
            "one ask site in the file",
            1,
            Regex("""MimirNotificationPermissionAsk\.""").findAll(service).count(),
        )

        // Every enabling path is a caller of that one function, so every path can ask, the dev hook included.
        assertTrue(
            "the mimir.overlayVisibility dev hook must still route through setPerformanceOverlayVisibility",
            code(MainActivityPath).substringAfter("private fun maybeSetOverlayVisibility() {")
                .substringBefore("private fun maybeProbeTuner() {")
                .contains("val result = setPerformanceOverlayVisibility("),
        )
        assertTrue(
            "the Utility row (one coordinator for the Main host, the Bottom host and the single-screen M panel) must " +
                "still route through setPerformanceOverlayVisibility",
            code(AppBrightnessCoordinatorPath)
                .contains("val result = setPerformanceOverlayVisibility(context, next.visibility, tweakerUsageAccessState)"),
        )
        assertEquals(
            "no enabled scope may be written around the function that asks",
            listOf("PerformanceOverlayService.kt"),
            mainKotlinFiles().filter { "PerformanceOverlaySettingsStore.setVisibility(" in code(it) }.map { it.name },
        )
    }

    @Test
    fun `the M button arms on its Utility enable and asks at its keep-alive start`() {
        val step = code(AppBrightnessCoordinatorPath)
            .substringAfter("internal fun stepMButtonVisibilityPolicy(direction: Int) {")
            .substringBefore("internal fun stepNavigationHintVisibility(direction: Int) {")
        assertTrue(
            "turning the button on arms the ask and turning it off disarms it, after the policy is written: $step",
            step.contains(
                "MButtonSettingsStore.setVisibilityPolicy(context, next) " +
                    "if (!current.buttonEnabled && next.buttonEnabled) { " +
                    "MimirNotificationPermissionAsk.armMButton(context) " +
                    "} else if (!next.buttonEnabled) { " +
                    "MimirNotificationPermissionAsk.disarmMButton(context) }",
            ),
        )
        val reconcile = code(MButtonRuntimePath).substringAfter("fun reconcile() {")
            .substringBefore("fun setPrimaryHostForeground(foreground: Boolean) {")
        assertTrue(
            "the ask is weighed only when the keep-alive service was actually started: $reconcile",
            reconcile.contains(
                "if (primaryHostForeground && MButtonKeepAliveService.ensureStarted(appContext)) { " +
                    "MimirNotificationPermissionAsk.onMButtonKeepAliveStarted(appContext) }",
            ),
        )
        assertEquals(
            "the keep-alive service has no other start site that could skip the ask",
            listOf("MButtonRuntime.kt"),
            mainKotlinFiles().filter { "MButtonKeepAliveService.ensureStarted(" in code(it) }.map { it.name },
        )
    }

    @Test
    fun `no service is gated on the grant, and Android's answer changes no feature`() {
        listOf(
            PerformanceOverlayServicePath,
            MButtonKeepAliveServicePath,
            MButtonRuntimePath,
            AppBrightnessCoordinatorPath,
        ).forEach { path ->
            val source = code(path)
            listOf("POST_NOTIFICATIONS", "checkSelfPermission", "areNotificationsEnabled").forEach { probe ->
                assertFalse("$path must not consult the notification grant ($probe)", source.contains(probe))
            }
        }
        val answer = code(AskPath).substringAfter("fun onPermissionResult(granted: Boolean) {")
            .substringBefore("private fun decide()")
        listOf("setVisibility", "stop(", "start(", "armMButton", "ending", "clearAsked", "markAsked").forEach { probe ->
            assertFalse("the answer must change no feature and no ledger ($probe): $answer", answer.contains(probe))
        }
    }

    @Test
    fun `the overlay row's step is gated on its pending ask before it changes anything`() {
        val coordinator = code(AppBrightnessCoordinatorPath)
        val step = coordinator.substringAfter("internal fun stepPerformanceOverlay(direction: Int) {")
            .substringBefore("internal fun openTweakerUsageAccess()")
        val gate = step.indexOf("MimirNotificationPermissionAsk.isAskPending(context, NotificationAskFeature.PERFORMANCE_OVERLAY)")
        assertTrue("stepPerformanceOverlay must ask whether its own permission dialog is still up", gate >= 0)
        assertTrue(
            "the gate precedes the first state read, so a step under the dialog changes nothing",
            gate < step.indexOf("PerformanceOverlaySettingsStore.visibility.value"),
        )
        assertTrue("and it returns", step.substring(gate, step.indexOf("PerformanceOverlaySettingsStore.visibility.value")).contains("return"))
    }

    private fun code(relative: String): String = code(File(relative))

    private fun code(file: File): String {
        assertTrue("${file.path} must be readable from the app module dir", file.isFile)
        return file.readText()
            .replace(Regex("""(?ms)^\s*/\*.*?\*/"""), "")
            .replace(Regex("""(?m)^\s*//.*$"""), "")
            .replace(Regex("""\s+"""), " ")
    }

    private fun xml(relative: String): String {
        val file = File(relative)
        assertTrue("$relative must be readable from the app module dir", file.isFile)
        return file.readText()
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""\s+"""), " ")
    }

    private fun mainKotlinFiles(): List<File> = File("src/main/kotlin").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.path }
        .toList()

    private companion object {
        const val Root = "src/main/kotlin/com/gruning/mimir/rewrite"
        const val MainActivityPath = "$Root/MainActivity.kt"
        const val PerformanceOverlayServicePath = "$Root/PerformanceOverlayService.kt"
        const val MButtonKeepAliveServicePath = "$Root/MButtonKeepAliveService.kt"
        const val MButtonRuntimePath = "$Root/MButtonRuntime.kt"
        const val AppBrightnessCoordinatorPath = "$Root/AppBrightnessCoordinator.kt"
        const val AskPath = "$Root/MimirNotificationPermissionAsk.kt"
    }
}
