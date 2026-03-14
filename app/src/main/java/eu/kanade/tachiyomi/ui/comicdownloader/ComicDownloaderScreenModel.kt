package eu.kanade.tachiyomi.ui.comicdownloader

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.Job
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
    private var downloadJob: Job? = null

    init {
        mutableState.update { it.copy(repositories = repoManager.getSavedRepos()) }

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

    /** Import a CBL file selected from the device (local file picker). */
    fun importCblFromLocalFile(uri: Uri) {
        downloadJob = screenModelScope.launchIO {
            mutableState.update { it.copy(isLoading = true, error = null) }
            try {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not open file")
                val comicList = stream.use { CBLParser.parseFromStream(it) }
                startImportAndDownload(comicList)
            } catch (e: Exception) {
                mutableState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    /** Import a CBL file from a GitHub repository. */
    fun importAndDownloadCbl(repoUrl: String, fileName: String) {
        downloadJob = screenModelScope.launchIO {
            mutableState.update { it.copy(isLoading = true, error = null) }
            try {
                val comicList = repoManager.importCblFromRepository(repoUrl, fileName)
                startImportAndDownload(comicList)
            } catch (e: Exception) {
                mutableState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private suspend fun startImportAndDownload(comicList: ComicList) {
        val mangaId = importManager.importCbl(comicList)

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

        comicList.books.forEach { book ->
            val key = "${book.series} #${book.number}"
            updateQueueItem(key) { it.copy(status = "downloading") }

            try {
                importManager.downloadIssue(
                    mangaId = mangaId,
                    comicBook = book,
                    mangaFolderName = comicList.folderName,
                ) { _, progress ->
                    updateQueueItem(key) { it.copy(progress = progress) }
                }
                updateQueueItem(key) { it.copy(status = "done") }
            } catch (e: Exception) {
                updateQueueItem(key) { it.copy(status = "failed", error = e.message ?: "Failed") }
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

    fun clearQueue() {
        downloadJob?.cancel()
        downloadJob = null
        mutableState.update { it.copy(downloadQueue = emptyList()) }
    }

    @Immutable
    data class State(
        val isLoading: Boolean = false,
        val error: String? = null,
        val readingList: List<LibraryManga> = emptyList(),
        val downloadQueue: List<DownloadQueueItem> = emptyList(),
        val repositories: List<String> = emptyList(),
        val repoFiles: Map<String, Map<String, String>> = emptyMap(),
    )
}

@Immutable
data class DownloadQueueItem(
    val bookKey: String,
    val series: String,
    val issueNumber: String,
    val status: String, // "pending", "downloading", "done", "failed"
    val progress: Int = 0,
    val error: String = "",
)
