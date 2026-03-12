# Mihon Comic Downloader Integration Guide

## Overview

The ComicDownloader has been fully integrated into Mihon's core functionality as a new main tab. This integration provides:

- **New "Comic Downloader" Tab** alongside Browse, History, Updates, Library, and More tabs
- **3 Nested Sub-tabs**:
  1. **Import Tab** - Import and manage GitHub repositories with CBL files
  2. **Reading List Tab** - Browse and manage imported comic lists with Mihon-style UI
  3. **Queue Tab** - Monitor download progress for all active downloads

- **Mihon Search Integration** - Uses Mihon's built-in source system for searching comics
- **Smart Folder Organization** - Creates folders with CBL names instead of extension names
- **Native Mihon UI Components** - All UI elements match Mihon's design system

## Architecture

### File Structure

```
app/src/main/java/eu/kanade/tachiyomi/ui/comicdownloader/
├── ComicDownloaderTab.kt              # Main tab implementation
├── ComicDownloaderScreen.kt            # Top-level screen with nested tabs
├── ComicDownloaderScreenModel.kt       # State management & business logic
├── ImportTab.kt                        # Import functionality UI
├── ReadingListTab.kt                   # Reading list with grid layout
├── QueueTab.kt                         # Download queue monitoring
└── EnhancedComicDownloader.kt         # Core logic with Mihon integration
```

## Component Details

### ComicDownloaderTab.kt

The main tab registration that includes the Comic Downloader in the home screen navigation.

- **Tab Index**: 5u (after Browse tab)
- **Title**: Uses i18n resource `MR.strings.label_comic_downloader`
- **Animated Icon**: Uses library icon animation (can be customized)

### ComicDownloaderScreen.kt

Top-level screen containing the 3 nested tabs with Material 3 TabRow.

Features:
- Horizontal tab navigation between Import, Reading List, and Queue
- Page-based navigation using HorizontalPager
- Snackbar support for error handling
- Integrated state management

### ComicDownloaderScreenModel.kt

Manages state and events for the entire Comic Downloader feature.

**State Data Class** (`ComicDownloaderState`):
```kotlin
data class ComicDownloaderState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val readingList: List<ComicListItem> = emptyList(),
    val downloadQueue: List<DownloadQueueItem> = emptyList(),
    val repositories: List<String> = emptyList(),
)
```

**Events**:
- `ComicListImported` - Fired when a CBL is imported
- `DownloadStarted` - Fires when download begins
- `DownloadProgressUpdated` - Real-time progress updates
- `DownloadCompleted` - Completion notification

### ImportTab.kt

Handles repository management and CBL file imports.

Features:
- Add GitHub repositories with URL input
- Display saved repositories in a list
- Search and select CBL files from repositories
- Real-time repository fetching with rate limiting
- Error handling with snackbar messages

### ReadingListTab.kt

Displays imported comic lists using a grid layout matching Mihon's library design.

Features:
- Grid-based layout (adaptive columns, ~150dp width)
- Cover images with Coil image loading library
- Issue count display
- Download status indicators (Pending, Downloading, Completed)
- Action buttons for download and deletion
- Completed items show a checkmark icon

### QueueTab.kt

Real-time monitoring of active downloads.

Features:
- List view of all queued downloads
- Status icons and colored labels:
  - ✓ Completed (Tertiary color)
  - ⬇ Downloading (Primary color)
  - ✕ Failed (Error color)
  - ⏳ Pending (Neutral)
- Linear progress indicator showing download percentage
- Error messages for failed downloads
- Issue number and series title display

### EnhancedComicDownloader.kt

Core business logic with Mihon search integration.

**Key Classes**:

#### CBLParser
```kotlin
object CBLParser {
    fun parse(cblPath: String): ComicList
}
```
- Parses CBL XML files
- Extracts comic series, issues, volumes, and years
- Returns `ComicList` with folder name

#### MihonComicSearcher
```kotlin
class MihonComicSearcher(
    private val context: Context,
    private val sourceManager: SourceManager,
)
```
- Leverages Mihon's SourceManager
- Searches available sources for comic series
- Retrieves chapters matching issue numbers
- Handles source fallback if one fails

#### EnhancedComicDownloader
```kotlin
class EnhancedComicDownloader(
    private val context: Context,
    private val sourceManager: SourceManager,
    private val outputBaseDir: String,
)
```
- Orchestrates CBL loading and downloading
- Uses Mihon search for series lookup
- Creates folders with CBL names (not extension names)
- Manages download progress and status
- Integrates with Mihon's download system

## Usage

### 1. Adding Repositories

Users can add GitHub repositories by:
1. Navigating to the **Comic Downloader** tab
2. Going to the **Import** sub-tab
3. Entering a GitHub repository URL
4. Clicking the "+" button

The system will:
- Recursively scan the repository for `.cbl` files
- Display available CBL files
- Allow selection for import

### 2. Importing Comic Lists

Once repositories are added:
1. Select a repository from the list
2. Browse available CBL files
3. Click a file to import
4. The comic list is added to the **Reading List**

### 3. Managing Reading Lists

In the **Reading List** tab:
- View all imported comic lists as cards
- See cover images (auto-fetched or placeholder)
- View issue count and status
- Click a card to start downloading
- Delete lists using the trash icon

### 4. Monitoring Downloads

The **Queue** tab shows:
- Real-time download progress
- Status for each comic issue
- Progress percentage for active downloads
- Error messages for failed downloads

## Mihon Search Integration

The `MihonComicSearcher` class integrates with Mihon's source system:

```kotlin
suspend fun searchSeriesMihon(series: String, volume: String): SManga? {
    val sources = sourceManager.getCatalogueSources()
    for (source in sources) {
        val results = source.searchManga(
            page = 1,
            query = "$series ($volume)",
            filters = FilterList()
        )
        // Return first matching manga
    }
}
```

**How it works**:
1. Queries all available sources in order
2. Searches for the comic series name and volume
3. Returns the first matching manga
4. Falls back to next source if search fails
5. Uses Mihon's manga objects that link to actual sources

## Folder Organization

When importing a CBL and downloading comics:

```
/Comics (or custom base directory)
├── [CBL Folder Name]  ← Named after CBL file, not source
│   ├── Issue #1
│   ├── Issue #2
│   └── ...
├── [Another CBL]
│   └── ...
```

Example:
```
/Comics
├── Marvel Action Comics 2023
│   ├── Action Comics 1
│   ├── Action Comics 2
│   └── ...
```

## String Resources Required

Add these to your i18n files (MR.strings):

```kotlin
label_comic_downloader = "Comic Downloader"
label_import = "Import"
label_reading_list = "Reading List"
label_queue = "Queue"
label_add_repository = "Add Repository"
label_saved_repositories = "Saved Repositories"
label_select = "Select"
label_remove = "Remove"
label_select_cbl_file = "Select CBL File"
label_no_reading_lists = "No reading lists added"
label_queue_empty = "Download queue is empty"
label_download = "Download"
```

## Integration Points

### SourceManager
- Used to get available sources
- Enables searching across all configured sources
- Integrates with Mihon's catalogues

### State Management
- Uses Voyager's StateScreenModel
- Follows Mihon's architecture pattern
- Coroutine-based async operations

### UI Components
- Compose Material 3 components
- Compatible with Mihon's theme
- Supports both phone and tablet layouts

## Future Enhancements

1. **Advanced Search Filters**
   - Filter by release year
   - Search by author/writer
   - Series completion status

2. **Batch Operations**
   - Bulk download multiple lists
   - Auto-download on interval
   - Resume interrupted downloads

3. **CBL Editor**
   - Create/edit CBL files in-app
   - Add custom series manually
   - Export curated lists

4. **Source Priority**
   - Set preferred sources for searching
   - Custom mappings for series names
   - Fallback source chains

5. **Analytics**
   - Download statistics
   - Most-downloaded series
   - Source performance metrics

## Technical Notes

### Dependencies Used
- `kotlinx.serialization` - For JSON parsing
- `kotlinx-coroutines` - Async operations
- `okhttp3` - HTTP requests
- `jsoup` - HTML parsing (for repository scanning)
- `lifecycle` - Lifecycle management
- `coil` - Image loading (for covers)

### Thread Safety
- All download operations use `Dispatchers.IO`
- State updates are thread-safe via Flow
- Repository scanning uses rate limiting to avoid API throttling

### Error Handling
- Try-catch blocks with meaningful error messages
- Snackbar notifications for user feedback
- Fallback to next source if search fails
- Graceful handling of network errors

## Testing

To test the integration:

1. **Create a test CBL file** with valid XML structure
2. **Add a test repository** with CBL files
3. **Test imports** to verify CBL parsing
4. **Test searches** with available Mihon sources
5. **Monitor queue** during downloads

## Troubleshooting

### CBL files not found
- Verify repository URL is correct
- Check that CBL files use `.cbl` extension
- Ensure repository structure has proper permissions

### Search returning no results
- Verify series name matches source database
- Try searching in Mihon directly with same query
- Check if source is properly installed and enabled

### Downloads failing
- Verify internet connection
- Check if source is accessible
- Ensure enough storage space
- Review error messages in Queue tab

## Support

For issues or feature requests related to the Comic Downloader integration:
1. Check the existing issues in the repository
2. Provide detailed error logs
3. Include CBL file samples for debugging
4. Specify which sources are available in your Mihon instance
