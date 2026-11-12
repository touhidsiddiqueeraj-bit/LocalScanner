package com.localscanner.app.data

import android.app.Application
import android.graphics.Bitmap
import com.localscanner.app.data.db.DocumentDao
import com.localscanner.app.data.db.DocumentEntity
import com.localscanner.app.data.db.DocumentSummary
import com.localscanner.app.data.db.PageDao
import com.localscanner.app.data.db.PageEntity
import com.localscanner.app.util.ImageFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class DocsRepository(
    private val app: Application,
    private val docDao: DocumentDao,
    private val pageDao: PageDao,
) {
    val documents: Flow<List<DocumentSummary>> = docDao.observeSummaries()

    fun observePages(docId: String): Flow<List<PageEntity>> = pageDao.observePages(docId)

    fun observeDoc(docId: String): Flow<DocumentEntity?> = docDao.observeById(docId)

    suspend fun getDoc(docId: String): DocumentEntity? = docDao.getById(docId)

    suspend fun getPages(docId: String): List<PageEntity> = pageDao.listPages(docId)

    fun docsDir(docId: String): File = File(app.filesDir, "documents/$docId").apply { mkdirs() }

    fun exportsDir(): File = File(app.filesDir, "exports").apply { mkdirs() }

    fun defaultTitle(): String =
        SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date())

    /**
     * Creates a document from finished page image files. The source files are
     * moved into the document directory, so callers must not reuse them.
     */
    suspend fun createDocFromFiles(title: String?, folder: String?, finalPages: List<PageResult>): DocumentEntity =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val doc = DocumentEntity(
                id = UUID.randomUUID().toString(),
                title = title?.takeIf { it.isNotBlank() } ?: defaultTitle(),
                folder = folder,
                createdAt = now,
                updatedAt = now,
                searchText = null,
            )
            docDao.insert(doc)
            val dir = docsDir(doc.id)
            insertPages(doc.id, dir, finalPages, startIndex = 0, now = now)
            doc
        }

    suspend fun appendPages(docId: String, finalPages: List<PageResult>): Int =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val dir = docsDir(docId)
            val start = pageDao.maxIndex(docId) + 1
            insertPages(docId, dir, finalPages, startIndex = start, now = now)
            docDao.getById(docId)?.let { d ->
                d.updatedAt = now
                docDao.update(d)
            }
            start + finalPages.size
        }

    private suspend fun insertPages(
        docId: String,
        dir: File,
        pages: List<PageResult>,
        startIndex: Int,
        now: Long,
    ) {
        val thumbDir = File(app.filesDir, "thumbs").apply { mkdirs() }
        pages.forEachIndexed { i, page ->
            val id = UUID.randomUUID().toString()
            val dest = File(dir, "page_$id.jpg")
            page.file.renameTo(dest) // move; falls back silently, source stays if FS denies
            val thumbFile = File(thumbDir, "thumb_$id.jpg")
            val bmp = ImageFiles.decodeScaled(dest.absolutePath, THUMB_SIZE)
            if (bmp != null) {
                ImageFiles.saveJpeg(bmp, thumbFile, 80)
                bmp.recycle()
            }
            pageDao.insert(
                PageEntity(
                    id = id,
                    docId = docId,
                    pageIndex = startIndex + i,
                    filePath = dest.absolutePath,
                    thumbPath = thumbFile.absolutePath,
                    ocrText = page.ocrText,
                    ocrEngine = page.ocrEngine,
                    ocrLinesJson = page.ocrLinesJson,
                    createdAt = now,
                )
            )
        }
    }

    suspend fun rename(docId: String, title: String) {
        docDao.rename(docId, title, System.currentTimeMillis())
    }

    suspend fun setFolder(docId: String, folder: String?) {
        docDao.setFolder(docId, folder, System.currentTimeMillis())
    }

    suspend fun deleteDoc(docId: String) {
        withContext(Dispatchers.IO) {
            docDao.delete(docId)
            File(app.filesDir, "documents/$docId").deleteRecursively()
        }
    }

    suspend fun deletePage(page: PageEntity) {
        withContext(Dispatchers.IO) {
            pageDao.delete(page.id)
            File(page.filePath).delete()
            File(page.thumbPath).delete()
            reindex(page.docId)
            docDao.getById(page.docId)?.let { d ->
                d.updatedAt = System.currentTimeMillis()
                docDao.update(d)
            }
        }
    }

    suspend fun movePage(page: PageEntity, delta: Int) {
        withContext(Dispatchers.IO) {
            val pages = pageDao.listPages(page.docId)
            val idx = pages.indexOfFirst { it.id == page.id }
            val target = idx + delta
            if (idx == -1 || target < 0 || target >= pages.size) return@withContext
            val reordered = pages.toMutableList().apply {
                removeAt(idx)
                add(target, page)
            }
            reordered.forEachIndexed { i, p -> pageDao.setIndex(p.id, i) }
        }
    }

    suspend fun rotatePage(page: PageEntity): Boolean =
        withContext(Dispatchers.IO) {
            val ok = ImageFiles.rotateFile(page.filePath, 90f)
            if (ok) {
                val bmp = ImageFiles.decodeScaled(page.filePath, THUMB_SIZE)
                if (bmp != null) {
                    ImageFiles.saveJpeg(bmp, File(page.thumbPath), 80)
                    bmp.recycle()
                }
            }
            ok
        }

    private suspend fun reindex(docId: String) {
        val pages = pageDao.listPages(docId)
        pages.forEachIndexed { i, p -> pageDao.setIndex(p.id, i) }
    }

    suspend fun setOcr(page: PageEntity, text: String?, engine: String?, linesJson: String?) {
        pageDao.setOcr(page.id, text, engine, linesJson)
        refreshSearchText(page.docId)
    }

    suspend fun refreshSearchText(docId: String) {
        val pages = pageDao.listPages(docId)
        val text = pages.mapNotNull { it.ocrText?.takeIf { t -> t.isNotBlank() } }
            .joinToString(separator = "\n")
            .takeIf { it.isNotBlank() }
        docDao.setSearchText(docId, text, System.currentTimeMillis())
    }

    data class PageResult(
        val file: File,
        val ocrText: String? = null,
        val ocrEngine: String? = null,
        val ocrLinesJson: String? = null,
    )

    companion object {
        const val THUMB_SIZE = 512
    }
}
