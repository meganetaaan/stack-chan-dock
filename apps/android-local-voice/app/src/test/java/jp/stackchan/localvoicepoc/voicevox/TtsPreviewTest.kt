package jp.stackchan.localvoicepoc.voicevox

import jp.stackchan.localvoicepoc.SdkBootstrap
import jp.stackchan.localvoicepoc.audio.PcmAudioSink
import jp.stackchan.localvoicepoc.piper.SpeechSynthesizer
import jp.stackchan.localvoicepoc.ui.MainUiState
import jp.stackchan.localvoicepoc.ui.UsbConnectionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TtsPreviewTest {
    private class Sink : PcmAudioSink {
        val calls = mutableListOf<String>()
        override suspend fun begin(sampleRate: Int) { calls += "begin:$sampleRate" }
        override suspend fun write(samples: ShortArray) { calls += "write:${samples.size}" }
        override suspend fun finish() { calls += "finish" }
        override fun stop() { calls += "abort" }
    }
    private fun synth(block: suspend kotlinx.coroutines.flow.FlowCollector<SpeechSynthesizer.AudioChunk>.() -> Unit) =
        object : SpeechSynthesizer {
            override val isLoaded = true
            override fun synthesize(text: String) = flow {
                assertEquals(TTS_PREVIEW_TEXT, text)
                block()
            }
            override fun cancel() = Unit
            override fun close() = Unit
        }

    @Test fun previewUsesBackendSampleRateAndFinishes() = runBlocking {
        for (rate in listOf(22050, 24000)) {
            val sink = Sink()
            playTtsPreview(synth { emit(SpeechSynthesizer.AudioChunk(rate, shortArrayOf(12,-23))) }, sink)
            assertEquals(listOf("begin:$rate", "write:2", "finish"), sink.calls)
        }
    }

    @Test fun stopDuringSynthesisNeverStartsPlayback() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val sink = Sink()
        val task = async {
            playTtsPreview(synth {
                started.complete(Unit)
                hold.await()
                emit(SpeechSynthesizer.AudioChunk(24000, shortArrayOf(12)))
            }, sink)
        }
        started.await()
        task.cancelAndJoin()
        assertEquals(listOf("abort"), sink.calls)
    }

    @Test fun failureAfterAudioAbortsPlayback() = runBlocking {
        val sink = Sink()
        try {
            playTtsPreview(synth {
                emit(SpeechSynthesizer.AudioChunk(24000, shortArrayOf(12)))
                error("synthetic failure")
            }, sink)
            fail("Expected failure")
        } catch (_: IllegalStateException) { }
        assertEquals(listOf("begin:24000", "write:1", "abort"), sink.calls)
    }

    @Test fun previewBlocksBackendMutationsAndConversation() {
        val state = MainUiState(ttsBackend = TtsBackend.VOICEVOX, voicevoxLoaded = true,
            modelsReady = true, sdkStatus = SdkBootstrap.Status.Ready, usbStatus = UsbConnectionStatus.READY)
        assertTrue(state.pipelineReady)
        assertFalse(state.copy(ttsPreviewRunning = true).pipelineReady)
        assertFalse(state.copy(ttsPreviewRunning = true).canStartModelMutation)
        assertFalse(state.copy(voicevoxDiagnosticRunning = true).canStartModelMutation)
        assertFalse(state.copy(voicevoxDiagnosticRunning = true).pipelineReady)
    }
    @Test fun timingOrdersRequestPcmGenerationAndPlaybackCompletion() = runBlocking {
        var time=0L
        val timing=TtsTiming("preview",clock={time})
        val sink=object : PcmAudioSink {
            override suspend fun begin(sampleRate: Int) { time+=10_000_000 }
            override suspend fun write(samples: ShortArray) { time+=20_000_000 }
            override suspend fun finish() { time+=30_000_000 }
            override fun stop() = Unit
        }
        playTtsPreview(synth { time=5_000_000; emit(SpeechSynthesizer.AudioChunk(22050,shortArrayOf(1,2))) },sink,timing)
        val metrics=timing.snapshot()
        assertEquals("0.000 ms",metrics["generationRequested"])
        assertEquals("5.000 ms",metrics["firstPcm"])
        assertEquals("35.000 ms",metrics["generationComplete"])
        assertEquals("65.000 ms",metrics["playbackComplete"])
        assertEquals("2",metrics["audioFrames"])
        assertEquals("5.000 ms",metrics["generationToFirstPcm"])
        assertEquals("65.000 ms",metrics["generationToCompletion"])
    }
    @Test fun failureIsRecordedWithoutClaimingPlaybackCompletion() = runBlocking {
        val timing=TtsTiming("failure")
        runCatching { playTtsPreview(synth { error("synthetic failure") },Sink(),timing) }
        assertEquals("IllegalStateException",timing.snapshot()["errorType"])
        assertTrue(timing.snapshot().containsKey("failed"))
        assertFalse(timing.snapshot().containsKey("playbackComplete"))
    }
}
