package dev.irte.voice

/** Compact, model-specific prefix KV cache. It never contains generated utterance state. */
data class PocketVoiceState(val frames: Int, val caches: List<FloatArray>) {
    fun validate() {
        require(frames in 2..64 && caches.size == 12) { "Invalid reference voice state" }
        require(caches.all { it.size == frames * 1024 && it.all(Float::isFinite) }) { "Invalid reference voice cache" }
    }
    companion object {
        const val MODEL = "d7e31014100474bb24d375f5f006d4dfc34b41b88c88e71dc2ea4de7c14d5522"
        const val ENCODER = "5a6927d66a3585ad220f446ef8d4429948a17f10257661304cf42d61653865f6"
        const val BOS = "ec11dd25e9e0c647bd11a5708d1d8de723a93dcfc44198b0b642467380e4af9c"
    }
}
