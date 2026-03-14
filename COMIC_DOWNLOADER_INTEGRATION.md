# Comic Downloader Integration

## Overview

A "Comic Downloader" tab has been seamlessly integrated into Mihon's navigation,
sitting between the Browse and More tabs. It imports **CBL (Comic Book List)** files
and uses Mihon's own search and download systems to find and download the listed
issues — no custom download pipeline, everything goes through the same code paths
a normal user would trigger manually.

---

## Workflow — "I add a CBL, what happens in order?"

### Step 1: Add a CBL file

**From a GitHub repository:**
1. User enters a GitHub URL (e.g. `https://github.com/owner/repo`).
2. `RepoManager` converts it to a GitHub API URL and recursively scans for
   `.cbl` files (depth ≤ 5, rate-limited delays to avoid throttling).
3. Found files are cached in `repo_files.json` and listed in the Import tab.
4. User taps a CBL file → `RepoManager` downloads the raw XML from
   `raw.githubusercontent.com`.

**From a local file:**
1. User picks a `.cbl` file from the device file picker.
2. The file is read directly via `ContentResolver`.

### Step 2: Parse the CBL

`CBLParser.parseFromStream()` reads the XML and extracts:

```xml
<ReadingList>
  <Name>[1990-1992] Infinity Gauntlet (Read Order)</Name>
  <Books>
    <Book Series="Silver Surfer" Number="34" Volume="3" Year="1990"/>
    <Book Series="Silver Surfer" Number="35" Volume="3" Year="1990"/>
    <Book Series="The Thanos Quest" Number="1" Volume="1" Year="1990"/>
    ...
  </Books>
</ReadingList>
```

Output: `ComicList(folderName, books)` — each book has a **series name**,
**issue number**, volume, and optional year.

### Step 3: Group by series and create the queue

`ComicDownloaderScreenModel.startImportAndDownload()` groups the books by
**series name** — all `Silver Surfer` issues together, all `The Thanos Quest`
issues together, etc. — so each series is searched only once.

Each issue gets a `DownloadQueueItem` displayed in the Queue tab
(status: `pending` → `searching` → `done` or `failed`).

### Step 4: For each series — search using Mihon's search

`MihonComicSearcher.findBestMatch(series)`:

1. Gets all **installed online sources** from `SourceManager.getOnlineSources()`.
2. Searches **every source in parallel** using `source.getSearchManga(1, series, FilterList())`.
   This is the same search Mihon uses when you type in the Browse tab.
3. Collects all results and picks the best match:
   - **Exact title match** (case-insensitive) → first choice
   - **Title contains** the series name → second choice
   - **First result** from any source → fallback
4. The result is cached in-memory (`seriesSourceCache` map inside
   `CBLImportManager`) for the lifetime of the current import session, so a
   second search for the same series name is instant.

**Key detail:** The search uses just the **series name** (e.g. `The Thanos Quest`)
— not the full issue identifier (`The Thanos Quest #1`). This finds the manga
entry that contains all issues.

### Step 5: Add the manga to the library

`CBLImportManager.importAndDownloadSeries()`:

1. Calls `source.getMangaDetails(sManga)` for full metadata (cover, author, etc.).
2. Saves to Mihon's database via `NetworkToLocalManga` — same path as adding any
   manga from the Browse tab.
3. Calls `UpdateManga.awaitUpdateFromSource()` to store the full details.
4. Sets `favorite = true` and assigns it to the **"Comic Downloader"** category
   (created automatically on first import).

The manga now appears in the **Reading List** tab and also in the main Library
under the "Comic Downloader" category.

### Step 6: Sync the chapter list from the source

1. Calls `source.getChapterList(sManga)` to get every available chapter.
2. Calls `SyncChaptersWithSource.await()` to write them into Mihon's database.
   This is the exact same chapter-sync that runs during a normal library update.

The manga now has a complete chapter list — identical to what you'd see if you
opened the manga from the Browse tab.

### Step 7: Match CBL issue numbers to real chapters

For each issue number the CBL requests (e.g. `34`, `35`, `1`):

1. Retrieves all chapters from the database via `GetChaptersByMangaId`.
2. Converts the issue number to a `Double` and finds the chapter whose
   `chapterNumber` is closest, accepting it if the difference is < 0.5
   (so issue `34` matches chapter `34.0` but not `35.0`).
3. Fallback: if the issue number isn't numeric, it looks for a chapter whose
   name contains `#<issueNumber>`.
4. Unmatched issues are logged and marked as `failed` in the queue.

### Step 8: Download using Mihon's download manager

Calls `DownloadManager.downloadChapters(manga, chaptersToDownload)` — the
same function Mihon uses when you long-press a chapter and tap "Download".

Downloads appear in Mihon's **standard download queue** (the notification and
the Downloads section). Files are saved to the configured download directory
under `<source name>/<manga title>/<chapter>/`.

### Step 9: Update the queue UI

- Matched & queued issues → status: `done` ✓
- Issues with no matching chapter → status: `failed` ✗ ("Chapter not found in source")
- Source errors → status: `failed` ✗ (error message)

---

## Architecture

### Navigation
- **Import tab** — GitHub URL management, file picker, and CBL file import.
- **Reading List tab** — `GetLibraryManga` flow filtered to the "Comic Downloader"
  category, displayed using `MangaCompactGridItem` (identical to the Library).
  Tap → `MangaScreen(mangaId)`.
- **Queue tab** — per-issue status (⏳ pending, 🔍 searching, ✓ done, ✗ failed).

### Mihon systems used

| Mihon component | How the Comic Downloader uses it |
|---|---|
| `SourceManager.getOnlineSources()` | Get all installed sources to search |
| `CatalogueSource.getSearchManga()` | Search for series by name |
| `Source.getMangaDetails()` | Fetch full manga metadata |
| `Source.getChapterList()` | Get all available chapters |
| `NetworkToLocalManga` | Insert manga into Mihon's database |
| `UpdateManga.awaitUpdateFromSource()` | Store full metadata from source |
| `SyncChaptersWithSource` | Sync chapter list from source to database |
| `GetChaptersByMangaId` | Retrieve synced chapters for matching |
| `DownloadManager.downloadChapters()` | Queue chapters for download |
| `SetMangaCategories` | Assign to "Comic Downloader" category |
| `GetLibraryManga` | Subscribe to library for Reading List display |

### Data structures

```kotlin
data class ComicBook(
    val series: String,    // "Silver Surfer"
    val number: String,    // "34"
    val volume: String,    // "3"
    val year: String?,     // "1990"
)

data class ComicList(
    val folderName: String,             // "[1990-1992] Infinity Gauntlet (Read Order)"
    val books: List<ComicBook>,         // All issues in the list
)

data class DownloadQueueItem(
    val bookKey: String,                // "Silver Surfer #34"
    val series: String,                 // "Silver Surfer"
    val issueNumber: String,            // "34"
    val status: String,                 // "pending" | "searching" | "done" | "failed"
    val progress: Int = 0,
    val error: String = "",
)
```

### Persistence (in `context.filesDir`)

| File | Contents |
|---|---|
| `saved_repos.json` | List of GitHub repository URLs |
| `repo_files.json` | Cached mapping of repos → their `.cbl` files |
| `imported_cbls.json` | Tracks which CBLs have been imported (with timestamp) |

### File structure
```
app/src/main/java/eu/kanade/tachiyomi/ui/comicdownloader/
├── ComicDownloader.kt             CBLParser, RepoManager, MihonComicSearcher,
│                                   CBLImportManager
├── ComicDownloaderScreenModel.kt  StateScreenModel wrapping the above
├── ComicDownloaderTab.kt          Voyager Tab (index 4u)
├── ImportTab.kt                   Sub-tab for importing repos/CBLs
├── ReadingListTab.kt              Sub-tab showing the MangaCompactGridItem grid
└── QueueTab.kt                    Sub-tab showing download status

app/src/main/res/drawable/
└── anim_comicdownloader_enter.xml   Tab icon (animated vector)

i18n/src/commonMain/moko-resources/base/strings.xml
    label_comic_downloader* strings added
```

### Modified files
- `ui/home/HomeScreen.kt` — ComicDownloaderTab added to TABS list; Tab.ComicDownloader added
- `ui/more/MoreTab.kt` — index bumped from 4u → 5u
