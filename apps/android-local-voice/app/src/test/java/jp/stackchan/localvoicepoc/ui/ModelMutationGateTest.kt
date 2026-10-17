package jp.stackchan.localvoicepoc.ui

import jp.stackchan.localvoicepoc.conversation.ConversationPhase
import jp.stackchan.localvoicepoc.voicevox.TtsBackend
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelMutationGateTest {
    private val piperFilesReady = MainUiState(
        sdkStatus = jp.stackchan.localvoicepoc.SdkBootstrap.Status.Ready,
        piperModelPresent = true,
        piperConfigPresent = true,
        piperDictionaryPresent = true,
    )

    @Test
    fun piper2OnlyInstallationCanPrepareAndLoadItsFirstVoice() {
        val state = piperFilesReady.copy(ttsBackend = TtsBackend.PIPER2, piper2Available = true)
        assertFalse(state.piperAarPresent)
        assertTrue(state.canPreparePiper)
        assertTrue(state.canLoadPiper)
    }

    @Test
    fun piper1OnlyInstallationKeepsPreparationAndLoading() {
        val state = piperFilesReady.copy(piperAarPresent = true)
        assertFalse(state.piper2Available)
        assertTrue(state.canPreparePiper)
        assertTrue(state.canLoadPiper)
    }

    @Test
    fun missingSelectedRuntimeCannotUseTheOtherPiperAvailability() {
        val states = listOf(
            piperFilesReady.copy(ttsBackend = TtsBackend.PIPER2, piperAarPresent = true),
            piperFilesReady.copy(piper2Available = true),
        )
        states.forEach { state ->
            assertFalse(state.canPreparePiper)
            assertFalse(state.canLoadPiper)
        }
    }

    @Test
    fun otherSelectedEnginesCannotStartPiperSetup() {
        TtsBackend.entries.filterNot { it.isPiper }.forEach { backend ->
            val state = piperFilesReady.copy(ttsBackend = backend, piperAarPresent = true, piper2Available = true)
            assertFalse(state.canPreparePiper)
            assertFalse(state.canLoadPiper)
        }
    }

    @Test
    fun manualLoadingRequiresAllAssetsAndPreservesMutationExclusion() {
        val ready = piperFilesReady.copy(ttsBackend = TtsBackend.PIPER2, piper2Available = true)
        listOf(ready.copy(piperModelPresent = false), ready.copy(piperConfigPresent = false),
            ready.copy(piperDictionaryPresent = false)).forEach { state ->
            assertTrue(state.canPreparePiper)
            assertFalse(state.canLoadPiper)
        }
        listOf(ready.copy(modelSetupRunning = true), ready.copy(piperBusy = true),
            ready.copy(ttsPreviewRunning = true), ready.copy(phase = ConversationPhase.SPEAKING)).forEach { state ->
            assertFalse(state.canPreparePiper)
            assertFalse(state.canLoadPiper)
        }
    }

    @Test
    fun permitsMutationOnlyWhileIdleAndNoOtherMutationIsRunning() {
        val ready = MainUiState(sdkStatus = jp.stackchan.localvoicepoc.SdkBootstrap.Status.Ready)
        assertTrue(ready.canStartModelMutation)

        ConversationPhase.entries
            .filterNot { it == ConversationPhase.IDLE }
            .forEach { phase ->
                assertFalse("phase=$phase", ready.copy(phase = phase).canStartModelMutation)
            }
        assertFalse(ready.copy(modelSetupRunning = true).canStartModelMutation)
        assertFalse(ready.copy(piperBusy = true).canStartModelMutation)
    }
}
