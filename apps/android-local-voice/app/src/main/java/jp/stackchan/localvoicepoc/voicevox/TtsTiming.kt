package jp.stackchan.localvoicepoc.voicevox

import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Monotonic API intervals and playback observations; never claims acoustic onset. */
class TtsTiming(val label: String, private val clock: () -> Long = System::nanoTime) {
    val origin = clock()
    private val entries = linkedMapOf<String, String>()
    private val observationNanos = linkedMapOf<String,Long>()
    @Synchronized fun event(name: String) {
        val now = clock()
        observationNanos.putIfAbsent(name,now)
        entries.putIfAbsent(name,ms(now-origin))
    }
    @Synchronized fun observation(name: String, nanos: Long) {
        observationNanos.putIfAbsent(name,nanos)
        entries.putIfAbsent(name, ms(nanos - origin))
    }
    @Synchronized fun difference(name: String, from: String, to: String) {
        val start=observationNanos[from] ?: return
        val end=observationNanos[to] ?: return
        entries[name]=ms(end-start)
    }
    @Synchronized fun value(name: String, value: String) { entries[name] = value }
    inline fun <T> interval(name: String, block: () -> T): T {
        val start = now()
        return block().also { duration(name, now() - start) }
    }
    fun now() = clock()
    @Synchronized fun duration(name: String, nanos: Long) { entries[name] = ms(nanos) }
    @Synchronized fun report(): String = "$label\n" + entries.entries.joinToString("\n") { "${it.key}=${it.value}" }
    @Synchronized fun snapshot(): Map<String,String> = LinkedHashMap(entries)
    private fun ms(nanos: Long) = String.format(Locale.ROOT, "%.3f ms", nanos / 1_000_000.0)
}

/** One engine at a time; reload never runs until the previous engine is released. */
suspend fun compareTtsThreads(
    reload: suspend (Int) -> Unit,
    play: suspend (Int, Int) -> Unit,
    release: suspend () -> Unit,
) {
    for (threads in listOf(1, 2, 4, 0)) {
        try {
            currentCoroutineContext().ensureActive()
            reload(threads)
            repeat(3) { iteration ->
                currentCoroutineContext().ensureActive()
                play(threads, iteration)
            }
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release() }
        }
    }
}
