package jp.stackchan.localvoicepoc.pocket

import android.content.Context
import android.net.Uri
import android.os.StatFs
import kotlinx.coroutines.*

/** Optional verified add-on; the original synthesis ZIP is never replaced. */
class PocketEncoderStore(private val context: Context) {
    private val manifest = context.assets.open("pocket-encoder-manifest.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
    val directory get() = PocketAssets.directory(context.filesDir, "pocket-encoder-v1")
    fun isReady(active: () -> Unit = {}) = PocketAssets.isVerified(context.filesDir, manifest, active, "pocket-encoder-v1")
    fun verify(active: () -> Unit) {
        check(isReady(active)) { "声の変換用追加モデルを取り込んでください" }
    }
    suspend fun import(uri: Uri, active: () -> Unit) = withContext(Dispatchers.IO) {
        check(StatFs(context.filesDir.path).availableBytes >= PocketAssets.files(manifest).sumOf { it.bytes } + 64L * 1024 * 1024) { "追加モデルを保存する空き容量が足りません" }
        requireNotNull(context.contentResolver.openInputStream(uri)).use {
            PocketAssets.import(context.filesDir, it, manifest, active, subdirectory = "pocket-encoder-v1")
        }
    }
}
