package eu.kanade.tachiyomi.ui.comicdownloader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.TabContent
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen

@Composable
fun Screen.readingListTab(screenModel: ComicDownloaderScreenModel): TabContent {
    val state = screenModel.state

    return TabContent(
        titleRes = MR.strings.label_comic_downloader_reading_list,
        content = { contentPadding, snackbarHostState ->
            ReadingListTabContent(
                state = state,
                contentPadding = contentPadding,
                snackbarHostState = snackbarHostState,
                onDownload = screenModel::downloadArc,
                onDelete = screenModel::removeFromReadingList,
            )
        },
    )
}

@Composable
private fun ReadingListTabContent(
    state: androidx.compose.runtime.State<ComicDownloaderScreenModel.State>,
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    onDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val currentState = state.value

    if (currentState.readingList.isEmpty()) {
        EmptyScreen(
            stringRes = MR.strings.label_comic_downloader_no_reading_lists,
            modifier = Modifier.padding(contentPadding),
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(currentState.readingList) { item ->
            val isCompleted = currentState.downloadQueue
                .filter { q -> q.bookKey.startsWith(item.folderName) || currentState.downloadQueue.isNotEmpty() }
                .let { queue ->
                    val arcItems = currentState.downloadQueue.filter { it.series.isNotEmpty() }
                    arcItems.isNotEmpty() && arcItems.all { it.status == "done" }
                }

            ComicListCard(
                item = item,
                onDownload = { onDownload(item.folderName) },
                onDelete = { onDelete(item.folderName) },
            )
        }
    }
}

@Composable
private fun ComicListCard(
    item: ComicListItem,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(4.dp)
            .fillMaxWidth(),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            ) {
                AsyncImage(
                    model = null, // Placeholder; real cover URL would go here
                    contentDescription = item.folderName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Text(
                    text = item.folderName.first().toString(),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.displayMedium,
                )
            }
            Text(
                text = item.folderName,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
            )
            Text(
                text = "${item.bookCount} ${stringResource(MR.strings.label_comic_downloader_issues)}",
                modifier = Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Outlined.Download, contentDescription = stringResource(MR.strings.action_download))
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null)
                }
            }
        }
    }
}
