package jp.stackchan.localvoicepoc.piper

import android.content.Context
import jp.stackchan.localvoicepoc.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

/**
 * Runtime adapter for the optional Piper Plus AAR.
 *
 * Reflection is deliberate: it keeps this PoC buildable before the locally-built AAR is
 * copied into app/libs, while retaining the real Piper Plus API at runtime.
 */
class PiperPlusReflectionSynthesizer(
    context: Context,
    private val runtimeClassName: String = "com.piperplus.PiperPlus",
    private val aarPresent: Boolean = BuildConfig.PIPER_PLUS_AAR_PRESENT,
) : SpeechSynthesizer {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val pcm = BlockingPiperFlow { normalized ->
        synchronized(lock) {
            val localEngine = engine ?: error("Piper Plus is not loaded")
            val clazz = engineClass ?: error("Piper Plus class is unavailable")
            try {
                val result = clazz.getMethod("synthesize", String::class.java, Int::class.javaPrimitiveType!!)
                    .invoke(localEngine, normalized, 0) as ShortArray
                SpeechSynthesizer.AudioChunk(modelSampleRate, result)
            } catch (error: InvocationTargetException) {
                throw error.targetException ?: error
            }
        }
    }

    @Volatile private var engine: Any? = null
    @Volatile private var engineClass: Class<*>? = null
    @Volatile private var modelSampleRate: Int = 0

    override val isLoaded: Boolean get() = engine != null

    val runtimeAvailable: Boolean
        get() = aarPresent &&
            runCatching { Class.forName(runtimeClassName) }.isSuccess

    suspend fun load(installation: PiperInstallation) = withContext(Dispatchers.IO) {
        require(runtimeAvailable) {
            "Piper Plus AAR is missing. Copy piper-plus-release.aar to app/libs and rebuild."
        }
        require(installation.isComplete) {
            "Piper model, JSON config, and OpenJTalk dictionary must all be imported first."
        }

        val coroutine = currentCoroutineContext()
        pcm.cancel()
        synchronized(lock) {
            closeLocked()
            val clazz = Class.forName(runtimeClassName)
            val created = try { invokeCreate(clazz, installation) }
                catch (error: InvocationTargetException) { throw error.targetException ?: error }
            try {
                val sampleRate = clazz.getMethod("getSampleRate").invoke(created) as Int
                require(sampleRate > 0) { "Piper Plus returned an invalid sample rate" }
                coroutine.ensureActive()
                engineClass = clazz
                engine = created
                modelSampleRate = sampleRate
            } catch (error: Throwable) {
                runCatching { clazz.getMethod("close").invoke(created) }
                throw if (error is InvocationTargetException) error.targetException ?: error else error
            }

        }
    }

    override fun synthesize(text: String) = pcm.synthesize(text)
    fun synthesize(text: String, timing: jp.stackchan.localvoicepoc.voicevox.TtsTiming) = pcm.synthesize(text, timing)
    override fun cancel() = pcm.cancel()
    override fun close() {
        cancel()
        synchronized(lock) { closeLocked() }
    }

    private fun invokeCreate(clazz: Class<*>, installation: PiperInstallation): Any {
        val parameterTypes = arrayOf<Class<*>>(
            Context::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
        )
        val arguments = arrayOf(
            appContext,
            installation.model.absolutePath,
            installation.config.absolutePath,
            installation.dictionaryDirectory.absolutePath,
        )

        clazz.methods.firstOrNull { method ->
            method.name == "create" &&
                Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.contentEquals(parameterTypes)
        }?.let { method ->
            return requireNotNull(method.invoke(null, *arguments)) {
                "PiperPlus.create returned null"
            }
        }

        val companion = requireNotNull(clazz.getField("Companion").get(null)) {
            "PiperPlus.Companion is null"
        }
        return requireNotNull(
            companion.javaClass.getMethod("create", *parameterTypes)
                .invoke(companion, *arguments),
        ) {
            "PiperPlus.Companion.create returned null"
        }
    }

    private fun closeLocked() {
        engine?.let { local ->
            runCatching { engineClass?.getMethod("close")?.invoke(local) }
        }
        engine = null
        engineClass = null
        modelSampleRate = 0
    }

}
