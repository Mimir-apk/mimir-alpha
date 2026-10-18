#!/usr/bin/env node

import { createHash } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(SCRIPT_DIR, "../..");
const REGISTRY_PATH = "platforms/mimir-platform-registry.v2.json";
const SOURCE_LOCK_PATH = "platforms/mimir-platform-sources.lock.json";
const ART_ROOT = "core-ui/src/main/assets/platform-console-art";
const MANIFEST_PATH = `${ART_ROOT}/mimir-platform-console-art.v1.json`;
const VECTOR_ROOT = "core-ui/src/main/res/drawable";
const FALLBACK_ID = "generic-console-v1";

// Generated-output bindings for the existing BAXY conversions. These are intentionally only the
// source-derived vectors; generic templates do not inflate the real-art coverage denominator.
const BUNDLED_VECTOR_TARGETS = Object.freeze({
  "mimir_platform_icon_3do": "the_3do_company_3do",
  "mimir_platform_icon_amiga": "commodore_amiga",
  "mimir_platform_icon_amstrad_cpc": "amstrad_cpc",
  "mimir_platform_icon_amstrad_gx4000": "amstrad_gx4000",
  "mimir_platform_icon_apple_ii": "apple_ii",
  "mimir_platform_icon_arcade": "arcade",
  "mimir_platform_icon_arcadia_2001": "emerson_arcadia_2001",
  "mimir_platform_icon_archimedes": "acorn_archimedes",
  "mimir_platform_icon_arduboy": "arduboy_inc_arduboy",
  "mimir_platform_icon_astrocade": "bally_astrocade",
  "mimir_platform_icon_atari_2600": "atari_2600",
  "mimir_platform_icon_atari_5200": "atari_5200",
  "mimir_platform_icon_atari_7800": "atari_7800",
  "mimir_platform_icon_atari_8_bit": "atari_8_bit_family",
  "mimir_platform_icon_atari_jaguar": "atari_jaguar",
  "mimir_platform_icon_atari_lynx": "atari_lynx",
  "mimir_platform_icon_atari_st": "atari_st",
  "mimir_platform_icon_bbc_micro": "acorn_bbc_micro",
  "mimir_platform_icon_channel_f": "fairchild_channel_f",
  "mimir_platform_icon_coleco_adam": "coleco_colecovision_adam",
  "mimir_platform_icon_colecovision": "coleco_colecovision",
  "mimir_platform_icon_commodore_64": "commodore_64",
  "mimir_platform_icon_commodore_plus_4": "commodore_plus_4",
  "mimir_platform_icon_creativision": "vtech_creativision",
  "mimir_platform_icon_dreamcast": "dreamcast",
  "mimir_platform_icon_famicom_disk_system": "nintendo_family_computer_disk_system",
  "mimir_platform_icon_fm_7": "fujitsu_fm_7",
  "mimir_platform_icon_fm_towns": "fujitsu_fm_towns",
  "mimir_platform_icon_gamate": "bit_corporation_gamate",
  "mimir_platform_icon_game_boy": "gb",
  "mimir_platform_icon_game_boy_advance": "gba",
  "mimir_platform_icon_game_boy_color": "gbc",
  "mimir_platform_icon_game_com": "tiger_game_com",
  "mimir_platform_icon_game_gear": "sega_game_gear",
  "mimir_platform_icon_game_master": "hartung_game_master",
  "mimir_platform_icon_gamecube": "gamecube",
  "mimir_platform_icon_genesis": "genesis",
  "mimir_platform_icon_intellivision": "mattel_intellivision",
  "mimir_platform_icon_lowres_nx": "lowres_nx",
  "mimir_platform_icon_macintosh": "apple_macintosh",
  "mimir_platform_icon_master_system": "sega_master_system_mark_iii",
  "mimir_platform_icon_mega_duck": "welback_mega_duck",
  "mimir_platform_icon_msx": "microsoft_msx",
  "mimir_platform_icon_msx2": "microsoft_msx2",
  "mimir_platform_icon_n64": "n64",
  "mimir_platform_icon_n_gage": "nokia_n_gage",
  "mimir_platform_icon_neo_geo": "snk_neo_geo",
  "mimir_platform_icon_neo_geo_cd": "snk_neo_geo_cd",
  "mimir_platform_icon_neo_geo_pocket": "snk_neo_geo_pocket",
  "mimir_platform_icon_neo_geo_pocket_color": "snk_neo_geo_pocket_color",
  "mimir_platform_icon_nes": "nes",
  "mimir_platform_icon_nintendo_3ds": "3ds",
  "mimir_platform_icon_nintendo_ds": "ds",
  "mimir_platform_icon_odyssey_2": "magnavox_odyssey2",
  "mimir_platform_icon_pc": "dos",
  "mimir_platform_icon_pc_98": "nec_pc_98",
  "mimir_platform_icon_pc_fx": "nec_pc_fx",
  "mimir_platform_icon_philips_cdi": "philips_cd_i",
  "mimir_platform_icon_playstation": "ps1",
  "mimir_platform_icon_playstation_2": "ps2",
  "mimir_platform_icon_playstation_3": "ps3",
  "mimir_platform_icon_playstation_4": "sony_playstation_4",
  "mimir_platform_icon_playstation_vita": "sony_playstation_vita",
  "mimir_platform_icon_pokemon_mini": "nintendo_pokemon_mini",
  "mimir_platform_icon_psp": "psp",
  "mimir_platform_icon_pv_1000": "casio_pv_1000",
  "mimir_platform_icon_saturn": "saturn",
  "mimir_platform_icon_sega_32x": "sega_32x",
  "mimir_platform_icon_sega_cd": "sega_mega_cd_sega_cd",
  "mimir_platform_icon_sg_1000": "sega_sg_1000",
  "mimir_platform_icon_sharp_x1": "sharp_x1",
  "mimir_platform_icon_snes": "snes",
  "mimir_platform_icon_spectravideo": "spectravideo_sv",
  "mimir_platform_icon_super_acan": "funtech_super_acan",
  "mimir_platform_icon_super_cassette_vision": "epoch_super_cassette_vision",
  "mimir_platform_icon_supergrafx": "nec_pc_engine_supergrafx",
  "mimir_platform_icon_supervision": "watara_supervision",
  "mimir_platform_icon_switch": "switch",
  "mimir_platform_icon_thomson": "thomson_moto",
  "mimir_platform_icon_tic_80": "tic_80",
  "mimir_platform_icon_turbografx_16": "nec_pc_engine_turbografx_16",
  "mimir_platform_icon_turbografx_cd": "nec_pc_engine_cd_turbografx_cd",
  "mimir_platform_icon_uzebox": "uzebox",
  "mimir_platform_icon_v_smile": "vtech_v_smile",
  "mimir_platform_icon_vectrex": "gce_vectrex",
  "mimir_platform_icon_vic_20": "commodore_vic_20",
  "mimir_platform_icon_vircon32": "vircon32",
  "mimir_platform_icon_virtual_boy": "nintendo_virtual_boy",
  "mimir_platform_icon_wasm_4": "wasm_4",
  "mimir_platform_icon_wii": "nintendo_wii",
  "mimir_platform_icon_wii_u": "nintendo_wii_u",
  "mimir_platform_icon_wonderswan": "bandai_wonderswan",
  "mimir_platform_icon_wonderswan_color": "bandai_wonderswan_color",
  "mimir_platform_icon_x68000": "sharp_x68000",
  "mimir_platform_icon_xbox": "xbox",
  "mimir_platform_icon_xbox_360": "xbox360",
  "mimir_platform_icon_xbox_one": "microsoft_xbox_one",
  "mimir_platform_icon_z_machine": "infocom_z_machine",
  "mimir_platform_icon_zx81": "sinclair_zx_81",
  "mimir_platform_icon_zx_spectrum": "sinclair_zx_spectrum",
});

// Icons Mimir draws itself, for a platform no BAXY console fits (each an policy). The source is the script
// that authors the drawable; its sha256 is recorded, and re-running it must reproduce the drawable byte for byte.
const MIMIR_AUTHORED_VECTOR_TARGETS = Object.freeze({
  //
  "mimir_platform_icon_windows_98": {
    canonicalId: "pc_games",
    sourcePath: "tools/platform-registry/author-windows-98-flag.py",
  },
});

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function readJson(path) {
  return JSON.parse(readFileSync(resolve(REPO_ROOT, path), "utf8"));
}

function normalize(raw) {
  return raw.trim().toLowerCase().replace(/[ _-]/g, "");
}

function packagedSourcePath(relativePath) {
  return relativePath.replace("platform_icons/baxy_automatic/", "baxy-automatic/");
}

function pretty(value) {
  return `${JSON.stringify(value, null, 2)}\n`;
}

export function generatePlatformConsoleArtManifest() {
  const registry = readJson(REGISTRY_PATH);
  const sourceLock = readJson(SOURCE_LOCK_PATH);
  const baxySource = sourceLock.upstreamAssets.find((asset) => asset.name === "BAXY Automatic");
  if (!baxySource || baxySource.license !== "MIT") throw new Error("Missing locked MIT BAXY source provenance");

  const canonicalIds = registry.collections.platforms.map((platform) => platform.id).sort();
  const canonicalIdSet = new Set(canonicalIds);
  const visualByCatalogId = new Map(
    registry.collections.visualAssets.map((asset) => [asset.catalogEntryId, asset]),
  );
  const sourceFiles = registry.collections.visualAssets
    .map((asset) => {
      const sourceRelativePath = packagedSourcePath(asset.relativePath);
      const path = `${ART_ROOT}/${sourceRelativePath}`;
      const bytes = readFileSync(resolve(REPO_ROOT, path));
      return { bytes: bytes.length, path: sourceRelativePath, sha256: sha256(bytes) };
    })
    .sort((left, right) => left.path.localeCompare(right.path));
  const licenseFiles = ["LICENSE_BAXY_AUTOMATIC.txt", "NOTICE_BAXY_AUTOMATIC.txt"].map((path) => {
    const bytes = readFileSync(resolve(REPO_ROOT, ART_ROOT, path));
    return { bytes: bytes.length, path, sha256: sha256(bytes) };
  });

  const renderedVectors = Object.entries(BUNDLED_VECTOR_TARGETS)
    .map(([resourceName, canonicalId]) => {
      if (!canonicalIdSet.has(canonicalId)) throw new Error(`${resourceName} targets unknown ${canonicalId}`);
      const source = visualByCatalogId.get(`shipping-catalog:${canonicalId}`);
      if (!source) throw new Error(`${resourceName} lacks a canonical BAXY source for ${canonicalId}`);
      const path = `${VECTOR_ROOT}/${resourceName}.xml`;
      const bytes = readFileSync(resolve(REPO_ROOT, path));
      const sourcePath = packagedSourcePath(source.relativePath);
      const sourceEntry = sourceFiles.find((entry) => entry.path === sourcePath);
      if (!sourceEntry) throw new Error(`${resourceName} lacks packaged source ${sourcePath}`);
      return {
        canonicalId,
        path: path.replace(`${REPO_ROOT}/`, ""),
        resourceName,
        sha256: sha256(bytes),
        sourcePath,
        sourceSha256: sourceEntry.sha256,
      };
    })
    .concat(
      Object.entries(MIMIR_AUTHORED_VECTOR_TARGETS).map(([resourceName, { canonicalId, sourcePath }]) => {
        if (!canonicalIdSet.has(canonicalId)) throw new Error(`${resourceName} targets unknown ${canonicalId}`);
        if (Object.values(BUNDLED_VECTOR_TARGETS).includes(canonicalId)) {
          throw new Error(`${resourceName} would replace ${canonicalId}'s BAXY icon`);
        }
        const path = `${VECTOR_ROOT}/${resourceName}.xml`;
        return {
          canonicalId,
          path,
          resourceName,
          sha256: sha256(readFileSync(resolve(REPO_ROOT, path))),
          sourcePath,
          sourceSha256: sha256(readFileSync(resolve(REPO_ROOT, sourcePath))),
        };
      }),
    )
    .sort((left, right) => left.canonicalId.localeCompare(right.canonicalId));
  const renderedByCanonicalId = new Map(renderedVectors.map((entry) => [entry.canonicalId, entry]));

  const aliases = new Map();
  for (const platform of registry.collections.platforms) {
    for (const value of [platform.id, ...platform.aliases.map((alias) => alias.normalizedValue)]) {
      const normalizedId = normalize(value);
      const prior = aliases.get(normalizedId);
      if (prior && prior !== platform.id) throw new Error(`Art alias collision ${normalizedId}: ${prior}/${platform.id}`);
      aliases.set(normalizedId, platform.id);
    }
  }

  const canonicalResolutions = canonicalIds.map((id) => {
    const vector = renderedByCanonicalId.get(id);
    return vector
      ? { id, kind: "BUNDLED_VECTOR", resourceName: vector.resourceName }
      : { id, kind: "GENERIC_FALLBACK", fallbackId: FALLBACK_ID };
  });
  const realAndroidVectors = renderedVectors.length;
  const genericFallbacks = canonicalResolutions.length - realAndroidVectors;

  return {
    schemaVersion: "mimirPlatformConsoleArt.v1",
    generatedBy: "tools/platform-registry/generate-platform-console-art.mjs",
    source: {
      license: baxySource.license,
      name: baxySource.name,
      noticePath: "NOTICE_BAXY_AUTOMATIC.txt",
      revision: baxySource.revision,
      url: baxySource.url,
    },
    coverage: {
      canonicalPlatforms: canonicalResolutions.length,
      genericFallbacks,
      realAndroidVectors,
      shippingAliasNormalizations: aliases.size,
      sourceSvgFiles: sourceFiles.length,
    },
    fallback: { id: FALLBACK_ID, renderer: "compose-stroke-console" },
    aliases: [...aliases.entries()]
      .map(([normalizedId, canonicalId]) => ({ canonicalId, normalizedId }))
      .sort((left, right) => left.normalizedId.localeCompare(right.normalizedId)),
    canonicalResolutions,
    licenseFiles,
    renderedVectors,
    sourceFiles,
  };
}

function main() {
  const output = pretty(generatePlatformConsoleArtManifest());
  const destination = resolve(REPO_ROOT, MANIFEST_PATH);
  if (process.argv.includes("--check")) {
    if (!existsSync(destination) || readFileSync(destination, "utf8") !== output) {
      throw new Error(`${MANIFEST_PATH} is stale; run generate-platform-console-art.mjs --write`);
    }
    return;
  }
  if (!process.argv.includes("--write")) {
    throw new Error("Usage: generate-platform-console-art.mjs --write|--check");
  }
  writeFileSync(destination, output);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) main();
