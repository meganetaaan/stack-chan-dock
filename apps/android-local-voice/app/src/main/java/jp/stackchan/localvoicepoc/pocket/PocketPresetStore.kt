package jp.stackchan.localvoicepoc.pocket

import com.google.gson.Gson
import dev.irte.voice.PocketVoiceState
import jp.stackchan.localvoicepoc.voicevox.VoicevoxWav
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class PocketPresetCache(val id: String, val sourceSha256: String, val frames: Int, val usedSamples: Int,
    val cacheSha256: String, val revision: String = PocketPresets.REVISION,
    val model: String = PocketVoiceState.MODEL, val encoder: String = PocketVoiceState.ENCODER,
    val bos: String = PocketVoiceState.BOS, val schema: Int = 1)

/** Device-local generated reference states only; never aliases the user's recordings/profiles. */
class PocketPresetStore(private val root: File, private val downloader: PocketPresetDownloader = PocketPresetDownloader()) {
    private val gson = Gson()
    fun resetDownload() = downloader.reset()
    fun cancelDownload() = downloader.cancel()
    fun reference(spec: PocketPreset, active: () -> Unit, progress: (Float) -> Unit): FloatArray {
        require(spec == PocketPresets.get(spec.id))
        return input(downloader.fetch(spec, active, progress))
    }
    @Synchronized
    fun readyIds(): Set<String> {
        val incoming = Regex("\\.incoming-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        root.listFiles().orEmpty().filter { incoming.matches(it.name) }.forEach { runCatching { erase(it) } }
        return setOf(PocketPresets.DEFAULT) + PocketPresets.additional.filter { isReady(it.id) }.map { it.id }
    }
    fun isReady(id: String): Boolean = if (id == PocketPresets.DEFAULT) true else runCatching {
        val item = metadata(PocketPresets.get(id))
        PocketVoiceStore.sha(File(directory(id), "cache.f32").readBytes()) == item.cacheSha256
    }.getOrDefault(false)
    private fun directory(id: String): File {
        PocketPresets.get(id)
        val base = root.canonicalFile
        val folder = File(base, id)
        require(folder.parentFile == base && folder.canonicalFile == folder) { "標準音声の保存先を確認できません" }
        return folder
    }
    private fun metadata(spec: PocketPreset): PocketPresetCache {
        require(spec.id != PocketPresets.DEFAULT && spec == PocketPresets.get(spec.id))
        val folder = directory(spec.id)
        val file = File(folder, "profile.json")
        require(file.canonicalFile.parentFile == folder && file.length() in 1..16384)
        val item = gson.fromJson(file.readText(Charsets.UTF_8), PocketPresetCache::class.java)
        require(item.schema == 1 && item.id == spec.id && item.revision == PocketPresets.REVISION && item.sourceSha256 == spec.sha256)
        require(item.model == PocketVoiceState.MODEL && item.encoder == PocketVoiceState.ENCODER && item.bos == PocketVoiceState.BOS)
        require(item.usedSamples in 24000..120000 && item.frames == (item.usedSamples + 1919) / 1920 + 1 && item.frames in 2..64)
        require(item.cacheSha256.matches(Regex("[a-f0-9]{64}")))
        val cache = File(folder, "cache.f32")
        require(cache.canonicalFile.parentFile == folder && cache.length() == item.frames * 1024L * 12 * 4)
        return item
    }
    fun read(id: String): PocketVoiceState {
        val item = metadata(PocketPresets.get(id))
        val data = File(directory(id), "cache.f32").readBytes()
        require(PocketVoiceStore.sha(data) == item.cacheSha256) { "標準音声のデータが壊れています。準備をやり直してください" }
        return PocketVoiceStore.decodeCache(data, item.frames)
    }
    @Synchronized
    fun save(spec: PocketPreset, voice: PocketVoiceState, usedSamples: Int, active: () -> Unit) {
        require(spec == PocketPresets.get(spec.id) && spec.id != PocketPresets.DEFAULT)
        voice.validate()
        require(usedSamples in 24000..120000 && voice.frames == (usedSamples + 1919) / 1920 + 1)
        check(root.isDirectory || root.mkdirs())
        val base = root.canonicalFile
        val stage = File(base, ".incoming-" + UUID.randomUUID())
        check(stage.mkdir())
        var committed = false
        try {
            val data = PocketVoiceStore.encodeCache(voice)
            val item = PocketPresetCache(spec.id, spec.sha256, voice.frames, usedSamples, PocketVoiceStore.sha(data))
            active()
            File(stage, "cache.f32").outputStream().use { it.write(data); it.fd.sync() }
            File(stage, "profile.json").outputStream().use { it.write(gson.toJson(item).toByteArray(Charsets.UTF_8)); it.fd.sync() }
            active()
            val destination = directory(spec.id)
            if (destination.exists()) {
                // Preserve a valid committed state. Only unusable owned files may be replaced.
                if (runCatching { read(spec.id) }.isSuccess) return
                check(erase(destination)) { "標準音声の保存先を確認できません" }
            }
            active()
            Files.move(stage.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            committed = true
        } finally { if (!committed) erase(stage) }
    }
    @Synchronized
    fun remove(id: String) {
        require(id != PocketPresets.DEFAULT)
        check(erase(directory(id))) { "標準音声の準備データを削除できませんでした" }
    }
    private fun erase(folder: File): Boolean {
        if (!folder.exists()) return true
        val base = root.canonicalFile
        if (folder.parentFile != base || folder.canonicalFile != folder || !folder.isDirectory) return false
        val files = folder.listFiles() ?: return false
        if (files.any { it.name !in setOf("cache.f32", "profile.json") || it.isDirectory || it.canonicalFile.parentFile != folder }) return false
        return files.all { !it.exists() || it.delete() } && folder.delete()
    }
    companion object {
        internal fun input(wav: ByteArray): FloatArray {
            require(wav.size <= 1000000)
            val chunk = VoicevoxWav.decode(wav)
            require(chunk.samples.size >= 24000) { "標準音声は1秒以上必要です" }
            return FloatArray(minOf(chunk.samples.size, 120000)) { chunk.samples[it] / 32768f }
        }
    }
}
