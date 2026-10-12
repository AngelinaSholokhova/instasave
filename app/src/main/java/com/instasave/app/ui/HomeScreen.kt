package com.instasave.app.ui

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.instasave.app.update.Release
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.instasave.app.util.ReportSender

@Composable
fun HomeScreen(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(state.snackbar) {
        state.snackbar?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.snackbarShown()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "InstaSave",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { viewModel.openSettings(true) }) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Настройки",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Вставьте ссылку на публичный Reels или видео-пост Instagram",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            state.update?.let { release ->
                UpdateBanner(
                    release = release,
                    onOpen = {
                        val url = release.html_url
                        if (url != null) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }
                        viewModel.dismissUpdate()
                    },
                    onDismiss = viewModel::dismissUpdate,
                )
                Spacer(Modifier.height(14.dp))
            }

            LinkInput(value = state.input, onValueChange = viewModel::onInputChange, onSubmit = viewModel::submit)

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = viewModel::submit,
                enabled = state.input.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Скачать")
            }

            Spacer(Modifier.height(20.dp))

            if (state.tasks.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Загрузки",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = viewModel::clearFinished) { Text("Очистить") }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.tasks, key = { it.id }) { task ->
                        TaskRow(
                            task,
                            viewModel::cancel,
                            onOpen = { openInGallery(context, it) },
                            onRetry = { viewModel.retry(it.id) },
                            onDelete = viewModel::removeTaskEntry,
                            onReport = { t ->
                                ReportSender.send(context, t.shortcode, t.error)
                            },
                            folderName = state.folderName,
                        )
                    }
                }
            } else {
                EmptyHint(state.folderName)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LinkInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Ссылка на Reels или пост") },
        placeholder = { Text("https://www.instagram.com/reel/...") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        trailingIcon = {
            IconButton(
                onClick = {
                    val clip = clipboard?.primaryClip
                    val text = if (clip != null && clip.itemCount > 0) {
                        clip.getItemAt(0).coerceToText(context).toString()
                    } else ""
                    if (text.isNotBlank()) onValueChange(text)
                },
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = "Вставить из буфера")
            }
        },
    )
}

@Composable
private fun UpdateBanner(release: Release, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Доступна новая версия",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                release.name ?: release.tag_name ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpen) { Text("Обновить") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Позже") }
            }
        }
    }
}

@Composable
private fun EmptyHint(folderName: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 60.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Видео появятся в папке\nMovies/$folderName в галерее",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun openInGallery(context: android.content.Context, task: TaskItem) {
    val uriText = task.uri
    if (uriText.isNullOrBlank()) {
        Toast.makeText(context, "Видео ещё не сохранено", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(uriText), "video/mp4")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Нет приложения для просмотра видео", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskRow(
    task: TaskItem,
    onCancel: (Long) -> Unit,
    onOpen: (TaskItem) -> Unit,
    onRetry: (TaskItem) -> Unit,
    onDelete: (TaskItem) -> Unit,
    onReport: (TaskItem) -> Unit,
    folderName: String = "InstaSave",
) {
    var previewing by remember { mutableStateOf(false) }
    val previewUri = task.uri
    if (previewing && !previewUri.isNullOrBlank()) {
        VideoPreviewDialog(uri = previewUri, onDismiss = { previewing = false })
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = task.status == TaskStatus.DONE) { onOpen(task) },
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(
                            enabled = task.status == TaskStatus.DONE && !previewUri.isNullOrBlank(),
                        ) { previewing = true },
                ) {
                    Thumb(task.thumbnail)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        task.fileName.ifBlank { (if (task.shortcode.isNotBlank()) "@" + task.shortcode else "Загрузка…") },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (task.username.isNotBlank()) {
                        Text(
                            "@${task.username}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                when (task.status) {
                    TaskStatus.DONE -> Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF2E9E5B),
                    )
                    TaskStatus.ERROR -> Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    else -> IconButton(onClick = { onCancel(task.id) }) {
                        Icon(Icons.Default.Close, contentDescription = "Отмена")
                    }
                }
            }

            when (task.status) {
                TaskStatus.RESOLVING -> {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "Ищем видео…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TaskStatus.DOWNLOADING -> {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Скачивание… ${(task.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TaskStatus.SAVING -> {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "Сохраняем в галерею…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TaskStatus.DONE -> {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Сохранено в Movies/$folderName · нажмите, чтобы открыть в галерее",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF2E9E5B),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (task.createdAt > 0L) {
                            val dateText = remember(task.createdAt) {
                                java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale("ru"))
                                    .format(java.util.Date(task.createdAt))
                            }
                            Text(
                                dateText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onDelete(task) }) { Text("Удалить") }
                    }
                }
                TaskStatus.ERROR -> {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        task.error ?: "Ошибка",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onRetry(task) }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Повторить")
                        }
                        TextButton(onClick = { onReport(task) }) { Text("Отчёт об ошибке") }
                    }
                }
            }
        }
    }
}

/**
 * Plays the saved file in a dialog so the result can be checked without leaving the app.
 */
@Composable
private fun VideoPreviewDialog(uri: String, onDismiss: () -> Unit) {
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var controller by remember { mutableStateOf<MediaController?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            videoView?.stopPlayback()
            controller?.hide()
            videoView = null
            controller = null
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply { videoView = this }.also { v ->
                        val media = MediaController(ctx)
                        controller = media
                        media.setAnchorView(v)
                        v.setMediaController(media)
                        v.setVideoURI(Uri.parse(uri))
                        v.setOnPreparedListener { mp ->
                            mp.isLooping = true
                            v.start()
                        }
                        v.setOnErrorListener { _, _, _ -> true }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Закрыть", color = Color.White) }
            }
        }
    }
}