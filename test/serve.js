'use strict';
// serve.js — 前端静态服务器（Node，零依赖，替代 serve.ps1）
// 用法: node serve.js [port] [root]
//   默认端口 12789（勿用 12788：曾有 http.sys 残留监听返回空白页），root = 脚本所在目录（test/）
//   监听所有网卡（0.0.0.0）→ 局域网内多台设备可同时访问（每个浏览器独立界面）
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');

const port = parseInt(process.argv[2], 10) || 12789;
const root = path.resolve(process.argv[3] || __dirname);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.txt': 'text/plain; charset=utf-8',
  '.md': 'text/plain; charset=utf-8'
};

const server = http.createServer((req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  try {
    let p = decodeURIComponent((req.url || '/').split('?')[0]);
    if (p === '/') p = '/index.html';
    const full = path.resolve(root, '.' + p);
    // 路径穿越防护
    if (full !== root && !full.startsWith(root + path.sep)) {
      res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('forbidden');
      return;
    }
    if (fs.existsSync(full) && fs.statSync(full).isFile()) {
      const ext = path.extname(full).toLowerCase();
      res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
      fs.createReadStream(full).pipe(res);
    } else {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('not found: ' + p);
    }
  } catch (e) {
    res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end(String(e));
  }
});

server.on('error', (e) => {
  console.error('[serve] 启动失败: ' + e.message);
  process.exit(1);
});

// 监听所有网卡：本机 + 局域网设备均可访问（每个浏览器独立界面，共享无状态仿真内核）
server.listen(port, () => {
  console.log('[serve] http://127.0.0.1:' + port + '/  root=' + root);
  try {
    const nets = os.networkInterfaces();
    for (const name of Object.keys(nets)) {
      for (const ni of nets[name] || []) {
        if (ni.family === 'IPv4' && !ni.internal) {
          console.log('[serve] 局域网访问: http://' + ni.address + ':' + port + '/  （多用户，每端独立界面）');
        }
      }
    }
  } catch (e) { /* ignore */ }
});
