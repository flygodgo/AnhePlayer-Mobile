package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.sync.LxSyncMode
import com.dd3boh.outertune.sync.LxSyncViewModel
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.utils.backToMain

/**
 * 洛雪云同步设置页。
 * 与桌面版 AnhePlayer 的「洛雪同步」设置一一对应：
 * 服务器地址、授权码、同步模式、连接/立即同步/断开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LxSyncSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: LxSyncViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val status by viewModel.status.collectAsState()

    var enabled by remember { mutableStateOf(viewModel.isEnabled()) }
    var serverUrl by remember { mutableStateOf(viewModel.getServerUrl()) }
    var authCode by remember { mutableStateOf(viewModel.getAuthCode()) }
    var mode by remember { mutableStateOf(viewModel.getMode()) }
    var modeExpanded by remember { mutableStateOf(false) }

    fun persist() = viewModel.saveConfig(enabled, serverUrl, authCode, mode)

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxWidth(),
        columnModifier = Modifier.verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "洛雪云同步",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Text(
            text = "通过 lx-server 在设备间同步「我喜欢的音乐」与自建歌单。" +
                "手机上已有洛雪音源，同步后可直接播放。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it; persist() },
            label = { Text("服务器地址") },
            placeholder = { Text("http://192.168.1.10:3000") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = authCode,
            onValueChange = { authCode = it; persist() },
            label = { Text("授权码（连接码）") },
            placeholder = { Text("在 lx-server 管理页查看") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { modeExpanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("同步模式：${mode.label}")
            }
            androidx.compose.material3.DropdownMenu(
                expanded = modeExpanded,
                onDismissRequest = { modeExpanded = false },
                modifier = Modifier.fillMaxWidth(),
            ) {
                LxSyncMode.entries.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m.label) },
                        onClick = { mode = m; modeExpanded = false; persist() },
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { persist(); viewModel.connect() },
                enabled = !status.connecting && serverUrl.isNotBlank(),
            ) {
                Text(if (status.connecting) "连接中..." else "连接")
            }
            Spacer(Modifier.padding(horizontal = 8.dp))
            TextButton(onClick = { viewModel.syncNow() }) { Text("立即同步") }
            TextButton(onClick = { viewModel.disconnect() }) { Text("断开") }
        }

        if (status.connecting) {
            Spacer(Modifier.height(8.dp))
            CircularProgressIndicator()
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = when {
                status.message.isNotBlank() -> "状态：${status.message}"
                status.connected -> "状态：已连接${status.serverName?.let { " · $it" } ?: ""}"
                else -> "状态：未连接"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        status.lastSyncAt?.let {
            Text("上次同步：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(it))}",
                style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        if (status.diag.isNotBlank()) {
            Text("诊断日志（同步过程）：", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(
                text = status.diag,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 为模式增加中文显示名 */
private val LxSyncMode.label: String
    get() = when (this) {
        LxSyncMode.MERGE_LOCAL_REMOTE -> "合并（本地优先）"
        LxSyncMode.MERGE_REMOTE_LOCAL -> "合并（云端优先）"
        LxSyncMode.OVERWRITE_LOCAL_REMOTE -> "用本地覆盖云端"
        LxSyncMode.OVERWRITE_REMOTE_LOCAL -> "用云端覆盖本地"
        LxSyncMode.OVERWRITE_LOCAL_REMOTE_FULL -> "完全覆盖云端"
        LxSyncMode.OVERWRITE_REMOTE_LOCAL_FULL -> "完全覆盖本地"
        LxSyncMode.CANCEL -> "取消"
    }
