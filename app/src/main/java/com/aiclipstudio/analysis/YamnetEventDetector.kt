package com.aiclipstudio.analysis

import android.content.Context
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.components.containers.AudioData.AudioDataFormat
import com.google.mediapipe.tasks.core.BaseOptions

/** True, on-device ML inference. YAMNet detects sound categories, not game victories. */
internal class YamnetEventDetector(context: Context) : AutoCloseable {
    private val classifier = AudioClassifier.createFromOptions(
        context,
        AudioClassifier.AudioClassifierOptions.builder()
            .setBaseOptions(
                BaseOptions.builder().setModelAssetPath("yamnet.tflite").build()
            )
            .setRunningMode(RunningMode.AUDIO_CLIPS)
            .setMaxResults(12)
            .build()
    )
    private val format = AudioDataFormat.builder().setSampleRate(16000f).build()

    fun score(samples: FloatArray): Double {
        require(samples.size == 16000)
        val audio = AudioData.create(format, samples.size)
        audio.load(samples)
        val result = classifier.classify(audio)
        var best = 0.0
        for (resultWindow in result.classificationResults()) {
            for (group in resultWindow.classifications()) {
                for (category in group.categories()) {
                    val label = category.categoryName().lowercase()
                    val factor = when {
                        listOf("gunshot", "gunfire", "explosion", "blast", "cheering", "applause")
                            .any { it in label } -> 1.0
                        listOf("screaming", "scream", "shout", "yell", "laughter", "laugh")
                            .any { it in label } -> 0.9
                        listOf("crowd", "whoop", "gasp") .any { it in label } -> 0.6
                        else -> 0.0
                    }
                    best = maxOf(best, category.score().toDouble() * factor)
                }
            }
        }
        return best.coerceIn(0.0, 1.0)
    }

    override fun close() { classifier.close() }
}
