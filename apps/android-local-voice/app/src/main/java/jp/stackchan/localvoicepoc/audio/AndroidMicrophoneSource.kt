package jp.stackchan.localvoicepoc.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Caller checks/requests RECORD_AUDIO before opening this foreground-only source. */
class AndroidMicrophoneSource(
    override val sampleRate: Int = 16_000,
    private val chunkDurationMs: Int = 20,
    private val audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
) : PcmAudioSource {
    override val channelCount = 1
    override val bytesPerSample = 2
    private class Capture {
        val stopped = AtomicBoolean(false)
        var recorder: AudioRecord? = null
    }
    private val lock = Any()
    private var active: Capture? = null

    @SuppressLint("MissingPermission")
    override fun chunks(): Flow<ByteArray> = callbackFlow {
        val capture = Capture()
        synchronized(lock) { check(active == null) { "Microphone is already active" }; active = capture }
        try {
            val chunkBytes = sampleRate * bytesPerSample * chunkDurationMs / 1000
            val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "マイクを利用できません" }
            synchronized(lock) {
                check(!capture.stopped.get())
                val recorder = AudioRecord.Builder()
                    .setAudioSource(audioSource)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(maxOf(minimum * 2, chunkBytes * 4)).build()
                capture.recorder = recorder
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { "マイクを開始できません" }
                check(recorder.sampleRate == sampleRate) { "マイクの録音形式を確認できません" }
                recorder.startRecording()
            }
            val reader = launch(Dispatchers.IO) {
                try {
                    val recorder = checkNotNull(capture.recorder)
                    val buffer = ByteArray(chunkBytes)
                    while (isActive && !capture.stopped.get()) {
                        val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        when {
                            capture.stopped.get() -> break
                            count > 0 -> trySendBlocking(buffer.copyOf(count)).getOrThrow()
                            count < 0 -> error("マイクとの接続が切れました")
                        }
                    }
                    close()
                } catch (error: Throwable) { close(if (capture.stopped.get()) null else error) }
            }
            awaitClose { reader.cancel(); release(capture) }
        } finally { release(capture) }
    }.buffer(0)

    override fun stop() { synchronized(lock) { active }?.let(::release) }
    private fun release(capture: Capture) = synchronized(lock) {
        if (!capture.stopped.compareAndSet(false, true)) return@synchronized
        capture.recorder?.let {
            runCatching { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() }
            runCatching { it.release() }
        }
        capture.recorder = null
        if (active === capture) active = null
    }
}
