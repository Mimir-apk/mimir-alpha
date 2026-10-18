#!/usr/bin/env node

import { createHash } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));

export const REPO_ROOT = resolve(SCRIPT_DIR, "../..");
// This tree owns its platform data. Nothing here reads the retired WebView/Java app
// (`../gaming-hobby-android-handhelds`) at generate, verify, or build time — see
// `importProvenance` below for the frozen, one-time record of what was vendored from it.
export const DEFAULT_SOURCE_ROOT = resolve(REPO_ROOT, "platforms/sources");

export const REGISTRY_SCHEMA_VERSION = "mimirPlatformRegistry.v2";
export const CERTIFICATION_SCHEMA_VERSION = "mimirPlatformCertification.v1";
export const SOURCE_LOCK_SCHEMA_VERSION = "mimirPlatformSourcesLock.v1";
export const SUPPORT_SET_SCHEMA_VERSION = "mimirPlatformSupportSetManifest.v1";
export const ID_MAP_VERSION = "mimirPlatformIdMap.v1";
export const COMPATIBILITY_FLOOR = "pk00-source-only.v1";
export const GENERATOR_ID = "tools/platform-registry/generate-platform-registry.mjs";
// Unchanged: the transformation semantics (shipping data in, this registry shape out) have
// not changed. Only the plumbing that acquires the inputs changed. Bumping this would change
// `lockedInputSetSha256` and every byte downstream of it, so it stays pinned at v3.
export const GENERATOR_SEMANTICS_VERSION = "mimirPlatformRegistryGeneratorSemantics.v3";
export const DIGEST_CANONICALIZATION = "mimir-canonical-json-v1";

const RELATION_FIXTURE_PATH = "tools/platform-registry/fixtures/shipping-catalog-relations.v1.json";
const CATALOG_RELATIONS = Object.freeze([
  "CANONICAL",
  "CONTENT_VARIANT",
  "HARDWARE_VARIANT",
  "PRESENTATION_ALIAS",
  "REGIONAL_VARIANT",
  "SOFTWARE_FAMILY",
]);
const VARIANT_RELATIONS = new Set(CATALOG_RELATIONS.filter((relation) => relation !== "CANONICAL"));

const ADVERSARIAL_RELATION_FIXTURES = Object.freeze([
  Object.freeze({ catalogId: "nintendo_wii_rvl_201", platformId: "nintendo_wii", relation: "HARDWARE_VARIANT" }),
  Object.freeze({ catalogId: "sony_playstation_2_scph_70000", platformId: "ps2", relation: "HARDWARE_VARIANT" }),
  Object.freeze({ catalogId: "neo_geo", platformId: "snk_neo_geo", relation: "CONTENT_VARIANT" }),
  Object.freeze({ catalogId: "nintendo_switch_hac_001", platformId: "switch", relation: "HARDWARE_VARIANT" }),
  Object.freeze({ catalogId: "nintendo_e_reader", platformId: "gba", relation: "HARDWARE_VARIANT" }),
  Object.freeze({ catalogId: "sony_playstation_portable_psx2psp", platformId: "ps1", relation: "CONTENT_VARIANT" }),
  Object.freeze({ catalogId: "apple_macintosh_beta", platformId: "apple_macintosh", relation: "CONTENT_VARIANT" }),
  Object.freeze({ catalogId: "mattel_hyperscan_alt", platformId: "mattel_hyperscan", relation: "PRESENTATION_ALIAS" }),
  Object.freeze({ catalogId: "sony_playstation_scph_100_131_flat", platformId: "ps1", relation: "PRESENTATION_ALIAS" }),
  Object.freeze({ catalogId: "vtech_socrates_alt", platformId: "vtech_socrates", relation: "PRESENTATION_ALIAS" }),
  Object.freeze({ catalogId: "sega_mega_drive_genesis_kr", platformId: "genesis", relation: "REGIONAL_VARIANT" }),
]);

const EXPECTED = Object.freeze({
  runtimeSystems: 21,
  catalogEntries: 403,
  canonicalPlatforms: 225,
  variantLinks: 178,
  hardwareVariants: 137,
  contentVariants: 37,
  presentationAliases: 3,
  regionalVariants: 1,
  softwareFamilies: 0,
  importBindings: 106,
  retroArchIds: 49,
  retroArchCoreRegistrations: 116,
  retroArchDiscoverable: 39,
  retroArchDiscoveryGap: 10,
  catalogOnly: 287,
  discoveryOnly: 59,
  discoveryRuntime: 47,
  standaloneEntries: 12,
  standaloneTargetRows: 44,
  standalonePackages: 43,
});

// Frozen, one-time import record. This is NOT read from disk — the retired repository may be
// absent entirely. These are the exact values
// that were already committed in `platforms/mimir-platform-sources.lock.json` /
// `platforms/mimir-platform-registry.v2.json` before this tree stopped depending on the
// retired app; freezing them is what keeps regeneration byte-identical. `MimirBridge.java`
// (`shipping-android-bridge`) contributed no data even before this change — role
// `shipping-runtime-reference-only` — and is not vendored locally; its provenance is kept here
// only as an honest historical record.
const IMPORT_PROVENANCE_REPOSITORY = Object.freeze({
  pathHint: "../gaming-hobby-android-handhelds",
  revision: "b34752d0290ef1a34199aa6c6dd4258646fe4c06",
  url: "https://github.com/Mimir-apk/gaming-hobby-android-handhelds.git",
});

const IMPORT_PROVENANCE_SOURCES = Object.freeze([
  Object.freeze({
    bytes: 1373704,
    id: "shipping-runtime-js",
    license: "UNSPECIFIED_PROJECT_SOURCE",
    path: "thor-torrent-shop/app.js",
    repositoryRevision: "b34752d0290ef1a34199aa6c6dd4258646fe4c06",
    roles: Object.freeze(["baseline-runtime-systems", "runtime-folder-aliases", "runtime-ambiguous-disc-handling"]),
    sha256: "2e5d7c25e104c52a0cb1b415d409e9a44088d292dfe87eac73a238e40663981c",
  }),
  Object.freeze({
    bytes: 1080178,
    id: "shipping-platform-catalog",
    license: "MIXED_PROJECT_METADATA_AND_NOTICED_ASSETS",
    path: "thor-torrent-shop/mimir-platform-catalog.json",
    repositoryRevision: "b34752d0290ef1a34199aa6c6dd4258646fe4c06",
    roles: Object.freeze(["catalog-identities", "discovery-bindings", "standalone-target-observations", "visual-assets"]),
    sha256: "f2ea34810476db28d1583b84869ea6ef89c67fac66b61bee28a7568fbb16ad56",
  }),
  Object.freeze({
    bytes: 12547,
    id: "shipping-retroarch-registry",
    license: "UNSPECIFIED_PROJECT_SOURCE",
    path: "thor-torrent-shop/mimir-retroarch-core-registry.json",
    repositoryRevision: "b34752d0290ef1a34199aa6c6dd4258646fe4c06",
    roles: Object.freeze(["retroarch-registration-ids", "retroarch-platform-core-registrations"]),
    sha256: "64d7fe9f06f2dba41de1b003224270f90840a2fd727aa5f5659a7a98ae8df7a2",
  }),
  Object.freeze({
    bytes: 824447,
    id: "shipping-android-bridge",
    license: "UNSPECIFIED_PROJECT_SOURCE",
    path: "thor-shop-android/app/src/main/java/com/gruning/mimir/MimirBridge.java",
    repositoryRevision: "b34752d0290ef1a34199aa6c6dd4258646fe4c06",
    roles: Object.freeze(["shipping-runtime-reference-only"]),
    sha256: "876d50b9c5552c6c67e14f30d3e5aef0ebdcf2e1c48622019f48348f0fb64ee0",
  }),
]);

const IMPORT_PROVENANCE_INTERPRETATION_INPUTS = Object.freeze([
  Object.freeze({
    bytes: 54762,
    id: "platform-registry-generator",
    path: "tools/platform-registry/generate-platform-registry.mjs",
    roles: Object.freeze(["generator-semantics", "registry-revision"]),
    sha256: "9a9c2b9c57493dd86ce97a4c9b236ff8be53bf7551d70b19ca02efb994a02c17",
  }),
  Object.freeze({
    bytes: 29751,
    id: "shipping-catalog-relations",
    path: RELATION_FIXTURE_PATH,
    roles: Object.freeze(["catalog-canonicalization", "variant-classification"]),
    sha256: "bc8e769312f97845c3accd778142411911679e805d1a8bf42b6baf5c54423461",
  }),
]);

// The "unknown" recognition-state folder. In the retired app.js this was the one row in
// `platforms` with `id: "unknown"` (folder `_inbox`), sitting alongside the 21 real runtime
// systems. It is not one of the 21 vendored runtime-system records because it identifies the
// absence of a match, not a platform; frozen here for the same reason the rest of
// `importProvenance` is frozen.
const UNKNOWN_RECOGNITION_FOLDER = "_inbox";

// The three shipping-derived files this tree owns under `platforms/sources/`. The catalog is
// byte-identical to its original import. The RetroArch snapshot has only its generatedBy provenance
// normalized; registry records are unchanged. The verifier pins its exact normalized bytes, while
// IMPORT_PROVENANCE_SOURCES retains the original import hash (see platforms/WIRE_FORMAT.md).
// `mimir-runtime-systems.json` replaces the `app.js` JavaScript parse: it is the 21 runtime-system
// records, declaratively, emitted once from this generator's own former output. Deliberately not
// listed here: `mimir-native-platforms.json`. It is now read (see `loadNativePlatformSource` /
// `NATIVE_NAMESPACE` below) but it is not shipping data and carries no `IMPORT_PROVENANCE_SOURCES`
// history, so it stays out of this frozen, shipping-only list and out of `importProvenance`
// entirely; it is tracked for integrity as its own `nativeSources` row in the source lock instead.
const VENDORED_SOURCE_FILES = Object.freeze([
  Object.freeze({
    file: "mimir-platform-catalog.json",
    id: "mimir-platform-catalog",
    license: "MIXED_PROJECT_METADATA_AND_NOTICED_ASSETS",
    path: "platforms/sources/mimir-platform-catalog.json",
    roles: Object.freeze(["catalog-identities", "discovery-bindings", "standalone-target-observations", "visual-assets"]),
  }),
  Object.freeze({
    file: "mimir-retroarch-core-registry.json",
    id: "mimir-retroarch-core-registry",
    license: "UNSPECIFIED_PROJECT_SOURCE",
    path: "platforms/sources/mimir-retroarch-core-registry.json",
    roles: Object.freeze(["retroarch-registration-ids", "retroarch-platform-core-registrations"]),
  }),
  Object.freeze({
    file: "mimir-runtime-systems.json",
    id: "mimir-runtime-systems",
    license: "MIMIR_OWNED_DERIVED_DATA",
    path: "platforms/sources/mimir-runtime-systems.json",
    roles: Object.freeze(["baseline-runtime-systems", "runtime-folder-aliases"]),
  }),
]);

const RUNTIME_SYSTEM_RECORD_KEYS = Object.freeze([
  "extensions",
  "externalId",
  "folder",
  "folderAliases",
  "id",
  "label",
  "platformId",
  "sourceOrdinal",
]);

const CONTENT_CONTRACT_RECONCILIATIONS = Object.freeze([
  Object.freeze({
    catalogId: "dreamcast",
    extension: "bin",
    runtimeSystemId: "dreamcast",
  }),
]);

export const GENERATED_PATHS = Object.freeze({
  "platforms/mimir-platform-sources.lock.json": "sourceLock",
  "platforms/mimir-platform-registry.v2.json": "registry",
  "platforms/mimir-platform-certification.v1.json": "certification",
  "platforms/support-set-manifest.json": "supportSetManifest",
});

const REWRITE_ALIASES = Object.freeze({
  megadrive: "genesis",
  mastersystem: "sega_master_system_mark_iii",
  gamegear: "sega_game_gear",
  sg1000: "sega_sg_1000",
  segacd: "sega_mega_cd_sega_cd",
  sega32x: "sega_32x",
  pcengine: "nec_pc_engine_turbografx_16",
  pcenginecd: "nec_pc_engine_cd_turbografx_cd",
  atari2600: "atari_2600",
  atari7800: "atari_7800",
  atarilynx: "atari_lynx",
  atarijaguar: "atari_jaguar",
  wonderswan: "bandai_wonderswan",
  wonderswancolor: "bandai_wonderswan_color",
  ngp: "snk_neo_geo_pocket",
  ngpc: "snk_neo_geo_pocket_color",
  virtualboy: "nintendo_virtual_boy",
  pokemini: "nintendo_pokemon_mini",
  wii: "nintendo_wii",
});

function fail(message) {
  throw new Error(message);
}

function assert(condition, message) {
  if (!condition) fail(message);
}

function sha256Bytes(value) {
  return createHash("sha256").update(value).digest("hex");
}

export function deepSort(value) {
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

export function canonicalJson(value) {
  return JSON.stringify(deepSort(value));
}

export function digest(value) {
  return sha256Bytes(Buffer.from(canonicalJson(value), "utf8"));
}

export function prettyJson(value) {
  return `${JSON.stringify(deepSort(value), null, 2)}\n`;
}

function sortedUnique(values) {
  return [...new Set(values)].sort((left, right) => left.localeCompare(right));
}

function sortedById(values) {
  return [...values].sort((left, right) => left.id.localeCompare(right.id));
}

function scopedId(namespace, value) {
  return `${namespace}:${value}`;
}

function canonicalPlatformId(namespace, value, catalogRelationById = new Map()) {
  if (namespace === "shipping-runtime" && value === "wii") return "nintendo_wii";
  if (namespace === "shipping-catalog") {
    const classification = catalogRelationById.get(value);
    assert(classification, `Catalog identity ${value} has no explicit relation classification`);
    return classification.platformId;
  }
  if (namespace === "shipping-retroarch-registration" && (value === "neo_geo" || value === "snk_neo_geo")) {
    return "snk_neo_geo";
  }
  return value;
}

function loadCatalogRelations(catalog, importProvenance) {
  const absolutePath = resolve(REPO_ROOT, RELATION_FIXTURE_PATH);
  const bytes = readFileSync(absolutePath);
  const fixture = JSON.parse(bytes.toString("utf8"));
  const catalogSource = importProvenance.sources.find((source) => source.id === "shipping-platform-catalog");
  assert(fixture.schemaVersion === "mimirShippingCatalogRelations.v1", "Unexpected catalog relation fixture schema");
  assert(
    fixture.sourceCatalogSha256 === catalogSource.sha256,
    "Catalog relation fixture is not bound to the locked shipping catalog SHA-256",
  );
  assert(!Object.hasOwn(fixture, "defaultRelation"), "Catalog relation fixture must not contain fail-open defaultRelation");
  assert(Array.isArray(fixture.canonicalIds), "Catalog relation fixture canonicalIds must be an array");
  assert(Array.isArray(fixture.variantLinks), "Catalog relation fixture variantLinks must be an array");
  assert(fixture.canonicalIds.length === EXPECTED.canonicalPlatforms, "Catalog relation fixture must contain 225 canonical IDs");
  assert(fixture.variantLinks.length === EXPECTED.variantLinks, "Catalog relation fixture must contain 178 variant links");

  const catalogById = new Map(catalog.entries.map((entry) => [entry.id, entry]));
  assert(catalogById.size === EXPECTED.catalogEntries, "Shipping catalog contains duplicate IDs");
  const relationById = new Map();
  const canonicalIds = new Set();
  assert(
    canonicalJson(fixture.canonicalIds) === canonicalJson([...fixture.canonicalIds].sort()),
    "Catalog relation fixture canonicalIds must be sorted",
  );
  for (const catalogId of fixture.canonicalIds) {
    assert(!canonicalIds.has(catalogId), `Duplicate canonical catalog ID ${catalogId}`);
    assert(catalogById.has(catalogId), `Canonical catalog ID ${catalogId} does not exist`);
    canonicalIds.add(catalogId);
    relationById.set(catalogId, { catalogId, platformId: catalogId, relation: "CANONICAL" });
  }

  let previousVariantId = "";
  for (const link of fixture.variantLinks) {
    assert(!relationById.has(link.catalogId), `Duplicate catalog relation for ${link.catalogId}`);
    assert(catalogById.has(link.catalogId), `Catalog relation source ${link.catalogId} does not exist`);
    assert(catalogById.has(link.platformId), `Catalog relation target ${link.platformId} does not exist`);
    assert(link.catalogId !== link.platformId, `Catalog variant ${link.catalogId} targets itself`);
    assert(canonicalIds.has(link.platformId), `Catalog variant ${link.catalogId} targets non-canonical ${link.platformId}`);
    assert(VARIANT_RELATIONS.has(link.relation), `Unsupported catalog relation ${link.relation} for ${link.catalogId}`);
    assert(previousVariantId < link.catalogId, "Catalog relation fixture variantLinks must be unique and sorted");
    previousVariantId = link.catalogId;
    relationById.set(link.catalogId, link);
  }
  assert(relationById.size === EXPECTED.catalogEntries, "Catalog relation fixture does not classify exactly 403 IDs");
  assert(
    canonicalJson([...relationById.keys()].sort()) === canonicalJson([...catalogById.keys()].sort()),
    "Canonical IDs and variant links do not form the exact shipping-catalog union",
  );

  const relationCounts = Object.fromEntries(
    CATALOG_RELATIONS.map((relation) => [
      relation,
      [...relationById.values()].filter((classification) => classification.relation === relation).length,
    ]),
  );
  assert(relationCounts.CANONICAL === EXPECTED.canonicalPlatforms, "Canonical relation denominator drifted");
  assert(relationCounts.HARDWARE_VARIANT === EXPECTED.hardwareVariants, "Hardware relation denominator drifted");
  assert(relationCounts.CONTENT_VARIANT === EXPECTED.contentVariants, "Content relation denominator drifted");
  assert(relationCounts.PRESENTATION_ALIAS === EXPECTED.presentationAliases, "Presentation relation denominator drifted");
  assert(relationCounts.REGIONAL_VARIANT === EXPECTED.regionalVariants, "Regional relation denominator drifted");
  assert(relationCounts.SOFTWARE_FAMILY === EXPECTED.softwareFamilies, "Software-family relation denominator drifted");

  for (const { catalogId, platformId, relation } of ADVERSARIAL_RELATION_FIXTURES) {
    const observed = relationById.get(catalogId);
    assert(
      observed?.platformId === platformId && observed?.relation === relation,
      `Required catalog relation ${catalogId} -> ${platformId} (${relation}) is missing`,
    );
  }

  return {
    fixture,
    fixtureBytes: bytes.length,
    fixtureSha256: sha256Bytes(bytes),
    relationCounts,
    relationById,
  };
}

function assertUniqueStringArray(value, label) {
  assert(Array.isArray(value), `${label} must be an array`);
  assert(value.every((item) => typeof item === "string"), `${label} must contain only strings`);
  assert(new Set(value).size === value.length, `${label} must contain unique values`);
}

function assertRuntimeSystemRecordShape(record) {
  assert(record && typeof record === "object" && !Array.isArray(record), "Vendored runtime-system record must be an object");
  assert(
    canonicalJson(Object.keys(record).sort()) === canonicalJson([...RUNTIME_SYSTEM_RECORD_KEYS].sort()),
    `Vendored runtime-system record ${record.id ?? "?"} must contain exactly the frozen record fields`,
  );
  assert(
    record.externalId && canonicalJson(Object.keys(record.externalId).sort()) === canonicalJson(["namespace", "value"]),
    `Vendored runtime-system record ${record.id} externalId must be exactly {namespace, value}`,
  );
  assert(
    record.externalId.namespace === "shipping-runtime",
    `Vendored runtime-system record ${record.id} has an unexpected externalId namespace`,
  );
  assert(
    record.id === scopedId("shipping-runtime", record.externalId.value),
    `Vendored runtime-system record ${record.id} id does not match its externalId`,
  );
  assert(typeof record.label === "string" && record.label.length > 0, `Vendored runtime-system record ${record.id} label must be non-empty`);
  assert(typeof record.folder === "string" && record.folder.length > 0, `Vendored runtime-system record ${record.id} folder must be non-empty`);
  assert(typeof record.platformId === "string" && record.platformId.length > 0, `Vendored runtime-system record ${record.id} platformId must be non-empty`);
  assert(typeof record.sourceOrdinal === "number", `Vendored runtime-system record ${record.id} sourceOrdinal must be a number`);
  assertUniqueStringArray(record.extensions, `${record.id}.extensions`);
  assertUniqueStringArray(record.folderAliases, `${record.id}.folderAliases`);
}

function readVendoredFile(sourceRoot, fileName, label) {
  const absolutePath = resolve(sourceRoot, fileName);
  assert(existsSync(absolutePath), `Missing vendored source ${label} (${absolutePath})`);
  return readFileSync(absolutePath);
}

function loadVendoredSources(sourceRoot) {
  const buffers = new Map();
  const sourceRows = VENDORED_SOURCE_FILES.map((source) => {
    const bytes = readVendoredFile(sourceRoot, source.file, source.path);
    buffers.set(source.id, bytes);
    return {
      bytes: bytes.length,
      id: source.id,
      license: source.license,
      path: source.path,
      roles: [...source.roles],
      sha256: sha256Bytes(bytes),
    };
  });
  return { buffers, sourceRows };
}

function parseVendoredSources(vendored) {
  const catalog = JSON.parse(vendored.buffers.get("mimir-platform-catalog").toString("utf8"));
  const retroArch = JSON.parse(vendored.buffers.get("mimir-retroarch-core-registry").toString("utf8"));
  const shippingRuntimeSystems = JSON.parse(vendored.buffers.get("mimir-runtime-systems").toString("utf8"));

  assert(catalog.schemaVersion === "mimirPlatformCatalog.v1", `Unexpected catalog schema ${catalog.schemaVersion}`);
  assert(retroArch.schemaVersion === "mimirRetroarchCoreRegistry.v1", `Unexpected RetroArch schema ${retroArch.schemaVersion}`);
  assert(Array.isArray(catalog.entries), "Shipping catalog entries are not an array");
  assert(retroArch.platforms && typeof retroArch.platforms === "object", "Shipping RetroArch platforms are not an object");
  assert(catalog.entries.length === EXPECTED.catalogEntries, `Expected ${EXPECTED.catalogEntries} catalog entries, found ${catalog.entries.length}`);
  assert(Object.keys(retroArch.platforms).length === EXPECTED.retroArchIds, `Expected ${EXPECTED.retroArchIds} RetroArch IDs`);

  assert(Array.isArray(shippingRuntimeSystems), "Vendored runtime-systems file is not an array");
  assert(
    shippingRuntimeSystems.length === EXPECTED.runtimeSystems,
    `Expected ${EXPECTED.runtimeSystems} vendored runtime systems, found ${shippingRuntimeSystems.length}`,
  );
  for (const record of shippingRuntimeSystems) assertRuntimeSystemRecordShape(record);

  const uniqueIds = (rows, label, key = (row) => row.id) => {
    const ids = rows.map(key);
    assert(new Set(ids).size === ids.length, `${label} contains duplicate IDs`);
  };
  uniqueIds(catalog.entries, "Catalog");
  uniqueIds(shippingRuntimeSystems, "Vendored runtime systems");

  const catalogIds = new Set(catalog.entries.map((entry) => entry.id));
  for (const retroArchId of Object.keys(retroArch.platforms)) {
    assert(catalogIds.has(retroArchId), `RetroArch ID ${retroArchId} has no catalog identity`);
  }

  return {
    catalog,
    retroArch,
    shippingRuntimeSystems: sortedById(shippingRuntimeSystems),
  };
}

// --- Mimir-native platform extension point ------------------------------------------------
// Unlike `VENDORED_SOURCE_FILES` above, `mimir-native-platforms.json` is not shipping data: it
// carries no `IMPORT_PROVENANCE_SOURCES` history and it does not define aliases onto an existing
// catalog platform the way `mimir-runtime-systems.json` does — a native record *is* the platform's
// display/folder identity, because no catalog entry exists for it. Its record count is
// intentionally open (0..N, not frozen like `EXPECTED.canonicalPlatforms`): `buildRegistry` binds
// `collections.platforms` to the shipping canonical fixture UNION whatever this file currently
// declares, so emptying it must reproduce the historical shipping-only registry byte-for-byte —
// that reversibility is the proof this extension point is genuinely wired, not hardcoded to one
// record.
const NATIVE_PLATFORMS_FILE = "mimir-native-platforms.json";
const NATIVE_PLATFORMS_SCHEMA_VERSION = "mimirNativePlatforms.v1";
const NATIVE_NAMESPACE = "mimir-native";
const NATIVE_FOLDER_CONTRACT_SCHEMA_VERSION = "mimirPlatformFolderContract.v1";
const NATIVE_CONTENT_CONTRACT_SCHEMA_VERSION = "mimirPlatformContentRules.v1";
// One of the exact literals `GeneratedRegistryAdapter.flatDumpPolicy()` (core-platform, Kotlin)
// accepts; anything else throws at load time. Every native extension is asserted globally unique
// below, so a flat dump never needs a folder to disambiguate it from another platform.
const NATIVE_FLAT_DUMP_POLICY = "unique_extension_only";
const NATIVE_PLATFORM_RECORD_KEYS = Object.freeze([
  "displayName",
  "extensions",
  "externalId",
  "folder",
  "folderAliases",
  "id",
  "label",
  "manufacturer",
  "platformId",
  "shortName",
  "sourceOrdinal",
  "type",
]);

function assertNativePlatformRecordShape(record, reservedPlatformIds) {
  assert(record && typeof record === "object" && !Array.isArray(record), "Mimir-native platform record must be an object");
  assert(
    canonicalJson(Object.keys(record).sort()) === canonicalJson([...NATIVE_PLATFORM_RECORD_KEYS].sort()),
    `Mimir-native platform record ${record.id ?? "?"} must contain exactly the frozen native record fields`,
  );
  assert(
    record.externalId && canonicalJson(Object.keys(record.externalId).sort()) === canonicalJson(["namespace", "value"]),
    `Mimir-native platform record ${record.id} externalId must be exactly {namespace, value}`,
  );
  assert(
    record.externalId.namespace === NATIVE_NAMESPACE,
    `Mimir-native platform record ${record.id} externalId namespace must be ${NATIVE_NAMESPACE}`,
  );
  assert(
    record.id === scopedId(NATIVE_NAMESPACE, record.externalId.value),
    `Mimir-native platform record ${record.id} id does not match its externalId`,
  );
  assert(
    /^[a-z0-9][a-z0-9_]*$/.test(record.platformId),
    `Mimir-native platform record ${record.id} platformId must match the shipping platformId pattern`,
  );
  assert(
    record.externalId.value === record.platformId,
    `Mimir-native platform record ${record.id} externalId.value must equal its own platformId`,
  );
  for (const field of ["displayName", "label", "manufacturer", "shortName", "type"]) {
    assert(typeof record[field] === "string" && record[field].length > 0, `Mimir-native platform record ${record.id} ${field} must be non-empty`);
  }
  assert(
    typeof record.folder === "string" && /^[a-z0-9]+$/.test(record.folder),
    `Mimir-native platform record ${record.id} folder must be a plain lowercase segment`,
  );
  assert(typeof record.sourceOrdinal === "number", `Mimir-native platform record ${record.id} sourceOrdinal must be a number`);
  assertUniqueStringArray(record.extensions, `${record.id}.extensions`);
  assert(record.extensions.length > 0, `Mimir-native platform record ${record.id} must declare at least one extension`);
  for (const extension of record.extensions) {
    assert(/^[a-z0-9]+$/.test(extension), `Mimir-native platform record ${record.id} extension '${extension}' must be a plain lowercase token`);
  }
  assertUniqueStringArray(record.folderAliases, `${record.id}.folderAliases`);
  assert(
    !reservedPlatformIds.has(record.platformId),
    `Mimir-native platform ${record.platformId} collides with an existing shipping catalog, runtime, rewrite-alias, or RetroArch identity`,
  );
}

// Reads and validates `mimir-native-platforms.json`. Deliberately parallel to, but never merged
// with, `loadVendoredSources`/`parseVendoredSources`: shipping-derived input bytes are strictly
// locked, with frozen original-import provenance; native additive data has no shipping import.
function loadNativePlatformSource(sourceRoot, reservedPlatformIds) {
  const absolutePath = resolve(sourceRoot, NATIVE_PLATFORMS_FILE);
  assert(existsSync(absolutePath), `Missing Mimir-native platform source (${absolutePath})`);
  const bytes = readFileSync(absolutePath);
  const parsed = JSON.parse(bytes.toString("utf8"));
  assert(parsed && typeof parsed === "object" && !Array.isArray(parsed), "Mimir-native platform source must be an object");
  assert(
    canonicalJson(Object.keys(parsed).sort()) === canonicalJson(["platforms", "schemaVersion"]),
    "Mimir-native platform source must contain exactly {platforms, schemaVersion}",
  );
  assert(
    parsed.schemaVersion === NATIVE_PLATFORMS_SCHEMA_VERSION,
    `Unexpected Mimir-native platform schema ${parsed.schemaVersion}`,
  );
  assert(Array.isArray(parsed.platforms), "Mimir-native platform source platforms must be an array");

  const seenIds = new Set();
  const seenPlatformIds = new Set();
  const seenFolders = new Set();
  const seenExtensions = new Set();
  for (const record of parsed.platforms) {
    assertNativePlatformRecordShape(record, reservedPlatformIds);
    assert(!seenIds.has(record.id), `Mimir-native platform id ${record.id} is duplicated`);
    seenIds.add(record.id);
    assert(!seenPlatformIds.has(record.platformId), `Mimir-native platformId ${record.platformId} is duplicated`);
    seenPlatformIds.add(record.platformId);
    assert(!seenFolders.has(record.folder), `Mimir-native folder ${record.folder} is claimed by more than one native platform`);
    seenFolders.add(record.folder);
    for (const extension of record.extensions) {
      assert(!seenExtensions.has(extension), `Mimir-native extension ${extension} is claimed by more than one native platform`);
      seenExtensions.add(extension);
    }
  }

  return {
    bytes,
    path: "platforms/sources/" + NATIVE_PLATFORMS_FILE,
    records: sortedById(parsed.platforms.map((record) => ({ ...record }))),
    sha256: sha256Bytes(bytes),
  };
}

function buildNativeFolderContract(record) {
  return {
    aliases: sortedUnique(record.folderAliases),
    canonicalFolder: record.folder,
    caseSensitive: false,
    flatDumpPolicy: NATIVE_FLAT_DUMP_POLICY,
    platformId: record.platformId,
    schemaVersion: NATIVE_FOLDER_CONTRACT_SCHEMA_VERSION,
    truth:
      "Mimir-native platforms are not folder-scoped shipping data: this contract is synthesized " +
      "from the extension-point record so recognition still resolves to a real folder identity.",
  };
}

function buildNativeContentContract(record) {
  const contract = {
    baseGameExtensions: sortedUnique(record.extensions),
    conditionalContent: [],
    hiddenContentKinds: [],
    schemaVersion: NATIVE_CONTENT_CONTRACT_SCHEMA_VERSION,
    sidecarRules: [],
    supportExtensions: [],
    supportFolders: [],
    supportReasons: {},
    truth:
      "Each extension is a declarative shortcut a companion launcher writes for one installed or " +
      "user-added title; the file's presence is the entire gamelist contract, so there is no disc, " +
      "sidecar, or hidden support-file grouping to reconcile.",
  };
  assertContentContract(contract, `${record.id}.contentContract`);
  return contract;
}

function buildNativeContentProfiles(nativeRecords) {
  return sortedById(
    nativeRecords.map((record) => ({
      contentContract: buildNativeContentContract(record),
      discoveryStatus: "BOUND",
      externalId: { namespace: NATIVE_NAMESPACE, value: record.externalId.value },
      id: scopedId("mimir-native-profile", record.externalId.value),
      platformId: record.platformId,
    })),
  );
}

// `PlatformRegistry`'s `extensionCandidates()` (core-platform, Kotlin) reads extension rules only
// through `document.importBindings` — never straight from `document.contentProfiles` or
// `document.platforms` — so a platform with a content profile but no import binding is invisible
// to extension recognition. Shipping platforms get theirs from `buildImportBindings` (one per
// catalog entry with extensions); native platforms have no catalog entry, so this is their
// equivalent, with `catalogEntryId: null` (the kernel's `ImportBindingDefinition.catalogEntryId`
// is nullable for exactly this case; `WireImportBinding.catalogEntryId` defaults to null too).
function buildNativeImportBindings(nativeRecords) {
  return sortedById(
    nativeRecords.map((record) => ({
      catalogEntryId: null,
      contentProfileId: scopedId("mimir-native-profile", record.externalId.value),
      id: scopedId("mimir-native-import", record.externalId.value),
      platformId: record.platformId,
      sourceExternalId: { namespace: NATIVE_NAMESPACE, value: record.externalId.value },
    })),
  );
}

function buildImportProvenance() {
  const sources = IMPORT_PROVENANCE_SOURCES.map((source) => ({ ...source, roles: [...source.roles] }));
  const interpretationInputs = IMPORT_PROVENANCE_INTERPRETATION_INPUTS.map((input) => ({ ...input, roles: [...input.roles] }));
  const sourceSetSha256 = digest({
    repositoryRevision: IMPORT_PROVENANCE_REPOSITORY.revision,
    sources: sources.map(({ id, path, sha256 }) => ({ id, path, sha256 })),
  });
  const lockedInputSetSha256 = digest({
    generatorSemanticsVersion: GENERATOR_SEMANTICS_VERSION,
    interpretationInputs: interpretationInputs.map(({ id, path, sha256 }) => ({ id, path, sha256 })),
    sourceSetSha256,
  });
  // Belt-and-suspenders: these two digests are computed from the frozen leaf constants above,
  // never pasted as independent literals, so a mistyped hex digit fails loudly right here
  // instead of surfacing as an unexplained byte diff on the final registry.
  assert(
    sourceSetSha256 === "567bea49645fae7e7b686a2072624206f64aa515d4ff3c64faadca809bda913f",
    "Frozen import-provenance source-set digest drifted from the historical shipping snapshot",
  );
  assert(
    lockedInputSetSha256 === "97f39e7ed118723f94ca66fb1be83465705118db6dd4585f57942560b2c7571c",
    "Frozen import-provenance locked-input digest drifted from the historical shipping snapshot",
  );
  return {
    interpretationInputs,
    lockedInputSetSha256,
    repository: IMPORT_PROVENANCE_REPOSITORY,
    sources,
    sourceSetSha256,
  };
}

function buildSourceLock(vendored, catalog, catalogRelations, importProvenance, nativePlatformSource) {
  const assetSource = catalog.assetSource ?? {};
  const generatorBytes = readFileSync(resolve(REPO_ROOT, GENERATOR_ID));
  const liveInterpretationInputs = [
    {
      bytes: generatorBytes.length,
      id: "platform-registry-generator",
      path: GENERATOR_ID,
      roles: ["generator-semantics", "registry-revision"],
      sha256: sha256Bytes(generatorBytes),
    },
    {
      bytes: catalogRelations.fixtureBytes,
      id: "shipping-catalog-relations",
      path: RELATION_FIXTURE_PATH,
      roles: ["catalog-canonicalization", "variant-classification"],
      sha256: catalogRelations.fixtureSha256,
    },
  ].sort((left, right) => left.id.localeCompare(right.id));

  return {
    digestCanonicalization: DIGEST_CANONICALIZATION,
    digestAlgorithm: "SHA-256",
    generatedBy: GENERATOR_ID,
    generatorSemanticsVersion: GENERATOR_SEMANTICS_VERSION,
    importProvenance,
    interpretationInputs: liveInterpretationInputs,
    nativeSources: [
      {
        bytes: nativePlatformSource.bytes.length,
        id: "mimir-native-platforms",
        license: "MIMIR_OWNED_DERIVED_DATA",
        path: nativePlatformSource.path,
        roles: ["mimir-native-platform-extension-point"],
        sha256: nativePlatformSource.sha256,
      },
    ],
    schemaVersion: SOURCE_LOCK_SCHEMA_VERSION,
    sources: vendored.sourceRows,
    upstreamAssets: [
      {
        license: assetSource.license,
        name: assetSource.name,
        noticePath: `thor-torrent-shop/${assetSource.noticeFile}`,
        path: assetSource.path,
        revision: assetSource.sourceCommit,
        url: assetSource.repo,
      },
    ],
  };
}

function buildStandaloneTargetObservations(catalogEntries, catalogRelationById) {
  const rows = [];
  for (const entry of catalogEntries) {
    entry.standaloneTargets.forEach((target, index) => {
      rows.push({
        catalogEntryId: scopedId("shipping-catalog", entry.id),
        dispatchContract: null,
        id: scopedId("shipping-catalog-standalone", `${entry.id}:${String(index + 1).padStart(2, "0")}`),
        label: target.label,
        observationOnly: true,
        packageName: target.packageName,
        platformId: canonicalPlatformId("shipping-catalog", entry.id, catalogRelationById),
        sourceOrdinal: index,
        sourceStatus: target.status,
      });
    });
  }
  return sortedById(rows);
}

const CONTENT_CONTRACT_KEYS = Object.freeze([
  "baseGameExtensions",
  "conditionalContent",
  "hiddenContentKinds",
  "schemaVersion",
  "sidecarRules",
  "supportExtensions",
  "supportFolders",
  "supportReasons",
  "truth",
]);

function assertContentContract(contract, label) {
  assert(contract && typeof contract === "object" && !Array.isArray(contract), `${label} must be a non-null object`);
  assert(
    canonicalJson(Object.keys(contract).sort()) === canonicalJson([...CONTENT_CONTRACT_KEYS].sort()),
    `${label} must contain exactly the strict content-contract fields`,
  );
  assert(contract.schemaVersion === "mimirPlatformContentRules.v1", `${label} has an unsupported schemaVersion`);
  for (const key of [
    "baseGameExtensions",
    "hiddenContentKinds",
    "supportExtensions",
    "supportFolders",
  ]) {
    assertUniqueStringArray(contract[key], `${label}.${key}`);
  }
  for (const key of ["conditionalContent", "sidecarRules"]) {
    assert(Array.isArray(contract[key]), `${label}.${key} must be an array`);
    assert(
      contract[key].every((item) => item && typeof item === "object" && !Array.isArray(item)),
      `${label}.${key} must contain only objects`,
    );
  }
  assert(
    contract.supportReasons &&
      typeof contract.supportReasons === "object" &&
      !Array.isArray(contract.supportReasons) &&
      Object.values(contract.supportReasons).every((reason) => typeof reason === "string"),
    `${label}.supportReasons must map to strings`,
  );
  assert(typeof contract.truth === "string" && contract.truth.length > 0, `${label}.truth must be non-empty`);
}

function assertContentProfileSemantics(profiles) {
  let boundCount = 0;
  let gapCount = 0;
  for (const profile of profiles) {
    if (profile.discoveryStatus === "BOUND") {
      boundCount += 1;
      assertContentContract(profile.contentContract, `${profile.id}.contentContract`);
    } else if (profile.discoveryStatus === "GAP") {
      gapCount += 1;
      assert(profile.contentContract === null, `${profile.id} GAP content contract must be null`);
    } else {
      fail(`${profile.id} has unsupported discovery status ${profile.discoveryStatus}`);
    }
  }
  assert(boundCount === EXPECTED.importBindings, "BOUND content-profile denominator drifted");
  assert(gapCount === EXPECTED.retroArchDiscoveryGap, "GAP content-profile denominator drifted");
}

function reconciledContentContract(entry, runtimeSystemsByExternalId) {
  const contract = structuredClone(entry.contentRules);
  const reconciliation = CONTENT_CONTRACT_RECONCILIATIONS.find((candidate) => candidate.catalogId === entry.id);
  if (!reconciliation) return contract;

  const runtime = runtimeSystemsByExternalId.get(reconciliation.runtimeSystemId);
  assert(runtime, `${entry.id} reconciliation has no vendored runtime row`);
  assert(runtime.extensions.includes("cue"), `${entry.id} runtime must declare cue for .bin sidecar grouping`);
  assert(
    !runtime.extensions.includes(reconciliation.extension),
    `${entry.id} reconciliation must only fill an extension absent from the runtime row`,
  );
  assert(!contract.baseGameExtensions.includes(reconciliation.extension), `${entry.id} already declares ${reconciliation.extension}`);

  contract.baseGameExtensions = sortedUnique([...contract.baseGameExtensions, reconciliation.extension]);
  contract.conditionalContent = [
    ...contract.conditionalContent,
    {
      extensions: [reconciliation.extension],
      kind: "disc_sidecar",
    },
  ];
  contract.sidecarRules = [
    ...contract.sidecarRules,
    {
      extensions: [reconciliation.extension],
      kind: "AMBIGUOUS_DISC_TRACK",
    },
  ];
  return contract;
}

function buildContentProfiles(catalogEntries, retroArchPlatforms, catalogRelationById, runtimeSystemsByExternalId) {
  const profiles = [];
  const discoveryProfileByExternalId = new Map();

  for (const entry of catalogEntries.filter((candidate) => candidate.fileExtensions.length > 0)) {
    const id = scopedId("shipping-catalog-profile", entry.id);
    discoveryProfileByExternalId.set(entry.id, id);
    profiles.push({
      contentContract: reconciledContentContract(entry, runtimeSystemsByExternalId),
      discoveryStatus: "BOUND",
      externalId: { namespace: "shipping-catalog", value: entry.id },
      id,
      platformId: canonicalPlatformId("shipping-catalog", entry.id, catalogRelationById),
    });
  }

  for (const retroArchId of Object.keys(retroArchPlatforms).sort()) {
    if (discoveryProfileByExternalId.has(retroArchId)) continue;
    profiles.push({
      contentContract: null,
      discoveryStatus: "GAP",
      externalId: { namespace: "shipping-retroarch-registration", value: retroArchId },
      id: scopedId("shipping-retroarch-profile", retroArchId),
      platformId: canonicalPlatformId("shipping-retroarch-registration", retroArchId),
    });
  }

  const sortedProfiles = sortedById(profiles);
  assertContentProfileSemantics(sortedProfiles);
  return { discoveryProfileByExternalId, profiles: sortedProfiles };
}

function buildImportBindings(catalogEntries, discoveryProfileByExternalId, catalogRelationById) {
  return sortedById(
    catalogEntries
      .filter((entry) => entry.fileExtensions.length > 0)
      .map((entry) => ({
        catalogEntryId: scopedId("shipping-catalog", entry.id),
        contentProfileId: discoveryProfileByExternalId.get(entry.id),
        id: scopedId("shipping-catalog-import", entry.id),
        platformId: canonicalPlatformId("shipping-catalog", entry.id, catalogRelationById),
        sourceExternalId: { namespace: "shipping-catalog", value: entry.id },
      })),
  );
}

function buildRetroArchRegistrations(retroArchPlatforms, discoveryProfileByExternalId) {
  return Object.entries(retroArchPlatforms)
    .map(([externalId, row]) => ({
      contentProfileId:
        discoveryProfileByExternalId.get(externalId) ?? scopedId("shipping-retroarch-profile", externalId),
      coreRegistrations: row.cores.map((core, index) => ({
        id: scopedId("shipping-retroarch-core", `${externalId}:${core.stem}`),
        isRecommended: core.stem === row.recommendedCore,
        label: core.label,
        sourceOrdinal: index,
        stem: core.stem,
      })),
      externalId: { namespace: "shipping-retroarch-registration", value: externalId },
      id: scopedId("shipping-retroarch", externalId),
      label: row.label,
      platformId: canonicalPlatformId("shipping-retroarch-registration", externalId),
      recommendedCore: row.recommendedCore,
    }))
    .sort((left, right) => left.id.localeCompare(right.id));
}

function buildCatalogEntries(catalogEntries, retroArchIds, standaloneRows, catalogRelations) {
  const catalogById = new Map(catalogEntries.map((entry) => [entry.id, entry]));
  const standaloneByCatalogId = new Map();
  for (const row of standaloneRows) {
    const values = standaloneByCatalogId.get(row.catalogEntryId) ?? [];
    values.push(row.id);
    standaloneByCatalogId.set(row.catalogEntryId, values);
  }

  return sortedById(
    catalogEntries.map((entry, sourceOrdinal) => {
      const catalogEntryId = scopedId("shipping-catalog", entry.id);
      const classification = catalogRelations.relationById.get(entry.id);
      assert(classification, `Catalog entry ${entry.id} has no explicit relation classification`);
      const platformId = canonicalPlatformId("shipping-catalog", entry.id, catalogRelations.relationById);
      const canonicalSource = catalogById.get(platformId);
      assert(canonicalSource, `Catalog relation target ${platformId} has no source entry`);
      return {
        display: {
          displayName: entry.displayName,
          manufacturer: entry.manufacturer,
          shortName: entry.shortName,
          type: entry.type,
        },
        externalId: { namespace: "shipping-catalog", value: entry.id },
        folderContract: entry.folderContract,
        id: catalogEntryId,
        importBindingId:
          entry.fileExtensions.length > 0 ? scopedId("shipping-catalog-import", entry.id) : null,
        launchConfidenceObservation: entry.launchConfidence,
        legacyRetroArchLabelObservations: [...entry.retroarchCore],
        platformId,
        relation: classification.relation,
        relationEvidence: {
          canonicalSourceIconFile: canonicalSource.sourceIconFile,
          classificationFixture: RELATION_FIXTURE_PATH,
          classificationFixtureSha256: catalogRelations.fixtureSha256,
          sourceIconFile: entry.sourceIconFile,
        },
        searchTokens: sortedUnique(entry.aliases),
        sourceOrdinal,
        standaloneTargetObservationIds: standaloneByCatalogId.get(catalogEntryId) ?? [],
        visualAssetId: scopedId("shipping-catalog-icon", entry.id),
        retroArchRegistrationIds: retroArchIds.has(entry.id)
          ? [scopedId("shipping-retroarch", entry.id)]
          : [],
      };
    }),
  );
}

function buildVisualAssets(catalogEntries) {
  return sortedById(
    catalogEntries.map((entry) => ({
      assetLicenseRef: "baxy-automatic-mit",
      catalogEntryId: scopedId("shipping-catalog", entry.id),
      id: scopedId("shipping-catalog-icon", entry.id),
      relativePath: entry.iconFile,
      sourceFile: entry.sourceIconFile,
    })),
  );
}

function buildPlatforms(
  catalogSourceEntries,
  runtimeSystems,
  retroArchPlatforms,
  catalogRelations,
  nativeRecords,
  { catalogEntries, contentProfiles, visualAssets },
) {
  const catalogById = new Map(catalogSourceEntries.map((entry) => [entry.id, entry]));
  const nativeById = new Map(nativeRecords.map((record) => [record.platformId, record]));
  const catalogAliasesByTarget = new Map();
  for (const entry of catalogSourceEntries) {
    const target = canonicalPlatformId("shipping-catalog", entry.id, catalogRelations.relationById);
    const aliases = catalogAliasesByTarget.get(target) ?? [];
    aliases.push({
      idMapVersion: ID_MAP_VERSION,
      namespace: "shipping-catalog",
      normalizedValue: entry.id,
      targetId: target,
    });
    catalogAliasesByTarget.set(target, aliases);
  }

  const runtimeAliasesByTarget = new Map();
  for (const row of runtimeSystems) {
    const target = canonicalPlatformId("shipping-runtime", row.externalId.value);
    const aliases = runtimeAliasesByTarget.get(target) ?? [];
    aliases.push({
      idMapVersion: ID_MAP_VERSION,
      namespace: "shipping-runtime",
      normalizedValue: row.externalId.value,
      targetId: target,
    });
    runtimeAliasesByTarget.set(target, aliases);
  }

  const rewriteAliasesByTarget = new Map();
  for (const [value, target] of Object.entries(REWRITE_ALIASES)) {
    const aliases = rewriteAliasesByTarget.get(target) ?? [];
    aliases.push({
      idMapVersion: ID_MAP_VERSION,
      namespace: "rewrite-v1",
      normalizedValue: value,
      targetId: target,
    });
    rewriteAliasesByTarget.set(target, aliases);
  }

  const nativeAliasesByTarget = new Map();
  for (const record of nativeRecords) {
    nativeAliasesByTarget.set(record.platformId, [
      {
        idMapVersion: ID_MAP_VERSION,
        namespace: NATIVE_NAMESPACE,
        normalizedValue: record.externalId.value,
        targetId: record.platformId,
      },
    ]);
  }

  const platformIds = new Set(
    catalogSourceEntries.map((entry) =>
      canonicalPlatformId("shipping-catalog", entry.id, catalogRelations.relationById),
    ),
  );
  for (const row of runtimeSystems) platformIds.add(canonicalPlatformId("shipping-runtime", row.externalId.value));
  for (const id of Object.keys(retroArchPlatforms)) {
    platformIds.add(canonicalPlatformId("shipping-retroarch-registration", id));
  }
  for (const target of Object.values(REWRITE_ALIASES)) platformIds.add(target);
  platformIds.delete("unknown");
  platformIds.delete("wii");
  platformIds.delete("neo_geo");
  for (const record of nativeRecords) platformIds.add(record.platformId);

  return [...platformIds]
    .sort()
    .map((id) => {
      const source = catalogById.get(id);
      const nativeRecord = nativeById.get(id);
      assert(source || nativeRecord, `Canonical platform ${id} has no catalog or Mimir-native display identity`);
      assert(!(source && nativeRecord), `Platform ${id} is claimed by both the shipping catalog and a Mimir-native record`);
      const aliases = [
        ...(catalogAliasesByTarget.get(id) ?? []),
        ...(runtimeAliasesByTarget.get(id) ?? []),
        ...(rewriteAliasesByTarget.get(id) ?? []),
        ...(nativeAliasesByTarget.get(id) ?? []),
      ].sort((left, right) =>
        `${left.namespace}:${left.normalizedValue}`.localeCompare(`${right.namespace}:${right.normalizedValue}`),
      );
      return {
        aliases,
        contentProfileIds: contentProfiles
          .filter((profile) => profile.platformId === id)
          .map((profile) => profile.id)
          .sort(),
        definitionStatus: "PROVISIONAL_SOURCE_ONLY",
        display: source
          ? {
              displayName: source.displayName,
              manufacturer: source.manufacturer,
              shortName: source.shortName,
              type: source.type,
            }
          : {
              displayName: nativeRecord.displayName,
              manufacturer: nativeRecord.manufacturer,
              shortName: nativeRecord.shortName,
              type: nativeRecord.type,
            },
        folder: source ? source.folderContract : buildNativeFolderContract(nativeRecord),
        id,
        identity: [],
        launchRouteIds: [],
        presentation: {
          catalogEntryIds: catalogEntries
            .filter((entry) => entry.platformId === id)
            .map((entry) => entry.id)
            .sort(),
          visualAssetKeys: visualAssets
            .filter((asset) => {
              const entry = catalogEntries.find((candidate) => candidate.id === asset.catalogEntryId);
              return entry?.platformId === id;
            })
            .map((asset) => asset.id)
            .sort(),
        },
      };
    });
}

function buildParityFixture(runtimeSystems, catalogDefaultActiveIds) {
  const expectedRuntimeIds = sortedUnique(runtimeSystems.map((row) => row.externalId.value));
  const observedCatalogIds = sortedUnique(catalogDefaultActiveIds);
  const observedSet = new Set(observedCatalogIds);
  const expectedSet = new Set(expectedRuntimeIds);
  return {
    canonicalResolution: {
      alias: { namespace: "shipping-runtime", value: "wii" },
      targetPlatformId: "nintendo_wii",
    },
    expectedRuntimeIds,
    extraCatalogDefaultIds: observedCatalogIds.filter((id) => !expectedSet.has(id)),
    id: "shipping-catalog-default-active-wii-drift",
    missingCatalogDefaultIds: expectedRuntimeIds.filter((id) => !observedSet.has(id)),
    observedCatalogDefaultIds: observedCatalogIds,
    status: "RESOLVED_BY_SCOPED_ALIAS",
  };
}

function assertFrozenCounts(records) {
  const retroArchCoreRegistrations = records.retroArchRegistrations.reduce(
    (total, registration) => total + registration.coreRegistrations.length,
    0,
  );
  const packageNames = new Set(records.standaloneTargetObservations.map((row) => row.packageName));
  const standaloneCatalogEntries = new Set(records.standaloneTargetObservations.map((row) => row.catalogEntryId));

  assert(records.shippingRuntimeSystems.length === EXPECTED.runtimeSystems, "Runtime system denominator drifted");
  assert(records.catalogEntries.length === EXPECTED.catalogEntries, "Catalog denominator drifted");
  assert(records.importBindings.length === EXPECTED.importBindings, "Import-binding denominator drifted");
  assert(records.retroArchRegistrations.length === EXPECTED.retroArchIds, "RetroArch ID denominator drifted");
  assert(retroArchCoreRegistrations === EXPECTED.retroArchCoreRegistrations, "RetroArch core-registration denominator drifted");
  assert(standaloneCatalogEntries.size === EXPECTED.standaloneEntries, "Standalone catalog-entry denominator drifted");
  assert(records.standaloneTargetObservations.length === EXPECTED.standaloneTargetRows, "Standalone target-row denominator drifted");
  assert(packageNames.size === EXPECTED.standalonePackages, "Standalone package denominator drifted");
}

function collectionCounts(collections) {
  const counts = Object.fromEntries(Object.entries(collections).map(([name, rows]) => [name, rows.length]));
  counts.retroArchCoreRegistrations = collections.retroArchRegistrations.reduce(
    (total, registration) => total + registration.coreRegistrations.length,
    0,
  );
  return counts;
}

function buildCatalogRelationFixtures(catalogEntries, catalogRelations) {
  const catalogById = new Map(catalogEntries.map((entry) => [entry.id, entry]));
  return catalogRelations.fixture.variantLinks
    .map((link) => ({
      canonicalSourceIconFile: catalogById.get(link.platformId).sourceIconFile,
      catalogEntryId: scopedId("shipping-catalog", link.catalogId),
      id: scopedId("shipping-catalog-relation", link.catalogId),
      platformId: link.platformId,
      relation: link.relation,
      sourceIconFile: catalogById.get(link.catalogId).sourceIconFile,
    }))
    .sort((left, right) => left.id.localeCompare(right.id));
}

function buildRegistryRevision(importProvenance, semanticOutputSha256) {
  const generatorInput = importProvenance.interpretationInputs.find(
    (input) => input.id === "platform-registry-generator",
  );
  assert(generatorInput, "Import provenance is missing the generator interpretation input");
  const revisionDigest = digest({
    generatorSemanticsVersion: GENERATOR_SEMANTICS_VERSION,
    generatorSha256: generatorInput.sha256,
    lockedInputSetSha256: importProvenance.lockedInputSetSha256,
    semanticOutputSha256,
  });
  return `pk00-${importProvenance.repository.revision.slice(0, 12)}-${revisionDigest.slice(0, 12)}`;
}

function buildRegistry(parsed, sourceLock, catalogRelations, nativeRecords) {
  const importProvenance = sourceLock.importProvenance;
  const shippingRuntimeSystems = parsed.shippingRuntimeSystems;
  const runtimeSystemsByExternalId = new Map(shippingRuntimeSystems.map((row) => [row.externalId.value, row]));
  const retroArchIds = new Set(Object.keys(parsed.retroArch.platforms));
  const standaloneTargetObservations = buildStandaloneTargetObservations(
    parsed.catalog.entries,
    catalogRelations.relationById,
  );
  const { discoveryProfileByExternalId, profiles: shippingContentProfiles } = buildContentProfiles(
    parsed.catalog.entries,
    parsed.retroArch.platforms,
    catalogRelations.relationById,
    runtimeSystemsByExternalId,
  );
  // Native profiles never enter `buildContentProfiles`/`assertContentProfileSemantics`: that
  // function's BOUND/GAP tally is frozen at the shipping `importBindings`/`retroArchDiscoveryGap`
  // denominators (106/10) and must stay exactly those numbers regardless of how many Mimir-native
  // platforms exist. Native profiles are merged in afterward, counted only by their own open tally.
  const nativeContentProfiles = buildNativeContentProfiles(nativeRecords);
  const contentProfiles = sortedById([...shippingContentProfiles, ...nativeContentProfiles]);
  const importBindings = buildImportBindings(
    parsed.catalog.entries,
    discoveryProfileByExternalId,
    catalogRelations.relationById,
  );
  const nativeImportBindings = buildNativeImportBindings(nativeRecords);
  const retroArchRegistrations = buildRetroArchRegistrations(
    parsed.retroArch.platforms,
    discoveryProfileByExternalId,
  );
  const catalogEntries = buildCatalogEntries(
    parsed.catalog.entries,
    retroArchIds,
    standaloneTargetObservations,
    catalogRelations,
  );
  const visualAssets = buildVisualAssets(parsed.catalog.entries);

  const collections = {
    catalogEntries,
    catalogRelationFixtures: buildCatalogRelationFixtures(parsed.catalog.entries, catalogRelations),
    contentProfiles,
    emulatorFamilies: [],
    firmwarePolicies: [],
    importBindings: sortedById([...importBindings, ...nativeImportBindings]),
    launchRoutes: [],
    parityFixtures: [buildParityFixture(shippingRuntimeSystems, parsed.catalog.defaultActivePlatformIds)],
    platforms: buildPlatforms(
      parsed.catalog.entries,
      shippingRuntimeSystems,
      parsed.retroArch.platforms,
      catalogRelations,
      nativeRecords,
      { catalogEntries, contentProfiles, visualAssets },
    ),
    recognitionStates: [
      {
        folder: UNKNOWN_RECOGNITION_FOLDER,
        id: "shipping-runtime-state:unknown",
        value: "unknown",
      },
    ],
    retroArchRegistrations,
    shippingRuntimeSystems,
    standaloneTargetObservations,
    supportSlices: [],
    visualAssets,
  };

  // `importBindings` here is deliberately the shipping-only (106) array, not `collections.importBindings`
  // (which also carries the native import bindings): the frozen denominator is a shipping fact and
  // must not shift just because a Mimir-native platform was added or removed.
  assertFrozenCounts({ ...collections, importBindings });
  assert(
    collections.importBindings.filter((binding) => binding.sourceExternalId.namespace === NATIVE_NAMESPACE).length ===
      nativeRecords.length,
    "Native import-binding count does not match the native platform record count",
  );
  // The extension point's closed-world proof: platform IDs are exactly the frozen shipping
  // fixture UNION whatever `mimir-native-platforms.json` currently declares. With zero native
  // records this reduces to the historical shipping-only equality, so emptying the native source
  // and regenerating reproduces the pre-extension-point registry byte-for-byte.
  const nativePlatformIds = nativeRecords.map((record) => record.platformId);
  assert(
    canonicalJson(collections.platforms.map((platform) => platform.id)) ===
      canonicalJson(sortedUnique([...catalogRelations.fixture.canonicalIds, ...nativePlatformIds])),
    "Platform IDs do not exactly match the shipping fixture canonicalIds union the Mimir-native platform IDs",
  );
  assert(
    collections.contentProfiles.filter((profile) => profile.externalId.namespace === NATIVE_NAMESPACE).length ===
      nativeRecords.length,
    "Native content-profile count does not match the native platform record count",
  );
  const sourceProvenance = {
    generatorSemanticsVersion: sourceLock.generatorSemanticsVersion,
    interpretationInputs: importProvenance.interpretationInputs.map(({ id, path, sha256 }) => ({ id, path, sha256 })),
    lockedInputSetSha256: importProvenance.lockedInputSetSha256,
    sourceSetSha256: importProvenance.sourceSetSha256,
    sources: importProvenance.sources.map(({ id, sha256 }) => ({ id, sha256 })),
  };
  const semanticOutput = {
    behaviorPosture: "BEHAVIOR_DARK",
    collections,
    compatibilityFloor: COMPATIBILITY_FLOOR,
    counts: collectionCounts(collections),
    generatedBy: GENERATOR_ID,
    idMapVersion: ID_MAP_VERSION,
    schemaVersion: REGISTRY_SCHEMA_VERSION,
    sourceProvenance,
  };
  const semanticOutputSha256 = digest(semanticOutput);
  const registryRevision = buildRegistryRevision(importProvenance, semanticOutputSha256);
  const payload = {
    ...semanticOutput,
    registryRevision,
    sourceProvenance: {
      ...sourceProvenance,
      semanticOutputSha256,
    },
  };
  const collectionDigests = Object.fromEntries(
    Object.entries(collections).map(([name, value]) => [name, digest(value)]),
  );

  return {
    ...payload,
    digests: {
      algorithm: "SHA-256",
      canonicalization: DIGEST_CANONICALIZATION,
      collections: collectionDigests,
      registryPayloadSha256: digest(payload),
    },
  };
}

function makeSet(namespace, ids, canonicalPlatformIds = undefined) {
  const sortedIds = sortedUnique(ids);
  const row = {
    count: sortedIds.length,
    ids: sortedIds,
    idsSha256: digest(sortedIds),
    namespace,
  };
  if (canonicalPlatformIds !== undefined) {
    row.canonicalPlatformIds = sortedUnique(canonicalPlatformIds);
    row.canonicalPlatformIdsSha256 = digest(row.canonicalPlatformIds);
  }
  return row;
}

function buildSupportSetManifest(parsed, registry, nativeRecords) {
  const entries = parsed.catalog.entries;
  const retroArchIds = new Set(Object.keys(parsed.retroArch.platforms));
  const discoveryIds = new Set(entries.filter((entry) => entry.fileExtensions.length > 0).map((entry) => entry.id));
  const standaloneIds = new Set(entries.filter((entry) => entry.standaloneTargets.length > 0).map((entry) => entry.id));
  const runtimeIds = parsed.shippingRuntimeSystems.map((row) => row.externalId.value);

  const r49 = sortedUnique(retroArchIds);
  const r39 = r49.filter((id) => discoveryIds.has(id));
  const r10 = r49.filter((id) => !discoveryIds.has(id));
  const a287 = entries.filter((entry) => !discoveryIds.has(entry.id) && !retroArchIds.has(entry.id) && !standaloneIds.has(entry.id)).map((entry) => entry.id);
  const d59 = entries.filter((entry) => discoveryIds.has(entry.id) && !retroArchIds.has(entry.id) && !standaloneIds.has(entry.id)).map((entry) => entry.id);
  const dr47 = entries.filter((entry) => discoveryIds.has(entry.id) && (retroArchIds.has(entry.id) || standaloneIds.has(entry.id))).map((entry) => entry.id);

  assert(r39.length === EXPECTED.retroArchDiscoverable, "R39_DISCOVERABLE denominator drifted");
  assert(r10.length === EXPECTED.retroArchDiscoveryGap, "R10_DISCOVERY_GAP denominator drifted");
  assert(a287.length === EXPECTED.catalogOnly, "A287_CATALOG_ONLY denominator drifted");
  assert(d59.length === EXPECTED.discoveryOnly, "D59_DISCOVERY_ONLY denominator drifted");
  assert(dr47.length === EXPECTED.discoveryRuntime, "DR47_DISCOVERY_RUNTIME denominator drifted");

  const sets = {
    A287_CATALOG_ONLY: makeSet("shipping-catalog", a287),
    B21: makeSet(
      "shipping-runtime",
      runtimeIds,
      runtimeIds.map((id) => canonicalPlatformId("shipping-runtime", id)),
    ),
    D59_DISCOVERY_ONLY: makeSet("shipping-catalog", d59),
    DR47_DISCOVERY_RUNTIME: makeSet("shipping-catalog", dr47),
    R10_DISCOVERY_GAP: makeSet("shipping-retroarch-registration", r10),
    R39_DISCOVERABLE: makeSet("shipping-retroarch-registration", r39),
    R49: makeSet("shipping-retroarch-registration", r49),
  };

  const partitionNames = ["A287_CATALOG_ONLY", "D59_DISCOVERY_ONLY", "R10_DISCOVERY_GAP", "DR47_DISCOVERY_RUNTIME"];
  const partitionIds = partitionNames.flatMap((name) => sets[name].ids);
  assert(partitionIds.length === EXPECTED.catalogEntries, "Catalog partition does not contain 403 memberships");
  assert(new Set(partitionIds).size === EXPECTED.catalogEntries, "Catalog partition sets overlap");
  assert(sortedUnique(partitionIds).join("\n") === sortedUnique(entries.map((entry) => entry.id)).join("\n"), "Catalog partition union differs from catalog IDs");

  const standaloneRows = registry.collections.standaloneTargetObservations.map((row) => ({
    catalogEntryId: row.catalogEntryId,
    id: row.id,
    label: row.label,
    packageName: row.packageName,
    sourceOrdinal: row.sourceOrdinal,
    sourceStatus: row.sourceStatus,
  }));
  const packageNames = sortedUnique(standaloneRows.map((row) => row.packageName));
  const rowsByPackage = new Map();
  for (const row of standaloneRows) {
    const ids = rowsByPackage.get(row.packageName) ?? [];
    ids.push(row.id);
    rowsByPackage.set(row.packageName, ids);
  }
  const duplicatePackages = [...rowsByPackage.entries()]
    .filter(([, ids]) => ids.length > 1)
    .map(([packageName, targetRowIds]) => ({
      occurrenceCount: targetRowIds.length,
      packageName,
      targetRowIds: targetRowIds.sort(),
    }))
    .sort((left, right) => left.packageName.localeCompare(right.packageName));

  const relationCounts = Object.fromEntries(
    CATALOG_RELATIONS.map((relation) => [
      relation,
      registry.collections.catalogEntries.filter((entry) => entry.relation === relation).length,
    ]),
  );
  const canonicalizationFixtures = ADVERSARIAL_RELATION_FIXTURES.map(({ catalogId, platformId, relation }) => {
    const entry = registry.collections.catalogEntries.find(
      (candidate) => candidate.externalId.value === catalogId,
    );
    assert(entry, `Canonicalization fixture ${catalogId} has no catalog entry`);
    assert(
      entry.platformId === platformId && entry.relation === relation,
      `Canonicalization fixture ${catalogId} did not resolve to ${platformId} (${relation})`,
    );
    return {
      catalogEntryId: entry.id,
      expectedPlatformId: platformId,
      expectedRelation: relation,
      observedPlatformId: entry.platformId,
      observedRelation: entry.relation,
      status: "PASS",
    };
  });

  const payload = {
    additiveExpansion: {
      catalogEntryIds: [],
      standaloneTargetRows: [],
    },
    catalogNormalization: {
      canonicalPlatformCount: registry.counts.platforms,
      catalogEntryCount: registry.counts.catalogEntries,
      countRationale: `${registry.counts.catalogEntries} catalog identities - ${registry.counts.catalogRelationFixtures} explicit variants + ${nativeRecords.length} Mimir-native platforms = ${registry.counts.platforms} canonical/provisional platforms`,
      fixtures: canonicalizationFixtures,
      relationCounts,
      relationFixtureSha256: registry.sourceProvenance.interpretationInputs.find(
        (input) => input.id === "shipping-catalog-relations",
      ).sha256,
    },
    catalogPartition: {
      disjointSetNames: partitionNames,
      unionCount: new Set(partitionIds).size,
      unionIdsSha256: digest(sortedUnique(partitionIds)),
    },
    compatibilityFloor: COMPATIBILITY_FLOOR,
    generatedBy: GENERATOR_ID,
    idMapVersion: ID_MAP_VERSION,
    lockedInputSetSha256: registry.sourceProvenance.lockedInputSetSha256,
    registryRevision: registry.registryRevision,
    registrySemanticOutputSha256: registry.sourceProvenance.semanticOutputSha256,
    schemaVersion: SUPPORT_SET_SCHEMA_VERSION,
    sets,
    sourceSetSha256: registry.sourceProvenance.sourceSetSha256,
    standalone: {
      catalogEntryCount: standaloneIds.size,
      catalogEntryIds: sortedUnique(standaloneIds),
      duplicatePackages,
      packageCount: packageNames.length,
      packageNames,
      targetRowCount: standaloneRows.length,
      targetRows: standaloneRows,
    },
    wiiDefaultDrift: registry.collections.parityFixtures[0],
  };

  return {
    ...payload,
    digests: {
      algorithm: "SHA-256",
      canonicalization: DIGEST_CANONICALIZATION,
      manifestPayloadSha256: digest(payload),
      setIds: Object.fromEntries(Object.entries(sets).map(([name, row]) => [name, row.idsSha256])),
      standalonePackageNamesSha256: digest(packageNames),
      standaloneTargetRowsSha256: digest(standaloneRows),
    },
  };
}

function buildCertificationLedger(registry) {
  return {
    behaviorPosture: "BEHAVIOR_DARK",
    certifications: [],
    compatibilityFloor: COMPATIBILITY_FLOOR,
    generatedBy: GENERATOR_ID,
    idMapVersion: ID_MAP_VERSION,
    lockedInputSetSha256: registry.sourceProvenance.lockedInputSetSha256,
    registryPayloadSha256: registry.digests.registryPayloadSha256,
    registryRevision: registry.registryRevision,
    registrySemanticOutputSha256: registry.sourceProvenance.semanticOutputSha256,
    registrySchemaVersion: REGISTRY_SCHEMA_VERSION,
    revocations: [],
    schemaVersion: CERTIFICATION_SCHEMA_VERSION,
    sourceSetSha256: registry.sourceProvenance.sourceSetSha256,
  };
}

export function generateModel({ sourceRoot = DEFAULT_SOURCE_ROOT } = {}) {
  const resolvedSourceRoot = resolve(sourceRoot);
  const vendored = loadVendoredSources(resolvedSourceRoot);
  const parsed = parseVendoredSources(vendored);
  const reservedPlatformIds = new Set([
    ...parsed.catalog.entries.map((entry) => entry.id),
    ...Object.keys(parsed.retroArch.platforms),
    ...parsed.shippingRuntimeSystems.map((row) => row.platformId),
    ...Object.values(REWRITE_ALIASES),
  ]);
  const nativePlatformSource = loadNativePlatformSource(resolvedSourceRoot, reservedPlatformIds);
  const importProvenance = buildImportProvenance();
  const catalogRelations = loadCatalogRelations(parsed.catalog, importProvenance);
  const sourceLock = buildSourceLock(vendored, parsed.catalog, catalogRelations, importProvenance, nativePlatformSource);
  const registry = buildRegistry(parsed, sourceLock, catalogRelations, nativePlatformSource.records);
  const certification = buildCertificationLedger(registry);
  const supportSetManifest = buildSupportSetManifest(parsed, registry, nativePlatformSource.records);
  return { certification, registry, sourceLock, supportSetManifest };
}

export function generateArtifacts(options = {}) {
  const model = generateModel(options);
  return Object.fromEntries(
    Object.entries(GENERATED_PATHS).map(([path, key]) => [path, prettyJson(model[key])]),
  );
}

function artifactDiffs(artifacts) {
  const diffs = [];
  for (const [path, expected] of Object.entries(artifacts)) {
    const absolutePath = resolve(REPO_ROOT, path);
    if (!existsSync(absolutePath)) {
      diffs.push(`${path}: missing`);
      continue;
    }
    const actual = readFileSync(absolutePath, "utf8");
    if (actual !== expected) diffs.push(`${path}: generated bytes differ`);
  }
  return diffs;
}

function writeArtifacts(artifacts, { refreshSourceLock }) {
  const lockPath = "platforms/mimir-platform-sources.lock.json";
  const absoluteLockPath = resolve(REPO_ROOT, lockPath);
  if (!refreshSourceLock) {
    assert(existsSync(absoluteLockPath), "Source lock is missing; initialize it with --refresh-source-lock");
    const actualLock = readFileSync(absoluteLockPath, "utf8");
    assert(
      actualLock === artifacts[lockPath],
      "Shipping source differs from the checked source lock; review the source change and use --refresh-source-lock intentionally",
    );
  }

  for (const [path, contents] of Object.entries(artifacts)) {
    if (path === lockPath && !refreshSourceLock) continue;
    writeFileSync(resolve(REPO_ROOT, path), contents, "utf8");
  }
}

function parseArgs(argv) {
  const options = {
    checkSource: false,
    refreshSourceLock: false,
    sourceRoot: process.env.MIMIR_SHIPPING_ROOT || DEFAULT_SOURCE_ROOT,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--check-source") options.checkSource = true;
    else if (argument === "--refresh-source-lock") options.refreshSourceLock = true;
    else if (argument === "--source-root") {
      index += 1;
      assert(argv[index], "--source-root requires a path");
      options.sourceRoot = argv[index];
    } else if (argument === "--help") {
      process.stdout.write(
        "Usage: generate-platform-registry.mjs [--check-source] [--refresh-source-lock] [--source-root PATH]\n",
      );
      process.exit(0);
    } else fail(`Unknown argument: ${argument}`);
  }
  assert(
    !(options.checkSource && options.refreshSourceLock),
    "--check-source and --refresh-source-lock are mutually exclusive",
  );
  return options;
}

function runCli() {
  const options = parseArgs(process.argv.slice(2));
  const artifacts = generateArtifacts({ sourceRoot: options.sourceRoot });
  if (options.checkSource) {
    const diffs = artifactDiffs(artifacts);
    if (diffs.length > 0) fail(`Generated artifact check failed:\n${diffs.join("\n")}`);
    process.stdout.write(`PASS platform registry generation: ${Object.keys(artifacts).length} byte-identical artifacts\n`);
    return;
  }

  writeArtifacts(artifacts, options);
  const registry = JSON.parse(artifacts["platforms/mimir-platform-registry.v2.json"]);
  process.stdout.write(
    `WROTE platform registry ${registry.registryRevision}: ` +
      `${registry.counts.catalogEntries} catalog, ${registry.counts.importBindings} bindings, ` +
      `${registry.counts.retroArchRegistrations} RetroArch IDs\n`,
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
