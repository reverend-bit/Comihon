package eu.kanade.tachiyomi.ui.comicdownloader

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ComicDownloaderScreenModel(
    private val context: Application = Injekt.get(),
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
) : StateScreenModel<ComicDownloaderScreenModel.State>(State()) {

    private val repoManager = RepoManager(context)
    private val importManager = CBLImportManager(context)

    init {
        mutableState.update {
            it.copy(
                repositories = repoManager.getSavedRepos(),
                importedCbls = repoManager.getImportedKeys(),
                repoFiles = repoManager.getAllRepoFiles(),
            )
        }

        screenModelScope.launchIO {
            getLibraryManga.subscribe()
                .map { libraryList ->
                    val categoryId = importManager.getCachedCategoryId()
                    if (categoryId == null) {
                        emptyList()
                    } else {
                        libraryList.filter { it.categories.contains(categoryId) }
                    }
                }
                .catch { /* ignore */ }
                .collectLatest { manga ->
                    mutableState.update { it.copy(readingList = manga) }
                }
        }
    }

    fun addRepository(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        repoManager.addRepo(trimmed)
        mutableState.update { it.copy(repositories = repoManager.getSavedRepos()) }
        fetchRepositoryCBLs(trimmed)
    }

    fun removeRepository(url: String) {
        repoManager.removeRepo(url)
        mutableState.update { state ->
            state.copy(
                repositories = repoManager.getSavedRepos(),
                repoFiles = state.repoFiles - url,
            )
        }
    }

    fun fetchRepositoryCBLs(repoUrl: String) {
        screenModelScope.launchIO {
            mutableState.update { it.copy(isLoading = true, error = null) }
            try {
                val files = repoManager.fetchRepoFiles(repoUrl)
                mutableState.update { state ->
                    state.copy(isLoading = false, repoFiles = state.repoFiles + (repoUrl to files))
                }
            } catch (e: Exception) {
                mutableState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun importCblFromLocalFile(uri: Uri) {
        screenModelScope.launchIO {
            mutableState.update { it.copy(isLoading = true, error = null) }
            try {
                val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        cursor.getString(nameIndex)
                    } else null
                } ?: "local_import_${System.currentTimeMillis()}.cbl"

                val stream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not open file")
                val comicList = stream.use { CBLParser.parseFromStream(it) }

                repoManager.markCblImported(fileName, "local", comicList.folderName)
                mutableState.update { it.copy(importedCbls = repoManager.getImportedKeys()) }

                startImportAndDownload(comicList)
            } catch (e: Exception) {
                mutableState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun importAndDownloadCbl(repoUrl: String, fileName: String) {
        screenModelScope.launchIO {
            mutableState.update { it.copy(isLoading = true, error = null) }
            try {
                val comicList = repoManager.importCblFromRepository(repoUrl, fileName)
                mutableState.update { it.copy(importedCbls = repoManager.getImportedKeys()) }
                startImportAndDownload(comicList)
            } catch (e: Exception) {
                mutableState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private suspend fun startImportAndDownload(comicList: ComicList) {
        // Create queue items for each book
        val queueItems = comicList.books.map { book ->
            DownloadQueueItem(
                bookKey = "${book.series} #${book.number}",
                series = book.series,
                issueNumber = book.number,
                status = "pending",
            )
        }
        mutableState.update { state ->
            state.copy(
                isLoading = false,
                downloadQueue = state.downloadQueue + queueItems,
            )
        }

        // Group books by series name (search once per series, not per issue)
        val seriesGroups = comicList.books.groupBy { it.series }

        for ((series, books) in seriesGroups) {
            // Mark all issues for this series as "searching"
            books.forEach { book ->
                val key = "${book.series} #${book.number}"
                updateQueueItem(key) { it.copy(status = "searching") }
            }

            try {
                val issueNumbers = books.map { it.number }
                val matchedIssues = importManager.importAndDownloadSeries(series, issueNumbers)

                // Update queue items based on which issues were matched
                books.forEach { book ->
                    val key = "${book.series} #${book.number}"
                    if (book.number in matchedIssues) {
                        updateQueueItem(key) { it.copy(status = "done", progress = 100) }
                    } else {
                        updateQueueItem(key) { it.copy(status = "failed", error = "Chapter not found in source") }
                    }
                }
            } catch (e: Exception) {
                // Mark all issues for this series as failed
                books.forEach { book ->
                    val key = "${book.series} #${book.number}"
                    updateQueueItem(key) { it.copy(status = "failed", error = e.message ?: "Failed") }
                }
            }
        }
    }

    private fun updateQueueItem(key: String, transform: (DownloadQueueItem) -> DownloadQueueItem) {
        mutableState.update { state ->
            state.copy(
                downloadQueue = state.downloadQueue.map { if (it.bookKey == key) transform(it) else it },
            )
        }
    }

    fun clearError() {
        mutableState.update { it.copy(error = null) }
    }

    @Immutable
    data class State(
        val isLoading: Boolean = false,
        val error: String? = null,
        val readingList: List<LibraryManga> = emptyList(),
        val downloadQueue: List<DownloadQueueItem> = emptyList(),
        val repositories: List<String> = emptyList(),
        val repoFiles: Map<String, Map<String, String>> = emptyMap(),
        val importedCbls: Set<String> = emptySet(),
    )
}

@Immutable
data class DownloadQueueItem(
    val bookKey: String,
    val series: String,
    val issueNumber: String,
    val status: String, // "pending", "searching", "done", "failed"
    val progress: Int = 0,
    val error: String = "",
)
