package jp.stackchan.localvoicepoc.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Canonical mono PCM16 WAV used by explicit recordings and existing diagnostics. */
object PcmWav {
    fun encode(pcm: ByteArray, sampleRate: Int): ByteArray {
        require(sampleRate in 8000..192000 && pcm.size % 2 == 0)
        val output=ByteBuffer.allocate(44+pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        output.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36+pcm.size).put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        output.putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate*2).putShort(2).putShort(16)
        output.put("data".toByteArray(Charsets.US_ASCII)).putInt(pcm.size).put(pcm)
        return output.array()
    }
    fun decode(wav: ByteArray, expectedRate: Int, expectedSamples: Int): ByteArray {
        require(wav.size==44+expectedSamples*2 && wav.copyOfRange(0,4).toString(Charsets.US_ASCII)=="RIFF")
        val header=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        require(header.getInt(4)==wav.size-8 && wav.copyOfRange(8,16).toString(Charsets.US_ASCII)=="WAVEfmt ")
        require(header.getInt(16)==16 && header.getShort(20)==1.toShort() && header.getShort(22)==1.toShort())
        require(header.getInt(24)==expectedRate && header.getInt(28)==expectedRate*2 && header.getShort(32)==2.toShort() && header.getShort(34)==16.toShort())
        require(wav.copyOfRange(36,40).toString(Charsets.US_ASCII)=="data" && header.getInt(40)==expectedSamples*2)
        return wav.copyOfRange(44,wav.size)
    }
}
