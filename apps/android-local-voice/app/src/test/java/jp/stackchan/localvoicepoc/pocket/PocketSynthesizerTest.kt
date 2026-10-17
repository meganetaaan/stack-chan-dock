package jp.stackchan.localvoicepoc.pocket

import dev.irte.voice.PocketEngine
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class PocketSynthesizerTest {
    private class Runtime : PocketEngine {
        var active: () -> Unit = {}
        var starts = 0; var closes = 0; var stops = 0
        var frames = 3; var pcm = FloatArray(1920) { .25f }
        var gate: (() -> Unit)? = null
        var voice: dev.irte.voice.PocketVoiceState? = null; var encodes = 0
        override fun encodeVoice(pcm: FloatArray, encoderDirectory: File): dev.irte.voice.PocketVoiceState {
            active(); encodes++; gate?.invoke(); active()
            val n=(pcm.size+1919)/1920+1
            return dev.irte.voice.PocketVoiceState(n,List(12){FloatArray(n*1024){.2f}})
        }
        override fun selectVoice(voice: dev.irte.voice.PocketVoiceState?, commit: () -> Unit) {active();commit();this.voice=voice}
        override fun prepareRequest(active: () -> Unit) { this.active = active }
        override fun generate(source: String, receive: (FloatArray) -> Boolean) {
            check(closes == 0); starts++; gate?.invoke()
            repeat(frames) { active(); check(receive(pcm)); active() }
        }
        override fun cancel() { stops++ }
        override fun close() { closes++ }
    }
    private suspend fun fixture(test: suspend (PocketInstallation) -> Unit) {
        val root = Files.createTempDirectory("pocket-test-").toFile()
        val directory = PocketAssets.directory(root).apply { mkdirs() }
        val bytes = byteArrayOf(1, 2, 3)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val manifest = """{"files":[{"name":"model.onnx","bytes":3,"sha256":"$hash"}]}"""
        File(directory,"model.onnx").writeBytes(bytes); File(directory,"manifest.json").writeText(manifest)
        try { test(PocketInstallation(root, manifest)) } finally { root.deleteRecursively() }
    }
    @Test fun residentRuntimeIsReusedAndProduces24kPcm16() = runBlocking { fixture { installation ->
        val runtime = Runtime(); var opens = 0
        val synth = PocketSynthesizer({ _, _ -> opens++; runtime }, {})
        assertFalse(synth.isLoaded)
        assertTrue(runCatching { synth.synthesize("未準備").toList() }.exceptionOrNull() is IllegalStateException)
        synth.load(installation)
        repeat(2) {
            val chunks = synth.synthesize("こんにちは").toList()
            assertEquals(3, chunks.size)
            assertTrue(chunks.all { it.sampleRate == 24000 && it.samples.size == 1920 && it.samples.all { sample -> sample == 8192.toShort() } })
        }
        assertEquals(1, opens); assertEquals(2, runtime.starts)
        synth.close(); synth.close(); assertEquals(1, runtime.closes); assertFalse(synth.isLoaded)
    } }
    @Test fun referenceOperationsUseSameCancellationAndKeepSelectionOnFailure() = runBlocking {fixture { installation ->
        val runtime=Runtime();var memoryOk=true
        val synth=PocketSynthesizer({_,_->runtime},{check(memoryOk){"low memory"}});synth.load(installation)
        val voice=synth.encodeVoice(FloatArray(120000){.1f},installation.directory)
        synth.selectVoice(voice){};assertSame(voice,runtime.voice)
        assertTrue(runCatching{synth.selectVoice(null){error("disk failure")}}.isFailure);assertSame(voice,runtime.voice)
        memoryOk=false;assertTrue(runCatching{synth.encodeVoice(FloatArray(120000),installation.directory)}.isFailure)
        assertEquals(1,runtime.encodes);assertSame(voice,runtime.voice)
        memoryOk=true;runtime.gate={synth.cancel()}
        assertTrue(runCatching{synth.encodeVoice(FloatArray(120000),installation.directory)}.exceptionOrNull() is CancellationException)
        runtime.gate=null;synth.selectVoice(null){};assertNull(runtime.voice)
        assertEquals(3,synth.synthesize("もう一度").toList().size);synth.close()
    } }
    @Test fun cancelThenRespeakDoesNotReviveOldPcmOrReload() = runBlocking { fixture { installation ->
        val runtime = Runtime(); val synth = PocketSynthesizer({ _, _ -> runtime }, {})
        synth.load(installation)
        val old = synth.synthesize("古い発話"); synth.cancel()
        assertTrue(runCatching { old.toList() }.exceptionOrNull() is CancellationException)
        assertEquals(0, runtime.starts)
        runtime.gate = { synth.cancel() }
        assertTrue(runCatching { synth.synthesize("途中停止").toList() }.exceptionOrNull() is CancellationException)
        assertTrue(runtime.stops > 0)
        runtime.gate = null
        assertEquals(3, synth.synthesize("再発話").toList().size)
        assertEquals(0, runtime.closes); synth.close()
    } }
    @Test fun parallelUtterancesAreSerializedWithoutOverwritingState() = runBlocking { fixture { installation ->
        val runtime = Runtime(); val synth = PocketSynthesizer({ _, _ -> runtime }, {}); synth.load(installation)
        val first = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val a = async { var n = 0; synth.synthesize("一").collect { if (n++ == 0) { first.complete(Unit); release.await() } }; n }
        withTimeout(5000) { first.await() }
        val b = async { synth.synthesize("二").toList() }
        yield(); assertEquals(1, runtime.starts)
        release.complete(Unit)
        assertEquals(3, withTimeout(5000) { a.await() }); assertEquals(3, withTimeout(5000) { b.await() }.size)
        synth.close()
    } }
    @Test fun cappedOutputFailsInsteadOfReportingSuccessfulCompletion() = runBlocking { fixture { installation ->
        val runtime = Runtime(); runtime.frames = 376
        val synth = PocketSynthesizer({ _, _ -> runtime }, {}); synth.load(installation)
        var received = 0
        val failure = runCatching { synth.synthesize("上限").collect { received += it.samples.size } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException); assertEquals(24000 * 30, received)
        runtime.frames = 3
        assertEquals(3, synth.synthesize("次").toList().size)
        synth.close()
    } }
    @Test fun switchClosesOnlyAfterNativeCallExitsAndInvalidatesQueuedWork() = runBlocking { fixture { installation ->
        val runtime = Runtime(); val synth = PocketSynthesizer({ _, _ -> runtime }, {}); synth.load(installation)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        runtime.gate = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val old = async { runCatching { synth.synthesize("切替前").toList() } }
        withContext(Dispatchers.IO) { check(entered.await(5, TimeUnit.SECONDS)) }
        val queued = synth.synthesize("切替中")
        val closing = async(Dispatchers.IO) { synth.close() }
        yield(); assertEquals(0, runtime.closes); release.countDown()
        assertTrue(withTimeout(5000) { old.await() }.exceptionOrNull() is CancellationException)
        withTimeout(5000) { closing.await() }
        assertTrue(runCatching { queued.toList() }.exceptionOrNull() is CancellationException)
        assertEquals(1, runtime.starts); assertEquals(1, runtime.closes)
    } }
    @Test fun stopReleasesAProducerWaitingForPlaybackCredit() = runBlocking { fixture { installation ->
        val runtime = Runtime(); runtime.frames = 20
        val synth = PocketSynthesizer({ _, _ -> runtime }, {}); synth.load(installation)
        val received = CompletableDeferred<Unit>(); val hold = CompletableDeferred<Unit>()
        val flow = async { runCatching { synth.synthesize("背圧").collect { received.complete(Unit); hold.await() } } }
        withTimeout(5000) { received.await() }; synth.cancel()
        // Consumer is deliberately stalled, but the bounded handoff must release its native lock.
        withTimeout(5000) { withContext(Dispatchers.IO) { synth.close() } }
        flow.cancel(); withTimeout(5000) { flow.join() }
        assertEquals(1, runtime.closes)
    } }
    @Test fun consumerFailureStopsProducerAndNextUtteranceCanRun() = runBlocking { fixture { installation ->
        val runtime = Runtime(); runtime.frames = 20
        val synth = PocketSynthesizer({ _, _ -> runtime }, {}); synth.load(installation)
        val error = runCatching { synth.synthesize("USB切断").collect { throw java.io.IOException("USB disconnected") } }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
        runtime.frames = 3
        assertEquals(3, withTimeout(5000) { synth.synthesize("再接続後").toList() }.size)
        synth.close()
    } }
    @Test fun failedOrCancelledLoadNeverPublishesOrLeaksCandidate() = runBlocking { fixture { installation ->
        val runtime = Runtime(); lateinit var synth: PocketSynthesizer
        synth = PocketSynthesizer({ _, _ -> synth.cancel(); runtime }, {})
        assertTrue(runCatching { synth.load(installation) }.exceptionOrNull() is CancellationException)
        assertFalse(synth.isLoaded); assertEquals(1, runtime.closes)
        File(installation.directory,"model.onnx").delete()
        var opened = false
        val missing = PocketSynthesizer({ _, _ -> opened = true; Runtime() }, {})
        assertTrue(runCatching { missing.load(installation) }.exceptionOrNull() is IllegalStateException)
        assertFalse(opened)
        val lowMemory = PocketSynthesizer({ _, _ -> error("must not open") }, { error("low memory") })
        // Restore only the synthetic test file; the manifest remains unchanged.
        File(installation.directory,"model.onnx").writeBytes(byteArrayOf(1,2,3))
        assertEquals("low memory", runCatching { lowMemory.load(installation) }.exceptionOrNull()?.message)
    } }
    @Test fun rejectsInvalidFramesAndCapsLongInputBeforeNativeWork() = runBlocking { fixture { installation ->
        val runtime = Runtime(); val synth = PocketSynthesizer({ _, _ -> runtime }, {}); synth.load(installation)
        for (text in listOf("あ".repeat(1001), "a\u0000b")) {
            assertTrue(runCatching { synth.synthesize(text).toList() }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(0, runtime.starts)
        for (pcm in listOf(FloatArray(1919), FloatArray(1920) { Float.NaN })) {
            runtime.pcm = pcm
            assertTrue(runCatching { synth.synthesize("検証").toList() }.exceptionOrNull() is IllegalStateException)
        }
        synth.close()
    } }
    @Test fun pocketReadinessAndBusyGateRemainIndependentOfLegacyEngines() {
        val s = jp.stackchan.localvoicepoc.ui.MainUiState(ttsBackend=jp.stackchan.localvoicepoc.voicevox.TtsBackend.POCKET, pocketLoaded=true, sdkStatus=jp.stackchan.localvoicepoc.SdkBootstrap.Status.Ready)
        assertTrue(s.voiceReady); assertTrue(s.canStartModelMutation)
        assertFalse(s.copy(pocketLoaded=false, sanoLoaded=true, piperLoaded=true, voicevoxLoaded=true).voiceReady)
        assertFalse(s.copy(pocketBusy=true).canStartModelMutation)
        assertFalse(s.copy(voicevoxBusy=true).canStartModelMutation)
        assertFalse(s.copy(ttsPreviewRunning=true).canStartModelMutation)
        assertEquals(listOf("PIPER","PIPER2","VOICEVOX","SANO","POCKET"), jp.stackchan.localvoicepoc.voicevox.TtsBackend.entries.map { it.name })
    }
}
