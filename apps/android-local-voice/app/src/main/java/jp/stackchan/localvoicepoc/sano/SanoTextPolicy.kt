package jp.stackchan.localvoicepoc.sano

/** Bound the upstream 44-token G2P frontend, preserving Unicode code points and all text. */
internal object SanoTextPolicy {
    const val MAX_CODE_POINTS = 1_000
    const val SEGMENT_CODE_POINTS = 40
    fun segments(text: String): List<String> {
        val value = text.trim()
        require('\u0000' !in value) { "sanoTTS text contains a NUL character" }
        var cursor = 0
        while (cursor < value.length) {
            val c = value[cursor++]
            if (Character.isHighSurrogate(c)) {
                require(cursor < value.length && Character.isLowSurrogate(value[cursor++])) { "Invalid Unicode text" }
            } else require(!Character.isLowSurrogate(c)) { "Invalid Unicode text" }
        }
        require(value.codePointCount(0, value.length) <= MAX_CODE_POINTS) { "sanoTTS text is limited to 1000 characters per request" }
        val result = mutableListOf<String>()
        var offset = 0
        while (offset < value.length) {
            val count = minOf(SEGMENT_CODE_POINTS, value.codePointCount(offset, value.length))
            val limit = value.offsetByCodePoints(offset, count)
            var end = limit
            for (index in offset until limit) {
                if (value[index] in "。！？!?、,\n") {
                    end = index + 1
                    while (end < limit && value[end] in "。！？!?、,\n") end++
                    break
                }
            }
            value.substring(offset, end).takeIf { it.isNotBlank() }?.let(result::add)
            offset = end
        }
        return result
    }
    fun pcm16(samples: FloatArray): ShortArray = ShortArray(samples.size) { index ->
        val sample = samples[index]
        check(sample.isFinite()) { "Nonfinite sanoTTS PCM" }
        (sample.coerceIn(-1f, 1f) * 32768f).toInt().coerceIn(-32768, 32767).toShort()
    }
}
