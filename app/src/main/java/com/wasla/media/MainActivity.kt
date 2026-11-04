package com.wasla.media

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.yausername.youtubedl_android.YoutubeDL

class MainActivity : Activity() {
    private val bg = Color.rgb(14, 18, 28)
    private val card = Color.rgb(25, 31, 45)
    private val accent = Color.rgb(99, 220, 183)
    private val soft = Color.rgb(177, 190, 205)
    private lateinit var root: LinearLayout
    private lateinit var link: EditText
    private lateinit var status: TextView
    private lateinit var titleView: TextView
    private lateinit var quality: Spinner
    private lateinit var list: LinearLayout
    private var kind = "video"
    private var tab = "download"
    private var foundTitle = "مقطع جديد"
    private var foundUrl = "" // the URL foundTitle belongs to
    private var draftUrl = ""
    private var updateChecked = false
    private var queueTab: TextView? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(DownloadService.EXTRA_MESSAGE)
            if (message != null && ::status.isInitialized) status.text = message
            queueTab?.text = queueLabel()
            if (tab == "queue") renderQueue()
            else if (intent?.getBooleanExtra(DownloadService.EXTRA_LIBRARY_CHANGED, false) == true) renderLibrary()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        makeUi()
        acceptShare(intent)
        openTab(intent)
        if (savedInstanceState == null) checkForUpdate(silent = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptShare(intent)
        openTab(intent)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, receiver, IntentFilter(DownloadService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        queueTab?.text = queueLabel()
        if (tab == "queue") renderQueue() else renderLibrary()
    }

    /** The download notification opens the app on the "قيد التنزيل" tab. */
    private fun openTab(intent: Intent?) {
        val target = intent?.getStringExtra("tab") ?: return
        intent.removeExtra("tab")
        saveDraft()
        tab = target
        makeUi()
    }

    private fun queueLabel() = Downloads.activeCount().let { if (it > 0) "قيد التنزيل ($it)" else "قيد التنزيل" }

    override fun onStop() {
        unregisterReceiver(receiver)
        super.onStop()
    }

    private fun makeUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(30), dp(20), dp(32)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(20), dp(30) + bars.top, dp(20), dp(32) + bars.bottom)
            insets
        }
        scroll.addView(root)
        setContentView(scroll)
        root.addView(text("وصلة", 33, Color.WHITE, true))
        root.addView(text("نزّل ما تملك حق حفظه واستمع إليه بطريقتك", 14, soft, false).apply { setPadding(0, dp(3), 0, dp(26)) })
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("download" to "تحميل", "queue" to queueLabel(), "audio" to "الصوتيات", "video" to "الفيديوهات").forEach { (key, label) ->
            val tabButton = button(label, key == tab) { saveDraft(); tab = key; makeUi() }.apply { textSize = 12f; setPadding(dp(2), 0, dp(2), 0) }
            if (key == "queue") queueTab = tabButton
            tabs.addView(tabButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(6) })
        }
        root.addView(tabs)
        root.addView(space(20))
        when (tab) {
            "download" -> renderDownload()
            "queue" -> renderQueuePage()
            else -> renderShelf()
        }
    }

    private fun renderQueuePage() {
        root.addView(text("قيد التنزيل", 23, Color.WHITE, true))
        root.addView(text("الملفات التي تُنزَّل الآن أو تنتظر دورها.", 14, soft, false).apply { setPadding(0, dp(8), 0, dp(20)) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        renderQueue()
    }

    private fun renderQueue() {
        if (!::list.isInitialized || tab != "queue") return
        list.removeAllViews()
        val jobs = Downloads.jobs.toList().asReversed()
        if (jobs.isEmpty()) {
            list.addView(text("لا توجد تنزيلات حالياً", 14, soft, false).apply { setPadding(0, dp(20), 0, 0) })
            return
        }
        if (jobs.any { !it.active }) {
            list.addView(button("مسح المنتهية", false) { Downloads.clearFinished(); renderQueue() },
                LinearLayout.LayoutParams(-1, dp(42)))
        }
        jobs.forEach { job ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(card); setPadding(dp(15), dp(13), dp(15), dp(13)) }
            box.addView(text(job.title, 15, Color.WHITE, true))
            val kindLabel = if (job.kind == "audio") "صوت" else "فيديو"
            val state = when (job.state) {
                DownloadJob.State.QUEUED -> "في الانتظار"
                DownloadJob.State.RUNNING -> "جاري التنزيل: ${job.percent}٪"
                DownloadJob.State.DONE -> "اكتمل وحُفظ في الهاتف"
                DownloadJob.State.FAILED -> job.message
                DownloadJob.State.CANCELED -> "أُلغي"
            }
            box.addView(text("$kindLabel · $state", 12, soft, false).apply { setPadding(0, dp(4), 0, 0) })
            if (job.state == DownloadJob.State.RUNNING) {
                box.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100; progress = job.percent; isIndeterminate = job.percent == 0
                    progressTintList = android.content.res.ColorStateList.valueOf(accent)
                }, LinearLayout.LayoutParams(-1, dp(14)).apply { topMargin = dp(8) })
            }
            if (job.active) {
                box.addView(button("إلغاء", false) {
                    ContextCompat.startForegroundService(this, Intent(this, DownloadService::class.java)
                        .setAction(DownloadService.ACTION_CANCEL).putExtra(DownloadService.EXTRA_ID, job.id))
                }, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(10) })
            }
            list.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
    }

    private fun renderDownload() {
        root.addView(text("رابط يوتيوب", 18, Color.WHITE, true))
        root.addView(text("الصق الرابط هنا، أو شارك المقطع من يوتيوب إلى وصلة.", 13, soft, false).apply { setPadding(0, dp(5), 0, dp(12)) })
        link = EditText(this).apply {
            hint = "https://youtube.com/watch?v=..."
            setHintTextColor(soft)
            setTextColor(Color.WHITE)
            textSize = 15f
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            background = rounded(card)
            setPadding(dp(15), dp(13), dp(15), dp(13))
            setText(draftUrl)
        }
        root.addView(link, LinearLayout.LayoutParams(-1, dp(55)))
        root.addView(space(12))
        root.addView(button("فحص الرابط", false) { inspect() }, LinearLayout.LayoutParams(-1, dp(48)))
        titleView = text(if (foundUrl.isEmpty() || foundUrl != draftUrl.trim()) "" else foundTitle, 15, Color.WHITE, true).apply { setPadding(0, dp(15), 0, dp(12)) }
        root.addView(titleView)
        root.addView(text("نوع الملف", 17, Color.WHITE, true))
        val options = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(10)) }
        val video = button("فيديو", kind == "video") { saveDraft(); kind = "video"; makeUi() }
        val audio = button("صوت فقط", kind == "audio") { saveDraft(); kind = "audio"; makeUi() }
        options.addView(video, LinearLayout.LayoutParams(0, dp(47), 1f).apply { marginEnd = dp(9) })
        options.addView(audio, LinearLayout.LayoutParams(0, dp(47), 1f))
        root.addView(options)
        root.addView(text(if (kind == "video") "الجودة (حد أعلى)" else "جودة الصوت MP3", 17, Color.WHITE, true))
        quality = Spinner(this).apply { background = rounded(card); setPadding(dp(10), 0, dp(10), 0) }
        val labels = if (kind == "video") listOf("الأفضل المتاح", "1080p", "720p", "480p", "360p") else listOf("192 kbps", "128 kbps", "64 kbps")
        quality.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply { setTextColor(Color.WHITE) }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        root.addView(quality, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(10) })
        root.addView(space(17))
        root.addView(button("ابدأ التنزيل  ↓", true) { download() }, LinearLayout.LayoutParams(-1, dp(55)))
        status = text("جاهز للتنزيل", 14, soft, false).apply { setPadding(0, dp(15), 0, 0) }
        root.addView(status)
        root.addView(space(28))
        root.addView(text("آخر الملفات", 18, Color.WHITE, true))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        renderLibrary()
        root.addView(space(28))
        root.addView(button("التحقق من التحديثات (الإصدار ${Updater.currentVersion(this)})", false) { checkForUpdate(silent = false) },
            LinearLayout.LayoutParams(-1, dp(46)))
    }

    private fun checkForUpdate(silent: Boolean) {
        if (silent && updateChecked) return
        updateChecked = true
        if (!silent) status.text = "جاري التحقق من التحديثات..."
        Thread {
            try {
                val release = Updater.latest()
                val current = Updater.currentVersion(this)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    when {
                        release != null && Updater.isNewer(release.version, current) -> offerUpdate(release)
                        !silent -> status.text = "أنت تستخدم أحدث إصدار ($current)"
                    }
                }
            } catch (e: Exception) {
                if (!silent) runOnUiThread { status.text = "تعذّر التحقق من التحديثات: ${e.message ?: "تحقق من الإنترنت"}" }
            }
        }.start()
    }

    private fun offerUpdate(release: Updater.Release) {
        AlertDialog.Builder(this)
            .setTitle("إصدار جديد: ${release.version}")
            .setMessage(release.notes.ifBlank { "يتوفر إصدار أحدث من وصلة." }.take(1500))
            .setPositiveButton("تحديث") { _, _ -> installUpdate(release) }
            .setNegativeButton("لاحقاً", null)
            .show()
    }

    private fun installUpdate(release: Updater.Release) {
        if (::status.isInitialized) status.text = "جاري تنزيل التحديث..."
        Thread {
            try {
                Updater.downloadAndInstall(this, release) { percent ->
                    runOnUiThread { if (::status.isInitialized) status.text = "جاري تنزيل التحديث: $percent٪" }
                }
                runOnUiThread { if (::status.isInitialized) status.text = "اتبع خطوات التثبيت على الشاشة" }
            } catch (e: Exception) {
                runOnUiThread { if (::status.isInitialized) status.text = "تعذّر تنزيل التحديث: ${e.message ?: "حاول مجدداً"}" }
            }
        }.start()
    }

    private fun renderShelf() {
        root.addView(text(if (tab == "audio") "مكتبتي الصوتية" else "مكتبة الفيديو", 23, Color.WHITE, true))
        root.addView(text(if (tab == "audio") "رتّب المقاطع بالسهمين وشغّلها بالترتيب." else "شاهد المقاطع المحفوظة بدون اتصال.", 14, soft, false)
            .apply { setPadding(0, dp(8), 0, dp(20)) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        renderLibrary()
    }

    private fun renderLibrary() {
        if (!::list.isInitialized || tab == "queue") return
        list.removeAllViews()
        val items = LibraryStore.all(this).filter { tab == "download" || it.kind == tab }.let { if (tab == "download") it.take(4) else it }
        if (items.isEmpty()) {
            list.addView(text("لا توجد مقاطع محفوظة بعد", 14, soft, false).apply { setPadding(0, dp(20), 0, 0) })
            return
        }
        items.forEach { item ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(card); setPadding(dp(15), dp(13), dp(15), dp(13)) }
            box.addView(text(item.title, 15, Color.WHITE, true))
            box.addView(text(if (item.kind == "audio") "مقطع صوتي" else "فيديو", 12, soft, false))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
            actions.addView(button("تشغيل", true) { startActivity(Intent(this, PlayerActivity::class.java).putExtra("id", item.id)) }, LinearLayout.LayoutParams(0, dp(40), 2f).apply { marginEnd = dp(7) })
            if (item.kind == "audio" && tab == "audio") {
                actions.addView(button("↑", false) { LibraryStore.move(this, item.id, -1); renderLibrary() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(7) })
                actions.addView(button("↓", false) { LibraryStore.move(this, item.id, 1); renderLibrary() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(7) })
            }
            actions.addView(button("حذف", false) {
                AlertDialog.Builder(this).setMessage("حذف هذا المقطع من الهاتف؟")
                    .setPositiveButton("حذف") { _, _ -> LibraryStore.remove(this, item.id); renderLibrary() }
                    .setNegativeButton("إلغاء", null).show()
            }, LinearLayout.LayoutParams(0, dp(40), 1f))
            box.addView(actions)
            list.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
    }

    private fun inspect(thenDownload: Boolean = false) {
        val url = validUrl(link.text.toString()) ?: run { status.text = "ضع رابط يوتيوب صحيح"; return }
        status.text = "جاري تحديث أداة التنزيل وفحص الرابط..."
        Thread {
            try {
                ExtractorSetup.ensureCurrent(this)
                val info = YoutubeDL.getInstance().getInfo(url)
                runOnUiThread {
                    foundUrl = url
                    foundTitle = info.title ?: "مقطع جديد"
                    // Only show the title if the field still holds the URL we inspected.
                    if (validUrl(link.text.toString()) == url) {
                        titleView.text = foundTitle
                        if (thenDownload) download() else status.text = "اختر النوع والجودة ثم ابدأ التنزيل"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "تعذّر فحص الرابط: ${e.message ?: "حاول مجدداً"}" }
            }
        }.start()
    }

    private fun download() {
        val url = validUrl(link.text.toString()) ?: run { status.text = "ضع رابط يوتيوب صحيح"; return }
        if (url != foundUrl) { inspect(thenDownload = true); return }
        val selected = if (kind == "video") listOf("best", "1080", "720", "480", "360")[quality.selectedItemPosition] else listOf("192", "128", "64")[quality.selectedItemPosition]
        val command = Intent(this, DownloadService::class.java).putExtra("url", url)
            .putExtra("kind", kind).putExtra("quality", selected).putExtra("title", foundTitle)
        try {
            // Android 8-9 need this to save into the public Music/Movies folders.
            if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
            }
            ContextCompat.startForegroundService(this, command)
            status.text = "أضيف إلى قائمة التنزيل؛ تابع التقدم من تبويب «قيد التنزيل»"
        } catch (e: Exception) { status.text = "تعذّر بدء التنزيل: ${e.message}" }
    }

    private fun acceptShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || !::link.isInitialized) return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE).find(shared)?.value ?: return
        if (validUrl(url) == null) return
        tab = "download"
        draftUrl = url
        makeUi()
        inspect()
    }

    private fun saveDraft() {
        if (::link.isInitialized && tab == "download") draftUrl = link.text.toString()
    }

    private fun validUrl(text: String): String? {
        val url = text.trim()
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (uri.scheme !in listOf("http", "https")) return null
        if (host !in listOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtu.be")) return null
        return url
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun button(label: String, selected: Boolean, click: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(if (selected) bg else Color.WHITE)
        gravity = Gravity.CENTER; background = rounded(if (selected) accent else card)
        setOnClickListener { click() }
    }
    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
