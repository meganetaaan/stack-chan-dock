package jp.stackchan.localvoicepoc.voicevox

import android.content.Context
import jp.stackchan.localvoicepoc.piper.PiperInstallation
import jp.stackchan.localvoicepoc.piper.PiperPlusReflectionSynthesizer
import jp.stackchan.localvoicepoc.piper.SpeechSynthesizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class TtsBackend(val label: String) {
    PIPER("Piper 1.13"), PIPER2("Piper 2.0.1"), VOICEVOX("VOICEVOX:ずんだもん"), SANO("sanoTTS"), POCKET("Pocket 日本語");
    val isPiper get() = this == PIPER || this == PIPER2
}

class SelectableSynthesizer(context: Context) : SpeechSynthesizer {
    private val piper = PiperPlusReflectionSynthesizer(context)
    private val piper2 = PiperPlusReflectionSynthesizer(context, "com.piperplus2.PiperPlus", jp.stackchan.localvoicepoc.BuildConfig.PIPER_PLUS_2_AAR_PRESENT)
    private val voicevox = VoicevoxSynthesizer(context)
    private val sano = jp.stackchan.localvoicepoc.sano.SanoSynthesizer()
    private val pocket = jp.stackchan.localvoicepoc.pocket.PocketSynthesizer {
        val memory = android.app.ActivityManager.MemoryInfo().also {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it)
        }
        check(!memory.lowMemory && memory.availMem >= 1536L * 1024 * 1024) { "Pocket読み込みには1.5GiB以上の空きメモリが必要です。他のアプリを閉じて再試行してください。" }
    }
    val pocketAvailable get() = android.os.Build.VERSION.SDK_INT >= 28 && jp.stackchan.localvoicepoc.BuildConfig.POCKET_RUNTIME_PRESENT
    @Volatile private var selected: SpeechSynthesizer = piper
    val runtimeAvailable get() = piper.runtimeAvailable
    val piper2Available get() = piper2.runtimeAvailable
    val voicevoxAvailable get() = voicevox.runtimeAvailable
    val sanoAvailable get() = sano.runtimeAvailable
    override val isLoaded get() = selected.isLoaded
    suspend fun select(backend: TtsBackend) = withContext(Dispatchers.IO) {
        cancel()
        selected.close()
        selected = when (backend) { TtsBackend.PIPER -> piper; TtsBackend.PIPER2 -> piper2; TtsBackend.VOICEVOX -> voicevox; TtsBackend.SANO -> sano; TtsBackend.POCKET -> pocket }
    }
    suspend fun load(installation: PiperInstallation) {
        val engine = selected as? PiperPlusReflectionSynthesizer ?: error("Select a Piper runtime first")
        engine.load(installation)
    }
    fun withTiming(timing: TtsTiming): SpeechSynthesizer {
        val delegate = selected
        return object : SpeechSynthesizer {
            override val isLoaded get() = delegate.isLoaded
            override fun synthesize(text: String) = if (delegate is PiperPlusReflectionSynthesizer) delegate.synthesize(text,timing) else delegate.synthesize(text)
            override fun cancel() = delegate.cancel()
            override fun close() = delegate.close()
        }
    }
    suspend fun loadVoicevox(installation: VoicevoxInstallation) = voicevox.load(installation)
    suspend fun loadSano(installation: jp.stackchan.localvoicepoc.sano.SanoInstallation) = sano.load(installation)
    suspend fun loadPocket(installation: jp.stackchan.localvoicepoc.pocket.PocketInstallation) = pocket.load(installation)
    suspend fun encodePocketVoice(pcm: FloatArray, encoderDirectory: java.io.File): dev.irte.voice.PocketVoiceState {
        check(selected === pocket); return pocket.encodeVoice(pcm, encoderDirectory)
    }
    suspend fun selectPocketVoice(voice: dev.irte.voice.PocketVoiceState?, commit: () -> Unit) {
        check(selected === pocket); pocket.selectVoice(voice, commit)
    }
    val diagnosticVoicevox get() = voicevox
    override fun synthesize(text: String) = selected.synthesize(text)
    override fun cancel() = selected.cancel()
    override fun close() { piper.close(); piper2.close(); voicevox.close(); sano.close(); pocket.close() }
}
