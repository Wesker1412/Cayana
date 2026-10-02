package com.cayana.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cayana.calendar.CalendarTarget
import com.cayana.core.permission.SourceStatus
import com.cayana.source.SourceType

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onComplete: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
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

    LaunchedEffect(uiState.isOnboardingCompleted) {
        if (uiState.isOnboardingCompleted) {
            onComplete()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when (uiState.currentStep) {
            OnboardingStep.WELCOME -> WelcomeStep(onStart = { viewModel.nextStep() })
            OnboardingStep.SOURCES -> SourcesStep(
                uiState = uiState,
                onToggleSource = { type, enabled -> viewModel.toggleSource(type, enabled) },
                onPermissionResult = { type, granted -> viewModel.onPermissionResult(type, granted) },
                onDownloadsUriSelected = { uri -> viewModel.onDownloadsUriSelected(uri) },
                onNext = { viewModel.nextStep() }
            )
            OnboardingStep.CALENDAR -> CalendarStep(
                uiState = uiState,
                onCalendarPermissionResult = { granted ->
                    if (granted) {
                        viewModel.checkCalendarPermission()
                    }
                },
                onSelectCalendar = { target -> viewModel.selectCalendar(target) },
                onSkip = { viewModel.skipCalendar() },
                onNext = { viewModel.nextStep() }
            )
            OnboardingStep.BACKUP -> BackupStep(
                onSkip = { viewModel.nextStep() }
            )
            OnboardingStep.FINISH -> FinishStep(
                onFinish = { viewModel.completeOnboarding() }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Step 1: Welcome
// ---------------------------------------------------------------------------
@Composable
private fun WelcomeStep(onStart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Cayana",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 38.sp
                ),
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "把你看過的東西，\n變成找得回來的記憶。",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Normal,
                    lineHeight = 34.sp
                ),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("開始", style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ---------------------------------------------------------------------------
// Step 2: Memory Sources
// ---------------------------------------------------------------------------
@Composable
private fun SourcesStep(
    uiState: OnboardingUiState,
    onToggleSource: (SourceType, Boolean) -> Unit,
    onPermissionResult: (SourceType, Boolean) -> Unit,
    onDownloadsUriSelected: (String?) -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current

    // Media permissions launcher
    val mediaPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val hasFull = results[Manifest.permission.READ_MEDIA_IMAGES] == true ||
            results[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        val hasSelected = results[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true
        val granted = hasFull || hasSelected
        onPermissionResult(SourceType.SCREENSHOT, granted)
        onPermissionResult(SourceType.PHOTO, granted)
    }

    // Audio permission launcher
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onPermissionResult(SourceType.RECORDING, granted)
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
            onDownloadsUriSelected(uri.toString())
        } else {
            onDownloadsUriSelected(null)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "讓 Cayana 記住什麼？",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "你可以隨時開啟或關閉來源。只有在啟用時才會請求相關權限。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            uiState.sources.forEach { item ->
                SourceCard(
                    item = item,
                    onCheckedChange = { checked ->
                        onToggleSource(item.type, checked)
                        if (checked && item.status != SourceStatus.ENABLED_AND_AUTHORIZED) {
                            launchPermissionForSource(item.type)
                        }
                    },
                    onRequestPermission = {
                        launchPermissionForSource(item.type)
                    },
                    onRequestSettings = {
                        openAppSettings(context)
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("下一步", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SourceCard(
    item: SourceItemUiState,
    onCheckedChange: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onRequestSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = getSourceIcon(item.type),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
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

            // Display permission status and action button when enabled
            if (item.isEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
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
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor
                    )

                    when (item.status) {
                        SourceStatus.ENABLED_PERMISSION_REQUIRED -> {
                            Button(
                                onClick = onRequestPermission,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("授權", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        SourceStatus.LIMITED_ACCESS -> {
                            OutlinedButton(
                                onClick = onRequestPermission,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("升級授權", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        SourceStatus.PERMISSION_DENIED -> {
                            TextButton(onClick = onRequestSettings) {
                                Text("前往設定", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Step 3: Calendar Target Selection
// ---------------------------------------------------------------------------
@Composable
private fun CalendarStep(
    uiState: OnboardingUiState,
    onCalendarPermissionResult: (Boolean) -> Unit,
    onSelectCalendar: (CalendarTarget) -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit
) {
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        onCalendarPermissionResult(granted)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "選擇行事曆",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "當從截圖發現活動行程時，Cayana 可以加入你選定的行事曆。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (!uiState.hasCalendarPermission) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.CalendarToday,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "需要行事曆讀取權限以列出可用的行事曆",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                            }
                        ) {
                            Text("授權存取行事曆")
                        }
                    }
                }
            } else if (uiState.isLoadingCalendars) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (uiState.calendars.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "裝置上目前沒有可寫入的行事曆",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                uiState.calendars.forEach { calendar ->
                    val isSelected = calendar.id == uiState.selectedCalendarId
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectCalendar(calendar) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onSelectCalendar(calendar) }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = calendar.displayName,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                )
                                if (calendar.isPrimary) {
                                    Text(
                                        text = "主要行事曆",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            if (uiState.selectedCalendarId != null) {
                Button(
                    onClick = onNext,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("下一步", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("稍後設定", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Step 4: Backup Screen
// ---------------------------------------------------------------------------
@Composable
private fun BackupStep(onSkip: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "備份你的 Memory",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "你的記憶完全保存在本機。未來可啟用端對端加密雲端備份以防換機遺失。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudQueue,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Google Drive",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Text(
                            text = "即將推出 (Stage 6)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = "尚未設定",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        Button(
            onClick = onSkip,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("稍後設定", style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ---------------------------------------------------------------------------
// Step 5: Finish Screen
// ---------------------------------------------------------------------------
@Composable
private fun FinishStep(onFinish: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "完成",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 38.sp
                ),
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "現在可以關掉 Cayana 了。",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Normal,
                    lineHeight = 34.sp
                ),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Button(
            onClick = onFinish,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("完成", style = MaterialTheme.typography.titleMedium)
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
