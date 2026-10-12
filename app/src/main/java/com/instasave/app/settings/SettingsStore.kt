package com.instasave.app.settings

import android.content.Context
import android.content.SharedPreferences

/** Quality of the video file to download. */
enum class Quality(val label: String, val hint: String) {
    MAX("Максимальное", "Лучшее качество, больше размер"),
    MEDIUM("Среднее", "Компромисс между размером и качеством"),
    LOW("Экономное", "Малый размер, быстрая загрузка"),
}

/**
 * User preferences persisted in SharedPreferences.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var quality: Quality
        get() = runCatching {
            Quality.valueOf(prefs.getString(KEY_QUALITY, Quality.MAX.name) ?: Quality.MAX.name)
        }.getOrDefault(Quality.MAX)
        set(value) {
            prefs.edit().putString(KEY_QUALITY, value.name).apply()
        }

    var folderName: String
        get() = sanitizeFolder(prefs.getString(KEY_FOLDER, DEFAULT_FOLDER))
        set(value) {
            prefs.edit().putString(KEY_FOLDER, sanitizeFolder(value)).apply()
        }

    var skipDuplicates: Boolean
        get() = prefs.getBoolean(KEY_DEDUPE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_DEDUPE, value).apply()
        }

    var autoStartFromShare: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTOSTART, value).apply()
        }

    var notifications: Boolean
        get() = prefs.getBoolean(KEY_NOTIF, true)
        set(value) {
            prefs.edit().putBoolean(KEY_NOTIF, value).apply()
        }

    companion object {
        const val PREFS = "instasave_settings"
        const val DEFAULT_FOLDER = "InstaSave"
        private const val KEY_QUALITY = "quality"
        private const val KEY_FOLDER = "folder"
        private const val KEY_DEDUPE = "skip_duplicates"
        private const val KEY_AUTOSTART = "autostart_from_share"
        private const val KEY_NOTIF = "notifications"

        /** Keeps only characters that are safe for a directory name. */
        fun sanitizeFolder(raw: String?): String {
            val cleaned = (raw ?: "")
                .replace('/', ' ').replace('\\', ' ')
                .replace(':', ' ').replace('*', ' ').replace('?', ' ')
                .replace('"', ' ').replace('<', ' ').replace('>', ' ')
                .replace('|', ' ').replace('\n', ' ').replace('\r', ' ')
                .trim()
                .replace(Regex("\\s+"), " ")
            return cleaned.ifBlank { DEFAULT_FOLDER }.take(40)
        }
    }
}
