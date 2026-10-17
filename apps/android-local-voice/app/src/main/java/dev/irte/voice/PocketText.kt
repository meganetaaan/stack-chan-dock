package dev.irte.voice

import org.json.JSONObject

/** Stock 3.3.0 Japanese preparation and token-boundary chunking. */
class PocketText(private val replacements:JSONObject,private val encode:(String)->IntArray,private val decode:(IntArray)->String) {
    // Java's (?U) flag is rejected by Android's ICU regex engine. Spell out the
    // same Unicode White_Space set so normalization and word counts agree.
    private val whitespace=Regex("[\\u0009-\\u000d\\u0020\\u0085\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]+")
    data class Chunk(val text:String,val tokens:LongArray,val framesAfterEos:Int,val maxFrames:Int)
    private fun normalize(text:String):String {
        val mapped=buildString {for(c in text){val key=c.toString();append(if(replacements.has(key))replacements.getString(key) else key)}}
        var value=mapped.trim().split(whitespace).joinToString(" ").trim().replace(Regex("([.!?…])\\s*[,;:]"),"$1").replace(';',',')
        require(value.isNotEmpty()){ "Text is empty after Japanese normalization" }
        if(value.last() !in ".!?…")value=if(value.last() in ",:—–-")value.trimEnd {it in ",:—–- "}+"." else value+"."
        return value
    }
    private fun segments(ids:IntArray,boundaries:Set<Int>,decimal:Boolean):List<Pair<Int,String>> {
        val indices=mutableListOf(0);var previous=false
        for(i in ids.indices){if(ids[i] in boundaries)previous=true else {if(previous){
            val prefix=if(decimal)decode(ids.copyOfRange(0,i)) else ""
            val suffix=if(decimal)decode(ids.copyOfRange(i,ids.size)) else ""
            val number=prefix.length>=2&&prefix.last()=='.'&&prefix[prefix.length-2].isDigit()&&suffix.firstOrNull()?.isDigit()==true
            if(!number)indices+=i
        };previous=false}}
        indices+=ids.size
        return indices.zipWithNext {a,b->(b-a) to decode(ids.copyOfRange(a,b))}
    }
    fun chunks(text:String):List<Chunk> {
        val normalized=normalize(text);val ids=encode(normalized)
        val end=encode(".!...?").drop(1).toSet();val fallback=encode(",;:").drop(1).toSet()
        val segments=segments(ids,end,true).flatMap {(count,s)->if(count<=50)listOf(count to s) else segments(encode(s.trim()),fallback,false)}
        val grouped=mutableListOf<String>();var current="";var count=0
        for((n,s) in segments){if(current.isNotEmpty()&&count+n>50){grouped+=current.trim();current="";count=0};current+=if(current.isEmpty())s else " $s";count+=n}
        if(current.isNotEmpty())grouped+=current.trim()
        return grouped.map {s->val prepared=normalize(s);val tokens=encode(prepared).map {it.toLong()}.toLongArray();require(tokens.size<=96){"A sentence exceeds 96 Japanese tokens; split the sentence"};require(tokens.isNotEmpty()&&tokens.all {it in 0..3999});val words=prepared.split(whitespace).size;Chunk(prepared,tokens,if(words<=4)5 else 3,kotlin.math.ceil((tokens.size/3.0+2)*12.5).toInt())}
    }
}
