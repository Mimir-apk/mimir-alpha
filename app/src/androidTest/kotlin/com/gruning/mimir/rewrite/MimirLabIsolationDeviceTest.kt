package com.gruning.mimir.rewrite

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gruning.mimir.rewrite.core.storage.MimirDatabase
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MimirLabIsolationDeviceTest {
    @Suppress("DEPRECATION")
    @Test
    fun targetHasSeparateUidInertApplicationAndNoProductStartupProviders() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext
        val packageManager = context.packageManager

        assertEquals("com.gruning.mimir.rewrite.lab", context.packageName)
        assertEquals("com.gruning.mimir.rewrite.lab.test", instrumentation.context.packageName)
        assertTrue(application is MimirLabApplication)
        assertEquals(context.applicationInfo.uid, Process.myUid())
        assertTrue(context.applicationInfo.dataDir.contains("com.gruning.mimir.rewrite.lab"))

        val labPackage = packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PROVIDERS or PackageManager.GET_ACTIVITIES,
        )
        assertNull(labPackage.sharedUserId)
        val providerNames = labPackage.providers.orEmpty().map { it.name }.toSet()
        assertFalse("Startup provider must be absent: $providerNames",
            "androidx.startup.InitializationProvider" in providerNames)
        assertFalse("Shizuku provider must be absent: $providerNames",
            "rikka.shizuku.ShizukuProvider" in providerNames)
        assertFalse("Launch FileProvider must be absent: $providerNames",
            "androidx.core.content.FileProvider" in providerNames)
        assertEquals(
            setOf(MimirLabRecoveryDocumentsProvider::class.java.name),
            providerNames,
        )

        val activityNames = labPackage.activities.orEmpty().map { it.name }.toSet()
        assertFalse(MainActivity::class.java.name in activityNames)
        assertFalse(CompanionReturnActivity::class.java.name in activityNames)
        assertFalse(PrimaryTransferActivity::class.java.name in activityNames)

        val owner = runCatching {
            packageManager.getPackageInfo("com.gruning.mimir.rewrite", 0)
        }.getOrNull()
        if (isEmulator()) {
            owner?.let {
                assertNull(it.sharedUserId)
                assertNotEquals(it.applicationInfo!!.uid, context.applicationInfo.uid)
            }
        } else {
            assertNotNull("Owner package must be present for physical-device D0.", owner)
            assertNull(owner!!.sharedUserId)
            assertNotEquals(owner.applicationInfo!!.uid, context.applicationInfo.uid)
        }

        assertFalse(context.getDatabasePath(MimirDatabase.DB_NAME).exists())
        assertFalse(context.getDatabasePath("androidx.work.workdb").exists())

        val preferenceName = "ep06a_lab_isolation_probe"
        val preferenceFile = File(
            context.applicationInfo.dataDir,
            "shared_prefs/$preferenceName.xml",
        )
        context.deleteSharedPreferences(preferenceName)
        assertFalse(preferenceFile.exists())
        val labPreferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        try {
            assertTrue(labPreferences.edit().putString("scope", context.packageName).commit())
            assertEquals(context.packageName, labPreferences.getString("scope", null))
            assertTrue(preferenceFile.exists())
        } finally {
            context.deleteSharedPreferences(preferenceName)
        }
        assertFalse(preferenceFile.exists())

        val dataRoot = File(context.applicationInfo.dataDir).canonicalFile
        assertTrue(context.filesDir.canonicalFile.toPath().startsWith(dataRoot.toPath()))
        assertTrue(context.cacheDir.canonicalFile.toPath().startsWith(dataRoot.toPath()))
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for") ||
            Build.MODEL.startsWith("sdk_gphone") ||
            Build.HARDWARE == "goldfish" ||
            Build.HARDWARE == "ranchu"
}
