package jp.stackchan.localvoicepoc.sano

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Defaults to the two upstream v1.2.0 assets; explicit expected assets use the same bounded verifier. Provider names are ignored. */
object SanoAssets {
    data class Asset(val name:String,val bytes:Long,val sha256:String) {
        val url get()="https://github.com/ayutaz/sanoTTS-jp/releases/download/v1.2.0/$name"
    }
    val weights=Asset("saanotts-jp-v4-int8.bin",654032L,"a1eb6b0812e2ad2a228836088a3e34160cb66731492fb957a5891605db2fa1b6")
    val dictionary=Asset("k1-dict-438750.bin",13702320L,"f162c922074d76817298b34d8a8fd35f7d195f38540303485a76c956b5d84877")
    val all=listOf(weights,dictionary)
    data class Inspection(val status:String,val observedBytes:Long?,val observedSha256:String?,val error:String?=null)
    private fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it.toInt() and 255)}
    fun inspect(file:File,asset:Asset,active:()->Unit={}):Inspection {
        if(!file.exists())return Inspection("missing",null,null)
        if(!file.isFile||!file.canRead())return Inspection("unreadable",file.length(),null)
        if(file.length()!=asset.bytes)return Inspection("size_mismatch",file.length(),null)
        return try {
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use {input->val buffer=ByteArray(65536);while(true){active();val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
            val hash=hex(digest.digest())
            Inspection(if(hash==asset.sha256)"verified" else "hash_mismatch",file.length(),hash)
        } catch(e:java.io.IOException){Inspection("unreadable",file.length(),null,e.toString())}
    }
    /** Bounded streaming copy, hash before atomic replacement; a rejected import leaves the previous asset intact. */
    fun install(input:InputStream,directory:File,expected:Asset?=null,active:()->Unit={},progress:(Long)->Unit={}):Asset {
        val temporary=try {
            directory.mkdirs()
            // A killed process cannot run finally. Only clean this app's generated staging files on the next serial import.
            val canonical=directory.canonicalFile
            directory.listFiles()?.filter {it.name.matches(Regex("sano-import-[0-9-]+\\.tmp"))&&it.canonicalFile.parentFile==canonical}?.forEach {check(it.delete()){"Cannot remove previous interrupted import staging file"}}
            File.createTempFile("sano-import-",".tmp",directory)
        }catch(e:Throwable){runCatching {input.close()};throw e}
        try {
            val digest=MessageDigest.getInstance("SHA-256");var count=0L
            input.use {source->temporary.outputStream().use {target->val buffer=ByteArray(65536);while(true){active();val n=source.read(buffer);if(n<0)break;count+=n;check(count<=(expected?.bytes?:dictionary.bytes)){"Selected/downloaded file exceeds pinned size"};target.write(buffer,0,n);digest.update(buffer,0,n);progress(count)}}}
            active()
            val asset=expected?:all.singleOrNull {it.bytes==count}?:error("Selected file size $count is neither pinned sano weights nor dictionary")
            check(count==asset.bytes){"Size mismatch: ${asset.name}, expected ${asset.bytes}, observed $count"}
            val hash=hex(digest.digest());check(hash==asset.sha256){"SHA mismatch: ${asset.name}, expected ${asset.sha256}, observed $hash"}
            active()
            val destination=File(directory,asset.name)
            try{Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}catch(_:java.nio.file.AtomicMoveNotSupportedException){Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING)}
            return asset
        }finally{temporary.delete()}
    }
}
