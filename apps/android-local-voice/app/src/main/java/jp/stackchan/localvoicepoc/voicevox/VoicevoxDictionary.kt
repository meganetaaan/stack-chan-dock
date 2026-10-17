package jp.stackchan.localvoicepoc.voicevox

import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** Fixed official archive only. No links, traversal, duplicate files, or unbounded expansion. */
object VoicevoxDictionary {
    val hashes = mapOf(
        "char.bin" to "888ee94c5a8a7a26d24ab3f1b7155441351954fd51ea06b4a2f78bd742492b2f",
        "COPYING" to "f4eca42ebd930e2c6e57fca58319d989bebcd1510cb7714b149c50f5425135ea",
        "left-id.def" to "db1adac8a7f9e5854cd82ea044c85115249206c8181b9d88cf92ae2ee5e87b84",
        "matrix.bin" to "62fd16b4f64c851d5dc352ef0d5740c5fc83ddc7c203b2b0b1fc5271969a14ce",
        "pos-id.def" to "3460aa742053085af47cdfc889a1e0e6f557e89b406e501ba81c9ccc286de0c7",
        "rewrite.def" to "7f7c8dfbfe24092e8a149a9b6e0a3a7f1c2cf37d6c3dc29d1cccc6c004da9c1c",
        "right-id.def" to "db1adac8a7f9e5854cd82ea044c85115249206c8181b9d88cf92ae2ee5e87b84",
        "sys.dic" to "ca57d9029691a70a5dfb99afc2844180256161d7130da65b1a867510e129b9a6",
        "unk.dic" to "ce97851ecda075914fa3ffe7294a1ab34ee4f6d56ba6bf9197d74143b5dffbfe",
    )
    fun isVerified(directory: File): Boolean = hashes.all { (name, hash) ->
        val file = File(directory, name)
        file.isFile && file.sha256() == hash
    }
    fun prepare(archive: File, destination: File) {
        val staging = File(destination.parentFile, "${destination.name}.staging")
        val backup = File(destination.parentFile, "${destination.name}.backup")
        if (!destination.exists() && backup.exists()) check(backup.renameTo(destination))
        if (isVerified(destination)) { staging.deleteRecursively(); backup.deleteRecursively(); return }
        staging.deleteRecursively()
        check(staging.mkdirs())
        try {
            GZIPInputStream(archive.inputStream()).use { extract(it, staging) }
            check(isVerified(staging)) { "Open JTalk dictionary hash mismatch" }
            backup.deleteRecursively()
            if (destination.exists()) check(destination.renameTo(backup))
            if (!staging.renameTo(destination)) {
                if (backup.exists()) backup.renameTo(destination)
                error("Could not install Open JTalk dictionary")
            }
            backup.deleteRecursively()
        } finally { staging.deleteRecursively() }
    }
    internal fun extract(input: InputStream, destination: File) {
        val seen = mutableSetOf<String>()
        var total = 0L
        val header = ByteArray(512)
        fun field(offset: Int, size: Int) = String(header, offset, size, Charsets.US_ASCII).substringBefore('\u0000').trim()
        while (true) {
            readFully(input, header)
            if (header.all { it == 0.toByte() }) break
            val checksum = header.indices.sumOf { if (it in 148..155) 32 else header[it].toInt() and 255 }
            require(field(148, 8).toInt(8) == checksum) { "Invalid tar checksum" }
            require(field(345, 155).isEmpty()) { "Unexpected tar prefix" }
            val name = field(0, 100)
            val type = header[156].toInt()
            val size = field(124, 12).toLong(8)
            require(size in 0..(120L * 1024 * 1024)) { "Invalid tar size" }
            total += size
            require(total <= 160L * 1024 * 1024) { "Dictionary expansion too large" }
            if (type == '5'.code) {
                require(name == "open_jtalk_dic_utf_8-1.11/" && size == 0L)
                continue
            }
            require(type == 0 || type == '0'.code) { "Tar links and special files are forbidden" }
            val basename = name.removePrefix("open_jtalk_dic_utf_8-1.11/")
            require(name.startsWith("open_jtalk_dic_utf_8-1.11/") && basename in hashes && seen.add(basename)) { "Unexpected or duplicate dictionary path" }
            File(destination, basename).outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var remaining = size
                while (remaining > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    require(n > 0) { "Truncated dictionary" }
                    output.write(buffer, 0, n); remaining -= n
                }
            }
            readFully(input, ByteArray(((512 - size % 512) % 512).toInt()))
        }
        require(seen == hashes.keys) { "Dictionary files missing" }
    }
    private fun readFully(input: InputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val n = input.read(bytes, offset, bytes.size - offset)
            require(n > 0) { "Truncated tar" }
            offset += n
        }
    }
}
