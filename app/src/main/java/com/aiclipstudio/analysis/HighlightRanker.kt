package com.aiclipstudio.analysis

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class HighlightCandidate(
    val startMs: Long,
    val endMs: Long,
    val score: Int,
    val reason: String
)

object HighlightRanker {
    fun rank(
        durationMs: Long,
        audioRms: DoubleArray,
        frameActivity: DoubleArray,
        maxClips: Int = 5,
        aiEvents: DoubleArray = DoubleArray(0)
    ): List<HighlightCandidate> {
        val seconds = (durationMs / 1000L).toInt().coerceAtLeast(1)
        if (seconds <= 12)
            return listOf(HighlightCandidate(0, durationMs, 50, "Короткий исходный ролик"))
        val audio = normalize(audioRms, seconds)
        val motion = normalize(frameActivity, seconds)
        val ml = DoubleArray(seconds) { i ->
            aiEvents.getOrElse(i) { 0.0 }.coerceIn(0.0, 1.0)
        }
        val active = audio.any { it > 0.05 } || motion.any { it > 0.05 } || ml.any { it > 0.05 }
        val clipSeconds = min(45, max(16, seconds / 3))
        val clipMs = min(durationMs, clipSeconds * 1000L)
        val desired = min(maxClips.coerceAtLeast(1), max(1, seconds / 55))
        if (!active) return (0 until desired).map { i ->
            val start = ((durationMs - clipMs) * (i + 1) / (desired + 1)).coerceAtLeast(0)
            HighlightCandidate(start, start + clipMs, 30, "Равномерная выборка")
        }
        val signals = DoubleArray(seconds) { i ->
            val previous = if (i >= 2) audio[i - 2] else audio[i]
            0.40 * audio[i] + 0.25 * motion[i] + 0.25 * ml[i] +
                0.10 * (audio[i] - previous).coerceAtLeast(0.0)
        }
        val prefix = DoubleArray(seconds + 1)
        for (i in signals.indices) prefix[i + 1] = prefix[i] + signals[i]
        data class Window(val from: Int, val to: Int, val value: Double)
        val proposals = mutableListOf<Window>()
        for (from in 0..max(0, seconds - clipSeconds) step 2) {
            val to = min(seconds, from + clipSeconds)
            val average = (prefix[to] - prefix[from]) / max(1, to - from)
            var peak = 0.0
            for (j in from until to) peak = max(peak, signals[j])
            proposals.add(Window(from, to, 0.7 * average + 0.3 * peak))
        }
        val chosen = mutableListOf<Window>()
        for (window in proposals.sortedByDescending { it.value }) {
            if (chosen.size >= desired) break
            if (chosen.none { it.from < window.to + 6 && window.from < it.to + 6 })
                chosen.add(window)
        }
        return chosen.sortedBy { it.from }.map { window ->
            val a = audio.slice(window.from until window.to).average()
            val m = motion.slice(window.from until window.to).average()
            val l = ml.slice(window.from until window.to).average()
            val reason = when {
                l > 0.08 && l > m * 0.4 -> "ИИ: звуковое событие"
                a > m * 1.4 -> "Звуковой всплеск"
                m > a * 1.4 -> "Динамичный видеоряд"
                else -> "Звук и движение"
            }
            HighlightCandidate(
                window.from * 1000L,
                min(durationMs, window.to * 1000L),
                (35 + window.value * 62).roundToInt().coerceIn(0, 100),
                reason
            )
        }
    }

    private fun normalize(input: DoubleArray, length: Int): DoubleArray {
        val positive = input.filter { it.isFinite() && it > 0.0 }.sorted()
        if (positive.isEmpty()) return DoubleArray(length)
        val low = positive[(positive.size * 0.35).toInt().coerceIn(0, positive.lastIndex)]
        val high = positive[(positive.size * 0.95).toInt().coerceIn(0, positive.lastIndex)]
        val delta = (high - low).coerceAtLeast(high * 0.15).coerceAtLeast(1e-8)
        return DoubleArray(length) { i ->
            val v = input.getOrElse(i) { 0.0 }
            if (v.isFinite()) ((v - low) / delta).coerceIn(0.0, 1.0) else 0.0
        }
    }
}
