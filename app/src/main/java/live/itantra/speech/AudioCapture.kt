package live.itantra.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class AudioCapture {

    private var record: AudioRecord? = null
    @Volatile private var capturing = false
    private var thread: Thread? = null
    private val chunks = ArrayList<FloatArray>()
    @Volatile var peakLevel: Float = 0f
        private set

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        stop()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBuffer <= 0) {
            Log.e(TAG, "invalid min buffer size: $minBuffer")
            return false
        }
        val bufferBytes = max(minBuffer, SAMPLE_RATE * 4)

        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                bufferBytes,
            )
        }.getOrNull() ?: return false

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            Log.e(TAG, "AudioRecord failed to initialise")
            return false
        }

        synchronized(chunks) { chunks.clear() }
        peakLevel = 0f
        record = recorder
        capturing = true
        recorder.startRecording()

        thread = Thread {
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_URGENT_AUDIO
            )
            val buffer = FloatArray(READ_FRAMES)
            while (capturing) {
                val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read > 0) {
                    val copy = buffer.copyOf(read)
                    var peak = 0f
                    for (sample in copy) peak = max(peak, abs(sample))
                    peakLevel = peak
                    synchronized(chunks) { chunks.add(copy) }
                }
            }
        }.also { it.start() }
        return true
    }

    fun stopAndTrim(): FloatArray? {
        val audio = stop() ?: return null
        if (audio.size < SAMPLE_RATE / 4) return null
        return trimSilence(audio)
    }

    private fun stop(): FloatArray? {
        capturing = false
        thread?.join(500)
        thread = null
        record?.runCatching { stop() }
        record?.release()
        record = null

        val collected = synchronized(chunks) { chunks.toList().also { chunks.clear() } }
        if (collected.isEmpty()) return null
        val total = collected.sumOf { it.size }
        val out = FloatArray(total)
        var offset = 0
        for (chunk in collected) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val READ_FRAMES = 1600
        private const val TAG = "iTantraAudio"

        private const val PAD_MS = 200
        private const val WINDOW_MS = 20
        private const val SILENCE_FLOOR = 0.005f

        fun trimSilence(audio: FloatArray): FloatArray {
            val window = SAMPLE_RATE * WINDOW_MS / 1000
            if (audio.size <= window * 2) return audio

            val windows = audio.size / window
            var first = -1
            var last = -1
            for (w in 0 until windows) {
                var sum = 0.0
                val base = w * window
                for (i in base until base + window) sum += audio[i] * audio[i].toDouble()
                val rms = sqrt(sum / window).toFloat()
                if (rms >= SILENCE_FLOOR) {
                    if (first < 0) first = w
                    last = w
                }
            }
            if (first < 0) return audio

            val pad = SAMPLE_RATE * PAD_MS / 1000
            val start = max(0, first * window - pad)
            val end = min(audio.size, (last + 1) * window + pad)
            return if (start == 0 && end == audio.size) audio else audio.copyOfRange(start, end)
        }
    }
}
