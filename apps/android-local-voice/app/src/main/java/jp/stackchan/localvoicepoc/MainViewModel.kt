package jp.stackchan.localvoicepoc

import android.app.Application
import jp.stackchan.localvoicepoc.audio.ConversationAudioMode
import jp.stackchan.localvoicepoc.audio.AndroidMicrophoneSource
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioAttributes
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.stackchan.localvoicepoc.conversation.ConversationEngine
import jp.stackchan.localvoicepoc.conversation.ConversationEvent
import jp.stackchan.localvoicepoc.conversation.ConversationPhase
import jp.stackchan.localvoicepoc.diagnostics.PlaybackTraceStore
import jp.stackchan.localvoicepoc.diagnostics.RecognitionCaptureStore
import jp.stackchan.localvoicepoc.model.GemmaModelManifest
import jp.stackchan.localvoicepoc.model.GemmaModelPreferences
import jp.stackchan.localvoicepoc.model.SelectableLanguageModel
import jp.stackchan.localvoicepoc.model.DeviceToolRegistry
import jp.stackchan.localvoicepoc.mcp.AndroidMcpToolProvider
import jp.stackchan.localvoicepoc.mcp.McpApprovalRequest
import jp.stackchan.localvoicepoc.mcp.McpProfile
import jp.stackchan.localvoicepoc.mcp.McpProfileStore
import jp.stackchan.localvoicepoc.realtime.ConversationCommandHandler
import jp.stackchan.localvoicepoc.realtime.ConversationCommandResult
import jp.stackchan.localvoicepoc.realtime.RealtimeSessionController
import jp.stackchan.localvoicepoc.realtime.RemoteConversationStartDecision
import jp.stackchan.localvoicepoc.realtime.RemoteConversationState
import jp.stackchan.localvoicepoc.realtime.decideRemoteConversationStart
import jp.stackchan.localvoicepoc.realtime.stackChanStatusFor
import jp.stackchan.localvoicepoc.model.ModelComponent
import jp.stackchan.localvoicepoc.model.ModelSetupManager
import jp.stackchan.localvoicepoc.piper.PiperAssetStore
import jp.stackchan.localvoicepoc.voicevox.SelectableSynthesizer
import jp.stackchan.localvoicepoc.voicevox.TtsBackend
import jp.stackchan.localvoicepoc.voicevox.VoicevoxAssetStore
import jp.stackchan.localvoicepoc.voicevox.playTtsPreview
import jp.stackchan.localvoicepoc.audio.AndroidPcmAudioSink
import jp.stackchan.localvoicepoc.speech.SherpaWhisperRecognizer
import jp.stackchan.localvoicepoc.serial.SerialPcmAudioSink
import jp.stackchan.localvoicepoc.serial.SerialPcmAudioSource
import jp.stackchan.localvoicepoc.serial.StackChanCapabilities
import jp.stackchan.localvoicepoc.serial.StackChanControl
import jp.stackchan.localvoicepoc.serial.StackChanStatus
import jp.stackchan.localvoicepoc.serial.StackChanUsbConnection
import jp.stackchan.localvoicepoc.serial.StackChanUsbState
import jp.stackchan.localvoicepoc.ui.ChatMessage
import jp.stackchan.localvoicepoc.ui.AppPreferences
import jp.stackchan.localvoicepoc.ui.AppStartupStatus
import jp.stackchan.localvoicepoc.ui.ComponentProgress
import jp.stackchan.localvoicepoc.ui.MainUiState
import jp.stackchan.localvoicepoc.ui.UsbConnectionStatus
import jp.stackchan.localvoicepoc.ui.StartupAssetDecision
import jp.stackchan.localvoicepoc.ui.decideStartupAssets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val voiceDiagnosticsDirectory = application.getExternalFilesDir("voice-diagnostics")
        ?: File(application.filesDir, "voice-diagnostics")
    private val recognitionCaptureStore = RecognitionCaptureStore(
        File(voiceDiagnosticsDirectory, "recognition-captures"),
    )
    private val playbackTraceStore = PlaybackTraceStore(
        File(voiceDiagnosticsDirectory, "playback-traces"),
    )
    private val gemmaPreferences = GemmaModelPreferences(application)
    private val appPreferences = AppPreferences(application)
    private val mcpProfileStore = McpProfileStore(application)
    private val toolRegistry = DeviceToolRegistry(application)
    private val mcpApprovalLock = Any()
    private var pendingMcpApproval: CompletableDeferred<Boolean>? = null
    private lateinit var realtimeSession: RealtimeSessionController
    private val speechRecognizer = SherpaWhisperRecognizer()
    private val languageModel = SelectableLanguageModel(application, toolRegistry) {
        if (::realtimeSession.isInitialized) realtimeSession.instructions else ""
    }
    private val modelManager = ModelSetupManager(application, speechRecognizer, languageModel)
    private val piperStore = PiperAssetStore(application)
    private val voicevoxStore = VoicevoxAssetStore(application)
    private val sanoStore = jp.stackchan.localvoicepoc.sano.SanoAssetStore(application)
    private val pocketStore = jp.stackchan.localvoicepoc.pocket.PocketAssetStore(application)
    private val pocketEncoderStore = jp.stackchan.localvoicepoc.pocket.PocketEncoderStore(application)
    private val pocketPresetStore = jp.stackchan.localvoicepoc.pocket.PocketPresetStore(File(application.filesDir, "pocket-presets-v1"))
    private val pocketVoiceStore = jp.stackchan.localvoicepoc.pocket.PocketVoiceStore(File(application.filesDir, "pocket-voices"), pocketPresetStore)
    private var pendingEncoderImport: Uri? = null
    private var pendingPocketImport: Uri? = null
    private var pendingSanoImport: Uri? = null
    private val ttsPreferences = application.getSharedPreferences("tts-backend", Application.MODE_PRIVATE)
    private val synthesizer = SelectableSynthesizer(application)
    private val usbConnection = StackChanUsbConnection(application)
    private val mcpTools = AndroidMcpToolProvider(mcpProfileStore) { request -> awaitMcpApproval(request) }
    private val serialAudioSource = SerialPcmAudioSource(usbConnection)
    private val serialAudioSink = SerialPcmAudioSink(
        connection = usbConnection,
        playbackTraceStore = playbackTraceStore,
    )
    private val phoneSource = AndroidMicrophoneSource()
    private val phoneSink = AndroidPcmAudioSink()
    private var foreground = false
    private var audioOperation: Job? = null
    private val audioManager = application.getSystemService(Application.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val referenceSource = AndroidMicrophoneSource(sampleRate = 24000,
        audioSource = if (audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") android.media.MediaRecorder.AudioSource.UNPROCESSED else android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION)
    private val referenceSink = AndroidPcmAudioSink()
    private val referenceRecordingStore = jp.stackchan.localvoicepoc.recording.ReferenceRecordingStore(File(application.filesDir, "reference-recordings"))
    private val referenceRecorder = jp.stackchan.localvoicepoc.recording.ReferenceRecorder(referenceSource, referenceSink, referenceRecordingStore, viewModelScope)
    private val referenceVoices = jp.stackchan.localvoicepoc.pocket.PocketVoiceController(
        referenceRecordingStore, pocketVoiceStore, pocketEncoderStore.directory, pocketEncoderStore::isReady,
        synthesizer::encodePocketVoice, synthesizer::selectPocketVoice, synthesizer::cancel,
        { canManageReferenceVoices() }, viewModelScope,
        presets = pocketPresetStore, canChoose = { canChoosePocketVoice() },
        playPreview = { previewPocketVoiceAudio() },
        stopPreview = { previewAudioSink.stop(); releaseAudioFocus() },
        onState = { voices -> mutableState.update { it.copy(pocketVoices = voices) } })
    private val engine = ConversationEngine(
        audioSource = if (appPreferences.audioMode() == ConversationAudioMode.PHONE) phoneSource else serialAudioSource,
        audioSink = if (appPreferences.audioMode() == ConversationAudioMode.PHONE) phoneSink else serialAudioSink,
        synthesizer = synthesizer,
        speechRecognizer = speechRecognizer,
        languageModel = languageModel,
        recognitionCaptureStore = recognitionCaptureStore,
    )

    private val mutableState = MutableStateFlow(
        MainUiState(
            audioMode = appPreferences.audioMode(),
            selectedGemmaModel = gemmaPreferences.selected(),
            ttsBackend = runCatching { TtsBackend.valueOf(ttsPreferences.getString("backend", "PIPER")!!) }.getOrDefault(TtsBackend.PIPER),
            voicevoxAvailable = synthesizer.voicevoxAvailable,
            sanoAvailable = synthesizer.sanoAvailable,
            pocketAvailable = synthesizer.pocketAvailable,
            piper2Available = synthesizer.piper2Available,
            piperAarPresent = synthesizer.runtimeAvailable,
            mcpProfiles = mcpProfileStore.profiles(),
        ),
    )
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()
    private val stackChanStatusMutex = Mutex()
    private var lastStackChanStatus: StackChanStatus? = null
    private var startupAttempted = false
    private var previewAudioSink = AndroidPcmAudioSink()
    private var previewIteration = 0
    private var previewJob: Job? = null
    @Volatile private var diagnosticSink: AndroidPcmAudioSink? = null
    @Volatile private var diagnosisShutdown = false

    init {
        viewModelScope.launch {
            referenceRecorder.state.collect { recording ->
                mutableState.update { it.copy(referenceRecording = recording) }
                if (recording.open && recording.phase == jp.stackchan.localvoicepoc.recording.RecordingPhase.IDLE) releaseAudioFocus()
            }
        }
        realtimeSession = RealtimeSessionController(
            transport = usbConnection,
            registry = toolRegistry,
            mcp = mcpTools,
            conversationCommands = object : ConversationCommandHandler {
                override suspend fun start(): ConversationCommandResult = handleRemoteConversationStart()
                override suspend fun stop(): ConversationCommandResult = handleRemoteConversationStop()
            },
            scope = viewModelScope,
        ).also { it.start() }
        Log.i(TAG, "Voice diagnostics directory: ${voiceDiagnosticsDirectory.absolutePath}")
        refreshPiperFiles()
        viewModelScope.launch {
            SdkBootstrap.status.collect { status ->
                mutableState.update { it.copy(sdkStatus = status) }
                when (status) {
                    SdkBootstrap.Status.Ready -> restorePreparedAssetsIfAvailable()
                    is SdkBootstrap.Status.Failed -> mutableState.update {
                        it.copy(
                            startupStatus = AppStartupStatus.FAILED,
                            startupMessage = "アプリの初期化に失敗しました",
                        )
                    }
                    SdkBootstrap.Status.Starting -> Unit
                }
            }
        }
        viewModelScope.launch {
            engine.events.collect(::handleConversationEvent)
        }
        viewModelScope.launch {
            usbConnection.state.collect { connectionState ->
                val (status, error) = when (connectionState) {
                    StackChanUsbState.Disconnected -> UsbConnectionStatus.DISCONNECTED to null
                    StackChanUsbState.PermissionPending -> UsbConnectionStatus.PERMISSION_PENDING to null
                    StackChanUsbState.Connecting -> UsbConnectionStatus.CONNECTING to null
                    is StackChanUsbState.Ready -> UsbConnectionStatus.READY to null
                    is StackChanUsbState.Error -> UsbConnectionStatus.ERROR to connectionState.message
                }
                mutableState.update { it.copy(usbStatus = status, usbError = error) }
                lastStackChanStatus = null
                if (connectionState is StackChanUsbState.Ready) {
                    syncStackChanStatus(mutableState.value.phase)
                }
                if (mutableState.value.audioMode == ConversationAudioMode.DOCK && status != UsbConnectionStatus.READY && mutableState.value.phase != ConversationPhase.IDLE) {
                    engine.stop()
                }
            }
        }
    }

    fun prepareModels() {
        val current = mutableState.value
        if (!current.canStartModelMutation) return
        val selectedModel = current.selectedGemmaModel
        mutableState.update { it.copy(modelSetupRunning = true, modelsReady = false, error = null) }
        viewModelScope.launch {
            try {
                modelManager.prepareAll(selectedModel) { progress ->
                    mutableState.update { current ->
                        current.copy(
                            modelProgress = current.modelProgress + (
                                progress.component to ComponentProgress(
                                    stage = progress.stage,
                                    fraction = progress.fraction,
                                )
                            ),
                        )
                    }
                }
                mutableState.update {
                    it.copy(
                        modelSetupRunning = false,
                        modelsReady = true,
                        startupStatus = AppStartupStatus.SETUP_REQUIRED,
                        error = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        modelSetupRunning = false,
                        modelsReady = false,
                        error = "モデル準備に失敗: ${error.message ?: error::class.java.simpleName}",
                    )
                }
            }
        }
    }

    fun selectGemmaModel(modelId: String) {
        val selectedModel = GemmaModelManifest.find(modelId) ?: return
        val current = mutableState.value
        if (current.selectedGemmaModel == selectedModel || !current.canStartModelMutation) {
            return
        }

        gemmaPreferences.select(selectedModel)
        mutableState.update { state ->
            state.copy(
                selectedGemmaModel = selectedModel,
                modelsReady = false,
                modelSetupRunning = true,
                modelProgress = state.modelProgress + (
                    ModelComponent.LLM to ComponentProgress(stage = "モデル切替中")
                ),
                error = null,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val closeError = runCatching { languageModel.shutdown() }.exceptionOrNull()
            mutableState.update { state ->
                if (state.selectedGemmaModel != selectedModel) {
                    state
                } else {
                    state.copy(
                        modelSetupRunning = false,
                        modelProgress = state.modelProgress + (
                            ModelComponent.LLM to ComponentProgress(stage = "未準備")
                        ),
                        error = closeError?.let {
                            "モデル切替に失敗: ${it.message ?: it::class.java.simpleName}"
                        },
                    )
                }
            }
        }
    }

    fun selectTtsBackend(backend: TtsBackend) {
        val current = mutableState.value
        if (!current.canStartModelMutation || current.ttsBackend == backend) return
        if (backend == TtsBackend.VOICEVOX && !current.voicevoxAvailable) return
        if (backend == TtsBackend.SANO && !current.sanoAvailable) return
        if (backend == TtsBackend.POCKET && !current.pocketAvailable) return
        if (backend == TtsBackend.PIPER2 && !current.piper2Available) return
        mutableState.update { it.copy(ttsBackend = backend, piperLoaded = false, voicevoxLoaded = false, sanoLoaded = false, pocketLoaded = false,
            sanoTermsConfirmationRequired = false, pocketTermsConfirmationRequired = false,
            voicevoxBusy = true, error = null) }
        viewModelScope.launch {
            try {
                synthesizer.select(backend)
                previewIteration = 0
                if (backend.isPiper) {
                    val installation = withContext(Dispatchers.IO) { piperStore.current() }
                    if (installation.isComplete) {
                        synthesizer.load(installation)
                        mutableState.update { it.copy(piperLoaded = true) }
                    }
                }
                if (backend == TtsBackend.POCKET && pocketStore.isReady()) {
                    loadPocketWithVoice(pocketStore.current())
                    mutableState.update { it.copy(pocketLoaded = true, pocketProgress = ComponentProgress("準備完了", 1f)) }
                }
                ttsPreferences.edit().putString("backend", backend.name).apply()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                showError(error)
            } finally { mutableState.update { it.copy(voicevoxBusy = false) } }
        }
    }

    fun preparePocket() {
        if (!mutableState.value.canStartModelMutation || mutableState.value.ttsBackend != TtsBackend.POCKET) return
        pendingPocketImport = null
        if (!pocketStore.hasConfirmedTerms()) mutableState.update { it.copy(pocketTermsConfirmationRequired = true) }
        else startPocketSetup(null)
    }

    fun importPocket(uri: Uri) {
        if (!mutableState.value.canStartModelMutation || mutableState.value.ttsBackend != TtsBackend.POCKET) return
        if (!pocketStore.hasConfirmedTerms()) {
            pendingPocketImport = uri
            mutableState.update { it.copy(pocketTermsConfirmationRequired = true) }
        } else startPocketSetup(uri)
    }

    fun confirmPocketTerms() {
        if (!mutableState.value.canStartModelMutation || mutableState.value.ttsBackend != TtsBackend.POCKET) return
        pocketStore.confirmTerms()
        mutableState.update { it.copy(pocketTermsConfirmationRequired = false) }
        val uri = pendingPocketImport; pendingPocketImport = null
        startPocketSetup(uri)
    }

    fun dismissPocketTerms() {
        pendingPocketImport = null
        mutableState.update { it.copy(pocketTermsConfirmationRequired = false) }
    }

    private fun startPocketSetup(uri: Uri?) {
        val current = mutableState.value
        if (!current.canStartModelMutation || current.ttsBackend != TtsBackend.POCKET || !current.pocketAvailable) return
        mutableState.update { it.copy(pocketBusy = true, pocketLoaded = false, error = null) }
        viewModelScope.launch {
            try {
                // Release mapped model files before replacing the complete verified directory.
                withContext(Dispatchers.IO) { synthesizer.close() }
                val installation = if (uri == null) pocketStore.current() else pocketStore.import(uri) { stage, fraction ->
                    mutableState.update { it.copy(pocketProgress = ComponentProgress(stage, fraction)) }
                }
                mutableState.update { it.copy(pocketProgress = ComponentProgress("モデルを検証・読み込み中", 1f)) }
                loadPocketWithVoice(installation)
                previewIteration = 0
                mutableState.update { it.copy(pocketLoaded = true, pocketProgress = ComponentProgress("準備完了", 1f)) }
            } catch (error: CancellationException) { throw error
            } catch (error: Throwable) { showError(error)
            } finally { mutableState.update { it.copy(pocketBusy = false, pocketLoaded = synthesizer.isLoaded) } }
        }
    }

    fun prepareSano() {
        if (!mutableState.value.canStartModelMutation || mutableState.value.ttsBackend != TtsBackend.SANO) return
        pendingSanoImport = null
        if (!sanoStore.hasConfirmedTerms()) {
            mutableState.update { it.copy(sanoTermsConfirmationRequired = true) }
        } else startSanoSetup(null)
    }

    fun importSano(uri: Uri) {
        if (!mutableState.value.canStartModelMutation || mutableState.value.ttsBackend != TtsBackend.SANO) return
        if (!sanoStore.hasConfirmedTerms()) {
            pendingSanoImport = uri
            mutableState.update { it.copy(sanoTermsConfirmationRequired = true) }
        } else startSanoSetup(uri)
    }

    fun confirmSanoTerms() {
        if (!mutableState.value.canStartModelMutation || mutableState.value.ttsBackend != TtsBackend.SANO) return
        sanoStore.confirmTerms()
        mutableState.update { it.copy(sanoTermsConfirmationRequired = false) }
        val uri = pendingSanoImport
        pendingSanoImport = null
        startSanoSetup(uri)
    }

    fun dismissSanoTerms() {
        pendingSanoImport = null
        mutableState.update { it.copy(sanoTermsConfirmationRequired = false) }
    }

    private fun startSanoSetup(uri: Uri?) {
        val current = mutableState.value
        if (!current.canStartModelMutation || current.ttsBackend != TtsBackend.SANO || !current.sanoAvailable) return
        mutableState.update { it.copy(sanoBusy = true, sanoLoaded = false, error = null) }
        viewModelScope.launch {
            try {
                val installation = if (uri == null) sanoStore.prepare { stage, fraction ->
                    mutableState.update { it.copy(sanoProgress = ComponentProgress(stage, fraction)) }
                } else sanoStore.import(uri)
                if (sanoStore.isReady()) {
                    synthesizer.loadSano(installation)
                    mutableState.update { it.copy(sanoLoaded = true, sanoProgress = ComponentProgress("準備完了", 1f)) }
                } else {
                    mutableState.update { it.copy(sanoProgress = ComponentProgress("検証済みファイルを保存しました。モデルと辞書の両方を取り込んでください。", 0f)) }
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Throwable) { showError(error)
            } finally { mutableState.update { it.copy(sanoBusy = false, sanoLoaded = synthesizer.isLoaded) } }
        }
    }

    fun prepareVoicevox() {
        if (!mutableState.value.canStartModelMutation || mutableState.value.ttsBackend != TtsBackend.VOICEVOX) return
        if (!voicevoxStore.hasConfirmedTerms()) {
            mutableState.update { it.copy(voicevoxTermsConfirmationRequired = true) }
            return
        }
        startVoicevoxSetup()
    }

    fun confirmVoicevoxTerms() {
        if (!mutableState.value.canStartModelMutation) return
        voicevoxStore.confirmTerms()
        mutableState.update { it.copy(voicevoxTermsConfirmationRequired = false) }
        startVoicevoxSetup()
    }
    fun dismissVoicevoxTerms() { mutableState.update { it.copy(voicevoxTermsConfirmationRequired = false) } }

    private fun startVoicevoxSetup() {
        val current = mutableState.value
        if (!current.canStartModelMutation || current.ttsBackend != TtsBackend.VOICEVOX || !current.voicevoxAvailable) return
        mutableState.update { it.copy(voicevoxBusy = true, voicevoxLoaded = false, error = null) }
        viewModelScope.launch {
            try {
                val installation = voicevoxStore.prepare { stage, fraction ->
                    mutableState.update { it.copy(voicevoxProgress = ComponentProgress(stage, fraction)) }
                }
                synthesizer.loadVoicevox(installation)
                mutableState.update { it.copy(voicevoxLoaded = true, voicevoxProgress = ComponentProgress("準備完了", 1f)) }
            } catch (error: CancellationException) { throw error
            } catch (error: Throwable) { showError(error)
            } finally { mutableState.update { it.copy(voicevoxBusy = false) } }
        }
    }

    fun importPiperModel(uri: Uri) = launchPiperOperation {
        piperStore.importModel(uri)
    }

    fun importPiperConfig(uri: Uri) = launchPiperOperation {
        piperStore.importConfig(uri)
    }

    fun importPiperDictionary(uri: Uri) = launchPiperOperation {
        piperStore.importDictionary(uri)
    }

    fun prepareRecommendedPiper() {
        if (!mutableState.value.ttsBackend.isPiper) return
        val current = mutableState.value
        if (!current.canPreparePiper) return
        if (!piperStore.hasConfirmedRecommendedTerms()) {
            mutableState.update {
                it.copy(piperTermsConfirmationRequired = true, error = null)
            }
            return
        }
        startRecommendedPiperSetup()
    }

    fun confirmRecommendedPiperTerms() {
        if (!mutableState.value.canPreparePiper) return
        piperStore.confirmRecommendedTerms()
        mutableState.update { it.copy(piperTermsConfirmationRequired = false) }
        startRecommendedPiperSetup()
    }

    fun dismissRecommendedPiperTerms() {
        mutableState.update { it.copy(piperTermsConfirmationRequired = false) }
    }

    fun loadPiper() {
        if (!mutableState.value.ttsBackend.isPiper) return
        if (!mutableState.value.canLoadPiper) return
        mutableState.update {
            it.copy(
                piperBusy = true,
                piperLoaded = false,
                piperProgress = ComponentProgress(stage = "Piper Plusをロード中"),
                error = null,
            )
        }
        viewModelScope.launch {
            try {
                synthesizer.load(piperStore.current())
                previewIteration = 0
                mutableState.update { state ->
                    state.copy(
                        piperBusy = false,
                        piperLoaded = true,
                        piperProgress = ComponentProgress(stage = "ロード済み", fraction = 1f),
                        error = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update { state ->
                    state.copy(
                        piperBusy = false,
                        piperLoaded = false,
                        piperProgress = ComponentProgress(stage = "ロード失敗"),
                        error = "Piper Plusのロードに失敗: ${error.message}",
                    )
                }
            }
        }
    }

    fun setAutomaticMode(enabled: Boolean) {
        if (mutableState.value.phase != ConversationPhase.IDLE) return
        mutableState.update { it.copy(automaticMode = enabled) }
    }

    fun previewVoice() {
        val current = mutableState.value
        if (!foreground || !current.canStartModelMutation || !current.voiceReady || !acquireAudioFocus()) return
        val backend = current.ttsBackend
        val iteration = previewIteration++
        val timing = jp.stackchan.localvoicepoc.voicevox.TtsTiming(
            "${backend.label}; ${if (iteration == 0) "first-preview-after-load" else "warm$iteration"}; LLM loaded=${languageModel.isLoaded}")
        previewAudioSink.stop()
        previewAudioSink = AndroidPcmAudioSink(timing)
        mutableState.update { it.copy(ttsPreviewRunning = true, error = null) }
        previewJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var outcome = "completed"
            try {
                val modelHash = withContext(Dispatchers.IO) {
                    val model = when (backend) {
                        TtsBackend.PIPER, TtsBackend.PIPER2 -> piperStore.current().model
                        TtsBackend.VOICEVOX -> voicevoxStore.current().model
                        TtsBackend.SANO -> sanoStore.current().model
                        TtsBackend.POCKET -> File(pocketStore.current().directory, "flow_lm_main.onnx")
                    }
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    model.inputStream().buffered().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            digest.update(buffer,0,n)
                        }
                    }
                    digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                }
                timing.value("modelSha256",modelHash)
                timing.value("pssBeforeKiB",android.os.Debug.getPss().toString())
                timing.value("nativeHeapBeforeBytes",android.os.Debug.getNativeHeapAllocatedSize().toString())
                if (backend == TtsBackend.POCKET) timing.value("options","cpu/threads=1/FP32/voice=" + (jp.stackchan.localvoicepoc.pocket.PocketPresets.selected(current.pocketVoices.selectedId)?.id ?: "local-reference") + "/seed=0/temperature=0.3/lsdSteps=1")
                if (backend.isPiper) timing.value("options","cpu/threads=auto/speaker=0/noise=0.667/length=1.0/noiseW=0.8")
                playTtsPreview(synthesizer.withTiming(timing), previewAudioSink, timing)
            } catch (error: CancellationException) {
                outcome = "cancelled"; timing.value("errorType",error::class.java.simpleName)
                throw error
            } catch (error: Throwable) {
                outcome = "failed"; timing.value("errorType",error::class.java.simpleName)
                showError(error)
            } finally {
                previewAudioSink.stop()
                releaseAudioFocus()
                timing.value("outcome",outcome)
                timing.value("pssAfterKiB",android.os.Debug.getPss().toString())
                timing.value("nativeHeapAfterBytes",android.os.Debug.getNativeHeapAllocatedSize().toString())
                timing.value("timingScope","API/observed playback head and timestamp; acoustic onset is not measured")
                timing.value("memoryScope","Process PSS/native heap snapshots before/after; peak is not sampled")
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    runCatching {
                        val file = java.io.File(voiceDiagnosticsDirectory,"tts-preview.jsonl")
                        file.parentFile?.mkdirs()
                        val record = org.json.JSONObject().put("schema",1).put("backend",backend.name)
                            .put("label",timing.label).put("fixedText",jp.stackchan.localvoicepoc.voicevox.TTS_PREVIEW_TEXT)
                            .put("metrics",org.json.JSONObject(timing.snapshot()))
                        file.appendText(record.toString()+"\n")
                    }.onFailure { timing.value("logWriteError",it::class.java.simpleName) }
                }
                mutableState.update { it.copy(ttsPreviewRunning = false,
                    voicevoxDiagnosticReport = (it.voicevoxDiagnosticReport + "\n\n" + timing.report()).takeLast(24000)) }
            }
        }.also { it.start() }
    }

    fun diagnoseVoicevox() {
        val current = mutableState.value
        if (!current.canStartModelMutation || !current.voicevoxLoaded || current.ttsBackend != TtsBackend.VOICEVOX) return
        val header = "VOICEVOX:ずんだもん / ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} API${android.os.Build.VERSION.SDK_INT}\n" +
            "CPU 1/2/4/auto; 各ロード後 初回+warm2回; LLM loaded=${languageModel.isLoaded}\n" +
            "queryはOpenJtalkと音高/長さ推定を含む。synthesisWavはWAV生成を含む。\n" +
            "診断ではPCMを10ms単位でwriteし、出力を観測します。通常の全文writeとは呼出し条件が異なります。欠けたhead/timestampは未取得です。\n" +
            "時刻は各試行の要求から。play/writeはAPI時刻。headは観測上限、timestampは出力frame0推定。実際の発音開始は未計測。\n"
        val reports = mutableListOf(header)
        mutableState.update { it.copy(ttsPreviewRunning = true, voicevoxDiagnosticRunning = true,
            voicevoxDiagnosticReport = header, error = null) }
        previewJob = viewModelScope.launch {
            val voicevox = synthesizer.diagnosticVoicevox
            try {
                val installation = withContext(Dispatchers.IO) { voicevoxStore.current() }
                jp.stackchan.localvoicepoc.voicevox.compareTtsThreads(
                    reload = { threads ->
                        val loadBegin = System.nanoTime()
                        voicevox.load(installation, threads)
                        reports += "threads=${if (threads == 0) "auto" else threads}; reload+verify+releaseMs=${(System.nanoTime()-loadBegin)/1_000_000}; pssKiB=${android.os.Debug.getPss()}"
                    },
                    play = { threads, iteration ->
                        val timing = jp.stackchan.localvoicepoc.voicevox.TtsTiming("threads=${if (threads == 0) "auto" else threads}; ${if (iteration == 0) "first-after-load" else "warm$iteration"}")
                        val chunk = voicevox.diagnosticChunk(jp.stackchan.localvoicepoc.voicevox.TTS_PREVIEW_TEXT, timing)
                        val sink = AndroidPcmAudioSink(timing)
                        diagnosticSink = sink
                        try {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            sink.begin(chunk.sampleRate)
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            sink.write(chunk.samples)
                            sink.finish()
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            timing.value("pssKiB", android.os.Debug.getPss().toString())
                            timing.value("nativeHeapBytes", android.os.Debug.getNativeHeapAllocatedSize().toString())
                            reports += timing.report()
                            mutableState.update { it.copy(voicevoxDiagnosticReport = reports.joinToString("\n\n")) }
                        } finally { sink.stop(); diagnosticSink = null }
                    },
                    release = {
                        voicevox.releaseAndAwait()
                        reports += "previous engine finalized; pssKiB=${android.os.Debug.getPss()} (allocator may retain memory)"
                    },
                )
                reports += "比較完了"
            } catch (error: CancellationException) {
                reports += "中止: 未完了の結果は破棄しました"
                throw error
            } catch (error: Throwable) {
                reports += "診断停止: ${error.message}"
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    diagnosticSink?.stop()
                    diagnosticSink = null
                    try {
                        voicevox.releaseAndAwait()
                        if (!diagnosisShutdown) voicevox.load(voicevoxStore.current(), 4)
                    } catch (error: Throwable) { reports += "復元停止: ${error.message}" }
                }
                mutableState.update { it.copy(ttsPreviewRunning = false, voicevoxDiagnosticRunning = false,
                    voicevoxLoaded = voicevox.isLoaded, voicevoxDiagnosticReport = reports.joinToString("\n\n")) }
            }
        }
    }

    fun stopVoicePreview() {
        previewJob?.cancel()
        synthesizer.cancel()
        previewAudioSink.stop()
        diagnosticSink?.stop()
        releaseAudioFocus()
    }

    fun startAutomatic() {
        if (!canStartConversation()) return
        runCatching {
            mutableState.update { it.copy(error = null, phase = ConversationPhase.CONNECTING) }
            engine.startAutomatic()
        }
            .onFailure { error ->
                releaseAudioFocus()
                mutableState.update { it.copy(phase = ConversationPhase.IDLE) }
                showError(error)
                viewModelScope.launch { syncStackChanStatus(mutableState.value.phase) }
            }
    }

    fun stopConversation() {
        if (mutableState.value.audioTransition) return
        mutableState.update { it.copy(audioTransition = true) }
        audioOperation = viewModelScope.launch {
            try { engine.stop() }
            catch (error: CancellationException) { throw error }
            catch (error: Throwable) {
                Log.w(TAG, "Could not finish conversation stop", error)
                showError(IllegalStateException("会話を停止しました。接続を確認してから再開してください"))
            }
            finally {
                releaseAudioFocus()
                mutableState.update { it.copy(audioTransition = false, phase = ConversationPhase.IDLE, audioLevel = 0f, assistantDraft = "") }
            }
        }
    }

    fun selectAudioMode(mode: ConversationAudioMode) {
        val current = mutableState.value
        if (current.audioMode == mode || !current.canChangeAudioMode) return
        mutableState.update { it.copy(audioTransition = true, error = null) }
        stopVoicePreview()
        audioOperation = viewModelScope.launch {
            try {
                previewJob?.join()
                engine.switchAudio(if (mode == ConversationAudioMode.PHONE) phoneSource else serialAudioSource,
                    if (mode == ConversationAudioMode.PHONE) phoneSink else serialAudioSink)
                appPreferences.saveAudioMode(mode)
                mutableState.update { it.copy(audioMode = mode, phase = ConversationPhase.IDLE, audioLevel = 0f, assistantDraft = "") }
            } catch (error: CancellationException) { throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Could not change audio mode", error)
                showError(IllegalStateException("音声を切り替えられませんでした。停止して、もう一度お試しください"))
            } finally { releaseAudioFocus(); mutableState.update { it.copy(audioTransition = false) } }
        }
    }

    private suspend fun loadPocketWithVoice(installation: jp.stackchan.localvoicepoc.pocket.PocketInstallation) {
        synthesizer.loadPocket(installation)
        try { referenceVoices.restoreSelected() }
        catch (error: Throwable) { withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { synthesizer.close() }; throw error }
    }
    private fun canChoosePocketVoice(): Boolean {
        val recording = referenceRecorder.state.value
        return foreground && mutableState.value.canChoosePocketVoices && (!recording.open ||
            (recording.phase == jp.stackchan.localvoicepoc.recording.RecordingPhase.IDLE &&
                recording.draft == null && recording.deletePrompt == null))
    }
    fun preparePocketPresets() = referenceVoices.preparePresets()
    fun previewPocketVoice(id: String?) = referenceVoices.previewSelection(id)
    fun removePocketPreset(id: String) = referenceVoices.removePreset(id)
    private suspend fun previewPocketVoiceAudio() {
        check(foreground && acquireAudioFocus()) { "音声を再生できません。ほかの音声の使用を確認してください" }
        previewAudioSink.stop()
        previewAudioSink = AndroidPcmAudioSink()
        try { playTtsPreview(synthesizer, previewAudioSink) }
        finally { previewAudioSink.stop(); releaseAudioFocus() }
    }
    private fun canManageReferenceVoices(): Boolean {
        val recording = referenceRecorder.state.value
        return foreground && mutableState.value.canManageReferenceVoices && recording.open &&
            recording.phase == jp.stackchan.localvoicepoc.recording.RecordingPhase.IDLE && recording.draft == null && recording.deletePrompt == null
    }
    fun importReferenceEncoder(uri: Uri) {
        if (!foreground) { pendingEncoderImport = uri; return }
        referenceVoices.importEncoder { active ->
            check(pocketStore.hasConfirmedTerms()) { "先に声の設定でPocketモデルを準備してください" }
            pocketEncoderStore.import(uri, active)
        }
    }
    fun createReferenceVoice(id: String) = referenceVoices.create(id)
    fun selectReferenceVoice(id: String?) = referenceVoices.select(id)
    fun cancelReferenceVoice() = referenceVoices.cancel()

    fun openReferenceRecording() {
        val current = mutableState.value
        if (!foreground || !current.canChangeAudioMode || current.referenceRecording.open) return
        mutableState.update { it.copy(audioTransition = true) }
        stopVoicePreview()
        audioOperation = viewModelScope.launch {
            try {
                previewJob?.join(); engine.stop(); referenceRecorder.open(); referenceVoices.refresh()
                if (!foreground) referenceRecorder.discard(close = true)
            } catch (error: CancellationException) { throw error
            } catch (error: Throwable) { showError(error)
            } finally { mutableState.update { it.copy(audioTransition = false) } }
        }
    }
    fun closeReferenceRecording() {
        mutableState.update { it.copy(audioTransition = true) }
        referenceVoices.cancel()
        referenceRecorder.interruptNow(); releaseAudioFocus()
        viewModelScope.launch {
            try { referenceVoices.cancelAndJoin(); referenceRecorder.discard(close = true) }
            finally { mutableState.update { it.copy(audioTransition = false) } }
        }
    }
    fun startReferenceRecording() {
        if (!canStartReferenceCapture()) return
        val recording = referenceRecorder.state.value
        if (!foreground || !recording.open || recording.phase != jp.stackchan.localvoicepoc.recording.RecordingPhase.IDLE) return
        if (androidx.core.content.ContextCompat.checkSelfPermission(getApplication(), android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            updateMicrophonePermission(false, true); return
        }
        if (acquireAudioFocus()) referenceRecorder.start()
    }
    fun canStartReferenceCapture(): Boolean = foreground && !mutableState.value.audioTransition && !referenceVoices.state.value.busy && referenceRecorder.state.value.deletePrompt == null &&
        referenceRecorder.state.value.open && referenceRecorder.state.value.phase == jp.stackchan.localvoicepoc.recording.RecordingPhase.IDLE
    fun stopReferenceRecording() = referenceRecorder.stopRecording()
    fun playReferenceRecording(id: String? = null) {
        if (mutableState.value.audioTransition || referenceVoices.state.value.busy) return
        if (!foreground || referenceRecorder.state.value.phase != jp.stackchan.localvoicepoc.recording.RecordingPhase.IDLE) return
        if (!referenceRecorder.state.value.open || (id == null && referenceRecorder.state.value.draft == null)) return
        val entry = id?.let { key -> referenceRecorder.state.value.saved.firstOrNull { it.id == key } ?: return }
        if (acquireAudioFocus()) referenceRecorder.play(entry)
    }
    fun stopReferencePlayback() { referenceSink.stop(); viewModelScope.launch { referenceRecorder.stopPlayback(); releaseAudioFocus() } }
    fun nameReferenceRecording(name: String) = referenceRecorder.setName(name)
    fun saveReferenceRecording() { if (!mutableState.value.audioTransition && !referenceVoices.state.value.busy) referenceRecorder.save() }
    private fun canDeleteReferenceRecording(): Boolean = foreground && !mutableState.value.audioTransition &&
        !referenceVoices.state.value.busy && referenceRecorder.state.value.open &&
        referenceRecorder.state.value.phase == jp.stackchan.localvoicepoc.recording.RecordingPhase.IDLE
    fun requestDeleteReferenceRecording(id: String) { if (canDeleteReferenceRecording()) referenceRecorder.requestDelete(id, referenceVoices.state.value.busy) }
    fun cancelDeleteReferenceRecording() = referenceRecorder.cancelDelete()
    fun confirmDeleteReferenceRecording() { if (canDeleteReferenceRecording()) referenceRecorder.confirmDelete(referenceVoices.state.value.busy) }
    private fun interruptReferenceRecording() {
        referenceVoices.cancel()
        val recording = referenceRecorder.state.value
        val unsaved = recording.draft != null || recording.phase == jp.stackchan.localvoicepoc.recording.RecordingPhase.RECORDING
        referenceRecorder.interruptNow()
        viewModelScope.launch { referenceRecorder.discard(close = false, reason = when {
            recording.deletePrompt != null -> "削除を取り消しました"
            recording.phase == jp.stackchan.localvoicepoc.recording.RecordingPhase.DELETING -> "録音一覧を確認してください"
            unsaved -> "録音を中断しました。未保存の録音は破棄されました"
            else -> "再生を停止しました"
        }) }
    }

    fun onForeground(microphoneGranted: Boolean) {
        foreground = true
        updateMicrophonePermission(microphoneGranted)
        pendingEncoderImport?.let { uri -> pendingEncoderImport = null; importReferenceEncoder(uri) }
    }

    fun updateMicrophonePermission(granted: Boolean, denied: Boolean = false) {
        mutableState.update { it.copy(microphoneGranted = granted, microphoneDenied = !granted && (denied || it.microphoneDenied)) }
        if (!granted && referenceRecorder.state.value.phase == jp.stackchan.localvoicepoc.recording.RecordingPhase.RECORDING) interruptReferenceRecording()
        if (!granted && mutableState.value.audioMode == ConversationAudioMode.PHONE && mutableState.value.phase != ConversationPhase.IDLE) stopConversation()
    }

    fun onBackground() {
        foreground = false
        interruptReferenceRecording()
        stopVoicePreview()
        phoneSource.stop(); phoneSink.stop(); releaseAudioFocus()
        stopConversation()
    }

    private fun canStartConversation(): Boolean {
        val current = mutableState.value
        if (referenceRecorder.state.value.open || referenceVoices.state.value.busy) return false
        if (!foreground || !current.pipelineReady || current.phase != ConversationPhase.IDLE) return false
        if (current.audioMode == ConversationAudioMode.DOCK) return true
        val permission = androidx.core.content.ContextCompat.checkSelfPermission(getApplication(), android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!permission) { updateMicrophonePermission(false, true); return false }
        return acquireAudioFocus()
    }

    private fun acquireAudioFocus(): Boolean {
        if (!foreground) return false
        releaseAudioFocus()
        var candidate: AudioFocusRequest? = null
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { change -> if (change < 0 && focusRequest === candidate) { interruptReferenceRecording(); stopVoicePreview(); stopConversation() } }.build()
        candidate = request
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            showError(IllegalStateException("ほかの音声が終了してから、もう一度お試しください")); return false
        }
        focusRequest = request
        return true
    }

    private fun releaseAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    fun startPushToTalk() {
        if (!canStartConversation()) return
        runCatching {
            mutableState.update { it.copy(error = null, phase = ConversationPhase.CONNECTING) }
            engine.startPushToTalk()
        }
            .onFailure { error ->
                releaseAudioFocus()
                mutableState.update { it.copy(phase = ConversationPhase.IDLE) }
                showError(error)
                viewModelScope.launch { syncStackChanStatus(mutableState.value.phase) }
            }
    }

    fun stopPushToTalkAndProcess() {
        if (mutableState.value.audioTransition || mutableState.value.phase != ConversationPhase.RECORDING) return
        mutableState.update { it.copy(phase = ConversationPhase.TRANSCRIBING) }
        engine.stopPushToTalkAndProcess()
    }

    fun clearError() {
        mutableState.update { it.copy(error = null) }
        viewModelScope.launch { syncStackChanStatus(mutableState.value.phase) }
    }

    fun retryUsbConnection() {
        usbConnection.retry()
    }

    fun saveMcpProfile(profile: McpProfile, bearerToken: String?) {
        runCatching { mcpProfileStore.save(profile, bearerToken) }
            .onSuccess { mutableState.update { it.copy(mcpProfiles = mcpProfileStore.profiles()) } }
            .onFailure(::showError)
    }

    fun deleteMcpProfile(connectorId: String) {
        mcpProfileStore.delete(connectorId)
        mutableState.update { it.copy(mcpProfiles = mcpProfileStore.profiles()) }
    }

    fun resolveMcpApproval(approved: Boolean) {
        val pending = synchronized(mcpApprovalLock) {
            pendingMcpApproval.also { pendingMcpApproval = null }
        }
        pending?.complete(approved)
        mutableState.update { it.copy(mcpApprovalRequest = null) }
    }

    private suspend fun awaitMcpApproval(request: McpApprovalRequest): Boolean {
        val response = CompletableDeferred<Boolean>()
        val superseded = synchronized(mcpApprovalLock) {
            pendingMcpApproval.also { pendingMcpApproval = response }
        }
        superseded?.complete(false)
        mutableState.update { it.copy(mcpApprovalRequest = request) }
        return try {
            kotlinx.coroutines.withTimeout(60_000L) { response.await() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            false
        } finally {
            val removed = synchronized(mcpApprovalLock) {
                if (pendingMcpApproval === response) {
                    pendingMcpApproval = null
                    true
                } else {
                    false
                }
            }
            if (removed) mutableState.update { it.copy(mcpApprovalRequest = null) }
        }
    }

    fun finishSetup() {
        val current = mutableState.value
        if (!current.modelsReady || !current.voiceReady) return
        appPreferences.markOnboardingComplete()
        mutableState.update {
            it.copy(
                startupStatus = AppStartupStatus.READY,
                startupMessage = "会話の準備ができました",
            )
        }
    }

    fun retryStartup() {
        if (mutableState.value.sdkStatus !is SdkBootstrap.Status.Ready) return
        startupAttempted = false
        restorePreparedAssetsIfAvailable()
    }

    private fun launchPiperOperation(block: suspend () -> Unit) {
        if (!mutableState.value.ttsBackend.isPiper) return
        if (!mutableState.value.canStartModelMutation) return
        mutableState.update {
            it.copy(
                piperBusy = true,
                piperLoaded = false,
                piperProgress = ComponentProgress(stage = "手動ファイルを取込中"),
                error = null,
            )
        }
        viewModelScope.launch {
            try {
                block()
                refreshPiperFiles()
                mutableState.update { state ->
                    state.copy(
                        piperBusy = false,
                        piperProgress = ComponentProgress(stage = "手動ファイル取込済み", fraction = 1f),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update { state ->
                    state.copy(
                        piperBusy = false,
                        piperProgress = ComponentProgress(stage = "ファイル取込失敗"),
                        error = "ファイル取込に失敗: ${error.message}",
                    )
                }
            }
        }
    }

    private fun startRecommendedPiperSetup() {
        val current = mutableState.value
        if (!current.canPreparePiper) {
            return
        }
        mutableState.update {
            it.copy(
                piperBusy = true,
                piperLoaded = false,
                piperTermsConfirmationRequired = false,
                piperProgress = ComponentProgress(stage = "自動セットアップを開始"),
                error = null,
            )
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { synthesizer.close() }
                val installation = piperStore.prepareRecommended { progress ->
                    mutableState.update { state ->
                        state.copy(
                            piperProgress = ComponentProgress(
                                stage = progress.stage,
                                fraction = progress.fraction,
                            ),
                        )
                    }
                }
                refreshPiperFiles()
                mutableState.update {
                    it.copy(
                        piperProgress = ComponentProgress(
                            stage = "Piper Plusをロード中",
                            fraction = 1f,
                        ),
                    )
                }
                synthesizer.load(installation)
                previewIteration = 0
                mutableState.update {
                    it.copy(
                        piperBusy = false,
                        piperLoaded = true,
                        startupStatus = AppStartupStatus.SETUP_REQUIRED,
                        piperProgress = ComponentProgress(stage = "準備完了", fraction = 1f),
                        error = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                refreshPiperFiles()
                mutableState.update {
                    it.copy(
                        piperBusy = false,
                        piperLoaded = false,
                        piperProgress = ComponentProgress(stage = "自動セットアップ失敗"),
                        error = "Piper Plusの自動セットアップに失敗: " +
                            (error.message ?: error::class.java.simpleName),
                    )
                }
            }
        }
    }

    private fun refreshPiperFiles() {
        val installation = piperStore.current()
        mutableState.update {
            it.copy(
                piperAarPresent = synthesizer.runtimeAvailable,
                piperModelPresent = installation.model.isFile,
                piperConfigPresent = installation.config.isFile,
                piperDictionaryPresent = installation.hasCompleteDictionary,
            )
        }
    }

    private fun restorePreparedAssetsIfAvailable() {
        if (startupAttempted) return
        startupAttempted = true
        viewModelScope.launch { restorePreparedAssets() }
    }

    private suspend fun restorePreparedAssets() {
        mutableState.update { it.copy(modelSetupRunning = true) }
        val selectedModel = mutableState.value.selectedGemmaModel
        val modelAssetsReady = runCatching { modelManager.hasPreparedAssets(selectedModel) }.getOrDefault(false)
        val backend = mutableState.value.ttsBackend
        synthesizer.select(backend)
        val piperAssetsReady = when (backend) { TtsBackend.PIPER, TtsBackend.PIPER2 -> piperStore.current().isComplete; TtsBackend.VOICEVOX -> voicevoxStore.isReady(); TtsBackend.SANO -> sanoStore.isReady(); TtsBackend.POCKET -> pocketStore.isReady() }
        val runtimeReady = when (backend) { TtsBackend.PIPER -> synthesizer.runtimeAvailable; TtsBackend.PIPER2 -> synthesizer.piper2Available; TtsBackend.VOICEVOX -> synthesizer.voicevoxAvailable; TtsBackend.SANO -> synthesizer.sanoAvailable; TtsBackend.POCKET -> synthesizer.pocketAvailable }
        if (
            decideStartupAssets(
                modelAssetsReady,
                piperAssetsReady,
                runtimeReady,
            ) == StartupAssetDecision.SETUP_REQUIRED
        ) {
            mutableState.update {
                it.copy(
                    startupStatus = AppStartupStatus.SETUP_REQUIRED,
                    modelSetupRunning = false,
                    startupMessage = "初回セットアップが必要です",
                )
            }
            return
        }

        mutableState.update {
            it.copy(
                startupStatus = AppStartupStatus.RESTORING,
                startupMessage = if (appPreferences.isOnboardingComplete()) {
                    "会話モデルを読み込んでいます"
                } else {
                    "保存済みの会話モデルを読み込んでいます"
                },
                modelSetupRunning = true,
                piperBusy = true,
                error = null,
            )
        }
        viewModelScope.launch {
            try {
                modelManager.prepareAll(selectedModel) { progress ->
                    mutableState.update { current ->
                        current.copy(
                            modelProgress = current.modelProgress + (
                                progress.component to ComponentProgress(progress.stage, progress.fraction)
                            ),
                        )
                    }
                }
                mutableState.update {
                    it.copy(
                        modelsReady = true,
                        modelSetupRunning = false,
                        startupMessage = "音声を読み込んでいます",
                    )
                }
                when (backend) {
                    TtsBackend.PIPER, TtsBackend.PIPER2 -> synthesizer.load(piperStore.current())
                    TtsBackend.VOICEVOX -> synthesizer.loadVoicevox(voicevoxStore.current())
                    TtsBackend.SANO -> synthesizer.loadSano(sanoStore.current())
                    TtsBackend.POCKET -> loadPocketWithVoice(pocketStore.current())
                }
                appPreferences.markOnboardingComplete()
                mutableState.update {
                    it.copy(
                        piperLoaded = backend.isPiper,
                        voicevoxLoaded = backend == TtsBackend.VOICEVOX,
                        sanoLoaded = backend == TtsBackend.SANO,
                        pocketLoaded = backend == TtsBackend.POCKET,
                        pocketProgress = ComponentProgress("準備完了", 1f),
                        sanoProgress = ComponentProgress("準備完了", 1f),
                        voicevoxProgress = ComponentProgress("準備完了", 1f),
                        piperBusy = false,
                        piperProgress = ComponentProgress("準備完了", 1f),
                        startupStatus = AppStartupStatus.READY,
                        startupMessage = "会話の準備ができました",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        modelSetupRunning = false,
                        piperBusy = false,
                        startupStatus = AppStartupStatus.FAILED,
                        startupMessage = "保存済みデータを読み込めませんでした",
                        error = error.message ?: error::class.java.simpleName,
                    )
                }
            }
        }
    }

    private suspend fun handleConversationEvent(event: ConversationEvent) {
        when (event) {
            is ConversationEvent.PhaseChanged -> {
                mutableState.update {
                    it.copy(
                        phase = event.phase,
                        audioLevel = if (event.phase == ConversationPhase.IDLE) 0f else it.audioLevel,
                    )
                }
                if (event.phase == ConversationPhase.IDLE && !mutableState.value.ttsPreviewRunning && !referenceVoices.state.value.busy && referenceRecorder.state.value.phase == jp.stackchan.localvoicepoc.recording.RecordingPhase.IDLE) releaseAudioFocus()
                syncStackChanStatus(event.phase)
            }
            is ConversationEvent.AudioLevel -> mutableState.update {
                it.copy(audioLevel = (event.rms * 12f).coerceIn(0f, 1f))
            }
            is ConversationEvent.UserText -> mutableState.update {
                it.copy(
                    messages = it.messages + ChatMessage(ChatMessage.Role.USER, event.text),
                    assistantDraft = "",
                    error = null,
                )
            }
            is ConversationEvent.AssistantDraft -> mutableState.update {
                it.copy(assistantDraft = event.text)
            }
            is ConversationEvent.AssistantText -> mutableState.update {
                it.copy(
                    messages = it.messages + ChatMessage(ChatMessage.Role.ASSISTANT, event.text),
                    assistantDraft = "",
                )
            }
            is ConversationEvent.Failure -> {
                Log.w(TAG, "Conversation failed", event.cause)
                showError(IllegalStateException(if (event.cause is SecurityException) "マイクの使用を許可してください" else if (event.message.startsWith("録音")) event.message else "会話を続けられませんでした。停止して、もう一度お試しください"))
                syncStackChanStatus(mutableState.value.phase)
            }
        }
    }

    private suspend fun handleRemoteConversationStart(): ConversationCommandResult {
        val current = mutableState.value
        if (!foreground || current.audioTransition || current.audioMode != ConversationAudioMode.DOCK || referenceRecorder.state.value.open || referenceVoices.state.value.busy) {
            return ConversationCommandResult(false, RemoteConversationState.BLOCKED, "接続端末モードの会話画面で開始してください")
        }
        when (
            val decision = decideRemoteConversationStart(
                pipelineReady = current.pipelineReady,
                automaticMode = current.automaticMode,
                phase = current.phase,
            )
        ) {
            is RemoteConversationStartDecision.AlreadyActive -> {
                return ConversationCommandResult(true, decision.state)
            }
            is RemoteConversationStartDecision.Reject -> {
                return ConversationCommandResult(
                    success = false,
                    state = RemoteConversationState.BLOCKED,
                    error = decision.error,
                )
            }
            RemoteConversationStartDecision.StartAutomatic -> Unit
        }
        return try {
            engine.startAutomatic()
            mutableState.update { it.copy(automaticMode = true, error = null) }
            ConversationCommandResult(true, RemoteConversationState.CONNECTING)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            showError(error)
            syncStackChanStatus(mutableState.value.phase)
            ConversationCommandResult(
                success = false,
                state = RemoteConversationState.BLOCKED,
                error = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private suspend fun handleRemoteConversationStop(): ConversationCommandResult = if (mutableState.value.audioMode != ConversationAudioMode.DOCK || referenceRecorder.state.value.open || referenceVoices.state.value.busy) {
        ConversationCommandResult(false, RemoteConversationState.BLOCKED, "接続端末モードを選んでください")
    } else try {
        engine.stop()
        ConversationCommandResult(true, RemoteConversationState.STANDBY)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        showError(error)
        syncStackChanStatus(mutableState.value.phase)
        ConversationCommandResult(
            success = false,
            state = RemoteConversationState.BLOCKED,
            error = error.message ?: error::class.java.simpleName,
        )
    }

    private fun showError(error: Throwable) {
        mutableState.update {
            it.copy(error = error.message ?: error::class.java.simpleName)
        }
    }

    private suspend fun syncStackChanStatus(phase: ConversationPhase) {
        if (mutableState.value.audioMode != ConversationAudioMode.DOCK) return
        stackChanStatusMutex.withLock {
            val ready = usbConnection.state.value as? StackChanUsbState.Ready ?: return
            if (ready.capabilities and StackChanCapabilities.STATUS_ICON == 0) return
            val extended = ready.capabilities and StackChanCapabilities.STATUS_EXTENDED != 0
            val status = stackChanStatusFor(
                phase = phase,
                extended = extended,
                hasError = mutableState.value.error != null,
            )
            if (status == lastStackChanStatus) return
            val sent = withContext(Dispatchers.IO) {
                runCatching {
                    usbConnection.sendControl(
                        StackChanControl.STATUS,
                        payload = byteArrayOf(status.wireValue.toByte()),
                    )
                }
            }
            sent.onSuccess { lastStackChanStatus = status }
                .onFailure { error -> Log.w(TAG, "Could not update Stack-chan status icon", error) }
        }
    }

    override fun onCleared() {
        diagnosisShutdown = true
        referenceVoices.cancel()
        referenceRecorder.close()
        foreground = false
        phoneSource.stop(); phoneSink.stop(); releaseAudioFocus()
        stopVoicePreview()
        synchronized(mcpApprovalLock) {
            pendingMcpApproval.also { pendingMcpApproval = null }
        }?.complete(false)
        realtimeSession.close()
        engine.close()
        serialAudioSource.close()
        serialAudioSink.close()
        usbConnection.close()
        super.onCleared()
    }

    private companion object {
        const val TAG = "MainViewModel"
    }
}
