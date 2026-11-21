package com.aiclipstudio.analysis

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Decodes file audio; passes a one-second slice from every five seconds to YAMNet. */
internal class AudioEnergyExtractor(private val context: Context) {
    data class Features(val rms: DoubleArray, val aiEvents: DoubleArray, val aiEnabled: Boolean)

    fun extract(uri: Uri, seconds: Int, onProgress: (Int) -> Unit): Features {
        val levels = DoubleArray(seconds)
        val events = DoubleArray(seconds)
        val energy = DoubleArray(seconds)
        val counts = LongArray(seconds)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        val model = try {
            YamnetEventDetector(context)
        } catch (e: Exception) {
            Log.w("AIClipStudio", "YAMNet unavailable, use energy fallback", e)
            null
        }
        var aiEnabled = model != null
        val mlInput = FloatArray(16000)
        var currentWindow = -1
        var sampleEnd = 0

        fun flush() {
            if (currentWindow < 0 || model == null || !aiEnabled || sampleEnd < 12000) return
            try {
                val event = model.score(mlInput)
                for (i in currentWindow until minOf(seconds, currentWindow + 5))
                    events[i] = event
            } catch (e: Exception) {
                aiEnabled = false
                Log.w("AIClipStudio", "YAMNet inference failed", e)
            }
        }

        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return Features(levels, events, false)
            extractor.selectTrack(track)
            val sourceFormat = extractor.getTrackFormat(track)
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME)
                ?: return Features(levels, events, false)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(sourceFormat, null, null, 0)
            codec.start()
            started = true

            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var finished = false
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var rate = sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var stalls = 0
            var lastProgress = -1

            while (!finished && !Thread.currentThread().isInterrupted) {
                if (!inputEnded) {
                    val slot = codec.dequeueInputBuffer(10000)
                    if (slot >= 0) {
                        val input = codec.getInputBuffer(slot) ?: error("No decoder buffer")
                        input.clear()
                        val bytes = extractor.readSampleData(input, 0)
                        if (bytes < 0 || extractor.sampleTime / 1000000L >= seconds) {
                            codec.queueInputBuffer(
                                slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(slot, 0, bytes, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val slot = codec.dequeueOutputBuffer(bufferInfo, 10000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        else AudioFormat.ENCODING_PCM_16BIT
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        stalls++
                        if (inputEnded && stalls > 1000) break
                    }
                    else -> if (slot >= 0) {
                        stalls = 0
                        if (bufferInfo.size > 0 && rate > 0 && channels > 0) {
                            val buf = codec.getOutputBuffer(slot)?.duplicate()
                            if (buf != null) {
                                buf.order(ByteOrder.LITTLE_ENDIAN)
                                val floatPcm = encoding == AudioFormat.ENCODING_PCM_FLOAT
                                val bytesPerSample = if (floatPcm) 4 else 2
                                val frameBytes = channels * bytesPerSample
                                buf.position(bufferInfo.offset)
                                buf.limit(bufferInfo.offset + bufferInfo.size)
                                val frames = bufferInfo.size / frameBytes
                                val baseFrame = bufferInfo.presentationTimeUs * rate / 1000000L
                                val stride = maxOf(1, rate / 16000)

                                for (frame in 0 until frames step stride) {
                                    val pos = bufferInfo.offset + frame * frameBytes
                                    if (pos + bytesPerSample > buf.limit()) break
                                    val sample = if (floatPcm) buf.getFloat(pos).toDouble()
                                        else buf.getShort(pos) / 32768.0
                                    if (!sample.isFinite()) continue
                                    val absolute = baseFrame + frame
                                    val second = (absolute / rate).toInt()
                                    if (second !in levels.indices) continue
                                    energy[second] += sample * sample
                                    counts[second]++
                                    val window = second - second % 5
                                    if (window != currentWindow) {
                                        flush()
                                        currentWindow = window
                                        mlInput.fill(0f)
                                        sampleEnd = 0
                                    }
                                    if (aiEnabled && second % 5 == 0) {
                                        val frameInSecond = (absolute % rate).toInt()
                                        val index = (frameInSecond.toLong() * 16000 / rate).toInt()
                                        if (index in mlInput.indices) {
                                            mlInput[index] = sample.toFloat()
                                            sampleEnd = maxOf(sampleEnd, index + 1)
                                        }
                                    }
                                }
                                val sec = (bufferInfo.presentationTimeUs / 1000000L).toInt()
                                val p = (sec.toDouble() / seconds * 65).toInt().coerceIn(0, 65)
                                if (p > lastProgress && p % 3 == 0) {
                                    onProgress(p)
                                    lastProgress = p
                                }
                            }
                        }
                        finished = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(slot, false)
                    }
                }
            }
            flush()
            for (i in levels.indices)
                if (counts[i] > 0) levels[i] = sqrt(energy[i] / counts[i])
        } catch (e: Exception) {
            aiEnabled = false
            Log.w("AIClipStudio", "Audio decoding failed; falling back to video", e)
        } finally {
            if (started) try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
            try { model?.close() } catch (_: Exception) {}
        }
        return Features(levels, events, aiEnabled)
    }
}
