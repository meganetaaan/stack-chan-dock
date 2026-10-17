package jp.stackchan.localvoicepoc.pocket

import dev.irte.voice.PocketVoiceState
import jp.stackchan.localvoicepoc.recording.ReferenceRecordingStore
import jp.stackchan.localvoicepoc.ui.MainUiState
import jp.stackchan.localvoicepoc.voicevox.TtsBackend
import jp.stackchan.localvoicepoc.recording.ReferenceRecordingState
import jp.stackchan.localvoicepoc.recording.RecordingPhase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class PocketPresetControllerTest {
    private class Fixture(val scope: CoroutineScope) {
        val root=Files.createTempDirectory("preset-controller-").toFile()
        val presets=PocketPresetStore(File(root,"presets"))
        val users=PocketVoiceStore(File(root,"users"),presets)
        val recordings=ReferenceRecordingStore(File(root,"recordings"))
        var allowed=true;var recordingAllowed=true;var encoderReady=true
        var downloads=0;var builds=0;var plays=0;var stops=0;var cancels=0
        var failDownloadAt=0;var blockBuildAt=0;var failApply=false;var failPreview=false
        var selected:PocketVoiceState?=null
        var buildGate=CompletableDeferred<Unit>();val enteredBuild=CompletableDeferred<Unit>()
        var previewGate:CompletableDeferred<Unit>?=null;var enteredPreview=CompletableDeferred<Unit>()
        var published=PocketVoiceUiState()
        fun voice(value:Float=.2f)=PocketVoiceState(14,List(12){FloatArray(14*1024){value}})
        fun make()=PocketVoiceController(recordings,users,File(root,"encoder"),{a->a();encoderReady},
            {pcm,_-> builds++;if(builds==blockBuildAt){enteredBuild.complete(Unit);buildGate.await()};voice(pcm[0])},
            {v,commit->check(!failApply){"allocation failed"};commit();selected=v},{cancels++},
            {recordingAllowed},scope,presets=presets,canChoose={allowed},
            playPreview={plays++;enteredPreview.complete(Unit);previewGate?.await();check(!failPreview){"speaker unavailable"}},
            stopPreview={stops++},onState={published=it},reference={_,a,progress->
                a();downloads++;check(downloads!=failDownloadAt){"offline"};progress(1f);FloatArray(24000){downloads/100f}})
        val controller=make()
        fun user():PocketVoiceProfile {
            val profile=users.save(UUID.randomUUID().toString(),"本人の声","a".repeat(64),24000,voice(),{})
            users.select(profile.id);selected=users.read(profile);return profile
        }
    }
    private suspend fun fixture(test:suspend (Fixture)->Unit)=coroutineScope {
        val f=Fixture(this)
        check(f.root.canonicalFile.parentFile==File(requireNotNull(System.getProperty("java.io.tmpdir"))).canonicalFile)
        try {f.controller.refresh();test(f)} finally {f.controller.cancelAndJoin();f.root.deleteRecursively()}
    }
    private suspend fun idle(c:PocketVoiceController)=withTimeout(10000){c.state.first{!it.busy}}
    @Test fun explicitPreparationMakesAllTenSelectableAndPreviewableAndDoesNotSelectAutomatically()=runBlocking {fixture {f->
        assertEquals(setOf(PocketPresets.DEFAULT),f.controller.state.value.readyPresets)
        assertEquals(0,f.downloads)
        f.controller.preparePresets();assertTrue(f.published.busy)
        f.controller.preparePresets();idle(f.controller)
        assertEquals(9,f.downloads);assertEquals(9,f.builds);assertNull(f.users.selectedId());assertNull(f.selected)
        assertEquals(10,f.published.readyPresets.size);assertTrue(f.users.list().isEmpty())
        f.controller.preparePresets();idle(f.controller);assertEquals(9,f.downloads)
        for(spec in PocketPresets.all) {
            val key=PocketPresets.selection(spec.id)
            f.controller.select(key);idle(f.controller);assertEquals(key,f.users.selectedId())
            f.controller.previewSelection(key);idle(f.controller);assertEquals(key,f.users.selectedId())
            assertEquals(key,f.published.selectedId)
        }
        assertEquals(10,f.plays)
    } }
    @Test fun partialFailureRetainsUserVoiceAndRetrySkipsAlreadyCommittedStates()=runBlocking {fixture {f->
        val profile=f.user();val previous=f.selected;f.failDownloadAt=3
        f.controller.preparePresets();idle(f.controller)
        assertEquals(3,f.downloads);assertEquals(3,f.published.readyPresets.size)
        assertEquals(profile.id,f.users.selectedId());assertSame(previous,f.selected)
        assertTrue(f.published.message!!.contains("offline"));assertEquals(profile,f.users.list().single())
        f.failDownloadAt=0;f.controller.preparePresets();idle(f.controller)
        assertEquals(10,f.downloads);assertEquals(9,f.builds);assertEquals(10,f.published.readyPresets.size)
        assertEquals(profile.id,f.users.selectedId())
    } }
    @Test fun cancellationPreservesCompletedStatesAndSelectionAndBlocksRacingOperations()=runBlocking {fixture {f->
        val profile=f.user();val before=f.selected;f.blockBuildAt=2
        f.controller.preparePresets();withTimeout(10000){f.enteredBuild.await()}
        f.controller.preparePresets();f.controller.select(null);f.controller.previewSelection(null)
        f.controller.removePreset("female_01");f.controller.importEncoder {error("must not start")}
        f.controller.create(UUID.randomUUID().toString())
        assertEquals(profile.id,f.users.selectedId());assertEquals(0,f.plays)
        f.controller.cancelAndJoin();idle(f.controller)
        assertEquals(2,f.builds);assertEquals(2,f.published.readyPresets.size)
        assertEquals(profile.id,f.users.selectedId());assertSame(before,f.selected);assertEquals(1,f.cancels)
        assertTrue(f.published.message!!.contains("中止"));assertFalse(f.published.busy)
        f.blockBuildAt=0;f.controller.preparePresets();idle(f.controller)
        assertEquals(10,f.published.readyPresets.size);assertEquals(profile.id,f.users.selectedId())
    } }
    @Test fun missingEncoderOrModelNeverStartsDownloadsAndPreparedVoicesNeedNoEncoder()=runBlocking {fixture {f->
        f.encoderReady=false;f.controller.preparePresets();idle(f.controller)
        assertEquals(0,f.downloads);assertTrue(f.published.message!!.contains("追加モデル"))
        f.allowed=false;f.controller.preparePresets();f.controller.select(null);f.controller.previewSelection(null)
        assertFalse(f.published.busy);assertEquals(0,f.downloads);assertEquals(0,f.plays)
        f.allowed=true;f.encoderReady=true;f.controller.preparePresets();idle(f.controller)
        f.encoderReady=false
        f.controller.previewSelection("preset:male_05");idle(f.controller)
        assertEquals("preset:male_05",f.users.selectedId());assertEquals(1,f.plays)
    } }
    @Test fun choiceAndLocalProfilesRestoreAfterRestartWithoutAnyDownload()=runBlocking {fixture {f->
        val profile=f.user()
        f.controller.preparePresets();idle(f.controller)
        f.controller.select("preset:male_04");idle(f.controller)
        val previous=f.selected!!;f.selected=null;val downloads=f.downloads
        val restarted=f.make();restarted.restoreSelected()
        assertEquals("preset:male_04",restarted.state.value.selectedId)
        assertEquals(previous.caches[0][0],f.selected!!.caches[0][0],0f);assertEquals(downloads,f.downloads)
        restarted.select(profile.id);idle(restarted);assertEquals(profile.id,f.users.selectedId())
        restarted.select(null);idle(restarted);assertNull(f.selected)
    } }
    @Test fun missingOrCorruptSelectedPresetFallsBackToFemale03WithoutDeletingUserVoices()=runBlocking {fixture {f->
        val profile=f.user();f.controller.preparePresets();idle(f.controller)
        f.controller.select("preset:female_02");idle(f.controller)
        File(f.root,"presets/female_02/cache.f32").writeBytes(byteArrayOf(1))
        f.selected=null;val restarted=f.make();restarted.restoreSelected()
        assertNull(f.users.selectedId());assertNull(f.selected)
        assertTrue(restarted.state.value.message!!.contains("標準の声"))
        assertEquals(profile,f.users.list().single());assertFalse("female_02" in restarted.state.value.readyPresets)
        val downloads=f.downloads;f.controller.preparePresets();idle(f.controller);assertEquals(downloads+1,f.downloads)
    } }
    @Test fun failedNativeSelectionPreservesCurrentVoiceAndDesiredRestoreCanRetry()=runBlocking {fixture {f->
        val profile=f.user();val previous=f.selected
        f.controller.preparePresets();idle(f.controller);f.failApply=true
        f.controller.previewSelection("preset:male_01");idle(f.controller)
        assertEquals(profile.id,f.users.selectedId());assertSame(previous,f.selected);assertEquals(0,f.plays)
        f.failApply=false;f.controller.select("preset:male_01");idle(f.controller)
        f.failApply=true
        assertTrue(runCatching {f.make().restoreSelected()}.isFailure)
        assertEquals("preset:male_01",f.users.selectedId())
        f.failApply=false;f.make().restoreSelected();assertNotNull(f.selected)
    } }
    @Test fun previewFailureOrCancellationKeepsExplicitChoiceAndStopsPlaybackWithoutResume()=runBlocking {fixture {f->
        f.controller.preparePresets();idle(f.controller);f.failPreview=true
        f.controller.previewSelection("preset:male_03");idle(f.controller)
        assertEquals("preset:male_03",f.users.selectedId());assertTrue(f.published.message!!.contains("speaker"))
        f.failPreview=false;f.previewGate=CompletableDeferred();f.enteredPreview=CompletableDeferred()
        f.controller.previewSelection("preset:male_02")
        withTimeout(10000){f.enteredPreview.await()};yield()
        assertTrue(f.published.busy)
        f.controller.select(null);f.controller.removePreset("male_02");f.controller.preparePresets()
        f.controller.cancelAndJoin();assertEquals("preset:male_02",f.users.selectedId())
        assertTrue(f.stops>0);assertEquals(2,f.plays);assertFalse(f.published.previewing)
    } }
    @Test fun deletingSelectedPresetReturnsToFemale03AndPreservesOtherAndUserStates()=runBlocking {fixture {f->
        val profile=f.user();f.controller.preparePresets();idle(f.controller)
        f.controller.select("preset:male_01");idle(f.controller)
        f.controller.removePreset("male_01");f.controller.removePreset("male_01");idle(f.controller)
        assertNull(f.users.selectedId());assertNull(f.selected);assertFalse(f.presets.isReady("male_01"))
        assertTrue(f.presets.isReady("male_02"));assertEquals(profile,f.users.list().single())
        f.controller.select(profile.id);idle(f.controller)
        f.controller.removePreset("male_02");idle(f.controller)
        assertEquals(profile.id,f.users.selectedId());assertNotNull(f.selected)
    } }
    @Test fun settingsChoiceGuardRejectsBusyRecordingDraftDeletionAndMissingModels() {
        val base=MainUiState(pocketLoaded=true,ttsBackend=TtsBackend.POCKET)
        assertTrue(base.canChoosePocketVoices);assertFalse(base.canManageReferenceVoices)
        assertFalse(base.copy(pocketLoaded=false).canChoosePocketVoices)
        assertFalse(base.copy(ttsBackend=TtsBackend.SANO).canChoosePocketVoices)
        assertFalse(base.copy(audioTransition=true).canChoosePocketVoices)
        assertFalse(base.copy(pocketVoices=PocketVoiceUiState(busy=true)).canChoosePocketVoices)
        assertFalse(base.copy(ttsPreviewRunning=true).canChoosePocketVoices)
        for(phase in listOf(RecordingPhase.RECORDING,RecordingPhase.PLAYING,RecordingPhase.DELETING,RecordingPhase.SAVING)) {
            assertFalse(base.copy(referenceRecording=ReferenceRecordingState(open=true,phase=phase)).canChoosePocketVoices)
        }
    }
}
