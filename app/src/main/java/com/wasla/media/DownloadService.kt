package com.wasla.media

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class DownloadJob(val id: String, val url: String, val title: String, val kind: String, val quality: String) {
    enum class State { QUEUED, RUNNING, DONE, FAILED, CANCELED }
    @Volatile var state = State.QUEUED
    @Volatile var percent = 0
    @Volatile var message = ""
    val active get() = state == State.QUEUED || state == State.RUNNING
}

/** In-process download list shown in the "قيد التنزيل" tab. Lost if the process dies. */
object Downloads {
    val jobs = CopyOnWriteArrayList<DownloadJob>()
    fun activeCount() = jobs.count { it.active }
    fun clearFinished() { jobs.removeAll { !it.active } }
}

class DownloadService : Service() {
    companion object {
        const val ACTION_STATUS = "com.wasla.media.STATUS"
        const val ACTION_CANCEL = "com.wasla.media.CANCEL"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_LIBRARY_CHANGED = "library_changed"
        const val EXTRA_ID = "id"
        private const val CHANNEL = "wasla_downloads"
        private const val NOTIFICATION_ID = 7
    }

    private val main = Handler(Looper.getMainLooper())
    private var worker: Thread? = null // touched on the main thread only

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "تنزيلات وصلة", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService call must be answered with startForeground.
        startForeground(NOTIFICATION_ID, progressNotification())
        if (intent?.action == ACTION_CANCEL) cancel(intent.getStringExtra(EXTRA_ID))
        else intent?.getStringExtra("url")?.let { url ->
            val job = DownloadJob(UUID.randomUUID().toString(), url,
                intent.getStringExtra("title") ?: "مقطع جديد",
                intent.getStringExtra("kind") ?: "video",
                intent.getStringExtra("quality") ?: "best")
            Downloads.jobs.add(job)
            changed("أضيف إلى قائمة التنزيل: ${job.title}")
        }
        pump()
        return START_NOT_STICKY
    }

    /** Runs on the main thread: start the next queued job, or stop when nothing is left. */
    private fun pump() {
        if (worker != null) return
        val job = Downloads.jobs.firstOrNull { it.state == DownloadJob.State.QUEUED }
        if (job == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        worker = Thread {
            try { run(job) } finally { main.post { worker = null; pump() } }
        }.also { it.start() }
    }

    private fun cancel(id: String?) {
        val job = Downloads.jobs.find { it.id == id } ?: return
        when (job.state) {
            DownloadJob.State.QUEUED -> { job.state = DownloadJob.State.CANCELED; changed("أُلغي: ${job.title}") }
            DownloadJob.State.RUNNING -> { job.state = DownloadJob.State.CANCELED; YoutubeDL.getInstance().destroyProcessById(job.id) }
            else -> Unit
        }
    }

    private fun run(job: DownloadJob) {
        if (job.state == DownloadJob.State.CANCELED) return
        job.state = DownloadJob.State.RUNNING
        changed("جاري تحضير: ${job.title}")
        val folder = LibraryStore.mediaDir(this)
        val target = File(folder, if (job.kind == "audio") "${job.id}.mp3" else "${job.id}.mp4")
        try {
            ExtractorSetup.ensureCurrent(this)
            FFmpeg.getInstance().init(applicationContext)
            if (job.state == DownloadJob.State.CANCELED) error("canceled")
            val request = YoutubeDLRequest(job.url)
            request.addOption("--no-playlist")
            request.addOption("--no-mtime")
            request.addOption("--restrict-filenames")
            request.addOption("-o", File(folder, "${job.id}.%(ext)s").absolutePath)
            if (job.kind == "audio") {
                request.addOption("-f", "bestaudio/best")
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
                request.addOption("--audio-quality", "${job.quality}K")
            } else {
                val limit = if (job.quality == "best") "" else "[height<=${job.quality}]"
                request.addOption("-f", "bv*$limit+ba/b$limit")
                // Prefer H.264/AAC so the file plays on devices without AV1/VP9 decoders.
                request.addOption("-S", "vcodec:h264,res,acodec:m4a")
                request.addOption("--merge-output-format", "mp4")
                request.addOption("--remux-video", "mp4")
            }
            var lastShown = -10
            YoutubeDL.getInstance().execute(request, job.id) { progress, _, _ ->
                val percent = progress.toInt().coerceIn(0, 100)
                if (percent >= lastShown + 5 || percent == 100) {
                    lastShown = percent
                    job.percent = percent
                    changed(null)
                }
            }
            if (job.state == DownloadJob.State.CANCELED) error("canceled")
            if (!target.isFile) error("لم يُعثر على الملف بعد التنزيل")
            val location = publish(target, job)
            LibraryStore.add(this, SavedMedia(job.id, job.title, job.kind, location))
            job.percent = 100
            job.state = DownloadJob.State.DONE
            changed("اكتمل التنزيل: ${job.title}", libraryChanged = true)
            finalNotification(job, "اكتمل التنزيل: ${job.title}")
        } catch (e: Exception) {
            if (job.state == DownloadJob.State.CANCELED) {
                changed("أُلغي: ${job.title}")
                return
            }
            val detail = e.message.orEmpty()
            job.message = when {
                detail.contains("HTTP Error 403") -> "تعذّر التنزيل من يوتيوب (403). جرّب المقطع لاحقاً."
                else -> "فشل التنزيل: ${detail.lineSequence().firstOrNull { it.isNotBlank() }?.take(140) ?: "خطأ غير معروف"}"
            }
            job.state = DownloadJob.State.FAILED
            changed(job.message)
            finalNotification(job, "فشل التنزيل: ${job.title}")
        } finally {
            // Remove fragments, .part files and intermediates; keep the finished file if it stayed here.
            folder.listFiles()?.filter { it.name.startsWith("${job.id}.") && it != target }?.forEach { it.delete() }
        }
    }

    /** Moves the finished file into the phone's Music/Wasla or Movies/Wasla folder; returns its library location. */
    private fun publish(temp: File, job: DownloadJob): String {
        val audio = job.kind == "audio"
        val name = job.title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(80).ifEmpty { "wasla" } +
            if (audio) ".mp3" else ".mp4"
        val mime = if (audio) "audio/mpeg" else "video/mp4"
        val publicDir = if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = if (audio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = contentResolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$publicDir/Wasla")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("تعذّر حفظ الملف في الهاتف")
            try {
                (contentResolver.openOutputStream(uri) ?: error("تعذّر حفظ الملف في الهاتف")).use { out ->
                    temp.inputStream().use { it.copyTo(out) }
                }
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
            temp.delete()
            return uri.toString()
        }
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            return temp.name // shortcut: Android 8-9 without storage permission keeps the file in app storage
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(publicDir), "Wasla").apply { mkdirs() }
        var dest = File(dir, name)
        var n = 1
        while (dest.exists()) dest = File(dir, "${name.substringBeforeLast('.')} (${n++}).${name.substringAfterLast('.')}")
        temp.copyTo(dest)
        temp.delete()
        MediaScannerConnection.scanFile(this, arrayOf(dest.path), arrayOf(mime), null)
        return Uri.fromFile(dest).toString()
    }

    private fun changed(message: String?, libraryChanged: Boolean = false) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName)
            .putExtra(EXTRA_MESSAGE, message).putExtra(EXTRA_LIBRARY_CHANGED, libraryChanged))
        if (Downloads.activeCount() > 0)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, progressNotification())
    }

    private fun openQueue(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).putExtra("tab", "queue").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun progressNotification(): Notification {
        val running = Downloads.jobs.firstOrNull { it.state == DownloadJob.State.RUNNING }
        val waiting = Downloads.jobs.count { it.state == DownloadJob.State.QUEUED }
        val text = (running?.let { "${it.title}: ${it.percent}٪" } ?: "جاري تحضير التنزيل") +
            if (waiting > 0) " · $waiting في الانتظار" else ""
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("وصلة")
            .setContentText(text)
            .setContentIntent(openQueue())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, running?.percent ?: 0, running == null || running.percent == 0)
            .build()
    }

    private fun finalNotification(job: DownloadJob, message: String) {
        getSystemService(NotificationManager::class.java).notify(job.id.hashCode(),
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(if (job.state == DownloadJob.State.DONE) android.R.drawable.stat_sys_download_done
                    else android.R.drawable.stat_notify_error)
                .setContentTitle("وصلة")
                .setContentText(message)
                .setContentIntent(openQueue())
                .setAutoCancel(true)
                .build())
    }
}
