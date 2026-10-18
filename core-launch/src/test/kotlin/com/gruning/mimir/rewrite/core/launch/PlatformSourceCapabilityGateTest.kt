package com.gruning.mimir.rewrite.core.launch

import com.gruning.mimir.rewrite.core.metadata.online.OnlinePlatformProvider
import com.gruning.mimir.rewrite.core.metadata.online.OnlineProviderPlatformProjection
import com.gruning.mimir.rewrite.core.model.EmulatorCapability
import com.gruning.mimir.rewrite.core.model.LaunchLocatorForm
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.platform.IdentityNamespace
import com.gruning.mimir.rewrite.core.platform.PlatformRegistry
import com.gruning.mimir.rewrite.core.platform.PlatformRegistryLoader
import com.gruning.mimir.rewrite.core.platform.PlatformSourceAuthority
import com.gruning.mimir.rewrite.core.platform.PlatformSourceCapabilityEvaluator
import com.gruning.mimir.rewrite.core.platform.PlatformSourceCapabilityRow
import com.gruning.mimir.rewrite.core.platform.SupportClaimLevel
import com.gruning.mimir.rewrite.core.resolver.ExtensionPlatformClassifier
import com.gruning.mimir.rewrite.core.resolver.PlatformResolution
import com.gruning.mimir.rewrite.core.resolver.ResolutionInput
import java.io.File
import java.util.Locale
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One deterministic closure over platform authorities that otherwise evolve independently.
 *
 * This is deliberately an aggregate gate, not a replacement for each module's deeper tests. It loads the
 * real generated artifacts and composes public/read-only projections without changing runtime behavior.
 */
class PlatformSourceCapabilityGateTest {
    @Test
    fun crossAuthorityPlatformGraphIsClosedDeterministicAndCertificationDark() {
        val registryText = resourceText("mimir-platform-registry.v2.json")
        val certificationText = resourceText("mimir-platform-certification.v1.json")
        val loadResult = PlatformRegistryLoader.load(registryText, certificationText)
        assertTrue(loadResult.validationReport.issues.toString(), loadResult.validationReport.isValid)
        val registry = loadResult.requireRegistry()
        val registryRoot = Json.parseToJsonElement(registryText).jsonObject
        val certificationRoot = Json.parseToJsonElement(certificationText).jsonObject
        assertEquals("BEHAVIOR_DARK", registryRoot.getValue("behaviorPosture").jsonPrimitive.content)
        assertEquals(0, certificationRoot.getValue("certifications").jsonArray.size)

        val scannerRows = scannerRows(registry, registryRoot)
        val scannerEmissionByCanonical = scannerRows
            .groupBy { it.declaredCanonicalPlatformId }
            .mapValues { (canonical, rows) ->
                val emissions = rows.map { it.rawPlatformId }.toSet()
                assertEquals("$canonical has divergent scanner emissions", 1, emissions.size)
                emissions.single()
            }
        val providerRows = providerRows()
        assertProviderProjectionClosesAgainstScanner(providerRows, scannerEmissionByCanonical)

        val launchRows = launchRows(registry)
        val artRows = artRowsAndAssertManifestClosure(registry, scannerRows)
        val certificationRows = registry.platforms()
            .filter { registry.supportReport(it.id).highestClaimLevel == SupportClaimLevel.CERTIFIED }
            .map { platform ->
                PlatformSourceCapabilityRow(
                    authority = PlatformSourceAuthority.CERTIFICATION,
                    subjectId = "certification:${platform.id.value}",
                    rawPlatformId = platform.id.value,
                    declaredCanonicalPlatformId = platform.id.value,
                    claimLevel = SupportClaimLevel.CERTIFIED,
                )
            }
        val catalogRows = registry.platforms().map { platform ->
            PlatformSourceCapabilityRow(
                authority = PlatformSourceAuthority.REGISTRY,
                subjectId = "registry:${platform.id.value}",
                rawPlatformId = platform.id.value,
                declaredCanonicalPlatformId = platform.id.value,
                claimLevel = SupportClaimLevel.CATALOG_ONLY,
            )
        }
        val allRows = catalogRows + scannerRows + launchRows + providerRows + artRows + certificationRows
        val evaluator = PlatformSourceCapabilityEvaluator(registry)
        val report = evaluator.evaluate(allRows)
        val shuffled = evaluator.evaluate(allRows.shuffled(Random(1234)))

        assertTrue(
            report.issues.joinToString("\n") {
                "${it.code} ${it.authority} ${it.subjectId} ${it.canonicalPlatformId}: ${it.detail}"
            },
            report.isValid,
        )
        assertEquals(report.canonicalText(), shuffled.canonicalText())
        assertEquals(report.sha256(), shuffled.sha256())
        assertEquals(EXPECTED_REPORT_SHA256, report.sha256())
        assertEquals(EXPECTED_REPORT_ROW_COUNT, report.rows.size)
        // 225 -> 226 and 57 -> 58: PC Games landed in the registry via the earlier change before R-2b started;
        // both pins were left stale by that merge (same root cause, and the same fix, as the "57 -> 58"
        // pin in LaunchCoverageContractTest).
        assertEquals(226, registry.platforms().size)
        assertEquals(58, scannerRows.map { it.declaredCanonicalPlatformId }.toSet().size)
        assertEquals(0, certificationRows.size)
        assertEquals(
            0,
            report.stages.count { it.highestClaimLevel == SupportClaimLevel.CERTIFIED },
        )

        assertScopedPackageQueries()
        assertShippingObservationCount()
        assertExactEdenAndArmsx2Rows()

        val stageCounts = report.stages.groupingBy { it.highestClaimLevel.name }.eachCount().toSortedMap()
        println(
            "EP_PLAT01_REPORT sha256=${report.sha256()} rows=${report.rows.size} " +
                "platforms=${report.stages.size} stages=$stageCounts",
        )
    }

    private fun scannerRows(
        registry: PlatformRegistry,
        registryRoot: JsonObject,
    ): List<PlatformSourceCapabilityRow> {
        val profiles = registryRoot
            .getValue("collections").jsonObject
            .getValue("contentProfiles").jsonArray
        val extensionsByCanonical = linkedMapOf<String, MutableSet<String>>()
        profiles.forEach { element ->
            val profile = element.jsonObject
            val contract = profile["contentContract"] as? JsonObject ?: return@forEach
            val canonical = profile.getValue("platformId").jsonPrimitive.content
            contract.getValue("baseGameExtensions").jsonArray.forEach { extension ->
                extensionsByCanonical.getOrPut(canonical) { linkedSetOf() } += extension.jsonPrimitive.content
            }
        }

        val classifier = ExtensionPlatformClassifier(registry)
        return extensionsByCanonical.entries.sortedBy { it.key }.flatMap { (canonical, extensions) ->
            val definition = requireNotNull(registry.definition(PlatformId(canonical))) {
                "Profile target $canonical is absent from the registry"
            }
            val folderHint = ExtensionPlatformClassifier.platformHintFromFolder(
                definition.folder.canonical.normalizedSegment,
                registry,
            )
            assertNotNull("$canonical canonical folder does not produce a scanner hint", folderHint)
            val expectedEmission = emittedId(registry, PlatformId(canonical))
            extensions.sorted().map { extension ->
                val result = classifier.resolveWithContext(
                    ResolutionInput(fileName = "gate.$extension", extension = extension),
                    folderHint,
                )
                assertTrue(
                    "$canonical .$extension did not resolve through its canonical folder: $result",
                    result is PlatformResolution.Resolved,
                )
                val resolved = result as PlatformResolution.Resolved
                assertEquals(
                    "$canonical .$extension emitted a divergent platform id",
                    expectedEmission,
                    resolved.platformId,
                )
                PlatformSourceCapabilityRow(
                    authority = PlatformSourceAuthority.SCANNER,
                    subjectId = "scanner:$canonical:$extension",
                    rawPlatformId = resolved.platformId.value,
                    declaredCanonicalPlatformId = canonical,
                    claimLevel = SupportClaimLevel.DISCOVERABLE,
                )
            }
        }
    }

    private fun launchRows(registry: PlatformRegistry): List<PlatformSourceCapabilityRow> =
        EmulatorSeedRegistry.SEEDS.flatMap { seed ->
            seed.platforms.map { rawPlatform ->
                val canonical = uniqueCanonicalTarget(registry, rawPlatform.value) ?: rawPlatform.value
                val claimLevel = when (seed.capability) {
                    EmulatorCapability.DIRECT_PAYLOAD -> {
                        val exactCandidate = EmulatorSeedRegistry
                            .candidatesFor(rawPlatform) { packageName ->
                                packageName == seed.packageName
                            }
                            .firstOrNull { candidate ->
                                candidate.packageName == seed.packageName &&
                                    candidate.capability == EmulatorCapability.DIRECT_PAYLOAD
                            }
                        assertNotNull(
                            "${seed.packageName}/${rawPlatform.value} has no exact direct candidate",
                            exactCandidate,
                        )
                        assertTrue(
                            "${seed.packageName}/${rawPlatform.value} has no exact dispatch profile",
                            ShippingLaunchProfiles.isCandidateDispatchSupported(
                                rawPlatform,
                                requireNotNull(exactCandidate),
                            ),
                        )
                        SupportClaimLevel.LAUNCHABLE
                    }

                    EmulatorCapability.OPEN_ONLY,
                    EmulatorCapability.RECOGNIZE_ONLY,
                    -> SupportClaimLevel.RESOLVABLE
                }
                PlatformSourceCapabilityRow(
                    authority = PlatformSourceAuthority.LAUNCH,
                    subjectId = "launch:${seed.packageName}:${rawPlatform.value}",
                    rawPlatformId = rawPlatform.value,
                    declaredCanonicalPlatformId = canonical,
                    claimLevel = claimLevel,
                )
            }
        }.sortedBy { it.subjectId }

    private fun providerRows(): List<PlatformSourceCapabilityRow> =
        OnlineProviderPlatformProjection.rows().map { row ->
            PlatformSourceCapabilityRow(
                authority = when (row.provider) {
                    OnlinePlatformProvider.RETRO_ACHIEVEMENTS ->
                        PlatformSourceAuthority.RETRO_ACHIEVEMENTS

                    OnlinePlatformProvider.SCREEN_SCRAPER ->
                        PlatformSourceAuthority.SCREEN_SCRAPER

                    OnlinePlatformProvider.IGDB ->
                        PlatformSourceAuthority.IGDB
                },
                subjectId = "provider:${row.provider.name}:${row.rawPlatformId}:${row.providerKey}",
                rawPlatformId = row.rawPlatformId,
                declaredCanonicalPlatformId = row.declaredCanonicalPlatformId,
                claimLevel = SupportClaimLevel.RESOLVABLE,
            )
        }

    private fun assertProviderProjectionClosesAgainstScanner(
        providerRows: List<PlatformSourceCapabilityRow>,
        scannerEmissionByCanonical: Map<String, String>,
    ) {
        val projectedRows = OnlineProviderPlatformProjection.rows()
        projectedRows.groupBy { it.provider to it.rawPlatformId }.forEach { (key, rows) ->
            assertEquals("$key declares conflicting canonical targets", 1, rows.map { it.declaredCanonicalPlatformId }.toSet().size)
        }
        projectedRows.groupBy { it.provider to it.declaredCanonicalPlatformId }.forEach { (key, rows) ->
            assertEquals("$key declares conflicting provider keys", 1, rows.map { it.providerKey }.toSet().size)
            // Provider catalogs may intentionally know a platform whose content profile is still a
            // discovery GAP. The parity assertion applies whenever the scanner can actually emit it.
            val emitted = scannerEmissionByCanonical[key.second] ?: return@forEach
            when (key.first) {
                OnlinePlatformProvider.RETRO_ACHIEVEMENTS -> assertTrue(
                    "$key does not accept scanner emission $emitted",
                    OnlineProviderPlatformProjection.retroAchievementsSupports(key.second) &&
                        OnlineProviderPlatformProjection.retroAchievementsSupports(emitted),
                )

                OnlinePlatformProvider.SCREEN_SCRAPER -> assertEquals(
                    "$key does not preserve its system id for scanner emission $emitted",
                    rows.first().providerKey.toInt(),
                    OnlineProviderPlatformProjection.screenScraperSystemId(emitted),
                )

                OnlinePlatformProvider.IGDB -> assertEquals(
                    "$key does not preserve its IGDB id for scanner emission $emitted",
                    rows.first().providerKey.toInt(),
                    OnlineProviderPlatformProjection.igdbId(emitted),
                )
            }
        }
        assertEquals(
            projectedRows.size,
            providerRows.size,
        )
    }

    private fun artRowsAndAssertManifestClosure(
        registry: PlatformRegistry,
        scannerRows: List<PlatformSourceCapabilityRow>,
    ): List<PlatformSourceCapabilityRow> {
        val root = Json.parseToJsonElement(
            resourceText("platform-console-art/mimir-platform-console-art.v1.json"),
        ).jsonObject
        val aliases = root.getValue("aliases").jsonArray
        val aliasMap = aliases.associate { element ->
            val row = element.jsonObject
            row.getValue("normalizedId").jsonPrimitive.content to
                row.getValue("canonicalId").jsonPrimitive.content
        }
        assertEquals("art manifest has duplicate normalized aliases", aliases.size, aliasMap.size)

        val resolutions = root.getValue("canonicalResolutions").jsonArray
        val resolutionByCanonical = resolutions.associate { element ->
            val row = element.jsonObject
            row.getValue("id").jsonPrimitive.content to row.getValue("kind").jsonPrimitive.content
        }
        // 225 -> 226 and 125 -> 126: PC Games landed in the registry via the earlier change before R-2b started,
        // with a real, complete (not missing) art resolution already bundled for it -- confirmed by the
        // keys-equality assertion just below this block, which requires every registry platform
        // (including pc_games) to have a resolution entry. It resolves as GENERIC_FALLBACK (+1 there,
        // BUNDLED_VECTOR unchanged), same PR-the earlier update-stale-pin pattern as every other count in this file.
        // 100 -> 101 and 126 -> 125: Windows 98-style art replaces the generic PC platform fallback.
        // pc_games moves from GENERIC_FALLBACK to the Mimir-authored mimir_platform_icon_windows_98.
        assertEquals(226, resolutionByCanonical.size)
        assertEquals(101, resolutionByCanonical.values.count { it == "BUNDLED_VECTOR" })
        assertEquals(125, resolutionByCanonical.values.count { it == "GENERIC_FALLBACK" })
        assertEquals(registry.platforms().map { it.id.value }.toSet(), resolutionByCanonical.keys)

        registry.platforms().forEach { platform ->
            assertEquals(
                "canonical art alias ${platform.id.value}",
                platform.id.value,
                aliasMap[normalizeArtId(platform.id.value)],
            )
            platform.aliases.forEach { alias ->
                assertEquals(
                    "art alias ${alias.namespace}:${alias.normalizedValue}",
                    platform.id.value,
                    aliasMap[normalizeArtId(alias.normalizedValue)],
                )
            }
        }
        scannerRows.forEach { row ->
            assertEquals(
                "scanner art alias ${row.rawPlatformId}",
                row.declaredCanonicalPlatformId,
                aliasMap[normalizeArtId(row.rawPlatformId)],
            )
        }

        return resolutionByCanonical.entries.sortedBy { it.key }.map { (canonical, kind) ->
            PlatformSourceCapabilityRow(
                authority = PlatformSourceAuthority.BUNDLED_ART,
                subjectId = "art:$canonical:$kind",
                rawPlatformId = canonical,
                declaredCanonicalPlatformId = canonical,
                claimLevel = SupportClaimLevel.CATALOG_ONLY,
            )
        }
    }

    private fun assertScopedPackageQueries() {
        val manifest = File(requireNotNull(System.getProperty("mimir.appManifest"))).readText()
        val queryRows = Regex("""<package\s+android:name="([^"]+)"""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toList()
        val seedPackages = EmulatorSeedRegistry.allSeedPackages().toSet()
        val allowedExtras = setOf(
            "com.odin.settings",
            "com.rp.settings",
            // The isolated Mimir Tweaker APK: one exact companion package, bound explicitly by component.
            "com.gruning.mimir.tweaker",
        )

        assertEquals("duplicate literal package queries", queryRows.size, queryRows.toSet().size)
        // +3 each over 73/77: com.izzy2lost.psx2, com.armsx3, com.izzy2lost.x1box.
        // +1 each over 76/80: app.gamenative (R-2b, PC Games).
        assertEquals(77, seedPackages.size)
        assertEquals(80, queryRows.size)
        assertEquals(emptySet<String>(), seedPackages - queryRows.toSet())
        assertEquals(allowedExtras, queryRows.toSet() - seedPackages)
        assertFalse(manifest.contains("android.permission.QUERY_ALL_PACKAGES"))
    }

    private fun assertShippingObservationCount() {
        val support = Json.parseToJsonElement(resourceText("support-set-manifest.json")).jsonObject
        val rows = support.getValue("standalone").jsonObject.getValue("targetRows").jsonArray
        assertEquals(44, rows.size)
    }

    private fun assertExactEdenAndArmsx2Rows() {
        val eden = EmulatorSeedRegistry.SEEDS.filter { it.familyId == "eden" }
        assertEquals(
            setOf(
                "dev.eden.eden_emulator",
                "dev.eden.eden_emulator.nightly",
                "com.miHoYo.Yuanshen.nightly",
                "dev.legacy.eden_emulator",
                "dev.legacy.eden_emulator.nightly",
            ),
            eden.map { it.packageName }.toSet(),
        )
        assertEquals(5, eden.map { it.variant }.toSet().size)
        assertTrue(eden.all { it.capability == EmulatorCapability.DIRECT_PAYLOAD })
        assertTrue(eden.all { it.platforms == setOf(PlatformId.SWITCH) })
        assertTrue(eden.all { it.locatorForm == LaunchLocatorForm.CONTENT_URI })
        eden.forEach { seed ->
            val candidate = EmulatorSeedRegistry
                .candidatesFor(PlatformId.SWITCH) { packageName -> packageName == seed.packageName }
                .first { it.packageName == seed.packageName }
            assertTrue(
                "${seed.packageName}/switch lacks exact dispatch",
                ShippingLaunchProfiles.isCandidateDispatchSupported(PlatformId.SWITCH, candidate),
            )
        }

        val armsx2 = EmulatorSeedRegistry.SEEDS.filter { it.familyId == "armsx2" }
        assertEquals(setOf("com.armsx2", "come.nanodata.armsx2"), armsx2.map { it.packageName }.toSet())
        assertTrue(armsx2.all { it.capability == EmulatorCapability.DIRECT_PAYLOAD })
        assertTrue(armsx2.all { it.platforms == setOf(PlatformId.PS2) })
        assertTrue(armsx2.all { it.locatorForm == LaunchLocatorForm.FILE_PATH })
        armsx2.forEach { seed ->
            val candidate = EmulatorSeedRegistry
                .candidatesFor(PlatformId.PS2) { packageName -> packageName == seed.packageName }
                .first { it.packageName == seed.packageName }
            assertTrue(
                "${seed.packageName}/ps2 lacks exact dispatch",
                ShippingLaunchProfiles.isCandidateDispatchSupported(PlatformId.PS2, candidate),
            )
        }
        assertFalse(EmulatorSeedRegistry.allSeedPackages().contains("come.nanodata.armsx2.debug"))
    }

    private fun emittedId(registry: PlatformRegistry, canonical: PlatformId): PlatformId =
        registry.definition(canonical)
            ?.aliases
            ?.firstOrNull { it.namespace == IdentityNamespace.REWRITE_LEGACY }
            ?.normalizedValue
            ?.let(::PlatformId)
            ?: canonical

    private fun uniqueCanonicalTarget(registry: PlatformRegistry, rawPlatformId: String): String? =
        IdentityNamespace.values()
            .mapNotNullTo(linkedSetOf()) { namespace ->
                registry.canonicalize(namespace, rawPlatformId)?.value
            }
            .singleOrNull()

    private fun normalizeArtId(raw: String): String =
        raw.trim().lowercase(Locale.US).replace(" ", "").replace("-", "").replace("_", "")

    private fun resourceText(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "Missing test resource $name"
        }.bufferedReader().use { it.readText() }

    private companion object {
        // The platform-surface count grew from 1_052 to 1_060 with PC Games coverage:
        // +1 launchRows for app.gamenative targeting pc_games (DIRECT_PAYLOAD/LAUNCHABLE);
        // +1 catalogRows for pc_games, one per registry.platforms() entry;
        // +5 scannerRows for steam/epic/gog/amazon/pcgame;
        // +1 artRows for pc_games' complete art resolution.
        // The +7 non-launch rows were already present when the launch route was added.
        //
        // Adding IgdbPlatformMap to OnlineProviderPlatformProjection.rows() grew the count
        // from 1_060 to 1_061 and changed the digest: its only entry is pc_games, so only one
        // provider row was added. PlatformSourceAuthority.IGDB is RESOLVABLE, matching its
        // two sibling online providers. Changes to this provider surface require review.
        //
        // Replacing the generic pc_games art with the bundled Windows 98 vector changed
        // art:pc_games:GENERIC_FALLBACK to art:pc_games:BUNDLED_VECTOR without changing
        // the 1_061 count. The digest moved from 58b72bc6...f614 to the value below;
        // see artRowsAndAssertManifestClosure.
        const val EXPECTED_REPORT_ROW_COUNT = 1_061
        const val EXPECTED_REPORT_SHA256 =
            "8c75fb62ba1cc6257cacaa25d09ba357f7a98ff8e6bc82838fcc049f023b5f20"
    }
}
