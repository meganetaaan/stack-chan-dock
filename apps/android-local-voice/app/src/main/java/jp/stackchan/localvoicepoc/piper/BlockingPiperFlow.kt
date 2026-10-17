package jp.stackchan.localvoicepoc.piper

import jp.stackchan.localvoicepoc.voicevox.SynthesisGeneration
import jp.stackchan.localvoicepoc.voicevox.TtsTiming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Synchronous upstream one-shot API; generation guards survive a later request after stop. */
internal class BlockingPiperFlow(private val generate: (String) -> SpeechSynthesizer.AudioChunk) {
    private val generation = SynthesisGeneration()
    fun cancel() = generation.cancel()
    fun synthesize(text: String, timing: TtsTiming? = null): kotlinx.coroutines.flow.Flow<SpeechSynthesizer.AudioChunk> {
        val request = generation.snapshot()
        return flow {
            val normalized = text.trim()
            if (normalized.isEmpty()) return@flow
            require(normalized.codePointCount(0,normalized.length) <= 1000 && '\u0000' !in normalized) {
                "Piper text is limited to 1000 characters without NUL per request"
            }
            currentCoroutineContext().ensureActive()
            checkCurrent(request)
            timing?.event("nativeWorkStart")
            val result = if (timing == null) generate(normalized) else timing.interval("nativeSynthesis") { generate(normalized) }
            currentCoroutineContext().ensureActive()
            checkCurrent(request)
            check(result.sampleRate > 0 && result.samples.isNotEmpty() && result.samples.size <= result.sampleRate * 120L) {
                "Piper returned empty, invalid or excessive PCM"
            }
            emit(result)
        }.flowOn(Dispatchers.IO).buffer(0)
    }
    private fun checkCurrent(request: Long) {
        if (!generation.isCurrent(request)) throw CancellationException("Piper request stopped or replaced")
    }
}
