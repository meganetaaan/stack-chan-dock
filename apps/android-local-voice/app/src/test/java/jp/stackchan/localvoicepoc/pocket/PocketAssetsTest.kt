package jp.stackchan.localvoicepoc.pocket

import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PocketAssetsTest {
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val model = byteArrayOf(1,2,3,4)
    private val manifest get() = """{"files":[{"name":"model.onnx","bytes":4,"sha256":"${hash(model)}"}]}"""
    private fun zip(vararg pairs: Pair<String,ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip -> for ((name,data) in pairs) { zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() } }
    }.toByteArray()
    private fun valid() = zip("model.onnx" to model, "manifest.json" to manifest.toByteArray())
    private fun fixture(test: (File) -> Unit) {
        val root=Files.createTempDirectory("pocket-assets-").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
    @Test fun completePinnedZipCommitsAndChangedOrMissingFilesFailReadiness() = fixture { root ->
        assertFalse(PocketAssets.isVerified(root, manifest))
        PocketAssets.import(root, valid().inputStream(), manifest)
        assertTrue(PocketAssets.isVerified(root, manifest))
        File(PocketAssets.directory(root),"model.onnx").writeBytes(byteArrayOf(4,3,2,1))
        assertFalse(PocketAssets.isVerified(root, manifest))
        assertTrue(runCatching { PocketAssets.verify(root,manifest) }.exceptionOrNull() is IllegalStateException)
        PocketAssets.import(root, valid().inputStream(), manifest)
        File(PocketAssets.directory(root),"manifest.json").delete()
        assertFalse(PocketAssets.isVerified(root, manifest))
    }
    @Test fun malformedOversizedUnknownAndIncompleteZipPreservePreviousInstall() = fixture { root ->
        PocketAssets.import(root, valid().inputStream(), manifest)
        val rejected=listOf(
            zip("../escape" to model), zip("/absolute" to model), zip("nested/model.onnx" to model),
            zip("model.onnx" to ByteArray(5)), zip("model.onnx" to byteArrayOf(1,2,3)),
            zip("model.onnx" to byteArrayOf(4,3,2,1),"manifest.json" to manifest.toByteArray()),
            zip("model.onnx" to model), zip("model.onnx" to model,"manifest.json" to "{}".toByteArray()),
            zip("model.onnx" to model,"dup__.onnx" to model).toString(Charsets.ISO_8859_1).replace("dup__.onnx","model.onnx").toByteArray(Charsets.ISO_8859_1),
            valid().copyOf(20))
        for (bytes in rejected) {
            assertTrue(runCatching { PocketAssets.import(root,bytes.inputStream(),manifest) }.isFailure)
            assertTrue(PocketAssets.isVerified(root,manifest))
            assertEquals(listOf("pocket-ja-v1"),root.listFiles()!!.map { it.name })
        }
    }
    @Test fun cancelledImportLeavesNoPartialFilesAndKeepsPreviousInstall() = fixture { root ->
        PocketAssets.import(root,valid().inputStream(),manifest)
        var checks=0
        assertTrue(runCatching { PocketAssets.import(root,valid().inputStream(),manifest, { if (++checks==3) throw CancellationException("stop") }) }.exceptionOrNull() is CancellationException)
        assertTrue(PocketAssets.isVerified(root,manifest))
        assertEquals(listOf("pocket-ja-v1"),root.listFiles()!!.map { it.name })
    }
    @Test fun importsTheExactAcceptedModelZipAndManifestWithoutConversion() {
        val source=System.getProperty("pocket.fixture.root")
        org.junit.Assume.assumeTrue("Supply the read-only accepted evaluation root",source!=null)
        val base=File(requireNotNull(source))
        val fixedManifest=File(base,"android/assets/pocket-model-manifest.json").readText(Charsets.UTF_8)
        assertEquals("f4150f04019876f1f322a2a06845111efbabae08212c7ed783918aece54770b6",hash(fixedManifest.toByteArray()))
        val sourceZip=File(base,"deliverables/pocket/pocket-tts-ja-6l-female03-fp32-v1.zip")
        assertEquals(191921727L,sourceZip.length())
        assertEquals("408939da4678395856b7f63d76fec4c635505dee9ae5fe88212117177bd11e02",hash(sourceZip.readBytes()))
        fixture { root ->
            sourceZip.inputStream().use { PocketAssets.import(root,it,fixedManifest) }
            assertTrue(PocketAssets.isVerified(root,fixedManifest))
            assertEquals(27,PocketAssets.directory(root).listFiles()!!.size)
        }
    }
    @Test fun addonImportIsIsolatedAndFailurePreservesBothInstallations() = fixture { root ->
        PocketAssets.import(root,valid().inputStream(),manifest)
        PocketAssets.import(root,valid().inputStream(),manifest,subdirectory="pocket-encoder-v1")
        assertTrue(PocketAssets.isVerified(root,manifest))
        assertTrue(PocketAssets.isVerified(root,manifest,subdirectory="pocket-encoder-v1"))
        assertTrue(runCatching { PocketAssets.import(root,zip("../escape" to model).inputStream(),manifest,subdirectory="pocket-encoder-v1") }.isFailure)
        assertTrue(PocketAssets.isVerified(root,manifest));assertTrue(PocketAssets.isVerified(root,manifest,subdirectory="pocket-encoder-v1"))
    }
    @Test fun apkContentsAreRejectedWithTheExactAddonFilenameAndPreserveExistingFiles() = fixture { root ->
        PocketAssets.import(root,valid().inputStream(),manifest)
        PocketAssets.import(root,valid().inputStream(),manifest,subdirectory="pocket-encoder-v1")
        for (entry in listOf("META-INF/com/android/build/gradle/app-metadata.properties","AndroidManifest.xml","classes.dex","classes12.dex","resources.arsc")) {
            val failure=runCatching {PocketAssets.import(root,zip(entry to model).inputStream(),manifest,subdirectory="pocket-encoder-v1")}.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure!!.message!!.contains("APK"));assertTrue(failure.message!!.contains("pocket-tts-ja-reference-encoder-v1.zip"))
            assertTrue(PocketAssets.isVerified(root,manifest));assertTrue(PocketAssets.isVerified(root,manifest,subdirectory="pocket-encoder-v1"))
            assertEquals(setOf("pocket-ja-v1","pocket-encoder-v1"),root.listFiles()!!.map {it.name}.toSet())
        }
    }
    @Test fun normalZipSupportsNonSeekableShortReadStreamsAndStillRejectsWrongHash() = fixture { root ->
        var closed=false
        val stream=object:FilterInputStream(valid().inputStream()) {
            override fun available()=0
            override fun markSupported()=false
            override fun read(b:ByteArray,off:Int,len:Int)=super.read(b,off,minOf(len,7))
            override fun close(){closed=true;super.close()}
        }
        PocketAssets.import(root,stream,manifest,subdirectory="pocket-encoder-v1")
        assertTrue(closed);assertTrue(PocketAssets.isVerified(root,manifest,subdirectory="pocket-encoder-v1"))
        val changed=zip("model.onnx" to byteArrayOf(4,3,2,1),"manifest.json" to manifest.toByteArray())
        val failure=runCatching {PocketAssets.import(root,changed.inputStream(),manifest,subdirectory="pocket-encoder-v1")}.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("hash/size mismatch"))
        assertTrue(PocketAssets.isVerified(root,manifest,subdirectory="pocket-encoder-v1"))
    }
}
