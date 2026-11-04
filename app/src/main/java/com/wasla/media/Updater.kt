package com.wasla.media

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import androidx.core.content.IntentCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases of [REPO] for a newer APK and installs it with PackageInstaller. */
object Updater {
    const val REPO = "KyuJunior/Wasla"

    data class Release(val version: String, val apkUrl: String, val notes: String)

    fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** Latest release with an .apk asset; throws on network/HTTP errors, null if it has no APK. */
    fun latest(): Release? {
        val conn = open("https://api.github.com/repos/$REPO/releases/latest")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk", true) } ?: return null
            return Release(json.getString("tag_name").removePrefix("v").removePrefix("V"),
                apk.getString("browser_download_url"), json.optString("body"))
        } finally {
            conn.disconnect()
        }
    }

    /** Numeric dotted compare: "1.10" > "1.9"; non-numeric parts count as 0. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Downloads the APK (blocking, call off the main thread) and hands it to the system installer. */
    fun downloadAndInstall(context: Context, release: Release, onProgress: (Int) -> Unit) {
        val app = context.applicationContext
        val file = File(app.cacheDir, "update.apk")
        val conn = open(release.apkUrl)
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastShown = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        if (percent != lastShown) { lastShown = percent; onProgress(percent) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(app.packageName) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("wasla.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(app, sessionId, Intent(app, UpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(callback.intentSender)
        }
        file.delete()
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "Wasla-Android")
    }
}

/** Receives PackageInstaller results: shows the system confirm screen, or reports failure. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> {
                val message = if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE)
                    "تعذّر التحديث: النسخة المثبتة موقّعة بمفتاح مختلف. احذف التطبيق وثبّت الإصدار الجديد يدوياً."
                else "تعذّر التحديث: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: status}"
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
