package jp.stackchan.localvoicepoc.voicevox

import jp.stackchan.localvoicepoc.piper.SpeechSynthesizer

/** Decode RIFF chunks, including padding/unknown chunks; reject incompatible or truncated PCM. */
object VoicevoxWav {
    fun decode(wav: ByteArray): SpeechSynthesizer.AudioChunk {
        fun text(offset: Int, size: Int) = String(wav, offset, size, Charsets.US_ASCII)
        fun u16(offset: Int) = (wav[offset].toInt() and 255) or ((wav[offset + 1].toInt() and 255) shl 8)
        fun u32(offset: Int): Long = (0..3).fold(0L) { v, i -> v or ((wav[offset + i].toLong() and 255) shl (i * 8)) }
        require(wav.size >= 12 && text(0, 4) == "RIFF" && text(8, 4) == "WAVE") { "Invalid WAV header" }
        val end = u32(4) + 8
        require(end == wav.size.toLong()) { "Truncated or trailing WAV bytes" }
        var offset = 12
        var format = false
        var samples: ShortArray? = null
        while (offset < end) {
            require(offset.toLong() + 8 <= end) { "Truncated WAV chunk header" }
            val name = text(offset, 4)
            val size = u32(offset + 4)
            val payload = offset + 8
            val next = payload.toLong() + size + (size and 1)
            require(next <= end) { "Truncated WAV chunk" }
            when (name) {
                "fmt " -> {
                    require(!format && size >= 16) { "Invalid WAV fmt" }
                    require(u16(payload) == 1 && u16(payload + 2) == 1 &&
                        u32(payload + 4) == 24000L && u32(payload + 8) == 48000L &&
                        u16(payload + 12) == 2 && u16(payload + 14) == 16) { "VOICEVOX requires 24 kHz mono PCM16" }
                    format = true
                }
                "data" -> {
                    require(samples == null && size > 0 && size % 2 == 0L) { "Invalid WAV data" }
                    samples = ShortArray((size / 2).toInt()) { u16(payload + it * 2).toShort() }
                }
            }
            offset = next.toInt()
        }
        require(format && samples != null) { "WAV fmt/data missing" }
        return SpeechSynthesizer.AudioChunk(24000, samples)
    }
}
