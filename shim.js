// maid-brain shim —— TLM(OpenAI兼容) ⇄ 本会话agent 的文件邮箱桥
// 监听 127.0.0.1:4315(player2站点默认指向) 与 127.0.0.1:7898(备用openai站点)
// POST */chat/completions 的请求落盘 mailbox/requests/<id>.json，
// 等 mailbox/responses/<id>.json 出现后原样回传（支持 stream=true 的 SSE 包装）。
const http = require('http');
const fs = require('fs');
const path = require('path');
const st = require('stream');

const MB = path.join(__dirname, 'mailbox');
const REQ = path.join(MB, 'requests');
const RES = path.join(MB, 'responses');
for (const d of [MB, REQ, RES]) fs.mkdirSync(d, { recursive: true });

const PORTS = [4315, 7898];
const WAIT_MS = 120000;
const POLL_MS = 400;

function log(...a) { console.log(new Date().toISOString().slice(11, 19), ...a); }

function openaiText(content) {
  return {
    id: 'shim-' + Date.now().toString(36), object: 'chat.completion',
    created: Math.floor(Date.now() / 1000), model: 'maid-brain',
    choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }]
  };
}
const sseWrap = obj => 'data: ' + JSON.stringify(obj) + '\n\ndata: [DONE]\n\n';

function handler(req, res) {
  const u = req.url || '';
  if (req.method === 'GET') {
    res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' });
    res.end('maid-brain shim alive. POST */chat/completions');
    return;
  }
  // 语音识别（STT）空实现：让 TLM 的语音流程不至于报 404；本 shim 只做文本大脑
  if (/\/stt\/start$/.test(u)) {
    log('STT start -> ok');
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok' }));
    return;
  }
  if (/\/stt\/stop$/.test(u)) {
    log('STT stop -> empty text');
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ text: '' }));
    return;
  }
  if (req.method !== 'POST' || !/chat\/completions$/.test(u)) {
    log(`404 ${req.method} ${u}`);
    res.writeHead(404, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ error: { message: 'only POST */chat/completions' } }));
    return;
  }
  const chunks = [];
  req.on('data', c => chunks.push(c));
  req.on('end', () => {
    let body;
    try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
    catch { res.writeHead(400); res.end('bad json'); return; }

    const id = Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 7);
    const streaming = body.stream === true;
    const nmsgs = Array.isArray(body.messages) ? body.messages.length : 0;
    const ntools = Array.isArray(body.tools) ? body.tools.length : 0;
    const lastUser = Array.isArray(body.messages) ? [...body.messages].reverse().find(m => m.role === 'user') : null;
    const preview = lastUser && typeof lastUser.content === 'string' ? lastUser.content.slice(0, 100) : '(complex)';
    const reqFile = path.join(REQ, id + '.json');
    fs.writeFileSync(reqFile,
      JSON.stringify({ id, ts: Date.now(), port: req.socket.localPort, url: req.url, streaming, body }, null, 1));
    log(`REQ ${id} port=${req.socket.localPort} msgs=${nmsgs} tools=${ntools} stream=${streaming} user="${preview}"`);

    const t0 = Date.now();
    let gave = false;
    const finish = (jsonStr) => {
      if (gave) return; gave = true;
      log(`RES ${id} ${((Date.now() - t0) / 1000).toFixed(1)}s bytes=${jsonStr.length}`);
      reply(res, 200, jsonStr, streaming);
    };
    const timer = setInterval(() => {
      if (gave) { clearInterval(timer); return; }
      const resFile = path.join(RES, id + '.json');
      if (!fs.existsSync(resFile)) {
        if (Date.now() - t0 > WAIT_MS) {
          clearInterval(timer); fs.rmSync(reqFile, { force: true });
          log(`TIMEOUT ${id}`);
          finish(JSON.stringify(openaiText('（女仆走神了一下，请再叫她一次）')));
        }
        return;
      }
      let out = null;
      try {
        out = fs.readFileSync(resFile, 'utf8');
        JSON.parse(out); // 只有完整且合法的 JSON 才消费，避免读到写一半的文件
      } catch { out = null; }
      if (out === null) {
        if (Date.now() - t0 > WAIT_MS) {
          clearInterval(timer); fs.rmSync(reqFile, { force: true });
          log(`BADRESP ${id}`);
          finish(JSON.stringify(openaiText('（大脑回信格式坏了）')));
        }
        return;
      }
      clearInterval(timer);
      try { fs.unlinkSync(resFile); } catch {}
      fs.rmSync(reqFile, { force: true });
      finish(out);
    }, POLL_MS);
  });
}

function reply(res, status, jsonStr, streaming) {
  if (streaming) {
    res.writeHead(status, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' });
    res.end(sseWrap(JSON.parse(jsonStr)));
  } else {
    res.writeHead(status, { 'content-type': 'application/json' });
    res.end(jsonStr);
  }
}

for (const p of PORTS) {
  http.createServer(handler).on('error', e => log(`PORT ${p} 不可用: ${e.code}`)).listen(p, '127.0.0.1', () => log(`listening 127.0.0.1:${p}`));
}
log('maid-brain shim 启动, 邮箱:', MB);
