package com.gruning.mimir.rewrite

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The pin `MimirTunerBackend`'s own KDoc claims — **which did not exist until an adversarial review
 * pointed out that the doc cited it by name.**
 *
 * That doc says the shipping product's behaviour is byte-identical to before the indirection existed,
 * and that "`MimirTunerBackendDefaultsTest` pins that". It did not. A claim of the form *"a test
 * guarantees this"* is worse than no claim when the test is absent, because the next reader stops
 * looking. Writing it is the cheaper of the two honest options.
 */
class MimirTunerBackendDefaultsTest {

    @Test
    fun theDefaultsAreTheRealDeviceAndNothingInMainChangesThem() {
        // The seam exists so a lab axis can substitute on a debuggable build. On any other build the
        // three fields must still be the real kernel and the real privileged host.
        assertTrue(MimirTunerBackend.nodes() is RealMimirTunerNodes)
        // `host` and `hostAvailable` are deliberately NOT asserted by identity here. `PServerHost::run`
        // allocates a fresh function object at every evaluation, so `assertSame` against a second `::run`
        // compares two different wrappers around the same method and always fails — it did, on the first
        // run of this test, and the failure was the test's and not the code's. What actually guarantees
        // those two fields is the source scan below: nothing outside `src/debug` may assign them.
        assertTrue(
            "the lab axis must never be installed by default",
            MimirTunerBackend.nodes()::class.java.name.startsWith("com.gruning.mimir.rewrite.Real"),
        )
    }

    @Test
    fun theOnlyWriterOutsideTestsIsTheDebugOnlyLabInstaller() {
        // The safety argument is not "nobody assigns these", it is "only code absent from a release
        // build assigns these". That is checkable, and it is what makes the `@Volatile var`s safe.
        val roots = listOf(File("src/main/kotlin"), File("src/release/kotlin"))
        val offenders = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.mapNotNull { file ->
                val assigns = file.readLines()
                    .map { it.substringBefore("//") }
                    .filter { line ->
                        Regex("""MimirTunerBackend\.(nodes|host|hostAvailable)\s*=""").containsMatchIn(line)
                    }
                if (assigns.isEmpty()) null else file.path to assigns
            }
        }
        assertTrue(
            "only app/src/debug may rebind the tuner backend; found writers in main/release: $offenders",
            offenders.isEmpty(),
        )
    }
}
