package jp.stackchan.localvoicepoc.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import jp.stackchan.localvoicepoc.audio.ConversationAudioMode
import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.composables.icons.lucide.R as LucideR
import jp.stackchan.localvoicepoc.R
import jp.stackchan.localvoicepoc.SdkBootstrap
import jp.stackchan.localvoicepoc.conversation.ConversationPhase
import jp.stackchan.localvoicepoc.model.GemmaModelManifest
import jp.stackchan.localvoicepoc.model.GemmaModelSpec
import jp.stackchan.localvoicepoc.model.ModelComponent
import jp.stackchan.localvoicepoc.mcp.McpProfile
import jp.stackchan.localvoicepoc.piper.PiperAssetLinks
import java.util.Locale
import jp.stackchan.localvoicepoc.voicevox.TtsBackend
import jp.stackchan.localvoicepoc.voicevox.VoicevoxAssets

private enum class AppPage { CONVERSATION, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StackChanScreen(
    state: MainUiState,
    onGemmaModelSelected: (String) -> Unit,
    onPrepareModels: () -> Unit,
    onPickPiperModel: () -> Unit,
    onPickPiperConfig: () -> Unit,
    onPickDictionary: () -> Unit,
    onPrepareRecommendedPiper: () -> Unit,
    onConfirmRecommendedPiperTerms: () -> Unit,
    onDismissRecommendedPiperTerms: () -> Unit,
    onLoadPiper: () -> Unit,
    onAutomaticModeChanged: (Boolean) -> Unit,
    onStartAutomatic: () -> Unit,
    onStopConversation: () -> Unit,
    onStartPushToTalk: () -> Unit,
    onStopPushToTalk: () -> Unit,
    onRetryUsbConnection: () -> Unit,
    onSaveMcpProfile: (McpProfile, String?) -> Unit = { _, _ -> },
    onDeleteMcpProfile: (String) -> Unit = {},
    onResolveMcpApproval: (Boolean) -> Unit = {},
    onFinishSetup: () -> Unit,
    onRetryStartup: () -> Unit,
    onDismissError: () -> Unit,
    onImportReferenceEncoder: () -> Unit = {},
    onCreateReferenceVoice: (String) -> Unit = {},
    onSelectReferenceVoice: (String?) -> Unit = {},
    onCancelReferenceVoice: () -> Unit = {},
    onOpenReferenceRecording: () -> Unit = {},
    onCloseReferenceRecording: () -> Unit = {},
    onStartReferenceRecording: () -> Unit = {},
    onStopReferenceRecording: () -> Unit = {},
    onPlayReferenceRecording: (String?) -> Unit = {},
    onStopReferencePlayback: () -> Unit = {},
    onNameReferenceRecording: (String) -> Unit = {},
    onSaveReferenceRecording: () -> Unit = {},
    onRequestDeleteReferenceRecording: (String) -> Unit = {},
    onCancelDeleteReferenceRecording: () -> Unit = {},
    onConfirmDeleteReferenceRecording: () -> Unit = {},
    onAudioModeSelected: (ConversationAudioMode) -> Unit = {},
    onTtsBackendSelected: (TtsBackend) -> Unit = {},
    onPrepareVoicevox: () -> Unit = {},
    onPrepareSano: () -> Unit = {},
    onPreparePocketPresets: () -> Unit = {},
    onPreviewPocketVoice: (String?) -> Unit = {},
    onRemovePocketPreset: (String) -> Unit = {},
    onPreparePocket: () -> Unit = {},
    onPickPocketAsset: () -> Unit = {},
    onPickSanoAsset: () -> Unit = {},
    onConfirmVoicevoxTerms: () -> Unit = {},
    onConfirmSanoTerms: () -> Unit = {},
    onConfirmPocketTerms: () -> Unit = {},
    onDismissPocketTerms: () -> Unit = {},
    onDismissSanoTerms: () -> Unit = {},
    onDismissVoicevoxTerms: () -> Unit = {},
    onPreviewVoice: () -> Unit = {},
    onStopVoicePreview: () -> Unit = {},
    onDiagnoseVoicevox: () -> Unit = {},
) {
    var page by rememberSaveable { mutableStateOf(AppPage.CONVERSATION) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error, state.startupStatus) {
        val message = state.error ?: return@LaunchedEffect
        if (state.error != null) {
            snackbarHostState.showSnackbar(if (page == AppPage.SETTINGS || message.startsWith("ほかの音声") || message.startsWith("マイク") || message.startsWith("録音")) message else "会話を続けられませんでした。設定を確認して、もう一度お試しください")
            onDismissError()
        }
    }

    BackHandler(enabled = page == AppPage.SETTINGS) { page = AppPage.CONVERSATION }
    if (state.referenceRecording.open) {
        val context = LocalContext.current
        jp.stackchan.localvoicepoc.recording.ReferenceRecordingScreen(state.referenceRecording,state.microphoneGranted,
            onCloseReferenceRecording,onStartReferenceRecording,onStopReferenceRecording,onPlayReferenceRecording,
            onStopReferencePlayback,onNameReferenceRecording,onSaveReferenceRecording,
            microphoneDenied=state.microphoneDenied,
            onOpenPermissionSettings={context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,"package:${context.packageName}".toUri()))},
            snackbarHostState=snackbarHostState,
            voices=state.pocketVoices,canManageVoices=state.canManageReferenceVoices,
            pocketReady=state.ttsBackend==TtsBackend.POCKET&&state.pocketLoaded,
            onImportEncoder=onImportReferenceEncoder,onCreateVoice=onCreateReferenceVoice,
            onSelectVoice=onSelectReferenceVoice,onCancelVoice=onCancelReferenceVoice,
            onRequestDelete=onRequestDeleteReferenceRecording,onCancelDelete=onCancelDeleteReferenceRecording,
            onConfirmDelete=onConfirmDeleteReferenceRecording)
    } else if (page == AppPage.SETTINGS) {
        SettingsScreen(
            state = state,
            onBack = { page = AppPage.CONVERSATION },
            onRetryStartup = onRetryStartup,
            onOpenReferenceRecording = onOpenReferenceRecording,
            onGemmaModelSelected = onGemmaModelSelected,
            onPrepareModels = onPrepareModels,
            onPickPiperModel = onPickPiperModel,
            onPickPiperConfig = onPickPiperConfig,
            onPickDictionary = onPickDictionary,
            onPrepareRecommendedPiper = onPrepareRecommendedPiper,
            onLoadPiper = onLoadPiper,
            onRetryUsbConnection = onRetryUsbConnection,
            onSaveMcpProfile = onSaveMcpProfile,
            onDeleteMcpProfile = onDeleteMcpProfile,
            snackbarHostState = snackbarHostState,
            onTtsBackendSelected = onTtsBackendSelected,
            onPrepareVoicevox = onPrepareVoicevox,
            onPrepareSano = onPrepareSano,
            onPreparePocketPresets = onPreparePocketPresets,
            onPreviewPocketVoice = onPreviewPocketVoice,
            onRemovePocketPreset = onRemovePocketPreset,
            onImportReferenceEncoder = onImportReferenceEncoder,
            onSelectReferenceVoice = onSelectReferenceVoice,
            onCancelReferenceVoice = onCancelReferenceVoice,
            onPreparePocket = onPreparePocket,
            onPickPocketAsset = onPickPocketAsset,
            onPickSanoAsset = onPickSanoAsset,
            onPreviewVoice = onPreviewVoice,
            onStopVoicePreview = onStopVoicePreview,
            onDiagnoseVoicevox = onDiagnoseVoicevox,
        )
    } else {
        ConversationScreen(
            state = state,
            onAudioModeSelected = onAudioModeSelected,
            onOpenSettings = { page = AppPage.SETTINGS },
            onAutomaticModeChanged = onAutomaticModeChanged,
            onStartAutomatic = onStartAutomatic,
            onStopConversation = onStopConversation,
            onStartPushToTalk = onStartPushToTalk,
            onStopPushToTalk = onStopPushToTalk,
            onRetryUsbConnection = onRetryUsbConnection,
            snackbarHostState = snackbarHostState,
        )
    }

    if (state.pocketTermsConfirmationRequired) {
        jp.stackchan.localvoicepoc.pocket.PocketTermsDialog(onConfirmPocketTerms, onDismissPocketTerms)
    }
    if (state.sanoTermsConfirmationRequired) {
        jp.stackchan.localvoicepoc.sano.SanoTermsDialog(onConfirmSanoTerms, onDismissSanoTerms)
    }
    if (state.voicevoxTermsConfirmationRequired) {
        val uriHandler = LocalUriHandler.current
        AlertDialog(
            onDismissRequest = onDismissVoicevoxTerms,
            title = { Text("VOICEVOX:ずんだもん の利用規約") },
            text = {
                Column {
                    Text("音声の利用・公開には VOICEVOX:ずんだもん のクレジットが必要です。モデル・runtime・音源の規約を確認してください。")
                    listOf("モデル規約" to VoicevoxAssets.MODEL_TERMS, "runtime規約" to VoicevoxAssets.RUNTIME_TERMS,
                        "ずんだもん音源規約" to VoicevoxAssets.CHARACTER_TERMS).forEach { (label, url) ->
                        TextButton(onClick = { uriHandler.openUri(url) }) { Text(label) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onConfirmVoicevoxTerms) { Text("同意して準備") } },
            dismissButton = { TextButton(onClick = onDismissVoicevoxTerms) { Text("キャンセル") } },
        )
    }
    if (state.piperTermsConfirmationRequired) {
        PiperTermsDialog(
            onConfirm = onConfirmRecommendedPiperTerms,
            onDismiss = onDismissRecommendedPiperTerms,
        )
    }
    state.mcpApprovalRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { onResolveMcpApproval(false) },
            title = { Text("MCPツールを実行しますか？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("接続先: ${request.serverLabel}")
                    Text("ツール: ${request.toolName}")
                    Text(request.arguments.toString(), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { Button(onClick = { onResolveMcpApproval(true) }) { Text("実行") } },
            dismissButton = { TextButton(onClick = { onResolveMcpApproval(false) }) { Text("拒否") } },
        )
    }
}

@Composable
private fun StartupScreen(state: MainUiState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StackChanFace(88.dp)
            Spacer(Modifier.height(28.dp))
            CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                text = state.startupMessage,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (state.startupStatus == AppStartupStatus.RESTORING) {
                Spacer(Modifier.height(20.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    progress = { restoreProgress(state) },
                )
            }
        }
    }
}

@Composable
private fun StartupFailureScreen(state: MainUiState, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LucideIcon(LucideR.drawable.lucide_ic_triangle_alert, "読込エラー", Modifier.size(44.dp))
            Spacer(Modifier.height(16.dp))
            Text(state.startupMessage, style = MaterialTheme.typography.titleLarge)
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetry) {
                LucideIcon(LucideR.drawable.lucide_ic_refresh_cw, null)
                Spacer(Modifier.width(8.dp))
                Text("もう一度読み込む")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScreen(
    state: MainUiState,
    onGemmaModelSelected: (String) -> Unit,
    onPrepareModels: () -> Unit,
    onPrepareRecommendedPiper: () -> Unit,
    onRetryUsbConnection: () -> Unit,
    onFinishSetup: () -> Unit,
    onDismissError: () -> Unit,
    onTtsBackendSelected: (TtsBackend) -> Unit = {},
    onPrepareVoicevox: () -> Unit = {},
    onPrepareSano: () -> Unit = {},
    onPreparePocketPresets: () -> Unit = {},
    onPreviewPocketVoice: (String?) -> Unit = {},
    onRemovePocketPreset: (String) -> Unit = {},
    onPreparePocket: () -> Unit = {},
    onPickPocketAsset: () -> Unit = {},
    onPickSanoAsset: () -> Unit = {},
    onPreviewVoice: () -> Unit = {},
    onStopVoicePreview: () -> Unit = {},
    onDiagnoseVoicevox: () -> Unit = {},
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("ｽﾀｯｸﾁｬﾝを準備") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item { SetupStepIndicator(state.setupStep) }
            item { TtsSelector(state, onTtsBackendSelected) }
            if (state.ttsBackend == TtsBackend.VOICEVOX) {
                item { VoicevoxSetup(state, onPrepareVoicevox) }
            } else if (state.ttsBackend == TtsBackend.POCKET) {
                item { jp.stackchan.localvoicepoc.pocket.PocketSetup(state, onPreparePocket, onPickPocketAsset) }
            } else if (state.ttsBackend == TtsBackend.SANO) {
                item { jp.stackchan.localvoicepoc.sano.SanoSetup(state, onPrepareSano, onPickSanoAsset) }
            }
            item { TtsPreviewControls(state, onPreviewVoice, onStopVoicePreview, onDiagnoseVoicevox) }
            item {
                when (state.setupStep) {
                    SetupStep.MODEL -> ModelSetupStep(state, onGemmaModelSelected, onPrepareModels)
                    SetupStep.VOICE -> if (state.ttsBackend.isPiper) VoiceSetupStep(state, onPrepareRecommendedPiper)
                    SetupStep.DEVICE -> DeviceSetupStep(state, onRetryUsbConnection, onFinishSetup)
                }
            }
            state.error?.let { message ->
                item { InlineError(message, onDismissError) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SetupStepIndicator(step: SetupStep) {
    val active = step.ordinal
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf("モデル", "音声", "接続").forEachIndexed { index, label ->
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(
                            if (index <= active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (index == active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ModelSetupStep(
    state: MainUiState,
    onGemmaModelSelected: (String) -> Unit,
    onPrepareModels: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepHeading(LucideR.drawable.lucide_ic_microchip, "会話モデル", "端末内で応答を生成します")
        GemmaModelSelector(state.selectedGemmaModel, state.canStartModelMutation, onGemmaModelSelected)
        Text(
            "${state.selectedGemmaModel.name}  ${formatModelSize(state.selectedGemmaModel.expectedBytes)}",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (state.selectedGemmaModel.supportsTools) {
                "端末ツール対応: 現在日時、バッテリー状態"
            } else {
                state.selectedGemmaModel.runtimeLabel
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ModelProgressLine("会話モデル", state.modelProgress.getValue(ModelComponent.LLM))
        ModelProgressLine("音声認識", state.modelProgress.getValue(ModelComponent.STT))
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = state.sdkStatus is SdkBootstrap.Status.Ready && state.canStartModelMutation,
            onClick = onPrepareModels,
        ) {
            LucideIcon(LucideR.drawable.lucide_ic_download, null)
            Spacer(Modifier.width(8.dp))
            Text(if (state.modelSetupRunning) "準備しています" else "モデルを準備")
        }
        GemmaInformationLinks(state.selectedGemmaModel)
    }
}

@Composable
private fun VoiceSetupStep(state: MainUiState, onPrepareRecommendedPiper: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepHeading(LucideR.drawable.lucide_ic_volume_2, "ｽﾀｯｸﾁｬﾝの声", PiperAssetLinks.RECOMMENDED_MODEL_NAME)
        StatusRow("音声ランタイム", if (state.piperAarPresent) "利用可能" else "利用できません")
        Text("ダウンロード 約72MB・保存領域 約180MB", style = MaterialTheme.typography.bodyMedium)
        ModelProgressLine("音声", state.piperProgress)
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = state.piperAarPresent && state.canStartModelMutation,
            onClick = onPrepareRecommendedPiper,
        ) {
            LucideIcon(LucideR.drawable.lucide_ic_download, null)
            Spacer(Modifier.width(8.dp))
            Text(if (state.piperBusy) "準備しています" else "推奨音声を準備")
        }
    }
}

@Composable
private fun DeviceSetupStep(
    state: MainUiState,
    onRetryUsbConnection: () -> Unit,
    onFinishSetup: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepHeading(LucideR.drawable.lucide_ic_usb, "CoreS3を接続", "USB音声デバイス")
        ConnectionStatus(state)
        if (state.usbStatus == UsbConnectionStatus.DISCONNECTED || state.usbStatus == UsbConnectionStatus.ERROR) {
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                onClick = onRetryUsbConnection,
            ) {
                LucideIcon(LucideR.drawable.lucide_ic_refresh_cw, null)
                Spacer(Modifier.width(8.dp))
                Text("接続を再試行")
            }
        }
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            onClick = onFinishSetup,
        ) {
            LucideIcon(LucideR.drawable.lucide_ic_message_circle, null)
            Spacer(Modifier.width(8.dp))
            Text(if (state.usbStatus == UsbConnectionStatus.READY) "会話をはじめる" else "接続せず進む")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationScreen(
    state: MainUiState,
    onAudioModeSelected: (ConversationAudioMode) -> Unit = {},
    onOpenSettings: () -> Unit,
    onAutomaticModeChanged: (Boolean) -> Unit,
    onStartAutomatic: () -> Unit,
    onStopConversation: () -> Unit,
    onStartPushToTalk: () -> Unit,
    onStopPushToTalk: () -> Unit,
    onRetryUsbConnection: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val listState = rememberLazyListState()
    val messageCount = state.messages.size + if (state.assistantDraft.isBlank()) 0 else 1
    LaunchedEffect(messageCount, state.assistantDraft) {
        if (messageCount > 0) listState.animateScrollToItem(messageCount - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ｽﾀｯｸﾁｬﾝ", maxLines = 1)
                        if (state.ttsBackend == TtsBackend.VOICEVOX) {
                            Text(VoicevoxAssets.CREDIT, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(
                            phaseLabel(state.phase, state.automaticMode),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (state.audioMode == ConversationAudioMode.DOCK) ConnectionDot(state.usbStatus)
                    IconButton(onClick = onOpenSettings) {
                        LucideIcon(LucideR.drawable.lucide_ic_settings, "設定")
                    }
                },
            )
        },
        bottomBar = {
            ConversationControlBar(
                state = state,
                onAutomaticModeChanged = onAutomaticModeChanged,
                onStartAutomatic = onStartAutomatic,
                onStopConversation = onStopConversation,
                onStartPushToTalk = onStartPushToTalk,
                onStopPushToTalk = onStopPushToTalk,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AudioModeSelector(state, onAudioModeSelected)
            if (!state.assetsReady) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.modelSetupRunning || state.startupStatus == AppStartupStatus.CHECKING) "会話の準備をしています" else "会話を始める準備が必要です", Modifier.weight(1f))
                    TextButton(onClick = onOpenSettings) { Text("設定で準備") }
                }
            }
            if (state.audioMode == ConversationAudioMode.DOCK && state.usbStatus != UsbConnectionStatus.READY) {
                UsbBanner(state, onRetryUsbConnection)
            }
            if (state.audioMode == ConversationAudioMode.PHONE && state.microphoneDenied) {
                val context = LocalContext.current
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("マイクの使用を許可してください", Modifier.weight(1f))
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }) { Text("権限の設定") }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = if (messageCount == 0) Arrangement.Center else Arrangement.spacedBy(10.dp),
            ) {
                if (messageCount == 0) {
                    item { EmptyConversation(state) }
                } else {
                    itemsIndexed(state.messages) { _, message -> MessageBubble(message) }
                    if (state.assistantDraft.isNotBlank()) {
                        item { MessageBubble(ChatMessage(ChatMessage.Role.ASSISTANT, state.assistantDraft), true) }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationControlBar(
    state: MainUiState,
    onAutomaticModeChanged: (Boolean) -> Unit,
    onStartAutomatic: () -> Unit,
    onStopConversation: () -> Unit,
    onStartPushToTalk: () -> Unit,
    onStopPushToTalk: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf(false to "押して話す", true to "自動会話").forEachIndexed { index, (automatic, label) ->
                SegmentedButton(
                    selected = state.automaticMode == automatic,
                    onClick = { onAutomaticModeChanged(automatic) },
                    enabled = state.phase == ConversationPhase.IDLE && !state.audioTransition,
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                ) { Text(label, maxLines = 1) }
            }
        }
        LinearProgressIndicator(
            progress = { state.audioLevel },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
        )
        val action = conversationAction(state)
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = action.enabled && !state.audioTransition,
            onClick = when (action.kind) {
                ConversationActionKind.START_AUTOMATIC -> onStartAutomatic
                ConversationActionKind.START_RECORDING -> onStartPushToTalk
                ConversationActionKind.FINISH_RECORDING -> onStopPushToTalk
                ConversationActionKind.STOP -> onStopConversation
            },
        ) {
            LucideIcon(action.icon, null, Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(action.label)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    state: MainUiState,
    onBack: () -> Unit,
    onImportReferenceEncoder: () -> Unit = {},
    onCreateReferenceVoice: (String) -> Unit = {},
    onSelectReferenceVoice: (String?) -> Unit = {},
    onCancelReferenceVoice: () -> Unit = {},
    onOpenReferenceRecording: () -> Unit = {},
    onRetryStartup: () -> Unit = {},
    onGemmaModelSelected: (String) -> Unit,
    onPrepareModels: () -> Unit,
    onPickPiperModel: () -> Unit,
    onPickPiperConfig: () -> Unit,
    onPickDictionary: () -> Unit,
    onPrepareRecommendedPiper: () -> Unit,
    onLoadPiper: () -> Unit,
    onRetryUsbConnection: () -> Unit,
    onSaveMcpProfile: (McpProfile, String?) -> Unit,
    onDeleteMcpProfile: (String) -> Unit,
    snackbarHostState: SnackbarHostState,
    onTtsBackendSelected: (TtsBackend) -> Unit,
    onPrepareVoicevox: () -> Unit,
    onPrepareSano: () -> Unit,
    onPreparePocketPresets: () -> Unit,
    onPreviewPocketVoice: (String?) -> Unit,
    onRemovePocketPreset: (String) -> Unit,
    onPreparePocket: () -> Unit,
    onPickPocketAsset: () -> Unit,
    onPickSanoAsset: () -> Unit,
    onPreviewVoice: () -> Unit,
    onStopVoicePreview: () -> Unit,
    onDiagnoseVoicevox: () -> Unit,
) {
    var showAdvancedVoice by rememberSaveable { mutableStateOf(false) }
    var editingMcpProfile by remember { mutableStateOf<McpProfile?>(null) }
    var showMcpEditor by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        LucideIcon(LucideR.drawable.lucide_ic_chevron_left, "会話へ戻る")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.startupStatus == AppStartupStatus.FAILED) {
                item {
                    Text(if (state.sdkStatus is SdkBootstrap.Status.Ready) "準備を続けられませんでした" else "アプリを閉じて、もう一度開いてください")
                    if (state.sdkStatus is SdkBootstrap.Status.Ready) OutlinedButton(onClick = onRetryStartup, enabled = state.canStartModelMutation) { Text("準備をやり直す") }
                }
            }
            item {
                SettingsSection("会話モデル") {
                    GemmaModelSelector(state.selectedGemmaModel, state.canStartModelMutation, onGemmaModelSelected)
                    StatusRow("端末ツール", if (state.selectedGemmaModel.supportsTools) "日時・バッテリー" else "非対応")
                    ModelProgressLine(state.selectedGemmaModel.name, state.modelProgress.getValue(ModelComponent.LLM))
                    ModelProgressLine("音声認識", state.modelProgress.getValue(ModelComponent.STT))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.canStartModelMutation && state.sdkStatus is SdkBootstrap.Status.Ready,
                        onClick = onPrepareModels,
                    ) { Text("選択したモデルを準備") }
                    GemmaInformationLinks(state.selectedGemmaModel)
                }
            }
            item { HorizontalDivider() }
            item {
                SettingsSection("音声") {
                    OutlinedButton(onClick=onOpenReferenceRecording,enabled=state.canChangeAudioMode,modifier=Modifier.fillMaxWidth()){Text("自分の声を録音")}
                    TtsSelector(state, onTtsBackendSelected)
                    TtsPreviewControls(state, onPreviewVoice, onStopVoicePreview, onDiagnoseVoicevox)
                    if (state.ttsBackend == TtsBackend.VOICEVOX) {
                        VoicevoxSetup(state, onPrepareVoicevox)
                    } else if (state.ttsBackend == TtsBackend.POCKET) {
                        jp.stackchan.localvoicepoc.pocket.PocketSetup(state, onPreparePocket, onPickPocketAsset,
                            onImportReferenceEncoder, onPreparePocketPresets, onSelectReferenceVoice,
                            onPreviewPocketVoice, onRemovePocketPreset, onCancelReferenceVoice)
                    } else if (state.ttsBackend == TtsBackend.SANO) {
                        jp.stackchan.localvoicepoc.sano.SanoSetup(state, onPrepareSano, onPickSanoAsset)
                    } else {
                    StatusRow("推奨音声", if (state.piperLoaded) "利用中" else "未読込")
                    ModelProgressLine("Piper Plus", state.piperProgress)
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.piperAarPresent && state.canStartModelMutation,
                        onClick = onPrepareRecommendedPiper,
                    ) { Text("推奨音声を再準備") }
                    TextButton(onClick = { showAdvancedVoice = !showAdvancedVoice }) {
                        Text(if (showAdvancedVoice) "詳細設定を閉じる" else "詳細設定")
                    }
                    if (showAdvancedVoice) {
                        PiperManualSettings(
                            state,
                            onPickPiperModel,
                            onPickPiperConfig,
                            onPickDictionary,
                            onLoadPiper,
                        )
                    }
                }
            }
            }
            item { HorizontalDivider() }
            item {
                SettingsSection("MCP接続") {
                    if (state.mcpProfiles.isEmpty()) {
                        Text("接続プロファイルはありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.mcpProfiles.forEach { profile ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(profile.displayName, fontWeight = FontWeight.SemiBold)
                                Text(profile.connectorId, style = MaterialTheme.typography.labelSmall)
                                Text(profile.serverUrl, style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = {
                                        editingMcpProfile = profile
                                        showMcpEditor = true
                                    }) { Text("編集") }
                                    TextButton(onClick = { onDeleteMcpProfile(profile.connectorId) }) {
                                        Text("削除")
                                    }
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            editingMcpProfile = null
                            showMcpEditor = true
                        },
                    ) { Text("MCPプロファイルを追加") }
                }
            }
            item { HorizontalDivider() }
            item {
                SettingsSection("CoreS3 USB") {
                    ConnectionStatus(state)
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onRetryUsbConnection) {
                        LucideIcon(LucideR.drawable.lucide_ic_refresh_cw, null)
                        Spacer(Modifier.width(8.dp))
                        Text("接続を再試行")
                    }
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
    if (showMcpEditor) {
        McpProfileDialog(
            initial = editingMcpProfile,
            onDismiss = { showMcpEditor = false },
            onSave = { profile, token ->
                onSaveMcpProfile(profile, token)
                showMcpEditor = false
            },
        )
    }
}

@Composable
private fun McpProfileDialog(
    initial: McpProfile?,
    onDismiss: () -> Unit,
    onSave: (McpProfile, String?) -> Unit,
) {
    var connectorId by remember(initial) { mutableStateOf(initial?.connectorId.orEmpty()) }
    var displayName by remember(initial) { mutableStateOf(initial?.displayName.orEmpty()) }
    var serverUrl by remember(initial) { mutableStateOf(initial?.serverUrl.orEmpty()) }
    var bearerToken by remember(initial) { mutableStateOf("") }
    var allowCleartext by remember(initial) { mutableStateOf(initial?.allowCleartext ?: false) }
    var forceApproval by remember(initial) { mutableStateOf(initial?.forceApproval ?: true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "MCPプロファイルを追加" else "MCPプロファイルを編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = connectorId,
                    onValueChange = { connectorId = it },
                    label = { Text("connector_id") },
                    singleLine = true,
                    enabled = initial == null,
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("表示名") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Streamable HTTP URL") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = bearerToken,
                    onValueChange = { bearerToken = it },
                    label = { Text(if (initial?.hasBearerToken == true) "Bearer token（空欄なら維持）" else "Bearer token") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allowCleartext, onCheckedChange = { allowCleartext = it })
                    Text("HTTP平文通信を許可")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = forceApproval, onCheckedChange = { forceApproval = it })
                    Text("実行時に常に確認")
                }
            }
        },
        confirmButton = {
            Button(
                enabled = connectorId.isNotBlank() && displayName.isNotBlank() && serverUrl.isNotBlank(),
                onClick = {
                    onSave(
                        McpProfile(
                            connectorId = connectorId,
                            displayName = displayName,
                            serverUrl = serverUrl,
                            allowCleartext = allowCleartext,
                            forceApproval = forceApproval,
                            hasBearerToken = initial?.hasBearerToken ?: false,
                        ),
                        bearerToken.takeIf { it.isNotBlank() },
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
private fun PiperManualSettings(
    state: MainUiState,
    onPickPiperModel: () -> Unit,
    onPickPiperConfig: () -> Unit,
    onPickDictionary: () -> Unit,
    onLoadPiper: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusRow("ONNX", presenceLabel(state.piperModelPresent))
        StatusRow("JSON", presenceLabel(state.piperConfigPresent))
        StatusRow("OpenJTalk辞書", presenceLabel(state.piperDictionaryPresent))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = state.canStartModelMutation,
                onClick = onPickPiperModel,
            ) {
                Text("ONNX")
            }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = state.canStartModelMutation,
                onClick = onPickPiperConfig,
            ) {
                Text("JSON")
            }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = state.canStartModelMutation,
                onClick = onPickDictionary,
            ) {
                Text("辞書")
            }
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = state.piperAarPresent && state.piperModelPresent && state.piperConfigPresent &&
                state.piperDictionaryPresent && state.canStartModelMutation,
            onClick = onLoadPiper,
        ) { Text("取り込んだ音声を使う") }
    }
}

@Composable
private fun EmptyConversation(state: MainUiState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StackChanFace(104.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            phaseLabel(state.phase, state.automaticMode),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                !state.assetsReady -> "設定で会話モデルと音声を準備してください"
                state.audioTransition -> "切り替えています"
                state.audioMode == ConversationAudioMode.PHONE && !state.microphoneGranted -> "マイクを許可すると、スマホだけで話せます"
                state.audioMode == ConversationAudioMode.PHONE -> "スマホに話しかけてください"
                state.usbStatus == UsbConnectionStatus.READY -> "接続端末に話しかけてください"
                else -> "接続端末をつなぐか、スマホを選んでください"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioModeSelector(state: MainUiState, select: (ConversationAudioMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        listOf(ConversationAudioMode.PHONE, ConversationAudioMode.DOCK).forEachIndexed { index, mode ->
            SegmentedButton(selected = state.audioMode == mode, onClick = { select(mode) },
                enabled = state.canChangeAudioMode,
                shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(mode.label) }
        }
    }
}

@Composable
private fun UsbBanner(state: MainUiState, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideIcon(LucideR.drawable.lucide_ic_usb, null)
        Spacer(Modifier.width(8.dp))
        Text(usbStatusLabel(state), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (state.usbStatus == UsbConnectionStatus.DISCONNECTED || state.usbStatus == UsbConnectionStatus.ERROR) {
            TextButton(onClick = onRetry) { Text("再接続") }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, draft: Boolean = false) {
    val user = message.role == ChatMessage.Role.USER
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.88f),
            colors = CardDefaults.cardColors(
                containerColor = if (user) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    if (user) "あなた" else "ｽﾀｯｸﾁｬﾝ",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(message.text, style = MaterialTheme.typography.bodyLarge)
                if (draft) {
                    Spacer(Modifier.height(4.dp))
                    Text("考えています…", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatus(state: MainUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConnectionDot(state.usbStatus)
            Spacer(Modifier.width(10.dp))
            Text(usbStatusLabel(state), fontWeight = FontWeight.SemiBold)
        }
        state.usbError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ConnectionDot(status: UsbConnectionStatus) {
    val color = when (status) {
        UsbConnectionStatus.READY -> Color(0xFF16835E)
        UsbConnectionStatus.PERMISSION_PENDING, UsbConnectionStatus.CONNECTING -> Color(0xFFC68516)
        UsbConnectionStatus.DISCONNECTED, UsbConnectionStatus.ERROR -> MaterialTheme.colorScheme.error
    }
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color)
            .semantics {
                contentDescription = when (status) {
                    UsbConnectionStatus.READY -> "USB接続済み"
                    UsbConnectionStatus.PERMISSION_PENDING -> "USB許可待ち"
                    UsbConnectionStatus.CONNECTING -> "USB接続中"
                    UsbConnectionStatus.DISCONNECTED -> "USB未接続"
                    UsbConnectionStatus.ERROR -> "USB通信エラー"
                }
            },
    )
}

@Composable
private fun StepHeading(icon: Int, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { LucideIcon(icon, null, Modifier.size(26.dp)) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InlineError(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideIcon(LucideR.drawable.lucide_ic_triangle_alert, null)
        Spacer(Modifier.width(8.dp))
        Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
        TextButton(onClick = onDismiss) { Text("閉じる") }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GemmaModelSelector(
    selected: GemmaModelSpec,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        GemmaModelManifest.all.forEachIndexed { index, model ->
            SegmentedButton(
                selected = model == selected,
                onClick = { onSelected(model.id) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, GemmaModelManifest.all.size),
            ) {
                val label = when (model) {
                    GemmaModelManifest.E2B -> "E2B 速度"
                    GemmaModelManifest.E4B -> "E4B 品質"
                    else -> "A1 ツール"
                }
                Text(label, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ModelProgressLine(label: String, progress: ComponentProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text(
                progress.stage,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GemmaInformationLinks(model: GemmaModelSpec) {
    val uriHandler = LocalUriHandler.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        TextButton(onClick = { uriHandler.openUri(model.modelCardUrl) }) { Text("モデル情報") }
        TextButton(onClick = { uriHandler.openUri(model.licenseUrl) }) { Text("利用条件") }
    }
}

@Composable
private fun PiperTermsDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("推奨音声の利用条件") },
        text = { Text("つくよみちゃんコーパスの利用条件が適用されます。リンク先を確認してください。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("確認して準備") } },
        dismissButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { uriHandler.openUri(PiperAssetLinks.MODEL_LICENSE_URL) }) {
                    LucideIcon(LucideR.drawable.lucide_ic_external_link, null)
                    Spacer(Modifier.width(6.dp))
                    Text("利用条件を開く")
                }
                TextButton(onClick = onDismiss) { Text("キャンセル") }
            }
        },
    )
}

@Composable
private fun StackChanFace(size: androidx.compose.ui.unit.Dp) {
    Image(
        painter = painterResource(R.drawable.ic_launcher),
        contentDescription = "ｽﾀｯｸﾁｬﾝ",
        modifier = Modifier
            .size(size)
            .clip(MaterialTheme.shapes.medium),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun LucideIcon(resource: Int, description: String?, modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(resource),
        contentDescription = description,
        modifier = modifier.size(20.dp),
    )
}

private enum class ConversationActionKind { START_AUTOMATIC, START_RECORDING, FINISH_RECORDING, STOP }

private data class ConversationAction(
    val kind: ConversationActionKind,
    val label: String,
    val icon: Int,
    val enabled: Boolean,
)

private fun conversationAction(state: MainUiState): ConversationAction = when {
    state.automaticMode && state.phase == ConversationPhase.IDLE -> ConversationAction(
        ConversationActionKind.START_AUTOMATIC,
        if (state.canRequestMicrophone) "マイクを許可" else "自動会話をはじめる",
        LucideR.drawable.lucide_ic_radio,
        state.pipelineReady || state.canRequestMicrophone,
    )
    state.automaticMode -> ConversationAction(
        ConversationActionKind.STOP,
        "自動会話を停止",
        LucideR.drawable.lucide_ic_square_stop,
        true,
    )
    state.phase == ConversationPhase.IDLE -> ConversationAction(
        ConversationActionKind.START_RECORDING,
        "話しかける",
        LucideR.drawable.lucide_ic_mic,
        state.pipelineReady || state.canRequestMicrophone,
    )
    state.phase == ConversationPhase.RECORDING -> ConversationAction(
        ConversationActionKind.FINISH_RECORDING,
        "応答する",
        LucideR.drawable.lucide_ic_message_circle,
        true,
    )
    else -> ConversationAction(
        ConversationActionKind.STOP,
        "処理を中断",
        LucideR.drawable.lucide_ic_square_stop,
        true,
    )
}

private fun restoreProgress(state: MainUiState): Float {
    val model = state.modelProgress.values.map { it.fraction }.average().toFloat()
    val voice = when (state.ttsBackend) { TtsBackend.VOICEVOX -> state.voicevoxProgress; TtsBackend.SANO -> state.sanoProgress; TtsBackend.POCKET -> state.pocketProgress; else -> state.piperProgress }
    return if (state.modelsReady) (0.8f + voice.fraction * 0.2f) else model * 0.8f
}

private fun usbStatusLabel(state: MainUiState): String = when (state.usbStatus) {
    UsbConnectionStatus.DISCONNECTED -> "接続端末が見つかりません"
    UsbConnectionStatus.PERMISSION_PENDING -> "USBの利用許可を待っています"
    UsbConnectionStatus.CONNECTING -> "接続端末につないでいます"
    UsbConnectionStatus.READY -> "接続済み"
    UsbConnectionStatus.ERROR -> "USB通信でエラーが発生しました"
}

private fun phaseLabel(phase: ConversationPhase, automaticMode: Boolean): String = when (phase) {
    ConversationPhase.IDLE -> "話しかけられます"
    ConversationPhase.CONNECTING -> "マイクを準備しています"
    ConversationPhase.LISTENING -> "声を待っています"
    ConversationPhase.RECORDING -> if (automaticMode) "聞いています" else "録音しています"
    ConversationPhase.TRANSCRIBING -> "声を認識しています"
    ConversationPhase.THINKING -> "考えています"
    ConversationPhase.SPEAKING -> "話しています"
}

private fun formatModelSize(bytes: Long): String =
    String.format(Locale.ROOT, "%.2fGB", bytes / 1_000_000_000.0)

private fun presenceLabel(present: Boolean): String = if (present) "取込済み" else "未取込"

@Preview(name = "初回セットアップ", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun SetupPreview() {
    StackChanTheme {
        SetupScreen(
            state = MainUiState(
                startupStatus = AppStartupStatus.SETUP_REQUIRED,
                sdkStatus = SdkBootstrap.Status.Ready,
                piperAarPresent = true,
            ),
            onGemmaModelSelected = {},
            onPrepareModels = {},
            onPrepareRecommendedPiper = {},
            onRetryUsbConnection = {},
            onFinishSetup = {},
            onDismissError = {},
        )
    }
}

@Preview(
    name = "会話・ダーク・大きな文字",
    showBackground = true,
    widthDp = 344,
    heightDp = 760,
    fontScale = 1.5f,
)
@Composable
private fun ConversationPreview() {
    StackChanTheme(darkTheme = true) {
        ConversationScreen(
            state = MainUiState(
                startupStatus = AppStartupStatus.READY,
                sdkStatus = SdkBootstrap.Status.Ready,
                modelsReady = true,
                piperLoaded = true,
                usbStatus = UsbConnectionStatus.READY,
                messages = listOf(
                    ChatMessage(ChatMessage.Role.USER, "今日の予定を教えて"),
                    ChatMessage(ChatMessage.Role.ASSISTANT, "今日は午後に予定があります。"),
                ),
            ),
            onOpenSettings = {},
            onAutomaticModeChanged = {},
            onStartAutomatic = {},
            onStopConversation = {},
            onStartPushToTalk = {},
            onStopPushToTalk = {},
            onRetryUsbConnection = {},
            snackbarHostState = remember { SnackbarHostState() },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TtsSelector(state: MainUiState, select: (TtsBackend) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TtsBackend.entries.chunked(2).forEach { row ->
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                row.forEachIndexed { index, backend ->
                    SegmentedButton(selected = state.ttsBackend == backend, onClick = { select(backend) },
                        enabled = state.canStartModelMutation && when (backend) {
                            TtsBackend.PIPER -> state.piperAarPresent
                            TtsBackend.PIPER2 -> state.piper2Available
                            TtsBackend.VOICEVOX -> state.voicevoxAvailable
                            TtsBackend.SANO -> state.sanoAvailable
                            TtsBackend.POCKET -> state.pocketAvailable
                        }, shape = SegmentedButtonDefaults.itemShape(index,row.size)) { Text(backend.label) }
                }
            }
        }
        if (state.ttsBackend.isPiper) {
            Text(jp.stackchan.localvoicepoc.piper.PIPER_MODEL_CREDIT)
            val uriHandler = LocalUriHandler.current
            TextButton(onClick = { uriHandler.openUri(PiperAssetLinks.MODEL_LICENSE_URL) }) { Text("推奨音声の利用条件") }
        }
    }
}

@Composable
private fun VoicevoxSetup(state: MainUiState, prepare: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(VoicevoxAssets.CREDIT, fontWeight = FontWeight.SemiBold)
        Text("ずんだもん・ノーマル。公開時にはこのクレジットを表示してください。Android 10 以上で利用できます。")
        ModelProgressLine("VOICEVOX", state.voicevoxProgress)
        Text("初回ダウンロード: 約 81.9 MB（モデル・辞書）。試聴はスマホのスピーカーから再生します。")
        OutlinedButton(onClick = prepare, enabled = state.voicevoxAvailable && state.canStartModelMutation) {
            Text(if (state.voicevoxLoaded) "VOICEVOX を再準備" else "VOICEVOX を準備")
        }
    }
}

@Composable
private fun TtsPreviewControls(state: MainUiState, preview: () -> Unit, stop: () -> Unit, diagnose: () -> Unit) {
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.ttsPreviewRunning) {
            OutlinedButton(onClick = stop) { Text(if (state.voicevoxDiagnosticRunning) "診断を停止" else "試聴を停止") }
        } else {
            OutlinedButton(onClick = preview, enabled = state.voiceReady && state.canStartModelMutation) {
                Text("スマホで音声を試聴")
            }
        }
        TextButton(onClick = { showDiagnostics = !showDiagnostics }) { Text(if (showDiagnostics) "詳細な音声診断を閉じる" else "詳細な音声診断") }
        if (showDiagnostics) {
        if (state.ttsBackend == TtsBackend.VOICEVOX) {
            OutlinedButton(onClick = diagnose, enabled = state.voicevoxLoaded && state.canStartModelMutation) {
                Text("開始時間を診断（CPU 1/2/4/auto）")
            }
            Text("各設定で初回とwarm2回を再生します。停止時は処理終了とCPU4復元を待ちます。", style = MaterialTheme.typography.bodySmall)
        }
        if (state.voicevoxDiagnosticReport.isNotBlank()) {
            val context = androidx.compose.ui.platform.LocalContext.current
            TextButton(onClick = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TTS timing", state.voicevoxDiagnosticReport))
            }) { Text("診断結果をコピー") }
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(state.voicevoxDiagnosticReport.takeLast(1800), style = MaterialTheme.typography.bodySmall)
            }
        }
        }
        Text("選んだ音声で短い挨拶を再生します。",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Preview(name = "スマホ・マイク許可前", showBackground = true, widthDp = 344, heightDp = 760, fontScale = 1.5f)
@Composable
private fun PhonePermissionPreview() { PhoneModePreview(MainUiState(audioMode = ConversationAudioMode.PHONE,
    sdkStatus = SdkBootstrap.Status.Ready, modelsReady = true, pocketLoaded = true, ttsBackend = TtsBackend.POCKET)) }

@Preview(name = "スマホ・準備前", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun PhonePreparationPreview() { PhoneModePreview(MainUiState(audioMode = ConversationAudioMode.PHONE, sdkStatus = SdkBootstrap.Status.Ready)) }

@Composable
private fun PhoneModePreview(state: MainUiState) {
    StackChanTheme {
        ConversationScreen(state = state, onOpenSettings = {}, onAutomaticModeChanged = {}, onStartAutomatic = {},
            onStopConversation = {}, onStartPushToTalk = {}, onStopPushToTalk = {}, onRetryUsbConnection = {},
            snackbarHostState = remember { SnackbarHostState() })
    }
}
