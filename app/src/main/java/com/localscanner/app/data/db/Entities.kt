package com.localscanner.app.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    var title: String,
    var folder: String?,
    val createdAt: Long,
    var updatedAt: Long,
    /** Concatenated OCR text used for full-text search across documents. */
    var searchText: String?,
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["docId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("docId")],
)
data class PageEntity(
    @PrimaryKey val id: String,
    val docId: String,
    var pageIndex: Int,
    val filePath: String,
    val thumbPath: String,
    var ocrText: String?,
    /** Which engine produced the text: "mlkit" or "tess". */
    var ocrEngine: String?,
    /** JSON encoded OCR line boxes used for the searchable PDF text layer. */
    var ocrLinesJson: String?,
    val createdAt: Long,
)

/** Summary row returned by document list queries. */
data class DocumentSummary(
    @Embedded val doc: DocumentEntity,
    val pageCount: Int,
    val coverPath: String?,
)
