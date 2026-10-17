package jp.stackchan.localvoicepoc.voicevox

import android.os.Build
import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in device measurement after the user prepares assets; never downloads or accepts terms. */
class VoicevoxDeviceTest {
    @Test fun diagnosticQueryPathPreservesNormalPcm() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = VoicevoxAssetStore(context)
        assumeTrue("Prepare VOICEVOX in the app first", store.isReady())
        val synth = VoicevoxSynthesizer(context)
        try {
            synth.load(store.current())
            val ordinary = synth.synthesize(TTS_PREVIEW_TEXT).toList().single()
            val timing = TtsTiming("device diagnostic regression")
            val measured = synth.diagnosticChunk(TTS_PREVIEW_TEXT, timing)
            assertEquals(ordinary.sampleRate, measured.sampleRate)
            assertArrayEquals(ordinary.samples, measured.samples)
            assertTrue(timing.report().contains("query="))
            assertTrue(timing.report().contains("synthesisWav="))
            assertTrue(timing.report().contains("wavDecode="))
        } finally { synth.releaseAndAwait() }
    }
    @Test fun normalCpuSynthesisAndProcessMemory() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = VoicevoxAssetStore(context)
        assumeTrue("Prepare VOICEVOX in the app first", store.isReady())
        val synth = VoicevoxSynthesizer(context)
        val lines = mutableListOf("credit=${VoicevoxAssets.CREDIT}",
            "device=${Build.MANUFACTURER}/${Build.MODEL}; sdk=${Build.VERSION.SDK_INT}",
            "LLM coexistence is not measured by this standalone test")
        val before = Debug.getPss()
        val begin = System.nanoTime()
        try {
            synth.load(store.current())
            lines += "loadMs=${(System.nanoTime()-begin)/1_000_000}; beforePssKiB=$before; loadedPssKiB=${Debug.getPss()}"
            repeat(4) { index ->
                val start = System.nanoTime()
                val chunks = synth.synthesize("こんにちは。スタックちゃんです。").toList()
                assertEquals(1, chunks.size)
                assertEquals(24000, chunks.single().sampleRate)
                assertTrue(chunks.single().samples.any { it != 0.toShort() })
                lines += "iteration=$index; synthesisMs=${(System.nanoTime()-start)/1_000_000}; samples=${chunks.single().samples.size}; pssKiB=${Debug.getPss()}; nativeHeapBytes=${Debug.getNativeHeapAllocatedSize()}"
            }
        } finally { synth.close() }
        File(context.getExternalFilesDir("voice-diagnostics"), "voicevox-device-benchmark.txt")
            .writeText(lines.joinToString("\n"))
    }
}
