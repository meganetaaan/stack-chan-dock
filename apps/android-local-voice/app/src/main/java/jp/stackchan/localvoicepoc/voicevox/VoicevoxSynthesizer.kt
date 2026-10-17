package jp.stackchan.localvoicepoc.voicevox

import android.content.Context
import android.os.Build
import jp.hiroshiba.voicevoxcore.AccelerationMode
import jp.hiroshiba.voicevoxcore.blocking.Onnxruntime
import jp.hiroshiba.voicevoxcore.blocking.OpenJtalk
import jp.hiroshiba.voicevoxcore.blocking.Synthesizer
import jp.hiroshiba.voicevoxcore.blocking.VoiceModelFile
import jp.stackchan.localvoicepoc.piper.SpeechSynthesizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.UUID

class VoicevoxSynthesizer(private val context: Context) : SpeechSynthesizer {
    private val lock = Any()
    private val pcm = VoicevoxPcmFlow { text ->
        synchronized(lock) {
            check(loaded) { "VOICEVOX is not loaded" }
            requireNotNull(engine).tts(text, 3).perform()
        }
    }
    private val releases = NativeReleaseTracker()
    private val diagnosticGeneration = SynthesisGeneration()
    private var engine: Synthesizer? = null
    private var openJtalk: OpenJtalk? = null
    private var modelId: UUID? = null
    @Volatile private var loaded = false
    override val isLoaded get() = loaded
    val runtimeAvailable get() = Build.VERSION.SDK_INT >= 29

    suspend fun load(installation: VoicevoxInstallation, threads: Int = 4) = withContext(Dispatchers.IO) {
        check(runtimeAvailable) { "VOICEVOX requires Android 10 (API 29) or later" }
        check(installation.isVerified()) { "VOICEVOX assets failed verification; prepare again" }
        require(threads in listOf(0, 1, 2, 4))
        close()
        releases.awaitRelease()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        synchronized(lock) {
            // Absolute path isolates this runtime from RunAnywhere and Piper's ORT libraries.
            val ort = Onnxruntime.loadOnce().filename(
                File(context.applicationInfo.nativeLibraryDir, "libvoicevox_onnxruntime.so").absolutePath,
            ).perform()
            val jtalk = OpenJtalk(installation.dictionary.absolutePath)
            openJtalk = jtalk
            val synth = Synthesizer.builder(ort, jtalk)
                .accelerationMode(AccelerationMode.CPU).cpuNumThreads(threads).build()
            engine = synth
            VoiceModelFile(installation.model.absolutePath).use { model ->
                synth.loadVoiceModel(model).perform()
                modelId = model.id
            }
            loaded = true
        }
    }

    override fun synthesize(text: String) = pcm.synthesize(text)

    suspend fun diagnosticChunk(text: String, timing: TtsTiming) = withContext(Dispatchers.Default) {
        val request = diagnosticGeneration.snapshot()
        timing.event("workStart")
        val coroutine = kotlinx.coroutines.currentCoroutineContext()
        val wav = synchronized(lock) {
            check(loaded) { "VOICEVOX is not loaded" }
            val synth = requireNotNull(engine)
            val query = timing.interval("query") { synth.createAudioQuery(text, 3) }
            timing.value("prePhonemeLength", "${query.prePhonemeLength} s")
            coroutine.ensureActive()
            checkCurrent(request)
            timing.interval("synthesisWav") { synth.synthesis(query, 3).perform() }
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        checkCurrent(request)
        val chunk = timing.interval("wavDecode") { VoicevoxWav.decode(wav) }
        timing.value("audioFrames", chunk.samples.size.toString())
        val onset = chunk.samples.indexOfFirst { kotlin.math.abs(it.toInt()) >= 100 }
        timing.value("pcmAbs100Offset", if (onset < 0) "none" else "${onset * 1000.0 / chunk.sampleRate} ms (threshold, not audible onset)")
        chunk
    }
    private fun checkCurrent(request: Long) {
        if (!diagnosticGeneration.isCurrent(request)) throw kotlinx.coroutines.CancellationException("stale diagnosis")
    }
    suspend fun releaseAndAwait() = withContext(Dispatchers.IO) {
        close()
        releases.awaitRelease()
    }
    override fun cancel() { pcm.cancel(); diagnosticGeneration.cancel() }
    override fun close() {
        cancel()
        synchronized(lock) { closeLocked() }
    }
    private fun closeLocked() {
        loaded = false
        modelId?.let { engine?.unloadVoiceModel(it) }
        modelId = null
        engine?.let { releases.retire(it) }
        openJtalk?.let { releases.retire(it) }
        engine = null
        openJtalk = null
        // Core Java 0.17 releases Synthesizer/OpenJtalk handles through finalizers;
        // model unloading is explicit, but process memory reclamation needs device measurement.
    }
}
