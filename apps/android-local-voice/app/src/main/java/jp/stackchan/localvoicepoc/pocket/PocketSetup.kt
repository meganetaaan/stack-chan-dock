package jp.stackchan.localvoicepoc.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import jp.stackchan.localvoicepoc.ui.MainUiState

@Composable
fun PocketSetup(state: MainUiState, prepare: () -> Unit, import: () -> Unit,
    importEncoder: () -> Unit = {}, preparePresets: () -> Unit = {}, selectVoice: (String?) -> Unit = {},
    previewVoice: (String?) -> Unit = {}, removePreset: (String) -> Unit = {}, cancelVoice: () -> Unit = {}) {
    val uri = LocalUriHandler.current
    var showTerms by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Pocket TTS 日本語 / takuma104・Kyutai")
        Text("使用する声：" + PocketPresets.name(state.pocketVoices.selectedId, state.pocketVoices.profiles))
        Text("モデル: CC BY-NC 4.0。本人限定の非商用評価用です。")
        Text(if (state.pocketLoaded) "準備完了" else state.pocketProgress.stage)
        if (state.pocketBusy) LinearProgressIndicator(progress = { state.pocketProgress.fraction })
        OutlinedButton(onClick = import, enabled = state.pocketAvailable && state.canStartModelMutation) { Text("PocketモデルZIPを取り込む") }
        OutlinedButton(onClick = prepare, enabled = state.pocketAvailable && state.canStartModelMutation) { Text("保存済みPocketを検証・読み込み") }
        Text("以前のモデルZIPをそのまま使えます。モデル約449MB、読み込みには1.5GiB以上の空きメモリが必要です。")
        TextButton(onClick = { uri.openUri(PocketAssetStore.MODEL_URL) }) { Text("モデルの公式出典を開く") }
        TextButton(onClick = { showTerms = true }) { Text("利用条件とクレジット") }
        PocketPresetChoices(state.pocketVoices, state.canChoosePocketVoices, state.pocketLoaded,
            importEncoder, preparePresets, selectVoice, previewVoice, removePreset, cancelVoice)
    }
    if (showTerms) PocketTermsDialog(null) { showTerms = false }
}

@Composable
fun PocketTermsDialog(confirm: (() -> Unit)?, dismiss: () -> Unit) {
    val context = LocalContext.current
    val notices = remember(context) {
        listOf("pocket-ja-attribution.txt", "pocket-exporter-MIT.txt", "sentencepiece-Apache2.txt", "onnxruntime-LICENSE.txt").joinToString("\n\n") {
            context.assets.open("pocket-notices/$it").bufferedReader(Charsets.UTF_8).use { input -> input.readText() }
        }
    }
    val uri = LocalUriHandler.current
    AlertDialog(onDismissRequest = dismiss, title = { Text("Pocket TTS 日本語の利用条件") },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("モデル重みと派生ONNX・固定音声状態はCC BY-NC 4.0（表示・非営利）です。コードのMIT / SentencePieceのApache 2.0とは条件が異なります。今回は本人限定の非商用評価として利用します。")
            TextButton(onClick = { uri.openUri(PocketAssetStore.MODEL_URL) }) { Text("モデル出典") }
            TextButton(onClick = { uri.openUri(PocketAssetStore.LICENSE_URL) }) { Text("CC BY-NC 4.0") }
            TextButton(onClick = { uri.openUri("https://github.com/microsoft/onnxruntime/blob/v1.30.0/ThirdPartyNotices.txt") }) { Text("ONNX Runtimeの第三者NOTICE") }
            Text(notices)
        } },
        confirmButton = { TextButton(onClick = confirm ?: dismiss) { Text(if (confirm == null) "閉じる" else "確認して準備") } },
        dismissButton = { if (confirm != null) TextButton(onClick = dismiss) { Text("キャンセル") } })
}
