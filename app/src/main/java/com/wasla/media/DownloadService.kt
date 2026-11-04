package com.wasla.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID

class DownloadService : Service() {
    companion object {
        const val ACTION_STATUS = "com.wasla.media.STATUS"
        const val EXTRA_MESSAGE = "message"
        private const val CHANNEL = "wasla_downloads"
        private const val NOTIFICATION_ID = 7
    }

    @Volatile private var busy = false
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "تنزيلات وصلة", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (busy) {
            sendStatus("انتظر اكتمال التنزيل الحالي")
            return START_NOT_STICKY
        }
        val url = intent?.getStringExtra("url") ?: return START_NOT_STICKY
        val kind = intent.getStringExtra("kind") ?: "video"
        val quality = intent.getStringExtra("quality") ?: "best"
        val title = intent.getStringExtra("title") ?: "مقطع جديد"
        busy = true
        startForeground(NOTIFICATION_ID, notification("جاري تحضير التنزيل", 0))
        Thread {
            val id = UUID.randomUUID().toString()
            try {
                sendStatus("جاري تحديث أداة التنزيل وفحصها...")
                ExtractorSetup.ensureCurrent(this)
                FFmpeg.getInstance().init(applicationContext)
                val folder = LibraryStore.mediaDir(this)
                val request = YoutubeDLRequest(url)
                request.addOption("--no-playlist")
                request.addOption("--no-mtime")
                request.addOption("--restrict-filenames")
                request.addOption("-o", File(folder, "$id.%(ext)s").absolutePath)
                if (kind == "audio") {
                    request.addOption("-f", "bestaudio/best")
                    request.addOption("-x")
                    request.addOption("--audio-format", "mp3")
                    request.addOption("--audio-quality", "${quality}K")
                } else {
                    val selector = if (quality == "best") "bestvideo+bestaudio/best" else
                        "bestvideo[height<=$quality]+bestaudio/best[height<=$quality]"
                    request.addOption("-f", selector)
                    request.addOption("--merge-output-format", "mp4")
                }
                var lastShown = -10
                YoutubeDL.getInstance().execute(request, id) { progress, _, _ ->
                    val percent = progress.toInt().coerceIn(0, 100)
                    if (percent >= lastShown + 5 || percent == 100) {
                        lastShown = percent
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, notification("جاري التنزيل: $percent٪", percent))
                        sendStatus("جاري التنزيل: $percent٪")
                    }
                }
                val file = folder.listFiles()?.firstOrNull { it.name.startsWith("$id.") && it.isFile && !it.name.endsWith(".part") }
                    ?: error("لم يُعثر على الملف بعد التنزيل")
                LibraryStore.add(this, SavedMedia(id, title, kind, file.name))
                sendStatus("اكتمل التنزيل: $title")
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID + 1, notification("اكتمل التنزيل: $title", 100))
            } catch (e: Exception) {
                val detail = e.message.orEmpty()
                val message = when {
                    detail.contains("403") -> "تعذّر التنزيل من يوتيوب (403). جرّب المقطع لاحقاً."
                    detail.contains("update", true) || detail.contains("github", true) -> "تعذّر تحديث أداة التنزيل. تحقق من الإنترنت ثم حاول مجدداً."
                    else -> "فشل التنزيل: ${detail.lineSequence().firstOrNull()?.take(140) ?: "خطأ غير معروف"}"
                }
                sendStatus(message)
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID + 1, notification("فشل التنزيل", 0))
            } finally {
                busy = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun sendStatus(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_MESSAGE, message))
    }

    private fun notification(message: String, progress: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("وصلة")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(progress in 1..99)
            .setProgress(100, progress, progress == 0)
            .build()
    }
}
