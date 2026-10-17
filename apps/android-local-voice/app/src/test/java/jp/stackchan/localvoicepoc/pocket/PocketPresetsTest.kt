package jp.stackchan.localvoicepoc.pocket

import dev.irte.voice.PocketVoiceState
import jp.stackchan.localvoicepoc.audio.PcmWav
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PocketPresetsTest {
    private class Response(url: URL, private val status: Int, private val data: ByteArray,
        private val headers: Map<String,String> = emptyMap()): HttpURLConnection(url) {
        var disconnected = false
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() {}
        override fun getResponseCode() = status
        override fun getHeaderField(name: String) = headers[name]
        override fun getInputStream() = ByteArrayInputStream(data)
    }
    private fun voice(value: Float = .2f) = PocketVoiceState(14, List(12) { FloatArray(14 * 1024) { value } })
    private fun fixture(test: (File, PocketPresetStore) -> Unit) {
        val root = Files.createTempDirectory("preset-test-").toFile()
        check(root.canonicalFile.parentFile == File(requireNotNull(System.getProperty("java.io.tmpdir"))).canonicalFile)
        try { test(root, PocketPresetStore(File(root,"presets"))) } finally { root.deleteRecursively() }
    }
    @Test fun catalogCoversTenOfficialVoicesWithoutChangingTheLegacyDefault() {
        val expected=(1..5).map { "female_0" + it } + (1..5).map { "male_0" + it }
        assertEquals(expected, PocketPresets.all.map { it.id })
        assertEquals(10, PocketPresets.all.map { it.sha256 }.distinct().size)
        assertEquals(9, PocketPresets.additional.size)
        assertNull(PocketPresets.selection("female_03"))
        assertEquals("female_03", PocketPresets.selected(null)!!.id)
        assertNull(PocketPresets.selected("preset:female_03"))
        for(spec in PocketPresets.additional) {
            assertEquals(spec, PocketPresets.selected(PocketPresets.selection(spec.id)))
            assertTrue(spec.url.contains("/" + PocketPresets.REVISION + "/voices/" + spec.id + ".wav"))
        }
        assertTrue(runCatching { PocketPresets.get("../outside") }.isFailure)
    }
    @Test fun anonymousPinnedDownloadFollowsOnlyOfficialHttpsRedirectsAndChecksEveryByte() {
        val data=ByteArray(50000) { it.toByte() }
        val spec=PocketPresets.get("male_01").copy(bytes=data.size,sha256=PocketVoiceStore.sha(data))
        val responses=mutableListOf<Response>()
        val downloader=PocketPresetDownloader { url ->
            Response(url,if(responses.isEmpty())302 else 200,data,
                if(responses.isEmpty())mapOf("Location" to "https://cdn-lfs.hf.co/model/audio") else mapOf("Content-Length" to data.size.toString()))
                .also {responses.add(it)}
        }
        downloader.reset()
        assertArrayEquals(data,downloader.fetch(spec,{},{}))
        assertEquals(2,responses.size);assertTrue(responses.all {it.disconnected})
        assertTrue(responses.all {it.getRequestProperty("Authorization")==null && it.getRequestProperty("Cookie")==null})
        assertTrue(runCatching { PocketPresetDownloader.verify(spec,data.copyOf(data.size-1)) }.isFailure)
        assertTrue(runCatching { PocketPresetDownloader.verify(spec,data.copyOf().also {it[0]=99}) }.isFailure)
    }
    @Test fun redirectDowngradeExternalHostsAndCredentialsAreRejected() {
        for(url in listOf("http://huggingface.co/a","https://attacker.example/a","https://user:secret@huggingface.co/a","https://huggingface.co:444/a")) {
            assertFalse(PocketPresetDownloader.allowed(URL(url)))
        }
        val response=Response(URL(PocketPresets.all.first().url),302,byteArrayOf(),
            mapOf("Location" to "http://attacker.example/wav"))
        var connections=0
        val downloader=PocketPresetDownloader { connections++;response }
        downloader.reset()
        assertTrue(runCatching { downloader.fetch(PocketPresets.all.first(),{},{}) }.isFailure)
        assertEquals(1,connections);assertTrue(response.disconnected)
    }
    @Test fun cancelledShortOversizedOrDeniedTransfersNeverReturnTrustedData() {
        val data=ByteArray(50000)
        val spec=PocketPresets.get("male_01").copy(bytes=data.size,sha256=PocketVoiceStore.sha(data))
        lateinit var response:Response
        val downloader=PocketPresetDownloader {url->Response(url,200,data).also {response=it}}
        downloader.reset()
        assertTrue(runCatching {downloader.fetch(spec,{},{downloader.cancel()})}.exceptionOrNull() is CancellationException)
        assertTrue(response.disconnected)
        downloader.reset()
        assertArrayEquals(data,downloader.fetch(spec,{},{}))
        for((status,body) in listOf(200 to data.copyOf(49999),200 to data.copyOf(50001),403 to data)) {
            val d=PocketPresetDownloader {url->Response(url,status,body)}
            d.reset();assertTrue(runCatching {d.fetch(spec,{},{})}.isFailure)
        }
    }
    @Test fun wavUsesOnlyTheFirstFiveSecondsAndRejectsIncompatibleInput() {
        val pcm=ByteArray(24000*2*6)
        for(i in 0 until 24000*6) { val v=if(i<120000)8192 else -8192;pcm[i*2]=v.toByte();pcm[i*2+1]=(v shr 8).toByte() }
        val wav=PcmWav.encode(pcm,24000);val original=wav.copyOf()
        val input=PocketPresetStore.input(wav)
        assertEquals(120000,input.size);assertTrue(input.all {it==.25f});assertArrayEquals(original,wav)
        for(bad in listOf(wav.copyOf(wav.size-1),PcmWav.encode(ByteArray(47998),24000),PcmWav.encode(pcm,22050),
            wav.copyOf().also {it[22]=2})) assertTrue(runCatching {PocketPresetStore.input(bad)}.isFailure)
    }
    @Test fun allNineStatesSurviveRestartWithoutSavingTheDownloadedWav() = fixture {root,store->
        for((index,spec) in PocketPresets.additional.withIndex()) store.save(spec,voice(index/10f),24000,{})
        assertEquals(10,store.readyIds().size)
        val restored=PocketPresetStore(File(root,"presets"))
        for((index,spec) in PocketPresets.additional.withIndex()) {
            assertTrue(restored.isReady(spec.id));assertEquals(index/10f,restored.read(spec.id).caches[0][0],0f)
        }
        assertFalse(File(root,"presets").walkTopDown().any {it.extension=="wav"})
        val choices=PocketVoiceStore(File(root,"voices"),restored)
        choices.select(PocketPresets.selection("male_04"))
        assertEquals("preset:male_04",PocketVoiceStore(File(root,"voices"),restored).selectedId())
    }
    @Test fun corruptedOrWrongModelSourceAndTruncatedStatesAreRejectedAndCanBeRebuilt() = fixture {root,store->
        val spec=PocketPresets.get("female_01");store.save(spec,voice(),24000,{})
        val cache=File(root,"presets/female_01/cache.f32");val original=cache.readBytes()
        cache.writeBytes(original.copyOf().also {it[0]=1})
        assertFalse(store.isReady(spec.id));assertTrue(runCatching {store.read(spec.id)}.isFailure)
        store.save(spec,voice(.3f),24000,{});assertTrue(store.isReady(spec.id))
        val meta=File(root,"presets/female_01/profile.json");val good=meta.readText()
        for(bad in listOf(good.replace(PocketVoiceState.MODEL,"0".repeat(64)),good.replace(spec.sha256,"1".repeat(64)),
            good.replace(PocketPresets.REVISION,"2".repeat(40)))) {
            meta.writeText(bad);assertFalse(store.isReady(spec.id));assertTrue(runCatching {store.read(spec.id)}.isFailure)
        }
        meta.writeText(good);cache.writeBytes(byteArrayOf(1));assertFalse(store.isReady(spec.id))
    }
    @Test fun failedSaveKeepsCommittedStateAndRefusesUnknownOrEscapingPaths() = fixture {root,store->
        val spec=PocketPresets.get("male_02");store.save(spec,voice(),24000,{})
        val original=File(root,"presets/male_02/cache.f32").readBytes()
        assertTrue(runCatching {store.save(spec,voice(.8f),24000,{throw CancellationException("stop")})}.isFailure)
        assertArrayEquals(original,File(root,"presets/male_02/cache.f32").readBytes())
        assertFalse(File(root,"presets").listFiles()!!.any {it.name.startsWith(".incoming")})
        val other=PocketPresets.get("male_03");val folder=File(root,"presets/male_03");folder.mkdir()
        File(folder,"unknown.txt").writeText("keep")
        assertTrue(runCatching {store.save(other,voice(),24000,{})}.isFailure)
        assertEquals("keep",File(folder,"unknown.txt").readText())
        assertTrue(runCatching {store.read("../outside")}.isFailure)
        assertTrue(runCatching {store.remove(PocketPresets.DEFAULT)}.isFailure)
    }
    @Test fun removingOnePresetLeavesOtherPresetsAndUserProfilesUntouched() = fixture {root,store->
        for(spec in PocketPresets.additional.take(2)) store.save(spec,voice(),24000,{})
        val users=PocketVoiceStore(File(root,"voices"),store)
        val profile=users.save(java.util.UUID.randomUUID().toString(),"本人の声","a".repeat(64),24000,voice(),{})
        users.select(profile.id);store.remove("female_01")
        assertFalse(store.isReady("female_01"));assertTrue(store.isReady("female_02"))
        assertEquals(profile.id,users.selectedId());assertEquals(profile,users.list().single())
        assertNotNull(users.read(profile))
    }
    @Test fun reopeningCleansOnlyOwnedInterruptedStagesAndRetainsCompletedPresets() = fixture {root,store->
        store.save(PocketPresets.get("male_01"),voice(),24000,{})
        val interrupted=File(root,"presets/.incoming-" + java.util.UUID.randomUUID())
        check(interrupted.mkdir());File(interrupted,"cache.f32").writeBytes(byteArrayOf(1))
        val unrelated=File(root,"presets/.incoming-not-an-id")
        check(unrelated.mkdir());File(unrelated,"keep.txt").writeText("keep")
        val restored=PocketPresetStore(File(root,"presets"))
        assertTrue("male_01" in restored.readyIds());assertFalse(interrupted.exists())
        assertEquals("keep",File(unrelated,"keep.txt").readText())
    }
}
