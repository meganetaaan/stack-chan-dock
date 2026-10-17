package jp.stackchan.localvoicepoc.pocket

import com.google.gson.JsonParser
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import jp.stackchan.localvoicepoc.sano.SanoAssets

/** Same v0.4.1 ZIP and manifest bytes, with bounded staging and whole-install rollback. */
object PocketAssets {
    fun directory(root: File, subdirectory: String = "pocket-ja-v1") = File(root, subdirectory)
    fun files(manifest: String): List<SanoAssets.Asset> {
        val list = JsonParser.parseString(manifest).asJsonObject.getAsJsonArray("files").map {
            val item = it.asJsonObject
            SanoAssets.Asset(item["name"].asString, item["bytes"].asLong, item["sha256"].asString)
        }
        require(list.isNotEmpty() && list.map { it.name }.toSet().size == list.size)
        require(list.all { it.name.matches(Regex("[A-Za-z0-9_.-]+")) && it.bytes > 0 && it.sha256.matches(Regex("[a-f0-9]{64}")) })
        val bytes = manifest.toByteArray(Charsets.UTF_8)
        return list + SanoAssets.Asset("manifest.json", bytes.size.toLong(), sha(bytes))
    }
    fun isVerified(root: File, manifest: String, active: () -> Unit = {}, subdirectory: String = "pocket-ja-v1"): Boolean =
        files(manifest).all { SanoAssets.inspect(File(directory(root, subdirectory), it.name), it, active).status == "verified" }

    fun verify(root: File, manifest: String, active: () -> Unit = {}, progress: (String) -> Unit = {}, subdirectory: String = "pocket-ja-v1") {
        for (asset in files(manifest)) {
            active(); progress("検証中: ${asset.name}")
            val status = SanoAssets.inspect(File(directory(root, subdirectory), asset.name), asset, active).status
            check(status == "verified") { "Pocket ${asset.name}: $status。評価済みモデルZIPを取り込んでください。" }
        }
    }

    fun import(root: File, input: InputStream, manifest: String, active: () -> Unit = {}, progress: (String, Float) -> Unit = { _, _ -> }, subdirectory: String = "pocket-ja-v1") {
        require(subdirectory.matches(Regex("[A-Za-z0-9_-]+")))
        val expected = files(manifest).associateBy { it.name }
        root.mkdirs()
        val stage = File(root, "pocket-incoming-${UUID.randomUUID()}")
        check(stage.mkdir()) { "Cannot create Pocket staging directory" }
        var committed = false
        try {
            val seen = mutableSetOf<String>(); var completedBytes = 0L
            val totalBytes = expected.values.sumOf { it.bytes }
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    active(); val entry = zip.nextEntry ?: break
                    val asset = expected[entry.name] ?: wrongArchive(entry.name, subdirectory)
                    require(!entry.isDirectory && seen.add(entry.name) && File(entry.name).name == entry.name) { "Invalid or duplicate Pocket ZIP entry" }
                    val hash = MessageDigest.getInstance("SHA-256"); var count = 0L; val buffer = ByteArray(128 * 1024)
                    FileOutputStream(File(stage, asset.name)).use { output ->
                        while (true) {
                            active(); val n = zip.read(buffer); if (n < 0) break
                            count += n; require(count <= asset.bytes) { "Pocket file exceeds pinned size" }
                            hash.update(buffer, 0, n); output.write(buffer, 0, n)
                            progress("取り込み中: ${asset.name}", (completedBytes + count).toFloat() / totalBytes)
                        }
                    }
                    require(count == asset.bytes && hex(hash.digest()) == asset.sha256) { "Pocket hash/size mismatch: ${asset.name}" }
                    completedBytes += count
                }
            }
            require(seen == expected.keys) { "Pocket ZIP is incomplete" }; active()
            val target = directory(root, subdirectory)
            val old = if (target.exists()) File(root, "pocket-previous-${UUID.randomUUID()}").also {
                check(target.renameTo(it)) { "Could not preserve previous Pocket files" }
            } else null
            try {
                Files.move(stage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                committed = true
            } catch (error: Throwable) {
                if (old != null) check(old.renameTo(target)) { "Pocket rollback failed: ${old.name}" }
                throw error
            }
            old?.deleteRecursively()
        } finally { if (!committed) stage.deleteRecursively() }
    }
    private fun wrongArchive(entry: String, subdirectory: String): Nothing {
        val filename = if (subdirectory == "pocket-encoder-v1") "pocket-tts-ja-reference-encoder-v1.zip" else "pocket-tts-ja-6l-female03-fp32-v1.zip"
        val apk = entry.startsWith("META-INF/com/android/") || entry == "AndroidManifest.xml" ||
            entry == "resources.arsc" || entry.matches(Regex("classes[0-9]*\\.dex"))
        if (apk) throw IllegalArgumentException("Androidアプリ（APK）が選ばれています。追加モデル「$filename」をZIPのまま選び直してください。")
        error("このZIPは指定のPocketモデル用ではありません。「$filename」をZIPのまま選び直してください。")
    }
    private fun sha(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}

data class PocketInstallation(val filesRoot: File, val manifest: String) {
    val directory get() = PocketAssets.directory(filesRoot)
}
