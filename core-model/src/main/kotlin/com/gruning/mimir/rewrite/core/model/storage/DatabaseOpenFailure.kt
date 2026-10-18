package com.gruning.mimir.rewrite.core.model.storage

/**
 * A typed startup refusal that protects an on-disk Mimir database from a binary that cannot read it.
 *
 * This is deliberately not a recovery or reset command. Keeping the type in core-model lets the Android
 * storage boundary report an incompatible schema or an unsafe legacy identity without leaking Room or
 * SQLite exceptions into the owner-facing surface.
 */
data class DatabaseOpenFailure(
    val reason: DatabaseOpenFailureReason,
    val foundSchemaVersion: Int,
    val supportedSchemaVersion: Int,
) {
    init {
        require(foundSchemaVersion >= 1) { "A database refusal requires a positive found schema" }
        require(supportedSchemaVersion >= 1) {
            "A database refusal requires a positive supported schema"
        }
        when (reason) {
            DatabaseOpenFailureReason.NEWER_SCHEMA ->
                require(foundSchemaVersion > supportedSchemaVersion) {
                    "A newer-schema refusal requires foundSchemaVersion > supportedSchemaVersion"
                }
            DatabaseOpenFailureReason.LEGACY_PHYSICAL_IDENTITY_UNSAFE ->
                require(foundSchemaVersion <= supportedSchemaVersion) {
                    "A legacy-identity refusal requires a readable schema"
                }
        }
    }
}

enum class DatabaseOpenFailureReason {
    NEWER_SCHEMA,
    LEGACY_PHYSICAL_IDENTITY_UNSAFE,
}
