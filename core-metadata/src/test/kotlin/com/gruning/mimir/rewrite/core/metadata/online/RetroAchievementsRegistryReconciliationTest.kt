package com.gruning.mimir.rewrite.core.metadata.online

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps the RA product audit bound to the current shipping registry denominator. */
class RetroAchievementsRegistryReconciliationTest {
    @Test
    fun shippingRuntimeAndRetroArchPlatformsHaveOneExplicitRaTruth() {
        val (shippingRuntimeIds, retroArchRegistrationIds) = registryProductPlatformIds()
        val registryIds = shippingRuntimeIds.toSet() + retroArchRegistrationIds.toSet()
        val mappedIds = RetroAchievementsConsoleMap.mappedProductPlatformIds
        val explicitlyUnsupportedIds = setOf(
            "3ds",
            "atari_5200",
            "atari_8_bit_family",
            "commodore_64",
            "commodore_amiga",
            "commodore_vic_20",
            "dos",
            "ps3",
            "scummvm",
            "sinclair_zx_spectrum",
            "switch",
            "tic_80",
            "xbox",
            "xbox360",
        )

        assertEquals(21, shippingRuntimeIds.size)
        assertEquals(shippingRuntimeIds.size, shippingRuntimeIds.toSet().size)
        assertEquals(49, retroArchRegistrationIds.size)
        assertEquals(retroArchRegistrationIds.size, retroArchRegistrationIds.toSet().size)
        assertEquals(59, registryIds.size)
        assertEquals(45, mappedIds.size)
        assertEquals(14, explicitlyUnsupportedIds.size)
        assertTrue(mappedIds.intersect(explicitlyUnsupportedIds).isEmpty())
        assertEquals(
            "Every current shipping/RetroArch platform must be mapped or explicitly unsupported",
            registryIds,
            mappedIds + explicitlyUnsupportedIds,
        )
        mappedIds.forEach { assertTrue(it, RetroAchievementsConsoleMap.supportsPlatform(it)) }
        explicitlyUnsupportedIds.forEach {
            assertTrue("$it must remain explicitly unsupported", !RetroAchievementsConsoleMap.supportsPlatform(it))
        }
    }

    private fun registryProductPlatformIds(): Pair<List<String>, List<String>> {
        val registryPath = checkNotNull(System.getProperty("mimir.retroAchievements.registry")) {
            "generated Mimir platform registry test input is missing"
        }
        val registry = File(registryPath)
            .bufferedReader()
            .use { Json.parseToJsonElement(it.readText()).jsonObject }
        val collections = checkNotNull(registry["collections"]).jsonObject
        val shippingRuntimeIds = checkNotNull(collections["shippingRuntimeSystems"])
            .jsonArray
            .map { row ->
                checkNotNull(row.jsonObject["platformId"]).jsonPrimitive.content
            }
        val retroArchRegistrationIds = checkNotNull(collections["retroArchRegistrations"])
            .jsonArray
            .map { row ->
                checkNotNull(row.jsonObject["externalId"])
                    .jsonObject
                    .getValue("value")
                    .jsonPrimitive
                    .content
            }
        return shippingRuntimeIds to retroArchRegistrationIds
    }
}
