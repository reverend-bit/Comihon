# Comic Downloader Integration

## Overview

A "Comic Downloader" tab has been seamlessly integrated into Mihon's navigation,
sitting between the Browse and More tabs. It is built entirely from Mihon's own
systems — indistinguishable from any native tab.

## Architecture

### Data flow
1. User adds a GitHub repository URL containing `.cbl` files.
2. User selects a CBL file from the repository — this triggers:
   - Parsing the CBL XML to extract the comic book list (`CBLParser`)
   - Creating a single `Manga` entry in Mihon's database using `NetworkToLocalManga`
     with `source = LocalSource.ID`, `title = CBL folder name`
   - Pre-creating `Chapter` stubs for each issue in the CBL via `ChapterRepository.addAll()`
   - Assigning the manga to the "Comic Downloader" category
   - Searching each issue across installed Mihon sources (`SourceManager.getCatalogueSources()`)
   - Downloading pages via `HttpSource.getImage()` and packaging as `.cbz` files
     in `LocalSource/<CBL folder name>/<Series> #<Number>.cbz`
3. LocalSource auto-serves the downloaded chapters for native reading.

### Navigation
- **Reading List tab** — `GetLibraryManga` flow filtered to the "Comic Downloader" category,
  displayed using `MangaCompactGridItem` (identical to the Library). Tap → `MangaScreen(mangaId)`.
- **Queue tab** — per-issue download progress (status, progress bar, error).
- **Import tab** — GitHub URL management and CBL file import.

### File structure
```
app/src/main/java/eu/kanade/tachiyomi/ui/comicdownloader/
├── ComicDownloader.kt          CBLParser, RepoManager, MihonComicSearcher,
│                                LocalComicDownloader, CBLImportManager
├── ComicDownloaderScreenModel.kt  StateScreenModel wrapping the above
├── ComicDownloaderTab.kt       Voyager Tab (index 4u)
├── ImportTab.kt                Sub-tab for importing repos/CBLs
├── ReadingListTab.kt           Sub-tab showing the MangaCompactGridItem grid
└── QueueTab.kt                 Sub-tab showing download progress

app/src/main/res/drawable/
└── anim_comicdownloader_enter.xml   Tab icon (animated vector)

i18n/src/commonMain/moko-resources/base/strings.xml
    label_comic_downloader* strings added
```

### Modified files
- `ui/home/HomeScreen.kt` — ComicDownloaderTab added to TABS list; Tab.ComicDownloader added
- `ui/more/MoreTab.kt` — index bumped from 4u → 5u
