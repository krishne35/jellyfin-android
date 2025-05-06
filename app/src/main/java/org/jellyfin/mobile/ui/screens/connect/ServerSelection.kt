package org.jellyfin.mobile.ui.screens.connect

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.ListItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Done
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.setup.ConnectionHelper
import org.jellyfin.mobile.ui.state.CheckUrlState
import org.jellyfin.mobile.ui.state.ServerSelectionMode
import org.jellyfin.mobile.ui.utils.CenterRow
import org.koin.compose.koinInject

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("LongMethod")
@Composable
fun ServerSelection(
    showExternalConnectionError: Boolean,
    apiClientController: ApiClientController = koinInject(),
    connectionHelper: ConnectionHelper = koinInject(),
    onConnected: suspend (String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    var serverSelectionMode by remember { mutableStateOf(ServerSelectionMode.ADDRESS) }
    var hostname by remember { mutableStateOf("") }
    val serverSuggestions = remember { mutableStateListOf<ServerSuggestion>() }
    var checkUrlState by remember<MutableState<CheckUrlState>> { mutableStateOf(CheckUrlState.Unchecked) }
    var externalError by remember { mutableStateOf(showExternalConnectionError) }
    var downloadURI by remember { mutableStateOf("") }
    val downloadItems = remember { mutableStateListOf<DownloadItem>() }

    // Prefill currently selected server if available
    LaunchedEffect(Unit) {
        val server = apiClientController.loadSavedServer()
        if (server != null) {
            hostname = server.hostname
        }
    }

    LaunchedEffect(Unit) {
        // Suggest saved servers
        apiClientController.loadPreviouslyUsedServers().mapTo(serverSuggestions) { server ->
            ServerSuggestion(
                type = ServerSuggestion.Type.SAVED,
                name = server.hostname,
                address = server.hostname,
                timestamp = server.lastUsedTimestamp,
            )
        }

        // Prepend discovered servers to suggestions
        connectionHelper.discoverServersAsFlow().collect { serverInfo ->
            serverSuggestions.removeIf { existing -> existing.address == serverInfo.address }
            serverSuggestions.add(
                index = 0,
                ServerSuggestion(
                    type = ServerSuggestion.Type.DISCOVERED,
                    name = serverInfo.name,
                    address = serverInfo.address,
                    timestamp = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun onSubmit() {
        externalError = false
        checkUrlState = CheckUrlState.Pending
        coroutineScope.launch {
            val state = connectionHelper.checkServerUrl(hostname)
            checkUrlState = state
            if (state is CheckUrlState.Success) {
                onConnected(state.address)
            }
        }
    }

    Column {
        Crossfade(
            targetState = serverSelectionMode,
            label = "Server selection mode",
        ) { selectionType ->
            when (selectionType) {
                ServerSelectionMode.ADDRESS -> {
                    Row {
                        AddressSelection(
                            text = hostname,
                            errorText = when {
                                externalError -> stringResource(R.string.connection_error_cannot_connect)
                                else -> (checkUrlState as? CheckUrlState.Error)?.message
                            },
                            loading = checkUrlState is CheckUrlState.Pending,
                            onTextChange = { value ->
                                externalError = false
                                checkUrlState = CheckUrlState.Unchecked
                                hostname = value
                            },
                            onDiscoveryClick = {
                                externalError = false
                                keyboardController?.hide()
                                serverSelectionMode = ServerSelectionMode.AUTO_DISCOVERY
                            },
                            onSubmit = {
                                onSubmit()
                            },
                            onDownloadButtonClick = {
                                serverSelectionMode = ServerSelectionMode.DOWNLOADS
                            },
                        )
                    }
                }
                ServerSelectionMode.AUTO_DISCOVERY -> {
                    Column {
                        ServerDiscoveryList(
                            serverSuggestions = serverSuggestions,
                            onGoBack = {
                                serverSelectionMode = ServerSelectionMode.ADDRESS
                            },
                            onSelectServer = { url ->
                                hostname = url
                                serverSelectionMode = ServerSelectionMode.ADDRESS
                                onSubmit()
                            },
                        )
                    }
                }
                ServerSelectionMode.DOWNLOADS -> DownloadsList(
                    downloadItems = downloadItems,
                    onGoBack = {
                        serverSelectionMode = ServerSelectionMode.ADDRESS
                    },
                    onPlayDownload = { uri ->
                        downloadURI = uri
                        onSubmit()
                    },
                )
            }
        }
    }
}

@Stable
@Composable
private fun AddressSelection(
    text: String,
    errorText: String?,
    loading: Boolean,
    onTextChange: (String) -> Unit,
    onDiscoveryClick: () -> Unit,
    onSubmit: () -> Unit,
    onDownloadButtonClick: () -> Unit,
) {
    Column {
        ServerUrlField(
            text = text,
            errorText = errorText,
            onTextChange = onTextChange,
            onSubmit = onSubmit,
        )
        AnimatedErrorText(errorText = errorText)
        if (!loading) {
            Spacer(modifier = Modifier.height(12.dp))
            StyledTextButton(
                text = stringResource(R.string.choose_server_button_text),
                onClick = onDiscoveryClick,
            )
            StyledTextButton(
                text = stringResource(R.string.pref_category_downloads),
                onClick = onDownloadButtonClick,
            )
        } else {
            CenterRow {
                CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            }
        }
    }
}

@Stable
@Composable
private fun ServerUrlField(
    text: String,
    errorText: String?,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    CenterRow {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .padding(bottom = 8.dp)
                .fillMaxWidth(0.8f)
                .onKeyEvent { keyEvent ->
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_ENTER -> {
                            onSubmit()
                            true
                        }
                        else -> false
                    }
                },
            label = {
                Text(text = stringResource(R.string.host_input_hint))
            },
            shape = MaterialTheme.shapes.large,
            isError = errorText != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(
                onGo = {
                    onSubmit()
                },
            ),
            singleLine = true,
        )
        Button(
            enabled = text.isNotBlank(),
            onClick = { onSubmit() },
            modifier = Modifier
                .padding(start = 16.dp)
                .height(56.dp)
            .width(56.dp),
            colors = ButtonDefaults.buttonColors(),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(
                imageVector = Icons.Filled.Done, contentDescription = null, tint = MaterialTheme.colors.onPrimary
            )
        }
    }
}

@Stable
@Composable
private fun AnimatedErrorText(
    errorText: String?,
) {
    AnimatedVisibility(
        visible = errorText != null,
        exit = ExitTransition.None,
    ) {
        Text(
            text = errorText.orEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            color = MaterialTheme.colors.error,
            style = MaterialTheme.typography.caption,
        )
    }
}

@Stable
@Composable
private fun StyledTextButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(56.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(),
        shape = MaterialTheme.shapes.large,
    ) {
        Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Stable
@Composable
private fun ServerDiscoveryList(
    serverSuggestions: SnapshotStateList<ServerSuggestion>,
    onGoBack: () -> Unit,
    onSelectServer: (String) -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onGoBack) {
                Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
            }
            Text(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                text = stringResource(R.string.available_servers_title),
                style = MaterialTheme.typography.h6,
            )
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .size(24.dp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier
                .padding(bottom = 16.dp)
                .fillMaxSize()
                .background(
                    color = MaterialTheme.colors.surface,
                    shape = MaterialTheme.shapes.medium,
                ),
        ) {
            items(serverSuggestions) { server ->
                ServerDiscoveryItem(
                    serverSuggestion = server,
                    onClickServer = {
                        onSelectServer(server.address)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Stable
@Composable
private fun ServerDiscoveryItem(
    serverSuggestion: ServerSuggestion,
    onClickServer: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClickServer),
        text = {
            Text(text = serverSuggestion.name)
        },
        secondaryText = {
            Text(text = serverSuggestion.address)
        },
    )
}

@Stable
@Composable
private fun DownloadsList(
    downloadItems: SnapshotStateList<DownloadItem>,
    onGoBack: () -> Unit,
    onPlayDownload: (String) -> Unit,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onGoBack) {
                Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
            }
            Text(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 16.dp),
                text = stringResource(R.string.available_downloads),
                style = MaterialTheme.typography.h6,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier
                .padding(bottom = 16.dp)
                .fillMaxSize()
                .background(
                    color = MaterialTheme.colors.surface,
                    shape = MaterialTheme.shapes.medium,
                ),
        ) {
            items(downloadItems) { download ->
                DownloadItem(
                    downloadItem = download,
                    onClickItem = {
                        onPlayDownload(download.uri)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Stable
@Composable
private fun DownloadItem(
    downloadItem: DownloadItem,
    onClickItem: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClickItem),
        text = {
            Text(text = downloadItem.name)
        },
    )
}
