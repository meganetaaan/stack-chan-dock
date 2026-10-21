import test from 'node:test';
import assert from 'node:assert/strict';
import { chat } from '../src/local-chat.mjs';
import { createServer } from 'node:http';
const messages = [{role:'user',content:'テスト'}];
test('reject external host and credentials before sending', async t => {
  t.mock.method(globalThis,'fetch',()=>{throw new Error('must not fetch');});
  await assert.rejects(chat(messages,{endpoint:'https://example.com/v1/chat/completions'}),/Local HTTP/);
  await assert.rejects(chat(messages,{endpoint:'http://user:secret@localhost:8081/v1/chat/completions'}),/Local HTTP/);
});
test('SSE preserves Japanese UTF-8 across byte boundaries and collects timing/usage', async t => {
  const text = 'data: '+JSON.stringify({choices:[{delta:{content:'こんにちは'}}]})+'\r\n\r\n'+'data: '+JSON.stringify({choices:[{delta:{},finish_reason:'stop'}],usage:{completion_tokens:2},timings:{predicted_n:2,predicted_per_second:60}})+'\n\n'+'data: [DONE]\n\n';
  const bytes = new TextEncoder().encode(text);
  t.mock.method(globalThis,'fetch', async (_, options) => {
    assert.equal(options.redirect,'error');
    const request = JSON.parse(options.body);
    assert.equal(request.chat_template_kwargs.enable_thinking,false);
    return new Response(new ReadableStream({start(controller){for(const byte of bytes)controller.enqueue(Uint8Array.of(byte));controller.close();}}));
  });
  const pieces = [];
  const result = await chat(messages,{onText:x=>pieces.push(x)});
  assert.equal(result.content,'こんにちは');
  assert.deepEqual(pieces,['こんにちは']);
  assert.equal(result.finishReason,'stop');
  assert.equal(result.outputTokens,2);
  assert.equal(result.timings.predicted_per_second,60);
  assert.ok(result.firstContentMs>=0);
});
test('nonstream response exposes output and server metrics without invented first-token timing',async t=>{
  t.mock.method(globalThis,'fetch',async()=>Response.json({choices:[{message:{content:'おやすみ'},finish_reason:'stop'}],usage:{completion_tokens:3},timings:{predicted_n:3,predicted_per_second:61}}));
  const result=await chat(messages,{stream:false});
  assert.equal(result.content,'おやすみ'); assert.equal(result.firstContentMs,null); assert.equal(result.outputTokens,3);
});
test('truncated SSE is reported as an error',async t=>{
  t.mock.method(globalThis,'fetch',async()=>new Response('data: {"choices":[{"delta":{"content":"途中"}}]}\n\n'));
  await assert.rejects(chat(messages),/without \[DONE\]/);
});
test('server failures are surfaced',async t=>{
  t.mock.method(globalThis,'fetch',async()=>new Response('model unavailable',{status:503}));
  await assert.rejects(chat(messages),/HTTP 503: model unavailable/);
});
test('caller cancellation closes an actual localhost SSE request',{timeout:3000},async()=>{
  const controller=new AbortController();let markClosed;
  const closed=new Promise(resolve=>{markClosed=resolve;});
  const server=createServer((request,response)=>{
    response.writeHead(200,{'Content-Type':'text/event-stream'});
    response.on('close',markClosed);
    response.write('data: '+JSON.stringify({choices:[{delta:{content:'こんにちは'}}]})+'\n\n');
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try{
    await assert.rejects(chat(messages,{endpoint:`http://127.0.0.1:${server.address().port}/v1/chat/completions`,signal:controller.signal,onText:()=>controller.abort()}),{name:'AbortError'});
    await closed;
  }finally{
    server.closeAllConnections();await new Promise(resolve=>server.close(resolve));
  }
});
