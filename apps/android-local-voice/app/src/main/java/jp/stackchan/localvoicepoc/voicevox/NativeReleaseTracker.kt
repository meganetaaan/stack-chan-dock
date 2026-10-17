package jp.stackchan.localvoicepoc.voicevox

import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import kotlinx.coroutines.delay

/** Phantom enqueue follows finalization. GC requests alone are not release evidence. */
internal class NativeReleaseTracker {
    private val queue = ReferenceQueue<Any>()
    private val pending = mutableSetOf<PhantomReference<Any>>()
    @Synchronized fun retire(value: Any) { pending += PhantomReference(value, queue) }
    @Synchronized private fun drained(): Boolean {
        while (true) {
            val reference = queue.poll() ?: break
            pending.remove(reference)
            reference.clear()
        }
        return pending.isEmpty()
    }
    suspend fun awaitRelease() {
        repeat(60) {
            if (drained()) return
            System.gc()
            System.runFinalization()
            delay(50)
        }
        check(drained()) { "旧 VOICEVOX エンジンの回収を確認できないため比較を停止しました。少し待って再度準備してください。" }
    }
}
