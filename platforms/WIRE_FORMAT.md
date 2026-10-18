# PK-00 generated wire format

`platforms/mimir-platform-registry.v2.json` is the canonical generated-source envelope. It is not the
flat Kotlin `PlatformRegistryDocument` serialization. The envelope preserves source provenance,
frozen counts, per-collection digests, and observations that remain behavior-dark during PK-00.

## Envelope to kernel projection

| Generated envelope | Kernel `PlatformRegistryDocument` | Rule |
| --- | --- | --- |
| `schemaVersion` | `schemaVersion` | Exact value, `mimirPlatformRegistry.v2`. |
| `registryRevision` | `registryRevision` | Exact value. |
| `idMapVersion` | `idMapVersion` | Exact value. |
| `compatibilityFloor` | `compatibilityFloor` | Exact value. |
| `digests.registryPayloadSha256` | adapter validation input | Validated before projection; not renamed to the kernel canonical digest. |
| `sourceProvenance.semanticOutputSha256` | projection integrity input | Binds stable generated semantics before revision and digest fields are attached. |
| `collections.platforms` | `platforms` | Scoped aliases and provisional status are projected; folder recognition is assembled from locked observations. |
| `collections.catalogEntries` | `catalogEntries` | Relation values are `CANONICAL`, `HARDWARE_VARIANT`, `REGIONAL_VARIANT`, `SOFTWARE_FAMILY`, `CONTENT_VARIANT`, and `PRESENTATION_ALIAS`. |
| `collections.contentProfiles` | `contentProfiles` | `BOUND` requires a strict non-null content contract; `GAP` requires `null`. |
| `collections.importBindings` | `importBindings` | Exact source bindings. |
| `collections.retroArchRegistrations` | `retroArchRegistrations` | External registration IDs stay scoped and distinct. |
| `collections.visualAssets` | `visualAssets` | Source asset records become platform-logo definitions. |
| empty runtime collections | matching kernel collections | PK-00 emits no launch routes, package variants, firmware policies, emulator families, or support slices. |

`behaviorPosture`, `counts`, `digests`, `generatedBy`, `sourceProvenance`, relation evidence,
recognition states, shipping runtime observations, standalone target observations, and parity fixtures
are generated-envelope fields. They are validated before or alongside projection and are not runtime
authorization. Unknown is represented by `collections.recognitionStates`; it is never a platform ID.

The certification envelope is also generated wire. PK-00 requires `certifications` and `revocations` to
remain empty and binds it to the registry revision, semantic-output digest, registry payload digest,
source set, and locked input set. The support-set manifest carries the same semantic-output link.

## Semantic revision binding

`semanticOutputSha256` is SHA-256 over canonical JSON containing `behaviorPosture`, `collections`,
`compatibilityFloor`, `counts`, `generatedBy`, `idMapVersion`, `schemaVersion`, and source provenance
before its own semantic digest is attached. It excludes `registryRevision` and `digests`, which avoids a
circular hash. Any display, contract, alias, relation, or other stable generated-content change alters
this digest.

The registry revision suffix binds `generatorSemanticsVersion`, the generator file SHA-256,
`lockedInputSetSha256`, and `semanticOutputSha256`. Rehashing collection, payload, manifest, and
certification digests while retaining an old revision therefore fails offline verification.

## Locked inputs

Nothing in this tree reads `../gaming-hobby-android-handhelds` — the retired WebView/Java app — at
generate, verify, or build time. `platforms/mimir-platform-sources.lock.json` has four input classes:

- `sources`: the three *shipping* files this tree owns under `platforms/sources/`, each checked live
  against the file on disk (path, byte count, SHA-256). `mimir-platform-catalog.json` is a
  byte-for-byte copy of its original shipping JSON. The RetroArch snapshot has only its
  `generatedBy` provenance normalized to `mimirbridge-literals-2026-07-04`; all registry records
  and API identifiers are unchanged. Its original import was 12,547 bytes with SHA-256
  `64d7fe9f06f2dba41de1b003224270f90840a2fd727aa5f5659a7a98ae8df7a2`, retained in
  `importProvenance`. The normalized source is 12,522 bytes with SHA-256
  `153a9a28eaa6f35e8d74209c86a21ee80cc72f696ce0503444fd132c9328b4b8`, pinned independently
  by the verifier and the current source lock. Normalization on 2026-10-18 changed that single
  metadata string directly; it did not reserialize or edit the registry records.
  `mimir-runtime-systems.json` is the 21 runtime-system records, declaratively — it replaces the
  `app.js` JavaScript parse the generator used to run. `mimir-native-platforms.json` is deliberately
  not one of these three: it is not vendored shipping data and carries no `IMPORT_PROVENANCE_SOURCES`
  history, so it is tracked separately, in its own `nativeSources` below, and stays out of
  `importProvenance`/`sourceProvenance` entirely.
- `nativeSources`: exactly one row, `mimir-native-platforms.json` itself — the Mimir-native platform
  extension point (R-2a wired it; the PC Games record is its first entry). Checked live the same way
  as `sources` (path, byte count, SHA-256), but this row only proves the *file* is untampered; the
  records it contains flow into `collections.platforms`/`collections.contentProfiles` the normal way
  and therefore do reach `semanticOutputSha256`. The record count is intentionally open, not frozen:
  `collections.platforms` is asserted equal to the shipping canonical fixture's `canonicalIds` UNION
  whatever `mimir-native-platforms.json` currently declares, so an empty `platforms: []` reproduces the
  historical shipping-only registry byte-for-byte and a populated one changes `registryRevision`. A
  native platform record supplies its own display/folder identity (`displayName`, `manufacturer`,
  `shortName`, `type`, `folder`, `folderAliases`) because, unlike a `mimir-runtime-systems.json` row,
  it is not aliasing extensions onto an *existing* catalog platform — it defines a platform the shipping
  catalog never had. Its alias namespace is `mimir-native` (added to the registry schema's closed
  `alias.namespace` enum), which `GeneratedRegistryAdapter`'s namespace mapping resolves to
  `IdentityNamespace.EXTERNAL_FRONTEND` via its existing catch-all — no kernel enum change needed.
- `importProvenance`: a frozen, one-time record of the original four files read from the retired
  repository before this refactor — including `MimirBridge.java` (`shipping-android-bridge`), which
  contributed no data even then (role `shipping-runtime-reference-only`) and is not vendored locally.
  Every value under `importProvenance` (the repository revision, each source's path/bytes/SHA-256, the
  two interpretation-input rows, `sourceSetSha256`, `lockedInputSetSha256`) is a literal constant in the
  generator, not read from disk. This is what the registry's `sourceProvenance` and `registryRevision`
  are actually built from, and it is why regenerating today produces a byte-identical
  `mimir-platform-registry.v2.json` to the one generated when the retired repo was still the source:
  the historical facts didn't change, only where the generator gets them from.
- `interpretationInputs`: the checked-in relation fixture and the generator itself, each locked live by
  path, byte count, roles, and SHA-256 — this is what still catches "generator or fixture bytes changed
  but the lock wasn't regenerated." It has no aggregate digest of its own; `importProvenance` is the only
  input set with a `lockedInputSetSha256`, and it is what `registryRevision` binds to.

The relation fixture is closed-world: it explicitly lists 225 `canonicalIds` and 178 `variantLinks`.
Those arrays must be disjoint and their exact union must equal all 403 shipping catalog IDs. There is no
`defaultRelation` and no implicit canonical fallback.

`generatorSemanticsVersion` is pinned to `mimirPlatformRegistryGeneratorSemantics.v3` — unchanged by this
refactor, since the transformation semantics didn't change, only the plumbing that acquires the inputs.
The offline verifier recomputes the live per-file hashes and byte counts, the frozen
`importProvenance.sourceSetSha256` / `lockedInputSetSha256` aggregates from their frozen leaf rows, the
semantic-output digest, and the registry revision — and additionally checks that the two `cp`-vendored
files still match their frozen `importProvenance` bytes, and that the vendored runtime-systems file still
carries exactly 21 records.

## Commands

Run the committed-artifact gate from the repository root, with no shipping checkout required:

```sh
tools/platform-registry/check.sh
./gradlew -I tools/platform-registry/platform-support-gate.init.gradle :platformSupportGate
```

Compare committed artifacts with the vendored sources explicitly:

```sh
tools/platform-registry/check-source.sh
```

Regenerate from `platforms/sources/`, or intentionally refresh reviewed source-lock evidence:

```sh
node tools/platform-registry/generate-platform-registry.mjs
node tools/platform-registry/generate-platform-registry.mjs --refresh-source-lock
```

Set `MIMIR_SHIPPING_ROOT` when the vendored sources are not at the default `platforms/sources` path
(the flag predates this refactor; it now points at this tree's own vendored directory, never a sibling
checkout).
