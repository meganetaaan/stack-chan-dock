package jp.stackchan.localvoicepoc.voicevox

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import jp.stackchan.localvoicepoc.ui.MainUiState
import jp.stackchan.localvoicepoc.ui.SetupStep
import jp.stackchan.localvoicepoc.ui.UsbConnectionStatus
import jp.stackchan.localvoicepoc.SdkBootstrap

class VoicevoxTest {
    private fun wav(rate: Int = 24000): ByteArray = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(40); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(4); putShort(-32768); putShort(1234)
    }.array()
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun pcmPreservesSignedSamples() {
        val decoded = VoicevoxWav.decode(wav())
        assertEquals(24000, decoded.sampleRate)
        assertArrayEquals(shortArrayOf(-32768, 1234), decoded.samples)
    }
    @Test fun rejectWrongRateTruncationAndOversizedChunks() {
        rejects { VoicevoxWav.decode(wav(48000)) }
        rejects { VoicevoxWav.decode(wav().copyOf(47)) }
        val huge = wav(); ByteBuffer.wrap(huge).order(ByteOrder.LITTLE_ENDIAN).putInt(40, -1)
        rejects { VoicevoxWav.decode(huge) }
    }
    @Test fun ignoresUnknownOddPaddedChunk() {
        val original = wav()
        val extended = original.copyOfRange(0, 12) + byteArrayOf(74,85,78,75,1,0,0,0,99,0) + original.copyOfRange(12, original.size)
        ByteBuffer.wrap(extended).order(ByteOrder.LITTLE_ENDIAN).putInt(4, extended.size - 8)
        assertArrayEquals(shortArrayOf(-32768,1234), VoicevoxWav.decode(extended).samples)
    }
    @Test fun delayedNativeResultIsSuppressedEvenWhenAnotherRequestStarts() = runBlocking {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val flow = VoicevoxPcmFlow { text ->
            if (text == "old") { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            wav()
        }
        val previous = async { flow.synthesize("old").toList() }
        // Yield the collector without blocking the runBlocking event loop.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { check(started.await(5, TimeUnit.SECONDS)) }
        flow.cancel()
        assertEquals(1, flow.synthesize("new").toList().size)
        release.countDown()
        assertTrue(previous.await().isEmpty())
    }
    @Test fun voicevoxReadinessDoesNotDependOnPiper() {
        val ready = MainUiState(ttsBackend = TtsBackend.VOICEVOX, voicevoxLoaded = true,
            modelsReady = true, sdkStatus = SdkBootstrap.Status.Ready, usbStatus = UsbConnectionStatus.READY)
        assertTrue(ready.pipelineReady)
        assertEquals(SetupStep.DEVICE, ready.setupStep)
        assertFalse(ready.copy(voicevoxLoaded=false, piperLoaded=true).pipelineReady)
        assertFalse(ready.copy(voicevoxBusy=true).canStartModelMutation)
    }
    private fun tarHeader(name: String, type: Char = '0'): ByteArray {
        val bytes = ByteArray(512)
        name.toByteArray().copyInto(bytes)
        "00000000000\u0000".toByteArray().copyInto(bytes,124)
        bytes[156] = type.code.toByte()
        (148..155).forEach { bytes[it] = 32 }
        val sum = bytes.sumOf { it.toInt() and 255 }
        (sum.toString(8).padStart(6,'0')+"\u0000 ").toByteArray().copyInto(bytes,148)
        return bytes
    }
    @Test fun rejectsTraversalLinksAndMissingDictionary() {
        val dir = java.nio.file.Files.createTempDirectory("voicevox-tar-test").toFile()
        try {
            for ((name,type) in listOf("open_jtalk_dic_utf_8-1.11/../escape" to '0', "open_jtalk_dic_utf_8-1.11/sys.dic" to '2')) {
                rejects { VoicevoxDictionary.extract(ByteArrayInputStream(tarHeader(name,type)),dir) }
            }
            rejects { VoicevoxDictionary.extract(ByteArrayInputStream(ByteArray(512)),dir) }
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }
}
