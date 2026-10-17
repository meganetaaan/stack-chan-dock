package jp.stackchan.localvoicepoc.sano

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import jp.stackchan.localvoicepoc.ui.MainUiState

@Composable
fun SanoSetup(state: MainUiState, prepare: () -> Unit, import: () -> Unit) {
    var showLicenses by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("sanoTTS-jp / つくよみちゃんコーパス（© 夢前黎）")
        Text("日本語を端末内で合成します。初回取得はモデルと辞書の約14.4MBです。")
        Text(if (state.sanoLoaded) "準備完了" else state.sanoProgress.stage)
        if (state.sanoBusy) LinearProgressIndicator(progress = { state.sanoProgress.fraction })
        OutlinedButton(onClick = prepare, enabled = state.sanoAvailable && state.canStartModelMutation) {
            Text(if (state.sanoLoaded) "sanoTTSを再準備" else "sanoTTSを準備")
        }
        OutlinedButton(onClick = import, enabled = state.sanoAvailable && state.canStartModelMutation) {
            Text("公式モデル／辞書ファイルを取込む")
        }
        Text("取得に失敗した場合は、公式リリースから2つのファイルを保存して、1つずつ取り込んでください。")
        val uriHandler = LocalUriHandler.current
        TextButton(onClick = { uriHandler.openUri("https://github.com/ayutaz/sanoTTS-jp/releases/tag/v1.2.0") }) { Text("公式ダウンロード") }
        TextButton(onClick = { showLicenses = true }) { Text("ライセンスとクレジット") }
    }
    if (showLicenses) SanoTermsDialog(null) { showLicenses = false }
}

@Composable
fun SanoTermsDialog(confirm: (() -> Unit)?, dismiss: () -> Unit) {
    val context = LocalContext.current
    val licenses = remember(context) {
        listOf("CREDITS.txt", "sano-LICENSE-MODEL.md", "sano-LICENSE", "NOTICE-dictionary.txt", "NOTICE-openjtalk.txt", "Apache-2.0.txt").joinToString("\n\n") { name ->
            name + "\n" + context.assets.open("sano-notices/$name").bufferedReader().use { it.readText() }
        }
    }
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("sanoTTSの利用条件とクレジット") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("モデルの条件はコードのMITライセンスと異なります。出力音声の用途制限を含む条件を確認してください。")
                TextButton(onClick = { uriHandler.openUri(SanoAssetStore.MODEL_TERMS) }) { Text("公式モデル利用条件") }
                TextButton(onClick = { uriHandler.openUri(SanoAssetStore.NOTICE) }) { Text("公式NOTICE") }
                Text(licenses)
            }
        },
        confirmButton = { TextButton(onClick = confirm ?: dismiss) { Text(if (confirm == null) "閉じる" else "同意して準備") } },
        dismissButton = { if (confirm != null) TextButton(onClick = dismiss) { Text("キャンセル") } },
    )
}
