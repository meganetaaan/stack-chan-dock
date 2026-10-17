package jp.stackchan.localvoicepoc.ui

import jp.stackchan.localvoicepoc.SdkBootstrap
import jp.stackchan.localvoicepoc.conversation.ConversationPhase
import jp.stackchan.localvoicepoc.model.GemmaModelManifest
import jp.stackchan.localvoicepoc.model.GemmaModelSpec
import jp.stackchan.localvoicepoc.model.ModelComponent
import jp.stackchan.localvoicepoc.mcp.McpApprovalRequest
import jp.stackchan.localvoicepoc.mcp.McpProfile

data class ChatMessage(
    val role: Role,
    val text: String,
) {
    enum class Role { USER, ASSISTANT }
}

data class ComponentProgress(
    val stage: String = "未準備",
    val fraction: Float = 0f,
)

enum class UsbConnectionStatus {
    DISCONNECTED,
    PERMISSION_PENDING,
    CONNECTING,
    READY,
    ERROR,
}

enum class AppStartupStatus {
    CHECKING,
    RESTORING,
    SETUP_REQUIRED,
    READY,
    FAILED,
}

enum class SetupStep {
    MODEL,
    VOICE,
    DEVICE,
}

data class MainUiState(
    val pocketVoices: jp.stackchan.localvoicepoc.pocket.PocketVoiceUiState = jp.stackchan.localvoicepoc.pocket.PocketVoiceUiState(),
    val referenceRecording: jp.stackchan.localvoicepoc.recording.ReferenceRecordingState = jp.stackchan.localvoicepoc.recording.ReferenceRecordingState(),
    val audioMode: jp.stackchan.localvoicepoc.audio.ConversationAudioMode = jp.stackchan.localvoicepoc.audio.ConversationAudioMode.DOCK,
    val audioTransition: Boolean = false,
    val microphoneGranted: Boolean = false,
    val microphoneDenied: Boolean = false,
    val ttsBackend: jp.stackchan.localvoicepoc.voicevox.TtsBackend = jp.stackchan.localvoicepoc.voicevox.TtsBackend.PIPER,
    val piper2Available: Boolean = false,
    val pocketAvailable: Boolean = false,
    val pocketLoaded: Boolean = false,
    val pocketBusy: Boolean = false,
    val pocketProgress: ComponentProgress = ComponentProgress(stage = "モデルZIPを取り込んでください"),
    val pocketTermsConfirmationRequired: Boolean = false,
    val sanoAvailable: Boolean = false,
    val sanoLoaded: Boolean = false,
    val sanoBusy: Boolean = false,
    val sanoProgress: ComponentProgress = ComponentProgress(),
    val sanoTermsConfirmationRequired: Boolean = false,
    val voicevoxAvailable: Boolean = false,
    val voicevoxLoaded: Boolean = false,
    val voicevoxBusy: Boolean = false,
    val voicevoxProgress: ComponentProgress = ComponentProgress(),
    val voicevoxTermsConfirmationRequired: Boolean = false,
    val voicevoxDiagnosticReport: String = "",
    val voicevoxDiagnosticRunning: Boolean = false,
    val ttsPreviewRunning: Boolean = false,
    val startupStatus: AppStartupStatus = AppStartupStatus.CHECKING,
    val startupMessage: String = "保存済みのデータを確認しています",
    val sdkStatus: SdkBootstrap.Status = SdkBootstrap.Status.Starting,
    val selectedGemmaModel: GemmaModelSpec = GemmaModelManifest.default,
    val modelProgress: Map<ModelComponent, ComponentProgress> =
        ModelComponent.entries.associateWith { ComponentProgress() },
    val modelSetupRunning: Boolean = false,
    val modelsReady: Boolean = false,
    val piperAarPresent: Boolean = false,
    val piperModelPresent: Boolean = false,
    val piperConfigPresent: Boolean = false,
    val piperDictionaryPresent: Boolean = false,
    val piperLoaded: Boolean = false,
    val piperBusy: Boolean = false,
    val piperProgress: ComponentProgress = ComponentProgress(stage = "未準備"),
    val piperTermsConfirmationRequired: Boolean = false,
    val phase: ConversationPhase = ConversationPhase.IDLE,
    val audioLevel: Float = 0f,
    val automaticMode: Boolean = false,
    val usbStatus: UsbConnectionStatus = UsbConnectionStatus.DISCONNECTED,
    val usbError: String? = null,
    val mcpProfiles: List<McpProfile> = emptyList(),
    val mcpApprovalRequest: McpApprovalRequest? = null,
    val messages: List<ChatMessage> = emptyList(),
    val assistantDraft: String = "",
    val error: String? = null,
) {
    val selectedPiperRuntimeAvailable: Boolean
        get() = when (ttsBackend) {
            jp.stackchan.localvoicepoc.voicevox.TtsBackend.PIPER -> piperAarPresent
            jp.stackchan.localvoicepoc.voicevox.TtsBackend.PIPER2 -> piper2Available
            else -> false
        }

    val canPreparePiper: Boolean
        get() = selectedPiperRuntimeAvailable && canStartModelMutation

    val canLoadPiper: Boolean
        get() = canPreparePiper && piperModelPresent && piperConfigPresent && piperDictionaryPresent

    val canStartModelMutation: Boolean
        get() = !pocketVoices.busy && !referenceRecording.open && sdkStatus is SdkBootstrap.Status.Ready && phase == ConversationPhase.IDLE && !audioTransition && !modelSetupRunning && !piperBusy && !voicevoxBusy && !sanoBusy && !pocketBusy && !ttsPreviewRunning && !voicevoxDiagnosticRunning

    val canManageReferenceVoices: Boolean
        get() = referenceRecording.open && canChoosePocketVoices

    val canChoosePocketVoices: Boolean
        get() = !pocketVoices.busy && !audioTransition &&
            (!referenceRecording.open || (referenceRecording.phase == jp.stackchan.localvoicepoc.recording.RecordingPhase.IDLE && referenceRecording.draft == null && referenceRecording.deletePrompt == null)) &&
            phase == ConversationPhase.IDLE && ttsBackend == jp.stackchan.localvoicepoc.voicevox.TtsBackend.POCKET && pocketLoaded &&
            !modelSetupRunning && !piperBusy && !voicevoxBusy && !sanoBusy && !pocketBusy && !ttsPreviewRunning && !voicevoxDiagnosticRunning

    val voiceReady: Boolean
        get() = when (ttsBackend) {
            jp.stackchan.localvoicepoc.voicevox.TtsBackend.PIPER, jp.stackchan.localvoicepoc.voicevox.TtsBackend.PIPER2 -> piperLoaded
            jp.stackchan.localvoicepoc.voicevox.TtsBackend.VOICEVOX -> voicevoxLoaded
            jp.stackchan.localvoicepoc.voicevox.TtsBackend.SANO -> sanoLoaded
            jp.stackchan.localvoicepoc.voicevox.TtsBackend.POCKET -> pocketLoaded
        }

    val assetsReady: Boolean
        get() = sdkStatus is SdkBootstrap.Status.Ready && modelsReady && voiceReady && !modelSetupRunning && !piperBusy && !voicevoxBusy && !sanoBusy && !pocketBusy

    val canChangeAudioMode: Boolean
        get() = !pocketVoices.busy && !referenceRecording.open && !audioTransition && !modelSetupRunning && !piperBusy && !voicevoxBusy && !sanoBusy && !pocketBusy && !voicevoxDiagnosticRunning

    val canRequestMicrophone: Boolean
        get() = !pocketVoices.busy && !referenceRecording.open && audioMode == jp.stackchan.localvoicepoc.audio.ConversationAudioMode.PHONE && assetsReady && !microphoneGranted && !audioTransition && !ttsPreviewRunning

    val pipelineReady: Boolean
        get() = !pocketVoices.busy && !referenceRecording.open && assetsReady && !audioTransition && !ttsPreviewRunning && !voicevoxDiagnosticRunning && when (audioMode) {
            jp.stackchan.localvoicepoc.audio.ConversationAudioMode.PHONE -> microphoneGranted
            jp.stackchan.localvoicepoc.audio.ConversationAudioMode.DOCK -> usbStatus == UsbConnectionStatus.READY
        }

    val setupStep: SetupStep
        get() = when {
            !modelsReady -> SetupStep.MODEL
            !voiceReady -> SetupStep.VOICE
            else -> SetupStep.DEVICE
        }
}
