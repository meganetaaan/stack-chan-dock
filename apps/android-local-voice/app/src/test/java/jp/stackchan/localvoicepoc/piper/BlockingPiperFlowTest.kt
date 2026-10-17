package jp.stackchan.localvoicepoc.piper

import jp.stackchan.localvoicepoc.voicevox.TtsTiming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BlockingPiperFlowTest {
    private fun chunk() = SpeechSynthesizer.AudioChunk(22050,shortArrayOf(-123,456))
    @Test fun cancelledRequestCannotBeRevivedByANewRequest() = runBlocking {
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val provider=BlockingPiperFlow { text ->
            if (text=="old") { entered.countDown(); check(release.await(5,TimeUnit.SECONDS)) }
            chunk()
        }
        val old=async { runCatching { provider.synthesize("old").toList() } }
        withContext(Dispatchers.IO) { check(entered.await(5,TimeUnit.SECONDS)) }
        provider.cancel()
        assertEquals(1,provider.synthesize("new").toList().size)
        release.countDown()
        assertTrue(old.await().exceptionOrNull() is CancellationException)
    }
    @Test fun flowCreatedBeforeCloseIsInvalidatedBeforeNativeWork() = runBlocking {
        var calls=0
        val provider=BlockingPiperFlow { calls++; chunk() }
        val pending=provider.synthesize("old")
        provider.cancel()
        assertTrue(runCatching { pending.toList() }.exceptionOrNull() is CancellationException)
        assertEquals(0,calls)
    }
    @Test fun rejectsExcessiveTextEmptyPcmAndWrongRate() = runBlocking {
        var calls=0
        val provider=BlockingPiperFlow { calls++; chunk() }
        for (text in listOf("あ".repeat(1001),"NUL\u0000")) {
            assertTrue(runCatching { provider.synthesize(text).toList() }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(0,calls)
        for (bad in listOf(SpeechSynthesizer.AudioChunk(0,shortArrayOf(1)),SpeechSynthesizer.AudioChunk(22050,shortArrayOf()))) {
            assertTrue(runCatching { BlockingPiperFlow { bad }.synthesize("test").toList() }.exceptionOrNull() is IllegalStateException)
        }
    }
    @Test fun recordsNativeStartDurationAndPreservesSignedPcm() = runBlocking {
        var clock=0L
        val timing=TtsTiming("Piper 2",clock={clock})
        val pcm=BlockingPiperFlow { clock=25_000_000L; chunk() }.synthesize("こんにちは",timing).toList().single()
        assertArrayEquals(shortArrayOf(-123,456),pcm.samples)
        assertEquals("0.000 ms",timing.snapshot()["nativeWorkStart"])
        assertEquals("25.000 ms",timing.snapshot()["nativeSynthesis"])
    }
    @Test fun piper2UsesExistingReadinessAndBlocksSwitchDuringPlayback() {
        val state=jp.stackchan.localvoicepoc.ui.MainUiState(ttsBackend=jp.stackchan.localvoicepoc.voicevox.TtsBackend.PIPER2,
            piperLoaded=true,modelsReady=true,sdkStatus=jp.stackchan.localvoicepoc.SdkBootstrap.Status.Ready,
            usbStatus=jp.stackchan.localvoicepoc.ui.UsbConnectionStatus.READY)
        assertTrue(state.pipelineReady)
        assertFalse(state.copy(piperLoaded=false,voicevoxLoaded=true,sanoLoaded=true).pipelineReady)
        assertFalse(state.copy(ttsPreviewRunning=true).canStartModelMutation)
        assertTrue(jp.stackchan.localvoicepoc.voicevox.TtsBackend.PIPER.isPiper)
        assertTrue(jp.stackchan.localvoicepoc.voicevox.TtsBackend.PIPER2.isPiper)
    }
}
