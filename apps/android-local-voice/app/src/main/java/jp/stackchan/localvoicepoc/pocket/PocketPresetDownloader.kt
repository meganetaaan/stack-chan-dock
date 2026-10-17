package jp.stackchan.localvoicepoc.pocket

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException

/** Bounded, anonymous official downloads. Never logs a redirect URL or sends recordings. */
class PocketPresetDownloader(private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    private val lock = Any()
    private var connection: HttpURLConnection? = null
    private var stopped = false
    fun reset() = synchronized(lock) { check(connection == null); stopped = false }
    fun cancel() = synchronized(lock) { stopped = true; connection?.disconnect() }
    private fun checkActive(active: () -> Unit) {
        active()
        synchronized(lock) { if (stopped) throw CancellationException("Download stopped") }
    }
    fun fetch(spec: PocketPreset, active: () -> Unit, progress: (Float) -> Unit): ByteArray {
        var url = URL(spec.url)
        try {
            repeat(6) { redirects ->
                checkActive(active)
                require(allowed(url)) { "標準音声の取得先を確認できません" }
                val current = connect(url).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15000
                    readTimeout = 15000
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("User-Agent", "stackchan-pocket-presets/1")
                }
                synchronized(lock) {
                    if (stopped) { current.disconnect(); throw CancellationException("Download stopped") }
                    connection = current
                }
                try {
                    val code = current.responseCode
                    if (code in setOf(301, 302, 303, 307, 308)) {
                        val location = current.getHeaderField("Location")
                        check(redirects < 5 && !location.isNullOrBlank()) { "標準音声の取得先を解決できません" }
                        url = URL(url, location)
                    } else {
                        check(code == 200) { "公式音声を取得できませんでした（HTTP " + code + "）。配布元の利用条件と接続を確認してください" }
                        val length = current.getHeaderFieldLong("Content-Length", -1)
                        check(length == -1L || length == spec.bytes.toLong()) { "標準音声の配布サイズが変わっています" }
                        val output = ByteArrayOutputStream(spec.bytes)
                        current.inputStream.use { input ->
                            val buffer = ByteArray(16384)
                            while (true) {
                                checkActive(active)
                                val count = input.read(buffer)
                                if (count < 0) break
                                check(output.size().toLong() + count <= spec.bytes) { "標準音声が指定サイズを超えています" }
                                output.write(buffer, 0, count)
                                progress(output.size().toFloat() / spec.bytes)
                            }
                        }
                        checkActive(active)
                        return verify(spec, output.toByteArray())
                    }
                } finally {
                    current.disconnect()
                    synchronized(lock) { if (connection === current) connection = null }
                }
            }
            error("標準音声の取得先を解決できません")
        } catch (error: Exception) {
            active()
            if (error is IllegalStateException || error is IllegalArgumentException || error is CancellationException) throw error
            // Network exceptions may contain an authenticated CDN redirect. Keep it out of UI/logs.
            throw IllegalStateException("標準音声を取得できませんでした。接続を確認して再試行してください")
        }
    }
    companion object {
        internal fun allowed(url: URL): Boolean = url.protocol == "https" && url.userInfo == null &&
            (url.port == -1 || url.port == 443) && (url.host == "huggingface.co" || url.host.endsWith(".huggingface.co") || url.host.endsWith(".hf.co"))
        internal fun verify(spec: PocketPreset, data: ByteArray): ByteArray {
            require(data.size == spec.bytes && PocketVoiceStore.sha(data) == spec.sha256) { "標準音声のサイズ・SHA-256が一致しません" }
            return data
        }
    }
}
