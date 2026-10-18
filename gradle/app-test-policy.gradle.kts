import org.gradle.api.file.FileTreeElement
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

// CI test-class selection (-Pmimir.ci.testShard and -Pmimir.ci.excludeTests, in testOptions below): the OUTER
// class's path for a class file, without any $inner suffix or .class, so an inner or generated class always
// follows its outer class. Null for a directory or any other file, which is never excluded.
fun outerClassPath(element: FileTreeElement): String? =
    if (element.isDirectory || !element.name.endsWith(".class")) {
        null
    } else {
        element.relativePath.pathString.substringBefore('$').removeSuffix(".class")
    }

tasks.withType<Test>().configureEach {
    // MainActivity.onResume's Tweaker settlement gate cannot be reached from a unit test,
    // so CompanionPrimaryResumePolicyTest reads the call site as source instead. Same
    // approach core-launch already uses for the app manifest (`mimir.appManifest`).
    systemProperty(
        "mimir.mainActivitySource",
        rootProject.file("app/src/main/kotlin/com/gruning/mimir/rewrite/MainActivity.kt")
            .absolutePath,
    )
    // Same shape, for DebugNavigationHooksTest: MimirAppCoordinator's DebugNavigationBridge
    // consumer lives inside a 2,000+ line composable a unit test cannot reach either — it can
    // only read the wiring as source.
    systemProperty(
        "mimir.mimirAppCoordinatorSource",
        rootProject.file("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppCoordinator.kt")
            .absolutePath,
    )
    // AlphaVariantCredentialBoundaryTest reads this build script as source: a debug unit test sees only the
    // debug BuildConfig, so the beta build type's key can only be pinned by reading its declaration.
    systemProperty(
        "mimir.appBuildScript",
        rootProject.file("app/build.gradle.kts").absolutePath,
    )
    // CI only: .github/workflows/android-build.yml's app-unit job passes
    // -Pmimir.ci.maxParallelForks=1 so a 2 vCPU / 7 GB runner never holds several Robolectric
    // test JVMs beside the Gradle daemon. Unset everywhere else, so a workstation run keeps
    // Gradle's own default.
    providers.gradleProperty("mimir.ci.maxParallelForks").orNull?.toIntOrNull()?.let { forks ->
        maxParallelForks = forks
    }
    // CI only: the app-unit job runs :app's tests as n parallel jobs, each passing
    // -Pmimir.ci.testShard=i/n (0 <= i < n) to run only the classes that hash to shard i. The
    // hash is the outer class's path (package and name, without any $inner suffix or .class),
    // String.hashCode being specified and so stable, which keeps an inner or generated class
    // in its outer class's shard. A malformed value fails the build: a typo must not run every
    // test in every shard. Unset everywhere else, so nothing is excluded.
    providers.gradleProperty("mimir.ci.testShard").orNull?.let { spec ->
        val shard = Regex("""(\d+)/(\d+)""").matchEntire(spec.trim())
        val shardIndex = shard?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val shardCount = shard?.groupValues?.get(2)?.toIntOrNull() ?: 0
        require(shardCount > 0 && shardIndex in 0 until shardCount) {
            "mimir.ci.testShard must be i/n with 0 <= i < n, but is '$spec'"
        }
        exclude(
            Spec<FileTreeElement> { element ->
                // Only a class file picks a shard; a directory is never excluded.
                val outerClass = outerClassPath(element)
                outerClass != null && Math.floorMod(outerClass.hashCode(), shardCount) != shardIndex
            },
        )
    }
    // CI only: -Pmimir.ci.excludeTests=<fully qualified class names, comma or space separated> drops
    // those classes, inner and generated ones with them, from the scan, by the same outer-class
    // path the shard uses. The 4 app-unit shards pass the workflow's SLOW_APP_TESTS here, so they
    // never run a class that needs its own job. Unset everywhere else, so nothing is excluded.
    val excludedClasses = providers.gradleProperty("mimir.ci.excludeTests").orNull.orEmpty()
        .replace(',', ' ')
        .split(' ')
        .map { name -> name.trim() }
        .filter { name -> name.isNotEmpty() }
        .toSet()
    if (excludedClasses.isNotEmpty()) {
        exclude(
            Spec<FileTreeElement> { element ->
                val outerClass = outerClassPath(element)
                outerClass != null && outerClass.replace('/', '.') in excludedClasses
            },
        )
    }
    // CI only: the app-unit job passes -Pmimir.ci.logTestEvents=true so its log names every test as
    // it starts and ends. A shard that dies at its time limit writes no XML, and the last STARTED
    // line is then the only record of the class that was running. No standard streams, so the log
    // stays short enough to read.
    if (providers.gradleProperty("mimir.ci.logTestEvents").orNull == "true") {
        testLogging {
            events(TestLogEvent.STARTED, TestLogEvent.PASSED, TestLogEvent.FAILED, TestLogEvent.SKIPPED)
            exceptionFormat = TestExceptionFormat.SHORT
            showStandardStreams = false
        }
    }
}
