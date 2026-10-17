package jp.stackchan.localvoicepoc.sano

import jp.stackchan.localvoicepoc.piper.SpeechSynthesizer
import jp.stackchan.localvoicepoc.voicevox.SynthesisGeneration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Calls are serialized across all native instances: upstream Japanese G2P has shared scratch. */
internal object NativeSano {
    private val initialization = runCatching { System.loadLibrary("stackchan_sano") }
    val available get() = initialization.isSuccess
    external fun open(path: String): Long
    external fun loadDictionary(handle: Long, path: String)
    external fun startJapanese(handle: Long, utf8: ByteArray): Int
    external fun pull(handle: Long): FloatArray
    external fun close(handle: Long)
}

internal interface SanoRuntime {
    val available: Boolean
    fun open(model: File, dictionary: File): Long
    fun start(handle: Long, text: String): Int
    fun pull(handle: Long): FloatArray
    fun close(handle: Long)
}

internal class JniSanoRuntime : SanoRuntime {
    override val available get() = NativeSano.available
    override fun open(model: File, dictionary: File): Long = synchronized(nativeLock) {
        check(available) { "sanoTTS runtime is unavailable on this device" }
        val handle = NativeSano.open(model.absolutePath)
        check(handle != 0L) { "sanoTTS could not allocate an engine" }
        try { NativeSano.loadDictionary(handle, dictionary.absolutePath); handle }
        catch (error: Throwable) { NativeSano.close(handle); throw error }
    }
    override fun start(handle: Long, text: String) = synchronized(nativeLock) {
        NativeSano.startJapanese(handle, text.toByteArray(Charsets.UTF_8))
    }
    override fun pull(handle: Long) = synchronized(nativeLock) { NativeSano.pull(handle) }
    override fun close(handle: Long) = synchronized(nativeLock) { NativeSano.close(handle) }
    private companion object { val nativeLock = Any() }
}

class SanoSynthesizer internal constructor(private val runtime: SanoRuntime) : SpeechSynthesizer {
    constructor() : this(JniSanoRuntime())
    private val nativeLock = Any()
    private val utterance = Mutex()
    private val generation = SynthesisGeneration()
    private var handle = 0L
    @Volatile private var loaded = false
    val runtimeAvailable get() = runtime.available
    override val isLoaded get() = loaded

    suspend fun load(installation: SanoInstallation) = withContext(Dispatchers.IO) {
        close()
        val request = generation.snapshot()
        val coroutine = currentCoroutineContext()
        check(installation.isVerified { coroutine.ensureActive() }) { "sanoTTS assets failed verification; prepare again" }
        synchronized(nativeLock) {
            coroutine.ensureActive()
            checkCurrent(request)
            val opened = runtime.open(installation.model, installation.dictionary)
            check(opened != 0L) { "sanoTTS could not allocate an engine" }
            try {
                coroutine.ensureActive()
                checkCurrent(request)
                handle = opened
                loaded = true
            } catch (error: Throwable) {
                runtime.close(opened)
                throw error
            }
        }
    }

    override fun synthesize(text: String): kotlinx.coroutines.flow.Flow<SpeechSynthesizer.AudioChunk> {
        val request = generation.snapshot()
        return flow {
            val segments = SanoTextPolicy.segments(text)
            if (segments.isEmpty()) return@flow
            utterance.withLock {
                var totalSamples = 0L
                for (segment in segments) {
                    currentCoroutineContext().ensureActive()
                    val frames = synchronized(nativeLock) {
                        checkCurrent(request)
                        check(loaded && handle != 0L) { "sanoTTS is not loaded; prepare its model and dictionary first" }
                        runtime.start(handle, segment)
                    }
                    require(frames > 0 && frames * 256L <= MAX_SAMPLES) { "sanoTTS utterance exceeds its audio limit" }
                    val expected = frames * 256L
                    var received = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val pcm = synchronized(nativeLock) { checkCurrent(request); runtime.pull(handle) }
                        currentCoroutineContext().ensureActive()
                        checkCurrent(request)
                        if (pcm.isEmpty()) break
                        received += pcm.size
                        totalSamples += pcm.size
                        check(received <= expected && totalSamples <= MAX_SAMPLES) { "sanoTTS PCM length exceeds its limit" }
                        emit(SpeechSynthesizer.AudioChunk(SAMPLE_RATE, SanoTextPolicy.pcm16(pcm)))
                    }
                    check(received == expected) { "sanoTTS PCM length differs from predicted frames" }
                }
            }
        }.flowOn(Dispatchers.Default).buffer(0)
    }

    private fun checkCurrent(request: Long) {
        if (!generation.isCurrent(request)) throw CancellationException("sanoTTS request was stopped or replaced")
    }
    override fun cancel() { generation.cancel() }
    override fun close() {
        cancel()
        synchronized(nativeLock) {
            loaded = false
            val previous = handle
            handle = 0L
            if (previous != 0L) runtime.close(previous)
        }
    }
    companion object {
        const val SAMPLE_RATE = 22_050
        private const val MAX_SAMPLES = SAMPLE_RATE * 120L
    }
}
