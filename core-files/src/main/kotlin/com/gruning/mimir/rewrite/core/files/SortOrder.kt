package com.gruning.mimir.rewrite.core.files

/**
 * Vendored from `SysAdminDoc/FileExplorer` (MIT) — see `/NOTICE`. Package renamed from
 * `com.explorer.fileexplorer.core.model`; unmodified otherwise. Upstream path:
 * `core/model/src/main/java/com/explorer/fileexplorer/core/model/SortOrder.kt`.
 */

enum class SortField {
    NAME, SIZE, DATE, TYPE
}

enum class SortDirection {
    ASCENDING, DESCENDING
}

data class SortOrder(
    val field: SortField = SortField.NAME,
    val direction: SortDirection = SortDirection.ASCENDING,
    val foldersFirst: Boolean = true,
)

enum class ViewMode {
    LIST, GRID
}

enum class FileColumn(val label: String) {
    SIZE("Size"),
    DATE("Modified"),
    TYPE("Type"),
    ;

    companion object {
        val DEFAULT_VISIBLE_COLUMNS: Set<FileColumn> = setOf(SIZE, DATE)
    }
}
