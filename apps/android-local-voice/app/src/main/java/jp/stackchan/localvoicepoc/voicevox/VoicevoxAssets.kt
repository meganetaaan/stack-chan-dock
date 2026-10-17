package jp.stackchan.localvoicepoc.voicevox

import android.content.Context
import jp.stackchan.localvoicepoc.piper.PiperAssetDownloader
import jp.stackchan.localvoicepoc.piper.PiperDownloadArtifact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

internal fun File.sha256(): String = inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1024 * 1024)
    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

data class VoicevoxInstallation(val model: File, val dictionary: File) {
    fun isVerified(): Boolean = model.isFile && model.length() == VoicevoxAssets.MODEL.expectedBytes &&
        model.sha256() == VoicevoxAssets.MODEL.sha256 && VoicevoxDictionary.isVerified(dictionary)
}

object VoicevoxAssets {
    const val MODEL_TERMS = "https://github.com/VOICEVOX/voicevox_vvm/releases/download/0.16.4/TERMS.txt"
    const val RUNTIME_TERMS = "https://github.com/VOICEVOX/onnxruntime-builder/releases/tag/voicevox_onnxruntime-1.23.2"
    const val CHARACTER_TERMS = "https://zunko.jp/con_ongen_kiyaku.html"
    const val CREDIT = "VOICEVOX:ずんだもん"
    val MODEL = PiperDownloadArtifact("VOICEVOX ずんだもん", "0.vvm",
        "https://github.com/VOICEVOX/voicevox_vvm/releases/download/0.16.4/0.vvm", 58214379,
        "917e592cae92a02fb7668bd9e168df4e39c8ab2f5302c2be71906c70ec4e2df2")
    val DICTIONARY = PiperDownloadArtifact("Open JTalk dictionary", "open_jtalk_dic_utf_8-1.11.tar.gz",
        "https://downloads.sourceforge.net/project/open-jtalk/Dictionary/open_jtalk_dic-1.11/open_jtalk_dic_utf_8-1.11.tar.gz", 23643819,
        "33e9cd251bc41aa2bd7ca36f57abbf61eae3543ca25ca892ae345e394cb10549")
}

class VoicevoxAssetStore(context: Context) {
    private val directory = File(context.filesDir, "voicevox/0.16.4")
    private val downloader = PiperAssetDownloader(directory)
    private val preferences = context.getSharedPreferences("voicevox", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    fun current() = VoicevoxInstallation(File(directory, "0.vvm"), File(directory, "dictionary-1.11"))
    fun hasConfirmedTerms() = preferences.getBoolean("terms-0.16.4-ort1.23.2-zundamon", false)
    fun confirmTerms() { preferences.edit().putBoolean("terms-0.16.4-ort1.23.2-zundamon", true).apply() }
    suspend fun isReady() = withContext(Dispatchers.IO) { hasConfirmedTerms() && current().isVerified() }
    suspend fun prepare(progress: suspend (String, Float) -> Unit): VoicevoxInstallation = mutex.withLock {
        check(hasConfirmedTerms()) { "VOICEVOX terms confirmation is required" }
        withContext(Dispatchers.IO) {
            listOf(VoicevoxAssets.MODEL, VoicevoxAssets.DICTIONARY).forEach { artifact ->
                val file = File(directory, artifact.fileName)
                if (file.isFile && file.sha256() != artifact.sha256) {
                    File(directory, "${artifact.fileName}.sha256").delete()
                }
            }
        }
        downloader.prepare(VoicevoxAssets.MODEL) { progress("モデル ${it.stage}", it.fraction * 0.6f) }
        val archive = downloader.prepare(VoicevoxAssets.DICTIONARY) { progress("辞書 ${it.stage}", 0.6f + it.fraction * 0.2f) }
        withContext(Dispatchers.IO) {
            // Rehash cached artifacts as well; markers alone do not prove unchanged bytes.
            check(current().model.sha256() == VoicevoxAssets.MODEL.sha256)
            check(archive.sha256() == VoicevoxAssets.DICTIONARY.sha256)
            VoicevoxDictionary.prepare(archive, current().dictionary)
            check(current().isVerified())
        }
        progress("準備完了", 1f)
        current()
    }
}
