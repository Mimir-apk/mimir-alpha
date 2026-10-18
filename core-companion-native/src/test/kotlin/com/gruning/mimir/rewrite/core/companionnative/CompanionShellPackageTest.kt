package com.gruning.mimir.rewrite.core.companionnative

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionShellPackageTest {
    @Test
    fun `homes, launchers and SystemUI are shells and apps are not`() {
        listOf(
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.cocoon.shell",
            "com.cocoon.launcher",
            "com.android.systemui",
        ).forEach { assertTrue(it, isCompanionShellPackage(it)) }
        listOf("com.android.chrome", "com.android.settings", "com.gruning.mimir.rewrite", "").forEach {
            assertFalse(it, isCompanionShellPackage(it))
        }
    }
}
