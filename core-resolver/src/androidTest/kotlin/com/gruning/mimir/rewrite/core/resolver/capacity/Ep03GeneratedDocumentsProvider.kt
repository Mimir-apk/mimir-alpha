package com.gruning.mimir.rewrite.core.resolver.capacity

import android.content.ContentProvider
import android.content.ContentValues
import android.database.AbstractCursor
import android.database.Cursor
import android.database.Cursor.FIELD_TYPE_INTEGER
import android.database.Cursor.FIELD_TYPE_NULL
import android.database.Cursor.FIELD_TYPE_STRING
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import com.gruning.mimir.rewrite.core.resolver.capacity.Ep03GeneratedFixtures.Order

/**
 * Instrumentation-only, metadata-only SAF fixture. Rows are computed at the cursor position, so a
 * 100,001-entry boundary probe does not allocate a MatrixCursor or create ROM-shaped payload files.
 */
class Ep03GeneratedDocumentsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = query(uri, projection, selection, selectionArgs, sortOrder, null)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
        cancellationSignal: CancellationSignal?,
    ): Cursor {
        cancellationSignal?.throwIfCanceled()
        val columns = projection?.map(String::toString)?.toTypedArray() ?: DEFAULT_COLUMNS
        val parent = DocumentsContract.getDocumentId(uri)
        val rowCount = if (parent == ROOT_ID) count else 0
        return GeneratedCursor(columns, rowCount, order, cancellationSignal)
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private class GeneratedCursor(
        private val columns: Array<String>,
        private val rowCount: Int,
        private val order: Order,
        private val cancellationSignal: CancellationSignal?,
    ) : AbstractCursor() {
        override fun getCount(): Int = rowCount
        override fun getColumnNames(): Array<String> = columns

        override fun getString(column: Int): String? {
            cancellationSignal?.throwIfCanceled()
            val index = Ep03GeneratedFixtures.indexAt(position, rowCount, order)
            return when (columns[column]) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> Ep03GeneratedFixtures.documentId(index)
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> Ep03GeneratedFixtures.displayName(index)
                DocumentsContract.Document.COLUMN_MIME_TYPE -> "application/octet-stream"
                else -> null
            }
        }

        override fun getShort(column: Int): Short = getLong(column).toShort()
        override fun getInt(column: Int): Int = getLong(column).toInt()

        override fun getLong(column: Int): Long {
            cancellationSignal?.throwIfCanceled()
            val index = Ep03GeneratedFixtures.indexAt(position, rowCount, order)
            return when (columns[column]) {
                DocumentsContract.Document.COLUMN_SIZE -> 32_768L + index
                else -> 0L
            }
        }

        override fun getFloat(column: Int): Float = getLong(column).toFloat()
        override fun getDouble(column: Int): Double = getLong(column).toDouble()

        override fun isNull(column: Int): Boolean = when (columns[column]) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            -> false
            else -> true
        }

        override fun getType(column: Int): Int = when {
            isNull(column) -> FIELD_TYPE_NULL
            columns[column] == DocumentsContract.Document.COLUMN_SIZE -> FIELD_TYPE_INTEGER
            else -> FIELD_TYPE_STRING
        }
    }

    companion object {
        const val ROOT_ID = "root"
        internal val TREE_URI: Uri =
            DocumentsContract.buildTreeDocumentUri(Ep03GeneratedFixtures.AUTHORITY, ROOT_ID)

        @Volatile
        internal var count: Int = 0

        @Volatile
        internal var order: Order = Order.FORWARD

        internal fun configure(entryCount: Int, enumerationOrder: Order) {
            require(entryCount > 0)
            count = entryCount
            order = enumerationOrder
        }

        private val DEFAULT_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}
