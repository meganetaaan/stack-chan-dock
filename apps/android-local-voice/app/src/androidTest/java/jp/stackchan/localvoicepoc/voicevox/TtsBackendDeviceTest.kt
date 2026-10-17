package jp.stackchan.localvoicepoc.voicevox

import android.os.Build
import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import jp.hiroshiba.voicevoxcore.blocking.Onnxruntime
import jp.stackchan.localvoicepoc.BuildConfig
import jp.stackchan.localvoicepoc.piper.PiperAssetStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Compiled only during development. Running it on a phone requires separate authorization. */
class TtsBackendDeviceTest {
    @Test fun bothNativeRuntimesLoadWithoutModels() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        assertTrue(BuildConfig.PIPER_PLUS_AAR_PRESENT)
        Class.forName("com.piperplus.PiperPlusNative")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = Onnxruntime.loadOnce().filename(
            File(context.applicationInfo.nativeLibraryDir, "libvoicevox_onnxruntime.so").absolutePath,
        ).perform()
        assertNotNull(runtime)
    }

    @Test fun switchesPiperVoicevoxAndPiperWithUserPreparedAssets() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val piper = PiperAssetStore(context)
        val voicevox = VoicevoxAssetStore(context)
        assumeTrue("Prepare both backends in the app first", piper.current().isComplete && voicevox.isReady())
        val synth = SelectableSynthesizer(context)
        val measurements = mutableListOf("LLM coexistence and audible USB playback are not measured here")
        try {
            for (backend in listOf(TtsBackend.PIPER, TtsBackend.VOICEVOX, TtsBackend.PIPER)) {
                synth.select(backend)
                if (backend == TtsBackend.PIPER) synth.load(piper.current()) else synth.loadVoicevox(voicevox.current())
                assertTrue(synth.isLoaded)
                val chunks = synth.synthesize(TTS_PREVIEW_TEXT).toList()
                assertTrue(chunks.isNotEmpty())
                assertTrue(chunks.any { chunk -> chunk.samples.any { it != 0.toShort() } })
                measurements += "backend=$backend; rate=${chunks.first().sampleRate}; pssKiB=${Debug.getPss()}"
            }
        } finally { synth.close() }
        val directory = context.getExternalFilesDir("voice-diagnostics") ?: context.filesDir
        directory.mkdirs()
        File(directory, "tts-backend-switching.txt").writeText(measurements.joinToString("\n"))
    }
}
