package dev.irte.voice

/** The resident CPU runtime contract used by the existing SpeechSynthesizer adapter. */
interface PocketEngine : AutoCloseable {
    fun prepareRequest(active: () -> Unit)
    fun generate(source: String, receive: (FloatArray) -> Boolean)
    fun cancel()
    fun encodeVoice(pcm: FloatArray, encoderDirectory: java.io.File): PocketVoiceState = error("Reference voice encoding is unavailable")
    fun selectVoice(voice: PocketVoiceState?, commit: () -> Unit) {
        require(voice == null); commit()
    }
}
