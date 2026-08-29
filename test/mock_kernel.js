'use strict';
// mock_kernel.js — 模拟 MC 内核 HTTP 接口（仅用于本地测试 start.bat / 前端内核模式）
// 用法: node test/mock_kernel.js [port]
const http = require('http');
const port = parseInt(process.argv[2], 10) || 12787;

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' });
  if (req.url.startsWith('/health')) {
    res.end(JSON.stringify({ ok: true, mod: 'Cryptand', kernel: 'MOCK(本地测试)' }));
  } else if (req.url.startsWith('/simulate')) {
    let body = '';
    req.on('data', d => { body += d; });
    req.on('end', () => {
      try {
        const reqJson = JSON.parse(body || '{}');
        // 模拟：返回 0 电压 + 每个元件 0 电流
        const nc = reqJson.nodeCount || 1;
        const nodeVoltages = new Array(nc).fill(0);
        const components = (reqJson.elements || []).map(e => ({
          id: e.id, i: 0, p: 0, t: 20, energy: 0, stored: 0
        }));
        const wires = (reqJson.elements || []).filter(e => e.kind === 'wire').map(e => ({
          id: e.id, r: e.r, length: e.length || 0, i: 0, p: 0, t: 20,
          energy: 0, burned: false, type: e.type || 'copper', name: e.name || '导线',
          ratedCurrent: e.ratedCurrent || 0, load: 0
        }));
        res.end(JSON.stringify({
          ok: true, solver: 'RealMnaSolver', mode: reqJson.mode || 'dc', converged: true,
          nodeVoltages, components, wires, burnedWires: [],
          transient: { times: [], waveforms: [] }
        }));
      } catch (e) {
        res.end(JSON.stringify({ ok: false, error: String(e) }));
      }
    });
  } else {
    res.end(JSON.stringify({ ok: false, error: 'not found' }));
  }
});

server.listen(port, '127.0.0.1', () => console.log('mock kernel: http://127.0.0.1:' + port));
