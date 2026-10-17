package jp.stackchan.localvoicepoc.sano

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import jp.stackchan.localvoicepoc.audio.PcmAudioSink
import jp.stackchan.localvoicepoc.serial.*
import jp.stackchan.localvoicepoc.piper.SpeechSynthesizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

/** Actual Dock sink, resampler, framing and credit writer; no COM port or Android hardware. */
class SanoDockMockTest {
    private class Dock(private val credit: Boolean = true, private val disconnectOnPcm: Boolean = false) : StackChanUsbTransport {
        override val state = MutableStateFlow<StackChanUsbState>(StackChanUsbState.Ready(640, StackChanCapabilities.ALL))
        override val frames = MutableSharedFlow<StackChanFrame>(extraBufferCapacity=32)
        val sent = CopyOnWriteArrayList<StackChanFrame>()
        val firstPcm = CompletableDeferred<Unit>()
        private fun controlFrame(kind: StackChanControl, rate: Int, stream: Int, payload: ByteArray = byteArrayOf()) =
            StackChanFrame(StackChanFrame.Type.CONTROL,flags=kind.wireValue,sequence=0,sampleRate=rate,streamId=stream,payload=payload)
        override suspend fun send(frame: StackChanFrame) {
            check(state.value is StackChanUsbState.Ready) { "USB disconnected" }
            sent += frame
            firstPcm.complete(Unit)
            if (disconnectOnPcm) { state.value = StackChanUsbState.Disconnected; throw IOException("USB disconnected") }
            if (credit) frames.emit(controlFrame(StackChanControl.SPEAKER_CREDIT,frame.sampleRate,frame.streamId,uint32Payload(frame.payload.size)))
        }
        override suspend fun sendControl(control: StackChanControl, sampleRate: Int, payload: ByteArray, streamId: Int) {
            if (state.value !is StackChanUsbState.Ready) throw IOException("USB disconnected")
            sent += controlFrame(control,sampleRate,streamId,payload)
            when (control) {
                StackChanControl.SPEAKER_START -> frames.emit(controlFrame(StackChanControl.SPEAKER_CREDIT,sampleRate,streamId,uint32Payload(1024)))
                StackChanControl.SPEAKER_END -> frames.emit(controlFrame(StackChanControl.SPEAKER_DONE,sampleRate,streamId))
                else -> Unit
            }
        }
    }
    private suspend fun play(sink: PcmAudioSink, synth: SpeechSynthesizer) {
        var begun=false; var complete=false
        try {
            synth.synthesize("日本語").collect { chunk ->
                if (!begun) { sink.begin(chunk.sampleRate); begun=true }
                sink.write(chunk.samples)
            }
            sink.finish(); complete=true
        } finally {
            if (!complete) { synth.cancel(); withContext(NonCancellable) { sink.abort() } }
        }
    }
    private fun source(samples: Int) = object : SpeechSynthesizer {
        override val isLoaded = true
        override fun synthesize(text: String) = flow {
            repeat(samples / 256) { emit(SpeechSynthesizer.AudioChunk(22_050,ShortArray(256) { (it*30-4000).toShort() })) }
        }
        override fun cancel() = Unit
        override fun close() = Unit
    }
    @Test fun resamplesSanoTo24kPcm16AndCompletesCreditStartEndDone() = runBlocking {
        val dock=Dock(); val sink=SerialPcmAudioSink(dock)
        withTimeout(5000) { play(sink,source(22_016)) }
        assertEquals(StackChanControl.SPEAKER_START.wireValue,dock.sent.first().flags)
        assertEquals(StackChanControl.SPEAKER_END.wireValue,dock.sent.last().flags)
        val pcm=dock.sent.filter { it.type==StackChanFrame.Type.SPEAKER_PCM }
        assertTrue(pcm.isNotEmpty())
        assertTrue(pcm.all { it.sampleRate==24000 && it.payload.size<=640 && it.payload.size%2==0 })
        val expected = 22_016L*24000/22050
        assertTrue(kotlin.math.abs(pcm.sumOf { it.payload.size }/2-expected)<=1)
        assertTrue(pcm.all { it.streamId==dock.sent.first().streamId })
        pcm.forEach { frame ->
            val decoded = StackChanFrameCodec.decode(StackChanFrameCodec.encode(frame))
            assertArrayEquals(frame.payload,decoded.payload)
            assertEquals(frame.streamId,decoded.streamId)
            assertEquals(frame.sequence,decoded.sequence)
        }
        sink.close()
    }
    @Test fun stoppingDuringStalledCreditAbortsAndTheNextStreamCanStart() = runBlocking {
        val dock=Dock(credit=false); val sink=SerialPcmAudioSink(dock)
        val playback=async { play(sink,source(22_016*10)) }
        withTimeout(5000) { dock.firstPcm.await() }
        playback.cancel()
        withTimeout(5000) { playback.join() }
        assertTrue(dock.sent.any { it.flags==StackChanControl.SPEAKER_ABORT.wireValue })
        assertFalse(dock.sent.any { it.flags==StackChanControl.SPEAKER_END.wireValue })
        val previousStream=dock.sent.first().streamId
        withTimeout(5000) { play(sink,source(256)) }
        val starts=dock.sent.filter { it.flags==StackChanControl.SPEAKER_START.wireValue }
        assertEquals(2,starts.size)
        assertNotEquals(previousStream,starts.last().streamId)
        sink.close()
    }
    @Test fun actualJapaneseNativePcmCompletesThroughTheExistingDockSink() = runBlocking {
        val path=System.getProperty("sano.pcm.fixture")
        org.junit.Assume.assumeTrue("Supply -PsanoPcmFixture for actual native PCM replay",path!=null)
        verifyNativePcmThroughDock(requireNotNull(path))
    }
    @Test fun actualPiper2NativePcmCompletesThroughTheExistingDockSink() = runBlocking {
        val path=System.getProperty("piper2.pcm.fixture")
        org.junit.Assume.assumeTrue("Supply -Ppiper2PcmFixture for Piper 2 native PCM replay",path!=null)
        verifyNativePcmThroughDock(requireNotNull(path))
    }
    @Test fun actualPocketPcmStays24kPcm16ThroughCreditStartEndDone() = runBlocking {
        val path=System.getProperty("pocket.pcm.fixture")
        org.junit.Assume.assumeTrue("Supply -PpocketPcmFixture for accepted Pocket CPU PCM replay",path!=null)
        verifyNativePcmThroughDock(requireNotNull(path),24000,1920)
    }
    private suspend fun verifyNativePcmThroughDock(path: String, sourceRate: Int = 22050, frames: Int = 2048) {
        val bytes=java.io.File(path).readBytes()
        val buffer=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val samples=ShortArray(bytes.size/2) { buffer.short }
        assertTrue(samples.size>22050 && samples.any { kotlin.math.abs(it.toInt())>100 })
        val synth=object : SpeechSynthesizer {
            override val isLoaded=true
            override fun synthesize(text: String)=flow {
                var offset=0
                while (offset<samples.size) {
                    val end=minOf(offset+frames,samples.size)
                    emit(SpeechSynthesizer.AudioChunk(sourceRate,samples.copyOfRange(offset,end)))
                    offset=end
                }
            }
            override fun cancel()=Unit
            override fun close()=Unit
        }
        val dock=Dock(); val sink=SerialPcmAudioSink(dock)
        withTimeout(5000) { play(sink,synth) }
        val pcm=dock.sent.filter { it.type==StackChanFrame.Type.SPEAKER_PCM }
        assertTrue(kotlin.math.abs(pcm.sumOf { it.payload.size }/2-samples.size*24000L/sourceRate)<=1)
        assertTrue(pcm.all { it.sampleRate==24000 && it.payload.size<=640 && it.payload.size%2==0 })
        if(sourceRate==24000) {
            val output=java.io.ByteArrayOutputStream()
            pcm.forEach { output.write(it.payload) }
            assertArrayEquals(bytes,output.toByteArray())
        }
        assertEquals(StackChanControl.SPEAKER_END.wireValue,dock.sent.last().flags)
        sink.close()
    }
    @Test fun disconnectPropagatesFailureAndStopsWithoutEndOrFurtherPcm() = runBlocking {
        val dock=Dock(disconnectOnPcm=true); val sink=SerialPcmAudioSink(dock)
        val error=withTimeout(5000) { runCatching { play(sink,source(22_016)) }.exceptionOrNull() }
        assertTrue(error is IOException)
        assertEquals(1,dock.sent.count { it.type==StackChanFrame.Type.SPEAKER_PCM })
        assertFalse(dock.sent.any { it.flags==StackChanControl.SPEAKER_END.wireValue })
        sink.close()
    }
}
