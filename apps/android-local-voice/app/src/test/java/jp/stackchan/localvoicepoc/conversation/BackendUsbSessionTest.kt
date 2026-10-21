package jp.stackchan.localvoicepoc.conversation

import dev.irte.voice.PocketEngine
import jp.stackchan.localvoicepoc.audio.PcmAudioSource
import jp.stackchan.localvoicepoc.model.*
import jp.stackchan.localvoicepoc.piper.SpeechSynthesizer
import jp.stackchan.localvoicepoc.pocket.PocketAssets
import jp.stackchan.localvoicepoc.pocket.PocketInstallation
import jp.stackchan.localvoicepoc.pocket.PocketSynthesizer
import jp.stackchan.localvoicepoc.serial.*
import jp.stackchan.localvoicepoc.speech.LocalSpeechRecognizer
import jp.stackchan.localvoicepoc.voicevox.VoicevoxPcmFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real backend Flow adapters and USB sink; only native synthesis and USB peer are synthetic. */
@RunWith(Parameterized::class)
class BackendUsbSessionTest(private val backend: String) {
    @Test fun twoSentencesCompleteWithBothCaptions() = checkResponse(2)
    @Test fun sixSentencesSurviveBoundedQueueBackpressure() = checkResponse(6)

    private fun checkResponse(count: Int) = runBlocking {
        fixture { synth, control ->
            val sentences = listOf("こんにちは！", "なにかお手伝いできることはありますか？") + List(count - 2) { "追加の文です。" }
            session(synth, sentences.joinToString("")) { engine, transport ->
                val result = withTimeoutOrNull(TIMEOUT) { engine.generateAndSpeak("test") }
                println("backend=$backend; sentences=$count; completed=${result != null}; synthesis=${control.calls.size}; captions=${transport.captions().size}; pcmBytes=${transport.pcmBytes()}")
                assertNotNull("$backend stopped after ${control.calls.size} syntheses", result)
                assertEquals(sentences, control.calls)
                assertEquals(sentences, transport.captions())
                assertEquals(count * PCM_BYTES_PER_SENTENCE, transport.pcmBytes())
                assertEquals(1, transport.controls(StackChanControl.SPEAKER_START).size)
                assertEquals(1, transport.controls(StackChanControl.SPEAKER_END).size)
                assertTrue(transport.controls(StackChanControl.SPEAKER_ABORT).isEmpty())
                assertEquals(1, transport.sent.map { it.streamId }.distinct().size)
                assertEquals(transport.sent.filter { it.type == StackChanFrame.Type.SPEAKER_PCM }.indices.toList(), transport.sent.filter { it.type == StackChanFrame.Type.SPEAKER_PCM }.map { it.sequence })
            }
        }
    }

    @Test fun secondSentenceFailureAbortsAndDoesNotReportNormalEnd() = runBlocking {
        fixture { synth, control ->
            control.failOn = 2
            session(synth, "最初です。次です。") { engine, transport ->
                val error = runCatching { withTimeout(TIMEOUT) { engine.generateAndSpeak("test") } }.exceptionOrNull()
                assertTrue("$backend error=$error", error is IOException)
                assertEquals(2, control.calls.size)
                assertEquals(1, transport.controls(StackChanControl.SPEAKER_ABORT).size)
                assertTrue(transport.controls(StackChanControl.SPEAKER_END).isEmpty())
            }
        }
    }

    @Test fun cancellingSecondSynthesisAbortsAndNextTurnUsesNewStream() = runBlocking {
        fixture { synth, control ->
            control.holdOn = 2
            session(synth, "最初です。次です。") { engine, transport ->
                val turn = async { engine.generateAndSpeak("test") }
                try {
                    withTimeout(TIMEOUT) { control.entered.await() }
                    turn.cancel()
                    control.release.countDown()
                    withTimeout(TIMEOUT) { turn.join() }
                    assertEquals(2, control.calls.size)
                    assertEquals(listOf("最初です。"), transport.captions())
                    val abort = transport.sent.indexOfLast { it.type == StackChanFrame.Type.CONTROL && it.flags == StackChanControl.SPEAKER_ABORT.wireValue }
                    assertTrue(abort >= 0)
                    val retired = transport.sent.first().streamId
                    control.holdOn = 0
                    withTimeout(TIMEOUT) { engine.generateAndSpeak("next") }
                    val next = transport.sent.drop(abort + 1)
                    assertEquals(StackChanControl.SPEAKER_START.wireValue, next.first().flags)
                    assertFalse(next.any { it.streamId == retired })
                    assertEquals(4, control.calls.size)
                    assertEquals(2, transport.controls(StackChanControl.SPEAKER_START).size)
                    assertEquals(1, transport.controls(StackChanControl.SPEAKER_END).size)
                } finally {
                    control.release.countDown()
                    turn.cancelAndJoin()
                }
            }
        }
    }

    private suspend fun fixture(block: suspend (SpeechSynthesizer, NativeControl) -> Unit) {
        val control = NativeControl()
        val directory = Files.createTempDirectory("backend-usb-session-").toFile()
        val synth: SpeechSynthesizer = if (backend == "VOICEVOX") {
            val pcm = VoicevoxPcmFlow { text -> control.start(text); wav() }
            object : SpeechSynthesizer {
                override val isLoaded = true
                override fun synthesize(text: String) = pcm.synthesize(text)
                override fun cancel() = pcm.cancel()
                override fun close() = cancel()
            }
        } else {
            val bytes = byteArrayOf(1, 2, 3)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            val manifest = """{"files":[{"name":"model.onnx","bytes":3,"sha256":"$hash"}]}"""
            val install = PocketAssets.directory(directory).apply { mkdirs() }
            File(install, "model.onnx").writeBytes(bytes)
            File(install, "manifest.json").writeText(manifest)
            val native = object : PocketEngine {
                var active: () -> Unit = {}
                override fun prepareRequest(active: () -> Unit) { this.active = active }
                override fun generate(source: String, receive: (FloatArray) -> Boolean) {
                    control.start(source)
                    repeat(FRAMES) { active(); check(receive(FloatArray(1920) { .25f })); active() }
                }
                override fun cancel() { control.release.countDown() }
                override fun close() = Unit
            }
            PocketSynthesizer({ _, _ -> native }, {}).also { it.load(PocketInstallation(directory, manifest)) }
        }
        try { block(synth, control) } finally {
            control.release.countDown()
            synth.close()
            directory.deleteRecursively()
        }
    }

    private suspend fun session(synth: SpeechSynthesizer, output: String, block: suspend (ConversationEngine, Transport) -> Unit) {
        val transport = Transport()
        val sink = SerialPcmAudioSink(transport)
        val model = object : LocalLanguageModel {
            override val isLoaded = true
            override val backend = LanguageModelBackend.CPU
            override suspend fun prepare(modelSpec: LanguageModelSpec, modelFile: File, preference: LanguageModelBackendPreference) = backend
            override fun generate(request: GenerationRequest) = flow { emit(output) }
            override suspend fun cancel() = Unit
            override fun close() = Unit
        }
        val engine = ConversationEngine(Source, sink, synth, Recognizer, model)
        try { block(engine, transport) } finally {
            engine.close()
            withTimeout(TIMEOUT) { engine.awaitClosed() }
            sink.close()
        }
    }

    private class NativeControl {
        val calls = CopyOnWriteArrayList<String>()
        var failOn = 0
        var holdOn = 0
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        fun start(text: String) {
            calls += text
            if (calls.size == failOn) throw IOException("synthetic second synthesis failure")
            if (calls.size == holdOn) { entered.complete(Unit); check(release.await(10, TimeUnit.SECONDS)) }
        }
    }

    private class Transport : StackChanUsbTransport {
        override val state = MutableStateFlow<StackChanUsbState>(StackChanUsbState.Ready(4096, StackChanCapabilities.ALL))
        override val frames = MutableSharedFlow<StackChanFrame>(extraBufferCapacity = 64)
        val sent = CopyOnWriteArrayList<StackChanFrame>()
        fun controls(control: StackChanControl) = sent.filter { it.type == StackChanFrame.Type.CONTROL && it.flags == control.wireValue }
        fun captions() = controls(StackChanControl.SPEAKER_TEXT).map { it.payload.toString(Charsets.UTF_8) }
        fun pcmBytes() = sent.filter { it.type == StackChanFrame.Type.SPEAKER_PCM }.sumOf { it.payload.size }
        override suspend fun send(frame: StackChanFrame) {
            sent += frame
            if (frame.type == StackChanFrame.Type.SPEAKER_PCM) ack(StackChanControl.SPEAKER_CREDIT, frame.sampleRate, uint32Payload(frame.payload.size), frame.streamId)
        }
        override suspend fun sendControl(control: StackChanControl, sampleRate: Int, payload: ByteArray, streamId: Int) {
            sent += frame(control, sampleRate, payload, streamId)
            when (control) {
                StackChanControl.SPEAKER_START -> ack(StackChanControl.SPEAKER_CREDIT, sampleRate, uint32Payload(8192), streamId)
                StackChanControl.SPEAKER_END -> ack(StackChanControl.SPEAKER_DONE, sampleRate, byteArrayOf(), streamId)
                else -> Unit
            }
        }
        private fun frame(control: StackChanControl, rate: Int, bytes: ByteArray, stream: Int) = StackChanFrame(type=StackChanFrame.Type.CONTROL, flags=control.wireValue, sequence=0, sampleRate=rate, payload=bytes, streamId=stream)
        private suspend fun ack(control: StackChanControl, rate: Int, bytes: ByteArray, stream: Int) = frames.emit(frame(control, rate, bytes, stream))
    }

    private object Source : PcmAudioSource {
        override val sampleRate = 16_000
        override val channelCount = 1
        override val bytesPerSample = 2
        override fun chunks(): Flow<ByteArray> = emptyFlow()
        override fun stop() = Unit
    }
    private object Recognizer : LocalSpeechRecognizer {
        override val isLoaded = true
        override suspend fun load(modelDirectory: File) = Unit
        override suspend fun transcribe(pcm16Le: ByteArray, sampleRate: Int): String = error("not used")
        override fun close() = Unit
    }
    private fun wav(): ByteArray {
        val data = ByteBuffer.allocate(44 + PCM_BYTES_PER_SENTENCE).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + PCM_BYTES_PER_SENTENCE).put("WAVEfmt ".toByteArray())
        data.putInt(16).putShort(1).putShort(1).putInt(24000).putInt(48000).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(PCM_BYTES_PER_SENTENCE)
        repeat(PCM_BYTES_PER_SENTENCE / 2) { data.putShort(8192) }
        return data.array()
    }
    companion object {
        private const val TIMEOUT = 3_000L
        private const val FRAMES = 25
        private const val PCM_BYTES_PER_SENTENCE = FRAMES * 1920 * 2
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun backends() = listOf(arrayOf("VOICEVOX"), arrayOf("POCKET"))
    }
}
