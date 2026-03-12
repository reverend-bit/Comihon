package eu.kanade.tachiyomi.ui.comicdownloader

import android.app.Application
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ComicDownloaderScreenModel(
    context: Application = Injekt.get(),
) : StateScreenModel<ComicDownloaderScreenModel.State>(State()) {

    private val comicDownloader = ComicDownloader(context)

    init {
        mutableState.update { it.copy(repositories = comicDownloader.getSavedRepositories()) }
    }

    fun addRepository(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        comicDownloader.addRepository(trimmed)
        mutableState.update { it.copy(repositories = comicDownloader.getSavedRepositories()) }
    }

    fun removeRepository(url: String) {
        comicDownloader.removeRepository(url)
        mutableState.update { state ->
            state.copy(
                repositories = comicDownloader.getSavedRepositories(),
                repoFiles = state.repoFiles - url,
            )
        }
    }

    fun fetchRepositoryCBLs(repoUrl: String) {
        screenModelScope.launchIO {
            mutableState.update { it.copy(isLoading = true, error = null) }
            try {
                val files = comicDownloader.fetchRepositoryCBLs(repoUrl)
                mutableState.update { state ->
                    state.copy(
                        isLoading = false,
                        repoFiles = state.repoFiles + (repoUrl to files),
                    )
                }
            } catch (e: Exception) {
                mutableState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun importCbl(repoUrl: String, fileName: String) {
        screenModelScope.launchIO {
            mutableState.update { it.copy(isLoading = true, error = null) }
            try {
                val comicList = comicDownloader.importFromRepository(repoUrl, fileName)
                val item = ComicListItem(
                    folderName = comicList.folderName,
                    bookCount = comicList.books.size,
                )
                mutableState.update { state ->
                    state.copy(
                        isLoading = false,
                        readingList = (state.readingList + item).distinctBy { it.folderName },
                    )
                }
            } catch (e: Exception) {
                mutableState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun removeFromReadingList(folderName: String) {
        mutableState.update { state ->
            state.copy(readingList = state.readingList.filter { it.folderName != folderName })
        }
    }

    fun downloadArc(folderName: String) {
        screenModelScope.launchIO {
            val arc = comicDownloader.getArc(folderName) ?: return@launchIO
            val initialQueue = arc.books.map { book ->
                DownloadQueueItem(
                    bookKey = "${book.series} #${book.number}",
                    series = book.series,
                    issueNumber = book.number,
                    status = "pending",
                )
            }
            mutableState.update { state ->
                val existing = state.downloadQueue.map { it.bookKey }.toSet()
                val toAdd = initialQueue.filter { it.bookKey !in existing }
                state.copy(downloadQueue = state.downloadQueue + toAdd)
            }

            comicDownloader.downloadArc(folderName) { key, status ->
                mutableState.update { state ->
                    state.copy(
                        downloadQueue = state.downloadQueue.map { item ->
                            if (item.bookKey == key) {
                                item.copy(
                                    status = status.status,
                                    progress = status.progress,
                                    error = status.reason,
                                )
                            } else {
                                item
                            }
                        },
                    )
                }
            }
        }
    }

    fun clearError() {
        mutableState.update { it.copy(error = null) }
    }

    @Immutable
    data class State(
        val isLoading: Boolean = false,
        val error: String? = null,
        val readingList: List<ComicListItem> = emptyList(),
        val downloadQueue: List<DownloadQueueItem> = emptyList(),
        val repositories: List<String> = emptyList(),
        val repoFiles: Map<String, Map<String, String>> = emptyMap(),
    )
}

@Immutable
data class ComicListItem(
    val folderName: String,
    val bookCount: Int,
)

@Immutable
data class DownloadQueueItem(
    val bookKey: String,
    val series: String,
    val issueNumber: String,
    val status: String, // "pending", "downloading", "done", "failed"
    val progress: Int = 0,
    val error: String = "",
)
