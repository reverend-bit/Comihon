package eu.kanade.tachiyomi.ui.comicdownloader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.screens.EmptyScreen

@Composable
fun Screen.readingListTab(screenModel: ComicDownloaderScreenModel): TabContent {
    val state by screenModel.state.collectAsState()
    val navigator = LocalNavigator.currentOrThrow

    return TabContent(
        titleRes = MR.strings.label_comic_downloader_reading_list,
        content = { contentPadding, _ ->
            if (state.readingList.isEmpty()) {
                EmptyScreen(
                    stringRes = MR.strings.label_comic_downloader_no_reading_lists,
                    modifier = Modifier.padding(contentPadding),
                )
                return@TabContent
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 96.dp),
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    items = state.readingList,
                    key = { it.manga.id },
                ) { libraryManga ->
                    MangaCompactGridItem(
                        coverData = libraryManga.manga.asMangaCover(),
                        title = libraryManga.manga.title,
                        onClick = { navigator.push(MangaScreen(libraryManga.manga.id)) },
                        onLongClick = { navigator.push(MangaScreen(libraryManga.manga.id)) },
                        coverBadgeEnd = null,
                        coverBadgeStart = null,
                    )
                }
            }
        },
    )
}
