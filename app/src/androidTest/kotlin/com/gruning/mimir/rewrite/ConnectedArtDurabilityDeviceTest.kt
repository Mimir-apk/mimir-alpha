package com.gruning.mimir.rewrite

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gruning.mimir.rewrite.core.model.posture.MimirConnectedArtGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Production-store crash/mismatch fixtures. Physical filesystem fault injection remains a later gate. */
@RunWith(AndroidJUnit4::class)
class ConnectedArtDurabilityDeviceTest {
    @Test
    fun restoreRequiresExactAccountRevisionAndProviderNeutralConsent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        reset(context)
        try {
            val binding = ArtworkCredentialStore.save(context, "member", "password")
            assertNotNull(binding)
            writeProviderNeutral(context, optedIn = true)
            writeCurrentProvider(context, "member", binding!!)

            ConnectedArtStore.restore(context)
            assertTrue(ConnectedArtStore.optedIn.value)
            assertEquals("member", ConnectedArtStore.connectedAccount.value)

            providerPreferences(context).edit()
                .putString(ConnectedArtKeyAccount, "other-member")
                .commit()
            MimirConnectedArtGate.setOpen(true)
            ConnectedArtStore.restore(context)
            assertFalse(ConnectedArtStore.optedIn.value)
            assertFalse(MimirConnectedArtGate.isOpen)

            writeCurrentProvider(context, "member", binding)
            writeProviderNeutral(context, optedIn = false)
            MimirConnectedArtGate.setOpen(true)
            ConnectedArtStore.restore(context)
            assertFalse(ConnectedArtStore.optedIn.value)
            assertFalse(MimirConnectedArtGate.isOpen)
        } finally {
            reset(context)
        }
    }

    @Test
    fun neutralConsentMigrationDistinguishesLegacyFromCurrentProviderRecords() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        reset(context)
        try {
            val legacyBinding = ArtworkCredentialStore.save(context, "legacy-member", "password")
            assertNotNull(legacyBinding)
            writeLegacyProvider(context, "legacy-member")

            ConnectedArtStore.restore(context)

            assertTrue(ConnectedArtStore.optedIn.value)
            assertTrue(ConnectedServicesStore.optedIn.value)
            assertEquals(
                legacyBinding,
                providerPreferences(context).getString(ConnectedArtKeyCredentialBinding, null),
            )

            reset(context)
            val incompleteBinding = ArtworkCredentialStore.save(context, "current-member", "password")
            assertNotNull(incompleteBinding)
            writeCurrentProvider(context, "current-member", incompleteBinding!!)

            ConnectedArtStore.restore(context)

            assertFalse(ConnectedArtStore.optedIn.value)
            assertFalse(ConnectedServicesStore.optedIn.value)
            assertFalse(MimirConnectedArtGate.isOpen)

            reset(context)
            val completeBinding = ArtworkCredentialStore.save(context, "current-member", "password")
            assertNotNull(completeBinding)
            writeCurrentProvider(context, "current-member", completeBinding!!)
            writeProviderNeutral(context, optedIn = true)

            ConnectedArtStore.restore(context)

            assertTrue(ConnectedArtStore.optedIn.value)
            assertTrue(ConnectedServicesStore.optedIn.value)
            assertEquals("current-member", ConnectedArtStore.connectedAccount.value)
        } finally {
            reset(context)
        }
    }

    @Test
    fun credentialUpgradeBeforeLegacyProviderMigrationResumesCloseFirst() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        reset(context)
        try {
            val binding = ArtworkCredentialStore.save(context, "legacy-member", "password")
            assertNotNull(binding)
            writeProviderNeutral(context, optedIn = true)
            assertTrue(
                providerPreferences(context).edit()
                    .remove(ConnectedArtKeyFormat)
                    .putBoolean(ConnectedArtKeyOptedIn, true)
                    .putString(ConnectedArtKeyAccount, "legacy-member")
                    .putBoolean(ConnectedArtKeyPreviewVideo, false)
                    .remove(ConnectedArtKeyCredentialBinding)
                    .commit(),
            )

            ConnectedArtStore.restore(context)

            assertTrue(ConnectedArtStore.optedIn.value)
            assertEquals(
                ConnectedArtRecordFormat,
                providerPreferences(context).getInt(ConnectedArtKeyFormat, -1),
            )
            assertEquals(
                binding,
                providerPreferences(context).getString(ConnectedArtKeyCredentialBinding, null),
            )
        } finally {
            reset(context)
        }
    }

    @Test
    fun currentProviderWithDeletedBindingClosesAnAlreadyOpenRuntimeGate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        reset(context)
        try {
            val binding = ArtworkCredentialStore.save(context, "member", "password")
            assertNotNull(binding)
            writeProviderNeutral(context, optedIn = true)
            writeCurrentProvider(context, "member", binding!!)
            ConnectedArtStore.restore(context)
            assertTrue(ConnectedArtStore.optedIn.value)

            assertTrue(
                providerPreferences(context).edit()
                    .remove(ConnectedArtKeyCredentialBinding)
                    .commit(),
            )
            MimirConnectedArtGate.setOpen(true)
            ConnectedArtStore.restore(context)

            assertFalse(ConnectedArtStore.optedIn.value)
            assertFalse(MimirConnectedArtGate.isOpen)
        } finally {
            reset(context)
        }
    }

    @Test
    fun malformedProviderOrNeutralRecordClosesBeforeReconciliation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        reset(context)
        try {
            val binding = ArtworkCredentialStore.save(context, "member", "password")
            assertNotNull(binding)
            writeProviderNeutral(context, optedIn = true)
            writeCurrentProvider(context, "member", binding!!)
            assertTrue(
                providerPreferences(context).edit()
                    .putString(ConnectedArtKeyFormat, "wrong-type")
                    .commit(),
            )
            MimirConnectedArtGate.setOpen(true)
            ConnectedArtStore.restore(context)
            assertFalse(ConnectedArtStore.optedIn.value)
            assertFalse(MimirConnectedArtGate.isOpen)

            reset(context)
            val freshBinding = ArtworkCredentialStore.save(context, "member", "password")
            assertNotNull(freshBinding)
            writeCurrentProvider(context, "member", freshBinding!!)
            assertTrue(
                connectedServicesPreferences(context).edit()
                    .putBoolean(KEY_CONNECTED_SERVICES_MIGRATED, true)
                    .putString(KEY_CONNECTED_SERVICES_OPTED_IN, "wrong-type")
                    .commit(),
            )
            MimirConnectedArtGate.setOpen(true)
            ConnectedArtStore.restore(context)
            assertFalse(ConnectedArtStore.optedIn.value)
            assertFalse(MimirConnectedArtGate.isOpen)
        } finally {
            reset(context)
        }
    }

    private fun writeCurrentProvider(
        context: Context,
        account: String,
        binding: String,
    ) {
        assertTrue(
            providerPreferences(context).edit()
                .putInt(ConnectedArtKeyFormat, ConnectedArtRecordFormat)
                .putBoolean(ConnectedArtKeyOptedIn, true)
                .putString(ConnectedArtKeyAccount, account)
                .putBoolean(ConnectedArtKeyPreviewVideo, false)
                .putString(ConnectedArtKeyCredentialBinding, binding)
                .commit(),
        )
    }

    private fun writeLegacyProvider(context: Context, account: String) {
        assertTrue(
            providerPreferences(context).edit()
                .remove(ConnectedArtKeyFormat)
                .putBoolean(ConnectedArtKeyOptedIn, true)
                .putString(ConnectedArtKeyAccount, account)
                .putBoolean(ConnectedArtKeyPreviewVideo, false)
                .remove(ConnectedArtKeyCredentialBinding)
                .commit(),
        )
    }

    private fun writeProviderNeutral(context: Context, optedIn: Boolean) {
        assertTrue(
            connectedServicesPreferences(context).edit()
                .putBoolean(KEY_CONNECTED_SERVICES_MIGRATED, true)
                .putBoolean(KEY_CONNECTED_SERVICES_OPTED_IN, optedIn)
                .commit(),
        )
    }

    private fun reset(context: Context) {
        ConnectedArtStore.disconnect(context)
        ArtworkCredentialStore.clear(context)
        providerPreferences(context).edit().clear().commit()
        connectedServicesPreferences(context).edit().clear().commit()
        MimirConnectedArtGate.setOpen(false)
    }

    private fun providerPreferences(context: Context) =
        context.getSharedPreferences(ConnectedArtPreferences, Context.MODE_PRIVATE)

    private fun connectedServicesPreferences(context: Context) =
        context.getSharedPreferences(CONNECTED_SERVICES_PREFERENCES, Context.MODE_PRIVATE)

    private companion object {
        const val CONNECTED_SERVICES_PREFERENCES = "mimir_rewrite_connected_services"
        const val KEY_CONNECTED_SERVICES_OPTED_IN = "connectedServicesOptedIn"
        const val KEY_CONNECTED_SERVICES_MIGRATED = "connectedServicesMigratedV1"
    }
}
