package jp.stackchan.localvoicepoc.pocket

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.core.content.edit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PocketAssetStore(private val context: Context) {
    private val manifest = context.assets.open("pocket-model-manifest.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
    private val preferences = context.getSharedPreferences("pocket-ja", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    fun current() = PocketInstallation(context.filesDir, manifest)
    fun hasConfirmedTerms() = preferences.getBoolean("ja6l-female03-fp32-v1-cc-by-nc-4", false)
    fun confirmTerms() { preferences.edit { putBoolean("ja6l-female03-fp32-v1-cc-by-nc-4", true) } }
    suspend fun isReady() = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        hasConfirmedTerms() && PocketAssets.isVerified(context.filesDir, manifest, active = { coroutine.ensureActive() })
    }
    suspend fun import(uri: Uri, progress: (String, Float) -> Unit): PocketInstallation = mutex.withLock {
        check(hasConfirmedTerms()) { "Confirm Pocket model conditions first" }
        withContext(Dispatchers.IO) {
            val required = PocketAssets.files(manifest).sumOf { it.bytes } + 512L * 1024 * 1024
            check(StatFs(context.filesDir.path).availableBytes >= required) { "Pocket ZIP取り込みにはモデル約449MB + 512MiBの空き容量が必要です" }
            val coroutine = currentCoroutineContext()
            requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot read Pocket ZIP" }.use {
                PocketAssets.import(context.filesDir, it, manifest, { coroutine.ensureActive() }, progress)
            }
        }
        current()
    }
    companion object {
        const val MODEL_URL = "https://huggingface.co/takuma104/pocket-tts-ja"
        const val LICENSE_URL = "https://creativecommons.org/licenses/by-nc/4.0/"
    }
}
