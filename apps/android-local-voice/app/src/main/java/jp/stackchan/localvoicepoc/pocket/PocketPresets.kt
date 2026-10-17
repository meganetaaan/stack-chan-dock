package jp.stackchan.localvoicepoc.pocket

/** Official reference metadata only. No additional voice audio or derived cache is bundled. */
data class PocketPreset(val id: String, val label: String, val bytes: Int, val sha256: String) {
    val url: String get() = "https://huggingface.co/takuma104/pocket-tts-ja/resolve/${PocketPresets.REVISION}/voices/$id.wav"
}

object PocketPresets {
    const val REVISION = "119685fd7a51356429c6d25e37d570b423c17663"
    const val DEFAULT = "female_03"
    const val SOURCE = "https://huggingface.co/takuma104/pocket-tts-ja/tree/119685fd7a51356429c6d25e37d570b423c17663/voices"
    val all = listOf(
        PocketPreset("female_01", "女性1・アナウンス調", 660524, "8705798fd21b7366ed2d34f5283c6485786a16452d67deec87ffe2a56a1b957c"),
        PocketPreset("female_02", "女性2・明るい", 580466, "fd1cc48a9cb629a207a2822acd3c352fe1d9384a5c7f8b97a782f71bd138aec6"),
        PocketPreset("female_03", "女性3・穏やか", 666496, "5111236ec3d84e8917a4d890f049003725fb731b59225db6b5bd18d5d68f018f"),
        PocketPreset("female_04", "女性4・低め", 681930, "e94655416a5af4bcbcc58525477536c9efecb7e2bc325bfccf72da5d1980a619"),
        PocketPreset("female_05", "女性5・高め", 711390, "b88f9e41463d33580f347e3468cd7be869b0acd82a7cd0e3545b921a91d89683"),
        PocketPreset("male_01", "男性1・低音", 712364, "ffd3cbcfd1df333b36f0da01380c9e089cc7ba231eb07ba5e08c31741bcce116"),
        PocketPreset("male_02", "男性2・爽やか", 620204, "6950eca54884c6407b84194db6e591ffad2b9046e153c91afdd51ac60d193f42"),
        PocketPreset("male_03", "男性3・穏やか", 658604, "06991df751082dea3ac9952167b1568fa1bb43572ea2c878b1251fc859de8237"),
        PocketPreset("male_04", "男性4・渋め", 733484, "117426c2bb9766ac391c0c0bfc828fccda521a885fc72ab723d827d549fb8d63"),
        PocketPreset("male_05", "男性5・軽やか", 627884, "bae9b4aba363a4d5fe99de402fa3c301f76109f62db6fe6e8f14ae3f5459005c"),
    )
    val additional get() = all.filter { it.id != DEFAULT }
    fun get(id: String) = all.firstOrNull { it.id == id } ?: error("標準音声が見つかりません")
    fun selection(id: String): String? = get(id).let { if (id == DEFAULT) null else "preset:$id" }
    fun selected(key: String?): PocketPreset? = if (key == null) get(DEFAULT)
        else all.firstOrNull { it.id != DEFAULT && key == "preset:${it.id}" }
    fun name(key: String?, profiles: List<PocketVoiceProfile>): String =
        selected(key)?.label ?: profiles.firstOrNull { it.id == key }?.name ?: "女性3・穏やか"
}
