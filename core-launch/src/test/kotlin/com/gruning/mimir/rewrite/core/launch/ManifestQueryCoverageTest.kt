package com.gruning.mimir.rewrite.core.launch

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestQueryCoverageTest {
    @Test
    fun everySeedPackageIsVisibleThroughScopedQueries() {
        val manifest = File(requireNotNull(System.getProperty("mimir.appManifest"))).readText()
        val queriedPackages = Regex("""<package\s+android:name="([^"]+)"""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toSet()
        val missing = EmulatorSeedRegistry.allSeedPackages().filterNot(queriedPackages::contains)

        assertTrue("emulator seed packages missing from app <queries>: $missing", missing.isEmpty())
    }
}
