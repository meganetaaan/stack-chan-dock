import { performance } from 'node:perf_hooks';
export async function chat(messages, { endpoint = 'http://127.0.0.1:8081/v1/chat/completions', stream = true, onText = () => {}, maxTokens = 128, signal } = {}) {
  const u = new URL(endpoint);
  if (u.protocol !== 'http:' || !['127.0.0.1','[::1]','localhost'].includes(u.hostname) || u.username || u.password) throw new Error('Local HTTP endpoint required');
  const request = { model: 'local', messages, temperature: 0, top_k: 20, top_p: 1, presence_penalty: 2, repeat_penalty: 1.1, max_tokens: maxTokens, stream, chat_template_kwargs: { enable_thinking: false }, ...(stream ? { stream_options: { include_usage: true } } : {}) };
  const started = performance.now();
  const response = await fetch(u, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(request), signal: signal ? AbortSignal.any([signal,AbortSignal.timeout(120000)]) : AbortSignal.timeout(120000), redirect: 'error' });
  if (!response.ok) throw new Error('HTTP ' + response.status + ': ' + await response.text());
  let content = '', firstContentMs = null, usage = null, timings = null, finishReason = null, done = false, contentEvents = 0, reasoning = '';
  if (!stream) {
    const data = await response.json();
    content = data.choices[0].message.content ?? '';
    reasoning = data.choices[0].message.reasoning_content ?? '';
    finishReason = data.choices[0].finish_reason;
    usage = data.usage;
    timings = data.timings ?? null;
    onText(content);
  } else {
    const decoder = new TextDecoder();
    let pending = '';
    const processLine = line => {
      if (!line.startsWith('data:')) return;
      const payload = line.slice(5).trim();
      if (!payload) return;
      if (payload === '[DONE]') { done = true; return; }
      const data = JSON.parse(payload);
      if (data.error) throw new Error(JSON.stringify(data.error));
      usage = data.usage ?? usage;
      timings = data.timings ?? timings;
      for (const choice of data.choices ?? []) {
        finishReason = choice.finish_reason ?? finishReason;
        reasoning += choice.delta?.reasoning_content ?? '';
        const piece = choice.delta?.content;
        if (typeof piece === 'string' && piece.length) {
          firstContentMs ??= performance.now() - started;
          content += piece; contentEvents++; onText(piece);
        }
      }
    };
    for await (const chunk of response.body) {
      pending += decoder.decode(chunk, { stream: true });
      let newline;
      while ((newline = pending.indexOf('\n')) >= 0) {
        processLine(pending.slice(0,newline).replace(/\r$/, ''));
        pending = pending.slice(newline + 1);
      }
    }
    pending += decoder.decode();
    if (pending.trim()) processLine(pending.replace(/\r$/, ''));
    if (!done) throw new Error('SSE ended without [DONE]');
  }
  const totalMs = performance.now() - started;
  const outputTokens = usage?.completion_tokens ?? timings?.predicted_n ?? null;
  return { content, reasoning, stream, firstContentMs, totalMs, contentEvents, finishReason, usage, timings, outputTokens, wallOutputTokensPerSecond: outputTokens == null ? null : outputTokens * 1000 / totalMs };
}
