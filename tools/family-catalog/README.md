# Tweaker family catalog tools

The checked-in public build uses its bundled catalog only: its catalog verification key is
empty, so network refresh is disabled. Publishing to staging does not update that build.
Remote testing requires configuring the public verification key below, rebuilding, and
serving a catalog signed by the matching private key.

Host-side tools for adding a SoC family to the Tweaker's catalog (`tweaker-families.json`) without
a code change: read what a device reports, draft an entry, sign the catalog.
Validation lives in `FamilyCatalog.kt`; `FamilyCatalogCorpusTest` checks draft entries.

## Add a chip

1. **Connect** the device over adb (USB or Wi-Fi). `adb devices` lists it. Over Wi-Fi the name is
   `ip:port` (e.g. `192.0.2.10:5555`), not the hardware serial; use whatever `adb devices` shows.
2. **Capture** (read-only; about 5 s; needs `adb` on PATH):

   ```
   bash tools/family-catalog/capture.sh <serial> > capture-<model>.txt
   ```

   It reads a fixed list of properties and sysfs nodes and writes nothing to the device. The settings
   and logcat lines are filtered on the device, so no account, wifi or file listing is captured. Open
   the file once before sending it.
3. **Send the file**, or draft it yourself:

   ```
   python tools/family-catalog/draft.py capture-<model>.txt > entry.json
   ```

   `entry.json` (stdout) is the draft entry. The notes on screen (stderr) are for you: the snapped kHz
   each profile would land on (eyeball them), observations, and the **owed** list.
4. **Owed** means what a read-only capture cannot see: whether a GPU cap write holds, whether the
   vendor performance mode reacts as mapped, fan behaviour, the vendor cadence, and any node the adb
   shell was refused (`daemon read owed`; the Tweaker's own process can read those). Each owed line is
   a test to run on the device, not a blocker to drafting.
5. **Test the entry.** The Kotlin validator is the authority, not the draft tool: the entry goes into
   `corpus/` and `FamilyCatalogCorpusTest` runs it. Then add it to `tweaker-families.json` on the
   `staging` branch (below). Remote testing then needs a debug build rebuilt with the matching
   public verification key; only that configured build fetches `staging`. Otherwise test the
   bundled entry through `FamilyCatalogCorpusTest` and a local build.

A draft is always `general-live-opp`. Promoting a family to `mimir-certified-reference` is a separate
decision after measurement.

**The id.** An id is 3 to 40 characters of `a-z 0-9 -` and must start with its SoC vendor: `qualcomm-`,
`mediatek-`, `unisoc-`, `samsung-`, `google-`, `rockchip-`, `allwinner-`, `amlogic-` or `nvidia-`, then
the chip (`qualcomm-8gen2`). The Tweaker drops any other entry: an id is used as the fallback device
profile id (`<id>-pserver`), and the fan authority is granted by the `ayn-`/`retroid-` prefix of that id, so a
catalog id may never sit in the chassis namespace or equal a compiled device profile. `draft.py` writes
`qualcomm-<soc>`; rename it for another vendor. If the draft says an alias is "already claimed", that SoC is covered; the
draft is a comparison, and appending it would make the whole catalog be rejected.

## Sign and publish

The catalog is hosted in `Mimir-apk/tweaker-families` (`main` is beta, `staging` is debug). The
Tweaker accepts a downloaded catalog only with a valid Ed25519 signature.

**One time: make the key.** `bash tools/family-catalog/keygen.sh`. It asks for a path and refuses any
path inside a git work tree or a cloud-synced folder (OneDrive, Dropbox, Google Drive, iCloud). Put the
private key on local disk only and back it up offline. It prints the PUBLIC key, which goes into the
Tweaker's BuildConfig `FAMILY_CATALOG_PUBLIC_KEY_BASE64`. The private key is never printed, and no key,
password or path is ever read from an env var or a flag.

**Each release:**

1. `bash tools/family-catalog/sign.sh tweaker-families.json` and type the key's path at the prompt.
   It writes `tweaker-families.json.sig` (base64 of the raw 64-byte signature, one line, no newline)
   and checks it before it finishes. Do not edit the JSON after signing; any byte change breaks it.
2. `bash tools/family-catalog/verify.sh tweaker-families.json <public-key-base64>` to re-check (exit 0 or 1).
3. Commit the JSON and its `.sig` together to `staging`. In that repo mark both `-text` in
   `.gitattributes` (`*.json -text`, `*.sig -text`) so no line-ending conversion changes the signed bytes.
4. Test on a debug build configured with that public verification key, then merge to `main`.

Needs OpenSSL 3.0 or newer (Git Bash ships 3.x; macOS system LibreSSL is not enough: `brew install openssl@3`).

## Files

| File | What |
|---|---|
| `capture.sh` | read-only device capture |
| `draft.py` | capture to draft entry (Python 3 standard library only) |
| `keygen.sh`, `sign.sh`, `verify.sh`, `_common.sh` | Ed25519 key, signature, check (OpenSSL 3) |
| `corpus/*.capture.txt`, `corpus/*.json` | fixtures and the entry each drafts to |
| `test_draft.py` | tests |

## Corpus

`corpus/thor-qcs8550` is a real run. `odin3-cq8725s` and `rp5-sm8250` are hand-assembled from readings
already in the repo; whatever the repo does not hold is marked `<not-captured>` and comes out owed. The
Thor capture was taken under a standing Balanced clamp, which the draft notes and ignores (it reads the
OPP tables, never the clamped maxima). Each of the three SoCs is already claimed by a bundled family, so
these entries are re-derivations; validate them one at a time, never combined into one catalog.

## Tests

From this directory: `python -m unittest`. It takes 80 to 110 s on Windows, nearly all of it the signing
round trip (keygen, sign, verify, a tampered byte, with throwaway keys in a temp dir that is deleted).
The fast part: `python -m unittest test_draft.CorpusTest test_draft.ArithmeticTest test_draft.DraftBehaviourTest test_draft.CaptureScriptTest`.
The signing tests skip themselves without bash and OpenSSL 3.

## Shape

`draft.py` writes the entry shape `FamilyCatalog.kt` parses (catalog format introduced in bbb116031): `cpu.kind` is
`anchor` with integer-pair rows, `gpu.kind` is `transposed`, and `transports` use the compiled enum's
kebab-case names. The Thor draft matches the bundled `qualcomm-8gen2` entry field for field in `cpu`,
`gpu` and `vendorMode`. If the Kotlin model changes, follow it in `build_entry()` and the constants
above it in `draft.py`, then regenerate `corpus/*.json` with `draft.py`.
