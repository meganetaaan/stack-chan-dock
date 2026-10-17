package jp.stackchan.localvoicepoc.pocket

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PocketVoiceChoices(state: PocketVoiceUiState, canManage: Boolean, pocketReady: Boolean,
    onImport: () -> Unit, onSelect: (String?) -> Unit, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("録音から作った声", style = MaterialTheme.typography.titleMedium)
        Text("使用中：" + PocketPresets.name(state.selectedId, state.profiles))
        Text("標準10音声は声の設定で選べます。", style = MaterialTheme.typography.bodySmall)
        Text("ご自身の録音の先頭から最大5秒を使って、声を作れます。録音も声のデータも、この端末内だけで処理します。")
        if (!pocketReady) Text("使うには、声の設定でPocketを選んで準備してください。録音の保存・試聴はそのまま使えます。")
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = onCancel) { Text("処理を中止") }
        } else if (!state.encoderReady) {
            OutlinedButton(onClick = onImport, enabled = canManage) { Text("声を作る追加モデルを取り込む") }
            Text("選ぶファイル：pocket-tts-ja-reference-encoder-v1.zip（約18MB）。解凍せずZIPのまま選んでください。", style = MaterialTheme.typography.bodySmall)
        } else Text("下の保存済み録音から「この録音から声を作る」を選べます。", style = MaterialTheme.typography.bodySmall)
        state.message?.let { Text(it) }
        OutlinedButton(onClick = { onSelect(null) }, enabled = canManage && state.selectedId != null) {
            Text(if (state.selectedId == null) "使用中：標準の声（female03）" else "標準の声（female03）を使う")
        }
        for (profile in state.profiles) {
            Text(profile.name)
            OutlinedButton(onClick = { onSelect(profile.id) }, enabled = canManage && state.selectedId != profile.id) {
                Text(if (state.selectedId == profile.id) "この声を使用中" else "この声を使う")
            }
        }
    }
}
