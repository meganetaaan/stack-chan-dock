package jp.stackchan.localvoicepoc.sano

import android.content.Context
import android.net.Uri
import jp.stackchan.localvoicepoc.piper.PiperAssetDownloader
import jp.stackchan.localvoicepoc.piper.PiperDownloadArtifact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class SanoAssetStore(private val context: Context) {
    private val directory = File(context.filesDir, "sano/v1.2.0")
    private val downloader = PiperAssetDownloader(directory)
    private val preferences = context.getSharedPreferences("sano", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    fun current() = SanoInstallation(File(directory, SanoAssets.weights.name), File(directory, SanoAssets.dictionary.name))
    fun hasConfirmedTerms() = preferences.getBoolean(TERMS_KEY, false)
    fun confirmTerms() { preferences.edit().putBoolean(TERMS_KEY, true).apply() }
    suspend fun isReady() = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        hasConfirmedTerms() && current().isVerified { coroutine.ensureActive() }
    }
    suspend fun prepare(progress: suspend (String, Float) -> Unit): SanoInstallation = mutex.withLock {
        check(hasConfirmedTerms()) { "Confirm sanoTTS model terms before downloading" }
        withContext(Dispatchers.IO) {
            val coroutine = currentCoroutineContext()
            for ((index, asset) in SanoAssets.all.withIndex()) {
                // Rehash cached bytes. A marker must never authorize a modified model or dictionary.
                if (SanoAssets.inspect(File(directory, asset.name), asset) { coroutine.ensureActive() }.status != "verified") {
                    File(directory, "${asset.name}.sha256").delete()
                }
                val artifact = PiperDownloadArtifact(asset.name, asset.name, asset.url, asset.bytes, asset.sha256)
                downloader.prepare(artifact) { progress(asset.name + " " + it.stage, (index + it.fraction) / 2f) }
            }
            check(current().isVerified { coroutine.ensureActive() }) { "sanoTTS asset verification failed" }
        }
        current()
    }
    suspend fun import(uri: Uri): SanoInstallation = mutex.withLock {
        check(hasConfirmedTerms()) { "Confirm sanoTTS model terms before importing" }
        withContext(Dispatchers.IO) {
            val coroutine = currentCoroutineContext()
            val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot read selected sanoTTS file" }
            SanoAssets.install(input, directory, active = { coroutine.ensureActive() })
        }
        current()
    }
    companion object {
        const val SOURCE_REVISION = "3eb73a91acdb8d4bc0cc10c3bc4e670555ce20bc"
        const val MODEL_TERMS = "https://github.com/ayutaz/sanoTTS-jp/blob/3eb73a91acdb8d4bc0cc10c3bc4e670555ce20bc/LICENSE-MODEL.md"
        const val NOTICE = "https://github.com/ayutaz/sanoTTS-jp/blob/3eb73a91acdb8d4bc0cc10c3bc4e670555ce20bc/NOTICE.md"
        private const val TERMS_KEY = "v4-model-1.0-3eb73a91"
    }
}
