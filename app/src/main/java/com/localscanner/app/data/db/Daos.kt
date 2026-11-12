package com.localscanner.app.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    @Insert
    suspend fun insert(doc: DocumentEntity)

    @Update
    suspend fun update(doc: DocumentEntity)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getById(id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observeById(id: String): Flow<DocumentEntity?>

    @Transaction
    @Query(
        """
        SELECT d.*,
            (SELECT COUNT(*) FROM pages WHERE docId = d.id) AS pageCount,
            (SELECT filePath FROM pages WHERE docId = d.id ORDER BY pageIndex ASC LIMIT 1) AS coverPath
        FROM documents d
        ORDER BY d.updatedAt DESC
        """
    )
    fun observeSummaries(): Flow<List<DocumentSummary>>

    @Transaction
    @Query(
        """
        SELECT d.*,
            (SELECT COUNT(*) FROM pages WHERE docId = d.id) AS pageCount,
            (SELECT filePath FROM pages WHERE docId = d.id ORDER BY pageIndex ASC LIMIT 1) AS coverPath
        FROM documents d
        WHERE d.title LIKE '%' || :q || '%'
           OR d.searchText LIKE '%' || :q || '%'
           OR EXISTS (SELECT 1 FROM pages p WHERE p.docId = d.id AND p.ocrText LIKE '%' || :q || '%')
        ORDER BY d.updatedAt DESC
        """
    )
    fun searchSummaries(q: String): Flow<List<DocumentSummary>>

    @Query("UPDATE documents SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)

    @Query("UPDATE documents SET folder = :folder, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setFolder(id: String, folder: String?, updatedAt: Long)

    @Query("UPDATE documents SET searchText = :text, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setSearchText(id: String, text: String?, updatedAt: Long)
}

@Dao
interface PageDao {

    @Insert
    suspend fun insert(page: PageEntity)

    @Update
    suspend fun update(page: PageEntity)

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM pages WHERE docId = :docId ORDER BY pageIndex ASC")
    fun observePages(docId: String): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE docId = :docId ORDER BY pageIndex ASC")
    suspend fun listPages(docId: String): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun getById(id: String): PageEntity?

    @Query("UPDATE pages SET pageIndex = :idx WHERE id = :id")
    suspend fun setIndex(id: String, idx: Int)

    @Query("SELECT COALESCE(MAX(pageIndex), -1) FROM pages WHERE docId = :docId")
    suspend fun maxIndex(docId: String): Int

    @Query("UPDATE pages SET ocrText = :text, ocrEngine = :engine, ocrLinesJson = :linesJson WHERE id = :id")
    suspend fun setOcr(id: String, text: String?, engine: String?, linesJson: String?)

    @Query("SELECT COUNT(*) FROM pages WHERE docId = :docId")
    suspend fun countForDoc(docId: String): Int
}

/** Result row for cover thumbnail joins. */
data class CoverRow(
    @Embedded val page: PageEntity,
)
