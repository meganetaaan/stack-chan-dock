import { spawn } from 'node:child_process';
import { readFile, writeFile, mkdtemp, unlink, rmdir, mkdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { performance } from 'node:perf_hooks';
import { chat } from './local-chat.mjs';
import { MODEL_NAME,MODEL_SHA256 } from './config.mjs';

export const root=path.dirname(fileURLToPath(import.meta.url));

export class VoiceSession {
  constructor(config,{say=console.log}={}) {
    this.config=config;this.base=config.endpoint;this.history=[];
    this.say=say;this.controller=new AbortController();this.children=new Set();
    this.server=null;this.serverExit=null;this.report={microphoneStarted:false,playbackStarted:false};
  }
  get signal(){return this.controller.signal;}
  get targetArgs(){return ['--port',this.config.device.port,'--serial-number',this.config.device.serialNumber];}
  cancel(){this.controller.abort();}
  async run(command,args,{cooperative=false,onReady,timeout=60000}={}) {
    this.signal.throwIfAborted();
    return await new Promise((resolve,reject)=>{
      const child=spawn(command,args,{cwd:root,windowsHide:true,stdio:['pipe','pipe','pipe']});
      this.children.add(child);
      let stdout='',stderr='',cancelTimer,readySent=false,stopping=false,timedOut=false;
      child.stdout.setEncoding('utf8');child.stderr.setEncoding('utf8');
      child.stdout.on('data',x=>stdout+=x);
      child.stderr.on('data',x=>{
        stderr+=x;
        if(onReady && !readySent && stderr.includes('MIC_READY')){readySent=true;onReady();}
      });
      child.stdin.on('error',()=>{});
      const stop=()=>{
        if(stopping)return;
        stopping=true;
        if(cooperative){
          child.stdin.end('cancel\n');
          cancelTimer=setTimeout(()=>child.kill(),12000);
        }else child.kill();
      };
      this.signal.addEventListener('abort',stop,{once:true});
      const timer=setTimeout(()=>{timedOut=true;stop();},timeout);
      const cleanup=()=>{
        clearTimeout(timer);clearTimeout(cancelTimer);
        this.signal.removeEventListener('abort',stop);this.children.delete(child);
      };
      child.once('error',error=>{cleanup();reject(error);});
      child.once('close',code=>{
        cleanup();
        if(this.signal.aborted)reject(new DOMException('Stopped','AbortError'));
        else if(timedOut)reject(new Error('子プロセスがタイムアウトしたため停止しました。'));
        else if(code!==0)reject(new Error(stderr.replace(/MIC_READY\r?\n/g,'').trim()||stdout.trim()||'Child process failed'));
        else resolve(stdout.trim());
      });
    });
  }
  async json(route) {
    if(!route.startsWith('/')||route.startsWith('//'))throw new Error('Local API path required');
    const url=this.base+route;
    const response=await fetch(url,{redirect:'error',signal:AbortSignal.any([this.signal,AbortSignal.timeout(2000)])});
    if(!response.ok)throw new Error('Local server HTTP '+response.status);
    return await response.json();
  }
  async verifyServerModel() {
    const models=await this.json('/v1/models');
    const ids=(models.data??[]).map(x=>x.id);
    if(!ids.some(id=>typeof id==='string' && id.split(/[\\/]/).at(-1)===MODEL_NAME))
      throw new Error('既存サーバーは対象Qwenモデルと確認できません。既存サーバーは変更していません。');
    this.report.serverModels=ids;
  }
  async ensureServer() {
    let health=null;
    try{health=await this.json('/health');}catch(error){if(this.signal.aborted)throw error;}
    if(health?.status==='ok'){
      await this.verifyServerModel();
      this.report.serverOwned=false;this.report.serverReused=true;
      this.say('ローカルLLM: 既存の対象Qwenサーバーを再利用します。');return;
    }
    try {
      await new Promise((resolve,reject)=>{
        const probe=createServer();probe.once('error',reject);
        const url=new URL(this.base);
        probe.listen(Number(url.port),url.hostname.replace(/[\[\]]/g,''),()=>probe.close(resolve));
      });
    }catch{
      throw new Error('LLMポートは使用中ですが対象モデルのhealth確認ができません。使用中のサーバーは停止しません。しばらく待って再実行してください。');
    }
    this.signal.throwIfAborted();
    this.say('ローカルLLM: CPUサーバーを起動しています…');
    const started=performance.now();
    const url=new URL(this.base);
    const args=['--model',this.config.modelPath,'--device','none','--n-gpu-layers','0','--no-op-offload','--ctx-size','2048','--threads','8','--threads-batch','8','--parallel','1','--jinja','--chat-template-kwargs','{"enable_thinking":false}','--reasoning','off','--host',url.hostname.replace(/[\[\]]/g,''),'--port',url.port,'--no-webui','--no-webui-mcp-proxy'];
    this.server=spawn(this.config.runtimePath,args,{windowsHide:true,stdio:['ignore','ignore','ignore']});
    let ended=false,spawnError;
    this.server.once('error',error=>{spawnError=error;ended=true;});
    this.serverExit=new Promise(resolve=>this.server.once('close',()=>{ended=true;resolve();}));
    this.report.serverOwned=true;this.report.serverReused=false;
    while(performance.now()-started<30000){
      this.signal.throwIfAborted();
      if(ended)throw spawnError??new Error('CPUサーバーが起動中に終了しました。');
      try{health=await this.json('/health');}catch(error){if(this.signal.aborted)throw error;}
      if(health?.status==='ok')break;
      await new Promise(resolve=>setTimeout(resolve,100));
    }
    if(health?.status!=='ok')throw new Error('ローカルLLMのhealth確認がタイムアウトしました。');
    await this.verifyServerModel();
    this.report.serverStartupMs=performance.now()-started;
  }
  async prepare({hostOnly=false}={}) {
    await mkdir(this.config.stateDirectory,{recursive:true});
    this.say('実機と日本語音声機能を確認しています。録音はまだ始まりません。');
    if(hostOnly)this.report.deviceCheckSkipped=true;
    else this.report.device=JSON.parse(await this.run(this.config.python,[path.join(root,'device_check.py'),...this.targetArgs]));
    this.report.speech=JSON.parse(await this.run(this.config.powershell,['-NoProfile','-ExecutionPolicy','Bypass','-File',path.join(root,'Speech-File.ps1'),'-Mode','check']));
    this.signal.throwIfAborted();
    const bytes=await readFile(this.config.modelPath);
    if(createHash('sha256').update(bytes).digest('hex')!==MODEL_SHA256)throw new Error('モデルSHA256が一致しません。');
    const executable=await readFile(this.config.runtimePath);
    if(createHash('sha256').update(executable).digest('hex')!==this.config.runtimeSha256)throw new Error('llama-server SHA256が一致しません。');
    this.report.runtimeHashVerified=true;
    this.report.modelHashVerified=true;
    await this.ensureServer();
    this.report.health='ok';this.report.endpoint=this.base;
    this.say(hostOnly?'ホスト検証完了: ローカルLLM、日本語ASR、Haruka。USBは開いていません。':`準備完了: ${this.config.device.port} / CDC v2、ローカルLLM、日本語ASR、Haruka。`);
  }
  async turn() {
    this.signal.throwIfAborted();
    const directory=await mkdtemp(path.join(tmpdir(),'stackchan-local-voice-'));
    const input=path.join(directory,'input.wav');
    const textPath=path.join(directory,'response.txt');
    const wavePath=path.join(directory,'response.wav');
    const report={date:new Date().toISOString(),status:'running',externalAudioSent:false,ambientAudioRetained:false};
    try {
      report.microphone=JSON.parse(await this.run(this.config.python,[path.join(root,'record_wave.py'),'3',input,...this.targetArgs],{cooperative:true,timeout:15000,onReady:()=>{
        this.report.microphoneStarted=true;this.say('● 録音中（3秒）: 今、短い日本語で話してください。');
      }}));
      this.say('録音終了。認識しています…');
      report.asr=JSON.parse(await this.run(this.config.powershell,['-NoProfile','-ExecutionPolicy','Bypass','-File',path.join(root,'Speech-File.ps1'),'-Mode','asr','-InputPath',input]));
      this.say('認識: '+report.asr.text);
      this.say('返答を考えています…');
      const userMessage={role:'user',content:report.asr.text};
      report.llm=await chat([{role:'system',content:'あなたはスタックチャンです。日本語の短い一文、40文字以内で返事してください。'},...this.history,userMessage],{endpoint:this.base+'/v1/chat/completions',maxTokens:64,signal:this.signal});
      if(report.llm.reasoning || /<think>/.test(report.llm.content))throw new Error('LLMの推論テキストが返りました。enable_thinking=falseの設定を確認してください。');
      if(!report.llm.content.trim()||report.llm.finishReason!=='stop'||report.llm.content.length>100)throw new Error('返答が長すぎるか未完了です。短い質問で再試行してください。');
      this.say('返答: '+report.llm.content);
      await writeFile(textPath,report.llm.content,'utf8');
      report.tts=JSON.parse(await this.run(this.config.powershell,['-NoProfile','-ExecutionPolicy','Bypass','-File',path.join(root,'Speech-File.ps1'),'-Mode','tts','-InputPath',textPath,'-OutputPath',wavePath]));
      this.signal.throwIfAborted();
      this.say('スタックチャンから再生しています（音量10%）…');
      this.report.playbackStarted=true;
      report.device=JSON.parse(await this.run(this.config.python,[path.join(root,'play_wave.py'),wavePath,...this.targetArgs],{cooperative:true,timeout:30000}));
      if(!report.device.playback.doneAcknowledged)throw new Error('再生完了が確認できませんでした。');
      this.history=[...this.history,userMessage,{role:'assistant',content:report.llm.content}].slice(-6);
      report.status='passed';this.say('再生完了。実際に聞こえたか確認してください。');
      return report;
    }catch(error){report.status=this.signal.aborted?'cancelled':'failed';report.error=error.message;throw error;}
    finally {
      for(const file of [input,textPath,wavePath])await unlink(file).catch(error=>{if(error.code!=='ENOENT')throw error;});
      await rmdir(directory);
      await writeFile(path.join(this.config.stateDirectory,'last-user-trial.json'),JSON.stringify(report,null,2)+'\n');
    }
  }
  async close() {
    this.cancel();
    // run() waits for each cooperative audio child to stop and release its port.
    while(this.children.size)await new Promise(resolve=>setTimeout(resolve,50));
    if(this.server){this.server.kill();await this.serverExit;this.report.ownedServerStopped=true;}
    else this.report.existingServerStopped=false;
  }
}
