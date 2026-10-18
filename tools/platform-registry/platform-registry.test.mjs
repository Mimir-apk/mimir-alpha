import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { cpSync, mkdtempSync, mkdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";
import { fileURLToPath } from "node:url";

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(SCRIPT_DIR, "../..");

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

function digest(value) {
  return createHash("sha256").update(canonicalJson(value)).digest("hex");
}

function without(value, key) {
  const copy = { ...value };
  delete copy[key];
  return copy;
}

function registrySemanticOutput(registry) {
  const sourceProvenance = without(registry.sourceProvenance, "semanticOutputSha256");
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

function loadJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function writeJson(path, value) {
  writeFileSync(path, prettyJson(value), "utf8");
}

function withIsolatedArtifacts(action) {
  const temporaryParent = mkdtempSync(join(tmpdir(), "mimir-pk00-offline-"));
  const isolatedRoot = join(temporaryParent, "repo");
  mkdirSync(join(isolatedRoot, "tools"), { recursive: true });
  mkdirSync(join(isolatedRoot, "core-ui/src/main/res"), { recursive: true });
  cpSync(resolve(REPO_ROOT, "platforms"), join(isolatedRoot, "platforms"), { recursive: true });
  cpSync(SCRIPT_DIR, join(isolatedRoot, "tools/platform-registry"), { recursive: true });
  cpSync(
    resolve(REPO_ROOT, "core-ui/src/main/assets/platform-console-art"),
    join(isolatedRoot, "core-ui/src/main/assets/platform-console-art"),
    { recursive: true },
  );
  cpSync(
    resolve(REPO_ROOT, "core-ui/src/main/res/drawable"),
    join(isolatedRoot, "core-ui/src/main/res/drawable"),
    { recursive: true },
  );
  try {
    action(realpathSync(isolatedRoot));
  } finally {
    rmSync(temporaryParent, { force: true, recursive: true });
  }
}

function runOfflineVerifier(isolatedRoot) {
  return spawnSync(
    process.execPath,
    [join(isolatedRoot, "tools/platform-registry/verify-platform-registry.mjs"), "--offline"],
    { cwd: isolatedRoot, encoding: "utf8" },
  );
}

test("committed artifacts verify without a shipping-source sibling", () => {
  withIsolatedArtifacts((isolatedRoot) => {
    const result = runOfflineVerifier(isolatedRoot);
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /PASS offline platform registry: 26 gates, 226 canonical\/provisional platforms/);
  });
});

test("fixture byte drift fails until artifacts and lock evidence are regenerated", () => {
  withIsolatedArtifacts((isolatedRoot) => {
    const fixturePath = join(
      isolatedRoot,
      "tools/platform-registry/fixtures/shipping-catalog-relations.v1.json",
    );
    writeFileSync(fixturePath, `${readFileSync(fixturePath, "utf8")} `, "utf8");

    const result = runOfflineVerifier(isolatedRoot);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /source-lock-interpretation-inputs: relation fixture byte count changed/);
  });
});

test("generator byte drift fails until artifacts and lock evidence are regenerated", () => {
  withIsolatedArtifacts((isolatedRoot) => {
    const generatorPath = join(isolatedRoot, "tools/platform-registry/generate-platform-registry.mjs");
    writeFileSync(generatorPath, `${readFileSync(generatorPath, "utf8")} `, "utf8");

    const result = runOfflineVerifier(isolatedRoot);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /source-lock-interpretation-inputs: generator byte count changed/);
  });
});

test("packaged console vectors reject byte drift even when their IDs remain valid", () => {
  withIsolatedArtifacts((isolatedRoot) => {
    const vectorPath = join(
      isolatedRoot,
      "core-ui/src/main/res/drawable/mimir_platform_icon_nes.xml",
    );
    writeFileSync(vectorPath, `${readFileSync(vectorPath, "utf8")} `, "utf8");

    const result = runOfflineVerifier(isolatedRoot);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /packaged-console-art: rendered vector SHA-256 differs for mimir_platform_icon_nes/);
  });
});

test("coordinated semantic rehash cannot retain the old registry revision", () => {
  withIsolatedArtifacts((isolatedRoot) => {
    const registryPath = join(isolatedRoot, "platforms/mimir-platform-registry.v2.json");
    const certificationPath = join(isolatedRoot, "platforms/mimir-platform-certification.v1.json");
    const supportSetPath = join(isolatedRoot, "platforms/support-set-manifest.json");
    const registry = loadJson(registryPath);
    const oldRevision = registry.registryRevision;

    registry.collections.platforms[0].display.displayName += " coordinated semantic mutation";
    registry.digests.collections.platforms = digest(registry.collections.platforms);
    registry.sourceProvenance.semanticOutputSha256 = digest(registrySemanticOutput(registry));
    registry.digests.registryPayloadSha256 = digest(without(registry, "digests"));
    assert.equal(registry.registryRevision, oldRevision);
    writeJson(registryPath, registry);

    const certification = loadJson(certificationPath);
    certification.registryPayloadSha256 = registry.digests.registryPayloadSha256;
    certification.registrySemanticOutputSha256 = registry.sourceProvenance.semanticOutputSha256;
    writeJson(certificationPath, certification);

    const supportSet = loadJson(supportSetPath);
    supportSet.registrySemanticOutputSha256 = registry.sourceProvenance.semanticOutputSha256;
    supportSet.digests.manifestPayloadSha256 = digest(without(supportSet, "digests"));
    writeJson(supportSetPath, supportSet);

    const result = runOfflineVerifier(isolatedRoot);
    assert.notEqual(result.status, 0);
    assert.match(
      result.stderr,
      /registry-revision-semantics: registry revision does not bind generator semantics, bytes, and semantic output/,
    );
    assert.doesNotMatch(result.stderr, /registry-semantic-output:/);
    assert.doesNotMatch(result.stderr, /registry-collection-digests:/);
  });
});

function assertContentProfileMutationFails(mutate, expectedMessage) {
  withIsolatedArtifacts((isolatedRoot) => {
    const registryPath = join(isolatedRoot, "platforms/mimir-platform-registry.v2.json");
    const registry = loadJson(registryPath);
    mutate(registry.collections.contentProfiles);
    writeJson(registryPath, registry);

    const result = runOfflineVerifier(isolatedRoot);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, expectedMessage);
  });
}

test("BOUND content profile rejects null contract", () => {
  assertContentProfileMutationFails((profiles) => {
    profiles.find((profile) => profile.discoveryStatus === "BOUND").contentContract = null;
  }, /content-profile-contracts: .* BOUND content contract must be non-null/);
});

test("BOUND content profile rejects empty contract", () => {
  assertContentProfileMutationFails((profiles) => {
    profiles.find((profile) => profile.discoveryStatus === "BOUND").contentContract = {};
  }, /content-profile-contracts: .* BOUND content contract is structurally invalid/);
});

test("BOUND content profile rejects invalid contract structure", () => {
  assertContentProfileMutationFails((profiles) => {
    const profile = profiles.find((candidate) => candidate.discoveryStatus === "BOUND");
    profile.contentContract.baseGameExtensions = "zip";
  }, /content-profile-contracts: .* BOUND content contract is structurally invalid/);
});

test("GAP content profile rejects non-null contract", () => {
  assertContentProfileMutationFails((profiles) => {
    const bound = profiles.find((profile) => profile.discoveryStatus === "BOUND");
    const gap = profiles.find((profile) => profile.discoveryStatus === "GAP");
    gap.contentContract = JSON.parse(JSON.stringify(bound.contentContract));
  }, /content-profile-contracts: .* GAP content contract must be null/);
});

function assertRegistryMutationFails(mutate, expectedMessage) {
  withIsolatedArtifacts((isolatedRoot) => {
    const registryPath = join(isolatedRoot, "platforms/mimir-platform-registry.v2.json");
    const registry = loadJson(registryPath);
    mutate(registry);
    writeJson(registryPath, registry);

    const result = runOfflineVerifier(isolatedRoot);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, expectedMessage);
  });
}

// Proves the `native-platform-extension-point` gate can actually see the defect it exists to
// catch: a Mimir-native platform with a BOUND content profile but no import binding schema-
// validates and even changes the registry revision, but `PlatformRegistry.extensionCandidates()`
// (core-platform) only reads extension rules through `importBindings` -- so its extensions would
// silently never be recognized. This is the same class of false pass the ticket that added the
// extension point warned about, one layer deeper.
test("Mimir-native platform without an import binding fails the extension-point gate", () => {
  assertRegistryMutationFails((registry) => {
    registry.collections.importBindings = registry.collections.importBindings.filter(
      (binding) => binding.sourceExternalId.namespace !== "mimir-native",
    );
  }, /native-platform-extension-point: Mimir-native platform \S+ has no import binding/);
});
