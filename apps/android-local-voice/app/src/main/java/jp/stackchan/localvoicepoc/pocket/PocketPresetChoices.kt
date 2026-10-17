package jp.stackchan.localvoicepoc.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

@Composable
fun PocketPresetChoices(state: PocketVoiceUiState, canManage: Boolean, pocketReady: Boolean,
    onImportEncoder: () -> Unit, onPrepare: () -> Unit, onSelect: (String?) -> Unit,
    onPreview: (String?) -> Unit, onRemove: (String) -> Unit, onCancel: () -> Unit) {
    var chosen by rememberSaveable { mutableStateOf(PocketPresets.DEFAULT) }
    var expanded by remember { mutableStateOf(false) }
    var confirmDownload by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<PocketPreset?>(null) }
    val uri = LocalUriHandler.current
    LaunchedEffect(state.selectedId) { PocketPresets.selected(state.selectedId)?.let { chosen = it.id } }
    DisposableEffect(Unit) { onDispose { onCancel() } }
    val spec = PocketPresets.get(chosen)
    val ready = chosen in state.readyPresets
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("標準の日本語音声", style = MaterialTheme.typography.titleMedium)
        Text("使用中：" + PocketPresets.name(state.selectedId, state.profiles))
        Text("音声の説明は配布元のものです。先頭5秒を使うため、聞こえ方は試聴して確認してください。",
            style = MaterialTheme.typography.bodySmall)
        if (!pocketReady) Text("先にPocketモデルを取り込み、読み込んでください。")
        if (state.busy) {
            state.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                ?: LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = onCancel) { Text(if (state.previewing) "試聴を停止" else "処理を中止") }
        } else {
            if (!state.encoderReady) {
                OutlinedButton(onClick = onImportEncoder, enabled = canManage) { Text("声を作る追加モデルを取り込む") }
                Text("pocket-tts-ja-reference-encoder-v1.zip（約18MB）を選びます。取り込み済みなら再取得は不要です。",
                    style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { confirmDownload = true },
                enabled = canManage && state.encoderReady && state.readyPresets.size < 10) {
                Text("公式音声を準備（" + state.readyPresets.size + "/10）")
            }
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = !state.busy) {
                Text(spec.label + if (ready) " ▾" else "・未準備 ▾")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 360.dp)) {
                PocketPresets.all.forEach { voice ->
                    DropdownMenuItem(text = { Text(voice.label + if (voice.id in state.readyPresets) "" else "（未準備）") },
                        onClick = { chosen = voice.id; expanded = false })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onSelect(PocketPresets.selection(chosen)) },
                enabled = canManage && ready && state.selectedId != PocketPresets.selection(chosen)) { Text("この声を使う") }
            OutlinedButton(onClick = { onPreview(PocketPresets.selection(chosen)) },
                enabled = canManage && ready) { Text("選んで試聴") }
        }
        Text("試聴は端末のスピーカーで再生し、その声を読み上げにも使います。", style = MaterialTheme.typography.bodySmall)
        if (ready && chosen != PocketPresets.DEFAULT) {
            TextButton(onClick = { confirmRemove = spec }, enabled = canManage) { Text("この標準音声の準備を解除") }
        }
        TextButton(onClick = { uri.openUri(PocketPresets.SOURCE) }) { Text("公式音声の配布元・条件を確認") }
        if (state.profiles.isNotEmpty()) {
            HorizontalDivider()
            Text("録音から作った声", style = MaterialTheme.typography.titleMedium)
            state.profiles.forEach { profile ->
                Text(profile.name)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSelect(profile.id) },
                        enabled = canManage && state.selectedId != profile.id) { Text("この声を使う") }
                    OutlinedButton(onClick = { onPreview(profile.id) }, enabled = canManage) { Text("選んで試聴") }
                }
            }
        }
    }
    if (confirmDownload) AlertDialog(onDismissRequest = { confirmDownload = false },
        title = { Text("公式の9音声をこの端末に取得") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Hugging Faceの公式配布元から合成サンプル約6MBを取得し、この端末で先頭5秒から声を作ります。録音は送信しません。途中で中止でき、作成済みの声と現在の選択は残ります。")
            Text("本人用の非商用評価として使います。配布元のモデル表示はCC BY-NC 4.0ですが、追加WAVや作成した声の再配布許諾は確認できていないため、共有・配布用には使いません。")
            TextButton(onClick = { uri.openUri(PocketPresets.SOURCE) }) { Text("配布元を開く") }
        } },
        confirmButton = { TextButton(onClick = { confirmDownload = false; onPrepare() },
            enabled = canManage && state.encoderReady) { Text("公式から取得して準備") } },
        dismissButton = { TextButton(onClick = { confirmDownload = false }) { Text("キャンセル") } })
    confirmRemove?.let { voice ->
        AlertDialog(onDismissRequest = { confirmRemove = null }, title = { Text("準備データを削除しますか？") },
            text = { Text("「" + voice.label + "」の準備データを削除します。使用中なら女性3の声に戻します。録音や録音から作った声は残ります。") },
            confirmButton = { TextButton(onClick = { confirmRemove = null; onRemove(voice.id) },
                enabled = canManage) { Text("削除") } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("キャンセル") } })
    }
}
