package com.instasave.app.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.instasave.app.BuildConfig
import com.instasave.app.download.Downloader
import com.instasave.app.gallery.GallerySaver
import com.instasave.app.history.HistoryEntry
import com.instasave.app.history.HistoryStore
import com.instasave.app.notify.Notifier
import com.instasave.app.resolver.InstagramApi
import com.instasave.app.resolver.ResolveException
import com.instasave.app.resolver.ResolvedMedia
import com.instasave.app.resolver.selectUrl
import com.instasave.app.settings.Quality
import com.instasave.app.settings.SettingsStore
import com.instasave.app.update.Release
import com.instasave.app.update.UpdateChecker
import com.instasave.app.util.LinkParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class TaskStatus { RESOLVING, DOWNLOADING, SAVING, DONE, ERROR }

data class TaskItem(
    val id: Long,
    val shortcode: String,
    val username: String = "",
    val fileName: String = "",
    val url: String = "",
    val thumbnail: String? = null,
    val progress: Float = 0f,
    val status: TaskStatus = TaskStatus.RESOLVING,
    val error: String? = null,
    val uri: String? = null,
    val createdAt: Long = 0L,
)

data class AppUiState(
    val input: String = "",
    val tasks: List<TaskItem> = emptyList(),
    val snackbar: String? = null,
    val quality: Quality = Quality.MAX,
    val folderName: String = SettingsStore.DEFAULT_FOLDER,
    val skipDuplicates: Boolean = true,
    val autoStartFromShare: Boolean = true,
    val notifications: Boolean = true,
    val settingsOpen: Boolean = false,
    val lastDownloadedUri: String? = null,
    val update: Release? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state

    private val api = InstagramApi()
    private val downloader = Downloader()
    private val settings = SettingsStore(application)
    private val saver = GallerySaver(application) { settings.folderName }
    private val notifier = Notifier(application) { settings.notifications }
    private val history = HistoryStore(application)
    private val jobs = mutableMapOf<Long, Job>()
    private val taskSeq = AtomicLong(0)
    /** Shortcodes of everything present in history; used for the "skip duplicates" check. */
    private val downloadedShortcodes = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    init {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    quality = settings.quality,
                    folderName = settings.folderName,
                    skipDuplicates = settings.skipDuplicates,
                    autoStartFromShare = settings.autoStartFromShare,
                    notifications = settings.notifications,
                )
            }
            loadHistory()
            prefillFromClipboard()
            checkForUpdate()
        }
    }

    /** Silently looks for a newer release on GitHub; shows a banner when one exists. */
    private suspend fun checkForUpdate() {
        val repo = BuildConfig.UPDATE_REPO
        if (repo.isBlank()) return
        val release = UpdateChecker.latestRelease(repo) ?: return
        if (!release.draft && !release.prerelease &&
            UpdateChecker.isNewer(BuildConfig.VERSION_NAME, release.tag_name)
        ) {
            _state.update { it.copy(update = release) }
        }
    }

    fun dismissUpdate() {
        _state.update { it.copy(update = null) }
    }

    // ---------------------------------------------------------------- settings

    fun openSettings(open: Boolean) {
        _state.update { it.copy(settingsOpen = open) }
    }

    fun setQuality(q: Quality) {
        settings.quality = q
        _state.update { it.copy(quality = q) }
    }

    fun setFolderName(name: String) {
        val cleaned = SettingsStore.sanitizeFolder(name)
        settings.folderName = cleaned
        _state.update { it.copy(folderName = cleaned) }
    }

    fun setSkipDuplicates(value: Boolean) {
        settings.skipDuplicates = value
        _state.update { it.copy(skipDuplicates = value) }
    }

    fun setAutoStartFromShare(value: Boolean) {
        settings.autoStartFromShare = value
        _state.update { it.copy(autoStartFromShare = value) }
    }

    fun setNotifications(value: Boolean) {
        settings.notifications = value
        _state.update { it.copy(notifications = value) }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) { history.clear() }
        _state.update { s -> s.copy(tasks = s.tasks.filterNot { it.status == TaskStatus.DONE }) }
        showSnackbar("История очищена")
    }

    private suspend fun loadHistory() {
        val items = withContext(Dispatchers.IO) {
            history.load().mapNotNull { entry ->
                downloadedShortcodes += entry.shortcode
                TaskItem(
                    id = entry.finishedAt,
                    shortcode = entry.shortcode,
                    username = entry.username,
                    fileName = entry.fileName,
                    thumbnail = entry.thumbnail,
                    uri = entry.uri,
                    status = TaskStatus.DONE,
                    progress = 1f,
                    createdAt = entry.finishedAt,
                )
            }
        }
        if (items.isNotEmpty()) {
            _state.update { s -> s.copy(tasks = items + s.tasks) }
        }
    }

    fun onInputChange(value: String) {
        _state.update { it.copy(input = value) }
    }

    fun submit() {
        val text = _state.value.input
        val shortcode = LinkParser.extractShortcode(text)
            ?: run {
                showSnackbar("Не удалось найти ссылку Instagram. Пример: instagram.com/reel/AbC123XYZ")
                return
            }
        _state.update { it.copy(input = "") }
        launchForShortcode(shortcode)
    }

    fun fillFromText(text: String?) {
        val link = LinkParser.extractLink(text)
        if (link != null) _state.update { it.copy(input = link) }
    }

    fun autoStartEnabled(): Boolean = settings.autoStartFromShare

    fun showHint(text: String) {
        _state.update { it.copy(snackbar = text) }
    }

    fun prefillFromClipboard() {
        val link = LinkParser.extractLink(clipboardTextOrNull())
        if (link != null && _state.value.input.isBlank()) {
            _state.update { it.copy(input = link) }
        }
    }

    fun cancel(taskId: Long) {
        jobs.remove(taskId)?.cancel()
        _state.update { s -> s.copy(tasks = s.tasks.filterNot { it.id == taskId }) }
    }

    fun clearFinished() {
        _state.update { s ->
            s.copy(tasks = s.tasks.filterNot { it.status == TaskStatus.DONE || it.status == TaskStatus.ERROR })
        }
        viewModelScope.launch(Dispatchers.IO) { history.clear() }
    }

    fun snackbarShown() {
        _state.update { it.copy(snackbar = null) }
    }

    private fun showSnackbar(text: String) {
        _state.update { it.copy(snackbar = text) }
    }

    private fun clipboardTextOrNull(): String? {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return try {
            clip.getItemAt(0).coerceToText(getApplication()).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun launchForShortcode(shortcode: String) {
        val id = taskSeq.incrementAndGet()
        addTask(TaskItem(id = id, shortcode = shortcode))
        val job = viewModelScope.launch {
            if (_state.value.skipDuplicates && isDuplicate(shortcode)) {
                removeTask(id)
                showSnackbar("Этот ролик уже скачан — он в истории загрузок.")
                return@launch
            }
            runCatching {
                val media = withContext(Dispatchers.IO) { api.resolve(shortcode) }
                processMedia(id, shortcode, media)
            }.onFailure { t ->
                if (t !is CancellationException) {
                    patchTask(id) { it.copy(status = TaskStatus.ERROR, error = messageOf(t)) }
                }
            }
        }
        jobs[id] = job
    }

    private suspend fun isDuplicate(shortcode: String): Boolean {
        if (shortcode.isBlank()) return false
        if (downloadedShortcodes.contains(shortcode)) return true
        if (_state.value.tasks.any { it.shortcode == shortcode && it.status == TaskStatus.DONE }) return true
        return withContext(Dispatchers.IO) { history.containsShortcode(shortcode) }
    }

    private suspend fun processMedia(resolvingTaskId: Long, shortcode: String, media: List<ResolvedMedia>) {
        removeTask(resolvingTaskId)
        if (media.isEmpty()) {
            showSnackbar("В этом посте не нашлось видео.")
            return
        }
        val count = media.size
        val tasks = media.mapIndexed { i, m ->
            val name = displayName(m, count, i)
            TaskItem(
                id = taskSeq.incrementAndGet(),
                shortcode = m.shortcode.ifBlank { shortcode },
                username = m.username.orEmpty(),
                fileName = name,
                url = m.selectUrl(_state.value.quality),
                thumbnail = m.thumbnail,
                status = TaskStatus.DOWNLOADING,
            )
        }
        addTasks(tasks)
        tasks.forEach { downloadOne(it) }
    }

    private fun downloadOne(task: TaskItem) {
        val job = viewModelScope.launch {
            runCatching {
                val file = downloadToTemp(task)
                try {
                    patchTask(task.id) { it.copy(status = TaskStatus.SAVING, progress = 1f) }
                    val uri = withContext(Dispatchers.IO) { saver.save(file, task.fileName) }
                    val finishedAt = System.currentTimeMillis()
                    patchTask(task.id) {
                        it.copy(status = TaskStatus.DONE, progress = 1f, uri = uri.toString(), createdAt = finishedAt)
                    }
                    withContext(Dispatchers.IO) {
                        history.put(
                            HistoryEntry(
                                shortcode = task.shortcode,
                                username = task.username,
                                fileName = task.fileName,
                                uri = uri.toString(),
                                thumbnail = task.thumbnail,
                                finishedAt = finishedAt,
                            ),
                        )
                    }
                    if (task.shortcode.isNotBlank()) downloadedShortcodes += task.shortcode
                    notifier.notifySaved(task.fileName, uri)
                } finally {
                    file.delete()
                }
            }.onFailure { t ->
                if (t !is CancellationException) {
                    patchTask(task.id) {
                        it.copy(status = TaskStatus.ERROR, error = messageOf(t))
                    }
                }
            }
        }
        jobs[task.id] = job
    }

    private suspend fun downloadToTemp(task: TaskItem): File {
        val file = File(getApplication<Application>().cacheDir, "download_${task.id}.mp4")
        downloader.download(task.url, file) { progress ->
            patchTask(task.id) { it.copy(progress = progress, status = TaskStatus.DOWNLOADING) }
        }
        return file
    }

    private fun displayName(m: ResolvedMedia, count: Int, index: Int): String {
        val user = sanitizeUser(m.username ?: "")
        val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val suffix = if (count > 1) "_${index + 1}" else ""
        return "$user${if (user.isEmpty()) "" else "_"}${m.shortcode}_$date$suffix.mp4"
    }

    private fun sanitizeUser(s: String): String =
        s.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(24)

    private fun messageOf(t: Throwable): String {
        if (t is ResolveException) return t.message ?: "Не удалось получить видео."
        val msg = t.message ?: ""
        val looksLikeParsing =
            t is kotlinx.serialization.SerializationException ||
                t is ClassCastException || t is IllegalStateException || t is IllegalArgumentException ||
                msg.contains("kotlinx", ignoreCase = true)
        return when {
            t is CancellationException -> t.message ?: "Отменено"
            looksLikeParsing -> "Не удалось разобрать ответ Instagram. Пост может иметь нестандартный формат."
            msg.isBlank() -> "Неожиданная ошибка."
            else -> msg.substringBefore('\n')
        }
    }

    private fun addTask(task: TaskItem) {
        _state.update { s -> s.copy(tasks = s.tasks + task) }
    }

    private fun addTasks(items: List<TaskItem>) {
        _state.update { s -> s.copy(tasks = s.tasks + items) }
    }

    private fun patchTask(id: Long, transform: (TaskItem) -> TaskItem) {
        _state.update { s ->
            s.copy(tasks = s.tasks.map { if (it.id == id) transform(it) else it })
        }
    }

    private fun removeTask(id: Long) {
        _state.update { s -> s.copy(tasks = s.tasks.filterNot { it.id == id }) }
    }
}