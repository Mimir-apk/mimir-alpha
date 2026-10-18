package com.gruning.mimir.rewrite

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/** Descriptor-backed, lab-UID-only document provider for recovery publication durability tests. */
class MimirLabRecoveryDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = rootDirectory().let { it.isDirectory || it.mkdirs() }

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: RootProjection).apply {
            newRow()
                .add(Root.COLUMN_ROOT_ID, RootId)
                .add(Root.COLUMN_DOCUMENT_ID, RootDocumentId)
                .add(Root.COLUMN_TITLE, "Mimir recovery lab")
                .add(
                    Root.COLUMN_FLAGS,
                    Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY,
                )
                .add(Root.COLUMN_MIME_TYPES, RecoveryMimeType)
        }

    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?,
    ): Cursor = MatrixCursor(projection ?: DocumentProjection).apply {
        includeDocument(this, documentId)
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        require(parentDocumentId == RootDocumentId) { "Unknown lab parent document" }
        return MatrixCursor(projection ?: DocumentProjection).apply {
            if (archiveFile().exists()) includeDocument(this, ArchiveDocumentId)
        }
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        require(parentDocumentId == RootDocumentId) { "Unknown lab parent document" }
        require(mimeType == RecoveryMimeType) { "Only recovery ZIP documents are supported" }
        require(displayName == RecoveryArchiveSuggestedFileName) { "Unexpected recovery document name" }
        val archive = archiveFile()
        archive.parentFile?.mkdirs()
        FileOutputStream(archive, false).use { output ->
            output.fd.sync()
        }
        return ArchiveDocumentId
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        if (documentId != ArchiveDocumentId) throw FileNotFoundException(documentId)
        val archive = archiveFile()
        archive.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(archive, ParcelFileDescriptor.parseMode(mode))
    }

    override fun deleteDocument(documentId: String) {
        if (documentId != ArchiveDocumentId || !archiveFile().delete()) {
            throw FileNotFoundException(documentId)
        }
    }

    override fun getDocumentType(documentId: String): String = when (documentId) {
        RootDocumentId -> Document.MIME_TYPE_DIR
        ArchiveDocumentId -> RecoveryMimeType
        else -> throw FileNotFoundException(documentId)
    }

    private fun includeDocument(cursor: MatrixCursor, documentId: String) {
        when (documentId) {
            RootDocumentId -> cursor.newRow()
                .add(Document.COLUMN_DOCUMENT_ID, RootDocumentId)
                .add(Document.COLUMN_DISPLAY_NAME, "Recovery lab")
                .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                .add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)
                .add(Document.COLUMN_SIZE, 0L)
                .add(Document.COLUMN_LAST_MODIFIED, rootDirectory().lastModified())

            ArchiveDocumentId -> {
                val archive = archiveFile()
                cursor.newRow()
                    .add(Document.COLUMN_DOCUMENT_ID, ArchiveDocumentId)
                    .add(Document.COLUMN_DISPLAY_NAME, RecoveryArchiveSuggestedFileName)
                    .add(Document.COLUMN_MIME_TYPE, RecoveryMimeType)
                    .add(
                        Document.COLUMN_FLAGS,
                        Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE,
                    )
                    .add(Document.COLUMN_SIZE, archive.takeIf(File::exists)?.length() ?: 0L)
                    .add(Document.COLUMN_LAST_MODIFIED, archive.takeIf(File::exists)?.lastModified() ?: 0L)
            }

            else -> throw FileNotFoundException(documentId)
        }
    }

    private fun rootDirectory(): File =
        File(requireNotNull(context).filesDir, "recovery-lab-provider")

    private fun archiveFile(): File = File(rootDirectory(), RecoveryArchiveSuggestedFileName)

    companion object {
        const val RootId = "mimir-recovery-lab"
        const val RootDocumentId = "recovery-lab-root"
        const val ArchiveDocumentId = "mimir-recovery.zip"
        const val RecoveryMimeType = "application/zip"

        private val RootProjection = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES,
        )
        private val DocumentProjection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
