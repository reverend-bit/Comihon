package eu.kanade.tachiyomi.ui.comicdownloader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen

@Composable
fun Screen.queueTab(screenModel: ComicDownloaderScreenModel): TabContent {
    val state by screenModel.state.collectAsState()

    return TabContent(
        titleRes = MR.strings.label_comic_downloader_queue,
        actions = if (state.downloadQueue.isNotEmpty()) {
            persistentListOf(
                AppBar.OverflowAction(
                    title = stringResource(MR.strings.action_cancel_all),
                    onClick = { screenModel.clearQueue() },
                ),
            )
        } else {
            persistentListOf()
        },
        content = { contentPadding, _ ->
            if (state.downloadQueue.isEmpty()) {
                EmptyScreen(
                    stringRes = MR.strings.label_comic_downloader_queue_empty,
                    modifier = Modifier.padding(contentPadding),
                )
                return@TabContent
            }

            LazyColumn(
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.downloadQueue) { item ->
                    QueueItem(item)
                }
            }
        },
    )
}

@Composable
private fun QueueItem(item: DownloadQueueItem) {
    val (statusIcon, statusColor) = when (item.status) {
        "done" -> Icons.Outlined.Check to MaterialTheme.colorScheme.tertiary
        "failed" -> Icons.Outlined.Close to MaterialTheme.colorScheme.error
        "downloading" -> null to MaterialTheme.colorScheme.primary
        else -> Icons.Outlined.HourglassBottom to MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (statusIcon != null) {
                Icon(statusIcon, contentDescription = item.status, tint = statusColor)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.series,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Issue #${item.issueNumber}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = item.status.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelMedium,
                color = statusColor,
            )
        }

        if (item.status == "downloading") {
            LinearProgressIndicator(
                progress = { item.progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }

        if (item.status == "failed" && item.error.isNotEmpty()) {
            Text(
                text = item.error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
