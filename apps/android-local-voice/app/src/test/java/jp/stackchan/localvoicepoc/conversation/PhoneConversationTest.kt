package jp.stackchan.localvoicepoc.conversation

import jp.stackchan.localvoicepoc.audio.*
import jp.stackchan.localvoicepoc.model.*
import jp.stackchan.localvoicepoc.piper.SpeechSynthesizer
import jp.stackchan.localvoicepoc.speech.LocalSpeechRecognizer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Real conversation engine, fake phone audio only: no USB, microphone, model requests or GPU. */
class PhoneConversationTest {
    private class Mic : PcmAudioSource {
        override val sampleRate=16000;override val channelCount=1;override val bytesPerSample=2
        val opened=Channel<Channel<ByteArray>>(Channel.UNLIMITED)
        @Volatile var active=false;var starts=0
        @Volatile var current:Channel<ByteArray>?=null
        override fun chunks()=flow {
            check(!active);active=true;starts++
            val frames=Channel<ByteArray>(Channel.UNLIMITED);current=frames;opened.send(frames)
            try { for(frame in frames) emit(frame) } finally { active=false;current=null }
        }
        override fun stop(){current?.close()}
    }
    private class Speaker(private val mic:Mic) : PcmAudioSink {
        val writes=Channel<Unit>(Channel.UNLIMITED);val finished=Channel<Unit>(Channel.UNLIMITED)
        val finishing=CompletableDeferred<Unit>();var drain:CompletableDeferred<Unit>?=null
        val calls=CopyOnWriteArrayList<String>()
        override suspend fun begin(sampleRate:Int){check(!mic.active){"Microphone overlapped speaker"};assertEquals(24000,sampleRate);calls+="begin"}
        override suspend fun write(samples:ShortArray){calls+="write";writes.send(Unit)}
        override suspend fun finish(){finishing.complete(Unit);drain?.await();calls+="finish";finished.send(Unit)}
        override suspend fun abort(){calls+="abort"}
        override fun stop(){calls+="stop"}
    }
    private class Recognizer(private val mic:Mic) : LocalSpeechRecognizer {
        var calls=0
        override val isLoaded=true
        override suspend fun load(modelDirectory:java.io.File)=Unit
        override suspend fun transcribe(pcm16Le:ByteArray,sampleRate:Int):String {
            check(!mic.active);assertEquals(16000,sampleRate);assertTrue(pcm16Le.size>=8000);calls++;return "こんにちは"
        }
        override fun close()=Unit
    }
    private class Llm : LocalLanguageModel {
        override val isLoaded=true
        override val backend=LanguageModelBackend.CPU
        override suspend fun prepare(modelSpec:LanguageModelSpec,modelFile:java.io.File,preference:LanguageModelBackendPreference)=backend
        override fun generate(request:GenerationRequest)=flow {emit("こんにちは。")}
        override suspend fun cancel()=Unit
        override fun close()=Unit
    }
    private class Voice : SpeechSynthesizer {
        override val isLoaded=true;var block=false;var starts=0
        override fun synthesize(text:String)=flow {starts++;emit(SpeechSynthesizer.AudioChunk(24000,ShortArray(1920){200}));if(block)awaitCancellation()}
        override fun cancel()=Unit
        override fun close()=Unit
    }
    private suspend fun Mic.record() {
        val frames=withTimeout(5000){opened.receive()}
        repeat(20){frames.send(ByteArray(640){10})}
    }
    private suspend fun close(engine:ConversationEngine){engine.close();withTimeout(5000){engine.awaitClosed()}}
    @Test fun phonePushToTalkRunsSttLlmSelectedTtsWithoutUsbAndRejectsDuplicateSend()=runBlocking {
        val mic=Mic();val speaker=Speaker(mic);val recognizer=Recognizer(mic);val voice=Voice()
        val engine=ConversationEngine(mic,speaker,voice,recognizer,Llm())
        engine.startPushToTalk();mic.record()
        engine.stopPushToTalkAndProcess();engine.stopPushToTalkAndProcess()
        withTimeout(5000){speaker.finished.receive()}
        assertEquals(1,recognizer.calls);assertEquals(1,voice.starts)
        assertEquals(listOf("begin","write","finish"),speaker.calls.toList())
        engine.stop();close(engine)
    }
    @Test fun stopDiscardsRecordingAndRestartUsesFreshMicrophone()=runBlocking {
        val mic=Mic();val speaker=Speaker(mic);val recognizer=Recognizer(mic)
        val engine=ConversationEngine(mic,speaker,Voice(),recognizer,Llm())
        engine.startPushToTalk();mic.record();engine.stop()
        assertFalse(mic.active);assertEquals(0,recognizer.calls)
        engine.startPushToTalk();mic.record();engine.stopPushToTalkAndProcess()
        withTimeout(5000){speaker.finished.receive()}
        assertEquals(2,mic.starts);assertEquals(1,recognizer.calls)
        close(engine)
    }
    @Test fun modeSwitchAbortsOldSpeechAndNextTurnUsesOnlyNewAudioDevices()=runBlocking {
        val old=Mic();val speaker=Speaker(old);val voice=Voice().apply{block=true}
        val recognizer=Recognizer(old)
        val engine=ConversationEngine(old,speaker,voice,recognizer,Llm())
        engine.startPushToTalk();old.record();engine.stopPushToTalkAndProcess()
        withTimeout(5000){speaker.writes.receive()}
        val phone=Mic();val next=Speaker(phone)
        withTimeout(5000){engine.switchAudio(phone,next)}
        assertFalse(old.active);assertTrue("abort" in speaker.calls);assertFalse("finish" in speaker.calls)
        voice.block=false
        engine.startPushToTalk();phone.record();engine.stopPushToTalkAndProcess()
        withTimeout(5000){next.finished.receive()}
        assertEquals(1,old.starts);assertEquals(1,phone.starts);assertEquals(2,voice.starts)
        close(engine)
    }
    @Test fun automaticListeningWaitsUntilSpeakerDrainFinishes()=runBlocking {
        val mic=Mic();val speaker=Speaker(mic).apply{drain=CompletableDeferred()}
        val recognizer=Recognizer(mic)
        val engine=ConversationEngine(mic,speaker,Voice(),recognizer,Llm(),endpointDetectorFactory={object:SpeechEndpointDetector{
            var n=0;override fun reset(){n=0};override fun isSpeech(chunk:ByteArray)=n++<20;override fun close()=Unit
        }})
        engine.startAutomatic()
        val frames=withTimeout(5000){mic.opened.receive()}
        repeat(60){frames.send(ByteArray(640){10})}
        withTimeout(5000){speaker.finishing.await()}
        assertFalse(mic.active);assertEquals(1,mic.starts)
        speaker.drain!!.complete(Unit)
        withTimeout(5000){mic.opened.receive()}
        assertEquals(2,mic.starts)
        engine.stop();assertFalse(mic.active);close(engine)
    }
    @Test fun missingSelectedVoiceCannotOpenMicrophone()=runBlocking {
        val mic=Mic();val speaker=Speaker(mic)
        val missing=object:SpeechSynthesizer{override val isLoaded=false;override fun synthesize(text:String)=emptyFlow<SpeechSynthesizer.AudioChunk>();override fun cancel()=Unit;override fun close()=Unit}
        val engine=ConversationEngine(mic,speaker,missing,Recognizer(mic),Llm())
        assertTrue(runCatching{engine.startPushToTalk()}.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching{engine.startAutomatic()}.exceptionOrNull() is IllegalStateException)
        assertEquals(0,mic.starts);close(engine)
    }
    @Test fun permissionFailureReturnsIdleAndGrantAloneDoesNotStartRecording()=runBlocking {
        val mic=Mic();val speaker=Speaker(mic);val recognizer=Recognizer(mic)
        val denied=object:PcmAudioSource {
            override val sampleRate=16000;override val channelCount=1;override val bytesPerSample=2
            override fun chunks()=flow<ByteArray>{throw SecurityException("permission denied")}
            override fun stop()=Unit
        }
        val engine=ConversationEngine(denied,speaker,Voice(),recognizer,Llm())
        val errors=Channel<ConversationEvent.Failure>(Channel.UNLIMITED)
        val collector=launch {engine.events.collect {if(it is ConversationEvent.Failure)errors.send(it)}}
        yield();engine.startPushToTalk()
        assertTrue(withTimeout(5000){errors.receive()}.cause is SecurityException)
        withTimeout(5000){engine.switchAudio(mic,speaker)}
        assertEquals(0,mic.starts);assertEquals(0,recognizer.calls)
        engine.startPushToTalk();mic.record();engine.stopPushToTalkAndProcess()
        withTimeout(5000){speaker.finished.receive()}
        assertEquals(1,recognizer.calls)
        collector.cancel();close(engine)
    }
}
