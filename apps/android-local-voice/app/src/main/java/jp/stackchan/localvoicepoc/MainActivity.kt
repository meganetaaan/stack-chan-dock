package jp.stackchan.localvoicepoc

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.platform.LocalContext
import jp.stackchan.localvoicepoc.audio.ConversationAudioMode
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import jp.stackchan.localvoicepoc.ui.StackChanScreen
import jp.stackchan.localvoicepoc.ui.StackChanTheme

class MainActivity : ComponentActivity() {
    private lateinit var mainModel: MainViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainModel = ViewModelProvider(this)[MainViewModel::class.java]
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            StackChanTheme {
                Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                    StackChanApp(mainModel)
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        if (::mainModel.isInitialized) mainModel.onForeground(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    override fun onStop() {
        if (::mainModel.isInitialized) mainModel.onBackground()
        super.onStop()
    }
}

@Composable
private fun StackChanApp(viewModel: MainViewModel = viewModel()) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Granting permission only changes readiness; recording always needs an explicit tap.
        viewModel.updateMicrophonePermission(granted, denied = !granted)
    }
    fun start(automatic: Boolean) {
        if (state.audioMode == ConversationAudioMode.PHONE && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            viewModel.updateMicrophonePermission(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            if (automatic) viewModel.startAutomatic() else viewModel.startPushToTalk()
        }
    }
    fun startReference() {
        if (!viewModel.canStartReferenceCapture()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        else { viewModel.updateMicrophonePermission(true); viewModel.startReferenceRecording() }
    }
    val modelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importPiperModel) }
    val configPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importPiperConfig) }
    val dictionaryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(viewModel::importPiperDictionary) }

    val pocketPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importPocket) }
    val encoderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importReferenceEncoder) }
    val sanoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importSano) }

    StackChanScreen(
        state = state,
        onGemmaModelSelected = viewModel::selectGemmaModel,
        onPrepareModels = viewModel::prepareModels,
        onPickPiperModel = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) },
        onPickPiperConfig = { configPicker.launch(arrayOf("application/json", "text/json", "*/*")) },
        onPickDictionary = { dictionaryPicker.launch(null) },
        onPrepareRecommendedPiper = viewModel::prepareRecommendedPiper,
        onConfirmRecommendedPiperTerms = viewModel::confirmRecommendedPiperTerms,
        onDismissRecommendedPiperTerms = viewModel::dismissRecommendedPiperTerms,
        onLoadPiper = viewModel::loadPiper,
        onAutomaticModeChanged = viewModel::setAutomaticMode,
        onStartAutomatic = { start(true) },
        onStopConversation = viewModel::stopConversation,
        onStartPushToTalk = { start(false) },
        onStopPushToTalk = viewModel::stopPushToTalkAndProcess,
        onRetryUsbConnection = viewModel::retryUsbConnection,
        onSaveMcpProfile = viewModel::saveMcpProfile,
        onDeleteMcpProfile = viewModel::deleteMcpProfile,
        onResolveMcpApproval = viewModel::resolveMcpApproval,
        onFinishSetup = viewModel::finishSetup,
        onRetryStartup = viewModel::retryStartup,
        onDismissError = viewModel::clearError,
        onImportReferenceEncoder = { encoderPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
        onCreateReferenceVoice = viewModel::createReferenceVoice,
        onSelectReferenceVoice = viewModel::selectReferenceVoice,
        onCancelReferenceVoice = viewModel::cancelReferenceVoice,
        onOpenReferenceRecording = viewModel::openReferenceRecording,
        onCloseReferenceRecording = viewModel::closeReferenceRecording,
        onStartReferenceRecording = ::startReference,
        onStopReferenceRecording = viewModel::stopReferenceRecording,
        onPlayReferenceRecording = viewModel::playReferenceRecording,
        onStopReferencePlayback = viewModel::stopReferencePlayback,
        onNameReferenceRecording = viewModel::nameReferenceRecording,
        onSaveReferenceRecording = viewModel::saveReferenceRecording,
        onRequestDeleteReferenceRecording = viewModel::requestDeleteReferenceRecording,
        onCancelDeleteReferenceRecording = viewModel::cancelDeleteReferenceRecording,
        onConfirmDeleteReferenceRecording = viewModel::confirmDeleteReferenceRecording,
        onAudioModeSelected = viewModel::selectAudioMode,
        onTtsBackendSelected = viewModel::selectTtsBackend,
        onPrepareVoicevox = viewModel::prepareVoicevox,
        onPrepareSano = viewModel::prepareSano,
        onPreparePocketPresets = viewModel::preparePocketPresets,
        onPreviewPocketVoice = viewModel::previewPocketVoice,
        onRemovePocketPreset = viewModel::removePocketPreset,
        onPreparePocket = viewModel::preparePocket,
        onPickPocketAsset = { pocketPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
        onConfirmPocketTerms = viewModel::confirmPocketTerms,
        onDismissPocketTerms = viewModel::dismissPocketTerms,
        onPickSanoAsset = { sanoPicker.launch(arrayOf("application/octet-stream", "*/*")) },
        onConfirmSanoTerms = viewModel::confirmSanoTerms,
        onDismissSanoTerms = viewModel::dismissSanoTerms,
        onPreviewVoice = viewModel::previewVoice,
        onStopVoicePreview = viewModel::stopVoicePreview,
        onDiagnoseVoicevox = viewModel::diagnoseVoicevox,
        onConfirmVoicevoxTerms = viewModel::confirmVoicevoxTerms,
        onDismissVoicevoxTerms = viewModel::dismissVoicevoxTerms,
    )
}
