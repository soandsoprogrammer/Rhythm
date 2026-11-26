/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.presentation.screens

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import chromahub.rhythm.app.R
import chromahub.rhythm.app.features.streaming.data.provider.WebDavMusicClient
import chromahub.rhythm.app.features.streaming.data.provider.isShenyeLocalAddress
import chromahub.rhythm.app.features.streaming.domain.model.StreamingServiceId
import chromahub.rhythm.app.features.streaming.presentation.viewmodel.StreamingMusicViewModel
import chromahub.rhythm.app.shared.presentation.components.common.CollapsibleHeaderScreen

@Composable
fun StreamingServiceSetupScreen(
    serviceId: String,
    viewModel: StreamingMusicViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    CollapsibleHeaderScreen(
        title = stringResource(R.string.shenye_server),
        showBackButton = true,
        onBackClick = onBackClick,
        headerDisplayMode = 1
    ) { contentModifier ->
        MusicServerForm(
            viewModel = viewModel,
            modifier = modifier.then(contentModifier).fillMaxSize()
                .imePadding().verticalScroll(rememberScrollState()).padding(20.dp)
        )
    }
}

@Composable
internal fun MusicServerForm(
    viewModel: StreamingMusicViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val serverId = StreamingServiceId.SUBSONIC
    val sessions by viewModel.serviceSessions.collectAsState()
    val busy by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val session = sessions[serverId] ?: viewModel.getServiceSession(serverId)
    var useWebDav by rememberSaveable(session.serverUrl) {
        mutableStateOf(viewModel.isWebDavConnected() || WebDavMusicClient.isNutstoreAddress(session.serverUrl))
    }
    var address by rememberSaveable(session.serverUrl) { mutableStateOf(session.serverUrl) }
    var username by rememberSaveable(session.username) { mutableStateOf(session.username.ifBlank { "music" }) }
    var token by remember { mutableStateOf("") }
    var networkPermissionDenied by remember { mutableStateOf(false) }
    val connect = { viewModel.connectService(serverId, address.trim(), username.trim(), token, useWebDav) }
    val permission = "android.permission.ACCESS_LOCAL_NETWORK"
    val requestNetworkPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        networkPermissionDenied = !granted
        if (granted) connect()
    }
    LaunchedEffect(session) {
        if (session.isConnected) token = ""
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.shenye_server),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Text(
                text = stringResource(when {
                    session.isConnected && address == session.serverUrl -> R.string.streaming_status_connected
                    useWebDav -> R.string.shenye_webdav_description
                    else -> R.string.shenye_server_description
                }),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !useWebDav,
                onClick = {
                    if (useWebDav) {
                        useWebDav = false
                        address = ""
                        username = "music"
                        token = ""
                    }
                },
                enabled = !busy,
                label = { Text(stringResource(R.string.shenye_music_server_source)) }
            )
            FilterChip(
                selected = useWebDav,
                onClick = {
                    if (!useWebDav) {
                        useWebDav = true
                        address = WebDavMusicClient.NUTSTORE_URL
                        username = ""
                        token = ""
                    }
                },
                enabled = !busy,
                label = { Text(stringResource(R.string.shenye_webdav_source)) }
            )
        }
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text(stringResource(R.string.streaming_service_setup_server_url)) },
            placeholder = {
                Text(if (useWebDav) WebDavMusicClient.NUTSTORE_URL else stringResource(R.string.shenye_server_address_hint))
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !busy
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(R.string.streaming_service_setup_username)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !busy
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(stringResource(if (useWebDav) R.string.shenye_webdav_password else R.string.shenye_server_token)) },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = !busy
        )
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        val connectionMessage = if (networkPermissionDenied) stringResource(R.string.shenye_local_network_required) else error
        connectionMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(4.dp),
            enabled = !busy && address.isNotBlank() && username.isNotBlank() && token.isNotBlank(),
            onClick = {
                networkPermissionDenied = false
                if (Build.VERSION.SDK_INT >= 37 && isShenyeLocalAddress(address) &&
                    ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestNetworkPermission.launch(permission)
                } else {
                    connect()
                }
            }
        ) {
            Text(stringResource(if (session.isConnected) R.string.streaming_service_setup_reconnect else R.string.streaming_connect))
        }
        if (session.isConnected) {
            TextButton(
                enabled = !busy,
                onClick = { viewModel.disconnectService(serverId) }
            ) {
                Text(stringResource(R.string.streaming_service_setup_disconnect))
            }
        }
    }
}
