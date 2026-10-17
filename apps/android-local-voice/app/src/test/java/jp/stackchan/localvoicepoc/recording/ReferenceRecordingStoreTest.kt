package jp.stackchan.localvoicepoc.recording

import com.google.gson.JsonParser
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ReferenceRecordingStoreTest {
    private fun fixture(test:(File,ReferenceRecordingStore)->Unit) {
        val root=Files.createTempDirectory("reference-store-").toFile()
        try{test(root,ReferenceRecordingStore(File(root,"saved")))}finally{root.deleteRecursively()}
    }
    private fun draft()=RecordingDraft(ByteArray(24000*4){(it%127).toByte()},24000)
    @Test fun explicitSavePersistsCanonicalWavActualRateDurationAndExactScript()=fixture {root,store->
        val draft=draft();assertTrue(root.listFiles()!!.isEmpty())
        val entry=store.save(draft,"いつもの声")
        assertEquals(1,store.list().size)
        val folder=File(root,"saved/${entry.id}")
        val wav=File(folder,"audio.wav").readBytes();val header=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF",wav.copyOfRange(0,4).toString(Charsets.US_ASCII));assertEquals(24000,header.getInt(24))
        assertEquals(1,header.getShort(22).toInt());assertEquals(16,header.getShort(34).toInt())
        assertEquals(draft.pcm.size,header.getInt(40));assertArrayEquals(draft.pcm,wav.copyOfRange(44,wav.size))
        val meta=JsonParser.parseString(File(folder,"recording.json").readText()).asJsonObject
        assertEquals(2000L,meta["durationMs"].asLong);assertEquals(REFERENCE_SCRIPT,meta["script"].asString)
        assertEquals(24000,meta["sampleRate"].asInt);assertEquals("いつもの声",meta["name"].asString)
        assertArrayEquals(draft.pcm,store.read(entry).pcm)
    }
    @Test fun cancelledSaveLeavesNoPartialRecordAndKeepsExistingRecord()=fixture {root,store->
        val old=store.save(draft(),"保存済み")
        var calls=0
        assertTrue(runCatching{store.save(draft(),"中断"){if(++calls==3)throw CancellationException("stop")}}.exceptionOrNull() is CancellationException)
        assertEquals(listOf(old.id),store.list().map{it.id})
        assertEquals(listOf(old.id),File(root,"saved").listFiles()!!.map{it.name})
    }
    @Test fun displayNamesCannotChoosePathsAndInvalidNamesNeverSave()=fixture {root,store->
        for(name in listOf(""," ","a".repeat(41),"声\u0000"))assertTrue(runCatching{store.save(draft(),name)}.isFailure)
        assertTrue(root.listFiles()!!.isEmpty())
        val saved=store.save(draft(),"../声の名前")
        assertEquals("../声の名前",saved.name);assertEquals(listOf(saved.id),File(root,"saved").listFiles()!!.map{it.name})
    }
    @Test fun invalidLengthsAndCorruptedAudioAreRejected()=fixture {_,store->
        for(draft in listOf(RecordingDraft(ByteArray(1),24000),RecordingDraft(ByteArray(2),24000),RecordingDraft(ByteArray(24000*2*61),24000))) {
            assertTrue(runCatching{store.save(draft,"不正")}.isFailure)
        }
        assertTrue(store.list().isEmpty())
    }
    @Test fun malformedSavedMetadataAndWavDoNotBecomeValidInput()=fixture {root,store->
        val entry=store.save(draft(),"声")
        val audio=File(root,"saved/${entry.id}/audio.wav");val data=audio.readBytes();data[24]=1;audio.writeBytes(data)
        assertTrue(runCatching{store.read(entry)}.isFailure)
        File(root,"saved/${entry.id}/recording.json").writeText("{}")
        assertTrue(store.list().isEmpty())
    }
    @Test fun deleteRemovesOnlyTheChosenRecordingAndRestartKeepsTheListConsistent()=fixture {root,store->
        val chosen=store.save(draft(),"同じ名前");val other=store.save(draft(),"同じ名前")
        assertFalse(store.delete(chosen).cleanupPending)
        assertEquals(listOf(other.id),store.list().map{it.id})
        assertFalse(File(root,"saved/${chosen.id}").exists())
        val restarted=ReferenceRecordingStore(File(root,"saved"))
        assertEquals(listOf(other.id),restarted.list().map{it.id});assertArrayEquals(draft().pcm,restarted.read(other).pcm)
    }
    @Test fun cancellationBeforeDeleteCommitPreservesBytesAndInvalidTargetsDoNotEscapeRoot()=fixture {root,store->
        val entry=store.save(draft(),"残す録音");val wav=File(root,"saved/${entry.id}/audio.wav").readBytes()
        var checks=0
        assertTrue(runCatching{store.delete(entry){if(++checks==2)throw CancellationException("cancel before commit")}}.exceptionOrNull() is CancellationException)
        assertArrayEquals(wav,File(root,"saved/${entry.id}/audio.wav").readBytes())
        assertEquals(listOf(entry.id),store.list().map{it.id})
        assertTrue(runCatching{store.delete(entry.copy(id="../outside"))}.isFailure)
        assertTrue(runCatching{store.delete(entry.copy(name="別の録音"))}.isFailure)
        assertEquals(listOf(entry.id),store.list().map{it.id})
    }
    @Test fun restartFinishesCommittedDeletionAndLeavesUnrelatedDirectoriesAlone()=fixture {root,store->
        val entry=store.save(draft(),"削除を確定済み");val keep=store.save(draft(),"保持")
        val base=File(root,"saved").canonicalFile
        val source=File(base,entry.id);val deleted=File(base,".deleted-${entry.id}")
        assertEquals(base,source.canonicalFile.parentFile);assertEquals(base,deleted.parentFile)
        Files.move(source.toPath(),deleted.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        val unrelated=File(base,".deleted-not-an-id").apply{mkdir()};File(unrelated,"keep.txt").writeText("keep")
        val restarted=ReferenceRecordingStore(base)
        assertEquals(listOf(keep.id),restarted.list().map{it.id});assertFalse(deleted.exists())
        assertEquals("keep",File(unrelated,"keep.txt").readText())
    }
}
