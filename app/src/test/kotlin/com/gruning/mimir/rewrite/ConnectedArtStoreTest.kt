package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedArtStoreTest {

    @Test
    fun gateOpensOnlyWhenOptedInBuildConfiguredAndCredentialsHealthy() {
        assertTrue(
            ConnectedArtStore.gateShouldOpen(
                optedIn = true,
                devConfigured = true,
                credentialsHealthy = true,
            ),
        )
        // Opt-in on an UNCONFIGURED build stays shut — never attempt a fetch that could only fail.
        assertFalse(
            ConnectedArtStore.gateShouldOpen(
                optedIn = true,
                devConfigured = false,
                credentialsHealthy = true,
            ),
        )
        // Configured build with no opt-in stays shut — off by default.
        assertFalse(
            ConnectedArtStore.gateShouldOpen(
                optedIn = false,
                devConfigured = true,
                credentialsHealthy = true,
            ),
        )
        assertFalse(
            ConnectedArtStore.gateShouldOpen(
                optedIn = true,
                devConfigured = true,
                credentialsHealthy = false,
            ),
        )
        assertFalse(
            ConnectedArtStore.gateShouldOpen(
                optedIn = true,
                devConfigured = true,
                credentialsHealthy = true,
                bindingMatches = false,
            ),
        )
        assertFalse(
            ConnectedArtStore.gateShouldOpen(
                optedIn = true,
                devConfigured = true,
                credentialsHealthy = true,
                storageAvailableForProcess = false,
            ),
        )
        assertFalse(
            ConnectedArtStore.gateShouldOpen(
                optedIn = true,
                devConfigured = true,
                credentialsHealthy = true,
                providerNeutralOptedIn = false,
            ),
        )
    }

    @Test
    fun `provider repository distinguishes complete legacy from damaged current records`() {
        val legacyStore = FakeConnectedArtRecordStore(
            record = ConnectedArtStoredRecord(
                malformed = false,
                format = null,
                optedIn = true,
                account = "member",
                previewVideoOptedIn = true,
                credentialBinding = null,
            ),
        )
        val legacyRepository = ConnectedArtActivationRepository(legacyStore)
        val legacy = legacyRepository.load()
        assertTrue(legacy is ConnectedArtRecordState.LegacyActive)

        val migrated = legacyRepository.activate("member", "bound-revision")
        assertTrue(migrated is ConnectedArtRecordState.Active)
        migrated as ConnectedArtRecordState.Active
        assertEquals("member", migrated.account)
        assertEquals("bound-revision", migrated.credentialBinding)
        assertTrue(migrated.previewVideoOptedIn)

        val damagedCurrent = ConnectedArtActivationRepository(
            FakeConnectedArtRecordStore(
                ConnectedArtStoredRecord(
                    malformed = false,
                    format = ConnectedArtRecordFormat,
                    optedIn = true,
                    account = "member",
                    previewVideoOptedIn = false,
                    credentialBinding = null,
                ),
            ),
        )
        assertSame(ConnectedArtRecordState.Unavailable, damagedCurrent.load())
    }

    @Test
    fun `provider repository rejects wrong format malformed types and partial inactive state`() {
        val records = listOf(
            ConnectedArtStoredRecord(false, 99, true, "member", false, "binding"),
            ConnectedArtStoredRecord(true, ConnectedArtRecordFormat, true, "member", false, "binding"),
            ConnectedArtStoredRecord(
                false,
                ConnectedArtRecordFormat,
                false,
                "stray-member",
                false,
                null,
            ),
            ConnectedArtStoredRecord(
                false,
                ConnectedArtRecordFormat,
                false,
                null,
                true,
                null,
            ),
        )
        records.forEach { record ->
            assertSame(
                record.toString(),
                ConnectedArtRecordState.Unavailable,
                ConnectedArtActivationRepository(FakeConnectedArtRecordStore(record)).load(),
            )
        }
    }

    @Test
    fun `provider activation and deactivation require exact persisted readback`() {
        val falseWrite = FakeConnectedArtRecordStore(writeActiveSucceeds = false)
        assertSame(
            ConnectedArtRecordState.Unavailable,
            ConnectedArtActivationRepository(falseWrite).activate("member", "binding"),
        )

        val corruptWrite = FakeConnectedArtRecordStore(corruptAfterActiveWrite = true)
        assertSame(
            ConnectedArtRecordState.Unavailable,
            ConnectedArtActivationRepository(corruptWrite).activate("member", "binding"),
        )

        val clearFailure = FakeConnectedArtRecordStore(
            record = currentActiveRecord(),
            writeInactiveSucceeds = false,
        )
        assertFalse(ConnectedArtActivationRepository(clearFailure).deactivate())

        clearFailure.writeInactiveSucceeds = true
        assertTrue(ConnectedArtActivationRepository(clearFailure).deactivate())
        assertSame(
            ConnectedArtRecordState.Inactive,
            ConnectedArtActivationRepository(clearFailure).load(),
        )
    }

    @Test
    fun `all durable records must agree on account binding and provider neutral consent`() {
        val provider = ConnectedArtRecordState.Active("member", "binding", false)
        val credential = ArtworkCredentialActivationSnapshot("member", "binding")
        assertTrue(connectedArtRecordsMatch(provider, credential, true))
        assertFalse(
            connectedArtRecordsMatch(
                provider,
                ArtworkCredentialActivationSnapshot("other-member", "binding"),
                true,
            ),
        )
        assertFalse(
            connectedArtRecordsMatch(
                provider,
                ArtworkCredentialActivationSnapshot("member", "other-binding"),
                true,
            ),
        )
        assertFalse(connectedArtRecordsMatch(provider, credential, false))
        assertFalse(connectedArtRecordsMatch(null, credential, true))
        assertFalse(connectedArtRecordsMatch(provider, null, true))
    }

    @Test
    fun `only a legacy provider may seed absent provider neutral consent`() {
        val legacy = ConnectedArtRecordState.LegacyActive(
            account = "member",
            previewVideoOptedIn = false,
        )
        val current = ConnectedArtRecordState.Active(
            account = "member",
            credentialBinding = "binding",
            previewVideoOptedIn = false,
        )

        assertTrue(providerCanSeedConnectedServicesMigration(legacy))
        assertTrue(
            resolveConnectedServicesOptIn(
                migrationComplete = false,
                storedOptIn = false,
                legacyScreenScraperOptIn = providerCanSeedConnectedServicesMigration(legacy),
            ),
        )
        assertFalse(providerCanSeedConnectedServicesMigration(current))
        assertFalse(
            resolveConnectedServicesOptIn(
                migrationComplete = false,
                storedOptIn = false,
                legacyScreenScraperOptIn = providerCanSeedConnectedServicesMigration(current),
            ),
        )
        assertTrue(
            resolveConnectedServicesOptIn(
                migrationComplete = true,
                storedOptIn = true,
                legacyScreenScraperOptIn = providerCanSeedConnectedServicesMigration(current),
            ),
        )
    }

    private fun currentActiveRecord(): ConnectedArtStoredRecord =
        ConnectedArtStoredRecord(
            malformed = false,
            format = ConnectedArtRecordFormat,
            optedIn = true,
            account = "member",
            previewVideoOptedIn = false,
            credentialBinding = "binding",
        )

    private class FakeConnectedArtRecordStore(
        var record: ConnectedArtStoredRecord = ConnectedArtStoredRecord(
            malformed = false,
            format = ConnectedArtRecordFormat,
            optedIn = false,
            account = null,
            previewVideoOptedIn = false,
            credentialBinding = null,
        ),
        var writeActiveSucceeds: Boolean = true,
        var writeInactiveSucceeds: Boolean = true,
        private val corruptAfterActiveWrite: Boolean = false,
    ) : ConnectedArtRecordStore {
        override fun read(): ConnectedArtStoredRecord = record

        override fun writeActive(account: String, credentialBinding: String): Boolean {
            if (!writeActiveSucceeds) return false
            record = ConnectedArtStoredRecord(
                malformed = false,
                format = ConnectedArtRecordFormat,
                optedIn = true,
                account = account,
                previewVideoOptedIn = record.previewVideoOptedIn,
                credentialBinding = if (corruptAfterActiveWrite) "wrong-binding" else credentialBinding,
            )
            return true
        }

        override fun setMaxThreads(value: Int?): Boolean {
            record = ConnectedArtStoredRecord(
                malformed = record.malformed,
                format = record.format,
                optedIn = record.optedIn,
                account = record.account,
                previewVideoOptedIn = record.previewVideoOptedIn,
                credentialBinding = record.credentialBinding,
                maxThreads = value,
            )
            return true
        }

        override fun writeInactive(): Boolean {
            if (!writeInactiveSucceeds) return false
            record = ConnectedArtStoredRecord(
                malformed = false,
                format = ConnectedArtRecordFormat,
                optedIn = false,
                account = null,
                previewVideoOptedIn = false,
                credentialBinding = null,
            )
            return true
        }

        override fun setPreviewVideo(enabled: Boolean): Boolean {
            record = ConnectedArtStoredRecord(
                malformed = record.malformed,
                format = record.format,
                optedIn = record.optedIn,
                account = record.account,
                previewVideoOptedIn = enabled,
                credentialBinding = record.credentialBinding,
            )
            return true
        }

        override fun snapshot(): ConnectedArtDurableSnapshot =
            ConnectedArtDurableSnapshot(
                CredentialPreferenceSnapshot(
                    buildMap {
                        record.format?.let { put("format", it) }
                        put("optedIn", record.optedIn)
                        record.account?.let { put("account", it) }
                        put("preview", record.previewVideoOptedIn)
                        record.credentialBinding?.let { put("binding", it) }
                    },
                ),
            )

        override fun restore(snapshot: ConnectedArtDurableSnapshot): Boolean {
            val values = snapshot.preferences.values
            record = ConnectedArtStoredRecord(
                malformed = false,
                format = values["format"] as? Int,
                optedIn = values["optedIn"] as? Boolean ?: false,
                account = values["account"] as? String,
                previewVideoOptedIn = values["preview"] as? Boolean ?: false,
                credentialBinding = values["binding"] as? String,
            )
            return true
        }
    }
}
