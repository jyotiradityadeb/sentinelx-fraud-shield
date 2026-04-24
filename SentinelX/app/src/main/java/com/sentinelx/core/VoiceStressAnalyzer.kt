package com.sentinelx.core

/*
 * Privacy design:
 * - only F0, jitter, zero-crossing-rate and related numeric features are computed
 * - raw PCM is immediately discarded after each analysis window
 * - no audio is stored or transmitted
 */

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

data class VoiceStressEvent(
    val avgScore: Float,
    val peakScore: Float,
    val windowCount: Int,
)

class MovingAverage(private val size: Int) {
    private val values = ArrayDeque<Float>(size)
    private var sum = 0f

    fun add(value: Float): Float {
        if (values.size >= size) {
            sum -= values.removeFirst()
        }
        values.addLast(value)
        sum += value
        return current()
    }

    fun current(): Float = if (values.isEmpty()) 0f else sum / values.size
}

class VoiceStressAnalyzer {
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var currentStressScore: Float = 0f

    @Volatile
    var isAnalyzing: Boolean = false
        private set

    private val scoreSmoother = MovingAverage(5)
    private val pitchHistory = ArrayDeque<Float>(5)
    private val scoreHistory = mutableListOf<Float>()
    private val f0History = mutableListOf<Float>()

    fun startAnalyzing() {
        if (isAnalyzing) return
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuffer <= 0) return

        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBuffer * 4,
        )

        recorder?.startRecording()
        isAnalyzing = true
        worker = thread(start = true, name = "VoiceStressAnalyzer") {
            val localRecorder = recorder ?: return@thread
            val readBuffer = ShortArray(WINDOW_SAMPLES)

            while (isAnalyzing) {
                var filled = 0
                while (filled < WINDOW_SAMPLES && isAnalyzing) {
                    val read = localRecorder.read(readBuffer, filled, WINDOW_SAMPLES - filled)
                    if (read <= 0) break
                    filled += read
                }
                if (filled < WINDOW_SAMPLES) continue
                analyzeWindow(readBuffer)
            }
        }
    }

    fun stopAnalyzing(): VoiceStressEvent {
        isAnalyzing = false
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { worker?.join(300) }
        worker = null

        val avg = if (scoreHistory.isEmpty()) 0f else scoreHistory.average().toFloat()
        val peak = scoreHistory.maxOrNull() ?: 0f
        val result = VoiceStressEvent(avgScore = avg, peakScore = peak, windowCount = scoreHistory.size)

        scoreHistory.clear()
        f0History.clear()
        pitchHistory.clear()
        currentStressScore = 0f

        return result
    }

    fun getCurrentStressScore(): Float = currentStressScore

    private fun analyzeWindow(samples: ShortArray) {
        val rms = computeRms(samples)
        val zcr = computeZeroCrossingRate(samples)
        val f0 = estimateFundamental(samples)
        if (f0 > 0f) {
            f0History.add(f0)
            if (pitchHistory.size >= 5) pitchHistory.removeFirst()
            pitchHistory.addLast(f0)
        }
        val pitchVariance = computePitchVariance()
        val jitter = computeJitter()

        val score = clamp01(
            0.3f * (zcr / 0.15f) +
                0.4f * (jitter / 0.05f) +
                0.3f * (pitchVariance / 30f),
        )

        currentStressScore = scoreSmoother.add(score)
        scoreHistory.add(currentStressScore)

        // Samples are local and discarded immediately after processing.
        samples.fill(0.toShort())
        if (rms <= 0.0001f) return
    }

    private fun computeRms(samples: ShortArray): Float {
        var sumSquares = 0.0
        for (s in samples) {
            val normalized = s / 32768.0
            sumSquares += normalized * normalized
        }
        return sqrt(sumSquares / samples.size).toFloat()
    }

    private fun computeZeroCrossingRate(samples: ShortArray): Float {
        var crossings = 0
        for (i in 1 until samples.size) {
            val prev = samples[i - 1]
            val curr = samples[i]
            if ((prev >= 0 && curr < 0) || (prev < 0 && curr >= 0)) crossings++
        }
        return crossings.toFloat() / samples.size.toFloat()
    }

    private fun estimateFundamental(samples: ShortArray): Float {
        val minLag = SAMPLE_RATE / MAX_F0.toInt()
        val maxLag = SAMPLE_RATE / MIN_F0.toInt()
        var bestLag = 0
        var bestCorr = Double.NEGATIVE_INFINITY

        for (lag in minLag..maxLag) {
            var corr = 0.0
            var i = 0
            while (i + lag < samples.size) {
                corr += samples[i].toDouble() * samples[i + lag].toDouble()
                i++
            }
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }
        return if (bestLag > 0) SAMPLE_RATE.toFloat() / bestLag else 0f
    }

    private fun computePitchVariance(): Float {
        if (pitchHistory.size < 2) return 0f
        val mean = pitchHistory.average().toFloat()
        val variance = pitchHistory.map { (it - mean) * (it - mean) }.average().toFloat()
        return variance
    }

    private fun computeJitter(): Float {
        if (f0History.size < 2) return 0f
        var sum = 0f
        var count = 0
        for (i in 1 until f0History.size) {
            val prev = f0History[i - 1]
            val curr = f0History[i]
            if (prev > 0f) {
                sum += abs(curr - prev) / prev
                count++
            }
        }
        return if (count == 0) 0f else sum / count
    }

    private fun clamp01(value: Float): Float = value.coerceIn(0f, 1f)

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val WINDOW_SAMPLES = 32_000
        private const val MIN_F0 = 50f
        private const val MAX_F0 = 400f
    }
}
