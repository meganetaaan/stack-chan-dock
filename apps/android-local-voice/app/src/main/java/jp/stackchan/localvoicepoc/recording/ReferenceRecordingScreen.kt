package jp.stackchan.localvoicepoc.recording

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import jp.stackchan.localvoicepoc.ui.StackChanTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferenceRecordingScreen(state:ReferenceRecordingState,microphoneGranted:Boolean,onBack:()->Unit,
    onStart:()->Unit,onStop:()->Unit,onPlay:(String?)->Unit,onStopPlayback:()->Unit,onName:(String)->Unit,onSave:()->Unit,
    microphoneDenied:Boolean=false,onOpenPermissionSettings:()->Unit={},snackbarHostState:SnackbarHostState?=null,
    voices:jp.stackchan.localvoicepoc.pocket.PocketVoiceUiState=jp.stackchan.localvoicepoc.pocket.PocketVoiceUiState(),
    canManageVoices:Boolean=false,pocketReady:Boolean=false,onImportEncoder:()->Unit={},onCreateVoice:(String)->Unit={},
    onSelectVoice:(String?)->Unit={},onCancelVoice:()->Unit={},
    onRequestDelete:(String)->Unit={},onCancelDelete:()->Unit={},onConfirmDelete:()->Unit={}) {
    var confirmExit by remember { mutableStateOf(false) }
    LaunchedEffect(state.draft,state.phase) { if(state.draft==null&&state.phase!=RecordingPhase.RECORDING)confirmExit=false }
    fun leave() {
        if(state.phase==RecordingPhase.SAVING||state.phase==RecordingPhase.STOPPING||state.phase==RecordingPhase.DELETING)return
        if(state.draft!=null||state.phase==RecordingPhase.RECORDING)confirmExit=true else onBack()
    }
    BackHandler { leave() }
    Scaffold(snackbarHost={snackbarHostState?.let {SnackbarHost(it)}},topBar={TopAppBar(title={Text("声を録音")},navigationIcon={TextButton(onClick={leave()}){Text("戻る")}})}) {padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {
                Text("静かな場所で、普段の声で読み上げてください。",style=MaterialTheme.typography.bodyLarge)
                Text("録音はこの端末内に保存します。画面を離れると未保存の録音は破棄されます。",style=MaterialTheme.typography.bodySmall)
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("読む台本",style=MaterialTheme.typography.titleMedium)
                        Text(REFERENCE_SCRIPT,style=MaterialTheme.typography.bodyLarge)
                        Text("目安は10〜20秒（最大60秒）です。自然な速さで話してください。",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(!microphoneGranted&&microphoneDenied) {
                        Text("録音するにはマイクを許可してください。許可の画面が出ない場合は端末の設定で変更できます。")
                        TextButton(onClick=onOpenPermissionSettings){Text("端末の設定を開く")}
                    }
                    Text(when(state.phase){RecordingPhase.RECORDING->"録音中  ${state.durationMs/1000}秒";RecordingPhase.STOPPING->"停止しています";RecordingPhase.PLAYING->"再生しています";RecordingPhase.SAVING->"保存しています";RecordingPhase.DELETING->"録音を削除しています";RecordingPhase.IDLE->if(state.draft==null)"準備ができたら録音してください" else "録音できました  ${state.draft.durationMs/1000}秒"})
                    if(state.phase==RecordingPhase.RECORDING)LinearProgressIndicator(progress={state.level},modifier=Modifier.fillMaxWidth())
                    when(state.phase) {
                        RecordingPhase.RECORDING->Button(onClick=onStop,modifier=Modifier.fillMaxWidth()){Text("録音を停止")}
                        RecordingPhase.PLAYING->OutlinedButton(onClick=onStopPlayback,modifier=Modifier.fillMaxWidth()){Text("再生を停止")}
                        RecordingPhase.IDLE->{
                            if(state.draft==null)Button(onClick=onStart,enabled=!voices.busy,modifier=Modifier.fillMaxWidth()){Text(if(microphoneGranted)"録音を開始" else "マイクを許可")}
                            else {
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick={onPlay(null)},enabled=!voices.busy,modifier=Modifier.weight(1f)){Text("試聴")}
                                    OutlinedButton(onClick=onStart,enabled=!voices.busy,modifier=Modifier.weight(1f)){Text("録り直す")}
                                }
                                OutlinedTextField(value=state.name,onValueChange=onName,label={Text("録音の名前")},singleLine=true,
                                    supportingText={Text("40文字まで")},modifier=Modifier.fillMaxWidth())
                                Button(onClick=onSave,enabled=!voices.busy&&state.name.isNotBlank()&&state.name.codePointCount(0,state.name.length)<=40,modifier=Modifier.fillMaxWidth()){Text("名前を付けて保存")}
                            }
                        }
                        else->LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
                    }
                    state.message?.let {Text(it,style=MaterialTheme.typography.bodyMedium)}
                }
            }
            item {jp.stackchan.localvoicepoc.pocket.PocketVoiceChoices(voices,canManageVoices,pocketReady,onImportEncoder,onSelectVoice,onCancelVoice)}
            if(state.saved.isNotEmpty()) {
                item {HorizontalDivider();Text("保存した録音",style=MaterialTheme.typography.titleMedium)}
                items(state.saved,key={it.id}) {entry->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)){Text(entry.name);Text("${entry.durationMs/1000}秒",style=MaterialTheme.typography.bodySmall)}
                        OutlinedButton(onClick={onPlay(entry.id)},enabled=!voices.busy&&state.phase==RecordingPhase.IDLE){Text("試聴")}
                        TextButton(onClick={onRequestDelete(entry.id)},enabled=!voices.busy&&state.phase==RecordingPhase.IDLE&&state.deletePrompt==null,
                            colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Text("削除")}
                    }
                    val created=voices.profiles.any {it.recordingId==entry.id}
                    OutlinedButton(onClick={onCreateVoice(entry.id)},enabled=canManageVoices&&voices.encoderReady&&!created) {
                        Text(if(created)"この録音の声は作成済み" else "この録音から声を作る")
                    }
                }
            }
            item {Spacer(Modifier.height(24.dp))}
        }
    }
    state.deletePrompt?.let { entry ->
        AlertDialog(onDismissRequest=onCancelDelete,title={Text("録音を削除しますか？")},
            text={Text("「${entry.name}」の録音を削除します。作成済みの声と現在の選択は残ります。録音は元に戻せません。")},
            confirmButton={TextButton(onClick=onConfirmDelete,enabled=!voices.busy,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Text("削除")}},
            dismissButton={TextButton(onClick=onCancelDelete){Text("キャンセル")}})
    }
    if(confirmExit)AlertDialog(onDismissRequest={confirmExit=false},title={Text("録音画面を閉じますか？")},
        text={Text("未保存の録音は破棄されます。保存した録音は残ります。")},
        confirmButton={TextButton(onClick={confirmExit=false;onBack()}){Text("破棄して閉じる")}},
        dismissButton={TextButton(onClick={confirmExit=false}){Text("続ける")}})
}

@Preview(name="声の録音・台本",showBackground=true,widthDp=344,heightDp=760,fontScale=1.5f)
@Composable
private fun RecordingScriptPreview(){StackChanTheme{ReferenceRecordingScreen(ReferenceRecordingState(open=true),true,{},{},{},{},{},{},{})}}

@Preview(name="声の録音・保存前",showBackground=true,widthDp=360,heightDp=800)
@Composable
private fun RecordingSavePreview(){StackChanTheme{ReferenceRecordingScreen(ReferenceRecordingState(open=true,draft=RecordingDraft(ByteArray(48000),24000),name="いつもの声"),true,{},{},{},{},{},{},{})}}
