package com.instasave.app.util

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.instasave.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Packages the last raw Instagram responses (written by InstagramApi.dump) into a single
 * HTML file and opens the system share sheet, so a broken parser can be debugged remotely.
 */
object ReportSender {

    private const val MAX_DUMP_CHARS = 400_000

    fun send(context: Context, shortcode: String, error: String?) {
        runCatching {
            val report = build(context, shortcode, error)
            val dir = File(context.cacheDir, "reports").apply { mkdirs() }
            dir.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 7 * 86_400_000L }
                ?.forEach { runCatching { it.delete() } }
            val file = File(dir, "instasave_report_${System.currentTimeMillis()}.html")
            file.writeText(report)

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/html")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "InstaSave — отчёт об ошибке")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(send, "Отправить отчёт")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }.onFailure {
            Toast.makeText(context, "Не удалось подготовить отчёт: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun build(context: Context, shortcode: String, error: String?): String {
        val sb = StringBuilder()
        sb.appendLine("<!--")
        sb.appendLine("InstaSave error report")
        sb.appendLine("version: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
        sb.appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine("shortcode: $shortcode")
        sb.appendLine("error: ${error ?: ""}")
        sb.appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        sb.appendLine("android: ${android.os.Build.VERSION.RELEASE} (sdk ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine("-->")

        val debugDir = File(context.filesDir, "debug")
        val dumps = debugDir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()
        if (dumps.isEmpty()) {
            sb.appendLine("<!-- InstagramApi не успел сохранить ответы (сбои не на уровне разметки) -->")
        }
        dumps.take(3).forEach { f ->
            sb.appendLine()
            sb.appendLine("<!-- ===== ${f.name}, ${f.length()} bytes ===== -->")
            runCatching { sb.appendLine(f.readText().take(MAX_DUMP_CHARS)) }
                .onFailure { sb.appendLine("<!-- unreadable: $it -->") }
        }
        return sb.toString()
    }
}
