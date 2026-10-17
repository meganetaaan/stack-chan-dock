package dev.irte.voice

object PocketTokenizer {
    init {System.loadLibrary("irte_pocket")}
    external fun open(path:ByteArray):Long
    external fun encode(handle:Long,text:ByteArray):IntArray
    external fun decode(handle:Long,tokens:IntArray):ByteArray
    external fun close(handle:Long)
}
