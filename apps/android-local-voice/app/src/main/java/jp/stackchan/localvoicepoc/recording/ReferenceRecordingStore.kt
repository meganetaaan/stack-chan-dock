package jp.stackchan.localvoicepoc.recording

import com.google.gson.Gson
import jp.stackchan.localvoicepoc.audio.PcmWav
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

const val REFERENCE_SCRIPT = "窓から、やわらかな光が入っています。ゆっくり深呼吸をして、いつもの声で話します。遠くの音に耳をすませながら、新しい一日を始めます。"
const val REFERENCE_MAX_SECONDS = 60

data class RecordingDraft(val pcm: ByteArray, val sampleRate: Int, val script: String = REFERENCE_SCRIPT) {
    val sampleCount get()=pcm.size/2
    val durationMs get()=sampleCount*1000L/sampleRate
}
data class SavedRecording(val id:String,val name:String,val sampleRate:Int,val sampleCount:Int,val script:String,
    val createdAtMillis:Long,val channels:Int=1,val bitsPerSample:Int=16,val schema:Int=1,
    val durationMs:Long=sampleCount*1000L/sampleRate)

data class RecordingDeletion(val cleanupPending: Boolean)

/** Drafts never touch storage. Explicit deletion never touches independently stored voice profiles. */
class ReferenceRecordingStore(private val root:File) {
    private val gson=Gson()
    private fun validName(name:String)=name.isNotBlank()&&name.codePointCount(0,name.length)<=40&&!name.any { it.isISOControl() }
    fun canSaveName(name:String)=validName(name.trim())
    @Synchronized
    fun list():List<SavedRecording> {
        root.listFiles().orEmpty().filter {it.name.matches(Regex("\\.deleted-[a-f0-9-]{36}"))}.forEach {runCatching {eraseDeleted(it)}}
        return root.listFiles().orEmpty().filter {it.isDirectory&&validId(it.name)}.mapNotNull {folder->
        runCatching {
            val file=File(folder,"recording.json");require(file.length() in 1..65536)
            val entry=gson.fromJson(file.readText(Charsets.UTF_8),SavedRecording::class.java)
            require(entry.id==folder.name&&validName(entry.name)&&entry.sampleRate in 8000..192000&&entry.sampleCount in entry.sampleRate..entry.sampleRate*REFERENCE_MAX_SECONDS)
            require(entry.channels==1&&entry.bitsPerSample==16&&entry.schema==1&&File(folder,"audio.wav").length()==44L+entry.sampleCount*2L)
            require(entry.durationMs==entry.sampleCount*1000L/entry.sampleRate)
            entry
        }.getOrNull()
        }.sortedByDescending {it.createdAtMillis}
    }

    /** The atomic rename is the deletion commit point. Cancellation before it preserves the recording. */
    @Synchronized
    fun delete(entry: SavedRecording, active: () -> Unit = {}): RecordingDeletion {
        require(validId(entry.id)); active()
        check(list().any {it == entry}) {"録音が見つかりません。画面を開き直してください"}
        val base = root.canonicalFile
        val source = File(base, entry.id)
        val deleted = File(base, ".deleted-${entry.id}")
        check(source.parentFile == base && source.canonicalFile == source && source.isDirectory)
        check(deleted.parentFile == base && !deleted.exists())
        active()
        Files.move(source.toPath(), deleted.toPath(), StandardCopyOption.ATOMIC_MOVE)
        // No cancellation after commit: either remove the two owned files or retry at the next list/open.
        return RecordingDeletion(cleanupPending = !runCatching {eraseDeleted(deleted)}.getOrDefault(false))
    }
    private fun eraseDeleted(folder: File): Boolean {
        if (!folder.exists()) return true
        val base = root.canonicalFile
        val canonical = folder.canonicalFile
        if (!folder.name.startsWith(".deleted-") || !validId(folder.name.removePrefix(".deleted-")) ||
            canonical.parentFile != base || canonical.name != folder.name || !folder.isDirectory) return false
        val children = folder.listFiles() ?: return false
        if (children.any {it.name !in setOf("audio.wav", "recording.json") || it.isDirectory || it.canonicalFile.parentFile != canonical}) return false
        return children.all {!it.exists() || it.delete()} && (!folder.exists() || folder.delete())
    }

    fun save(draft:RecordingDraft,name:String,active:()->Unit={}):SavedRecording {
        val title=name.trim();require(validName(title)){"録音の名前は1〜40文字で入力してください"}
        require(draft.sampleRate in 8000..192000&&draft.pcm.size%2==0&&draft.sampleCount in draft.sampleRate..draft.sampleRate*REFERENCE_MAX_SECONDS)
        root.mkdirs();check(root.isDirectory)
        val id=UUID.randomUUID().toString();val stage=File(root,".incoming-$id");check(stage.mkdir())
        var committed=false
        try {
            active()
            val entry=SavedRecording(id,title,draft.sampleRate,draft.sampleCount,draft.script,System.currentTimeMillis())
            val audio=PcmWav.encode(draft.pcm,draft.sampleRate)
            File(stage,"audio.wav").outputStream().use {out->
                var offset=0
                while(offset<audio.size){active();val n=minOf(65536,audio.size-offset);out.write(audio,offset,n);offset+=n}
                out.fd.sync()
            }
            active();File(stage,"recording.json").writeText(gson.toJson(entry),Charsets.UTF_8);active()
            Files.move(stage.toPath(),File(root,id).toPath(),StandardCopyOption.ATOMIC_MOVE);committed=true
            return entry
        } finally {if(!committed)stage.deleteRecursively()}
    }
    fun read(entry:SavedRecording):RecordingDraft {
        require(validId(entry.id)&&entry.sampleRate in 8000..192000&&entry.sampleCount in entry.sampleRate..entry.sampleRate*REFERENCE_MAX_SECONDS)
        val file=File(File(root,entry.id),"audio.wav")
        require(file.length()==44L+entry.sampleCount*2L)
        return RecordingDraft(PcmWav.decode(file.readBytes(),entry.sampleRate,entry.sampleCount),entry.sampleRate,entry.script)
    }
    private fun validId(id:String)=id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
}
