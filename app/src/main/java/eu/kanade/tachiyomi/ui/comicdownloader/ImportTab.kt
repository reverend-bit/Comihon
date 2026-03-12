package eu.kanade.tachiyomi.ui.comicdownloader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.presentation.components.TabContent
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun Screen.importTab(screenModel: ComicDownloaderScreenModel): TabContent {
    val state by screenModel.state.collectAsState()

    return TabContent(
        titleRes = MR.strings.label_comic_downloader_import,
        content = { contentPadding, snackbarHostState ->
            ImportTabContent(
                state = state,
                contentPadding = contentPadding,
                snackbarHostState = snackbarHostState,
                onAddRepository = screenModel::addRepository,
                onRemoveRepository = screenModel::removeRepository,
                onFetchCBLs = screenModel::fetchRepositoryCBLs,
                onImportCBL = screenModel::importCbl,
                onClearError = screenModel::clearError,
            )
        },
    )
}

@Composable
private fun ImportTabContent(
    state: ComicDownloaderScreenModel.State,
    contentPadding: PaddingValues,
    snackbarHostState: SnackbarHostState,
    onAddRepository: (String) -> Unit,
    onRemoveRepository: (String) -> Unit,
    onFetchCBLs: (String) -> Unit,
    onImportCBL: (String, String) -> Unit,
    onClearError: () -> Unit,
) {
    var newRepoUrl by remember { mutableStateOf("") }
    var selectedRepo by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.error) {
        state.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            onClearError()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newRepoUrl,
                onValueChange = { newRepoUrl = it },
                label = { Text(stringResource(MR.strings.label_comic_downloader_add_repo)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            IconButton(
                onClick = {
                    if (newRepoUrl.isNotBlank()) {
                        onAddRepository(newRepoUrl)
                        newRepoUrl = ""
                    }
                },
            ) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = stringResource(MR.strings.label_comic_downloader_add_repo),
                )
            }
        }

        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        if (state.repositories.isNotEmpty()) {
            Text(
                text = stringResource(MR.strings.label_comic_downloader_saved_repos),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.repositories) { repoUrl ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = repoUrl,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(
                            onClick = {
                                onFetchCBLs(repoUrl)
                                selectedRepo = repoUrl
                            },
                        ) {
                            Text(stringResource(MR.strings.label_comic_downloader_select))
                        }
                        IconButton(onClick = { onRemoveRepository(repoUrl) }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(MR.strings.label_comic_downloader_remove),
                            )
                        }
                    }

                    if (selectedRepo == repoUrl) {
                        val files = state.repoFiles[repoUrl] ?: emptyMap()
                        files.forEach { (fileName, _) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = fileName,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                IconButton(onClick = { onImportCBL(repoUrl, fileName) }) {
                                    Icon(
                                        Icons.Outlined.FileDownload,
                                        contentDescription = stringResource(MR.strings.label_comic_downloader_select_cbl),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
