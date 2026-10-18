package com.gruning.mimir.tweaker.mgde

/** D6 freeze: checked 198 framed; checked 199 rendered null/not sent; raw 198 and 199 were observed only. */
internal object MgdeCommandBudget {
    const val SEALED_EVIDENCE =
        "Mimir-Probe45-RP5-D4D6-V3-20260730T015646Z"
    const val EVIDENCE_MANIFEST_SHA256 =
        "0b27cfc9c10ebe80d4e27fccd7508841bd5e1c8c429ff53cc8d6c6f4615852a2"
    const val CHECKED_INNER_MAX = 198
    const val INSTALLED_SOURCE_DIR_BYTES = 86
    const val DIRECT_S_BYTES = 152
    const val DIRECT_A_BYTES = 152
    const val DIRECT_R_BYTES = 152
    const val DIRECT_T_BYTES = 152
    const val DIRECT_C_BYTES = 152
    const val DIRECT_G_BYTES = 165
    const val LOCATOR_S_BYTES = 169
    const val LOCATOR_G_BYTES = 182
    const val RAW_198_MARKER_RETURNED = true
    const val RAW_199_MARKER_RETURNED = true
    const val CHECKED_198_FRAMED = true
    const val CHECKED_199_RENDERED_NULL = true
    const val CHECKED_199_NOT_SENT = true
    const val CHECKED_199_RENDERED_NULL_AND_NOT_SENT = true
    fun checked(command: String): String? = command.takeIf { it.toByteArray(Charsets.UTF_8).size <= CHECKED_INNER_MAX }
    /** Observation facts, not a transport capacity or payload grant. */
    fun rawObserved(length: Int): Boolean = (length == 198 && RAW_198_MARKER_RETURNED) || (length == 199 && RAW_199_MARKER_RETURNED)
}
