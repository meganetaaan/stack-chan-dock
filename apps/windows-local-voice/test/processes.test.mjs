import test from 'node:test';
import assert from 'node:assert/strict';
import {VoiceSession} from '../src/session.mjs';
import {fixtureConfig} from './fixtures.mjs';
test('audio cancellation listener does not block normal Python child exit',{timeout:5000},async()=>{
  const session=new VoiceSession(fixtureConfig(),{say:()=>{}});
  try{
    const result=await session.run(session.config.python,['-c','from cancel_signal import cancellation_event; cancellation_event(); print("OK",flush=True)'],{cooperative:true});
    assert.equal(result,'OK');assert.equal(session.children.size,0);
  }finally{await session.close();}
});
test('owning session sends cooperative cancellation and awaits child release',{timeout:5000},async()=>{
  const session=new VoiceSession(fixtureConfig(),{say:()=>{}});
  try{
    const pending=session.run(session.config.python,['-c','import sys; from cancel_signal import cancellation_event; event=cancellation_event(); print("MIC_READY",file=sys.stderr,flush=True); event.wait(); print("stopped",flush=True)'],{cooperative:true,onReady:()=>session.cancel()});
    await assert.rejects(pending,{name:'AbortError'});
    assert.equal(session.children.size,0);
  }finally{await session.close();}
});
test('deadline reports timeout after cooperative child release',{timeout:5000},async()=>{
  const session=new VoiceSession(fixtureConfig(),{say:()=>{}});
  try{
    await assert.rejects(session.run(session.config.python,['-c','from cancel_signal import cancellation_event; event=cancellation_event(); event.wait(); print("stopped",flush=True)'],{cooperative:true,timeout:200}),/タイムアウト/);
    assert.equal(session.children.size,0);
  }finally{await session.close();}
});
