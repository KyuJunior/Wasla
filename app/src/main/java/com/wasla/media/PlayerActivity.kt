package com.wasla.media

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class) // controllerAutoShow
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(14, 18, 28)
        window.navigationBarColor = Color.rgb(14, 18, 28)
        val all = LibraryStore.all(this)
        val id = intent.getStringExtra("id")
        val selected = all.find { it.id == id } ?: run { finish(); return }
        val queue = if (selected.kind == "audio") all.filter { it.kind == "audio" } else listOf(selected)
        val start = queue.indexOfFirst { it.id == selected.id }.coerceAtLeast(0)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(14, 18, 28))
            setPadding(dp(18), dp(24), dp(18), dp(18))
        }
        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(18), dp(24) + bars.top, dp(18), dp(18) + bars.bottom)
            insets
        }
        val back = TextView(this).apply {
            text = "←  الرجوع للمكتبة"; textSize = 16f; setTextColor(Color.WHITE)
            setOnClickListener { finish() }
        }
        layout.addView(back)
        layout.addView(TextView(this).apply {
            text = selected.title; textSize = 21f; setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD); setPadding(0, dp(25), 0, dp(20))
        })
        val view = PlayerView(this).apply {
            setBackgroundColor(Color.rgb(25, 31, 45))
            useController = true
            controllerAutoShow = true
        }
        layout.addView(view, LinearLayout.LayoutParams(-1, if (selected.kind == "audio") dp(190) else 0,
            if (selected.kind == "audio") 0f else 1f))
        if (selected.kind == "audio") {
            layout.addView(TextView(this).apply {
                text = "المقطع التالي يبدأ تلقائياً حسب ترتيب مكتبتك"
                textSize = 14f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER
                setPadding(0, dp(26), 0, 0)
            })
        }
        setContentView(layout)
        player = ExoPlayer.Builder(this).build().also { exo ->
            exo.setMediaItems(queue.map { MediaItem.fromUri(it.uri(this)) }, start, 0L)
            view.player = exo
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
