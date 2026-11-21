package com.aiclipstudio.export

import android.app.Activity
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.aiclipstudio.analysis.HighlightCandidate
import java.io.File
import java.util.UUID

/** Ensures audio and video are exported by the same Media3 timeline. */
class ClipExporter(private val activity: Activity) {
    private var transformer: Transformer? = null

    fun export(
        uri: Uri,
        clip: HighlightCandidate,
        cropToPortrait: Boolean,
        fullHd: Boolean,
        onSuccess: (Uri) -> Unit,
        onFailure: (String) -> Unit
    ) {
        require(clip.endMs > clip.startMs) { "Неверные границы клипа" }
        val width = if (fullHd) 1080 else 720
        val height = if (fullHd) 1920 else 1280
        val scale = if (cropToPortrait) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
            else Presentation.LAYOUT_SCALE_TO_FIT
        val item = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.startMs)
                    .setEndPositionMs(clip.endMs)
                    .build()
            )
            .build()
        val edits = EditedMediaItem.Builder(item)
            .setEffects(Effects(emptyList(), listOf(
                Presentation.createForWidthAndHeight(width, height, scale)
            )))
            .build()
        val temporary = File(activity.cacheDir, "clip_${UUID.randomUUID()}.mp4")
        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, result: ExportResult) {
                transformer = null
                Thread {
                    try {
                        val saved = saveToGallery(temporary)
                        activity.runOnUiThread { onSuccess(saved) }
                    } catch (e: Exception) {
                        activity.runOnUiThread {
                            onFailure("Не удалось сохранить видео: ${e.message}")
                        }
                    } finally {
                        temporary.delete()
                    }
                }.start()
            }

            override fun onError(
                composition: Composition,
                result: ExportResult,
                exception: ExportException
            ) {
                transformer = null
                temporary.delete()
                onFailure("Ошибка кодирования: ${exception.message}")
            }
        }
        try {
            transformer = Transformer.Builder(activity)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(listener)
                .build()
            transformer!!.start(edits, temporary.absolutePath)
        } catch (e: Exception) {
            transformer = null
            temporary.delete()
            onFailure("Не удалось начать экспорт: ${e.message}")
        }
    }

    private fun saveToGallery(file: File): Uri {
        val resolver = activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "AIClip_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/AI Clip Studio")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val output = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Не удалось создать файл в медиатеке")
        try {
            resolver.openOutputStream(output, "w")?.use { stream ->
                file.inputStream().use { input -> input.copyTo(stream) }
            } ?: error("Нет доступа к медиатеке")
            resolver.update(output, ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }, null, null)
            return output
        } catch (e: Exception) {
            resolver.delete(output, null, null)
            throw e
        }
    }
}
