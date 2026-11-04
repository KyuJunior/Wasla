package com.wasla.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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
        // Every startForegroundService call must be answered with startForeground, even ones we reject.
        startForeground(NOTIFICATION_ID, notification("جاري تحضير التنزيل", 0))
        if (busy) {
            sendStatus("انتظر اكتمال التنزيل الحالي")
            return START_NOT_STICKY
        }
        val url = intent?.getStringExtra("url")
        if (url == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val kind = intent.getStringExtra("kind") ?: "video"
        val quality = intent.getStringExtra("quality") ?: "best"
        val title = intent.getStringExtra("title") ?: "مقطع جديد"
        busy = true
        Thread {
            val id = UUID.randomUUID().toString()
            val folder = LibraryStore.mediaDir(this)
            val target = File(folder, if (kind == "audio") "$id.mp3" else "$id.mp4")
            try {
                sendStatus("جاري تحديث أداة التنزيل وفحصها...")
                ExtractorSetup.ensureCurrent(this)
                FFmpeg.getInstance().init(applicationContext)
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
                    val limit = if (quality == "best") "" else "[height<=$quality]"
                    request.addOption("-f", "bv*$limit+ba/b$limit")
                    // Prefer H.264/AAC so the file plays on devices without AV1/VP9 decoders.
                    request.addOption("-S", "vcodec:h264,res,acodec:m4a")
                    request.addOption("--merge-output-format", "mp4")
                    request.addOption("--remux-video", "mp4")
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
                if (!target.isFile) error("لم يُعثر على الملف بعد التنزيل")
                LibraryStore.add(this, SavedMedia(id, title, kind, target.name))
                sendStatus("اكتمل التنزيل: $title")
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID + 1, notification("اكتمل التنزيل: $title", null))
            } catch (e: Exception) {
                val detail = e.message.orEmpty()
                val message = when {
                    detail.contains("HTTP Error 403") -> "تعذّر التنزيل من يوتيوب (403). جرّب المقطع لاحقاً."
                    else -> "فشل التنزيل: ${detail.lineSequence().firstOrNull { it.isNotBlank() }?.take(140) ?: "خطأ غير معروف"}"
                }
                sendStatus(message)
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID + 1, notification("فشل التنزيل", null))
            } finally {
                // Remove fragments, .part files and intermediates; keep only the finished file.
                folder.listFiles()?.filter { it.name.startsWith("$id.") && it != target }?.forEach { it.delete() }
                busy = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun sendStatus(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_MESSAGE, message))
    }

    /** progress: null = no bar, 0 = indeterminate, 1..100 = percent. */
    private fun notification(message: String, progress: Int?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("وصلة")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(progress != null && progress < 100)
        if (progress != null) builder.setProgress(100, progress, progress == 0)
        return builder.build()
    }
}
