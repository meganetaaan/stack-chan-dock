package jp.stackchan.localvoicepoc.pocket

import dev.irte.voice.PocketVoiceState
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PocketVoiceStoreTest {
    private fun fixture(test: (File, PocketVoiceStore) -> Unit) {
        val root = Files.createTempDirectory("pocket-voice-").toFile()
        try { test(root, PocketVoiceStore(root)) } finally { root.deleteRecursively() }
    }
    private fun state() = PocketVoiceState(14, List(12) { layer -> FloatArray(14 * 1024) { layer / 100f } })
    private fun save(store: PocketVoiceStore, active: () -> Unit = {}) = store.save(UUID.randomUUID().toString(), "自分の声", "a".repeat(64), 24000, state(), active)
    @Test fun compactStateRoundTripsAndSelectionSurvivesRestart() = fixture { root, store ->
        val profile = save(store); assertNull(store.selectedId())
        val decoded = store.read(profile); assertEquals(14, decoded.frames)
        state().caches.zip(decoded.caches).forEach { (a,b) -> assertArrayEquals(a,b,0f) }
        assertEquals(14L * 1024 * 12 * 4, File(root,"${profile.id}/cache.f32").length())
        store.select(profile.id); assertEquals(profile.id, PocketVoiceStore(root).selectedId())
        store.select(null); assertNull(PocketVoiceStore(root).selectedId()); assertEquals(1, store.list().size)
    }
    @Test fun cancellationNeverReplacesCurrentSelectionOrPublishesPartialProfile() = fixture { root, store ->
        val previous = save(store); store.select(previous.id)
        assertTrue(runCatching { save(store) { throw CancellationException("stop") } }.exceptionOrNull() is CancellationException)
        assertEquals(listOf(previous.id), store.list().map { it.id })
        assertEquals(previous.id, store.selectedId())
        assertTrue(runCatching { store.select(null) { throw CancellationException("stop") } }.isFailure)
        assertEquals(previous.id, store.selectedId())
        assertFalse(root.listFiles()!!.any { it.name.startsWith('.') })
    }
    @Test fun corruptedTruncatedAndIncompatibleCachesCannotBeLoaded() = fixture { root, store ->
        val profile = save(store); val file = File(root,"${profile.id}/cache.f32")
        val original = file.readBytes(); val changed = original.copyOf().also { it[0] = 1 }; file.writeBytes(changed)
        assertTrue(runCatching { store.read(profile) }.isFailure)
        file.writeBytes(original.copyOf(10)); assertTrue(store.list().isEmpty())
        file.writeBytes(original)
        val metadata=File(root,"${profile.id}/profile.json")
        metadata.writeText(metadata.readText().replace(PocketVoiceState.MODEL,"b".repeat(64)))
        assertTrue(store.list().isEmpty()); assertTrue(runCatching { store.select(profile.id) }.isFailure)
    }
    @Test fun invalidStateAndPathsAreRejectedBeforeDiskWrites() = fixture { root, store ->
        for (voice in listOf(PocketVoiceState(65,List(12){FloatArray(65*1024)}), PocketVoiceState(14,List(12){FloatArray(14*1024){Float.NaN}}))) {
            assertTrue(runCatching { store.save(UUID.randomUUID().toString(),"声","a".repeat(64),24000,voice,{}) }.isFailure)
        }
        assertTrue(runCatching { store.save("../escape","声","a".repeat(64),24000,state(),{}) }.isFailure)
        assertTrue(root.listFiles()!!.isEmpty())
    }
}
