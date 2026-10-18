#!/usr/bin/env node

import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import { validateJsonSchema } from "./json-schema-lite.mjs";

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(SCRIPT_DIR, "../..");
const GENERATOR_PATH = "tools/platform-registry/generate-platform-registry.mjs";
const EXPECTED_GENERATOR_SEMANTICS_VERSION = "mimirPlatformRegistryGeneratorSemantics.v3";
const CATALOG_RELATIONS = Object.freeze([
  "CANONICAL",
  "CONTENT_VARIANT",
  "HARDWARE_VARIANT",
  "PRESENTATION_ALIAS",
  "REGIONAL_VARIANT",
  "SOFTWARE_FAMILY",
]);

const PATHS = Object.freeze({
  certification: "platforms/mimir-platform-certification.v1.json",
  artManifest: "core-ui/src/main/assets/platform-console-art/mimir-platform-console-art.v1.json",
  certificationSchema: "platforms/schema/mimir-platform-certification.v1.schema.json",
  nativePlatforms: "platforms/sources/mimir-native-platforms.json",
  nativePlatformsSchema: "platforms/schema/mimir-native-platforms.v1.schema.json",
  registry: "platforms/mimir-platform-registry.v2.json",
  registrySchema: "platforms/schema/mimir-platform-registry.v2.schema.json",
  relationFixture: "tools/platform-registry/fixtures/shipping-catalog-relations.v1.json",
  relationFixtureSchema: "platforms/schema/mimir-shipping-catalog-relations.v1.schema.json",
  sourceLock: "platforms/mimir-platform-sources.lock.json",
  sourceLockSchema: "platforms/schema/mimir-platform-sources-lock.v1.schema.json",
  supportSet: "platforms/support-set-manifest.json",
  supportSetSchema: "platforms/schema/mimir-platform-support-set-manifest.v1.schema.json",
});

const NATIVE_NAMESPACE = "mimir-native";

const EXPECTED = Object.freeze({
  catalogEntries: 403,
  // The shipping-catalog canonical count only. Frozen against the closed-world relation fixture;
  // never bump this for a Mimir-native platform, which is not a catalog identity at all.
  canonicalPlatforms: 225,
  contentVariants: 37,
  hardwareVariants: 137,
  presentationAliases: 3,
  regionalVariants: 1,
  softwareFamilies: 0,
  importBindings: 106,
  relationFixtures: 178,
  retroArchCoreRegistrations: 116,
  retroArchDiscoverable: 39,
  retroArchDiscoveryGap: 10,
  retroArchIds: 49,
  runtimeSystems: 21,
  standaloneEntries: 12,
  standalonePackages: 43,
  standaloneTargetRows: 44,
  visualAssets: 403,
  bundledConsoleVectors: 100,
  // Icons Mimir draws itself, for a platform no BAXY console fits; see MIMIR_AUTHORED_VECTORS.
  mimirAuthoredVectors: 1,
  // canonicalPlatforms + today's Mimir-native platform count (1: PC Games). Bump alongside a
  // reviewed native-platform addition/removal; never conflate with canonicalPlatforms above.
  totalPlatforms: 226,
  // importBindings (106, the shipping BOUND denominator) + today's native BOUND profile count (1).
  totalContentProfiles: 117,
  // importBindings (106) + today's native import-binding count (1) — see `registry-counts`.
  totalImportBindings: 107,
});

const EXPECTED_SET_COUNTS = Object.freeze({
  A287_CATALOG_ONLY: 287,
  B21: 21,
  D59_DISCOVERY_ONLY: 59,
  DR47_DISCOVERY_RUNTIME: 47,
  R10_DISCOVERY_GAP: 10,
  R39_DISCOVERABLE: 39,
  R49: 49,
});

const EXPECTED_SOURCE_REVISION = "b34752d0290ef1a34199aa6c6dd4258646fe4c06";
const EXPECTED_SOURCE_HASHES = Object.freeze({
  "shipping-android-bridge": "876d50b9c5552c6c67e14f30d3e5aef0ebdcf2e1c48622019f48348f0fb64ee0",
  "shipping-platform-catalog": "f2ea34810476db28d1583b84869ea6ef89c67fac66b61bee28a7568fbb16ad56",
  "shipping-retroarch-registry": "64d7fe9f06f2dba41de1b003224270f90840a2fd727aa5f5659a7a98ae8df7a2",
  "shipping-runtime-js": "2e5d7c25e104c52a0cb1b415d409e9a44088d292dfe87eac73a238e40663981c",
});
const EXPECTED_RELATION_FIXTURE_SHA256 = "bc8e769312f97845c3accd778142411911679e805d1a8bf42b6baf5c54423461";
const ART_ROOT = "core-ui/src/main/assets/platform-console-art";
// The Mimir-authored platform icons, each checked against the script that authors it. The same table lives in
// generate-platform-console-art.mjs (MIMIR_AUTHORED_VECTOR_TARGETS); an entry must be added to both.
const MIMIR_AUTHORED_VECTORS = Object.freeze({
  //
  mimir_platform_icon_windows_98: {
    canonicalId: "pc_games",
    sourcePath: "tools/platform-registry/author-windows-98-flag.py",
  },
});
const ART_FALLBACK_ID = "generic-console-v1";
const ADVERSARIAL_RELATION_FIXTURES = Object.freeze([
  Object.freeze(["nintendo_wii_rvl_201", "nintendo_wii", "HARDWARE_VARIANT"]),
  Object.freeze(["sony_playstation_2_scph_70000", "ps2", "HARDWARE_VARIANT"]),
  Object.freeze(["neo_geo", "snk_neo_geo", "CONTENT_VARIANT"]),
  Object.freeze(["nintendo_switch_hac_001", "switch", "HARDWARE_VARIANT"]),
  Object.freeze(["nintendo_e_reader", "gba", "HARDWARE_VARIANT"]),
  Object.freeze(["sony_playstation_portable_psx2psp", "ps1", "CONTENT_VARIANT"]),
  Object.freeze(["apple_macintosh_beta", "apple_macintosh", "CONTENT_VARIANT"]),
  Object.freeze(["mattel_hyperscan_alt", "mattel_hyperscan", "PRESENTATION_ALIAS"]),
  Object.freeze(["sony_playstation_scph_100_131_flat", "ps1", "PRESENTATION_ALIAS"]),
  Object.freeze(["vtech_socrates_alt", "vtech_socrates", "PRESENTATION_ALIAS"]),
  Object.freeze(["sega_mega_drive_genesis_kr", "genesis", "REGIONAL_VARIANT"]),
]);

function deepSort(value) {
  if (Array.isArray(value)) return value.map(deepSort);
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map((key) => [key, deepSort(value[key])]),
    );
  }
  return value;
}

function canonicalJson(value) {
  return JSON.stringify(deepSort(value));
}

function prettyJson(value) {
  return `${JSON.stringify(deepSort(value), null, 2)}\n`;
}

function sha256Bytes(value) {
  return createHash("sha256").update(value).digest("hex");
}

function digest(value) {
  return sha256Bytes(Buffer.from(canonicalJson(value), "utf8"));
}

function sortedUnique(values) {
  return [...new Set(values)].sort((left, right) => left.localeCompare(right));
}

function sameValues(left, right) {
  return canonicalJson(left) === canonicalJson(right);
}

function load(path) {
  const absolutePath = resolve(REPO_ROOT, path);
  const bytes = readFileSync(absolutePath);
  const text = bytes.toString("utf8");
  return { absolutePath, bytes, path, text, value: JSON.parse(text) };
}

function indexBy(rows, key, label) {
  const result = new Map();
  for (const row of rows) {
    const value = key(row);
    if (result.has(value)) throw new Error(`${label} contains duplicate key ${value}`);
    result.set(value, row);
  }
  return result;
}

function exactArray(actual, expected, label) {
  if (!sameValues(actual, expected)) {
    throw new Error(`${label} differs from its exact expected membership`);
  }
}

function without(value, key) {
  const copy = { ...value };
  delete copy[key];
  return copy;
}

function registrySemanticOutput(registry) {
  const sourceProvenance = { ...registry.sourceProvenance };
  delete sourceProvenance.semanticOutputSha256;
  return {
    behaviorPosture: registry.behaviorPosture,
    collections: registry.collections,
    compatibilityFloor: registry.compatibilityFloor,
    counts: registry.counts,
    generatedBy: registry.generatedBy,
    idMapVersion: registry.idMapVersion,
    schemaVersion: registry.schemaVersion,
    sourceProvenance,
  };
}

export function verifyOffline() {
  const checks = [];
  const failures = [];
  const run = (id, action) => {
    try {
      action();
      checks.push(id);
    } catch (error) {
      failures.push(`${id}: ${error.message}`);
    }
  };
  const require = (condition, message) => {
    if (!condition) throw new Error(message);
  };

  const files = Object.fromEntries(Object.entries(PATHS).map(([key, path]) => [key, load(path)]));
  const registry = files.registry.value;
  const certification = files.certification.value;
  const sourceLock = files.sourceLock.value;
  const supportSet = files.supportSet.value;
  const relationFixture = files.relationFixture.value;
  const artManifest = files.artManifest.value;
  const collections = registry.collections;
  const generatorBytes = readFileSync(resolve(REPO_ROOT, GENERATOR_PATH));
  const nativePlatformRecords = files.nativePlatforms.value.platforms;
  const nativePlatformIds = nativePlatformRecords.map((record) => record.platformId);

  run("schemas", () => {
    const pairs = [
      [files.registry, files.registrySchema],
      [files.certification, files.certificationSchema],
      [files.sourceLock, files.sourceLockSchema],
      [files.supportSet, files.supportSetSchema],
      [files.relationFixture, files.relationFixtureSchema],
      [files.nativePlatforms, files.nativePlatformsSchema],
    ];
    for (const [document, schema] of pairs) {
      const errors = validateJsonSchema(document.value, schema.value);
      require(errors.length === 0, `${document.path} schema errors:\n${errors.slice(0, 20).join("\n")}`);
    }
  });

  run("canonical-generated-bytes", () => {
    for (const file of [files.registry, files.certification, files.sourceLock, files.supportSet]) {
      require(file.text === prettyJson(file.value), `${file.path} is not canonical pretty JSON`);
    }
  });

  run("source-lock-import-provenance", () => {
    const importProvenance = sourceLock.importProvenance;
    require(importProvenance.repository.revision === EXPECTED_SOURCE_REVISION, "frozen shipping revision changed");
    require(importProvenance.sources.length === 4, "import provenance must contain four frozen shipping anchors");
    const sources = indexBy(importProvenance.sources, (source) => source.id, "import provenance");
    for (const [id, expectedHash] of Object.entries(EXPECTED_SOURCE_HASHES)) {
      require(sources.get(id)?.sha256 === expectedHash, `${id} SHA-256 changed`);
      require(sources.get(id)?.repositoryRevision === EXPECTED_SOURCE_REVISION, `${id} revision changed`);
    }
    const expectedSourceSetSha256 = digest({
      repositoryRevision: importProvenance.repository.revision,
      sources: importProvenance.sources.map(({ id, path, sha256 }) => ({ id, path, sha256 })),
    });
    require(importProvenance.sourceSetSha256 === expectedSourceSetSha256, "importProvenance.sourceSetSha256 does not match its frozen rows");
    const expectedLockedInputSetSha256 = digest({
      generatorSemanticsVersion: sourceLock.generatorSemanticsVersion,
      interpretationInputs: importProvenance.interpretationInputs.map(({ id, path, sha256 }) => ({ id, path, sha256 })),
      sourceSetSha256: importProvenance.sourceSetSha256,
    });
    require(
      importProvenance.lockedInputSetSha256 === expectedLockedInputSetSha256,
      "importProvenance.lockedInputSetSha256 does not bind the frozen shipping and interpretation inputs",
    );
    require(importProvenance.interpretationInputs.length === 2, "import provenance must freeze two interpretation inputs");
    const frozenGenerator = importProvenance.interpretationInputs.find((input) => input.id === "platform-registry-generator");
    require(frozenGenerator, "import provenance is missing the frozen generator interpretation input");
    require(frozenGenerator.path === GENERATOR_PATH, "frozen generator lock path changed");
  });

  run("source-lock-local-vendored-sources", () => {
    require(sourceLock.sources.length === 3, "source lock must contain exactly three locally vendored files");
    const localSources = indexBy(sourceLock.sources, (source) => source.id, "vendored sources");
    exactArray(
      [...localSources.keys()].sort(),
      ["mimir-platform-catalog", "mimir-retroarch-core-registry", "mimir-runtime-systems"],
      "vendored source IDs",
    );
    for (const [id, source] of localSources) {
      const absolutePath = resolve(REPO_ROOT, source.path);
      require(existsSync(absolutePath), `${id} vendored file is absent: ${source.path}`);
      const bytes = readFileSync(absolutePath);
      require(source.bytes === bytes.length, `${id} byte count differs from the vendored file on disk`);
      require(source.sha256 === sha256Bytes(bytes), `${id} SHA-256 differs from the vendored file on disk`);
    }
    // The catalog remains byte-identical to its original import. The RetroArch snapshot differs
    // only in generatedBy provenance metadata; pin its exact normalized SHA-256 independently
    // while the frozen import-provenance record continues to pin the original bytes.
    const importSources = indexBy(sourceLock.importProvenance.sources, (source) => source.id, "import provenance");
    require(
      localSources.get("mimir-platform-catalog")?.sha256 === importSources.get("shipping-platform-catalog")?.sha256,
      "vendored catalog copy no longer matches the frozen shipping catalog bytes",
    );
    require(
      localSources.get("mimir-retroarch-core-registry")?.sha256 === "153a9a28eaa6f35e8d74209c86a21ee80cc72f696ce0503444fd132c9328b4b8",
      "vendored RetroArch registry differs from the exact metadata-normalized snapshot",
    );
    const runtimeSystems = JSON.parse(readFileSync(resolve(REPO_ROOT, localSources.get("mimir-runtime-systems").path), "utf8"));
    require(Array.isArray(runtimeSystems) && runtimeSystems.length === EXPECTED.runtimeSystems, "vendored runtime-systems file does not carry exactly 21 records");
  });

  run("source-lock-interpretation-inputs", () => {
    require(
      sourceLock.generatorSemanticsVersion === EXPECTED_GENERATOR_SEMANTICS_VERSION,
      "generator semantics version changed without gate review",
    );
    require(sourceLock.interpretationInputs.length === 2, "expected two locked interpretation inputs");
    const inputs = indexBy(sourceLock.interpretationInputs, (input) => input.id, "interpretation inputs");
    exactArray([...inputs.keys()], ["platform-registry-generator", "shipping-catalog-relations"], "interpretation input IDs");

    const relationInput = inputs.get("shipping-catalog-relations");
    require(relationInput.path === PATHS.relationFixture, "relation fixture lock path changed");
    require(relationInput.bytes === files.relationFixture.bytes.length, "relation fixture byte count changed");
    require(relationInput.sha256 === sha256Bytes(files.relationFixture.bytes), "relation fixture bytes do not match source lock");
    require(relationInput.sha256 === EXPECTED_RELATION_FIXTURE_SHA256, "relation fixture SHA-256 changed without gate review");

    const generatorInput = inputs.get("platform-registry-generator");
    require(generatorInput.path === GENERATOR_PATH, "generator lock path changed");
    require(generatorInput.bytes === generatorBytes.length, "generator byte count changed");
    require(generatorInput.sha256 === sha256Bytes(generatorBytes), "generator bytes do not match source lock");
    // Unlike the frozen `importProvenance.lockedInputSetSha256`, these top-level rows are live: they
    // exist purely to catch "generator or fixture bytes changed but the lock wasn't regenerated" and
    // deliberately have no aggregate digest of their own that anything else consumes.
  });

  run("packaged-console-art", () => {
    const artSource = sourceLock.upstreamAssets.find((asset) => asset.name === "BAXY Automatic");
    require(artManifest.schemaVersion === "mimirPlatformConsoleArt.v1", "unexpected console-art manifest schema");
    require(artSource?.license === "MIT", "BAXY source is not locked as MIT");
    require(artManifest.source.name === artSource.name, "console-art source name differs from lock");
    require(artManifest.source.license === artSource.license, "console-art license differs from lock");
    require(artManifest.source.revision === artSource.revision, "console-art revision differs from lock");
    require(artManifest.source.url === artSource.url, "console-art source URL differs from lock");
    require(artManifest.source.noticePath === "NOTICE_BAXY_AUTOMATIC.txt", "console-art notice path differs");

    const packageBytes = (relativePath) => {
      const absolutePath = resolve(REPO_ROOT, relativePath);
      require(existsSync(absolutePath), `packaged art file is absent: ${relativePath}`);
      return readFileSync(absolutePath);
    };
    const requiredLicenseFiles = new Set(["LICENSE_BAXY_AUTOMATIC.txt", "NOTICE_BAXY_AUTOMATIC.txt"]);
    require(artManifest.licenseFiles.length === requiredLicenseFiles.size, "console-art license file count differs");
    for (const entry of artManifest.licenseFiles) {
      require(requiredLicenseFiles.delete(entry.path), `unexpected or duplicate license file ${entry.path}`);
      const bytes = packageBytes(`${ART_ROOT}/${entry.path}`);
      require(entry.bytes === bytes.length, `${entry.path} byte count differs`);
      require(entry.sha256 === sha256Bytes(bytes), `${entry.path} SHA-256 differs`);
    }
    require(requiredLicenseFiles.size === 0, "a required BAXY license file is absent");

    require(artManifest.sourceFiles.length === EXPECTED.visualAssets, "packaged BAXY source count differs");
    const sourcePaths = new Set();
    for (const entry of artManifest.sourceFiles) {
      require(entry.path.startsWith("baxy-automatic/") && entry.path.endsWith(".svg"), `invalid BAXY source path ${entry.path}`);
      require(sourcePaths.add(entry.path), `duplicate packaged BAXY source ${entry.path}`);
      const bytes = packageBytes(`${ART_ROOT}/${entry.path}`);
      require(entry.bytes === bytes.length, `${entry.path} byte count differs`);
      require(entry.sha256 === sha256Bytes(bytes), `${entry.path} SHA-256 differs`);
    }
    const expectedSourcePaths = new Set(
      collections.visualAssets.map((asset) => asset.relativePath.replace("platform_icons/baxy_automatic/", "baxy-automatic/")),
    );
    require(sourcePaths.size === expectedSourcePaths.size, "packaged BAXY source set size differs");
    for (const path of expectedSourcePaths) require(sourcePaths.has(path), `packaged BAXY source missing ${path}`);

    const canonicalIds = collections.platforms.map((platform) => platform.id).sort();
    const canonicalSet = new Set(canonicalIds);
    require(artManifest.canonicalResolutions.length === canonicalIds.length, "canonical art resolution count differs");
    const vectorsByCanonicalId = new Map();
    for (const vector of artManifest.renderedVectors) {
      require(canonicalSet.has(vector.canonicalId), `rendered vector has unknown canonical id ${vector.canonicalId}`);
      require(!vectorsByCanonicalId.has(vector.canonicalId), `duplicate rendered vector for ${vector.canonicalId}`);
      require(vector.path === `core-ui/src/main/res/drawable/${vector.resourceName}.xml`, `rendered vector path differs for ${vector.resourceName}`);
      require(vector.resourceName.startsWith("mimir_platform_icon_"), `invalid rendered resource ${vector.resourceName}`);
      const authored = MIMIR_AUTHORED_VECTORS[vector.resourceName];
      if (authored) {
        require(vector.canonicalId === authored.canonicalId, `authored vector ${vector.resourceName} targets ${vector.canonicalId}`);
        require(vector.sourcePath === authored.sourcePath, `authored vector source differs for ${vector.resourceName}`);
        const script = packageBytes(authored.sourcePath);
        require(sha256Bytes(script) === vector.sourceSha256, `authored vector source hash differs for ${vector.resourceName}`);
      } else {
        require(sourcePaths.has(vector.sourcePath), `rendered vector source is not packaged: ${vector.sourcePath}`);
        const source = artManifest.sourceFiles.find((entry) => entry.path === vector.sourcePath);
        require(source.sha256 === vector.sourceSha256, `rendered vector source hash differs for ${vector.resourceName}`);
      }
      const bytes = packageBytes(vector.path);
      require(vector.sha256 === sha256Bytes(bytes), `rendered vector SHA-256 differs for ${vector.resourceName}`);
      vectorsByCanonicalId.set(vector.canonicalId, vector);
    }
    require(
      vectorsByCanonicalId.size === EXPECTED.bundledConsoleVectors + EXPECTED.mimirAuthoredVectors,
      "real console vector count differs",
    );

    const resolutionByCanonicalId = new Map();
    let realCount = 0;
    let fallbackCount = 0;
    for (const resolution of artManifest.canonicalResolutions) {
      require(canonicalSet.has(resolution.id), `unknown canonical art resolution ${resolution.id}`);
      require(!resolutionByCanonicalId.has(resolution.id), `duplicate canonical art resolution ${resolution.id}`);
      if (resolution.kind === "BUNDLED_VECTOR") {
        const vector = vectorsByCanonicalId.get(resolution.id);
        require(vector?.resourceName === resolution.resourceName, `missing rendered vector for ${resolution.id}`);
        realCount += 1;
      } else if (resolution.kind === "GENERIC_FALLBACK") {
        require(resolution.fallbackId === ART_FALLBACK_ID, `non-deterministic fallback for ${resolution.id}`);
        fallbackCount += 1;
      } else {
        throw new Error(`unsupported art resolution kind ${resolution.kind}`);
      }
      resolutionByCanonicalId.set(resolution.id, resolution);
    }
    require(resolutionByCanonicalId.size === canonicalIds.length, "canonical art resolutions are incomplete");
    require(artManifest.coverage.canonicalPlatforms === canonicalIds.length, "console-art canonical coverage count differs");
    require(artManifest.coverage.realAndroidVectors === realCount, "console-art real coverage count differs");
    require(artManifest.coverage.genericFallbacks === fallbackCount, "console-art fallback coverage count differs");
    require(realCount + fallbackCount === canonicalIds.length, "console-art coverage does not close");
    require(artManifest.fallback.id === ART_FALLBACK_ID, "console-art fallback id differs");
    require(artManifest.fallback.renderer === "compose-stroke-console", "console-art fallback renderer differs");

    const aliases = new Map();
    for (const alias of artManifest.aliases) {
      require(/^[a-z0-9]+$/.test(alias.normalizedId), `art alias is not normalized: ${alias.normalizedId}`);
      require(canonicalSet.has(alias.canonicalId), `art alias targets unknown canonical ${alias.canonicalId}`);
      require(!aliases.has(alias.normalizedId), `duplicate art alias ${alias.normalizedId}`);
      aliases.set(alias.normalizedId, alias.canonicalId);
    }
    const normalizeArtId = (value) => value.trim().toLowerCase().replace(/[ _-]/g, "");
    const expectedAliases = new Map();
    for (const platform of collections.platforms) {
      for (const rawId of [platform.id, ...platform.aliases.map((alias) => alias.normalizedValue)]) {
        const normalizedId = normalizeArtId(rawId);
        const previous = expectedAliases.get(normalizedId);
        require(!previous || previous === platform.id, `registry art alias collision ${normalizedId}`);
        expectedAliases.set(normalizedId, platform.id);
      }
    }
    require(aliases.size === expectedAliases.size, "shipping art alias count differs");
    for (const [normalizedId, canonicalId] of expectedAliases) {
      require(aliases.get(normalizedId) === canonicalId, `shipping art alias does not resolve: ${normalizedId}`);
      require(resolutionByCanonicalId.has(canonicalId), `shipping art alias reaches no resolution: ${normalizedId}`);
    }
    require(artManifest.coverage.shippingAliasNormalizations === aliases.size, "console-art alias coverage count differs");
    require(artManifest.coverage.sourceSvgFiles === sourcePaths.size, "console-art source coverage count differs");
    execFileSync("node", ["tools/platform-registry/generate-platform-console-art.mjs", "--check"], {
      cwd: REPO_ROOT,
      stdio: "pipe",
    });
  });

  run("registry-revision-semantics", () => {
    const generatorInput = sourceLock.importProvenance.interpretationInputs.find(
      (input) => input.id === "platform-registry-generator",
    );
    require(generatorInput, "generator input is absent from frozen revision evidence");
    const revisionDigest = digest({
      generatorSemanticsVersion: sourceLock.generatorSemanticsVersion,
      generatorSha256: generatorInput.sha256,
      lockedInputSetSha256: sourceLock.importProvenance.lockedInputSetSha256,
      semanticOutputSha256: registry.sourceProvenance.semanticOutputSha256,
    });
    const expectedRevision = `pk00-${sourceLock.importProvenance.repository.revision.slice(0, 12)}-${revisionDigest.slice(0, 12)}`;
    require(
      registry.registryRevision === expectedRevision,
      "registry revision does not bind generator semantics, bytes, and semantic output",
    );
    require(supportSet.registryRevision === expectedRevision, "support-set registry revision differs");
    require(certification.registryRevision === expectedRevision, "certification registry revision differs");
  });

  run("registry-semantic-output", () => {
    const expectedSemanticOutputSha256 = digest(registrySemanticOutput(registry));
    require(
      registry.sourceProvenance.semanticOutputSha256 === expectedSemanticOutputSha256,
      "semantic output digest does not match stable pre-revision registry content",
    );
    require(
      supportSet.registrySemanticOutputSha256 === expectedSemanticOutputSha256,
      "support-set semantic output link differs",
    );
    require(
      certification.registrySemanticOutputSha256 === expectedSemanticOutputSha256,
      "certification semantic output link differs",
    );
  });

  run("cross-file-lock-linkage", () => {
    const importProvenance = sourceLock.importProvenance;
    const expectedSources = importProvenance.sources.map(({ id, sha256 }) => ({ id, sha256 }));
    const expectedInterpretations = importProvenance.interpretationInputs.map(({ id, path, sha256 }) => ({ id, path, sha256 }));
    exactArray(registry.sourceProvenance.sources, expectedSources, "registry source provenance");
    exactArray(registry.sourceProvenance.interpretationInputs, expectedInterpretations, "registry interpretation provenance");
    require(
      registry.sourceProvenance.generatorSemanticsVersion === sourceLock.generatorSemanticsVersion,
      "registry generator semantics link differs",
    );
    require(registry.sourceProvenance.sourceSetSha256 === importProvenance.sourceSetSha256, "registry source set link differs");
    require(registry.sourceProvenance.lockedInputSetSha256 === importProvenance.lockedInputSetSha256, "registry locked input link differs");
    require(supportSet.sourceSetSha256 === importProvenance.sourceSetSha256, "support-set source link differs");
    require(supportSet.lockedInputSetSha256 === importProvenance.lockedInputSetSha256, "support-set locked input link differs");
    require(certification.sourceSetSha256 === importProvenance.sourceSetSha256, "certification source link differs");
    require(certification.lockedInputSetSha256 === importProvenance.lockedInputSetSha256, "certification locked input link differs");
    require(relationFixture.sourceCatalogSha256 === EXPECTED_SOURCE_HASHES["shipping-platform-catalog"], "fixture catalog source hash differs");
  });

  run("registry-collection-digests", () => {
    const collectionNames = Object.keys(collections).sort();
    exactArray(Object.keys(registry.digests.collections).sort(), collectionNames, "collection digest names");
    for (const name of collectionNames) {
      require(registry.digests.collections[name] === digest(collections[name]), `${name} digest differs`);
    }
    const payload = without(registry, "digests");
    require(registry.digests.registryPayloadSha256 === digest(payload), "registry payload digest differs");
  });

  run("registry-counts", () => {
    for (const [name, rows] of Object.entries(collections)) {
      require(registry.counts[name] === rows.length, `${name} count differs from collection length`);
    }
    const coreCount = collections.retroArchRegistrations.reduce(
      (total, registration) => total + registration.coreRegistrations.length,
      0,
    );
    require(registry.counts.retroArchCoreRegistrations === coreCount, "RetroArch core-registration count differs");
    require(registry.counts.catalogEntries === EXPECTED.catalogEntries, "catalog count is not frozen at 403");
    require(registry.counts.catalogRelationFixtures === EXPECTED.relationFixtures, "relation fixture count differs");
    require(registry.counts.platforms === EXPECTED.totalPlatforms, "total platform count (shipping + Mimir-native) differs");
    require(registry.counts.contentProfiles === EXPECTED.totalContentProfiles, "total content-profile count (shipping + Mimir-native) differs");
    require(registry.counts.importBindings === EXPECTED.totalImportBindings, "total import-binding count (shipping + Mimir-native) differs");
    require(registry.counts.retroArchRegistrations === EXPECTED.retroArchIds, "RetroArch ID count differs");
    require(coreCount === EXPECTED.retroArchCoreRegistrations, "RetroArch core-registration denominator differs");
    require(registry.counts.shippingRuntimeSystems === EXPECTED.runtimeSystems, "runtime baseline count differs");
    require(registry.counts.standaloneTargetObservations === EXPECTED.standaloneTargetRows, "standalone row count differs");
    require(registry.counts.visualAssets === EXPECTED.visualAssets, "visual asset count differs");
  });

  run("content-profile-contracts", () => {
    const strictContentContractSchema = files.registrySchema.value.$defs.contentContract;
    let shippingBoundCount = 0;
    let nativeBoundCount = 0;
    let gapCount = 0;
    for (const profile of collections.contentProfiles) {
      if (profile.discoveryStatus === "BOUND") {
        if (profile.externalId.namespace === NATIVE_NAMESPACE) nativeBoundCount += 1;
        else shippingBoundCount += 1;
        require(profile.contentContract !== null, `${profile.id} BOUND content contract must be non-null`);
        const errors = validateJsonSchema(profile.contentContract, strictContentContractSchema);
        require(
          errors.length === 0,
          `${profile.id} BOUND content contract is structurally invalid: ${errors.slice(0, 5).join("; ")}`,
        );
      } else if (profile.discoveryStatus === "GAP") {
        gapCount += 1;
        require(profile.contentContract === null, `${profile.id} GAP content contract must be null`);
      } else {
        throw new Error(`${profile.id} has unsupported discovery status ${profile.discoveryStatus}`);
      }
    }
    // Shipping BOUND stays pinned at the import-binding denominator regardless of how many
    // Mimir-native platforms exist; native BOUND is counted against the native record count
    // itself (open, not frozen) so this gate never needs editing when a native platform is added.
    require(shippingBoundCount === EXPECTED.importBindings, "shipping BOUND content-profile denominator differs");
    require(nativeBoundCount === nativePlatformRecords.length, "native BOUND content-profile count differs from native record count");
    require(gapCount === EXPECTED.retroArchDiscoveryGap, "GAP content-profile denominator differs");
  });

  run("dreamcast-cue-bin-reconciliation", () => {
    const profile = collections.contentProfiles.find((entry) => entry.id === "shipping-catalog-profile:dreamcast");
    require(profile?.contentContract, "Dreamcast content contract is absent");
    const contract = profile.contentContract;
    require(contract.baseGameExtensions.includes("cue"), "Dreamcast must retain cue as a launch candidate");
    require(contract.baseGameExtensions.includes("bin"), "Dreamcast must declare bin as an ambiguous extension");
    require(
      contract.conditionalContent.some(
        (entry) => entry.kind === "disc_sidecar" && sameValues(entry.extensions, ["bin"]),
      ),
      "Dreamcast bin must be conditional disc-sidecar content",
    );
    require(
      contract.sidecarRules.some(
        (entry) => entry.kind === "AMBIGUOUS_DISC_TRACK" && sameValues(entry.extensions, ["bin"]),
      ),
      "Dreamcast bin must have an ambiguous-disc sidecar rule",
    );
    const runtime = collections.shippingRuntimeSystems.find((entry) => entry.externalId.value === "dreamcast");
    require(runtime?.extensions.includes("cue"), "shipping Dreamcast runtime cue evidence is absent");
    require(!runtime.extensions.includes("bin"), "Dreamcast bin reconciliation must remain explicit rather than source-inferred");
  });

  run("behavior-dark", () => {
    require(registry.behaviorPosture === "BEHAVIOR_DARK", "registry is not behavior-dark");
    for (const name of ["emulatorFamilies", "firmwarePolicies", "launchRoutes", "supportSlices"]) {
      require(collections[name].length === 0, `${name} must remain empty in PK-00`);
    }
    require(certification.behaviorPosture === "BEHAVIOR_DARK", "certification ledger is not behavior-dark");
    require(certification.certifications.length === 0, "certification ledger is not empty");
    require(certification.revocations.length === 0, "certification revocation ledger is not empty");
    require(
      certification.registryPayloadSha256 === registry.digests.registryPayloadSha256,
      "certification ledger registry digest differs",
    );
    for (const observation of collections.standaloneTargetObservations) {
      require(observation.observationOnly === true, `${observation.id} is not observation-only`);
      require(observation.dispatchContract === null, `${observation.id} contains a dispatch contract`);
    }
  });

  run("catalog-relations", () => {
    require(!Object.hasOwn(relationFixture, "defaultRelation"), "relation fixture contains fail-open defaultRelation");
    require(relationFixture.canonicalIds.length === EXPECTED.canonicalPlatforms, "canonical ID denominator differs");
    require(relationFixture.variantLinks.length === EXPECTED.relationFixtures, "relation fixture denominator differs");
    const fixtureLinks = indexBy(relationFixture.variantLinks, (link) => link.catalogId, "relation fixture");
    const catalog = indexBy(collections.catalogEntries, (entry) => entry.externalId.value, "catalog entries");
    const platformIds = new Set(collections.platforms.map((platform) => platform.id));
    const canonicalIds = new Set(relationFixture.canonicalIds);
    require(canonicalIds.size === EXPECTED.canonicalPlatforms, "canonical IDs contain duplicates");
    exactArray(relationFixture.canonicalIds, [...canonicalIds].sort(), "sorted canonical IDs");
    exactArray(
      relationFixture.variantLinks.map((link) => link.catalogId),
      [...fixtureLinks.keys()].sort(),
      "sorted variant links",
    );
    for (const catalogId of canonicalIds) {
      require(!fixtureLinks.has(catalogId), `${catalogId} is both canonical and variant`);
    }
    const classifiedIds = [...canonicalIds, ...fixtureLinks.keys()].sort();
    require(classifiedIds.length === EXPECTED.catalogEntries, "relation fixture classification count differs");
    exactArray(classifiedIds, [...catalog.keys()].sort(), "relation fixture exact catalog union");

    const relationCounts = Object.fromEntries(CATALOG_RELATIONS.map((relation) => [relation, 0]));

    for (const [externalId, entry] of catalog) {
      const isCanonical = canonicalIds.has(externalId);
      const link = fixtureLinks.get(externalId);
      require(isCanonical !== Boolean(link), `${externalId} does not have exactly one explicit classification`);
      const expectedPlatformId = isCanonical ? externalId : link.platformId;
      const expectedRelation = isCanonical ? "CANONICAL" : link.relation;
      require(CATALOG_RELATIONS.includes(expectedRelation), `${externalId} has unsupported relation ${expectedRelation}`);
      require(entry.platformId === expectedPlatformId, `${externalId} canonical platform differs`);
      require(entry.relation === expectedRelation, `${externalId} relation differs`);
      require(
        entry.relationEvidence.classificationFixtureSha256 === EXPECTED_RELATION_FIXTURE_SHA256,
        `${externalId} relation evidence is not fixture-bound`,
      );
      relationCounts[expectedRelation] += 1;
    }
    require(relationCounts.CANONICAL === EXPECTED.canonicalPlatforms, "CANONICAL relation count differs");
    require(relationCounts.CONTENT_VARIANT === EXPECTED.contentVariants, "CONTENT_VARIANT count differs");
    require(relationCounts.HARDWARE_VARIANT === EXPECTED.hardwareVariants, "HARDWARE_VARIANT count differs");
    require(relationCounts.PRESENTATION_ALIAS === EXPECTED.presentationAliases, "PRESENTATION_ALIAS count differs");
    require(relationCounts.REGIONAL_VARIANT === EXPECTED.regionalVariants, "REGIONAL_VARIANT count differs");
    require(relationCounts.SOFTWARE_FAMILY === EXPECTED.softwareFamilies, "SOFTWARE_FAMILY count differs");
    exactArray(
      [...platformIds].sort(),
      sortedUnique([...relationFixture.canonicalIds, ...nativePlatformIds]),
      "canonical platform IDs union Mimir-native platform IDs",
    );
    for (const link of relationFixture.variantLinks) {
      require(canonicalIds.has(link.platformId), `${link.catalogId} targets non-canonical ${link.platformId}`);
      require(!platformIds.has(link.catalogId), `variant ${link.catalogId} leaked into canonical platform IDs`);
      require(platformIds.has(link.platformId), `variant ${link.catalogId} target ${link.platformId} is absent`);
    }
    const registryFixtures = indexBy(
      collections.catalogRelationFixtures,
      (fixture) => fixture.catalogEntryId.replace("shipping-catalog:", ""),
      "registry relation fixtures",
    );
    exactArray([...registryFixtures.keys()], [...fixtureLinks.keys()], "registry variant fixture IDs");
    for (const [catalogId, link] of fixtureLinks) {
      const registryFixture = registryFixtures.get(catalogId);
      require(registryFixture.platformId === link.platformId, `${catalogId} registry fixture platform differs`);
      require(registryFixture.relation === link.relation, `${catalogId} registry fixture relation differs`);
    }
  });

  run("variant-adversarial-fixtures", () => {
    const catalog = indexBy(collections.catalogEntries, (entry) => entry.externalId.value, "catalog entries");
    const platformIds = new Set(collections.platforms.map((platform) => platform.id));
    require(
      new Set(ADVERSARIAL_RELATION_FIXTURES.map(([externalId]) => externalId)).size ===
        ADVERSARIAL_RELATION_FIXTURES.length,
      "adversarial exemplar IDs are not unique",
    );
    for (const [externalId, platformId, relation] of ADVERSARIAL_RELATION_FIXTURES) {
      const entry = catalog.get(externalId);
      require(entry?.platformId === platformId, `${externalId} does not map to ${platformId}`);
      require(entry?.relation === relation, `${externalId} is not ${relation}`);
      require(!platformIds.has(externalId), `${externalId} is still a false resolver identity`);
    }
    const manifestFixtures = indexBy(
      supportSet.catalogNormalization.fixtures,
      (fixture) => fixture.catalogEntryId.replace("shipping-catalog:", ""),
      "manifest adversarial fixtures",
    );
    exactArray([...manifestFixtures.keys()], ADVERSARIAL_RELATION_FIXTURES.map(([externalId]) => externalId), "manifest exemplar IDs");
    for (const [externalId, platformId, relation] of ADVERSARIAL_RELATION_FIXTURES) {
      const fixture = manifestFixtures.get(externalId);
      require(fixture.status === "PASS", `${fixture.catalogEntryId} fixture is not PASS`);
      require(fixture.expectedPlatformId === platformId, `${fixture.catalogEntryId} expected platform differs`);
      require(fixture.observedPlatformId === platformId, `${fixture.catalogEntryId} observed platform differs`);
      require(fixture.expectedRelation === relation, `${fixture.catalogEntryId} expected relation differs`);
      require(fixture.observedRelation === relation, `${fixture.catalogEntryId} observed relation differs`);
    }
  });

  run("platform-wire-links", () => {
    const catalogByPlatform = new Map();
    for (const entry of collections.catalogEntries) {
      const ids = catalogByPlatform.get(entry.platformId) ?? [];
      ids.push(entry.id);
      catalogByPlatform.set(entry.platformId, ids);
    }
    const profilesByPlatform = new Map();
    for (const profile of collections.contentProfiles) {
      const ids = profilesByPlatform.get(profile.platformId) ?? [];
      ids.push(profile.id);
      profilesByPlatform.set(profile.platformId, ids);
    }
    for (const platform of collections.platforms) {
      exactArray(platform.presentation.catalogEntryIds, (catalogByPlatform.get(platform.id) ?? []).sort(), `${platform.id} catalog links`);
      exactArray(platform.contentProfileIds, (profilesByPlatform.get(platform.id) ?? []).sort(), `${platform.id} content-profile links`);
      require(platform.launchRouteIds.length === 0, `${platform.id} exposes a launch route during PK-00`);
      require(platform.identity.length === 0, `${platform.id} asserts unimplemented identity strategies`);
    }
  });

  run("scoped-aliases", () => {
    const aliasTargets = new Map();
    for (const platform of collections.platforms) {
      for (const alias of platform.aliases) {
        const key = `${alias.namespace}:${alias.normalizedValue}`;
        require(!aliasTargets.has(key), `scoped alias ${key} is duplicated`);
        require(alias.targetId === platform.id, `scoped alias ${key} points outside its platform`);
        aliasTargets.set(key, alias.targetId);
      }
    }
    require(aliasTargets.get("shipping-runtime:wii") === "nintendo_wii", "shipping-runtime:wii alias differs");
    require(aliasTargets.get("shipping-catalog:nintendo_wii_rvl_201") === "nintendo_wii", "Wii variant alias differs");
    require(aliasTargets.get("shipping-catalog:sony_playstation_2_scph_70000") === "ps2", "PS2 variant alias differs");
    for (const entry of collections.catalogEntries) {
      const alias = `shipping-catalog:${entry.externalId.value}`;
      require(aliasTargets.get(alias) === entry.platformId, `${alias} does not resolve to its classified platform`);
    }
    require(!collections.platforms.some((platform) => platform.id === "unknown"), "unknown became a platform");
    require(collections.recognitionStates.length === 1 && collections.recognitionStates[0].value === "unknown", "unknown state differs");
  });

  run("retroarch-namespace-preservation", () => {
    const registrations = indexBy(
      collections.retroArchRegistrations,
      (registration) => registration.externalId.value,
      "RetroArch registrations",
    );
    const neo = registrations.get("neo_geo");
    const snk = registrations.get("snk_neo_geo");
    require(neo && snk, "both Neo Geo RetroArch registration IDs must exist");
    require(neo.id !== snk.id, "Neo Geo RetroArch IDs collapsed");
    require(neo.contentProfileId !== snk.contentProfileId, "Neo Geo content profiles collapsed");
    require(neo.platformId === "snk_neo_geo" && snk.platformId === "snk_neo_geo", "Neo Geo canonical join differs");
  });

  run("referential-integrity", () => {
    const platformIds = new Set(collections.platforms.map((row) => row.id));
    const catalogIds = new Set(collections.catalogEntries.map((row) => row.id));
    const profileIds = new Set(collections.contentProfiles.map((row) => row.id));
    const importIds = new Set(collections.importBindings.map((row) => row.id));
    const visualIds = new Set(collections.visualAssets.map((row) => row.id));
    for (const binding of collections.importBindings) {
      require(platformIds.has(binding.platformId), `${binding.id} platform is absent`);
      // `catalogEntryId` is null for Mimir-native import bindings, which have no catalog entry.
      require(
        binding.catalogEntryId === null
          ? binding.sourceExternalId.namespace === NATIVE_NAMESPACE
          : catalogIds.has(binding.catalogEntryId),
        `${binding.id} catalog entry is absent`,
      );
      require(profileIds.has(binding.contentProfileId), `${binding.id} content profile is absent`);
    }
    for (const entry of collections.catalogEntries) {
      require(platformIds.has(entry.platformId), `${entry.id} platform is absent`);
      require(entry.importBindingId === null || importIds.has(entry.importBindingId), `${entry.id} import binding is absent`);
      require(visualIds.has(entry.visualAssetId), `${entry.id} visual asset is absent`);
    }
    for (const registration of collections.retroArchRegistrations) {
      require(platformIds.has(registration.platformId), `${registration.id} platform is absent`);
      require(profileIds.has(registration.contentProfileId), `${registration.id} content profile is absent`);
      require(
        registration.coreRegistrations.some((core) => core.stem === registration.recommendedCore && core.isRecommended),
        `${registration.id} recommended core is absent`,
      );
    }
  });

  run("support-set-membership", () => {
    const catalog = collections.catalogEntries;
    const discovery = new Set(catalog.filter((entry) => entry.importBindingId !== null).map((entry) => entry.externalId.value));
    const retroArch = new Set(collections.retroArchRegistrations.map((entry) => entry.externalId.value));
    const standalone = new Set(catalog.filter((entry) => entry.standaloneTargetObservationIds.length > 0).map((entry) => entry.externalId.value));
    const catalogIds = catalog.map((entry) => entry.externalId.value);
    const expectedSets = {
      A287_CATALOG_ONLY: catalogIds.filter((id) => !discovery.has(id) && !retroArch.has(id) && !standalone.has(id)),
      B21: collections.shippingRuntimeSystems.map((entry) => entry.externalId.value),
      D59_DISCOVERY_ONLY: catalogIds.filter((id) => discovery.has(id) && !retroArch.has(id) && !standalone.has(id)),
      DR47_DISCOVERY_RUNTIME: catalogIds.filter((id) => discovery.has(id) && (retroArch.has(id) || standalone.has(id))),
      R10_DISCOVERY_GAP: [...retroArch].filter((id) => !discovery.has(id)),
      R39_DISCOVERABLE: [...retroArch].filter((id) => discovery.has(id)),
      R49: [...retroArch],
    };
    for (const [name, ids] of Object.entries(expectedSets)) {
      const expectedIds = sortedUnique(ids);
      const observed = supportSet.sets[name];
      require(observed.count === EXPECTED_SET_COUNTS[name], `${name} frozen count differs`);
      require(observed.count === observed.ids.length, `${name} count differs from IDs`);
      exactArray(observed.ids, expectedIds, name);
      require(observed.idsSha256 === digest(observed.ids), `${name} digest differs`);
      require(supportSet.digests.setIds[name] === observed.idsSha256, `${name} digest linkage differs`);
    }
    exactArray(
      supportSet.sets.R49.ids,
      sortedUnique([...supportSet.sets.R39_DISCOVERABLE.ids, ...supportSet.sets.R10_DISCOVERY_GAP.ids]),
      "R49 discovery split",
    );
  });

  run("catalog-partition", () => {
    const names = supportSet.catalogPartition.disjointSetNames;
    const ids = names.flatMap((name) => supportSet.sets[name].ids);
    require(ids.length === EXPECTED.catalogEntries, "catalog partition membership count differs");
    require(new Set(ids).size === EXPECTED.catalogEntries, "catalog partition sets overlap");
    require(supportSet.catalogPartition.unionCount === EXPECTED.catalogEntries, "catalog partition union count differs");
    require(supportSet.catalogPartition.unionIdsSha256 === digest(sortedUnique(ids)), "catalog partition digest differs");
  });

  run("standalone-exact-rows", () => {
    const rows = collections.standaloneTargetObservations.map((row) => ({
      catalogEntryId: row.catalogEntryId,
      id: row.id,
      label: row.label,
      packageName: row.packageName,
      sourceOrdinal: row.sourceOrdinal,
      sourceStatus: row.sourceStatus,
    }));
    const catalogEntryIds = sortedUnique(rows.map((row) => row.catalogEntryId.replace("shipping-catalog:", "")));
    const packageNames = sortedUnique(rows.map((row) => row.packageName));
    require(catalogEntryIds.length === EXPECTED.standaloneEntries, "standalone catalog-entry count differs");
    require(rows.length === EXPECTED.standaloneTargetRows, "standalone target-row count differs");
    require(packageNames.length === EXPECTED.standalonePackages, "standalone package count differs");
    exactArray(supportSet.standalone.catalogEntryIds, catalogEntryIds, "standalone catalog IDs");
    exactArray(supportSet.standalone.targetRows, rows, "standalone target rows");
    exactArray(supportSet.standalone.packageNames, packageNames, "standalone package names");
    require(supportSet.digests.standaloneTargetRowsSha256 === digest(rows), "standalone row digest differs");
    require(supportSet.digests.standalonePackageNamesSha256 === digest(packageNames), "standalone package digest differs");
  });

  run("support-set-digests", () => {
    require(supportSet.digests.manifestPayloadSha256 === digest(without(supportSet, "digests")), "support-set payload digest differs");
    require(supportSet.catalogNormalization.canonicalPlatformCount === EXPECTED.totalPlatforms, "manifest canonical count (shipping + Mimir-native) differs");
    require(supportSet.catalogNormalization.relationFixtureSha256 === EXPECTED_RELATION_FIXTURE_SHA256, "manifest relation fixture hash differs");
    const expectedRelationCounts = {
      CANONICAL: EXPECTED.canonicalPlatforms,
      CONTENT_VARIANT: EXPECTED.contentVariants,
      HARDWARE_VARIANT: EXPECTED.hardwareVariants,
      PRESENTATION_ALIAS: EXPECTED.presentationAliases,
      REGIONAL_VARIANT: EXPECTED.regionalVariants,
      SOFTWARE_FAMILY: EXPECTED.softwareFamilies,
    };
    require(
      sameValues(supportSet.catalogNormalization.relationCounts, expectedRelationCounts),
      "manifest relation counts differ",
    );
    require(
      Object.values(expectedRelationCounts).reduce((total, count) => total + count, 0) === EXPECTED.catalogEntries,
      "manifest relation counts do not cover catalog",
    );
  });

  run("wii-default-drift", () => {
    const fixture = supportSet.wiiDefaultDrift;
    require(fixture.expectedRuntimeIds.length === 21, "runtime fixture is not 21 entries");
    require(fixture.observedCatalogDefaultIds.length === 20, "catalog default fixture is not 20 entries");
    exactArray(fixture.missingCatalogDefaultIds, ["wii"], "Wii default drift");
    require(fixture.extraCatalogDefaultIds.length === 0, "Wii default fixture has unexpected extras");
    require(fixture.canonicalResolution.targetPlatformId === "nintendo_wii", "Wii default resolution differs");
  });

  run("native-platform-extension-point", () => {
    require(
      files.nativePlatforms.value.schemaVersion === "mimirNativePlatforms.v1",
      "Mimir-native platform source schema differs",
    );
    require(new Set(nativePlatformIds).size === nativePlatformRecords.length, "Mimir-native platformIds are not unique");

    const lockRows = sourceLock.nativeSources;
    require(Array.isArray(lockRows) && lockRows.length === 1, "source lock must contain exactly one native source row");
    const lockRow = lockRows[0];
    require(lockRow.id === "mimir-native-platforms", "native source lock id differs");
    require(lockRow.path === PATHS.nativePlatforms, "native source lock path differs");
    require(lockRow.bytes === files.nativePlatforms.bytes.length, "native source byte count differs from the file on disk");
    require(lockRow.sha256 === sha256Bytes(files.nativePlatforms.bytes), "native source SHA-256 differs from the file on disk");

    const platformsById = indexBy(collections.platforms, (platform) => platform.id, "platforms");
    const catalogExternalIds = new Set(collections.catalogEntries.map((entry) => entry.externalId.value));
    const retroArchExternalIds = new Set(collections.retroArchRegistrations.map((entry) => entry.externalId.value));

    for (const record of nativePlatformRecords) {
      require(!catalogExternalIds.has(record.platformId), `${record.platformId} collides with a shipping catalog identity`);
      require(!retroArchExternalIds.has(record.platformId), `${record.platformId} collides with a RetroArch identity`);

      const platform = platformsById.get(record.platformId);
      require(platform, `Mimir-native platform ${record.platformId} produced no registry platform record`);
      require(platform.definitionStatus === "PROVISIONAL_SOURCE_ONLY", `${record.platformId} definitionStatus differs`);
      require(platform.display.displayName === record.displayName, `${record.platformId} display.displayName differs from its native record`);
      require(platform.display.manufacturer === record.manufacturer, `${record.platformId} display.manufacturer differs from its native record`);
      require(platform.display.shortName === record.shortName, `${record.platformId} display.shortName differs from its native record`);
      require(platform.folder.canonicalFolder === record.folder, `${record.platformId} folder.canonicalFolder differs from its native record`);
      require(platform.folder.platformId === record.platformId, `${record.platformId} folder.platformId differs`);
      require(!platform.folder.caseSensitive, `${record.platformId} folder must not be case-sensitive`);
      require(platform.folder.flatDumpPolicy === "unique_extension_only", `${record.platformId} flatDumpPolicy differs`);
      exactArray(platform.folder.aliases, sortedUnique(record.folderAliases), `${record.platformId} folder aliases`);
      require(platform.launchRouteIds.length === 0, `${record.platformId} exposes a launch route (R-2b is not this ticket's scope)`);

      const selfAlias = platform.aliases.find((alias) => alias.namespace === NATIVE_NAMESPACE);
      require(selfAlias, `${record.platformId} has no ${NATIVE_NAMESPACE} alias`);
      require(selfAlias.normalizedValue === record.externalId.value, `${record.platformId} native alias normalizedValue differs`);
      require(selfAlias.targetId === record.platformId, `${record.platformId} native alias does not target itself`);

      const profile = collections.contentProfiles.find(
        (candidate) => candidate.externalId.namespace === NATIVE_NAMESPACE && candidate.externalId.value === record.externalId.value,
      );
      require(profile, `Mimir-native platform ${record.platformId} has no content profile`);
      require(profile.discoveryStatus === "BOUND", `${record.platformId} content profile is not BOUND`);
      require(platform.contentProfileIds.includes(profile.id), `${record.platformId} platform does not list its content profile`);
      exactArray(
        profile.contentContract.baseGameExtensions,
        sortedUnique(record.extensions),
        `${record.platformId} content contract extensions`,
      );

      // `PlatformRegistry.extensionCandidates()` (core-platform, Kotlin) reads extension rules only
      // through `document.importBindings`, never straight from `contentProfiles` — a profile alone
      // is not "recognized". This is the check that would have caught the false pass: a platform and
      // a BOUND profile with no import binding compiles, schema-validates, and changes the revision
      // hash, but the extension is never actually recognized.
      const binding = collections.importBindings.find(
        (candidate) => candidate.sourceExternalId.namespace === NATIVE_NAMESPACE && candidate.sourceExternalId.value === record.externalId.value,
      );
      require(binding, `Mimir-native platform ${record.platformId} has no import binding`);
      require(binding.catalogEntryId === null, `${record.platformId} import binding must have a null catalogEntryId`);
      require(binding.platformId === record.platformId, `${record.platformId} import binding platformId differs`);
      require(binding.contentProfileId === profile.id, `${record.platformId} import binding does not point at its content profile`);
    }

    // Emptying `mimir-native-platforms.json` and regenerating must reproduce the historical
    // shipping-only registry byte-for-byte; a frozen native count here would make that impossible
    // to prove through the real generator, so this gate only checks internal consistency, never a
    // fixed native record count.
    require(
      collections.platforms.length === EXPECTED.canonicalPlatforms + nativePlatformRecords.length,
      "total platform count does not equal shipping canonical + Mimir-native",
    );
  });

  if (failures.length > 0) {
    const error = new Error(`Offline platform registry verification failed:\n${failures.join("\n")}`);
    error.failures = failures;
    throw error;
  }

  return {
    canonicalPlatformCount: registry.counts.platforms,
    checkCount: checks.length,
    registryRevision: registry.registryRevision,
    sourceSetSha256: sourceLock.importProvenance.sourceSetSha256,
  };
}

function runCli() {
  if (process.argv.length > 2 && process.argv[2] !== "--offline") {
    throw new Error("Usage: verify-platform-registry.mjs [--offline]");
  }
  const result = verifyOffline();
  process.stdout.write(
    `PASS offline platform registry: ${result.checkCount} gates, ${result.canonicalPlatformCount} canonical/provisional platforms, ${result.registryRevision}\n`,
  );
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  try {
    runCli();
  } catch (error) {
    process.stderr.write(`FAIL ${error.message}\n`);
    process.exitCode = 1;
  }
}
