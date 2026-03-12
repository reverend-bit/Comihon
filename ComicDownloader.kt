import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.min

/**
 * Data class representing a comic book to be downloaded
 */
data class ComicBook(
    val series: String,
    val number: String,
    val volume: String,
    val year: String? = null,
    val issueId: String? = null
)

/**
 * Data class representing a list of comics (parsed from CBL)
 */
data class ComicList(
    val folderName: String,
    val books: List<ComicBook>
)

/**
 * Data class representing download progress
 */
data class DownloadStatus(
    val bookKey: String,
    val status: String, // "pending", "downloading", "done", "failed"
    val reason: String = "",
    val progress: Int = 0
)

/**
 * Data class representing a comic list with its download status
 */
data class ComicArc(
    val folderName: String,
    val books: List<ComicBook>,
    val outputDir: String,
    val status: MutableMap<String, DownloadStatus> = mutableMapOf()
)

/**
 * Data class representing a repository entry
 */
data class RepositoryCBLFile(
    val fileName: String,
    val filePath: String
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
                    books.add(ComicBook(
                        series = series,
                        number = number,
                        volume = volume,
                        year = year,
                        issueId = issueId
                    ))
                } else {
                    System.err.println("Skipping incomplete entry: Series=$series, Number=$number, Volume=$volume")
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
    private val timeout: Long = 30
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
            System.err.println("Error searching for series $series: ${e.message}")
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
            System.err.println("Error fetching series page: ${e.message}")
            null
        }
    }
    
    suspend fun downloadIssue(issueUrl: String, outputDir: String): Result<Unit> {
        return try {
            // ExecutableRunner.run("comic-dl", "-dd", outputDir, "-i", issueUrl)
            // For actual implementation, this would call the comic-dl command
            // You might want to use a process builder or call it via JNI/external tool
            Result.success(Unit)
        } catch (e: Exception) {
            System.err.println("Error downloading from $issueUrl: ${e.message}")
            Result.failure(e)
        }
    }
}

/**
 * Repository data for CBL files from GitHub
 */
data class GitHubRepository(
    val url: String,
    val cblFiles: Map<String, String> = emptyMap() // {fileName: filePath}
)

/**
 * Manages GitHub repositories and CBL imports
 */
class RepoManager(
    private val savedReposFile: String = "saved_repos.json",
    private val importedCblsFile: String = "imported_cbls.json"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private var savedRepos: MutableList<String> = loadRepos().toMutableList()
    private var importedCbls: MutableMap<String, CBLImportData> = loadImportedCbls().toMutableMap()
    private var repoFiles: MutableMap<String, Map<String, String>> = mutableMapOf() // {repo_url: {fileName: filePath}}
    
    data class CBLImportData(
        val repoUrl: String,
        val folderName: String,
        val importedAt: String
    )
    
    private fun loadRepos(): List<String> {
        return try {
            val file = File(savedReposFile)
            if (file.exists()) {
                // Simple JSON parsing - replace with a JSON library like Kotlinx.serialization if needed
                file.readText().split(",")
                    .map { it.trim().removeSurrounding("\"") }
                    .filter { it.startsWith("https://github.com") }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            System.err.println("Error loading repos: ${e.message}")
            emptyList()
        }
    }
    
    private fun loadImportedCbls(): Map<String, CBLImportData> {
        return try {
            val file = File(importedCblsFile)
            if (file.exists()) {
                // Simple JSON parsing - replace with proper JSON library
                emptyMap()
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            System.err.println("Error loading imported CBLs: ${e.message}")
            emptyMap()
        }
    }
    
    fun saveRepos() {
        try {
            val json = "[" + savedRepos.joinToString(",") { "\"$it\"" } + "]"
            File(savedReposFile).writeText(json)
        } catch (e: Exception) {
            System.err.println("Error saving repos: ${e.message}")
        }
    }
    
    fun saveImportedCbls() {
        try {
            // Save to file - use proper JSON library for production
            val file = File(importedCblsFile)
            file.writeText(importedCbls.toString())
        } catch (e: Exception) {
            System.err.println("Error saving imported CBLs: ${e.message}")
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
            System.out.println("Fetched ${allCblFiles.size} CBL files from $repoUrl")
            allCblFiles
        } catch (e: Exception) {
            System.err.println("Failed to fetch repo files: ${e.message}")
            throw e
        }
    }
    
    private suspend fun scanDirectory(
        apiUrl: String,
        collector: MutableMap<String, String>,
        depth: Int = 0
    ) {
        try {
            // Rate limiting
            if (depth > 0) {
                Thread.sleep((500 + (depth * 200)).toLong())
            }
            
            val request = Request.Builder().url(apiUrl).build()
            val response = client.newCall(request).execute()
            
            if (response.code == 404) {
                System.err.println("Directory not found: $apiUrl")
                return
            }
            
            if (!response.isSuccessful) {
                System.err.println("Error scanning $apiUrl: ${response.code}")
                return
            }
            
            val body = response.body?.string() ?: return
            // Parse JSON response - you'll need a JSON library for this
            // For now, a simple implementation:
            
            val items = mutableListOf<Triple<String, String, String>>() // name, type, path
            
            // This is a simplified parser - use a real JSON library in production
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
                        System.out.println("Found CBL file: $name at $path")
                    }
                    type == "dir" && depth < 5 -> { // Limit depth to avoid infinite recursion
                        val subUrl = apiUrl.trimEnd('/') + '/' + name
                        scanDirectory(subUrl, collector, depth + 1)
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("Error scanning directory: ${e.message}")
        }
    }
    
    private fun parseJsonArray(jsonString: String): List<String> {
        val trimmed = jsonString.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            return emptyList()
        }
        
        val content = trimmed.substring(1, trimmed.length - 1)
        val items = mutableListOf<String>()
        var current = StringBuilder()
        var braceCount = 0
        var inQuotes = false
        var escaped = false
        
        for (char in content) {
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }
                char == '\\' -> {
                    current.append(char)
                    escaped = true
                }
                char == '"' -> {
                    current.append(char)
                    inQuotes = !inQuotes
                }
                !inQuotes && char == '{' -> {
                    braceCount++
                    current.append(char)
                }
                !inQuotes && char == '}' -> {
                    braceCount--
                    current.append(char)
                    if (braceCount == 0) {
                        items.add(current.toString())
                        current = StringBuilder()
                    }
                }
                else -> {
                    current.append(char)
                }
            }
        }
        
        return items
    }
    
    private fun extractJsonString(jsonObject: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]*?)\"".toRegex()
        return pattern.find(jsonObject)?.groupValues?.get(1)
    }
    
    suspend fun importCblFromRepository(
        repoUrl: String,
        fileName: String
    ): ComicList {
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
            System.err.println("Failed to import $fileName: ${e.message}")
            throw e
        }
    }
}

/**
 * Main comic downloader orchestrator
 */
class ComicDownloader {
    private val parser = CBLParser
    private val downloader = Downloader()
    private val repoManager = RepoManager()
    private val arcs = mutableMapOf<String, ComicArc>()
    
    /**
     * Load a CBL file and prepare it for downloading
     */
    fun loadComicList(cblPath: String, outputDir: String): ComicList {
        return try {
            val comicList = parser.parse(cblPath)
            addArc(comicList.folderName, comicList.books, outputDir)
            comicList
        } catch (e: Exception) {
            System.err.println("Failed to load CBL file: ${e.message}")
            throw e
        }
    }
    
    /**
     * Import a CBL file from GitHub repository
     */
    suspend fun importFromRepository(
        repoUrl: String,
        fileName: String,
        outputDir: String
    ): ComicList {
        return try {
            val comicList = repoManager.importCblFromRepository(repoUrl, fileName)
            addArc(comicList.folderName, comicList.books, outputDir)
            comicList
        } catch (e: Exception) {
            System.err.println("Failed to import from repository: ${e.message}")
            throw e
        }
    }
    
    /**
     * Add a comic arc to the download queue
     */
    private fun addArc(folderName: String, books: List<ComicBook>, outputDir: String) {
        val status = mutableMapOf<String, DownloadStatus>()
        books.forEach { book ->
            val key = "${book.series} #${book.number}"
            status[key] = DownloadStatus(key, "pending")
        }
        
        arcs[folderName] = ComicArc(folderName, books, outputDir, status)
    }
    
    /**
     * Download all books in an arc
     */
    suspend fun downloadArc(arcName: String): Boolean {
        return try {
            val arc = arcs[arcName] ?: return false
            
            for (book in arc.books) {
                val key = "${book.series} #${book.number}"
                arc.status[key] = DownloadStatus(key, "downloading")
                
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
            }
            
            true
        } catch (e: Exception) {
            System.err.println("Error downloading arc: ${e.message}")
            false
        }
    }
    
    /**
     * Get download progress for an arc
     */
    fun getArcProgress(arcName: String): Pair<Int, Int> {
        val arc = arcs[arcName] ?: return Pair(0, 0)
        val completed = arc.status.values.count { it.status == "done" }
        val total = arc.books.size
        return Pair(completed, total)
    }
    
    /**
     * Get all arcs
     */
    fun getAllArcs(): List<ComicArc> = arcs.values.toList()
    
    /**
     * Get arc by name
     */
    fun getArc(arcName: String): ComicArc? = arcs[arcName]
    
    /**
     * Add a repository
     */
    fun addRepository(repoUrl: String) {
        repoManager.addRepo(repoUrl)
    }
    
    /**
     * Remove a repository
     */
    fun removeRepository(repoUrl: String) {
        repoManager.removeRepo(repoUrl)
    }
    
    /**
     * Get saved repositories
     */
    fun getSavedRepositories(): List<String> = repoManager.getSavedRepos()
    
    /**
     * Fetch CBL files from a repository
     */
    suspend fun fetchRepositoryCBLs(repoUrl: String): Map<String, String> {
        return repoManager.fetchRepoFiles(repoUrl)
    }
    
    /**
     * Check if a CBL file has been imported
     */
    fun isFileImported(fileName: String): Boolean {
        return repoManager.isCblImported(fileName)
    }
}

/**
 * Example usage
 */
suspend fun main() {
    val downloader = ComicDownloader()
    
    // Add a repository
    downloader.addRepository("https://github.com/user/comic-repo")
    
    // Fetch CBLs from repo
    try {
        val cbls = downloader.fetchRepositoryCBLs("https://github.com/user/comic-repo")
        println("Found ${cbls.size} CBL files")
        
        // Import a CBL
        val comicList = downloader.importFromRepository(
            "https://github.com/user/comic-repo",
            "comics.cbl",
            "/storage/comics"
        )
        println("Imported ${comicList.books.size} books from ${comicList.folderName}")
        
        // Start download
        val success = downloader.downloadArc(comicList.folderName)
        
        // Check progress
        val (completed, total) = downloader.getArcProgress(comicList.folderName)
        println("Progress: $completed/$total")
    } catch (e: Exception) {
        System.err.println("Error: ${e.message}")
    }
}

#intergrate this into mihons core functionality. create new tabs alongside the browse, history, more, updates and library tabs. it will be one tab that has 3 nested inside it. these will be an import tab, a readinglist tab (this is where the cbls will be, icons will be just like the ones normal comics and mangas have when using mihon), and a queue tab to monitor progress. Just use all the gui code that mihon uses for each part where needed. another thing is that the search function needs to be completely revamped and will instead use the mihon search for each issue in the cbl. it will create a folder with the cbl name rather than the extension name (e.ge not readcomicsfree). 
