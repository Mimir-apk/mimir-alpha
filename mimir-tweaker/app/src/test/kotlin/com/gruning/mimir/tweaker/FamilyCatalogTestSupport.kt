package com.gruning.mimir.tweaker

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Test helpers for the family catalog. Every test that touches `org.json` must run under
 * Robolectric (`@Config(sdk = [30])`): `unitTests.isReturnDefaultValues = true` makes the plain-JVM
 * `org.json` stubs return nulls silently, so a test there would pass on garbage.
 */
internal object FamilyCatalogTestSupport {
    private const val ASSET = "tweaker-families.json"

    /** The shipped asset, found the way `ManifestControlPermissionContractTest` finds the manifest. */
    fun bundledFile(): File = generateSequence(File(System.getProperty("user.dir").orEmpty()).absoluteFile) { it.parentFile }
        .flatMap { root ->
            sequenceOf(
                File(root, "src/main/assets/$ASSET"),
                File(root, "app/src/main/assets/$ASSET"),
                File(root, "mimir-tweaker/app/src/main/assets/$ASSET"),
            )
        }
        .first { it.isFile }

    fun bundledBytes(): ByteArray = bundledFile().readBytes()

    fun bundledText(): String = String(bundledBytes(), Charsets.US_ASCII)

    /** The shipped catalog; fails the test if the asset does not parse. */
    fun bundledCatalog(): FamilyCatalog =
        when (val parsed = FamilyCatalogParser.parse(bundledBytes())) {
            is FamilyCatalogParse.Accepted -> parsed.catalog
            is FamilyCatalogParse.Rejected -> error("the bundled catalog is rejected: ${parsed.reason} ${parsed.detail}")
        }

    /** The shipped document, edited, re-serialised. */
    fun mutated(edit: (JSONObject) -> Unit): ByteArray {
        val root = JSONObject(bundledText())
        edit(root)
        return root.toString().toByteArray(Charsets.US_ASCII)
    }

    fun withRevision(revision: Int): ByteArray = mutated { it.put("revision", revision) }

    /** A fourth, valid, non-reserved entry; every per-entry drop test breaks exactly one field of this. */
    fun extraEntry(): JSONObject = JSONObject(
        """
        {
          "id": "mediatek-extra-family",
          "label": "Extra test family",
          "treatment": "general-live-opp",
          "match": { "soc": ["MT6893", "XYZ99*"], "platform": ["mt6893"] },
          "cpu": {
            "kind": "khz",
            "profiles": {
              "quiet": { "0": 500000, "4": 600000 },
              "balanced": { "0": 700000, "4": 800000 },
              "performance": { "0": 900000, "4": 1000000 }
            }
          },
          "gpu": {
            "kind": "transposed",
            "anchorLevels": 14,
            "capped": { "quiet": 5 },
            "uncapped": ["balanced", "performance"]
          },
          "vendorMode": { "quiet": 0, "balanced": 1, "performance": 2 },
          "transports": ["cpu-scaling-max", "fan-pwm"]
        }
        """.trimIndent(),
    )

    /** The shipped document with [entry] appended to `families`. */
    fun withEntry(entry: JSONObject): ByteArray = mutated { it.getJSONArray("families").put(entry) }

    fun entryAt(root: JSONObject, id: String): JSONObject {
        val families: JSONArray = root.getJSONArray("families")
        for (i in 0 until families.length()) {
            val entry = families.getJSONObject(i)
            if (entry.getString("id") == id) return entry
        }
        error("no entry $id")
    }
}
