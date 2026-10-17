package jp.stackchan.localvoicepoc.sano

import java.io.File
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SanoAssetsTest {
    private fun fixtures(): File {
        val value = System.getProperty("sano.fixture.dir")
        org.junit.Assume.assumeTrue("Supply -PsanoFixtureDir for pinned public model tests", value != null)
        return File(requireNotNull(value))
    }
    @Test fun identifiesBothPinnedAssetsAndRejectsTampering() {
        for (asset in SanoAssets.all) assertEquals("verified", SanoAssets.inspect(File(fixtures(),asset.name),asset).status)
        val directory = Files.createTempDirectory("sano-assets-test").toFile()
        try {
            val file = File(directory,SanoAssets.weights.name)
            assertEquals("missing",SanoAssets.inspect(file,SanoAssets.weights).status)
            file.writeBytes(byteArrayOf(0))
            assertEquals("size_mismatch",SanoAssets.inspect(file,SanoAssets.weights).status)
            file.writeBytes(ByteArray(SanoAssets.weights.bytes.toInt()))
            assertEquals("hash_mismatch",SanoAssets.inspect(file,SanoAssets.weights).status)
        } finally { directory.deleteRecursively() }
    }
    @Test fun invalidOversizeAndCancelledImportPreserveVerifiedDestination() {
        val directory = Files.createTempDirectory("sano-import-test").toFile()
        try {
            val original = File(fixtures(),SanoAssets.weights.name)
            val installed = SanoAssets.install(original.inputStream(),directory)
            assertEquals(SanoAssets.weights,installed)
            for (size in listOf(1,SanoAssets.weights.bytes.toInt(),SanoAssets.dictionary.bytes.toInt()+1)) {
                assertTrue(runCatching { SanoAssets.install(ByteArrayInputStream(ByteArray(size)),directory) }.isFailure)
                assertEquals("verified", SanoAssets.inspect(File(directory,installed.name),installed).status)
            }
            assertTrue(runCatching { SanoAssets.install(original.inputStream(),directory,active={throw CancellationException("stop")}) }.exceptionOrNull() is CancellationException)
            assertEquals("verified", SanoAssets.inspect(File(directory,installed.name),installed).status)
            assertFalse(directory.listFiles()!!.any { it.name.endsWith(".tmp") })
        } finally { directory.deleteRecursively() }
    }
}
