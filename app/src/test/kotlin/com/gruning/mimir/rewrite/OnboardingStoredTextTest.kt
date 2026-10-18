package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.After
import org.robolectric.shadows.ShadowLooper

/**
 * The settlement reason and the scan failure are stored as the English sentence that was written, so a checkpoint from an earlier
 * build keeps working. The screen resolves a stored sentence it knows to the player's language ([OnboardingStoredText]); one it does
 * not know (a failure's own message) shows as it is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OnboardingStoredTextTest {
    /**
     * This class runs in the shared sdk 34 Robolectric sandbox, and the copy it reads can write Compose snapshot state outside any
     * composition. Apply those writes and run what they posted, or a later Compose test in the sandbox fails "Compose did not get
     * idle" (CompanionThemeSeedTest, MimirTrackpadPointerStyleTest).
     */
    @After
    fun applyPendingSnapshotWrites() {
        Snapshot.sendApplyNotifications()
        ShadowLooper.idleMainLooper()
    }

    private val english = onboardingTestResources()
    private val spanish = onboardingTestResourcesFor("es-419")

    @Test
    fun `every stored sentence is exactly the English resource it resolves to`() {
        (OnboardingStoredText.settlementReasons + OnboardingStoredText.scanFailures).forEach { entry ->
            assertEquals(entry.stored, english.getString(entry.textRes))
        }
    }

    @Test
    fun `every distinct reason the coordinator can write has an entry`() {
        val written = listOf(
            settlementReasonForEnrichment(converged = false, connectedDetails = false),
            settlementReasonForEnrichment(converged = false, connectedDetails = true, steamGridDbKey = true),
            settlementReasonForEnrichment(converged = true, connectedDetails = true, steamGridDbKey = false),
            settlementReasonForEnrichment(converged = true, connectedDetails = true, steamGridDbKey = true),
            settlementReasonForEnrichment(converged = true, connectedDetails = false, steamGridDbKey = false),
            settlementReasonForEnrichment(converged = true, connectedDetails = false, steamGridDbKey = true),
        ).toSet()
        val known = OnboardingStoredText.settlementReasons.map { it.stored }.toSet()
        assertEquals(emptySet<String>(), written - known)
    }

    @Test
    fun `a stored sentence reads in the language of the screen`() {
        val stored = settlementReasonForEnrichment(converged = true, connectedDetails = false)
        assertEquals(stored, OnboardingStoredText.settlementReasonText(english, stored))
        assertEquals(
            "Mimir pregunta antes de descargar ilustraciones.",
            OnboardingStoredText.settlementReasonText(spanish, stored),
        )
        assertEquals(
            "No se conservó el acceso a la carpeta. Elige la carpeta de nuevo.",
            OnboardingStoredText.scanFailureText(spanish, OnboardingScanWorker.MissingReadGrant),
        )
    }

    @Test
    fun `a stored value nobody here wrote shows as written`() {
        assertEquals("Disk full", OnboardingStoredText.scanFailureText(spanish, "Disk full"))
        assertEquals("old settlement failure", OnboardingStoredText.settlementReasonText(spanish, "old settlement failure"))
    }

    @Test
    fun `the settlement screen paints the stored reason in Spanish and the fixed lines in Spanish too`() {
        val ready = onboardingSettlementUi(
            spanish,
            checkpoint = OnboardingCheckpoint(
                scanStatus = OnboardingScanStatus.COMPLETE,
                settlementStatus = OnboardingSettlementStatus.READY,
                settlementReason = settlementReasonForEnrichment(converged = true, connectedDetails = false, steamGridDbKey = true),
            ),
            resolvingCount = 0,
            identityFinalizing = false,
        )
        assertEquals("Tu biblioteca está lista", ready.title)
        assertEquals("Con tu clave de SteamGridDB, las ilustraciones del estante de Inicio se descargan solas.", ready.detail)
        val identifying = onboardingSettlementUi(
            spanish,
            checkpoint = OnboardingCheckpoint(scanStatus = OnboardingScanStatus.COMPLETE),
            resolvingCount = 5,
            identityFinalizing = false,
        )
        assertEquals("Quedan 5 juegos por revisar.", identifying.detail)
        assertNotEquals(
            onboardingSettlementUi(
                english,
                checkpoint = OnboardingCheckpoint(scanStatus = OnboardingScanStatus.COMPLETE),
                resolvingCount = 5,
                identityFinalizing = false,
            ).detail,
            identifying.detail,
        )
    }
}
