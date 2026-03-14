package eu.kanade.tachiyomi.ui.comicdownloader

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
                onImportCBL = screenModel::importAndDownloadCbl,
                onImportLocalCBL = screenModel::importCblFromLocalFile,
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
    onImportLocalCBL: (Uri) -> Unit,
    onClearError: () -> Unit,
) {
    var newRepoUrl by remember { mutableStateOf("") }

    // File picker for local CBL files
    val filePicker = rememberLauncherForActivityResult(
        object : ActivityResultContracts.GetContent() {
            override fun createIntent(context: android.content.Context, input: String): Intent {
                return super.createIntent(context, input).apply {
                    type = "*/*"
                }
            }
        },
    ) { uri ->
        if (uri != null) {
            onImportLocalCBL(uri)
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            onClearError()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
            start = 16.dp,
            end = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── Local file import ──────────────────────────────────────────────
        item {
            Button(
                onClick = { filePicker.launch("*/*") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(stringResource(MR.strings.label_comic_downloader_pick_file))
            }
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }

        // ── GitHub repository import ───────────────────────────────────────
        item {
            Text(
                text = stringResource(MR.strings.label_comic_downloader_or_repo),
                style = MaterialTheme.typography.titleSmall,
            )
        }

        item {
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
                    isError = newRepoUrl.isNotBlank() && !newRepoUrl.startsWith("https://github.com"),
                )
                IconButton(
                    onClick = {
                        if (newRepoUrl.startsWith("https://github.com")) {
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
        }

        if (state.isLoading) {
            item {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }

        // ── Saved Repositories ─────────────────────────────────────────────
        if (state.repositories.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(MR.strings.label_comic_downloader_saved_repos),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            items(state.repositories) { repoUrl ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = repoUrl,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                    TextButton(onClick = { onFetchCBLs(repoUrl) }) {
                        Text(stringResource(MR.strings.label_comic_downloader_select))
                    }
                    IconButton(onClick = { onRemoveRepository(repoUrl) }) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(MR.strings.label_comic_downloader_remove),
                        )
                    }
                }
            }
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

        // ── CBL Files Logic ───────────────────────────────────────────────
        val allCbls = state.repoFiles.flatMap { (repoUrl, files) ->
            files.keys.map { fileName ->
                val isImported = state.importedCbls.contains("$repoUrl|$fileName")
                Triple(repoUrl, fileName, isImported)
            }
        }.sortedBy { it.second } // Sort by name

        val downloaded = allCbls.filter { it.third }
        val available = allCbls.filter { !it.third }

        // ── Downloaded Section ────────────────────────────────────────────
        if (downloaded.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(MR.strings.label_comic_downloader_downloaded),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            items(downloaded) { (repoUrl, fileName, _) ->
                CblItem(fileName, repoUrl, true) { /* Already imported */ }
            }
        }

        // ── Available Section ─────────────────────────────────────────────
        if (available.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(MR.strings.label_comic_downloader_available),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            items(available) { (repoUrl, fileName, _) ->
                CblItem(fileName, repoUrl, false) { onImportCBL(repoUrl, fileName) }
            }
        } else if (allCbls.isEmpty() && !state.isLoading && state.repositories.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(MR.strings.label_comic_downloader_no_cbl_files),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun CblItem(
    fileName: String,
    repoUrl: String,
    isImported: Boolean,
    onImport: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(enabled = !isImported) { onImport() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (repoUrl != "local") {
                Text(
                    text = repoUrl.removePrefix("https://github.com/"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (isImported) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        } else {
            IconButton(onClick = onImport) {
                Icon(
                    Icons.Outlined.FileDownload,
                    contentDescription = stringResource(MR.strings.label_comic_downloader_select_cbl),
                )
            }
        }
    }
}
