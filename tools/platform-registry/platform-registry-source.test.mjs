import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import test from "node:test";

import {
  DEFAULT_SOURCE_ROOT,
  GENERATED_PATHS,
  REPO_ROOT,
  generateArtifacts,
} from "./generate-platform-registry.mjs";

const sourceRoot = process.env.MIMIR_SHIPPING_ROOT ?? DEFAULT_SOURCE_ROOT;

test("shipping-source generation is deterministic and matches committed bytes", () => {
  const first = generateArtifacts({ sourceRoot });
  const second = generateArtifacts({ sourceRoot });

  assert.deepEqual(second, first);
  for (const path of Object.keys(GENERATED_PATHS)) {
    assert.equal(readFileSync(resolve(REPO_ROOT, path), "utf8"), first[path], `${path} differs`);
  }
});
