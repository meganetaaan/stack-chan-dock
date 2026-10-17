package jp.stackchan.localvoicepoc.recording

import jp.stackchan.localvoicepoc.audio.*
import jp.stackchan.localvoicepoc.util.Pcm
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.selects.select

enum class RecordingPhase { IDLE, RECORDING, STOPPING, PLAYING, SAVING, DELETING }
data class ReferenceRecordingState(val open:Boolean=false,val phase:RecordingPhase=RecordingPhase.IDLE,val draft:RecordingDraft?=null,
    val name:String="",val durationMs:Long=0,val level:Float=0f,val saved:List<SavedRecording> = emptyList(),val message:String?=null,
    val deletePrompt:SavedRecording?=null)

/** Specific recorder workflow using the existing audio interfaces; contains no TTS or network path. */
class ReferenceRecorder(private val source:PcmAudioSource,private val sink:PcmAudioSink,
    private val store:ReferenceRecordingStore,private val scope:CoroutineScope) {
    private val mutable=MutableStateFlow(ReferenceRecordingState())
    val state:StateFlow<ReferenceRecordingState> = mutable.asStateFlow()
    private val generation=AtomicLong()
    private val transitions=Mutex()
    private var job:Job?=null
    private var stopSignal:CompletableDeferred<Unit>?=null
    @Volatile private var userStopped=false
    suspend fun open() = transitions.withLock { discardLocked(close=false);mutable.value=ReferenceRecordingState(open=true,saved=withContext(Dispatchers.IO){store.list()}) }
    fun setName(name:String){if(mutable.value.phase==RecordingPhase.IDLE&&mutable.value.deletePrompt==null)mutable.update{it.copy(name=name)}}
    fun start() {
        if(!mutable.value.open||mutable.value.phase!=RecordingPhase.IDLE||mutable.value.deletePrompt!=null||job?.isActive==true)return
        val request=generation.incrementAndGet();userStopped=false
        val stop=CompletableDeferred<Unit>();stopSignal=stop
        mutable.update{it.copy(phase=RecordingPhase.RECORDING,draft=null,name="",durationMs=0,level=0f,message=null)}
        job=scope.launch(Dispatchers.IO) {
            try {
                require(source.channelCount==1&&source.bytesPerSample==2&&source.sampleRate in 8000..192000)
                val bytes=ByteArrayOutputStream();val maximum=source.sampleRate*2*REFERENCE_MAX_SECONDS
                // The stop signal also cancels a capture that has not opened its source yet.
                // Only the child is cancelled; a deliberate stop can still publish its PCM draft.
                if (!stop.isCompleted) coroutineScope {
                    val capture=launch {
                        source.chunks().takeWhile {bytes.size()<maximum}.collect {chunk->
                            currentCoroutineContext().ensureActive();check(generation.get()==request)
                            require(chunk.size%2==0){"マイクの音声形式を確認できません"}
                            val count=minOf(chunk.size,maximum-bytes.size());bytes.write(chunk,0,count)
                            mutable.update{it.copy(durationMs=bytes.size()*1000L/(source.sampleRate*2),level=(Pcm.rms16Le(chunk)*8f).coerceIn(0f,1f))}
                            if(bytes.size()>=maximum)source.stop()
                        }
                    }
                    select<Unit> {
                        capture.onJoin { }
                        stop.onAwait { capture.cancelAndJoin() }
                    }
                }
                check(userStopped||bytes.size()>=maximum){"録音が中断されました。もう一度録音してください"}
                check(bytes.size()>=source.sampleRate*2){"1秒以上録音してください"}
                if(generation.get()==request)mutable.update{it.copy(draft=RecordingDraft(bytes.toByteArray(),source.sampleRate),message=null)}
            } catch(error:CancellationException){throw error
            } catch(error:Throwable){if(generation.get()==request)mutable.update{it.copy(message=if(error is SecurityException)"マイクの使用を許可してください" else error.message?:"録音できませんでした")}
            } finally {source.stop();if(generation.get()==request){stopSignal=null;job=null;mutable.update{it.copy(phase=RecordingPhase.IDLE,level=0f)}}}
        }
    }
    fun stopRecording() {
        if(mutable.value.phase!=RecordingPhase.RECORDING)return
        userStopped=true;mutable.update{it.copy(phase=RecordingPhase.STOPPING)};stopSignal?.complete(Unit);source.stop()
    }
    fun play(entry:SavedRecording?=null) {
        if(!mutable.value.open||mutable.value.phase!=RecordingPhase.IDLE||mutable.value.deletePrompt!=null||job?.isActive==true)return
        val draft=mutable.value.draft
        if(entry==null&&draft==null)return
        val request=generation.incrementAndGet();mutable.update{it.copy(phase=RecordingPhase.PLAYING,message=null)}
        job=scope.launch(Dispatchers.IO) {
            try {
                val audio=if(entry==null)checkNotNull(draft) else store.read(entry)
                sink.begin(audio.sampleRate)
                val buffer=ByteBuffer.wrap(audio.pcm).order(ByteOrder.LITTLE_ENDIAN)
                while(buffer.hasRemaining()){
                    currentCoroutineContext().ensureActive();check(generation.get()==request)
                    val samples=ShortArray(minOf(buffer.remaining()/2,2400)){buffer.short};sink.write(samples)
                }
                sink.finish()
            } catch(error:CancellationException){throw error
            } catch(_:Throwable){if(generation.get()==request)mutable.update{it.copy(message="録音を再生できませんでした")}
            } finally {sink.stop();if(generation.get()==request){job=null;mutable.update{it.copy(phase=RecordingPhase.IDLE)}}}
        }
    }
    suspend fun stopPlayback(){if(mutable.value.phase==RecordingPhase.PLAYING){val previous=job;previous?.cancel();sink.stop();previous?.join()}}
    fun save() {
        val current=mutable.value;val draft=current.draft?:return
        if(current.phase!=RecordingPhase.IDLE||current.deletePrompt!=null||job?.isActive==true)return
        if(!store.canSaveName(current.name)){mutable.update{it.copy(message="録音の名前は1〜40文字で入力してください")};return}
        val request=generation.incrementAndGet();mutable.update{it.copy(phase=RecordingPhase.SAVING,message=null)}
        job=scope.launch(Dispatchers.IO) {
            try {
                val coroutine=currentCoroutineContext()
                store.save(draft,current.name){coroutine.ensureActive()}
                val entries=store.list()
                if(generation.get()==request)mutable.update{it.copy(draft=null,name="",durationMs=0,saved=entries,message="録音を保存しました")}
            } catch(error:CancellationException){throw error
            } catch(_:Throwable){if(generation.get()==request)mutable.update{it.copy(message="保存できませんでした。空き容量を確認して再試行してください")}
            } finally {if(generation.get()==request){job=null;mutable.update{it.copy(phase=RecordingPhase.IDLE)}}}
        }
    }
    fun requestDelete(id:String,voiceBusy:Boolean=false) {
        val current=mutable.value
        if(voiceBusy||!current.open||current.phase!=RecordingPhase.IDLE||current.deletePrompt!=null||job?.isActive==true)return
        val entry=current.saved.firstOrNull {it.id==id}?:return
        mutable.update{it.copy(deletePrompt=entry,message=null)}
    }
    fun cancelDelete(){if(mutable.value.phase==RecordingPhase.IDLE)mutable.update{it.copy(deletePrompt=null)}}
    fun confirmDelete(voiceBusy:Boolean=false) {
        val current=mutable.value;val entry=current.deletePrompt?:return
        if(voiceBusy||!current.open||current.phase!=RecordingPhase.IDLE||job?.isActive==true)return
        val request=generation.incrementAndGet()
        mutable.update{it.copy(phase=RecordingPhase.DELETING,deletePrompt=null,message=null)}
        job=scope.launch(Dispatchers.IO) {
            try {
                val coroutine=currentCoroutineContext()
                val result=store.delete(entry){coroutine.ensureActive();check(generation.get()==request)}
                val entries=store.list()
                if(generation.get()==request)mutable.update{it.copy(saved=entries,message=if(result.cleanupPending)"録音を一覧から削除しました。残ったファイルの片付けを次回も試みます" else "「${entry.name}」の録音を削除しました。作成済みの声は引き続き使えます")}
            } catch(error:CancellationException){throw error
            } catch(_:Throwable){if(generation.get()==request)mutable.update{it.copy(message="録音を削除できませんでした。画面を開き直して再試行してください")}
            } finally {if(generation.get()==request){job=null;mutable.update{it.copy(phase=RecordingPhase.IDLE)}}}
        }
    }
    /** Immediate native stop, then await the old job before allowing a new capture. */
    suspend fun discard(close:Boolean,reason:String?=null) = transitions.withLock { discardLocked(close,reason) }
    private suspend fun discardLocked(close:Boolean,reason:String?=null) {
        generation.incrementAndGet();mutable.update{it.copy(phase=RecordingPhase.STOPPING)}
        job?.cancel();source.stop();sink.stop();job?.join();job=null
        val entries=withContext(Dispatchers.IO){store.list()}
        mutable.update{it.copy(open=if(close)false else it.open,phase=RecordingPhase.IDLE,draft=null,name="",durationMs=0,level=0f,message=reason,saved=entries,deletePrompt=null)}
    }
    fun interruptNow(){job?.cancel();source.stop();sink.stop()}
    fun close(){generation.incrementAndGet();interruptNow();mutable.value=ReferenceRecordingState()}
}
