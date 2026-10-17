package jp.stackchan.localvoicepoc.pocket

import dev.irte.voice.PocketVoiceState
import jp.stackchan.localvoicepoc.recording.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PocketVoiceControllerTest {
    private class Fake {
        var ready=true; var allowed=true; var starts=0; var cancels=0; var selected: PocketVoiceState?=null
        var gate:CompletableDeferred<Unit>?=null;val entered=CompletableDeferred<Unit>();var fail=false
        suspend fun build(pcm:FloatArray, ignored:File):PocketVoiceState {
            starts++;entered.complete(Unit);gate?.await();check(!fail){"low memory"}
            val frames=(pcm.size+1919)/1920+1
            return PocketVoiceState(frames,List(12){FloatArray(frames*1024){.2f}})
        }
        suspend fun apply(voice:PocketVoiceState?,commit:()->Unit){check(!fail){"load failed"};commit();selected=voice}
    }
    private suspend fun fixture(test:suspend (ReferenceRecordingStore,PocketVoiceStore,Fake,PocketVoiceController)->Unit)=coroutineScope {
        val root=Files.createTempDirectory("voice-controller-").toFile()
        val recordings=ReferenceRecordingStore(File(root,"recordings"));val store=PocketVoiceStore(File(root,"voices"));val fake=Fake()
        val controller=PocketVoiceController(recordings,store,File(root,"encoder"),{active->active();fake.ready},fake::build,fake::apply,{fake.cancels++},{fake.allowed},this)
        try {controller.refresh();test(recordings,store,fake,controller)}finally{controller.cancel();withTimeout(5000){controller.state.first{!it.busy}};root.deleteRecursively()}
    }
    private fun recording(store:ReferenceRecordingStore)=store.save(RecordingDraft(ByteArray(24000*2*6){17},24000),"私の声")
    private suspend fun idle(controller:PocketVoiceController)=withTimeout(5000){controller.state.first{!it.busy}}
    @Test fun createDoesNotChangeVoiceUntilExplicitSelectionAndCanRestore()=runBlocking {fixture { recordings,store,fake,controller->
        val entry=recording(recordings);controller.create(entry.id);controller.create(entry.id);idle(controller)
        assertEquals(1,fake.starts);val profile=store.list().single();assertEquals(120000,profile.usedSamples);assertEquals(64,profile.frames)
        assertNull(store.selectedId());assertNull(fake.selected)
        controller.select(profile.id);idle(controller);assertEquals(profile.id,store.selectedId());assertNotNull(fake.selected)
        fake.selected=null;controller.restoreSelected();assertNotNull(fake.selected)
        controller.select(null);idle(controller);assertNull(store.selectedId());assertNull(fake.selected)
    } }
    @Test fun missingAddonAndLowMemoryPreserveExistingSelection()=runBlocking {fixture {recordings,store,fake,controller->
        val a=recording(recordings);controller.create(a.id);idle(controller);val previous=store.list().single()
        controller.select(previous.id);idle(controller);val selected=fake.selected
        val b=recording(recordings);fake.ready=false;controller.create(b.id);idle(controller);assertEquals(1,fake.starts)
        fake.ready=true;fake.fail=true;controller.create(b.id);idle(controller)
        assertEquals(1,store.list().size);assertEquals(previous.id,store.selectedId());assertSame(selected,fake.selected)
        controller.select(null);idle(controller);assertEquals(previous.id,store.selectedId());assertSame(selected,fake.selected)
    } }
    @Test fun cancellationReleasesBusyAndNeverPersistsOrAutomaticallyRestarts()=runBlocking {fixture { recordings,store,fake,controller->
        val entry=recording(recordings);fake.gate=CompletableDeferred();controller.create(entry.id)
        withTimeout(5000){fake.entered.await()};controller.cancelAndJoin();idle(controller)
        assertEquals(1,fake.cancels);assertTrue(store.list().isEmpty());assertEquals(1,fake.starts)
        fake.gate=null;controller.create(entry.id);idle(controller);assertEquals(1,store.list().size)
    } }
    @Test fun guardsPreventRecordingPlaybackConversationAndModelRaces()=runBlocking {fixture {recordings,store,fake,controller->
        fake.allowed=false;controller.create(recording(recordings).id);assertFalse(controller.state.value.busy);assertEquals(0,fake.starts)
        controller.select(null);assertNull(fake.selected);assertTrue(store.list().isEmpty())
        val base=jp.stackchan.localvoicepoc.ui.MainUiState(pocketLoaded=true,ttsBackend=jp.stackchan.localvoicepoc.voicevox.TtsBackend.POCKET,
            referenceRecording=ReferenceRecordingState(open=true))
        assertTrue(base.canManageReferenceVoices)
        assertFalse(base.copy(referenceRecording=ReferenceRecordingState(open=true,phase=RecordingPhase.PLAYING)).canManageReferenceVoices)
        assertFalse(base.copy(referenceRecording=ReferenceRecordingState(open=true,phase=RecordingPhase.RECORDING)).canManageReferenceVoices)
        val busy=base.copy(referenceRecording=ReferenceRecordingState(),pocketVoices=PocketVoiceUiState(busy=true))
        assertFalse(busy.pipelineReady);assertFalse(busy.canChangeAudioMode);assertFalse(busy.canStartModelMutation)
    } }
    @Test fun inputUsesExistingResamplerAndKeepsTheFullRecordingUntouched() {
        for(rate in listOf(16000,22050,24000,48000)) {
            val bytes=ByteBuffer.allocate(rate*2*6).order(ByteOrder.LITTLE_ENDIAN).also {b->repeat(rate*6){b.putShort(8192)}}.array()
            val original=bytes.copyOf();val pcm=PocketVoiceController.input(bytes,rate)
            assertEquals(120000,pcm.size);assertTrue(pcm.all {it==.25f});assertArrayEquals(original,bytes)
        }
        assertTrue(runCatching{PocketVoiceController.input(ByteArray(23998*2),24000)}.isFailure)
    }
    @Test fun failedAddonImportDoesNotChangeProfilesOrVoice()=runBlocking {fixture {_,store,fake,controller->
        controller.importEncoder { error("hash mismatch") };idle(controller)
        assertTrue(controller.state.value.message!!.contains("hash mismatch"));assertTrue(store.list().isEmpty());assertNull(fake.selected)
    } }
    @Test fun restoreRuntimeFailurePreservesTheDesiredSelectionForRetry()=runBlocking {fixture { recordings,store,fake,controller->
        controller.create(recording(recordings).id);idle(controller);val profile=store.list().single()
        controller.select(profile.id);idle(controller);fake.fail=true
        assertTrue(runCatching{controller.restoreSelected()}.isFailure)
        assertEquals(profile.id,store.selectedId())
        fake.fail=false;controller.restoreSelected();assertNotNull(fake.selected)
    } }
    @Test fun deletingSourceRecordingKeepsSelectedVoiceUsableAndRestorable()=runBlocking {fixture {recordings,store,fake,controller->
        val entry=recording(recordings);controller.create(entry.id);idle(controller)
        val voice=store.list().single();val original=store.read(voice)
        controller.select(voice.id);idle(controller);val active=fake.selected
        assertFalse(recordings.delete(entry).cleanupPending);assertTrue(recordings.list().isEmpty())
        assertEquals(voice.id,store.selectedId());assertSame(active,fake.selected);assertEquals(voice,store.list().single())
        original.caches.zip(store.read(voice).caches).forEach{(a,b)->assertArrayEquals(a,b,0f)}
        controller.select(null);idle(controller);assertNull(fake.selected)
        controller.select(voice.id);idle(controller);assertNotNull(fake.selected)
        fake.selected=null;controller.restoreSelected();assertNotNull(fake.selected)
        assertEquals(voice.id,controller.state.value.selectedId)
    } }
}
