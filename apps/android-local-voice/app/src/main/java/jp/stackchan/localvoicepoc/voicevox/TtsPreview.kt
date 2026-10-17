package jp.stackchan.localvoicepoc.voicevox

import jp.stackchan.localvoicepoc.audio.PcmAudioSink
import jp.stackchan.localvoicepoc.piper.SpeechSynthesizer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

const val TTS_PREVIEW_TEXT = "こんにちは。スタックちゃんです。"

/** Play through the existing phone sink without an LLM, microphone or USB session. */
suspend fun playTtsPreview(synthesizer: SpeechSynthesizer, sink: PcmAudioSink, timing: TtsTiming? = null) {
    var started = false
    var completed = false
    var frames = 0L
    try {
        timing?.event("generationRequested")
        synthesizer.synthesize(TTS_PREVIEW_TEXT).collect { chunk ->
            currentCoroutineContext().ensureActive()
            if (!started) {
                timing?.event("firstPcm")
                timing?.value("sampleRate",chunk.sampleRate.toString())
                sink.begin(chunk.sampleRate)
                started = true
            }
            frames += chunk.samples.size
            sink.write(chunk.samples)
        }
        check(started) { "TTS returned no audio for the preview" }
        timing?.event("generationComplete")
        timing?.value("audioFrames",frames.toString())
        sink.finish()
        timing?.event("playbackComplete")
        completed = true
    } catch (error: Throwable) {
        timing?.event("failed")
        timing?.value("errorType",error::class.java.simpleName)
        throw error
    } finally {
        timing?.difference("generationToFirstPcm","generationRequested","firstPcm")
        timing?.difference("generationToPlayCall","generationRequested","playCall")
        timing?.difference("generationToObservedPlaybackHead","generationRequested","firstPositivePlaybackHeadObserved")
        timing?.difference("generationToEstimatedFrameZero","generationRequested","frameZeroOutputEstimate")
        timing?.difference("generationToCompletion","generationRequested","playbackComplete")
        if (!completed) withContext(NonCancellable) { sink.abort() }
    }
}
