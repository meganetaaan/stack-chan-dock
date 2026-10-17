package jp.stackchan.localvoicepoc.ui

import jp.stackchan.localvoicepoc.SdkBootstrap
import jp.stackchan.localvoicepoc.audio.ConversationAudioMode
import jp.stackchan.localvoicepoc.conversation.ConversationPhase
import jp.stackchan.localvoicepoc.voicevox.TtsBackend
import org.junit.Assert.*
import org.junit.Test

class PhoneModeStateTest {
    @Test fun conversationFirstScreenCannotPrepareModelsDuringSdkStartup() {
        assertFalse(MainUiState().canStartModelMutation)
        assertTrue(MainUiState(sdkStatus=SdkBootstrap.Status.Ready).canStartModelMutation)
    }
    private fun ready() = MainUiState(sdkStatus=SdkBootstrap.Status.Ready, modelsReady=true,
        piperLoaded=true, audioMode=ConversationAudioMode.PHONE, microphoneGranted=true)
    @Test fun phoneStartsWithoutUsbButPermissionAndModelsRemainRequired() {
        val state=ready()
        assertTrue(state.pipelineReady)
        assertFalse(state.copy(microphoneGranted=false).pipelineReady)
        assertTrue(state.copy(microphoneGranted=false).canRequestMicrophone)
        assertFalse(state.copy(modelsReady=false).canRequestMicrophone)
        assertFalse(state.copy(modelsReady=false).pipelineReady)
        assertFalse(state.copy(piperLoaded=false).pipelineReady)
        assertFalse(state.copy(audioTransition=true).pipelineReady)
        assertFalse(state.copy(ttsPreviewRunning=true).pipelineReady)
        assertFalse(state.copy(pocketBusy=true).pipelineReady)
    }
    @Test fun deniedPermissionAndUsbChangesDoNotChangeTheExplicitMode() {
        val phone=ready().copy(microphoneGranted=false,microphoneDenied=true)
        for (usb in UsbConnectionStatus.entries) {
            val state=phone.copy(usbStatus=usb)
            assertEquals(ConversationAudioMode.PHONE,state.audioMode)
            assertFalse(state.pipelineReady);assertTrue(state.canRequestMicrophone)
        }
        val dock=ready().copy(audioMode=ConversationAudioMode.DOCK,microphoneGranted=false)
        assertFalse(dock.pipelineReady);assertFalse(dock.canRequestMicrophone)
        assertTrue(dock.copy(usbStatus=UsbConnectionStatus.READY).pipelineReady)
    }
    @Test fun switchingStopsFirstAndTtsMutationWaitsForIdle() {
        val speaking=ready().copy(phase=ConversationPhase.SPEAKING)
        assertTrue(speaking.canChangeAudioMode)
        assertFalse(speaking.canStartModelMutation)
        val switching=speaking.copy(audioTransition=true)
        assertFalse(switching.canChangeAudioMode)
        assertFalse(switching.pipelineReady)
        assertFalse(switching.canStartModelMutation)
        assertFalse(ready().copy(voicevoxDiagnosticRunning=true).canChangeAudioMode)
    }
    @Test fun everySelectedVoiceMustBeReadyInPhoneMode() {
        for (tts in TtsBackend.entries) {
            val state=ready().copy(ttsBackend=tts,piperLoaded=false)
            assertFalse(state.pipelineReady)
            val loaded=when(tts) {
                TtsBackend.PIPER,TtsBackend.PIPER2 -> state.copy(piperLoaded=true)
                TtsBackend.VOICEVOX -> state.copy(voicevoxLoaded=true)
                TtsBackend.SANO -> state.copy(sanoLoaded=true)
                TtsBackend.POCKET -> state.copy(pocketLoaded=true)
            }
            assertTrue("tts=$tts",loaded.pipelineReady)
        }
    }
}
