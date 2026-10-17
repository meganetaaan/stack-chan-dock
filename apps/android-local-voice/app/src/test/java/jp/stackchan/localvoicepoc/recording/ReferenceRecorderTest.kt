package jp.stackchan.localvoicepoc.recording

import jp.stackchan.localvoicepoc.audio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ReferenceRecorderTest {
    private class Mic:PcmAudioSource {
        override val sampleRate=24000;override val channelCount=1;override val bytesPerSample=2
        val opened=Channel<Channel<ByteArray>>(Channel.UNLIMITED)
        var starts=0;var denied=false;var active=false
        private var current:Channel<ByteArray>?=null
        override fun chunks()=flow {
            if(denied)throw SecurityException("denied")
            check(!active);active=true;starts++
            val channel=Channel<ByteArray>(Channel.UNLIMITED);current=channel;opened.send(channel)
            try{for(bytes in channel)emit(bytes)}finally{active=false;current=null}
        }
        override fun stop(){current?.close()}
    }
    private class Speaker(private val mic:Mic):PcmAudioSink {
        val output=java.io.ByteArrayOutputStream();val draining=CompletableDeferred<Unit>();var hold:CompletableDeferred<Unit>?=null
        override suspend fun begin(sampleRate:Int){check(!mic.active);assertEquals(24000,sampleRate)}
        override suspend fun write(samples:ShortArray){val b=ByteBuffer.allocate(samples.size*2).order(ByteOrder.LITTLE_ENDIAN);samples.forEach{b.putShort(it)};output.write(b.array())}
        override suspend fun finish(){draining.complete(Unit);hold?.await()}
        override fun stop()=Unit
    }
    private suspend fun fixture(test:suspend CoroutineScope.(File,Mic,Speaker,ReferenceRecorder,ReferenceRecordingStore)->Unit)=coroutineScope {
        val root=Files.createTempDirectory("reference-session-").toFile();val mic=Mic();val speaker=Speaker(mic)
        val store=ReferenceRecordingStore(File(root,"saved"));val recorder=ReferenceRecorder(mic,speaker,store,this)
        try{recorder.open();test(root,mic,speaker,recorder,store)}finally{recorder.discard(true);root.deleteRecursively()}
    }
    private suspend fun ReferenceRecorder.idle()=withTimeout(5000){state.first{it.phase==RecordingPhase.IDLE}}
    private suspend fun record(mic:Mic,recorder:ReferenceRecorder):ByteArray {
        recorder.start();val bytes=ByteArray(24000*4){(it%127).toByte()}
        withTimeout(5000){mic.opened.receive()}.send(bytes)
        withTimeout(5000){recorder.state.first{it.durationMs>=2000}}
        recorder.stopRecording();recorder.idle();return bytes
    }
    @Test fun recordPreviewAndSaveAreExplicitAndDoubleSaveCannotDuplicate()=runBlocking {fixture {root,mic,speaker,recorder,store->
        val bytes=record(mic,recorder);assertFalse(mic.active);assertTrue(root.listFiles()!!.isEmpty());assertTrue(store.list().isEmpty())
        recorder.play();recorder.idle();assertArrayEquals(bytes,speaker.output.toByteArray())
        recorder.setName("練習した声");recorder.save();recorder.save()
        withTimeout(5000){recorder.state.first{it.phase==RecordingPhase.IDLE&&it.draft==null}}
        assertEquals(1,store.list().size);assertArrayEquals(bytes,store.read(store.list().single()).pcm)
    } }
    @Test fun backgroundOrScreenExitDiscardsUnsavedAudioAndDoesNotResume()=runBlocking {fixture {root,mic,_,recorder,_->
        record(mic,recorder);recorder.discard(false,"中断")
        assertTrue(recorder.state.value.open);assertNull(recorder.state.value.draft);assertEquals(1,mic.starts)
        recorder.discard(true);assertFalse(recorder.state.value.open);assertTrue(root.listFiles()!!.isEmpty())
        recorder.open();assertEquals(1,mic.starts);assertNull(recorder.state.value.draft)
    } }
    @Test fun cancelDuringCaptureThenRestartCannotPublishOldAudio()=runBlocking {fixture {root,mic,_,recorder,_->
        recorder.start();val frames=withTimeout(5000){mic.opened.receive()};frames.send(ByteArray(48000))
        withTimeout(5000){recorder.state.first{it.durationMs>=1000}}
        recorder.discard(false);assertFalse(mic.active);assertNull(recorder.state.value.draft)
        val fresh=record(mic,recorder);assertArrayEquals(fresh,recorder.state.value.draft!!.pcm)
        assertEquals(2,mic.starts);assertTrue(root.listFiles()!!.isEmpty())
    } }
    @Test fun permissionDenialDoesNotPersistOrAutomaticallyRestart()=runBlocking {fixture {root,mic,_,recorder,_->
        mic.denied=true;recorder.start();recorder.idle()
        assertTrue(recorder.state.value.message!!.startsWith("マイク"));assertTrue(root.listFiles()!!.isEmpty())
        mic.denied=false;assertEquals(0,mic.starts)
        record(mic,recorder);assertEquals(1,mic.starts)
    } }
    @Test fun stoppedPreviewKeepsDraftButInterruptionDiscardsIt()=runBlocking {fixture {_,mic,speaker,recorder,_->
        record(mic,recorder);speaker.hold=CompletableDeferred();recorder.play()
        withTimeout(5000){speaker.draining.await()};recorder.stopPlayback();recorder.idle()
        assertNotNull(recorder.state.value.draft)
        recorder.discard(false);assertNull(recorder.state.value.draft);assertFalse(mic.active)
    } }
    @Test fun savedRecordingsRemainAfterExitAndCanPlayWithoutOpeningMic()=runBlocking {fixture {_,mic,speaker,recorder,store->
        val entry=store.save(RecordingDraft(ByteArray(48000){10},24000),"保存済み")
        recorder.discard(true);recorder.open();assertEquals(1,recorder.state.value.saved.size)
        recorder.play(entry);recorder.idle();assertEquals(0,mic.starts);assertEquals(48000,speaker.output.size())
    } }
    @Test fun duplicateStartIsIgnoredAndMaximumLengthStopsCleanly()=runBlocking {fixture {_,mic,_,recorder,_->
        recorder.start();recorder.start();val frames=withTimeout(5000){mic.opened.receive()}
        frames.send(ByteArray(24000*2*61));recorder.idle()
        assertEquals(1,mic.starts);assertEquals(60000L,recorder.state.value.draft!!.durationMs)
        assertFalse(mic.active)
    } }
    @Test fun recorderScreenBlocksConversationModeAndModelMutation() {
        val state=jp.stackchan.localvoicepoc.ui.MainUiState(sdkStatus=jp.stackchan.localvoicepoc.SdkBootstrap.Status.Ready,
            modelsReady=true,pocketLoaded=true,ttsBackend=jp.stackchan.localvoicepoc.voicevox.TtsBackend.POCKET,
            audioMode=ConversationAudioMode.PHONE,microphoneGranted=true,
            referenceRecording=ReferenceRecordingState(open=true))
        assertFalse(state.pipelineReady);assertFalse(state.canChangeAudioMode);assertFalse(state.canStartModelMutation)
    }
    @Test fun stopDuringSourceStartupCancelsPendingOpen()=runBlocking {
        val entering=CompletableDeferred<Unit>();val allowOpen=CompletableDeferred<Unit>();var opened=false
        val source=object:PcmAudioSource {
            override val sampleRate=24000;override val channelCount=1;override val bytesPerSample=2
            override fun chunks():Flow<ByteArray> = flow {entering.complete(Unit);allowOpen.await();opened=true;awaitCancellation()}
            override fun stop()=Unit
        }
        val root=Files.createTempDirectory("reference-startup-").toFile()
        val recorder=ReferenceRecorder(source,Speaker(Mic()),ReferenceRecordingStore(root),this)
        try {
            recorder.open();recorder.start();withTimeout(5000){entering.await()}
            recorder.stopRecording();recorder.idle();allowOpen.complete(Unit)
            assertFalse(opened);assertNull(recorder.state.value.draft);assertTrue(root.listFiles()!!.isEmpty())
        } finally {recorder.discard(true);root.deleteRecursively()}
    }
    @Test fun deleteRequiresTheNamedConfirmationAndCancellationKeepsTheRecording()=runBlocking {fixture {_,mic,_,recorder,store->
        val entry=store.save(RecordingDraft(ByteArray(48000){1},24000),"消す前に確認する名前")
        recorder.open();recorder.confirmDelete();recorder.idle();assertEquals(1,store.list().size)
        recorder.requestDelete(entry.id,voiceBusy=true);assertNull(recorder.state.value.deletePrompt)
        recorder.requestDelete(entry.id);assertEquals(entry,recorder.state.value.deletePrompt)
        recorder.confirmDelete(voiceBusy=true);assertEquals(entry,recorder.state.value.deletePrompt);assertEquals(1,store.list().size)
        recorder.start();recorder.play(entry);recorder.save()
        assertEquals(0,mic.starts);assertEquals(RecordingPhase.IDLE,recorder.state.value.phase)
        recorder.cancelDelete();assertNull(recorder.state.value.deletePrompt);assertEquals(1,store.list().size)
        recorder.requestDelete(entry.id);recorder.discard(false)
        assertNull(recorder.state.value.deletePrompt);assertEquals(1,store.list().size)
    } }
    @Test fun confirmedDeleteRefreshesTheListAndRetainsTheUnsavedDraft()=runBlocking {fixture {_,mic,_,recorder,store->
        val entry=store.save(RecordingDraft(ByteArray(48000){1},24000),"削除対象")
        recorder.open();val bytes=record(mic,recorder);recorder.setName("未保存の録音")
        recorder.requestDelete(entry.id);recorder.confirmDelete();recorder.confirmDelete();recorder.idle()
        assertTrue(recorder.state.value.saved.isEmpty());assertTrue(store.list().isEmpty())
        assertArrayEquals(bytes,recorder.state.value.draft!!.pcm);assertEquals("未保存の録音",recorder.state.value.name)
        recorder.discard(true);recorder.open();assertTrue(recorder.state.value.saved.isEmpty())
    } }
    @Test fun captureAndPlaybackRejectDeleteRequestsAndPendingDeleteBlocksVoiceConversion()=runBlocking {fixture {_,mic,speaker,recorder,store->
        val entry=store.save(RecordingDraft(ByteArray(48000){1},24000),"保持")
        recorder.open();recorder.start();withTimeout(5000){mic.opened.receive()}
        recorder.requestDelete(entry.id);assertNull(recorder.state.value.deletePrompt);recorder.discard(false)
        speaker.hold=CompletableDeferred();recorder.play(entry);withTimeout(5000){speaker.draining.await()}
        recorder.requestDelete(entry.id);assertNull(recorder.state.value.deletePrompt);recorder.stopPlayback();recorder.idle()
        recorder.requestDelete(entry.id)
        val state=jp.stackchan.localvoicepoc.ui.MainUiState(pocketLoaded=true,
            ttsBackend=jp.stackchan.localvoicepoc.voicevox.TtsBackend.POCKET,referenceRecording=recorder.state.value)
        assertFalse(state.canManageReferenceVoices)
        assertFalse(state.copy(referenceRecording=recorder.state.value.copy(deletePrompt=null,phase=RecordingPhase.DELETING)).canManageReferenceVoices)
        assertEquals(1,store.list().size)
    } }
}
