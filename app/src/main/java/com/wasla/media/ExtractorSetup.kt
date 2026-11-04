package com.wasla.media

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL

object ExtractorSetup {
    private const val CHECK_INTERVAL = 24 * 60 * 60 * 1000L

    @Synchronized fun ensureCurrent(context: Context) {
        val app = context.applicationContext
        val downloader = YoutubeDL.getInstance()
        downloader.init(app)
        val prefs = app.getSharedPreferences("extractor", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_update_check", 0L)
        if (last == 0L || now < last || now - last >= CHECK_INTERVAL) {
            // A failed self-update must not block downloads: the bundled yt-dlp usually still works.
            try {
                downloader.updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
                prefs.edit().putLong("last_update_check", now).apply()
            } catch (e: Exception) {
                Log.w("Wasla", "yt-dlp update failed, using current version", e)
            }
        }
    }
}
