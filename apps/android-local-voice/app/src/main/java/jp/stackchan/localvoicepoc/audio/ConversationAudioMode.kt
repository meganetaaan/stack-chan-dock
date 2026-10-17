package jp.stackchan.localvoicepoc.audio

/** Explicit choice, independent of USB connection state. */
enum class ConversationAudioMode(val label: String) { DOCK("接続端末"), PHONE("スマホ") }
