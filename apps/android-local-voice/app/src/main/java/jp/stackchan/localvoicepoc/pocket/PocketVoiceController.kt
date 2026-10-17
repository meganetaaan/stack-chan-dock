package jp.stackchan.localvoicepoc.pocket

import dev.irte.voice.PocketVoiceState
import jp.stackchan.localvoicepoc.recording.ReferenceRecordingStore
import jp.stackchan.localvoicepoc.util.StreamingPcmResampler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PocketVoiceUiState(val busy: Boolean = false, val encoderReady: Boolean = false,
    val profiles: List<PocketVoiceProfile> = emptyList(), val selectedId: String? = null, val message: String? = null,
    val readyPresets: Set<String> = setOf(PocketPresets.DEFAULT), val progress: Float? = null, val previewing: Boolean = false)

/** A single explicit operation, separate from microphone capture and conversation playback. */
class PocketVoiceController(private val recordings: ReferenceRecordingStore, private val store: PocketVoiceStore,
    private val encoderDirectory: File, private val encoderReady: (() -> Unit) -> Boolean,
    private val build: suspend (FloatArray, File) -> PocketVoiceState,
    private val apply: suspend (PocketVoiceState?, () -> Unit) -> Unit, private val cancelNative: () -> Unit,
    private val canWork: () -> Boolean, private val scope: CoroutineScope,
    private val presets: PocketPresetStore? = null, private val canChoose: () -> Boolean = canWork,
    private val playPreview: suspend () -> Unit = { error("試聴を開始できません") },
    private val stopPreview: () -> Unit = {}, private val onState: (PocketVoiceUiState) -> Unit = {},
    private val reference: (PocketPreset, () -> Unit, (Float) -> Unit) -> FloatArray = { spec, active, progress ->
        requireNotNull(presets).reference(spec, active, progress) }) {
    private val mutable = MutableStateFlow(PocketVoiceUiState())
    val state: StateFlow<PocketVoiceUiState> = mutable.asStateFlow()
    private val lock = Any()
    private val operation = Mutex()
    private var job: Job? = null

    private fun update(transform: (PocketVoiceUiState) -> PocketVoiceUiState) = synchronized(lock) {
        mutable.update(transform)
        onState(mutable.value)
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        val entries = store.list(); val selected = store.selectedId()
        val ready = encoderReady { coroutine.ensureActive() }
        update { it.copy(profiles = entries, selectedId = selected, encoderReady = ready,
            readyPresets = presets?.readyIds() ?: setOf(PocketPresets.DEFAULT)) }
    }
    private fun start(message: String, allowed: () -> Boolean = canWork, work: suspend (() -> Unit) -> Unit) = synchronized(lock) {
        if (mutable.value.busy || !allowed()) return@synchronized
        update { it.copy(busy = true, message = message) }
        job = scope.launch {
            try {
                operation.withLock {
                    val coroutine = currentCoroutineContext()
                    work { coroutine.ensureActive() }
                }
            } catch (error: CancellationException) {
                update { it.copy(message = "処理を中止しました") }
            } catch (error: Throwable) {
                val message = if (!currentCoroutineContext().isActive) "処理を中止しました"
                    else error.message ?: "声の処理ができませんでした。もう一度お試しください"
                update { it.copy(message = message) }
            } finally {
                withContext(NonCancellable) { runCatching { refresh() } }
                synchronized(lock) { job = null; update { it.copy(busy = false, progress = null, previewing = false) } }
            }
        }
    }
    fun importEncoder(import: suspend (() -> Unit) -> Unit) = start("追加モデルを取り込んでいます", canChoose) { active ->
        import(active); active(); update { it.copy(message = "声を作る準備ができました") }
    }
    fun create(recordingId: String) = start("録音の先頭から最大5秒を使って、声を作っています") { active ->
        check(mutable.value.profiles.none { it.recordingId == recordingId }) { "この録音の声は作成済みです" }
        val entry = withContext(Dispatchers.IO) { recordings.list().firstOrNull { it.id == recordingId } }
            ?: error("録音が見つかりません")
        val draft = withContext(Dispatchers.IO) { recordings.read(entry) }
        val pcm = withContext(Dispatchers.IO) {
            active(); check(encoderReady(active)) { "声の変換用追加モデルを取り込んでください" }
            input(draft.pcm, draft.sampleRate)
        }
        require(pcm.any { kotlin.math.abs(it) > .0001f }) { "声が小さすぎます。録音を確認して録り直してください" }
        val voice = build(pcm, encoderDirectory)
        withContext(Dispatchers.IO) {
            active(); store.save(entry.id, entry.name, PocketVoiceStore.sha(draft.pcm), pcm.size, voice, active)
        }
        update { it.copy(message = "声を保存しました。「この声を使う」で切り替えられます") }
    }
    private suspend fun readSelection(id: String): PocketVoiceState = withContext(Dispatchers.IO) {
        val preset = PocketPresets.selected(id)
        if (preset != null) requireNotNull(presets).read(preset.id)
        else store.read(store.list().firstOrNull { it.id == id } ?: error("声が見つかりません"))
    }
    private suspend fun commitSelection(id: String?, active: () -> Unit) {
        val voice = id?.let { readSelection(it) }
        active()
        apply(voice) { store.select(id, active) }
        update { it.copy(selectedId = id) }
    }
    fun select(id: String?) = start("声を切り替えています", canChoose) { active ->
        commitSelection(id, active)
        update { it.copy(message = "声を切り替えました") }
    }
    fun preparePresets() = start("公式の標準音声を準備しています", canChoose) { active ->
        val presetStore = requireNotNull(presets)
        check(withContext(Dispatchers.IO) { encoderReady(active) }) { "先に声を作る追加モデルを取り込んでください" }
        presetStore.resetDownload()
        for ((index, spec) in PocketPresets.additional.withIndex()) {
            active()
            if (withContext(Dispatchers.IO) { presetStore.isReady(spec.id) }) continue
            val pcm = withContext(Dispatchers.IO) {
                reference(spec, active) { fraction ->
                    update { it.copy(message = spec.label + "を取得しています", progress = (index + fraction) / 9f) }
                }
            }
            update { it.copy(message = spec.label + "を変換しています") }
            val voice = build(pcm, encoderDirectory)
            withContext(Dispatchers.IO) { active(); presetStore.save(spec, voice, pcm.size, active) }
        }
        update { it.copy(message = "標準10音声の準備ができました。声を選んで試聴できます") }
    }
    fun previewSelection(id: String?) = start("声を選んで試聴しています", canChoose) { active ->
        commitSelection(id, active)
        update { it.copy(previewing = true) }
        playPreview()
        update { it.copy(message = "試聴しました。この声を読み上げに使います") }
    }
    fun removePreset(id: String) = start("標準音声の準備データを削除しています", canChoose) { active ->
        val presetStore = requireNotNull(presets)
        val key = PocketPresets.selection(id)
        require(key != null)
        if (store.selectedId() == key) commitSelection(null, active)
        withContext(Dispatchers.IO) { active(); presetStore.remove(id) }
        update { it.copy(message = "この標準音声の準備を解除しました。公式音声の準備で再作成できます") }
    }
    suspend fun restoreSelected() = operation.withLock {
        val id = withContext(Dispatchers.IO) { store.selectedId() }
        if (id != null) {
            val voice = try {
                readSelection(id)
            } catch (error: CancellationException) { throw error
            } catch (_: Throwable) {
                // A corrupt cache cannot be used; the freshly loaded runtime still has female03.
                withContext(Dispatchers.IO) { store.select(null) }
                update { it.copy(message = "保存した声を読めないため、標準の声を使います") }
                null
            }
            // A runtime/allocation failure keeps the desired selection for a later retry.
            if (voice != null) apply(voice) { }
        }
        refresh()
    }
    fun cancel() = synchronized(lock) {
        if (mutable.value.busy) { job?.cancel(); presets?.cancelDownload(); cancelNative(); stopPreview() }
    }
    suspend fun cancelAndJoin() { cancel(); val previous = synchronized(lock) { job }; previous?.join() }

    companion object {
        fun input(bytes: ByteArray, sampleRate: Int): FloatArray {
            require(sampleRate in 8000..192000 && bytes.size % 2 == 0)
            val used = minOf(bytes.size / 2, sampleRate * 5)
            require(used >= sampleRate) { "1秒以上の録音が必要です" }
            val b = ByteBuffer.wrap(bytes, 0, used * 2).order(ByteOrder.LITTLE_ENDIAN)
            val samples = ShortArray(used) { b.short }
            val resampler = StreamingPcmResampler(sampleRate, 24000)
            val converted = (resampler.process(samples) + resampler.process(shortArrayOf(samples.last())))
                .copyOf((used * 24000L / sampleRate).toInt())
            require(converted.size in 24000..120000)
            return FloatArray(converted.size) { converted[it] / 32768f }
        }
    }
}
