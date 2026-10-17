package jp.stackchan.localvoicepoc.voicevox

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** A later request cannot revive an earlier synchronous synthesis after stop. */
class SynthesisGeneration {
    private val generation = AtomicLong()
    fun snapshot(): Long = generation.get()
    fun cancel() { generation.incrementAndGet() }
    fun isCurrent(value: Long): Boolean = value == generation.get()
}

internal class VoicevoxPcmFlow(private val synthesizeWav: (String) -> ByteArray) {
    private val generation = SynthesisGeneration()
    fun cancel() = generation.cancel()
    fun synthesize(text: String) = flow {
        if (text.isBlank()) return@flow
        val request = generation.snapshot()
        currentCoroutineContext().ensureActive()
        val wav = synthesizeWav(text.trim())
        currentCoroutineContext().ensureActive()
        if (generation.isCurrent(request)) emit(VoicevoxWav.decode(wav))
    }.flowOn(Dispatchers.Default)
}
