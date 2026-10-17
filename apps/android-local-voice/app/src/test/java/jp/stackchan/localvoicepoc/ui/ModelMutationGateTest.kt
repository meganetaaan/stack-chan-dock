package jp.stackchan.localvoicepoc.ui

import jp.stackchan.localvoicepoc.conversation.ConversationPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelMutationGateTest {
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
