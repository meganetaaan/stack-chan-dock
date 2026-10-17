package jp.stackchan.localvoicepoc.pocket

import dev.irte.voice.PocketEngine
import dev.irte.voice.PocketRuntime
import jp.stackchan.localvoicepoc.piper.SpeechSynthesizer
import kotlin.math.roundToInt
import jp.stackchan.localvoicepoc.voicevox.SynthesisGeneration
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** One resident runtime; bounded PCM handoff to the existing phone/USB sinks. */
class PocketSynthesizer internal constructor(
    private val open: (File, () -> Unit) -> PocketEngine,
    private val preflight: () -> Unit,
) : SpeechSynthesizer {
    constructor(preflight: () -> Unit) : this({ root, active -> PocketRuntime(root, 1, active) }, preflight)
    private val nativeLock = Any()
    private val controlLock = Any()
    private val utterance = Mutex()
    private val generation = SynthesisGeneration()
    @Volatile private var engine: PocketEngine? = null
    @Volatile private var loaded = false
    private var activeRun: Any? = null
    private var cancelHandoff: (() -> Unit)? = null
    override val isLoaded get() = loaded

    suspend fun load(installation: PocketInstallation) = withContext(Dispatchers.IO) {
        close()
        val request = generation.snapshot(); val coroutine = currentCoroutineContext()
        fun active() { coroutine.ensureActive(); checkCurrent(request) }
        PocketAssets.verify(installation.filesRoot, installation.manifest, ::active)
        active(); preflight()
        synchronized(nativeLock) {
            active()
            val candidate = open(installation.directory, ::active)
            try {
                synchronized(controlLock) { active(); engine = candidate; loaded = true }
            } catch (error: Throwable) { candidate.close(); throw error }
        }
    }

    override fun synthesize(text: String): kotlinx.coroutines.flow.Flow<SpeechSynthesizer.AudioChunk> {
        val request = generation.snapshot()
        return callbackFlow {
            val run = Any()
            val worker = launch(Dispatchers.IO) {
                try {
                    require(text.length <= 1000 && text.none { it == '\u0000' }) { "Pocket input is invalid or exceeds 1000 characters" }
                    if (text.isBlank()) { close(); return@launch }
                    utterance.withLock {
                        val coroutine = currentCoroutineContext()
                        fun active() { coroutine.ensureActive(); checkCurrent(request) }
                        synchronized(nativeLock) {
                            active()
                            val runtime = checkNotNull(engine) { "Pocket is not loaded; import and prepare its model ZIP first" }
                            check(loaded)
                            synchronized(controlLock) {
                                active(); runtime.prepareRequest(::active); activeRun = run
                                cancelHandoff = { this@callbackFlow.cancel(CancellationException("Pocket stopped")) }
                            }
                            var samples = 0L
                            try {
                                runtime.generate(text) { pcm ->
                                    active()
                                    check(pcm.size == 1920) { "Pocket frame must contain 1920 samples" }
                                    check(pcm.all { it.isFinite() }) { "Pocket decoder returned nonfinite PCM" }
                                    samples += pcm.size
                                    check(samples <= 24000L * 30) { "Pocket output exceeds 30 seconds" }
                                    // Preserve the exact Float-to-PCM16 conversion used by the accepted phone build.
                                    val pcm16 = ShortArray(pcm.size) { (pcm[it].coerceIn(-1f, 1f) * 32767).roundToInt().toShort() }
                                    trySendBlocking(SpeechSynthesizer.AudioChunk(24000, pcm16)).getOrThrow()
                                    active(); true
                                }
                                active()
                            } finally { synchronized(controlLock) { if (activeRun === run) { activeRun = null; cancelHandoff = null } } }
                        }
                    }
                    close()
                } catch (error: Throwable) { close(error) }
            }
            awaitClose {
                worker.cancel()
                synchronized(controlLock) { if (activeRun === run) engine?.cancel() }
            }
        }.buffer(0)
    }
    private fun checkCurrent(request: Long) {
        if (!generation.isCurrent(request)) throw CancellationException("Pocket request was stopped or replaced")
    }
    private suspend fun <T> voiceOperation(block: (PocketEngine, () -> Unit) -> T): T = withContext(Dispatchers.IO) {
        val request = generation.snapshot()
        utterance.withLock {
            val coroutine = currentCoroutineContext()
            fun active() { coroutine.ensureActive(); checkCurrent(request) }
            synchronized(nativeLock) {
                active(); val runtime = checkNotNull(engine); check(loaded)
                val run = Any()
                synchronized(controlLock) { active(); runtime.prepareRequest(::active); activeRun = run }
                try { block(runtime, ::active) }
                finally { synchronized(controlLock) { if (activeRun === run) activeRun = null } }
            }
        }
    }
    suspend fun encodeVoice(pcm: FloatArray, encoderDirectory: File) = voiceOperation { runtime, active ->
        active(); preflight(); runtime.encodeVoice(pcm, encoderDirectory)
    }
    suspend fun selectVoice(voice: dev.irte.voice.PocketVoiceState?, commit: () -> Unit) = voiceOperation { runtime, _ ->
        runtime.selectVoice(voice, commit)
    }
    override fun cancel() {
        synchronized(controlLock) {
            generation.cancel()
            if (activeRun != null) { engine?.cancel(); cancelHandoff?.invoke() }
        }
    }
    override fun close() {
        cancel()
        synchronized(nativeLock) {
            loaded = false
            val previous = engine; engine = null
            previous?.close()
        }
    }
}
