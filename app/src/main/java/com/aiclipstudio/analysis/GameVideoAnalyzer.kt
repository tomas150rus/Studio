package com.aiclipstudio.analysis

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.ceil
import kotlin.math.min

class GameVideoAnalyzer(private val context: Context) {
    data class Result(
        val durationMs: Long,
        val analyzedMs: Long,
        val clips: List<HighlightCandidate>,
        val aiEnabled: Boolean
    )

    fun analyze(uri: Uri, onProgress: (Int, String) -> Unit): Result {
        val metadata = MediaMetadataRetriever()
        val duration = try {
            metadata.setDataSource(context, uri)
            metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: error("Не удалось прочитать длительность видео")
        } finally { metadata.release() }
        require(duration > 1000L) { "Видео слишком короткое" }

        // Prototype limit: scan the first two hours, not unlimited long streams.
        val analyzed = min(duration, 2L * 60L * 60L * 1000L)
        val seconds = ceil(analyzed / 1000.0).toInt()
        onProgress(0, "Анализируем звук и события…")
        val audio = AudioEnergyExtractor(context).extract(uri, seconds) { p ->
            onProgress(p, "Анализируем звуковые реакции… $p%")
        }
        onProgress(65, "Изучаем видеоряд…")
        val motion = FrameActivityExtractor(context).extract(uri, seconds) { p ->
            onProgress(p, "Анализ кадров… $p%")
        }
        onProgress(99, "Выбираем лучшие фрагменты…")
        val clips = HighlightRanker.rank(analyzed, audio.rms, motion, aiEvents = audio.aiEvents)
        onProgress(100, "Готово: найдено ${clips.size} фрагментов")
        return Result(duration, analyzed, clips, audio.aiEnabled)
    }
}
