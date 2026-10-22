// 本地 mock：模拟 DeepSeek /chat/completions 的 SSE 流与 function calling，用于端到端自测。
const http = require('http');

const PORT = 8787;
const LOG = [];

function toolCallRound(res, base, toolName, argChunks) {
  const delta = (d) => res.write('data: ' + JSON.stringify({
    id: base.id, object: 'chat.completion.chunk', created: base.created, model: base.model,
    choices: [{ index: 0, delta: d, finish_reason: null }]
  }) + '\n\n');

  delta({ role: 'assistant', content: '' });
  res.write('data: ' + JSON.stringify({
    id: base.id, object: 'chat.completion.chunk', created: base.created, model: base.model,
    choices: [{
      index: 0,
      delta: { tool_calls: [{ index: 0, id: 'call_mock_1', type: 'function', function: { name: toolName, arguments: '' } }] },
      finish_reason: null
    }]
  }) + '\n\n');
  for (const chunk of argChunks) {
    delta({ tool_calls: [{ index: 0, function: { arguments: chunk } }] });
  }
  res.write('data: ' + JSON.stringify({
    id: base.id, object: 'chat.completion.chunk', created: base.created, model: base.model,
    choices: [{ index: 0, delta: {}, finish_reason: 'tool_calls' }]
  }) + '\n\n');
  res.write('data: [DONE]\n\n');
  res.end();
}

const ANSWER = [
  '我看到工作区目前是**空的**，没有任何文件。\n\n',
  '可以这样开始：\n\n',
  '```python\n',
  '# qsort.py\n',
  'def qsort(items):\n',
  '    if len(items) <= 1:\n',
  '        return items\n',
  '    pivot = items[len(items) // 2]\n',
  '    left = [x for x in items if x < pivot]\n',
  '    mid = [x for x in items if x == pivot]\n',
  '    right = [x for x in items if x > pivot]\n',
  '    return qsort(left) + mid + qsort(right)\n',
  '```\n\n',
  '- 先跑 `python qsort.py` 验证\n',
  '- 需要我直接写入文件就说一声\n'
];

function answerRound(res, base) {
  let i = 0;
  const tick = () => {
    if (i >= ANSWER.length) {
      res.write('data: ' + JSON.stringify({
        id: base.id, object: 'chat.completion.chunk', created: base.created, model: base.model,
        choices: [{ index: 0, delta: {}, finish_reason: 'stop' }]
      }) + '\n\n');
      res.write('data: [DONE]\n\n');
      res.end();
      return;
    }
    res.write('data: ' + JSON.stringify({
      id: base.id, object: 'chat.completion.chunk', created: base.created, model: base.model,
      choices: [{ index: 0, delta: { content: ANSWER[i] }, finish_reason: null }]
    }) + '\n\n');
    i++;
    setTimeout(tick, 180);
  };
  tick();
}

const server = http.createServer((req, res) => {
  if (req.method === 'POST' && req.url.indexOf('/chat/completions') >= 0) {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      let parsed = {};
      try { parsed = JSON.parse(body); } catch (e) { }
      const msgs = parsed.messages || [];
      const hasTool = msgs.some((m) => m.role === 'tool');
      const hasTools = Array.isArray(parsed.tools) && parsed.tools.length > 0;
      LOG.push({ at: Date.now(), msgs: msgs.length, hasTool, hasTools, model: parsed.model });
      console.log('[req]', 'messages=' + msgs.length, 'tool_result=' + hasTool, 'tools=' + hasTools);

      // 模拟"该渠道不支持工具调用"：带 tools 一律 400，逼客户端降级重试
      const lastUserMsg = msgs.slice().reverse().find((m) => m.role === 'user');
      if (lastUserMsg && /downgrade/i.test(lastUserMsg.content || '') && hasTools) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: { message: 'This model does not support tools', type: 'invalid_request_error' } }));
        return;
      }

      res.writeHead(200, {
        'Content-Type': 'text/event-stream; charset=utf-8',
        'Cache-Control': 'no-cache',
        'Connection': 'keep-alive'
      });
      const base = { id: 'chatcmpl-mock', created: Math.floor(Date.now() / 1000), model: parsed.model || 'deepseek-chat' };
      if (!hasTool && hasTools) {
        const lastUser = msgs.slice().reverse().find((m) => m.role === 'user');
        const wantsShell = lastUser && /shell|命令/i.test(lastUser.content || '');
        if (wantsShell) {
          toolCallRound(res, base, 'run_shell',
            ['{"command":"echo hello-from-agent && pwd && ', 'uname -a && ls -la"}']);
        } else {
          toolCallRound(res, base, 'list_dir', ['{"pa', 'th":"."}']);
        }
      } else {
        answerRound(res, base);
      }
    });
    return;
  }

  if (req.url.indexOf('/models') >= 0) {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      object: 'list',
      data: [
        { id: 'deepseek-chat', object: 'model' },
        { id: 'glm-4-plus', object: 'model' },
        { id: 'qwen-plus', object: 'model' }
      ]
    }));
    return;
  }

  res.writeHead(404, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify({ error: { message: 'not found' } }));
});

server.listen(PORT, '0.0.0.0', () => {
  console.log('mock deepseek listening on http://0.0.0.0:' + PORT);
});
