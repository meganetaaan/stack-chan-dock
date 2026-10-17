package jp.stackchan.localvoicepoc.pocket

import com.google.gson.Gson
import dev.irte.voice.PocketVoiceState
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class PocketVoiceProfile(val id: String, val recordingId: String, val name: String,
    val recordingSha256: String, val frames: Int, val usedSamples: Int, val cacheSha256: String,
    val model: String = PocketVoiceState.MODEL, val encoder: String = PocketVoiceState.ENCODER,
    val bos: String = PocketVoiceState.BOS,
    val schema: Int = 1)

/** App-private immutable profiles and a tiny atomic selection pointer. No network/export path. */
class PocketVoiceStore(private val root: File, private val presets: PocketPresetStore? = null) {
    private val gson = Gson()
    private fun validId(id: String) = id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
    fun list(): List<PocketVoiceProfile> = root.listFiles().orEmpty().filter { it.isDirectory && validId(it.name) }.mapNotNull { folder ->
        runCatching {
            val metadata = File(folder, "profile.json"); require(metadata.length() in 1..16384)
            val profile = gson.fromJson(metadata.readText(Charsets.UTF_8), PocketVoiceProfile::class.java)
            require(profile.id == folder.name && validId(profile.recordingId) && profile.name.isNotBlank() && profile.name.length <= 80)
            require(profile.schema == 1 && profile.model == PocketVoiceState.MODEL && profile.encoder == PocketVoiceState.ENCODER && profile.bos == PocketVoiceState.BOS)
            require(profile.usedSamples in 24000..120000 && profile.frames == (profile.usedSamples + 1919) / 1920 + 1)
            require(profile.frames in 2..64 && profile.cacheSha256.matches(Regex("[a-f0-9]{64}")) && profile.recordingSha256.matches(Regex("[a-f0-9]{64}")))
            require(File(folder, "cache.f32").length() == profile.frames * 1024L * 12 * 4)
            profile
        }.getOrNull()
    }.sortedBy { it.name }

    fun save(recordingId: String, name: String, sourceHash: String, usedSamples: Int, voice: PocketVoiceState, active: () -> Unit): PocketVoiceProfile {
        voice.validate(); require(validId(recordingId) && name.isNotBlank() && name.length <= 80)
        require(sourceHash.matches(Regex("[a-f0-9]{64}")) && usedSamples in 24000..120000)
        require(voice.frames == (usedSamples + 1919) / 1920 + 1)
        root.mkdirs(); val id = UUID.randomUUID().toString(); val stage = File(root, ".incoming-$id")
        check(stage.mkdir()); var committed = false
        try {
            val data = encodeCache(voice)
            val profile = PocketVoiceProfile(id, recordingId, name, sourceHash, voice.frames, usedSamples, sha(data))
            active(); File(stage, "cache.f32").outputStream().use { it.write(data); it.fd.sync() }
            File(stage, "profile.json").writeText(gson.toJson(profile), Charsets.UTF_8)
            active(); Files.move(stage.toPath(), File(root, id).toPath(), StandardCopyOption.ATOMIC_MOVE); committed = true
            return profile
        } finally { if (!committed) stage.deleteRecursively() }
    }
    fun read(profile: PocketVoiceProfile): PocketVoiceState {
        require(list().any { it == profile }) { "声のデータが見つかりません。作り直してください" }
        val data = File(File(root, profile.id), "cache.f32").readBytes()
        require(sha(data) == profile.cacheSha256) { "声のデータが壊れています。作り直してください" }
        return decodeCache(data, profile.frames)
    }
    fun selectedId(): String? = runCatching {
        val file = File(root, "selected.txt"); require(file.length() in 1..64)
        file.readText(Charsets.US_ASCII).takeIf { id -> validId(id) || PocketPresets.selected(id) != null }
    }.getOrNull()

    /** Called at the runtime's commit point, before its in-memory pointer is swapped. */
    fun select(id: String?, active: () -> Unit = {}) {
        require(id == null || (PocketPresets.selected(id)?.let { presets?.isReady(it.id) == true }
            ?: list().any { it.id == id }))
        root.mkdirs(); val stage = File(root, ".selection-${UUID.randomUUID()}")
        try {
            stage.outputStream().use { it.write((id ?: "female03").toByteArray(Charsets.US_ASCII)); it.fd.sync() }
            active(); Files.move(stage.toPath(), File(root, "selected.txt").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { stage.delete() }
    }
    companion object {
        internal fun encodeCache(voice: PocketVoiceState): ByteArray {
            voice.validate()
            val bytes = ByteBuffer.allocate(voice.frames * 1024 * 12 * 4).order(ByteOrder.LITTLE_ENDIAN)
            voice.caches.forEach { cache -> cache.forEach { bytes.putFloat(it) } }
            return bytes.array()
        }
        internal fun decodeCache(data: ByteArray, frames: Int): PocketVoiceState {
            require(frames in 2..64 && data.size == frames * 1024 * 12 * 4)
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            return PocketVoiceState(frames, List(12) { FloatArray(frames * 1024) { b.float } }).also { it.validate() }
        }
        fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
