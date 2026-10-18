val verifyLabIsolationManifest by tasks.registering {
    group = "verification"
    description = "Fails unless the EP-06A lab APK is a separate-UID inert instrumentation target."
    dependsOn("processLabManifest", "processLabAndroidTestManifest")

    val targetManifest = layout.buildDirectory.file(
        "intermediates/merged_manifests/lab/processLabManifest/AndroidManifest.xml",
    )
    val testManifest = layout.buildDirectory.file(
        "intermediates/packaged_manifests/labAndroidTest/processLabAndroidTestManifest/AndroidManifest.xml",
    )
    inputs.files(targetManifest, testManifest)

    doLast {
        val target = targetManifest.get().asFile.readText()
        val test = testManifest.get().asFile.readText()
        check("""package="com.gruning.mimir.rewrite.lab"""" in target)
        check("""android:name="com.gruning.mimir.rewrite.MimirLabApplication"""" in target)
        check("android:sharedUserId=" !in target)
        check("<activity" !in target)
        check("<service" !in target)
        check("<receiver" !in target)
        check("android.intent.category.HOME" !in target)
        check("android.intent.category.SECONDARY_HOME" !in target)
        check("android.intent.category.LAUNCHER" !in target)
        check("<meta-data" !in target)

        val forbidden = listOf(
            "androidx.startup.InitializationProvider",
            "androidx.core.content.FileProvider",
            "android.permission.INTERNET",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.WRITE_SETTINGS",
            "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.KILL_BACKGROUND_PROCESSES",
            "android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.VIBRATE",
        )
        forbidden.forEach { value ->
            check(value !in target) { "Forbidden lab manifest capability: $value" }
        }
        val targetPermissions = Regex("""<uses-permission\b[^>]*android:name="([^"]+)"[^>]*>""")
            .findAll(target)
            .map { it.groupValues[1] }
            .toSet()
        check(
            targetPermissions ==
                setOf("com.gruning.mimir.rewrite.lab.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"),
        ) {
            "Unexpected lab target permission set: $targetPermissions"
        }
        val authorities = Regex("""android:authorities="([^"]+)"""")
            .findAll(target)
            .map { it.groupValues[1] }
            .toList()
        check(authorities == listOf("com.gruning.mimir.rewrite.lab.recoverylab")) {
            "Unexpected lab provider authorities: $authorities"
        }
        val recoveryProvider = Regex(
            """<provider\b[^>]*android:name="com\.gruning\.mimir\.rewrite\.MimirLabRecoveryDocumentsProvider"[^>]*>.*?</provider>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(target)?.value.orEmpty()
        check("""android:enabled="true"""" in recoveryProvider)
        check("""android:exported="true"""" in recoveryProvider)
        check("""android:grantUriPermissions="true"""" in recoveryProvider)
        check("""android:permission="android.permission.MANAGE_DOCUMENTS"""" in recoveryProvider)
        check(
            Regex("""<intent-filter\b[^>]*>""").findAll(recoveryProvider).count() == 1,
        )
        val providerActions = Regex("""<action\b[^>]*android:name="([^"]+)"[^>]*/>""")
            .findAll(recoveryProvider)
            .map { it.groupValues[1] }
            .toList()
        check(providerActions == listOf("android.content.action.DOCUMENTS_PROVIDER")) {
            "Unexpected lab recovery-provider actions: $providerActions"
        }
        check("<category" !in recoveryProvider)
        check("<data" !in recoveryProvider)

        check("""package="com.gruning.mimir.rewrite.lab.test"""" in test)
        check("android:sharedUserId=" !in test)
        check("""android:targetPackage="com.gruning.mimir.rewrite.lab"""" in test)
        check(
            """android:name="com.gruning.mimir.rewrite.MimirLabTestRunner"""" in test,
        )
        val testPermissions = Regex("""<uses-permission\b[^>]*android:name="([^"]+)"[^>]*>""")
            .findAll(test)
            .map { it.groupValues[1] }
            .toSet()
        check(testPermissions.isEmpty()) {
            "The engine-only lab test APK must have no permissions: $testPermissions"
        }
        check("<activity" !in test)
        check("<service" !in test)
        check("<receiver" !in test)
        check("<provider" !in test)
    }
}

/**
 * devlab's one safety property, asserted rather than trusted to a comment.
 *
 * devlab exists to be RUNNABLE — that is the whole reason it is not `lab` — so the thing that can go
 * wrong is the opposite of lab's failure mode. lab must have no activities; devlab must have exactly one
 * and it must be unreachable except by explicit component. A merger change, a `tools:node` typo, or
 * someone "restoring" the intent filter would silently put a second HOME candidate on a device whose
 * whole product posture is that Mimir IS the launcher, and nothing else in the build would notice.
 *
 * So this asserts BOTH directions: the activity is still there (the variant stayed useful) AND no
 * launcher-facing category survived (the variant stayed safe). Asserting only the second would pass
 * happily on a devlab that had quietly become as unusable as lab.
 */
val verifyDevlabManifest by tasks.registering {
    dependsOn("processDevlabManifest")

    val targetManifest = layout.buildDirectory.file(
        "intermediates/merged_manifests/devlab/processDevlabManifest/AndroidManifest.xml",
    )
    inputs.files(targetManifest)

    doLast {
        val raw = targetManifest.get().asFile.readText()

        // Two things must be stripped before ANY of this means what it looks like, and the first draft
        // of this task got both wrong and failed on a manifest that was completely correct:
        //
        //  1. XML comments. The devlab manifest overlay documents the categories it removes, by name, and
        //     the merger copies that comment through verbatim. A raw `in` check reads the documentation
        //     as the declaration.
        //  2. Everything outside <application>. The product's <queries> block legitimately declares an
        //     <intent> with MAIN + LAUNCHER -- that is Mimir ASKING the system which apps are launchers so
        //     it can enumerate emulators. Declaring yourself a launcher and asking who the launchers are
        //     look identical to a substring search and are opposites in fact.
        val declared = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL).replace(raw, "")
            .substringAfter("<application")

        check("""package="com.gruning.mimir.rewrite.devlab"""" in raw) {
            "devlab must install alongside the owner's app, never over it"
        }

        // Still useful: the activity a device pass drives must exist.
        val mainActivity = Regex(
            """<activity[^>]*android:name="com\.gruning\.mimir\.rewrite\.MainActivity".*?(?:</activity>|/>)""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(declared)?.value
        checkNotNull(mainActivity) {
            "devlab lost MainActivity -- it is now as undriveable as `lab`, and pointless"
        }

        // Still safe: the activity itself must carry no way in.
        check("<intent-filter" !in mainActivity) {
            "devlab's MainActivity must have NO intent filters -- it is reachable only by explicit " +
                "component. Found: $mainActivity"
        }

        // And nothing anywhere in <application> may make this install launcher-facing.
        listOf(
            "android.intent.category.LAUNCHER",
            "android.intent.category.LEANBACK_LAUNCHER",
            "android.intent.category.HOME",
            "android.intent.category.SECONDARY_HOME",
        ).forEach { category ->
            check(category !in declared) {
                "devlab must never be a launcher/HOME candidate, but a declared component carries $category"
            }
        }
    }
}

tasks.named("check").configure {
    dependsOn(verifyLabIsolationManifest, verifyDevlabManifest)
}
