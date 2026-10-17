package jp.stackchan.localvoicepoc.sano

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SanoSynthesizerTest {
    private fun installation(): SanoInstallation {
        val value = System.getProperty("sano.fixture.dir")
        org.junit.Assume.assumeTrue("Supply -PsanoFixtureDir for pinned public model tests", value != null)
        val folder = File(requireNotNull(value))
        return SanoInstallation(File(folder, SanoAssets.weights.name), File(folder, SanoAssets.dictionary.name))
    }
    private class Runtime : SanoRuntime {
        override val available = true
        @Volatile var starts = 0
        var closes = 0
        var pulls = 0
        var remaining = 0
        var frames = 1
        var openGate: (() -> Unit)? = null
        var startGate: (() -> Unit)? = null
        var pullGate: (() -> Unit)? = null
        var startFailure: Throwable? = null
        var pcm = FloatArray(256) { 0.25f }
        override fun open(model: File, dictionary: File): Long { openGate?.invoke(); return 1L }
        override fun start(handle: Long, text: String): Int {
            check(handle == 1L && closes == 0)
            starts++
            startGate?.invoke()
            startFailure?.let { throw it }
            remaining = frames
            return frames
        }
        override fun pull(handle: Long): FloatArray {
            check(handle == 1L && closes == 0)
            pulls++
            pullGate?.invoke()
            return if (remaining-- > 0) pcm else floatArrayOf()
        }
        override fun close(handle: Long) { assertEquals(1L, handle); closes++ }
    }
    @Test fun refusesUnpreparedModelAndThenLoadsVerifiedFiles() = runBlocking {
        val runtime = Runtime(); val synth = SanoSynthesizer(runtime)
        assertFalse(synth.isLoaded)
        assertTrue(runCatching { synth.synthesize("こんにちは").toList() }.exceptionOrNull() is IllegalStateException)
        assertEquals(0, runtime.starts)
        synth.load(installation())
        val result = synth.synthesize("こんにちは").toList().single()
        assertEquals(22_050, result.sampleRate)
        assertEquals(256, result.samples.size)
        assertTrue(result.samples.all { it == 8192.toShort() })
        synth.close(); synth.close()
        assertEquals(1, runtime.closes)
        assertFalse(synth.isLoaded)
    }
    @Test fun cancelInvalidatesAFlowCreatedBeforeCollection() = runBlocking {
        val runtime = Runtime(); val synth = SanoSynthesizer(runtime); synth.load(installation())
        val old = synth.synthesize("古い要求")
        synth.cancel()
        assertTrue(runCatching { old.toList() }.exceptionOrNull() is CancellationException)
        assertEquals(0, runtime.starts)
        assertEquals(1, synth.synthesize("新しい要求").toList().size)
        synth.close()
    }
    @Test fun suppressesPcmWhenStoppedInsideNativeStart() = runBlocking {
        val runtime = Runtime(); val synth = SanoSynthesizer(runtime); synth.load(installation())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        runtime.startGate = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val old = async { runCatching { synth.synthesize("古い要求").toList() } }
        withContext(Dispatchers.IO) { check(entered.await(5, TimeUnit.SECONDS)) }
        synth.cancel(); release.countDown()
        assertTrue(withTimeout(5000) { old.await() }.exceptionOrNull() is CancellationException)
        assertEquals(0, runtime.pulls)
        runtime.startGate = null
        assertEquals(1, synth.synthesize("次の要求").toList().size)
        synth.close()
    }
    @Test fun switchingAwayWaitsForPullAndNeverUsesAFreedHandle() = runBlocking {
        val runtime = Runtime(); val synth = SanoSynthesizer(runtime); synth.load(installation())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        runtime.pullGate = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val old = async { runCatching { synth.synthesize("切り替え前").toList() } }
        withContext(Dispatchers.IO) { check(entered.await(5, TimeUnit.SECONDS)) }
        synth.cancel()
        val close = async(Dispatchers.IO) { synth.close() }
        assertEquals(0, runtime.closes)
        release.countDown()
        assertTrue(withTimeout(5000) { old.await() }.exceptionOrNull() is CancellationException)
        withTimeout(5000) { close.await() }
        assertEquals(1, runtime.closes)
        assertEquals(1, runtime.pulls)
        assertFalse(synth.isLoaded)
    }
    @Test fun longTextAndFrontendFailureNeverReturnPartialSuccess() = runBlocking {
        val runtime = Runtime(); val synth = SanoSynthesizer(runtime); synth.load(installation())
        assertTrue(runCatching { synth.synthesize("あ".repeat(1001)).toList() }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, runtime.starts)
        runtime.startFailure = IllegalStateException("upstream token limit")
        assertTrue(runCatching { synth.synthesize("入力").toList() }.exceptionOrNull() is IllegalStateException)
        runtime.startFailure = null
        assertEquals(3, synth.synthesize("あ".repeat(81)).toList().size)
        synth.close()
    }
    @Test fun rejectsNonfiniteAndIncorrectLengthPcm() = runBlocking {
        val runtime = Runtime(); val synth = SanoSynthesizer(runtime); synth.load(installation())
        runtime.pcm = floatArrayOf(Float.NaN)
        assertTrue(runCatching { synth.synthesize("入力").toList() }.exceptionOrNull() is IllegalStateException)
        runtime.pcm = FloatArray(255)
        assertTrue(runCatching { synth.synthesize("入力").toList() }.exceptionOrNull() is IllegalStateException)
        runtime.pcm = FloatArray(257)
        assertTrue(runCatching { synth.synthesize("入力").toList() }.exceptionOrNull() is IllegalStateException)
        synth.close()
    }
    @Test fun boundsUnicodeAndClipsSignedPcm() {
        val text = "あ".repeat(39) + "😀" + "日本語。続き"
        assertEquals(text, SanoTextPolicy.segments(text).joinToString(""))
        assertTrue(SanoTextPolicy.segments(text).all { it.codePointCount(0,it.length) <= 40 })
        assertEquals(listOf("こんにちは？！", "続き。"), SanoTextPolicy.segments("こんにちは？！続き。"))
        for (invalid in listOf("\u0000", "\uD800", "\uDC00")) {
            assertTrue(runCatching { SanoTextPolicy.segments(invalid) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertArrayEquals(shortArrayOf(-32768, -32768, 0, 32767, 32767),
            SanoTextPolicy.pcm16(floatArrayOf(-2f,-1f,0f,1f,2f)))
    }
    @Test fun concurrentRequestsDoNotOverwriteAnActiveNativeStream() = runBlocking {
        val runtime=Runtime(); runtime.frames=3
        val synth=SanoSynthesizer(runtime); synth.load(installation())
        val firstReceived=kotlinx.coroutines.CompletableDeferred<Unit>()
        val release=kotlinx.coroutines.CompletableDeferred<Unit>()
        val first=async {
            var count=0
            synth.synthesize("最初").collect { if (count++==0) { firstReceived.complete(Unit); release.await() } }
            count
        }
        withTimeout(5000) { firstReceived.await() }
        val second=async { synth.synthesize("次").toList() }
        kotlinx.coroutines.yield()
        assertEquals(1,runtime.starts)
        release.complete(Unit)
        assertEquals(3,withTimeout(5000) { first.await() })
        assertEquals(3,withTimeout(5000) { second.await() }.size)
        synth.close()
    }
    @Test fun cancelledLoadClosesTheCandidateHandleBeforePublishingReadiness() = runBlocking {
        val runtime=Runtime(); val synth=SanoSynthesizer(runtime)
        runtime.openGate={ synth.cancel() }
        assertTrue(runCatching { synth.load(installation()) }.exceptionOrNull() is CancellationException)
        assertFalse(synth.isLoaded)
        assertEquals(1,runtime.closes)
        synth.close(); assertEquals(1,runtime.closes)
    }
    @Test fun sanoReadinessAndMutationGateAreIndependentOfOtherEngines() {
        val state = jp.stackchan.localvoicepoc.ui.MainUiState(
            ttsBackend = jp.stackchan.localvoicepoc.voicevox.TtsBackend.SANO, sanoLoaded = true,
            modelsReady = true, sdkStatus = jp.stackchan.localvoicepoc.SdkBootstrap.Status.Ready,
            usbStatus = jp.stackchan.localvoicepoc.ui.UsbConnectionStatus.READY)
        assertTrue(state.pipelineReady)
        assertFalse(state.copy(sanoLoaded=false, piperLoaded=true, voicevoxLoaded=true).pipelineReady)
        assertFalse(state.copy(sanoBusy=true).canStartModelMutation)
        assertFalse(state.copy(ttsPreviewRunning=true).canStartModelMutation)
        assertFalse(state.copy(phase=jp.stackchan.localvoicepoc.conversation.ConversationPhase.SPEAKING).canStartModelMutation)
    }
}
