import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,unlink,rmdir,writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {VoiceSession} from '../src/session.mjs';
import {MODEL_NAME} from '../src/config.mjs';
import {fixtureConfig} from './fixtures.mjs';
async function removeTestDirectory(directory){await unlink(path.join(directory,'last-user-trial.json')).catch(error=>{if(error.code!=='ENOENT')throw error;});await rmdir(directory);}
test('matching existing server is reused and remains available after session close',async t=>{
  t.mock.method(globalThis,'fetch',async url=>new Response(JSON.stringify(url.endsWith('/health')?{status:'ok'}:{data:[{id:MODEL_NAME}]}),{headers:{'Content-Type':'application/json'}}));
  const session=new VoiceSession(fixtureConfig(),{say:()=>{}});
  await session.ensureServer();assert.equal(session.report.serverReused,true);
  await session.close();assert.equal(session.report.existingServerStopped,false);
  assert.equal((await fetch('http://127.0.0.1:8081/health').then(r=>r.json())).status,'ok');
});
test('wrong existing model is rejected without claiming server ownership',async t=>{
  t.mock.method(globalThis,'fetch',async url=>Response.json(url.endsWith('/health')?{status:'ok'}:{data:[{id:'other.gguf'}]}));
  const session=new VoiceSession(fixtureConfig(),{say:()=>{}});
  await assert.rejects(session.ensureServer(),/Qwen/);assert.equal(session.server,null);await session.close();
});
test('turn keeps bounded completed dialogue history and deletes temporary audio',async t=>{
  const directory=await mkdtemp(path.join(tmpdir(),'dock-turn-test-'));const waveFiles=[],requests=[];
  t.mock.method(globalThis,'fetch',async (_url,options)=>{
    requests.push(JSON.parse(options.body));
    return new Response('data: '+JSON.stringify({choices:[{delta:{content:'こんにちは。'},finish_reason:'stop'}]})+'\n\ndata: [DONE]\n\n');
  });
  class FixtureSession extends VoiceSession {
    async run(_command,args,{onReady}={}){
      const script=path.basename(args[0]??'');
      if(script==='record_wave.py'){onReady();waveFiles.push(args[2]);await writeFile(args[2],'synthetic fixture');return JSON.stringify({microphone:{stopAcknowledged:true}});}
      if(script==='play_wave.py')return JSON.stringify({playback:{doneAcknowledged:true}});
      if(args.includes('asr'))return JSON.stringify({text:'こんにちは',confidence:0.5});
      if(args.includes('tts')){const file=args[args.indexOf('-OutputPath')+1];waveFiles.push(file);await writeFile(file,'synthetic fixture');return '{}';}
      throw new Error('Unexpected child call');
    }
  }
  const session=new FixtureSession(fixtureConfig({stateDirectory:directory}),{say:()=>{}});
  try{
    assert.equal(session.report.microphoneStarted,false);
    for(let i=0;i<4;i++)await session.turn();
    assert.equal(requests[0].messages.length,2);assert.equal(requests[1].messages.length,4);
    assert.equal(requests[3].messages.length,8);assert.equal(session.history.length,6);
    for(const file of waveFiles)await assert.rejects(readFile(file),{code:'ENOENT'});
    assert.equal(JSON.parse(await readFile(path.join(directory,'last-user-trial.json'),'utf8')).status,'passed');
  }finally{await session.close();await removeTestDirectory(directory);}
});
test('cancelled turn does not record its partial answer in dialogue history',async t=>{
  const directory=await mkdtemp(path.join(tmpdir(),'dock-stop-test-'));
  class StoppedSession extends VoiceSession {async run(){this.cancel();throw new DOMException('stopped','AbortError');}}
  const session=new StoppedSession(fixtureConfig({stateDirectory:directory}),{say:()=>{}});
  try{await assert.rejects(session.turn(),{name:'AbortError'});assert.deepEqual(session.history,[]);assert.equal(JSON.parse(await readFile(path.join(directory,'last-user-trial.json'),'utf8')).status,'cancelled');}
  finally{await session.close();await removeTestDirectory(directory);}
});
