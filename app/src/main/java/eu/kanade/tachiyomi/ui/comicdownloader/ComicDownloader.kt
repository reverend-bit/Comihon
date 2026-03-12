package eu.kanade.tachiyomi.ui.comicdownloader

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

private const val TAG = "ComicDownloader"

/**
 * Data class representing a comic book to be downloaded
 */
data class ComicBook(
    val series: String,
    val number: String,
    val volume: String,
    val year: String? = null,
    val issueId: String? = null,
)

/**
 * Data class representing a list of comics (parsed from CBL)
 */
data class ComicList(
    val folderName: String,
    val books: List<ComicBook>,
)

/**
 * Data class representing download progress
 */
data class DownloadStatus(
    val bookKey: String,
    val status: String, // "pending", "downloading", "done", "failed"
    val reason: String = "",
    val progress: Int = 0,
)

/**
 * Data class representing a comic list with its download status
 */
data class ComicArc(
    val folderName: String,
    val books: List<ComicBook>,
    val outputDir: String,
    val status: MutableMap<String, DownloadStatus> = mutableMapOf(),
)

/**
 * Data class representing a repository entry
 */
data class RepositoryCBLFile(
    val fileName: String,
    val filePath: String,
)

/**
 * Parses CBL (Comic Book List) XML files
 */
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

                val databaseNode = bookElement.childNodes.let { nodes ->
                    (0 until nodes.length).map { nodes.item(it) }
                        .find { it.nodeName == "Database" }
                }
                val issueId = databaseNode?.attributes?.getNamedItem("Issue")?.nodeValue

                if (!series.isNullOrEmpty() && !number.isNullOrEmpty() && !volume.isNullOrEmpty()) {
                    books.add(
                        ComicBook(
                            series = series,
                            number = number,
                            volume = volume,
                            year = year,
                            issueId = issueId,
                        ),
                    )
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
 * Handles downloading comics from readcomiconline.li
 */
class Downloader(
    private val baseUrl: String = "https://readcomiconline.li",
    private val timeout: Long = 30,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(timeout, TimeUnit.SECONDS)
        .readTimeout(timeout, TimeUnit.SECONDS)
        .build()

    suspend fun getSeriesUrl(series: String, volume: String): String? {
        return try {
            val searchQuery = URLEncoder.encode("$series ($volume)", "UTF-8")
            val searchUrl = "$baseUrl/AdvanceSearch?name=$searchQuery"

            val request = Request.Builder().url(searchUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) return null

            val doc = Jsoup.parse(response.body?.string() ?: "")
            val seriesLinks = doc.select("a[href^=/Comic/]")

            // Exact match first
            for (link in seriesLinks) {
                if (series.equals(link.text(), ignoreCase = true)) {
                    return baseUrl + link.attr("href")
                }
            }

            // Partial word match
            for (link in seriesLinks) {
                if (link.text().contains(series, ignoreCase = true)) {
                    return baseUrl + link.attr("href")
                }
            }

            // Try with first word
            val firstWord = series.split(" ").firstOrNull()?.lowercase() ?: ""
            if (firstWord.isNotEmpty()) {
                for (link in seriesLinks) {
                    if (link.text().lowercase().contains(firstWord)) {
                        return baseUrl + link.attr("href")
                    }
                }
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "Error searching for series $series", e)
            null
        }
    }

    suspend fun getIssueUrl(seriesUrl: String, number: String): String? {
        return try {
            val request = Request.Builder().url(seriesUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) return null

            val doc = Jsoup.parse(response.body?.string() ?: "")
            val issueLinks = doc.select("a[href~=/Issue-]")

            val numStr = number.trim()

            // Try exact matches first
            for (link in issueLinks) {
                val linkText = link.text().trim()
                when {
                    linkText.contains("#$numStr") -> return baseUrl + link.attr("href")
                    linkText.contains("Issue $numStr") -> return baseUrl + link.attr("href")
                    linkText.endsWith("#$numStr") -> return baseUrl + link.attr("href")
                    linkText.endsWith(numStr) -> return baseUrl + link.attr("href")
                }
            }

            // Fallback: try any link with the number
            for (link in issueLinks) {
                if (link.text().contains(numStr)) {
                    return baseUrl + link.attr("href")
                }
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching series page", e)
            null
        }
    }

    suspend fun downloadIssue(issueUrl: String, outputDir: String): Result<Unit> {
        return try {
            // Actual download logic would invoke Mihon's download system or an external tool.
            // This stub is a placeholder that should be replaced with the real implementation.
            Log.d(TAG, "downloadIssue: url=$issueUrl outputDir=$outputDir")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading from $issueUrl", e)
            Result.failure(e)
        }
    }
}

/**
 * Repository data for CBL files from GitHub
 */
data class GitHubRepository(
    val url: String,
    val cblFiles: Map<String, String> = emptyMap(), // {fileName: filePath}
)

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
                // TODO: replace with proper kotlinx.serialization deserialization
                emptyMap()
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
            val json = "[" + savedRepos.joinToString(",") { "\"$it\"" } + "]"
            savedReposFile.writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving repos", e)
        }
    }

    fun saveImportedCbls() {
        try {
            // TODO: replace with proper kotlinx.serialization serialization
            importedCblsFile.writeText(importedCbls.toString())
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
                delay((500 + (depth * 200)).toLong())
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
                    type == "dir" && depth < 5 -> {
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
 * Main comic downloader orchestrator.
 */
class ComicDownloader(context: Context) {
    private val parser = CBLParser
    private val downloader = Downloader()
    val repoManager = RepoManager(context)
    private val outputBaseDir = context.getExternalFilesDir(null)?.absolutePath
        ?: context.filesDir.absolutePath
    private val arcs = mutableMapOf<String, ComicArc>()

    /**
     * Load a CBL file and prepare it for downloading.
     */
    fun loadComicList(cblPath: String): ComicList {
        val comicList = parser.parse(cblPath)
        addArc(comicList.folderName, comicList.books)
        return comicList
    }

    /**
     * Import a CBL file from a GitHub repository.
     */
    suspend fun importFromRepository(repoUrl: String, fileName: String): ComicList {
        val comicList = repoManager.importCblFromRepository(repoUrl, fileName)
        addArc(comicList.folderName, comicList.books)
        return comicList
    }

    private fun addArc(folderName: String, books: List<ComicBook>) {
        val outputDir = "$outputBaseDir/$folderName"
        val status = mutableMapOf<String, DownloadStatus>()
        books.forEach { book ->
            val key = "${book.series} #${book.number}"
            status[key] = DownloadStatus(key, "pending")
        }
        arcs[folderName] = ComicArc(folderName, books, outputDir, status)
    }

    /**
     * Download all books in an arc, updating statuses as downloads proceed.
     */
    suspend fun downloadArc(arcName: String, onProgress: (String, DownloadStatus) -> Unit = { _, _ -> }): Boolean {
        val arc = arcs[arcName] ?: return false

        for (book in arc.books) {
            val key = "${book.series} #${book.number}"
            arc.status[key] = DownloadStatus(key, "downloading")
            onProgress(key, arc.status[key]!!)

            try {
                val seriesUrl = downloader.getSeriesUrl(book.series, book.volume)
                if (seriesUrl != null) {
                    val issueUrl = downloader.getIssueUrl(seriesUrl, book.number)
                    if (issueUrl != null) {
                        val result = downloader.downloadIssue(issueUrl, arc.outputDir)
                        arc.status[key] = if (result.isSuccess) {
                            DownloadStatus(key, "done")
                        } else {
                            DownloadStatus(key, "failed", result.exceptionOrNull()?.message ?: "Unknown error")
                        }
                    } else {
                        arc.status[key] = DownloadStatus(key, "failed", "No issue URL found")
                    }
                } else {
                    arc.status[key] = DownloadStatus(key, "failed", "No series URL found")
                }
            } catch (e: Exception) {
                arc.status[key] = DownloadStatus(key, "failed", e.message ?: "Unknown error")
            }

            onProgress(key, arc.status[key]!!)
        }

        return true
    }

    fun getArcProgress(arcName: String): Pair<Int, Int> {
        val arc = arcs[arcName] ?: return Pair(0, 0)
        val completed = arc.status.values.count { it.status == "done" }
        return Pair(completed, arc.books.size)
    }

    fun getAllArcs(): List<ComicArc> = arcs.values.toList()

    fun getArc(arcName: String): ComicArc? = arcs[arcName]

    fun addRepository(repoUrl: String) = repoManager.addRepo(repoUrl)

    fun removeRepository(repoUrl: String) = repoManager.removeRepo(repoUrl)

    fun getSavedRepositories(): List<String> = repoManager.getSavedRepos()

    suspend fun fetchRepositoryCBLs(repoUrl: String): Map<String, String> =
        repoManager.fetchRepoFiles(repoUrl)

    fun isFileImported(fileName: String): Boolean = repoManager.isCblImported(fileName)
}
