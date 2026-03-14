package eu.kanade.tachiyomi.ui.comicdownloader

import android.content.Context
import android.util.Log
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.domain.manga.model.toDomainManga
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

private const val TAG = "ComicDownloader"
private const val BASE_SCAN_DELAY_MS = 500L
private const val SCAN_DEPTH_INCREMENT_MS = 200L
private const val MAX_SCAN_DEPTH = 5

data class ComicBook(
    val series: String,
    val number: String,
    val volume: String,
    val year: String? = null,
)

data class ComicList(
    val folderName: String,
    val books: List<ComicBook>,
)

object CBLParser {
    fun parse(cblPath: String): ComicList {
        return try {
            File(cblPath).inputStream().use { parseFromStream(it) }
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            throw RuntimeException("Error parsing CBL file: ${e.message}", e)
        }
    }

    fun parseFromStream(stream: InputStream): ComicList {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(stream)

            val root = doc.documentElement
            val nameNode = root.getElementsByTagName("Name")

            if (nameNode.length == 0) {
                throw IllegalArgumentException("No <Name> tag found in CBL. Cannot create folder.")
            }

            val folderName = nameNode.item(0).textContent.trim()
            val books = mutableListOf<ComicBook>()

            val bookNodes = root.getElementsByTagName("Book")
            for (i in 0 until bookNodes.length) {
                val bookElement = bookNodes.item(i)
                val series = bookElement.attributes?.getNamedItem("Series")?.nodeValue
                val number = bookElement.attributes?.getNamedItem("Number")?.nodeValue
                val volume = bookElement.attributes?.getNamedItem("Volume")?.nodeValue
                val year = bookElement.attributes?.getNamedItem("Year")?.nodeValue

                if (!series.isNullOrEmpty() && !number.isNullOrEmpty() && !volume.isNullOrEmpty()) {
                    books.add(ComicBook(series = series, number = number, volume = volume, year = year))
                } else {
                    Log.w(TAG, "Skipping incomplete entry: Series=$series, Number=$number, Volume=$volume")
                }
            }

            if (books.isEmpty()) {
                throw IllegalArgumentException("No valid books found in CBL. Nothing to download.")
            }

            ComicList(folderName = folderName, books = books)
        } catch (e: Exception) {
            throw RuntimeException("Error parsing CBL stream: ${e.message}", e)
        }
    }
}

/**
 * Manages GitHub repositories and CBL imports, persisting data in app-private storage.
 */
class RepoManager(context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val savedReposFile = File(context.filesDir, "saved_repos.json")
    private val importedCblsFile = File(context.filesDir, "imported_cbls.json")
    private val repoFilesFile = File(context.filesDir, "repo_files.json")

    // json MUST be initialized before savedRepos/importedCbls since load* functions use it
    private val json: Json = Injekt.get()

    private var savedRepos: MutableList<String> = loadRepos().toMutableList()
    private var importedCbls: MutableMap<String, CBLImportData> = loadImportedCbls().toMutableMap()
    private var repoFiles: MutableMap<String, Map<String, String>> = loadRepoFiles().toMutableMap()

    data class CBLImportData(
        val repoUrl: String,
        val folderName: String,
        val importedAt: String,
    )

    // Use a simple JSON format for persistence compatible with kotlinx.serialization
    @Serializable
    private data class SavedCBLEntry(
        val fileName: String,
        val repoUrl: String,
        val folderName: String,
        val importedAt: String,
    )

    @Serializable
    private data class RepoFilesEntry(
        val repoUrl: String,
        val files: Map<String, String>,
    )

    private fun loadRepos(): List<String> {
        return try {
            if (savedReposFile.exists()) {
                json.decodeFromString<List<String>>(savedReposFile.readText())
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading repos", e)
            emptyList()
        }
    }

    private fun loadImportedCbls(): Map<String, CBLImportData> {
        return try {
            if (importedCblsFile.exists()) {
                val entries = json.decodeFromString<List<SavedCBLEntry>>(importedCblsFile.readText())
                entries.associate { getImportKey(it.repoUrl, it.fileName) to CBLImportData(it.repoUrl, it.folderName, it.importedAt) }
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading imported CBLs", e)
            emptyMap()
        }
    }

    private fun loadRepoFiles(): Map<String, Map<String, String>> {
        return try {
            if (repoFilesFile.exists()) {
                val entries = json.decodeFromString<List<RepoFilesEntry>>(repoFilesFile.readText())
                entries.associate { it.repoUrl to it.files }
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading repo files cache", e)
            emptyMap()
        }
    }

    fun saveRepos() {
        try {
            savedReposFile.writeText(json.encodeToString(savedRepos))
        } catch (e: Exception) {
            Log.e(TAG, "Error saving repos", e)
        }
    }

    fun saveImportedCbls() {
        try {
            val entries = importedCbls.entries.mapNotNull { (key, data) ->
                val parts = key.split("|", limit = 2)
                if (parts.size == 2) {
                    SavedCBLEntry(parts[1], parts[0], data.folderName, data.importedAt)
                } else null
            }
            importedCblsFile.writeText(json.encodeToString(entries))
        } catch (e: Exception) {
            Log.e(TAG, "Error saving imported CBLs", e)
        }
    }

    private fun saveRepoFiles() {
        try {
            val entries = repoFiles.entries.map { (repoUrl, files) ->
                RepoFilesEntry(repoUrl, files)
            }
            repoFilesFile.writeText(json.encodeToString(entries))
        } catch (e: Exception) {
            Log.e(TAG, "Error saving repo files cache", e)
        }
    }

    fun addRepo(repoUrl: String) {
        if (!savedRepos.contains(repoUrl)) {
            savedRepos.add(repoUrl)
            saveRepos()
        }
    }

    fun removeRepo(repoUrl: String) {
        savedRepos.remove(repoUrl)
        repoFiles.remove(repoUrl)
        saveRepos()
        saveRepoFiles()
    }

    fun getSavedRepos(): List<String> = savedRepos.toList()

    fun getAllRepoFiles(): Map<String, Map<String, String>> = repoFiles.toMap()

    fun getImportedKeys(): Set<String> = importedCbls.keys

    private fun getImportKey(repoUrl: String, fileName: String): String = "$repoUrl|$fileName"

    fun markCblImported(fileName: String, repoUrl: String, folderName: String) {
        val key = getImportKey(repoUrl, fileName)
        importedCbls[key] = CBLImportData(repoUrl, folderName, System.currentTimeMillis().toString())
        saveImportedCbls()
    }

    fun isCblImported(repoUrl: String, fileName: String): Boolean = getImportKey(repoUrl, fileName) in importedCbls

    suspend fun fetchRepoFiles(repoUrl: String): Map<String, String> {
        return try {
            // Convert user-facing GitHub URL to GitHub Contents API URL
            // https://github.com/owner/repo -> https://api.github.com/repos/owner/repo/contents
            val apiBaseUrl = repoUrl.trimEnd('/')
                .removeSuffix(".git")
                .replace("https://github.com/", "https://api.github.com/repos/")
                .plus("/contents")

            val allCblFiles = mutableMapOf<String, String>()
            scanDirectory(apiBaseUrl, allCblFiles, depth = 0)

            if (allCblFiles.isEmpty()) {
                throw IllegalArgumentException("No .cbl files found in $repoUrl")
            }

            repoFiles[repoUrl] = allCblFiles
            saveRepoFiles()
            Log.d(TAG, "Fetched ${allCblFiles.size} CBL files from $repoUrl")
            allCblFiles
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch repo files", e)
            throw e
        }
    }

    private suspend fun scanDirectory(
        apiUrl: String,
        collector: MutableMap<String, String>,
        depth: Int = 0,
    ) {
        try {
            if (depth > 0) {
                // Rate-limit polite delay: increases with recursion depth to avoid GitHub API throttling
                delay(BASE_SCAN_DELAY_MS + (depth * SCAN_DEPTH_INCREMENT_MS))
            }

            val request = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github.v3+json")
                .build()
            val response = client.newCall(request).execute()

            if (response.code == 404) {
                Log.w(TAG, "Directory not found: $apiUrl")
                return
            }

            if (!response.isSuccessful) {
                Log.w(TAG, "Error scanning $apiUrl: ${response.code}")
                return
            }

            val body = response.body?.string() ?: return
            val items = mutableListOf<Triple<String, String, String>>()

            parseJsonArray(body).forEach { item ->
                val name = extractJsonString(item, "name") ?: return@forEach
                val type = extractJsonString(item, "type") ?: return@forEach
                val path = extractJsonString(item, "path") ?: return@forEach
                items.add(Triple(name, type, path))
            }

            for ((name, type, path) in items) {
                when {
                    type == "file" && name.endsWith(".cbl", ignoreCase = true) -> {
                        collector[name] = path
                        Log.d(TAG, "Found CBL file: $name at $path")
                    }
                    type == "dir" && depth < MAX_SCAN_DEPTH -> {
                        val subUrl = apiUrl.trimEnd('/') + '/' + name
                        scanDirectory(subUrl, collector, depth + 1)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning directory $apiUrl", e)
        }
    }

    private fun parseJsonArray(jsonString: String): List<String> {
        val trimmed = jsonString.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyList()

        val content = trimmed.substring(1, trimmed.length - 1)
        val items = mutableListOf<String>()
        var current = StringBuilder()
        var braceCount = 0
        var inQuotes = false
        var escaped = false

        for (char in content) {
            when {
                escaped -> { current.append(char); escaped = false }
                char == '\\' -> { current.append(char); escaped = true }
                char == '"' -> { current.append(char); inQuotes = !inQuotes }
                !inQuotes && char == '{' -> { braceCount++; current.append(char) }
                !inQuotes && char == '}' -> {
                    braceCount--
                    current.append(char)
                    if (braceCount == 0) {
                        items.add(current.toString())
                        current = StringBuilder()
                    }
                }
                else -> current.append(char)
            }
        }
        return items
    }

    private fun extractJsonString(jsonObject: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]*?)\"".toRegex()
        return pattern.find(jsonObject)?.groupValues?.get(1)
    }

    suspend fun importCblFromRepository(repoUrl: String, fileName: String): ComicList {
        return try {
            val fileDict = repoFiles[repoUrl] ?: fetchRepoFiles(repoUrl)
            val filePath = fileDict[fileName] ?: throw IllegalArgumentException("File not found: $fileName")

            // Use /HEAD/ so the URL works for any default branch name (main, master, etc.)
            val rawUrl = repoUrl.trimEnd('/')
                .removeSuffix(".git")
                .replace("https://github.com/", "https://raw.githubusercontent.com/")
                .plus("/HEAD/$filePath")

            val request = Request.Builder().url(rawUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                throw IllegalStateException("Failed to fetch CBL file: HTTP ${response.code}")
            }

            val body = response.body ?: throw IllegalStateException("Empty response body")
            val comicList = body.byteStream().use { CBLParser.parseFromStream(it) }
            markCblImported(fileName, repoUrl, comicList.folderName)
            comicList
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import $fileName", e)
            throw e
        }
    }
}

/**
 * Searches installed Mihon sources for a matching manga series.
 * Uses Mihon's source search in parallel across all online sources,
 * searching by series name only (not issue number).
 */
class MihonComicSearcher(private val sourceManager: SourceManager = Injekt.get()) {

    suspend fun findBestMatch(series: String): Pair<CatalogueSource, SManga>? {
        val sources = sourceManager.getOnlineSources()
        if (sources.isEmpty()) return null

        // Search all online sources in parallel (like Mihon's global search)
        val results = coroutineScope {
            sources.map { source ->
                async {
                    try {
                        val page = source.getSearchManga(1, series, FilterList())
                        page.mangas.map { manga -> (source as CatalogueSource) to manga }
                    } catch (e: Exception) {
                        Log.w(TAG, "Source ${source.name} failed for '$series': ${e.message}")
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

        if (results.isEmpty()) return null

        // Prioritize exact title match (case-insensitive)
        results.find { (_, manga) ->
            manga.title.equals(series, ignoreCase = true)
        }?.let { return it }

        // Then try contains match
        results.find { (_, manga) ->
            manga.title.contains(series, ignoreCase = true)
        }?.let { return it }

        // Fall back to first result
        return results.first()
    }
}

/**
 * Manages importing CBL comic lists into Mihon's library and downloading issues
 * using Mihon's native search and download systems.
 */
class CBLImportManager(context: Context) {

    private val networkToLocalManga: NetworkToLocalManga = Injekt.get()
    private val updateManga: UpdateManga = Injekt.get()
    private val setMangaCategories: SetMangaCategories = Injekt.get()
    private val createCategoryWithName: CreateCategoryWithName = Injekt.get()
    private val categoryRepository: CategoryRepository = Injekt.get()
    private val downloadManager: DownloadManager = Injekt.get()
    private val syncChaptersWithSource: SyncChaptersWithSource = Injekt.get()
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get()
    private val getManga: GetManga = Injekt.get()

    private val searcher = MihonComicSearcher()

    private var cachedCategoryId: Long? = null

    // Cache for series matches to avoid repeating expensive searches
    private val seriesSourceCache = mutableMapOf<String, Pair<CatalogueSource, SManga>?>()

    companion object {
        const val CATEGORY_NAME = "Comic Downloader"
    }

    suspend fun getCachedCategoryId(): Long? {
        if (cachedCategoryId == null) {
            cachedCategoryId = categoryRepository.getAll().find { it.name == CATEGORY_NAME }?.id
        }
        return cachedCategoryId
    }

    suspend fun ensureCategory(): Long {
        cachedCategoryId?.let { return it }
        val categories = categoryRepository.getAll()
        val existing = categories.find { it.name == CATEGORY_NAME }
        if (existing != null) {
            cachedCategoryId = existing.id
            return existing.id
        }
        createCategoryWithName.await(CATEGORY_NAME)
        val updated = categoryRepository.getAll()
        val created = updated.find { it.name == CATEGORY_NAME }
            ?: error("Failed to create '$CATEGORY_NAME' category")
        cachedCategoryId = created.id
        return created.id
    }

    /**
     * Searches for a series using Mihon's search, adds the manga to the library,
     * syncs chapters from the source, and queues matching issues for download
     * using Mihon's download manager.
     *
     * @param series the series name to search for (without issue number)
     * @param issueNumbers the specific issue numbers needed from this series
     * @return the set of issue numbers that were successfully matched and queued
     */
    suspend fun importAndDownloadSeries(
        series: String,
        issueNumbers: List<String>,
    ): Set<String> {
        val categoryId = ensureCategory()

        // 1. Search for the series using Mihon's search (just the series name)
        val (source, sManga) = seriesSourceCache.getOrPut(series) {
            searcher.findBestMatch(series)
        } ?: run {
            Log.w(TAG, "No source match for series '$series'")
            return emptySet()
        }

        // 2. Get full manga details and save to DB
        val detailedManga = try {
            source.getMangaDetails(sManga)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get details for '${sManga.title}': ${e.message}")
            sManga
        }

        val domainManga = networkToLocalManga(detailedManga.toDomainManga(source.id))
        val mangaId = domainManga.id

        // Update with full details from source
        updateManga.awaitUpdateFromSource(domainManga, detailedManga, manualFetch = false)

        // Set as favorite and assign to "Comic Downloader" category
        updateManga.await(
            MangaUpdate(
                id = mangaId,
                favorite = true,
                dateAdded = System.currentTimeMillis(),
            ),
        )
        setMangaCategories.await(mangaId, listOf(categoryId))

        // 3. Sync chapters from source using Mihon's chapter sync
        val sourceChapters = source.getChapterList(sManga)
        val refreshedManga = getManga.await(mangaId) ?: domainManga
        syncChaptersWithSource.await(sourceChapters, refreshedManga, source)

        // 4. Find matching chapters for the needed issue numbers
        val dbChapters = getChaptersByMangaId.await(mangaId)
        val matchedIssues = mutableSetOf<String>()
        val chaptersToDownload = mutableListOf<Chapter>()

        for (issueNumber in issueNumbers) {
            val chapter = findMatchingChapter(dbChapters, issueNumber)
            if (chapter != null) {
                chaptersToDownload.add(chapter)
                matchedIssues.add(issueNumber)
            } else {
                Log.w(TAG, "No matching chapter for '$series' #$issueNumber")
            }
        }

        // 5. Download matched chapters using Mihon's download manager
        if (chaptersToDownload.isNotEmpty()) {
            val latestManga = getManga.await(mangaId) ?: refreshedManga
            downloadManager.downloadChapters(latestManga, chaptersToDownload.distinct())
        }

        return matchedIssues
    }

    private fun findMatchingChapter(
        dbChapters: List<Chapter>,
        issueNumber: String,
    ): Chapter? {
        val targetNumber = issueNumber.toDoubleOrNull()
        return if (targetNumber != null) {
            // Find chapter with closest matching number (within threshold of 0.5)
            val bestMatch = dbChapters.minByOrNull { abs(it.chapterNumber - targetNumber) }
            bestMatch?.takeIf { abs(it.chapterNumber - targetNumber) < 0.5 }
        } else {
            // Fallback: try matching by name containing the issue number
            dbChapters.find { it.name.contains("#$issueNumber", ignoreCase = true) }
        }
    }
}
