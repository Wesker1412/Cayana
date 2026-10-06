package com.cayana.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.result.IntentSenderRequest
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import com.cayana.backup.drive.DriveAuthStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cayana.core.permission.SourceStatus
import com.cayana.source.SourceType
import com.cayana.ui.onboarding.SourceItemUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showCalendarDialog by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val driveAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onDriveAuthorizationResult(
            result.resultCode == Activity.RESULT_OK,
            result.data
        )
    }

    var selectedBackupFileId by remember { mutableStateOf<String?>(null) }
    var restoreRecoveryKeyInput by remember { mutableStateOf("") }
    var showReplaceConfirmationDialog by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Media permissions launcher
    val mediaPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val hasFull = results[Manifest.permission.READ_MEDIA_IMAGES] == true ||
            results[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        val hasSelected = results[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true
        val granted = hasFull || hasSelected
        viewModel.onPermissionResult(SourceType.SCREENSHOT, granted)
        viewModel.onPermissionResult(SourceType.PHOTO, granted)
    }

    // Audio permission launcher
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onPermissionResult(SourceType.RECORDING, granted)
    }

    // Downloads SAF directory picker launcher
    val openDocumentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {}
            viewModel.onDownloadsUriSelected(uri.toString())
        } else {
            viewModel.onDownloadsUriSelected(null)
        }
    }

    val launchPermissionForSource: (SourceType) -> Unit = { type ->
        when (type) {
            SourceType.SCREENSHOT, SourceType.PHOTO -> {
                val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                    )
                } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                } else {
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
                mediaPermissionsLauncher.launch(perms)
            }
            SourceType.RECORDING -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    audioPermissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
                } else {
                    audioPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            SourceType.DOWNLOAD -> {
                openDocumentTreeLauncher.launch(null)
            }
            else -> {}
        }
    }

    // Calendar permission launcher
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.checkCalendarPermission()
            showCalendarDialog = true
        }
    }

    // Notification permission launcher
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.refreshPermissions()
    }

    // Write calendar permission launcher for auto-calendar toggle
    val writeCalendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.toggleAutoCalendar(true)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshPermissions()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Home"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // ---------------------------------------------------------------
            // Section 1: Memory Sources
            // ---------------------------------------------------------------
            Text(
                text = "Memory Sources",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    uiState.sourceItems.forEachIndexed { index, item ->
                        if (index > 0) Spacer(modifier = Modifier.height(16.dp))
                        SourceItemRow(
                            item = item,
                            isSttModelInstalled = uiState.isSttModelInstalled,
                            onDownloadModelClick = { viewModel.showSttModelDialog() },
                            onCheckedChange = { checked ->
                                viewModel.toggleSource(item.type, checked)
                                if (checked && item.status != SourceStatus.ENABLED_AND_AUTHORIZED) {
                                    launchPermissionForSource(item.type)
                                }
                            },
                            onRequestPermission = {
                                launchPermissionForSource(item.type)
                            },
                            onRequestSettings = { openAppSettings(context) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ---------------------------------------------------------------
            // Section 2: Notifications
            // ---------------------------------------------------------------
            Text(
                text = "Notifications",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "記憶通知",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Cayana 可以在記住內容後通知你。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val hasNotificationPerm = com.cayana.core.notification.NotificationHelper.hasNotificationPermission(context)
                        val statusText = if (hasNotificationPerm) "✓ 已開啟" else "尚未開啟（選填）"
                        val statusColor = if (hasNotificationPerm) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColor
                        )

                        if (!hasNotificationPerm) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                Button(
                                    onClick = {
                                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("開啟通知", style = MaterialTheme.typography.labelMedium)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { openAppSettings(context) },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("系統設定", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ---------------------------------------------------------------
            // Section 3: Calendar Target
            // ---------------------------------------------------------------
            Text(
                text = "Calendar",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "自動加入行事曆",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "截圖偵測到行程時自動加入行事曆",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.settings.autoCalendarEnabled,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    writeCalendarPermissionLauncher.launch(Manifest.permission.WRITE_CALENDAR)
                                } else {
                                    viewModel.toggleAutoCalendar(false)
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CalendarToday,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Calendar Target",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = uiState.settings.selectedCalendarName ?: "尚未設定",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = {
                                if (uiState.hasCalendarPermission) {
                                    viewModel.loadCalendars()
                                    showCalendarDialog = true
                                } else {
                                    calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                                }
                            }
                        ) {
                            Text("變更")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ---------------------------------------------------------------
            // Section 4: Backup
            // ---------------------------------------------------------------
            Text(
                text = "Backup",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Google Drive 備份",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Status Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val statusIcon = when (uiState.settings.driveAuthStatus) {
                            DriveAuthStatus.CONNECTED -> Icons.Default.CloudDone
                            DriveAuthStatus.AUTH_REQUIRED -> Icons.Default.Warning
                            DriveAuthStatus.DISCONNECTED -> Icons.Default.CloudQueue
                        }
                        val statusTint = when (uiState.settings.driveAuthStatus) {
                            DriveAuthStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                            DriveAuthStatus.AUTH_REQUIRED -> MaterialTheme.colorScheme.error
                            DriveAuthStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = null,
                            tint = statusTint,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            val statusText = when (uiState.settings.driveAuthStatus) {
                                DriveAuthStatus.CONNECTED -> "已連接"
                                DriveAuthStatus.AUTH_REQUIRED -> "需要重新授權"
                                DriveAuthStatus.DISCONNECTED -> "未連接"
                            }
                            Text(
                                text = "狀態：$statusText",
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (uiState.settings.driveAuthStatus == DriveAuthStatus.CONNECTED) {
                                val lastTime = uiState.settings.lastBackupTimestamp
                                val lastTimeStr = if (lastTime > 0L) {
                                    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(lastTime))
                                } else {
                                    "尚未備份"
                                }
                                Text(
                                    text = "上次備份：$lastTimeStr",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Recovery Key Status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.VpnKey,
                            contentDescription = null,
                            tint = if (uiState.settings.hasRecoveryKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (uiState.settings.hasRecoveryKey) "復原金鑰：已設定" else "復原金鑰：未設定",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (uiState.backupState is BackupUiState.BackingUp || uiState.backupState is BackupUiState.Restoring) {
                        Spacer(modifier = Modifier.height(16.dp))
                        val progressLabel = if (uiState.backupState is BackupUiState.BackingUp) "正在建立端對端加密備份並上傳..." else "正在自 Google Drive 下載並驗證還原..."
                        Text(progressLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    if (uiState.settings.driveAuthStatus != DriveAuthStatus.CONNECTED) {
                        Button(
                            onClick = {
                                viewModel.startConnectDrive { sender ->
                                    driveAuthLauncher.launch(IntentSenderRequest.Builder(sender).build())
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("連接 Google Drive")
                        }
                    } else {
                        // Connected actions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { viewModel.performManualBackup() },
                                modifier = Modifier.weight(1f),
                                enabled = uiState.backupState !is BackupUiState.BackingUp && uiState.backupState !is BackupUiState.Restoring
                            ) {
                                Text("立即備份")
                            }
                            OutlinedButton(
                                onClick = { viewModel.openRestoreDialog() },
                                modifier = Modifier.weight(1f),
                                enabled = uiState.backupState !is BackupUiState.BackingUp && uiState.backupState !is BackupUiState.Restoring
                            ) {
                                Text("從備份還原")
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Auto-backup toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "自動備份 (每 24 小時)",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "連上網路且電量充足時自動在背景加密備份",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = uiState.settings.autoBackupEnabled,
                                enabled = uiState.settings.hasRecoveryKey,
                                onCheckedChange = { checked ->
                                    viewModel.toggleAutoBackup(checked, context)
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        TextButton(
                            onClick = { viewModel.disconnectDrive(context) },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("中斷連接", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ---------------------------------------------------------------
            // Section 5: Privacy & Safety Principles
            // ---------------------------------------------------------------
            Text(
                text = "Privacy & Safety Principles",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "• Local-first architecture", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "• Private OCR / Transcript never recorded to logs", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "• Crash reports and analytics contain no raw memory", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "• Original files preserved; deleting original retains memory", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ---------------------------------------------------------------
            // Section 6: About
            // ---------------------------------------------------------------
            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Cayana — Personal Memory Layer", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Version ${uiState.appVersion}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Calendar Selection Dialog
    if (showCalendarDialog) {
        AlertDialog(
            onDismissRequest = { showCalendarDialog = false },
            title = { Text("選擇行事曆 Target") },
            text = {
                if (uiState.isLoadingCalendars) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (uiState.availableCalendars.isEmpty()) {
                    Text("裝置上目前無可寫入的行事曆。")
                } else {
                    Column {
                        uiState.availableCalendars.forEach { cal ->
                            val isSelected = cal.id == uiState.settings.selectedCalendarId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.selectCalendar(cal)
                                        showCalendarDialog = false
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        viewModel.selectCalendar(cal)
                                        showCalendarDialog = false
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(text = cal.displayName, style = MaterialTheme.typography.bodyLarge)
                                    if (cal.isPrimary) {
                                        Text(text = "主要", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCalendarDialog = false }) {
                    Text("關閉")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.clearCalendar()
                    showCalendarDialog = false
                }) {
                    Text("清除選擇")
                }
            }
        )
    }

    // STT Model Download Dialog
    if (uiState.showSttModelDialog) {
        AlertDialog(
            onDismissRequest = {
                if (uiState.sttModelInstallState !is ModelInstallUiState.Downloading &&
                    uiState.sttModelInstallState !is ModelInstallUiState.Verifying &&
                    uiState.sttModelInstallState !is ModelInstallUiState.Installing
                ) {
                    viewModel.dismissSttModelDialog()
                }
            },
            title = { Text("本機語音辨識模型") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    when (val state = uiState.sttModelInstallState) {
                        is ModelInstallUiState.Idle -> {
                            Text(
                                "約 156 MB 下載，安裝後約 229 MB\n\n錄音只會在此裝置上處理。",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        is ModelInstallUiState.Downloading -> {
                            Text("正在下載模型檔案...", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(12.dp))
                            if (state.percentage >= 0) {
                                LinearProgressIndicator(
                                    progress = { state.percentage / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "${state.percentage}% (${state.bytesDownloaded / 1048576} MB / ${state.totalBytes / 1048576} MB)",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                        is ModelInstallUiState.Verifying -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("驗證模型 SHA-256 完整性...", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        is ModelInstallUiState.Installing -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("正在安裝模型至安全目錄...", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        is ModelInstallUiState.Completed -> {
                            Text("✓ 模型已成功安裝！現在錄音將在離線狀態下自動進行本機語音辨識。", style = MaterialTheme.typography.bodyMedium)
                        }
                        is ModelInstallUiState.Failed -> {
                            Text(
                                "安裝失敗：${state.message}\n\n已清除暫存檔案。錄音已安全保留為等待模型狀態。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            },
            confirmButton = {
                when (uiState.sttModelInstallState) {
                    is ModelInstallUiState.Idle -> {
                        Button(onClick = { viewModel.startModelDownload() }) {
                            Text("下載模型")
                        }
                    }
                    is ModelInstallUiState.Completed -> {
                        Button(onClick = { viewModel.dismissSttModelDialog() }) {
                            Text("完成")
                        }
                    }
                    is ModelInstallUiState.Failed -> {
                        Button(onClick = { viewModel.startModelDownload() }) {
                            Text("重試")
                        }
                    }
                    else -> {}
                }
            },
            dismissButton = {
                if (uiState.sttModelInstallState is ModelInstallUiState.Idle ||
                    uiState.sttModelInstallState is ModelInstallUiState.Failed
                ) {
                    TextButton(onClick = { viewModel.dismissSttModelDialog() }) {
                        Text("取消")
                    }
                }
            }
        )
    }

    // ---------------------------------------------------------------
    // Recovery Key Dialog
    // ---------------------------------------------------------------
    if (uiState.showRecoveryKeyDialog && uiState.generatedRecoveryKey != null) {
        val keyText = uiState.generatedRecoveryKey ?: ""
        var confirmationInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { viewModel.dismissRecoveryKeyDialog() },
            title = { Text("Cayana 備份復原金鑰") },
            text = {
                Column {
                    Text(
                        text = "這是解密您 Google Drive 雲端備份的唯一金鑰。沒有這把金鑰，Cayana 與 Google 都無法解密您的備份。\n\n請務必妥善保存或抄寫此金鑰：",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = keyText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Cayana Recovery Key", keyText)
                            clipboard.setPrimaryClip(clip)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("複製金鑰")
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "請輸入此金鑰的最後兩組代碼以確認您已確實記錄：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = confirmationInput,
                        onValueChange = { confirmationInput = it },
                        label = { Text("輸入末兩組代碼 (例如末 10 碼)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = uiState.recoveryKeyConfirmationError != null
                    )
                    if (uiState.recoveryKeyConfirmationError != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = uiState.recoveryKeyConfirmationError ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmRecoveryKey(confirmationInput) },
                    enabled = confirmationInput.isNotBlank()
                ) {
                    Text("確認並啟用金鑰")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissRecoveryKeyDialog() }) {
                    Text("取消")
                }
            }
        )
    }

    // ---------------------------------------------------------------
    // Restore Dialog
    // ---------------------------------------------------------------
    if (uiState.showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRestoreDialog() },
            title = { Text("從 Google Drive 備份還原") },
            text = {
                Column {
                    if (uiState.isFetchingBackups) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                        Text(
                            "正在搜尋 Google Drive 備份...",
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = 8.dp)
                        )
                    } else if (uiState.availableBackups.isEmpty()) {
                        Text("在 Google Drive appDataFolder 中找不到 Cayana 備份檔案。")
                    } else {
                        Text("選擇欲還原的備份快照：", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        uiState.availableBackups.forEach { backup ->
                            val isSelected = selectedBackupFileId == backup.fileId || (selectedBackupFileId == null && backup == uiState.availableBackups.first())
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedBackupFileId = backup.fileId }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedBackupFileId = backup.fileId }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(backup.fileName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                    val dateStr = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(backup.createdTimeMillis))
                                    Text("$dateStr (${backup.sizeBytes / 1024} KB)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = restoreRecoveryKeyInput,
                            onValueChange = { restoreRecoveryKeyInput = it },
                            label = { Text("輸入復原金鑰 (XXXXX-XXXXX-...)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                if (!uiState.isFetchingBackups && uiState.availableBackups.isNotEmpty()) {
                    Button(
                        onClick = {
                            showReplaceConfirmationDialog = true
                        },
                        enabled = restoreRecoveryKeyInput.isNotBlank()
                    ) {
                        Text("下一步")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissRestoreDialog() }) {
                    Text("取消")
                }
            }
        )
    }

    // ---------------------------------------------------------------
    // Replace Confirmation Dialog
    // ---------------------------------------------------------------
    if (showReplaceConfirmationDialog) {
        val targetFileId = selectedBackupFileId ?: uiState.availableBackups.firstOrNull()?.fileId ?: ""
        AlertDialog(
            onDismissRequest = { showReplaceConfirmationDialog = false },
            title = { Text("確認還原記憶資料？") },
            text = {
                Text(
                    "這會取代目前 Cayana 中的記憶資料。原始照片、錄音與 Android 行事曆不會被刪除或修改。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showReplaceConfirmationDialog = false
                        viewModel.performRestore(targetFileId, restoreRecoveryKeyInput)
                    }
                ) {
                    Text("確認取代並還原")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReplaceConfirmationDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // ---------------------------------------------------------------
    // Backup Status Message Dialog
    // ---------------------------------------------------------------
    if (uiState.backupState is BackupUiState.Success || uiState.backupState is BackupUiState.Error) {
        val isSuccess = uiState.backupState is BackupUiState.Success
        val msg = if (isSuccess) (uiState.backupState as BackupUiState.Success).message else (uiState.backupState as BackupUiState.Error).message
        AlertDialog(
            onDismissRequest = { viewModel.clearBackupState() },
            title = { Text(if (isSuccess) "操作完成" else "操作失敗") },
            text = { Text(msg) },
            confirmButton = {
                Button(onClick = { viewModel.clearBackupState() }) {
                    Text("確定")
                }
            }
        )
    }
}

@Composable
private fun SourceItemRow(
    item: SourceItemUiState,
    isSttModelInstalled: Boolean = false,
    onDownloadModelClick: () -> Unit = {},
    onCheckedChange: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onRequestSettings: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = getSourceIcon(item.type),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = item.isEnabled,
                onCheckedChange = onCheckedChange
            )
        }

        if (item.isEnabled) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val statusText = when (item.status) {
                    SourceStatus.ENABLED_AND_AUTHORIZED -> "✓ 已授權"
                    SourceStatus.LIMITED_ACCESS -> "⚠ 部分存取"
                    SourceStatus.ENABLED_PERMISSION_REQUIRED -> "⚠ 需要系統權限"
                    SourceStatus.PERMISSION_DENIED -> "✕ 權限已被拒絕"
                    SourceStatus.DISABLED -> ""
                }
                val statusColor = when (item.status) {
                    SourceStatus.ENABLED_AND_AUTHORIZED -> MaterialTheme.colorScheme.primary
                    SourceStatus.LIMITED_ACCESS -> MaterialTheme.colorScheme.tertiary
                    SourceStatus.ENABLED_PERMISSION_REQUIRED -> MaterialTheme.colorScheme.tertiary
                    SourceStatus.PERMISSION_DENIED -> MaterialTheme.colorScheme.error
                    SourceStatus.DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor
                )

                when (item.status) {
                    SourceStatus.ENABLED_PERMISSION_REQUIRED -> {
                        Button(
                            onClick = onRequestPermission,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("授權", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    SourceStatus.LIMITED_ACCESS -> {
                        OutlinedButton(
                            onClick = onRequestPermission,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("升級授權", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    SourceStatus.PERMISSION_DENIED -> {
                        TextButton(onClick = onRequestSettings) {
                            Text("前往設定", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    else -> {}
                }
            }

            if (item.type == SourceType.RECORDING) {
                Spacer(modifier = Modifier.height(8.dp))
                if (isSttModelInstalled) {
                    Text(
                        text = "✓ 本機語音辨識模型已就緒 (SenseVoice INT8)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⚠ 尚未下載語音模型 (156 MB)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        OutlinedButton(
                            onClick = onDownloadModelClick,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("下載模型", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

private fun getSourceIcon(type: SourceType): ImageVector {
    return when (type) {
        SourceType.SCREENSHOT -> Icons.Default.Screenshot
        SourceType.PHOTO -> Icons.Default.PhotoCamera
        SourceType.RECORDING -> Icons.Default.Mic
        SourceType.DOWNLOAD -> Icons.Default.Download
        else -> Icons.Default.Screenshot
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(intent)
}
