package eu.kanade.tachiyomi.ui.comicdownloader

import android.content.Context
import android.util.Log
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
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
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
            val file = File(cblPath)
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(file)

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
            throw RuntimeException("Error parsing CBL file: ${e.message}", e)
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

    private var savedRepos: MutableList<String> = loadRepos().toMutableList()
    private var importedCbls: MutableMap<String, CBLImportData> = loadImportedCbls().toMutableMap()
    private var repoFiles: MutableMap<String, Map<String, String>> = mutableMapOf()

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

    private val json: Json = Injekt.get()

    private fun loadRepos(): List<String> {
        return try {
            if (savedReposFile.exists()) {
                savedReposFile.readText().split(",")
                    .map { it.trim().removeSurrounding("\"") }
                    .filter { it.startsWith("https://github.com") }
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
                entries.associate { it.fileName to CBLImportData(it.repoUrl, it.folderName, it.importedAt) }
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading imported CBLs", e)
            emptyMap()
        }
    }

    fun saveRepos() {
        try {
            val serialized = savedRepos.joinToString(",") { "\"$it\"" }
            savedReposFile.writeText("[$serialized]")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving repos", e)
        }
    }

    fun saveImportedCbls() {
        try {
            val entries = importedCbls.entries.map { (fileName, data) ->
                SavedCBLEntry(fileName, data.repoUrl, data.folderName, data.importedAt)
            }
            importedCblsFile.writeText(json.encodeToString(entries))
        } catch (e: Exception) {
            Log.e(TAG, "Error saving imported CBLs", e)
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
    }

    fun getSavedRepos(): List<String> = savedRepos.toList()

    fun markCblImported(fileName: String, repoUrl: String, folderName: String) {
        importedCbls[fileName] = CBLImportData(repoUrl, folderName, System.currentTimeMillis().toString())
        saveImportedCbls()
    }

    fun isCblImported(fileName: String): Boolean = fileName in importedCbls

    suspend fun fetchRepoFiles(repoUrl: String): Map<String, String> {
        return try {
            val normalizedUrl = repoUrl.trimEnd('/').replace(".git", "")
            val allCblFiles = mutableMapOf<String, String>()

            scanDirectory(normalizedUrl, allCblFiles, depth = 0)

            if (allCblFiles.isEmpty()) {
                throw IllegalArgumentException("No .cbl files found in $repoUrl")
            }

            repoFiles[repoUrl] = allCblFiles
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
                delay(BASE_SCAN_DELAY_MS + (depth * SCAN_DEPTH_INCREMENT_MS))
            }

            val request = Request.Builder().url(apiUrl).build()
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
                    type == "file" && name.endsWith(".cbl") -> {
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
            Log.e(TAG, "Error scanning directory", e)
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

            val normalizedUrl = repoUrl.trimEnd('/').replace(".git", "")
            val rawUrl = normalizedUrl.replace("github.com", "raw.githubusercontent.com") + "/main/$filePath"

            val request = Request.Builder().url(rawUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                throw IllegalStateException("Failed to fetch file: ${response.code}")
            }

            val tempFile = File.createTempFile(fileName, ".cbl")
            tempFile.writeText(response.body?.string() ?: "")

            val comicList = CBLParser.parse(tempFile.absolutePath)
            markCblImported(fileName, repoUrl, comicList.folderName)

            tempFile.delete()
            comicList
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import $fileName", e)
            throw e
        }
    }
}

/**
 * Searches installed Mihon sources for a matching manga series.
 */
class MihonComicSearcher(private val sourceManager: SourceManager = Injekt.get()) {

    suspend fun findBestMatch(series: String, issueNumber: String): Pair<CatalogueSource, SManga>? {
        val sources = sourceManager.getCatalogueSources()
        for (source in sources) {
            try {
                val page = source.getSearchManga(1, series, FilterList())
                val match = page.mangas.firstOrNull() ?: continue
                return Pair(source, match)
            } catch (e: Exception) {
                Log.w(TAG, "Source ${source.name} failed for '$series': ${e.message}")
            }
        }
        return null
    }

    suspend fun getMatchingChapter(source: CatalogueSource, sManga: SManga, issueNumber: String): SChapter? {
        return try {
            val chapters = source.getChapterList(sManga)
            val targetNumber = issueNumber.toFloatOrNull() ?: return chapters.firstOrNull()
            chapters.minByOrNull { abs(it.chapter_number - targetNumber) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get chapters for ${sManga.title}: ${e.message}")
            null
        }
    }
}

/**
 * Downloads a comic issue and packages it as a CBZ in LocalSource's directory.
 */
class LocalComicDownloader(private val storageManager: StorageManager = Injekt.get()) {

    suspend fun downloadIssueToLocalSource(
        source: HttpSource,
        sManga: SManga,
        sChapter: SChapter,
        mangaFolderName: String,
        chapterName: String,
        onProgress: (Int) -> Unit,
    ): Boolean {
        return try {
            val localSourceDir = storageManager.getLocalSourceDirectory() ?: return false
            val mangaDir = localSourceDir.createDirectory(mangaFolderName) ?: return false

            val pages = source.getPageList(sChapter)
            if (pages.isEmpty()) return false

            val zipFile = mangaDir.createFile("$chapterName.cbz") ?: return false
            zipFile.openOutputStream().use { fos ->
                ZipOutputStream(fos).use { zos ->
                    pages.forEachIndexed { idx, page ->
                        val response = source.getImage(page)
                        val bytes = response.body.bytes()
                        val ext = if (response.headers["Content-Type"]?.contains("png") == true) "png" else "jpg"
                        zos.putNextEntry(ZipEntry(String.format("%03d.%s", idx + 1, ext)))
                        zos.write(bytes)
                        zos.closeEntry()
                        response.close()
                        onProgress((idx + 1) * 100 / pages.size)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download '$chapterName': ${e.message}", e)
            false
        }
    }
}

/**
 * Manages importing CBL comic lists into Mihon's database and downloading issues.
 */
class CBLImportManager(context: Context) {

    private val networkToLocalManga: NetworkToLocalManga = Injekt.get()
    private val updateManga: UpdateManga = Injekt.get()
    private val setMangaCategories: SetMangaCategories = Injekt.get()
    private val createCategoryWithName: CreateCategoryWithName = Injekt.get()
    private val categoryRepository: CategoryRepository = Injekt.get()
    private val chapterRepository: ChapterRepository = Injekt.get()

    private val searcher = MihonComicSearcher()
    private val downloader = LocalComicDownloader()

    private var cachedCategoryId: Long? = null

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

    suspend fun importCbl(comicList: ComicList): Long {
        val categoryId = ensureCategory()
        val mangaFolderName = sanitizeFolderName(comicList.folderName)

        val sManga = SManga.create().apply {
            url = mangaFolderName
            title = comicList.folderName
            initialized = true
        }

        val inserted = networkToLocalManga.invoke(sManga.toDomainManga(LocalSource.ID))
        val mangaId = inserted.id

        updateManga.await(
            MangaUpdate(
                id = mangaId,
                favorite = true,
                dateAdded = System.currentTimeMillis(),
                initialized = true,
            ),
        )

        val chapters = comicList.books.mapIndexed { index, book ->
            val chapterName = "${book.series} #${book.number}"
            Chapter(
                id = -1L,
                mangaId = mangaId,
                read = false,
                bookmark = false,
                lastPageRead = 0L,
                dateFetch = System.currentTimeMillis(),
                sourceOrder = index.toLong(),
                url = "$mangaFolderName/$chapterName.cbz",
                name = chapterName,
                dateUpload = 0L,
                chapterNumber = book.number.toDoubleOrNull() ?: -1.0,
                scanlator = null,
                lastModifiedAt = System.currentTimeMillis(),
                version = 0L,
            )
        }
        chapterRepository.addAll(chapters)

        setMangaCategories.await(mangaId, listOf(categoryId))

        return mangaId
    }

    suspend fun downloadIssue(
        mangaId: Long,
        comicBook: ComicBook,
        mangaFolderName: String,
        onProgress: (String, Int) -> Unit,
    ) {
        val chapterName = "${comicBook.series} #${comicBook.number}"
        val (catalogueSource, sManga) = searcher.findBestMatch(comicBook.series, comicBook.number)
            ?: run {
                Log.w(TAG, "No source match for '$chapterName'")
                return
            }
        val sChapter = searcher.getMatchingChapter(catalogueSource, sManga, comicBook.number)
            ?: run {
                Log.w(TAG, "No chapter match for '$chapterName'")
                return
            }
        val httpSource = catalogueSource as? HttpSource
            ?: run {
                Log.w(TAG, "Source for '$chapterName' is not an HttpSource")
                return
            }
        downloader.downloadIssueToLocalSource(
            source = httpSource,
            sManga = sManga,
            sChapter = sChapter,
            mangaFolderName = mangaFolderName,
            chapterName = chapterName,
            onProgress = { progress -> onProgress(chapterName, progress) },
        )
    }

    private fun sanitizeFolderName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
}
