package jp.stackchan.localvoicepoc.voicevox

import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

class VoicevoxDictionaryFixtureTest {
    @Test fun officialArchiveVerifiesAndRecoversFromCorruptionAndInterruptedReplacement() {
        val fixture = System.getProperty("voicevox.dictionary.fixture")
        assumeNotNull(fixture)
        val archive = File(fixture!!)
        assertTrue(archive.isFile)
        assertEquals(VoicevoxAssets.DICTIONARY.sha256, archive.sha256())
        val root = java.nio.file.Files.createTempDirectory("voicevox-dictionary-fixture").toFile()
        try {
            val dictionary = File(root, "dictionary")
            VoicevoxDictionary.prepare(archive, dictionary)
            assertTrue(VoicevoxDictionary.isVerified(dictionary))
            File(dictionary, "sys.dic").outputStream().use { it.write(0) }
            assertFalse(VoicevoxDictionary.isVerified(dictionary))
            VoicevoxDictionary.prepare(archive, dictionary)
            assertTrue(VoicevoxDictionary.isVerified(dictionary))
            assertTrue(dictionary.renameTo(File(root, "dictionary.backup")))
            VoicevoxDictionary.prepare(archive, dictionary)
            assertTrue(VoicevoxDictionary.isVerified(dictionary))
            assertFalse(File(root, "dictionary.backup").exists())
            assertFalse(File(root, "dictionary.staging").exists())
        } finally { root.deleteRecursively() }
    }
}
