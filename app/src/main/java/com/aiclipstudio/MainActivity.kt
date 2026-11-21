package com.aiclipstudio

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.aiclipstudio.analysis.GameVideoAnalyzer
import com.aiclipstudio.analysis.HighlightCandidate
import com.aiclipstudio.export.ClipExporter
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var importButton: Button
    private lateinit var analyzeButton: Button
    private lateinit var previewButton: Button
    private lateinit var exportButton: Button
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private lateinit var editor: LinearLayout
    private lateinit var startInput: EditText
    private lateinit var endInput: EditText
    private lateinit var portraitCrop: CheckBox
    private lateinit var fullHD: CheckBox
    private lateinit var progress: ProgressBar

    private val exporter by lazy { ClipExporter(this) }
    private var source: Uri? = null
    private var suggestions: List<HighlightCandidate> = emptyList()
    private var analyzedMs = 0L
    private var chosen = -1
    private var exporting = false
    private var generation = 0
    private var stopAt: Long? = null

    private val stopPreview = object : Runnable {
        override fun run() {
            val stop = stopAt ?: return
            if (player.currentPosition >= stop) {
                player.pause()
                stopAt = null
            } else ui.postDelayed(this, 150)
        }
    }

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) load(uri)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float = 15f, color: Int = Color.WHITE): TextView {
        return TextView(this).apply {
            setText(text)
            setTextColor(color)
            textSize = size
            setPadding(0, dp(7), 0, dp(7))
        }
    }

    private fun addButton(container: LinearLayout, title: String, action: () -> Unit): Button {
        return Button(this).apply {
            isAllCaps = false
            text = title
            setOnClickListener { action() }
            container.addView(this, LinearLayout.LayoutParams(-1, dp(50)).apply {
                bottomMargin = dp(8)
            })
        }
    }

    private fun numberInput(hintText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(13, 17, 26)
        window.navigationBarColor = Color.rgb(13, 17, 26)

        val root = ScrollView(this).apply {
            setFillViewport(true)
            setBackgroundColor(Color.rgb(15, 20, 31))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }
        root.addView(content)
        setContentView(root)
        content.addView(label("AI CLIP STUDIO", 25f))
        content.addView(label("Игровые хайлайты • анализ прямо на телефоне",
            14f, Color.rgb(160, 183, 209)))

        player = ExoPlayer.Builder(this).build()
        playerView = PlayerView(this).apply {
            player = this@MainActivity.player
            useController = true
            setBackgroundColor(Color.BLACK)
        }
        content.addView(playerView, LinearLayout.LayoutParams(-1, dp(224)).apply {
            topMargin = dp(12)
            bottomMargin = dp(16)
        })

        importButton = addButton(content, "1. Выбрать игровое видео") {
            openDocument.launch(arrayOf("video/*"))
        }
        analyzeButton = addButton(content, "2. Найти интересные моменты") {
            analyze()
        }
        analyzeButton.isEnabled = false
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        content.addView(progress, LinearLayout.LayoutParams(-1, dp(12)))
        status = label("Загрузи запись игры или стрима.", 14f,
            Color.rgb(177, 198, 215))
        content.addView(status)

        content.addView(label("Найденные клипы", 19f))
        results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(results)

        editor = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(12), 0, dp(8))
        }
        content.addView(editor)
        editor.addView(label("Начало и конец клипа (секунды)", 16f))
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        startInput = numberInput("Начало")
        endInput = numberInput("Конец")
        timeRow.addView(startInput, LinearLayout.LayoutParams(0, dp(52), 1f))
        timeRow.addView(endInput, LinearLayout.LayoutParams(0, dp(52), 1f))
        editor.addView(timeRow)

        portraitCrop = CheckBox(this).apply {
            text = "Кадрировать 9:16 (по центру)"
            setTextColor(Color.WHITE)
            isChecked = false
            setOnCheckedChangeListener { _, checked ->
                playerView.resizeMode = if (checked)
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        }
        editor.addView(portraitCrop)
        editor.addView(label("Без кадрирования сохраняется весь игровой экран с полями.",
            13f, Color.rgb(169, 180, 199)))
        fullHD = CheckBox(this).apply {
            text = "Экспорт 1080×1920 (иначе 720×1280)"
            setTextColor(Color.WHITE)
            isChecked = false
        }
        editor.addView(fullHD)
        previewButton = addButton(editor, "▶ Предпросмотр отрывка") { preview() }
        exportButton = addButton(editor, "3. Экспортировать MP4") { export() }
    }

    private fun load(uri: Uri) {
        generation++
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) { }
        source = uri
        chosen = -1
        suggestions = emptyList()
        analyzedMs = 0L
        results.removeAllViews()
        editor.visibility = View.GONE
        progress.progress = 0
        analyzeButton.isEnabled = true
        stopAt = null
        ui.removeCallbacks(stopPreview)
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        status.text = "Видео загружено. Нажми «Найти интересные моменты»."
    }

    private fun analyze() {
        val uri = source ?: return
        val current = ++generation
        analyzeButton.isEnabled = false
        importButton.isEnabled = false
        status.text = "Запущен локальный анализ…"
        progress.progress = 0
        worker.execute {
            try {
                val data = GameVideoAnalyzer(applicationContext).analyze(uri) { p, message ->
                    runOnUiThread {
                        if (!isDestroyed && current == generation) {
                            progress.progress = p
                            status.text = message
                        }
                    }
                }
                runOnUiThread {
                    if (isDestroyed || current != generation) return@runOnUiThread
                    suggestions = data.clips
                    analyzedMs = data.analyzedMs
                    status.text = "Найдено ${data.clips.size} клипов. " +
                        if (data.aiEnabled) "YAMNet работает локально."
                        else "YAMNet недоступна, использованы звуковые пики и кадры."
                    if (data.analyzedMs < data.durationMs)
                        status.append(" Проверены только первые два часа.")
                    displayResults()
                    importButton.isEnabled = true
                    analyzeButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isDestroyed && current == generation) {
                        status.text = "Ошибка анализа: ${e.message}"
                        importButton.isEnabled = true
                        analyzeButton.isEnabled = true
                    }
                }
            }
        }
    }

    private fun displayResults() {
        results.removeAllViews()
        suggestions.forEachIndexed { index, clip ->
            addButton(results,
                "${index + 1}. ${time(clip.startMs)} — ${time(clip.endMs)}  •  ${clip.score}/100\n${clip.reason}"
            ) { choose(index) }
        }
        if (suggestions.isNotEmpty()) choose(0)
    }

    private fun choose(index: Int) {
        chosen = index
        val clip = suggestions[index]
        editor.visibility = View.VISIBLE
        startInput.setText(String.format(Locale.US, "%.1f", clip.startMs / 1000.0))
        endInput.setText(String.format(Locale.US, "%.1f", clip.endMs / 1000.0))
        player.pause()
        stopAt = null
        ui.removeCallbacks(stopPreview)
        player.seekTo(clip.startMs)
    }

    private fun editedClip(): HighlightCandidate? {
        val original = suggestions.getOrNull(chosen) ?: return null
        val start = startInput.text.toString().replace(',', '.').toDoubleOrNull()
        val end = endInput.text.toString().replace(',', '.').toDoubleOrNull()
        if (start == null || end == null || start < 0 || end <= start + 0.5 ||
            end * 1000.0 > analyzedMs + 500.0) {
            Toast.makeText(this, "Проверь время начала и конца клипа", Toast.LENGTH_LONG).show()
            return null
        }
        return original.copy(
            startMs = (start * 1000.0).roundToLong(),
            endMs = (end * 1000.0).roundToLong().coerceAtMost(analyzedMs)
        )
    }

    private fun preview() {
        val clip = editedClip() ?: return
        ui.removeCallbacks(stopPreview)
        stopAt = clip.endMs
        player.seekTo(clip.startMs)
        player.play()
        ui.postDelayed(stopPreview, 150)
    }

    private fun export() {
        if (exporting) return
        val uri = source ?: return
        val clip = editedClip() ?: return
        exporting = true
        importButton.isEnabled = false
        analyzeButton.isEnabled = false
        exportButton.isEnabled = false
        progress.isIndeterminate = true
        status.text = "Экспортируем MP4. Не закрывай приложение."
        exporter.export(uri, clip, portraitCrop.isChecked, fullHD.isChecked,
            onSuccess = {
                finishExport("Готово! Клип сохранён: Фильмы / AI Clip Studio")
                Toast.makeText(this, "Видео в галерее", Toast.LENGTH_LONG).show()
            },
            onFailure = { message -> finishExport(message) }
        )
    }

    private fun finishExport(message: String) {
        exporting = false
        importButton.isEnabled = true
        analyzeButton.isEnabled = source != null
        exportButton.isEnabled = true
        progress.isIndeterminate = false
        progress.progress = 100
        status.text = message
    }

    private fun time(ms: Long): String {
        val seconds = ms / 1000L
        return String.format(Locale.US, "%02d:%02d:%02d",
            seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L)
    }

    override fun onDestroy() {
        generation++
        stopAt = null
        ui.removeCallbacks(stopPreview)
        worker.shutdownNow()
        player.release()
        super.onDestroy()
    }
}
