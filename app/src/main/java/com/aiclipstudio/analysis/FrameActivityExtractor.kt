package com.aiclipstudio.analysis

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlin.math.abs
import kotlin.math.max

/** Samples small video frames to approximate gameplay movement and scene changes. */
internal class FrameActivityExtractor(private val context: Context) {
    fun extract(uri: Uri, seconds: Int, onProgress: (Int) -> Unit): DoubleArray {
        val activity = DoubleArray(seconds)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val step = max(3, seconds / 240)
            var previous: IntArray? = null
            var t = 0
            while (t < seconds && !Thread.currentThread().isInterrupted) {
                val frame = retriever.getScaledFrameAtTime(
                    t * 1000000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 64, 36
                )
                if (frame != null) {
                    val pixels = IntArray(frame.width * frame.height)
                    frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
                    frame.recycle()
                    val old = previous
                    if (old != null && old.size == pixels.size) {
                        var sum = 0.0
                        for (i in pixels.indices step 4) {
                            val p = pixels[i]
                            val q = old[i]
                            val luminanceP = ((p ushr 16) and 255) * 0.299 +
                                ((p ushr 8) and 255) * 0.587 + (p and 255) * 0.114
                            val luminanceQ = ((q ushr 16) and 255) * 0.299 +
                                ((q ushr 8) and 255) * 0.587 + (q and 255) * 0.114
                            sum += abs(luminanceP - luminanceQ)
                        }
                        val motion = (sum / max(1, pixels.size / 4) / 85.0)
                            .coerceIn(0.0, 1.0)
                        for (sec in t until minOf(seconds, t + step)) activity[sec] = motion
                    }
                    previous = pixels
                }
                onProgress(65 + (t.toDouble() / seconds * 34).toInt().coerceIn(0, 34))
                t += step
            }
        } catch (e: Exception) {
            Log.w("AIClipStudio", "No frame analysis, proceed with audio", e)
        } finally {
            retriever.release()
        }
        return activity
    }
}
