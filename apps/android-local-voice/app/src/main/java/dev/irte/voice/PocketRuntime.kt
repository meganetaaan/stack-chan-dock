package dev.irte.voice

import ai.onnxirodori.*
import java.io.File
import java.nio.*
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.json.JSONArray

/** ONNX CPU orchestration; transformer/flow/codec graphs come from the reviewed exporter. */
class PocketRuntime(private val root:File,threads:Int,private var active:()->Unit):PocketEngine {
    private val env=OrtEnvironment.getEnvironment()
    private val sessions=linkedMapOf<String,OrtSession>()
    private val voice=linkedMapOf<String,OnnxTensor>()
    private var tokenizer=0L
    private val cancelled=AtomicBoolean(false)
    private val runLock=Any()
    private var runOptions:OrtSession.RunOptions?=null
    private val noise=File(root,"noise-seed0.f32").readBytes().let {ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let {b->FloatArray(b.remaining()).also {b.get(it)}}}
    private val config=JSONObject(File(root,"text-config.json").readText())
    private lateinit var text: PocketText
    val runtimeVersion:String get()=env.version
    var lastResult=JSONObject();private set
    init {
        try {
            env.setTelemetry(false)
            for(name in listOf("text_conditioner","flow_lm_main","flow_lm_flow","mimi_decoder")){
                active();OrtSession.SessionOptions().use {opts->
                    opts.setIntraOpNumThreads(threads);opts.setInterOpNumThreads(1)
                    opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                    opts.setCPUArenaAllocator(false);opts.setMemoryPatternOptimization(false)
                    opts.addConfigEntry("session.intra_op.allow_spinning","0");opts.addConfigEntry("session.inter_op.allow_spinning","0")
                    sessions[name]=env.createSession(File(root,"$name.onnx").absolutePath,opts)
                }
            }
            voice.putAll(defaultVoice())
            tokenizer=PocketTokenizer.open(File(root,"tokenizer.model").absolutePath.toByteArray(Charsets.UTF_8))
            text=PocketText(config.getJSONObject("replacements"),{s->PocketTokenizer.encode(tokenizer,s.toByteArray(Charsets.UTF_8))},{ids->String(PocketTokenizer.decode(tokenizer,ids),Charsets.UTF_8)})
        }catch(e:Throwable){close();throw e}
    }
    private fun defaultVoice(): Map<String,OnnxTensor> {
        val candidate=linkedMapOf<String,OnnxTensor>()
        try {
            val manifest=JSONObject(File(root,"manifest.json").readText()).getJSONArray("voice_state")
            for(i in 0 until manifest.length()){
                active();val spec=manifest.getJSONObject(i);val shape=spec.getJSONArray("shape").let {a->LongArray(a.length()){a.getLong(it)}}
                val bytes=File(root,spec.getString("file")).readBytes();val buffer=ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN).put(bytes).also {it.flip()}
                candidate[spec.getString("input")]=if(spec.getString("dtype")=="int64")OnnxTensor.createTensor(env,buffer.asLongBuffer(),shape) else OnnxTensor.createTensor(env,buffer.asFloatBuffer(),shape)
            }
            return candidate
        }catch(e:Throwable){candidate.values.forEach {it.close()};throw e}
    }
    // Called only while the adapter owns the serialized runtime and cancellation lock.
    override fun prepareRequest(active:()->Unit){this.active=active;cancelled.set(false)}
    override fun cancel(){cancelled.set(true);synchronized(runLock){runOptions?.setTerminate(true)}}
    private fun check(){active();if(cancelled.get())throw CancellationException("Pocket TTS cancelled")}
    private fun floats(data:FloatArray,vararg shape:Long)=OnnxTensor.createTensor(env,FloatBuffer.wrap(data),shape)
    private fun prefixTensors(prefix: PocketVoiceState?): Map<String,OnnxTensor> {
        if(prefix==null)return defaultVoice()
        prefix.validate()
        val tensors=linkedMapOf<String,OnnxTensor>()
        try {
            for(layer in 0 until 6){
                for(kv in 0 until 2){
                    check();val full=FloatArray(1000*1024)
                    prefix.caches[layer*2+kv].copyInto(full)
                    tensors["state_${layer*3+kv}"]=floats(full,1,1000,16,64)
                }
                tensors["state_${layer*3+2}"]=OnnxTensor.createTensor(env,LongBuffer.wrap(longArrayOf(prefix.frames.toLong())),longArrayOf(1))
            }
            return tensors
        }catch(e:Throwable){tensors.values.forEach {it.close()};throw e}
    }
    override fun selectVoice(voice: PocketVoiceState?, commit: () -> Unit) {
        val candidate=prefixTensors(voice)
        try {check();commit()}catch(e:Throwable){candidate.values.forEach {it.close()};throw e}
        val previous=this.voice.values.toList()
        this.voice.clear();this.voice.putAll(candidate)
        previous.forEach {it.close()}
    }
    override fun encodeVoice(pcm: FloatArray, encoderDirectory: File): PocketVoiceState {
        require(pcm.size in 24000..120000 && pcm.all {it.isFinite() && it in -1f..1f})
        val frames=(pcm.size+1919)/1920+1
        check()
        val conditioning=try {
            OrtSession.SessionOptions().use {opts->
                opts.setIntraOpNumThreads(1);opts.setInterOpNumThreads(1)
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                opts.setCPUArenaAllocator(false);opts.setMemoryPatternOptimization(false)
                opts.addConfigEntry("session.intra_op.allow_spinning","0");opts.addConfigEntry("session.inter_op.allow_spinning","0")
                sessions["reference_encoder"]=env.createSession(File(encoderDirectory,"mimi_encoder.onnx").absolutePath,opts)
            }
            check()
            floats(pcm,1,1,pcm.size.toLong()).use {input->
                run("reference_encoder",mapOf("audio" to input)).use {result->
                    val tensor=result.get("conditioning").get() as OnnxTensor
                    require(tensor.info.shape.contentEquals(longArrayOf(1,(frames-1).toLong(),1024)))
                    tensor.floatBuffer.let {b->FloatArray(b.remaining()).also {b.get(it)}}
                }
            }
        }finally{sessions.remove("reference_encoder")?.close()}
        check();require(conditioning.all(Float::isFinite))
        val bos=File(encoderDirectory,"bos-before-voice.f32").readBytes()
        require(bos.size==4096)
        val prompt=FloatArray(frames*1024)
        ByteBuffer.wrap(bos).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(prompt,0,1024)
        conditioning.copyInto(prompt,1024);require(prompt.all(Float::isFinite))
        val zero=linkedMapOf<String,OnnxTensor>()
        try {
            for(layer in 0 until 6){
                for(kv in 0 until 2){check();zero["state_${layer*3+kv}"]=floats(FloatArray(1000*1024),1,1000,16,64)}
                zero["state_${layer*3+2}"]=OnnxTensor.createTensor(env,LongBuffer.wrap(longArrayOf(0)),longArrayOf(1))
            }
            floats(FloatArray(0),1,0,32).use {empty->floats(prompt,1,frames.toLong(),1024).use {embedding->
                run("flow_lm_main",zero+mapOf("sequence" to empty,"text_embeddings" to embedding)).use {result->
                    for(layer in 0 until 6)require((result.get("out_state_${layer*3+2}").get() as OnnxTensor).longBuffer.get(0)==frames.toLong())
                    check()
                    return PocketVoiceState(frames,List(12){index->
                        val key="out_state_${(index/2)*3+index%2}"
                        val b=(result.get(key).get() as OnnxTensor).floatBuffer
                        FloatArray(frames*1024).also {b.get(it)}
                    }).also {it.validate()}
                }
            }}
        }finally{zero.values.forEach {it.close()}}
    }
    private fun run(name:String,inputs:Map<String,OnnxTensor>):OrtSession.Result {
        check();val session=sessions.getValue(name);require(inputs.keys==session.inputNames){"Pocket ONNX input contract changed for $name"}
        OrtSession.RunOptions().use {options->
            synchronized(runLock){check();runOptions=options}
            try {return session.run(inputs,options)}catch(error:Throwable){
                if(cancelled.get())throw CancellationException("Pocket TTS cancelled").also {it.initCause(error)}
                throw error
            }finally{synchronized(runLock){runOptions=null}}
        }
    }
    private inner class State(val name:String,val initial:Map<String,OnnxTensor>):AutoCloseable {
        var result:OrtSession.Result?=null
        fun call(inputs:Map<String,OnnxTensor>):OrtSession.Result {
            val previous=result;val carry=if(previous==null)initial else initial.keys.associateWith {key->previous.get("out_$key").get() as OnnxTensor}
            val next=run(name,inputs+carry);result=next;previous?.close();return next
        }
        override fun close(){result?.close();result=null}
    }
    private fun decoderInitial():Map<String,OnnxTensor> {
        val values=linkedMapOf<String,OnnxTensor>()
        try {for((name,node) in sessions.getValue("mimi_decoder").inputInfo){if(name=="latent")continue
            val info=node.info as TensorInfo;val shape=info.shape;require(shape.all {it>=0})
            val count=shape.fold(1L){a,b->Math.multiplyExact(a,b)}.toInt()
            values[name]=when(info.type){OnnxJavaType.FLOAT->floats(FloatArray(count),*shape);OnnxJavaType.INT64->OnnxTensor.createTensor(env,LongBuffer.wrap(LongArray(count)),shape);OnnxJavaType.BOOL->OnnxTensor.createTensor(env,ByteBuffer.allocateDirect(count).put(ByteArray(count){1}).also {it.flip()},shape,OnnxJavaType.BOOL);else->error("Unexpected decoder state ${info.type}")}
        };return values}catch(e:Throwable){values.values.forEach {it.close()};throw e}
    }
    override fun generate(source:String,receive:(FloatArray)->Boolean) {
        require(source.length<=1000){"Pocket input exceeds 1000 characters; split the text"}
        require(source.none {it=='\u0000'}){"Pocket input contains NUL"}
        check();val started=System.nanoTime();val chunks=text.chunks(source);val records=JSONArray();var noiseRow=0;var frames=0;var samples=0L
        lastResult=JSONObject().put("seed",0).put("noise_source","fixed stock Torch seed0 normal draws, std=sqrt(0.3)").put("chunks",records)
        try {for(chunk in chunks){
            check();val record=JSONObject().put("text",chunk.text).put("token_ids",JSONArray(chunk.tokens.toList())).put("frames_after_eos",chunk.framesAfterEos).put("max_frames",chunk.maxFrames);records.put(record)
            val decInitial=decoderInitial()
            try {State("flow_lm_main",voice).use {main->State("mimi_decoder",decInitial).use {decoder->
                OnnxTensor.createTensor(env,LongBuffer.wrap(chunk.tokens),longArrayOf(1,chunk.tokens.size.toLong())).use {tokens->
                    run("text_conditioner",mapOf("token_ids" to tokens)).use {embedded->
                        floats(FloatArray(0),1,0,32).use {empty->main.call(mapOf("sequence" to empty,"text_embeddings" to (embedded.get("embeddings").get() as OnnxTensor)))}
                    }
                }
                noiseRow++ // Stock text prefill samples an unused flow output.
                var previous=FloatArray(32){Float.NaN};var eosAt:Int?=null;var ended=false
                val empty=floats(FloatArray(0),1,0,1024)
                try {for(step in 0 until chunk.maxFrames){
                    check();require(frames<375){"Pocket output reached 30-second cap before EOS"};require(noiseRow*32+32<=noise.size){"Noise sequence exhausted"}
                    val output=floats(previous,1,1,32).use {sequence->main.call(mapOf("sequence" to sequence,"text_embeddings" to empty))}
                    val eos=(output.get("eos_logit").get() as OnnxTensor).floatBuffer.get(0)
                    require(eos.isFinite()){"Pocket EOS contains nonfinite value"}
                    val cond=output.get("conditioning").get() as OnnxTensor
                    val x=noise.copyOfRange(noiseRow*32,(noiseRow+1)*32);noiseRow++
                    floats(x,1,32).use {xt->floats(floatArrayOf(0f),1,1).use {s->floats(floatArrayOf(1f),1,1).use {t->
                        run("flow_lm_flow",mapOf("c" to cond,"s" to s,"t" to t,"x" to xt)).use {flow->val b=(flow.get(0) as OnnxTensor).floatBuffer;previous=FloatArray(32){x[it]+b.get(it)}}
                    }}}
                    require(previous.all {it.isFinite()}){"Pocket latent contains nonfinite values"}
                    if(eos>-4&&eosAt==null&&step>=6)eosAt=step
                    if(eosAt!=null&&step>=eosAt!!+chunk.framesAfterEos){ended=true;break}
                    val decoded=floats(previous,1,1,32).use {latent->decoder.call(mapOf("latent" to latent))}
                    val pcm=(decoded.get("audio_frame").get() as OnnxTensor).floatBuffer.let {b->FloatArray(b.remaining()).also {b.get(it)}}
                    require(pcm.size==1920&&pcm.all {it.isFinite()}){"Pocket decoder returned invalid PCM"}
                    if(!receive(pcm))throw CancellationException("Playback cancelled")
                    frames++;samples+=pcm.size
                }}finally{empty.close()}
                require(ended){"Pocket generation reached model length limit without EOS"}
                record.put("eos_step",eosAt).put("complete",true)
            }}}finally{decInitial.values.forEach {it.close()}}
        }
        lastResult.put("status","complete").put("frames",frames).put("samples",samples).put("sample_rate",24000).put("generation_ms",(System.nanoTime()-started)/1e6)
        }catch(e:Throwable){lastResult.put("status","failed").put("error",e.toString());throw e}
        finally{active={}}
    }
    override fun close(){cancel();if(tokenizer!=0L){PocketTokenizer.close(tokenizer);tokenizer=0};voice.values.forEach {it.close()};voice.clear();sessions.values.forEach {it.close()};sessions.clear()}
}
