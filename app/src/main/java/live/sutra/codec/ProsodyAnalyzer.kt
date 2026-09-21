package live.sutra.codec

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.sqrt

object ProsodyAnalyzer {

    const val SAMPLE_RATE = 16_000
    private const val HOP = 160
    private const val WINDOW = 400
    private const val MIN_F0 = 70
    private const val MAX_F0 = 400
    private const val YIN_THRESHOLD = 0.15
    private const val PAUSE_FRAMES = 15
    private const val LONG_PAUSE_FRAMES = 40

    data class Frames(val rmsDb: DoubleArray, val f0: DoubleArray, val speech: BooleanArray)

    data class Analysis(val prosody: Prosody, val frames: Frames, val speechSeconds: Double)

    fun analyze(audio: FloatArray, text: String): Analysis {
        val words = Words.split(text)
        val frames = measure(audio)
        val n = frames.rmsDb.size
        val speechIndices = frames.speech.indices.filter { frames.speech[it] }
        if (words.isEmpty() || speechIndices.isEmpty()) {
            return Analysis(Prosody.neutral(words.size), frames, 0.0)
        }

        val first = speechIndices.first()
        val last = speechIndices.last()

        val pauses = mutableListOf<IntRange>()
        var runStart = -1
        for (i in first..last) {
            if (!frames.speech[i]) {
                if (runStart < 0) runStart = i
            } else if (runStart >= 0) {
                if (i - runStart >= PAUSE_FRAMES) pauses += runStart until i
                runStart = -1
            }
        }
        val inPause = BooleanArray(n)
        pauses.forEach { r -> r.forEach { inPause[it] = true } }
        val timeline = (first..last).filter { !inPause[it] }

        val weights = words.map(Words::weight)
        val totalWeight = weights.sum().toDouble()
        val boundaries = IntArray(words.size + 1)
        var cumulative = 0
        for (i in words.indices) {
            cumulative += weights[i]
            boundaries[i + 1] = (timeline.size * cumulative / totalWeight).toInt()
        }
        boundaries[words.size] = timeline.size

        val pauseAfter = IntArray(words.size)
        for (pause in pauses) {
            val speechBefore = timeline.count { it < pause.first }
            val nearest = (1 until words.size).minByOrNull { abs(boundaries[it] - speechBefore) } ?: continue
            pauseAfter[nearest - 1] = max(pauseAfter[nearest - 1], pause.last - pause.first + 1)
        }

        val voicedF0 = speechIndices.map { frames.f0[it] }.filter { it > 0 }
        val medianF0 = median(voicedF0)
        val speechDb = speechIndices.map { frames.rmsDb[it] }
        val meanDb = speechDb.average()
        val framesPerWeight = timeline.size / totalWeight

        val spans = words.indices.map { w ->
            timeline.subList(boundaries[w], max(boundaries[w] + 1, boundaries[w + 1]).coerceAtMost(timeline.size))
        }
        val wordF0s = spans.map { span -> span.map { frames.f0[it] }.filter { it > 0 } }

        val reference = median(wordF0s.filter { it.size >= 3 }.map { log2(median(it)!!) })

        val levels = words.indices.map { w ->
            val span = spans[w]
            val wordF0 = wordF0s[w]
            val wordDb = if (span.isEmpty()) meanDb else span.map { frames.rmsDb[it] }.average()

            val pitch = when {
                reference == null || wordF0.size < 3 -> Prosody.Pitch.MID
                slopeSemitones(wordF0) > 3.0 -> Prosody.Pitch.RISING
                else -> {
                    val st = 12 * (log2(median(wordF0)!!) - reference)
                    when { st < -2 -> Prosody.Pitch.LOW; st > 2 -> Prosody.Pitch.HIGH; else -> Prosody.Pitch.MID }
                }
            }
            val relativeDb = wordDb - meanDb
            val energy = when {
                relativeDb < -6 -> Prosody.Energy.SOFT
                relativeDb > 8 -> Prosody.Energy.SHOUT
                relativeDb > 3 -> Prosody.Energy.LOUD
                else -> Prosody.Energy.NORMAL
            }
            val timing = when {
                pauseAfter[w] >= LONG_PAUSE_FRAMES -> Prosody.Timing.LONG_PAUSE
                pauseAfter[w] >= PAUSE_FRAMES -> Prosody.Timing.SHORT_PAUSE
                span.size > 1.6 * framesPerWeight * weights[w] -> Prosody.Timing.LENGTHENED
                else -> Prosody.Timing.PLAIN
            }
            Prosody.Word(pitch, energy, timing)
        }

        val speechSeconds = timeline.size * HOP / SAMPLE_RATE.toDouble()
        val rate = if (speechSeconds > 0) totalWeight / speechSeconds else 0.0
        val loudnessBucket = Prosody.loudnessBucketFor(meanDb)

        val prosody = Prosody(
            pitchBucket = Prosody.pitchBucketFor(medianF0),
            rateBucket = Prosody.rateBucketFor(rate),
            urgency = urgency(levels, rate, voicedF0, loudnessBucket),
            loudness = loudnessBucket,
            words = levels,
        )
        return Analysis(prosody, frames, speechSeconds)
    }

    private fun urgency(words: List<Prosody.Word>, rate: Double, voicedF0: List<Double>, loudness: Int): Int {
        var score = 0
        val loudShare = words.count { it.energy >= Prosody.Energy.LOUD }.toDouble() / words.size
        score += (loudShare * 3).toInt()
        if (rate >= 6) score++
        if (rate >= 7) score++
        if (voicedF0.size >= 10) {
            val sorted = voicedF0.sorted()
            val range = 12 * log2(sorted[(sorted.size * 0.9).toInt()] / sorted[(sorted.size * 0.1).toInt()])
            if (range >= 10) score++
            if (range >= 14) score++
        }
        if (loudness >= 6) score++
        return score.coerceIn(0, 7)
    }

    fun measure(audio: FloatArray): Frames {
        val count = max(0, (audio.size - WINDOW) / HOP + 1)
        val rmsDb = DoubleArray(count)
        val rms = DoubleArray(count)
        for (f in 0 until count) {
            var sum = 0.0
            val base = f * HOP
            for (i in base until base + WINDOW) sum += audio[i] * audio[i].toDouble()
            rms[f] = sqrt(sum / WINDOW)
            rmsDb[f] = 20 * log10(rms[f] + 1e-9)
        }
        val peak = rms.maxOrNull() ?: 0.0

        val threshold = max(0.004, peak * 0.06)
        val speech = BooleanArray(count) { rms[it] >= threshold }
        val f0 = DoubleArray(count) { if (speech[it]) yin(audio, it * HOP) else 0.0 }
        return Frames(rmsDb, f0, speech)
    }

    internal fun yin(audio: FloatArray, start: Int): Double {
        val tauMin = SAMPLE_RATE / MAX_F0
        val tauMax = SAMPLE_RATE / MIN_F0
        if (start + WINDOW + tauMax + 1 > audio.size) return 0.0

        val cmnd = DoubleArray(tauMax + 2)
        var runningSum = 0.0
        cmnd[0] = 1.0
        for (tau in 1..tauMax + 1) {
            var d = 0.0
            for (j in 0 until WINDOW) {
                val diff = audio[start + j] - audio[start + j + tau].toDouble()
                d += diff * diff
            }
            runningSum += d
            cmnd[tau] = if (runningSum == 0.0) 1.0 else d * tau / runningSum
        }
        var tau = tauMin
        while (tau <= tauMax) {
            if (cmnd[tau] < YIN_THRESHOLD) {
                while (tau + 1 <= tauMax && cmnd[tau + 1] < cmnd[tau]) tau++

                val a = cmnd[tau - 1]; val b = cmnd[tau]; val c = cmnd[tau + 1]
                val denominator = a - 2 * b + c
                val shift = if (denominator != 0.0) (0.5 * (a - c) / denominator).coerceIn(-1.0, 1.0) else 0.0
                return SAMPLE_RATE / (tau + shift)
            }
            tau++
        }
        return 0.0
    }

    private fun slopeSemitones(f0: List<Double>): Double {
        val third = max(1, f0.size / 3)
        val head = median(f0.subList(0, third))!!
        val tail = median(f0.subList(f0.size - third, f0.size))!!
        return 12 * log2(tail / head)
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
