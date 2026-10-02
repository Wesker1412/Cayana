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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
            // Section 3: Backup
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudQueue,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Google Drive",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "尚未設定 (Stage 6 即將推出)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ---------------------------------------------------------------
            // Section 4: Privacy & Safety Principles
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
            // Section 5: About
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
}

@Composable
private fun SourceItemRow(
    item: SourceItemUiState,
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
