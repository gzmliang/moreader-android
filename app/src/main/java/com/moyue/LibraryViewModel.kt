package com.moyue.app.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.moyue.app.data.BookRepository
import com.moyue.app.data.models.Book
import com.moyue.app.sync.SyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 合并后的书架条目：本地书（深色）或云端独有（浅色） */
data class LibraryItem(
    val localBook: Book? = null,          // 非空表示本地存在
    val cloudInfo: SyncClient.BookInfo? = null,  // 非空表示云端存在
) {
    val title: String get() = localBook?.title ?: cloudInfo?.title ?: ""
    val author: String get() = localBook?.author ?: cloudInfo?.author ?: "未知"
    val isCloudOnly: Boolean get() = localBook == null && cloudInfo != null
    val isLocalOnly: Boolean get() = localBook != null && cloudInfo == null
    val isOnBoth: Boolean get() = localBook != null && cloudInfo != null
}

class LibraryViewModel(
    private val repository: BookRepository,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    val books: StateFlow<List<Book>> = repository.getAllBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _cloudBooks = MutableStateFlow<List<SyncClient.BookInfo>>(emptyList())
    val cloudBooks: StateFlow<List<SyncClient.BookInfo>> = _cloudBooks

    // Upload progress state
    private val _uploadProgress = MutableStateFlow(0)
    val uploadProgress: StateFlow<Int> = _uploadProgress
    private val _uploadTotal = MutableStateFlow(0)
    val uploadTotal: StateFlow<Int> = _uploadTotal
    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading

    /** 合并本地+云端书架，云端独有书籍放后面 */
    val mergedItems: StateFlow<List<LibraryItem>> = combine(books, _cloudBooks, _searchQuery) { localBooks, cloudBooks, query ->
        val localTitles = localBooks.map { it.title }.toMutableSet()

        val items = mutableListOf<LibraryItem>()

        // 1) 本地已有的书
        for (book in localBooks) {
            val cloudMatch = cloudBooks.find { it.title == book.title }
            if (cloudMatch != null) localTitles.remove(book.title) // 匹配上了就不算云端独有
            items.add(LibraryItem(localBook = book, cloudInfo = cloudMatch))
        }

        // 2) 云端独有（本地没有的）
        for (cb in cloudBooks) {
            if (cb.title in localTitles) {
                items.add(LibraryItem(cloudInfo = cb))
            }
        }

        // 3) 搜索过滤
        if (query.isNotBlank()) {
            items.filter { it.title.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true) }
        } else items
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 从服务器加载云端书库清单 */
    fun loadCloudBooks(syncClient: SyncClient) {
        viewModelScope.launch {
            syncClient.listBooks().onSuccess { books ->
                _cloudBooks.value = books
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun importBook(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val book = repository.importBook(uri)
                val coverPath = repository.extractCover(book.id)
                if (coverPath != null) {
                    repository.updateBookCover(book.id, coverPath)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun deleteBook(context: Context, book: Book) {
        viewModelScope.launch {
            repository.deleteBook(book)
        }
    }

    // ── 云同步 ──────────────────────────────────────────

    /** 上传全部本地书籍到云端（含书签和高亮） */
    fun uploadAllToCloud(context: Context, syncClient: SyncClient) {
        viewModelScope.launch {
            _isUploading.value = true
            val repo = BookRepository(context)
            val allBooks = repo.getAllBooksOnce()
            _uploadTotal.value = allBooks.size
            _uploadProgress.value = 0
            var success = 0
            var fail = 0
            for ((i, book) in allBooks.withIndex()) {
                val result = syncClient.uploadBookWithMetadata(book.id, repo)
                if (result.isSuccess) success++ else fail++
                _uploadProgress.value = i + 1
            }
            _isUploading.value = false
            android.widget.Toast.makeText(context,
                "同步完成：成功 $success 本${if (fail > 0) "，失败 $fail 本" else ""}",
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** 上传单本书到云端（含书签和高亮） */
    fun uploadSingleBook(context: Context, syncClient: SyncClient, bookId: String) {
        viewModelScope.launch {
            val repo = BookRepository(context)
            syncClient.uploadBookWithMetadata(bookId, repo).fold(
                onSuccess = { msg ->
                    android.widget.Toast.makeText(context, msg,
                        android.widget.Toast.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    android.widget.Toast.makeText(context, "上传失败: ${e.message}",
                        android.widget.Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    /** 下载云端独有书籍到本地（含书签和高亮） */
    fun downloadCloudBook(context: Context, syncClient: SyncClient, bookInfo: SyncClient.BookInfo) {
        viewModelScope.launch {
            val tmpFile = File(context.cacheDir, "download_${bookInfo.id}.epub")
            syncClient.downloadBook(bookInfo.id, tmpFile).fold(
                onSuccess = { file ->
                    val repo = BookRepository(context)
                    val localBook = repo.importEpubFile(file)
                    val coverPath = repo.extractCover(localBook.id)
                    if (coverPath != null) {
                        repo.updateBookCover(localBook.id, coverPath)
                    }
                    file.delete()
                    // 拉取云端元数据（书签+高亮+进度）
                    var restoredBm = 0
                    var restoredHl = 0
                    syncClient.pullBookMetadata(bookInfo.id).onSuccess { metaJson ->
                        // 解析并写入元数据
                        val obj = org.json.JSONObject(metaJson)
                        restoredBm = obj.optJSONArray("bookmarks")?.length() ?: 0
                        restoredHl = obj.optJSONArray("highlights")?.length() ?: 0
                        applyPullMetadata(repo, localBook.id, metaJson)
                    }
                    android.widget.Toast.makeText(context,
                        "已下载: ${bookInfo.title}（含${restoredBm}书签+${restoredHl}高亮）", android.widget.Toast.LENGTH_SHORT).show()
                    // 刷新书架
                    _cloudBooks.value = _cloudBooks.value.filter { it.id != bookInfo.id }
                },
                onFailure = { e ->
                    android.widget.Toast.makeText(context,
                        "下载失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    /** 合并同步：拉取云端 → 与本地合并（取最新） → 推回云端 */
    fun syncAllMetadata(context: Context, syncClient: SyncClient, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val repo = BookRepository(context)
            syncClient.mergeSync(repo).fold(
                onSuccess = { msg ->
                    android.util.Log.i("Sync", "同步成功: $msg")
                    onResult(msg)
                },
                onFailure = { e ->
                    android.util.Log.e("Sync", "同步失败", e)
                    onResult("同步失败: ${e.message}")
                },
            )
        }
    }

    /** 将拉取的元数据 JSON 写入本地书 */
    private suspend fun applyPullMetadata(repo: BookRepository, localBookId: String, json: String) {
        try {
            val obj = org.json.JSONObject(json)
            // 进度
            if (obj.has("progress") && !obj.isNull("progress")) {
                val p = obj.getJSONObject("progress")
                val chIdx = p.optInt("chapter_index", -1)
                if (chIdx >= 0) {
                    val localBook = repo.getBook(localBookId) ?: return
                    repo.updateProgress(localBookId,
                        p.optString("chapter_href", null), chIdx,
                        p.optDouble("percentage", 0.0).toFloat(), null,
                        p.optInt("paragraph_index", 0), localBook.themeId, localBook.fontSize)
                }
            }
            // 书签
            if (obj.has("bookmarks")) {
                val arr = obj.getJSONArray("bookmarks")
                val bms = (0 until arr.length()).map { j ->
                    val b = arr.getJSONObject(j)
                    com.moyue.app.data.models.Bookmark(
                        bookId = localBookId,
                        chapterIndex = b.optInt("chapter_index", 0),
                        chapterTitle = b.optString("chapter_title", null),
                        paragraphIndex = b.optInt("paragraph_index", 0),
                        paragraphText = b.optString("paragraph_text", null),
                        progress = b.optDouble("progress", 0.0).toFloat(),
                        createdAt = b.optLong("created_at", System.currentTimeMillis()),
                    )
                }
                repo.importBookmarks(bms)
            }
            // 高亮
            if (obj.has("highlights")) {
                val arr = obj.getJSONArray("highlights")
                val hls = (0 until arr.length()).map { j ->
                    val h = arr.getJSONObject(j)
                    com.moyue.app.data.models.Highlight(
                        bookId = localBookId,
                        chapterIndex = h.optInt("chapter_index", 0),
                        startParagraph = h.optInt("start_paragraph", 0),
                        startOffset = h.optInt("start_offset", 0),
                        endParagraph = h.optInt("end_paragraph", 0),
                        endOffset = h.optInt("end_offset", 0),
                        text = h.optString("text", ""),
                        note = h.optString("note", null),
                        color = h.optInt("color", 0xFFFFFF00.toInt()),
                        createdAt = h.optLong("created_at", System.currentTimeMillis()),
                    )
                }
                repo.importHighlights(hls)
            }
        } catch (e: Exception) {
            android.util.Log.e("Sync", "写入元数据失败", e)
        }
    }
}

class LibraryViewModelFactory(
    private val repository: BookRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return LibraryViewModel(repository) as T
    }
}
