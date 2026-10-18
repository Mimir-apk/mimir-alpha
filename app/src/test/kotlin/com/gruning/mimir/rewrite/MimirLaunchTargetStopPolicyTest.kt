package com.gruning.mimir.rewrite

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MimirLaunchTargetStopPolicyTest {
    private lateinit var previousProvider: () -> Set<String>

    @Before
    fun resetProvider() {
        previousProvider = MimirLaunchTargetStopPolicy.sessionAllowedPackages
        MimirLaunchTargetStopPolicy.sessionAllowedPackages = { emptySet() }
    }

    @After
    fun restoreProvider() {
        MimirLaunchTargetStopPolicy.sessionAllowedPackages = previousProvider
    }

    @Test
    fun `emulator package is allowed`() {
        assertTrue(MimirLaunchTargetStopPolicy.isAllowed("com.retroarch"))
    }

    @Test
    fun `other apps require the session provider`() {
        val packageName = "com.example.sessionapp"
        assertFalse(MimirLaunchTargetStopPolicy.isAllowed(packageName))
        MimirLaunchTargetStopPolicy.sessionAllowedPackages = { setOf(packageName) }
        assertTrue(MimirLaunchTargetStopPolicy.isAllowed(packageName))
    }

    @Test
    fun `own package is refused even when listed`() {
        MimirLaunchTargetStopPolicy.sessionAllowedPackages = { setOf(BuildConfig.APPLICATION_ID) }
        assertFalse(MimirLaunchTargetStopPolicy.isAllowed(BuildConfig.APPLICATION_ID))
    }

    @Test
    fun `bad syntax is refused even when listed`() {
        val invalid = setOf("", "not a package", "com.example;id", "a".repeat(256))
        MimirLaunchTargetStopPolicy.sessionAllowedPackages = { invalid }
        invalid.forEach { assertFalse(MimirLaunchTargetStopPolicy.isAllowed(it)) }
    }
}
