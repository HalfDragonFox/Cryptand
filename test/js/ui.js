'use strict';
// ui.js — 界面：工具栏、元件库、属性/仿真/结果面板、仿真控制与文件操作

let EDB = null;   // editor
let SIM = null;   // simulator
let simRunning = false;

function buildUI(editor, sim) {
  EDB = editor; SIM = sim;
  buildPalette(editor);
  buildToolbar(editor, sim);
  buildPanels(editor, sim);
  bindStatus(editor, sim);
  editor.on('save', saveCircuit);
  editor.on('open', openCircuit);
  buildSample(editor);
  startKernelStatusWatch();   // 内核状态自动检测（未启动→显示"内核未启动"）
}

// ---------- 内核状态徽标 ----------
let _kernelWatchTimer = null;
let _lastKernelToast = '';

function renderKernelStatus() {
  const el = document.getElementById('kstatus');
  if (!el) return;
  const st = kernelStatus.state;
  if (st === 'online') {
    const info = kernelStatus.info || {};
    el.textContent = '● 内核已连接';
    el.className = 'kstatus online';
    el.title = '内核已连接：' + (info.mod || 'Cryptand') + ' · ' + (info.kernel || '') + '（端口 ' + (info.port != null ? info.port : '?') + '）';
  } else if (st === 'checking') {
    el.textContent = '● 检测中…';
    el.className = 'kstatus checking';
  } else {
    el.textContent = '● 内核未启动';
    el.className = 'kstatus offline';
    el.title = '内核未启动：请运行 test\\start.bat（自动启动内核），或先启动内核后刷新页面';
  }
  // 状态切换时只提示一次，避免每 3 秒刷屏
  if (st === 'offline' && _lastKernelToast !== 'offline') {
    _lastKernelToast = 'offline';
    setStatus('内核未启动：请运行 test\\start.bat 启动内核（内核起来后本状态自动更新，无需刷新）');
  } else if (st === 'online' && _lastKernelToast !== 'online') {
    _lastKernelToast = 'online';
    const info = kernelStatus.info || {};
    setStatus('内核已连接：' + (info.mod || 'Cryptand') + ' · ' + (info.kernel || ''));
  }
}

function startKernelStatusWatch() {
  kernelStatus.on(renderKernelStatus);
  renderKernelStatus();      // 立即显示"检测中…"
  checkKernelStatus();       // 首次检测
  clearInterval(_kernelWatchTimer);
  _kernelWatchTimer = setInterval(checkKernelStatus, 3000);   // 每 3 秒刷新
}

// ---------- 提示 ----------
let toastTimer = null;
function toast(msg, ms = 1800) {
  const el = document.getElementById('toast');
  if (!el) return;
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), ms);
}

function setStatus(m) {
  const msg = document.getElementById('status-msg');
  if (msg) msg.textContent = m;
  const st = document.getElementById('sim-status');
  if (st) st.textContent = m;
  if (window.TB) {
    window.TB.run.disabled = simRunning;
    window.TB.step.disabled = simRunning;
    window.TB.dc.disabled = simRunning;
    window.TB.stop.disabled = !simRunning;
  }
}

// ---------- 移动端抽屉（窄屏下元件库/面板变抽屉） ----------
function mobileOverlay() {
  let o = document.getElementById('mobile-overlay');
  if (!o) {
    o = document.createElement('div');
    o.id = 'mobile-overlay';
    o.className = 'mobile-overlay';
    o.addEventListener('click', closeMobilePanels);
    document.body.appendChild(o);
  }
  return o;
}
function togglePalette(force) {
  const p = document.getElementById('palette');
  if (!p) return;
  const open = force !== undefined ? force : !p.classList.contains('mobile-open');
  p.classList.toggle('mobile-open', open);
  mobileOverlay().classList.toggle('show', open);
}
function toggleSide(force) {
  const s = document.getElementById('side');
  if (!s) return;
  const open = force !== undefined ? force : !s.classList.contains('mobile-open');
  s.classList.toggle('mobile-open', open);
  mobileOverlay().classList.toggle('show', open);
}
function closeMobilePanels() {
  const p = document.getElementById('palette'), s = document.getElementById('side');
  if (p) p.classList.remove('mobile-open');
  if (s) s.classList.remove('mobile-open');
  mobileOverlay().classList.remove('show');
}

// ---------- 左侧元件库 ----------
function buildPalette(editor) {
  const list = document.getElementById('palette-list');
  for (const group of COMPONENT_GROUPS) {
    const g = document.createElement('div');
    g.className = 'pal-group';
    const h = document.createElement('div');
    h.className = 'pal-title';
    h.textContent = group;
    g.appendChild(h);
    const body = document.createElement('div');
    body.className = 'pal-body';
    for (const [type, def] of Object.entries(COMPONENT_DEFS)) {
      if (def.group !== group) continue;
      const it = document.createElement('div');
      it.className = 'pal-item';
      it.dataset.type = type;
      it.appendChild(makeIcon(type));
      const name = document.createElement('span');
      name.className = 'pal-name';
      name.textContent = def.name;
      it.appendChild(name);
      it.addEventListener('click', () => {
        editor.setMode(type);
        highlightPalette(editor.mode);
        // 移动端：选完元件后收起元件库抽屉，露出画布放置
        if (window.matchMedia && window.matchMedia('(max-width:820px)').matches) togglePalette(false);
      });
      body.appendChild(it);
    }
    g.appendChild(body);
    list.appendChild(g);
  }
}

function highlightPalette(mode) {
  document.querySelectorAll('.pal-item').forEach(el =>
    el.classList.toggle('active', el.dataset.type === mode));
}

function makeIcon(type) {
  const cv = document.createElement('canvas');
  cv.width = 48; cv.height = 28;
  cv.className = 'pal-icon';
  const ctx = cv.getContext('2d');
  ctx.strokeStyle = '#8ab4f8';
  ctx.fillStyle = '#8ab4f8';
  ctx.lineWidth = 1.6;
  ctx.lineJoin = 'round';
  ctx.lineCap = 'round';
  ctx.translate(24, 14);
  ctx.scale(0.42, 0.42);
  const def = COMPONENT_DEFS[type];
  def.draw(ctx, { params: Object.assign({}, def.params), rotation: 0 }, def);
  return cv;
}

// ---------- 顶部工具栏 ----------
function buildToolbar(editor, sim) {
  const bar = document.getElementById('toolbar');
  const TB = {};
  // 移动端：元件库 / 面板 抽屉开关（桌面隐藏）
  const mPal = document.createElement('button');
  mPal.className = 'tb-btn mobile-only';
  mPal.textContent = '☰';
  mPal.title = '元件库';
  mPal.addEventListener('click', () => { togglePalette(); toggleSide(false); });
  bar.appendChild(mPal);
  const mSide = document.createElement('button');
  mSide.className = 'tb-btn mobile-only';
  mSide.textContent = '面板';
  mSide.title = '属性/仿真/结果 面板';
  mSide.addEventListener('click', () => { toggleSide(); togglePalette(false); });
  bar.appendChild(mSide);
  const group = (label) => {
    const g = document.createElement('div');
    g.className = 'tb-group';
    if (label) {
      const t = document.createElement('span');
      t.className = 'tb-label';
      t.textContent = label;
      g.appendChild(t);
    }
    bar.appendChild(g);
    return g;
  };
  const btn = (g, text, title, fn, id) => {
    const b = document.createElement('button');
    b.className = 'tb-btn';
    b.textContent = text;
    b.title = title || text;
    if (id) b.id = id;
    b.addEventListener('click', fn);
    g.appendChild(b);
    return b;
  };
  const updateToolButtons = () => {
    document.querySelectorAll('.tb-btn[data-tool]').forEach(b =>
      b.classList.toggle('active', b.dataset.tool === editor.mode));
  };

  // 文件
  let g = group('文件');
  btn(g, '新建', '清空电路', () => { if (confirm('确定清空当前电路？')) editor.clearAll(); });
  btn(g, '打开', '打开电路文件 (Ctrl+O)', () => openCircuit());
  btn(g, '保存', '保存电路 (Ctrl+S)', () => saveCircuit());
  btn(g, 'PNG', '导出原理图图片', () => exportPNG());
  btn(g, '示例', '载入示例电路', () => showTemplates());
  btn(g, '帮助', '操作说明', () => document.getElementById('help-modal').classList.remove('hidden'));

  // 编辑
  g = group('编辑');
  btn(g, '↶', '撤销 (Ctrl+Z)', () => editor.undo());
  btn(g, '↷', '重做 (Ctrl+Y)', () => editor.redo());
  btn(g, '旋转', '旋转 (R)', () => editor.rotate());
  btn(g, '复制', '复制 (Ctrl+D)', () => editor.duplicate());
  btn(g, '删除', '删除 (Del)', () => editor.deleteSelected());

  // 工具
  g = group('工具');
  const toolBtn = (text, title, mode) => {
    const b = btn(g, text, title, () => { editor.setMode(mode); updateToolButtons(); });
    b.dataset.tool = mode;
    return b;
  };
  toolBtn('选择', '选择/移动 (Space)', 'select');
  toolBtn('导线', '布线工具 (W)', 'wire');
  toolBtn('探针', '电压探针 (P)', 'probe');

  // 仿真
  g = group('仿真');
  TB.dc = btn(g, '直流', '直流工作点分析', () => runDC(), 'btn-dc');
  TB.run = btn(g, '▶ 运行', '瞬态仿真', () => runSim(), 'btn-run');
  TB.step = btn(g, '步进', '单步瞬态', () => stepSim(), 'btn-step');
  TB.stop = btn(g, '■ 停止', '停止仿真', () => stopSim(), 'btn-stop');

  // 仿真内核（后端选择：MC 模组内核 / 本地 JS 参考）
  g = group('内核');
  const ksel = document.createElement('select');
  ksel.className = 'tb-select';
  ksel.title = '仿真后端：MC 内核 = 调用模组 Java 求解器；本地参考 = 内置 JS 引擎（离线对照）';
  ksel.innerHTML = '<option value="js">本地参考</option><option value="kernel">MC 内核</option>';
  ksel.addEventListener('change', e => {
    setBackend(e.target.value);
    setStatus('仿真后端：' + backendName());
  });
  g.appendChild(ksel);
  const kurl = document.createElement('input');
  kurl.type = 'text';
  kurl.className = 'tb-text';
  kurl.value = kernelClient.baseUrl;
  kurl.title = '内核地址（MC 内 HTTP 接口，默认 12787 端口）';
  kurl.addEventListener('change', () => {
    kernelClient = new KernelClient(kurl.value || 'http://127.0.0.1:12787');
  });
  g.appendChild(kurl);
  const kconn = document.createElement('button');
  kconn.className = 'tb-btn';
  kconn.textContent = '连接';
  kconn.title = '测试内核连接（内核由 start.bat 自动启动）';
  kconn.addEventListener('click', async () => {
    kconn.textContent = '检测中…';
    const ok = await checkKernelStatus();
    kconn.textContent = '连接';
    if (ok) {
      setStatus('内核已连接：' + (kernelStatus.info.mod || 'Cryptand') + ' · ' + (kernelStatus.info.kernel || ''));
      toast('内核已连接');
    } else {
      setStatus('内核未连接：' + (kernelStatus.lastError || ''));
      toast('内核未启动：请运行 test\\start.bat 启动内核（内核起来后状态自动更新，无需刷新）', 3000);
    }
  });
  g.appendChild(kconn);

  // 内核状态徽标（自动检测：每 3 秒刷新，无需手动刷新页面）
  const kst = document.createElement('span');
  kst.id = 'kstatus';
  kst.className = 'kstatus offline';
  kst.textContent = '● 内核未启动';
  kst.title = '内核状态：由 start.bat 自动启动（独立 cmd 窗口，关闭窗口即关闭内核）';
  g.appendChild(kst);

  // 视图
  g = group('视图');
  btn(g, '＋', '放大', () => editor.zoomIn());
  btn(g, '－', '缩小', () => editor.zoomOut());
  btn(g, '适应', '适应窗口', () => editor.fit());

  // 导线设置
  g = group('导线');
  const lab1 = document.createElement('label');
  lab1.className = 'tb-check';
  lab1.innerHTML = '<input type="checkbox" checked> 网格';
  lab1.querySelector('input').addEventListener('change', e => { editor.gridSnap = e.target.checked; editor.render(); });
  g.appendChild(lab1);
  const lab2 = document.createElement('label');
  lab2.className = 'tb-check';
  lab2.innerHTML = '<input type="checkbox" checked> 正交';
  lab2.querySelector('input').addEventListener('change', e => { editor.ortho = e.target.checked; });
  g.appendChild(lab2);
  const lab3 = document.createElement('label');
  lab3.className = 'tb-check';
  lab3.innerHTML = '<input type="checkbox" checked> 线阻';
  lab3.title = '导线带电阻与温度模型';
  lab3.querySelector('input').addEventListener('change', e => {
    editor.wireResistive = e.target.checked;
    editor.rebuildNetlist();
    editor.render();
  });
  g.appendChild(lab3);
  const wsel = document.createElement('select');
  wsel.className = 'tb-select';
  wsel.title = '导线类型';
  for (const [k, v] of Object.entries(WIRE_TYPES)) {
    const o = document.createElement('option');
    o.value = k;
    o.textContent = v.name;
    wsel.appendChild(o);
  }
  wsel.value = editor.wireType;
  wsel.addEventListener('change', e => { editor.wireType = e.target.value; });
  g.appendChild(wsel);
  const col = document.createElement('input');
  col.type = 'color';
  col.className = 'tb-color';
  col.value = editor.wireColor;
  col.title = '导线颜色';
  col.addEventListener('input', e => { editor.wireColor = e.target.value; editor.render(); });
  g.appendChild(col);
  const wid = document.createElement('input');
  wid.type = 'number';
  wid.className = 'tb-num';
  wid.value = editor.wireWidth;
  wid.min = 1; wid.max = 6;
  wid.title = '线宽';
  wid.addEventListener('change', e => {
    editor.wireWidth = clamp(parseInt(e.target.value) || 2, 1, 6);
    editor.render();
  });
  g.appendChild(wid);

  window.TB = TB;
  editor.on('mode', updateToolButtons);
  setStatus('就绪');
}

// ---------- 右侧面板 ----------
function buildPanels(editor, sim) {
  const props = document.getElementById('props-panel');
  props.innerHTML = '<div class="panel-title">属性</div><div class="panel-body" id="props-body"></div>';

  const sp = document.getElementById('sim-panel');
  sp.innerHTML = `
    <div class="panel-title">仿真设置</div>
    <div class="panel-body">
      <div class="row"><span>时长 (s)</span><input id="sim-dur" type="text" value="0.05"></div>
      <div class="row"><span>步长 (ms)</span><input id="sim-step" type="text" value="0.1"></div>
      <div class="row"><span>仿真时间</span><span id="sim-time">0 s</span></div>
      <div class="row"><span>进度</span><div class="prog"><div id="sim-prog"></div></div></div>
      <div id="sim-status" class="sim-status">就绪</div>
    </div>`;

  const rp = document.getElementById('results-panel');
  rp.innerHTML = `
    <div class="panel-title">结果</div>
    <div class="panel-body" id="dc-results"></div>
    <div class="panel-title sub">波形</div>
    <canvas id="wave"></canvas>
    <div id="wave-legend"></div>`;
  const wave = document.getElementById('wave');
  const sizeWave = () => {
    const dpr = window.devicePixelRatio || 1;
    const w = wave.clientWidth || 240, h = wave.clientHeight || 150;
    wave.width = Math.round(w * dpr);
    wave.height = Math.round(h * dpr);
    wave.getContext('2d').setTransform(dpr, 0, 0, dpr, 0, 0);
  };
  sizeWave();
  window.addEventListener('resize', sizeWave);

  editor.on('change', renderProps);
  editor.on('select', renderProps);
  renderProps();
}

function fmtParam(c, pd) {
  if (pd.type === 'bool') return '';
  const v = c.params[pd.key];
  if (pd.key === 'beta' || pd.key === 'br' || pd.key === 'Is' || pd.key === 'n') return String(v);
  return formatSI(v, pd.unit || '');
}

function wireLenOf(w) {
  let len = 0;
  for (let i = 0; i < w.points.length - 1; i++) {
    len += Math.hypot(w.points[i + 1].x - w.points[i].x, w.points[i + 1].y - w.points[i].y);
  }
  return len;
}

function renderProps() {
  const body = document.getElementById('props-body');
  if (!body) return;
  const sel = EDB.selected;
  if (!sel) {
    body.innerHTML = '<div class="hint">在画布中选择元件：编辑参数，仿真后可查看电压/电流/功率</div>';
    return;
  }
  if (sel.type === 'wire') {
    const w = sel.obj;
    const wt = WIRE_TYPES[w.type] || WIRE_TYPES.copper;
    let h = '<div class="prop-name">导线 · ' + wt.name + '</div>';
    h += '<div class="row"><span>类型</span><select id="wire-type" class="prop-select">';
    for (const [k, v] of Object.entries(WIRE_TYPES)) {
      h += '<option value="' + k + '"' + (k === w.type ? ' selected' : '') + '>' + v.name + '</option>';
    }
    h += '</select></div>';
    const len = wireLenOf(w);
    h += '<div class="row"><span>长度</span><span class="val">' + len.toFixed(1) + ' m</span></div>';
    h += '<div class="row"><span>电阻</span><span class="val">' + formatSI(wt.resistancePerMeter * len, 'Ω') + '</span></div>';
    if (EDB.simResults && EDB.simResults.wires && EDB.simResults.wires.get(w.id)) {
      const info = EDB.simResults.wires.get(w.id);
      h += '<div class="panel-title sub">仿真结果</div>';
      h += '<div class="res-meta">' + (EDB.simResults.mode === 'dc' ? '直流工作点' : '瞬态 @ t=' + EDB.simResults.t.toFixed(4) + ' s') + '</div>';
      h += '<div class="row"><span>电流</span><span class="val">' + formatSI(info.i, 'A') + '</span></div>';
      h += '<div class="row"><span>损耗功率</span><span class="val">' + formatSI(info.p, 'W') + '</span></div>';
      h += '<div class="row"><span>温度</span><span class="val">' + info.t.toFixed(1) + ' °C</span></div>';
      h += '<div class="row"><span>负载率</span><span class="val">' + (info.load * 100).toFixed(1) + '% / 额定 ' + formatSI(info.ratedCurrent, 'A') + '</span></div>';
      h += '<div class="row"><span>累计能量</span><span class="val">' + formatSI(info.energy, 'J') + '</span></div>';
      if (info.burned) h += '<div class="res-meta" style="color:#ff5252">⚠ 过热烧毁</div>';
    }
    body.innerHTML = h;
    const ts = document.getElementById('wire-type');
    if (ts) {
      ts.addEventListener('change', () => {
        w.type = ts.value;
        EDB.rebuildNetlist();
        EDB.emit('change');
        EDB.render();
        renderProps();
      });
    }
    return;
  }
  const c = sel.obj, def = COMPONENT_DEFS[c.type];
  let html = '<div class="prop-name">' + def.name + '</div>';
  html += '<div class="row"><span>位置</span><span>' + c.x + ', ' + c.y + '</span></div>';
  for (const pd of def.paramDefs) {
    if (pd.type === 'bool') {
      html += '<div class="row"><span>' + pd.label + '</span><label class="switch"><input type="checkbox" data-key="' + pd.key + '" ' + (c.params[pd.key] ? 'checked' : '') + '></label></div>';
    } else if (pd.type === 'select') {
      const opts = (pd.options || []).map(o =>
        '<option value="' + o + '"' + (c.params[pd.key] === o ? ' selected' : '') + '>' + o + '</option>').join('');
      html += '<div class="row"><span>' + pd.label + '</span><select data-key="' + pd.key + '" class="prop-select">' + opts + '</select></div>';
    } else {
      html += '<div class="row"><span>' + pd.label + (pd.unit ? ' (' + pd.unit + ')' : '') + '</span><input type="text" data-key="' + pd.key + '" value="' + fmtParam(c, pd) + '"></div>';
    }
  }
  // 仿真结果（直流或瞬态分析后，点击元件显示其电压/电流/功率等参数）
  if (EDB.simResults && EDB.simResults.currents && EDB.simResults.currents.get(c.id)) {
    const res = EDB.simResults;
    const cur = res.currents.get(c.id);
    const nd = c._nodeIdx || [];
    html += '<div class="panel-title sub">仿真结果</div>';
    html += '<div class="res-meta">' + (res.mode === 'dc' ? '直流工作点' : '瞬态 @ t=' + res.t.toFixed(4) + ' s') + (res.converged ? '' : ' · 未收敛') + '</div>';
    if (c.type === 'npn' && nd.length >= 3) {
      html += '<div class="row"><span>Vbe</span><span class="val">' + formatSI(res.V[nd[0]] - res.V[nd[2]], 'V') + '</span></div>';
      html += '<div class="row"><span>Vce</span><span class="val">' + formatSI(res.V[nd[1]] - res.V[nd[2]], 'V') + '</span></div>';
    } else if (nd.length >= 2) {
      html += '<div class="row"><span>电压</span><span class="val">' + formatSI(res.V[nd[0]] - res.V[nd[1]], 'V') + '</span></div>';
    }
    html += '<div class="row"><span>电流</span><span class="val">' + formatSI(cur.i, 'A') + '</span></div>';
    if (c.type === 'npn') {
      html += '<div class="row"><span>Ic / Ib</span><span class="val">' + formatSI(cur.ic, 'A') + ' / ' + formatSI(cur.ib, 'A') + '</span></div>';
    }
    html += '<div class="row"><span>功率</span><span class="val">' + formatSI(cur.p, 'W') + '</span></div>';
    if (cur.t !== undefined) {
      html += '<div class="row"><span>温度</span><span class="val">' + cur.t.toFixed(1) + ' °C</span></div>';
    }
    if (cur.stored) {
      html += '<div class="row"><span>储能</span><span class="val">' + formatSI(cur.stored, 'J') + '</span></div>';
    }
    if (res.energy && res.energy.get(c.id)) {
      html += '<div class="row"><span>累计能量</span><span class="val">' + formatSI(res.energy.get(c.id), 'J') + '</span></div>';
    }
  }
  body.innerHTML = html;
  body.querySelectorAll('input, select').forEach(inp => {
    inp.addEventListener('change', () => {
      const key = inp.dataset.key;
      if (inp.type === 'checkbox') c.params[key] = inp.checked;
      else if (inp.tagName === 'SELECT') c.params[key] = inp.value;
      else {
        const v = parseSI(inp.value);
        if (!isNaN(v)) c.params[key] = v;
        else {
          const pd = def.paramDefs.find(p => p.key === key);
          inp.value = fmtParam(c, pd);
        }
      }
      EDB.rebuildNetlist();
      EDB.emit('change');
      EDB.render();
      renderProps();
    });
  });
}

// ---------- 状态栏 ----------
function bindStatus(editor, sim) {
  const info = document.getElementById('status-info');
  const coords = document.getElementById('status-coords');
  const modeName = () => {
    const m = editor.mode;
    if (m === 'select') return '选择';
    if (m === 'wire') return '导线';
    if (m === 'probe') return '探针';
    return (COMPONENT_DEFS[m] && COMPONENT_DEFS[m].name) || m;
  };
  const update = () => {
    const nl = editor.netlist;
    info.textContent = '模式: ' + modeName() +
      ' | 旋转: ' + editor.rotation + '°' +
      ' | 元件: ' + editor.components.length +
      ' | 导线: ' + editor.wires.length +
      ' | 节点: ' + (nl ? nl.numNodes : 0) +
      ' | 探针: ' + editor.probes.length;
  };
  editor.on('change', update);
  editor.on('mode', update);
  update();
  editor.canvas.addEventListener('mousemove', e => {
    const r = editor.canvas.getBoundingClientRect();
    const w = editor.toWorld(e.clientX - r.left, e.clientY - r.top);
    coords.textContent = 'X:' + Math.round(w.x) + '  Y:' + Math.round(w.y);
  });
}

// ---------- 仿真控制（统一走后端抽象：MC 内核 / 本地 JS 参考） ----------
function backendName() { return getBackend().kind === 'kernel' ? 'MC内核' : '本地参考'; }

function applySimResult(out) {
  const res = out.lastRes;
  EDB.simResults = res;
  renderDCResults(res);
  drawWaveform(out.times || [], out.data || []);
  renderProps();
  EDB.render();
  if (out.warnings && out.warnings.length) {
    toast('提示: ' + out.warnings[0]);
  }
}

async function runDC() {
  if (simRunning) return;
  simRunning = true;
  setStatus('直流分析中（' + backendName() + '）…');
  try {
    const out = await getBackend().runDC();
    simRunning = false;
    applySimResult(out);
    setStatus((out.lastRes.converged ? '直流工作点分析完成' : '直流分析未收敛') + '（' + backendName() + '）');
    toast(out.lastRes.converged ? '直流分析完成' : '直流分析未收敛（检查电路连接）');
  } catch (err) {
    simRunning = false;
    const msg = err && err.message ? err.message : String(err);
    setStatus('直流分析失败：' + msg);
    toast('直流分析失败：' + msg);
  }
}

async function runSim() {
  if (simRunning) return;
  const dur = Math.max(parseFloat(document.getElementById('sim-dur').value) || 0.05, 1e-6);
  const stepMs = Math.max(parseFloat(document.getElementById('sim-step').value) || 0.1, 1e-4);
  simRunning = true;
  setStatus('仿真运行中（' + backendName() + '）…');
  let out = null;
  try {
    out = await getBackend().runTransient(dur, stepMs / 1000, EDB.probeNodeIndexes(), (t, frac, times, data) => {
      updateSimTime(t, dur);
      // 进度期间渐进刷新波形/结果（本地参考后端每块回调一次）
      if (times && times.length && data && data.length) {
        EDB.simResults = { V: [], mode: 'tran', t, h: stepMs / 1000, currents: new Map(), wires: new Map(), energy: new Map(), converged: true };
        drawWaveform(times, data);
        EDB.render();
      }
    });
  } catch (err) {
    simRunning = false;
    const msg = err && err.message ? err.message : String(err);
    setStatus('仿真失败：' + msg);
    toast('仿真失败：' + msg);
    EDB.render();
    return;
  }
  simRunning = false;
  updateSimTime(out.lastRes.t, dur);
  applySimResult(out);
  setStatus('仿真完成 @ ' + out.lastRes.t.toFixed(4) + ' s（' + backendName() + '）');
  toast('瞬态仿真完成');
}

async function stepSim() {
  if (simRunning) return;
  const stepMs = Math.max(parseFloat(document.getElementById('sim-step').value) || 0.1, 1e-4);
  const h = stepMs / 1000;
  simRunning = true;
  setStatus('步进中（' + backendName() + '）…');
  try {
    const out = await getBackend().runTransient(h, h, EDB.probeNodeIndexes(), () => {});
    simRunning = false;
    applySimResult(out);
    updateSimTime(out.lastRes.t, h);
    setStatus('步进 @ ' + out.lastRes.t.toFixed(4) + ' s（' + backendName() + '）');
  } catch (err) {
    simRunning = false;
    const msg = err && err.message ? err.message : String(err);
    setStatus('步进失败：' + msg);
    toast('步进失败：' + msg);
  }
}

function stopSim() {
  if (simRunning) getBackend().stop();
}

function updateSimTime(t, dur) {
  const el = document.getElementById('sim-time');
  if (el) el.textContent = t.toFixed(4) + ' s';
  const p = document.getElementById('sim-prog');
  if (p) p.style.width = Math.min(100, (t / dur) * 100).toFixed(1) + '%';
}

// ---------- 结果展示 ----------
function renderDCResults(res) {
  const el = document.getElementById('dc-results');
  if (!el) return;
  if (!res) { el.innerHTML = '<div class="hint">未运行</div>'; return; }
  let html = '<table class="res-table"><tr><th>元件</th><th>电流</th><th>功率</th><th>温度</th><th>能量(J)</th></tr>';
  for (const c of EDB.components) {
    const cur = res.currents.get(c.id);
    if (!cur) continue;
    const name = COMPONENT_DEFS[c.type].short;
    const t = cur.t !== undefined ? cur.t.toFixed(0) + '°C' : '';
    const e = (res.energy && res.energy.get(c.id)) ? formatSI(res.energy.get(c.id), '') : '';
    html += '<tr><td>' + name + c.id + '</td><td>' + formatSI(cur.i, 'A') + '</td><td>' + formatSI(cur.p, 'W') + '</td><td>' + t + '</td><td>' + e + '</td></tr>';
  }
  html += '</table>';
  // 导线（电阻 + 温度模型）
  if (res.wires && res.wires.size) {
    html += '<table class="res-table"><tr><th>导线</th><th>R</th><th>电流</th><th>损耗</th><th>温度</th><th>负载</th></tr>';
    for (const [id, info] of res.wires) {
      html += '<tr><td>' + info.name + '#' + id + (info.burned ? ' 🔥' : '') + '</td><td>' + formatSI(info.r, 'Ω') + '</td><td>' + formatSI(info.i, 'A') + '</td><td>' + formatSI(info.p, 'W') + '</td><td>' + info.t.toFixed(0) + '°C</td><td>' + (info.load * 100).toFixed(0) + '%</td></tr>';
    }
    html += '</table>';
  }
  html += '<table class="res-table"><tr><th>节点</th><th>电压</th></tr>';
  for (let n = 1; n < res.V.length; n++) {
    if (!isFinite(res.V[n])) continue;
    html += '<tr><td>N' + n + '</td><td>' + res.V[n].toFixed(3) + ' V</td></tr>';
  }
  html += '</table>';
  el.innerHTML = html;
}

function niceStep(range, target) {
  const raw = range / target;
  const mag = Math.pow(10, Math.floor(Math.log10(raw)));
  const norm = raw / mag;
  let step;
  if (norm < 1.5) step = 1; else if (norm < 3.5) step = 2; else if (norm < 7.5) step = 5; else step = 10;
  return step * mag;
}

function drawWaveform(times, data) {
  const wave = document.getElementById('wave');
  if (!wave) return;
  const ctx = wave.getContext('2d');
  const dpr = window.devicePixelRatio || 1;
  const W = wave.clientWidth || 240, H = wave.clientHeight || 150;
  wave.width = Math.round(W * dpr);
  wave.height = Math.round(H * dpr);
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, W, H);
  ctx.fillStyle = '#0c0e13';
  ctx.fillRect(0, 0, W, H);
  const padL = 38, padR = 8, padT = 10, padB = 18;
  const plotW = W - padL - padR, plotH = H - padT - padB;
  if (!times || !times.length) {
    ctx.fillStyle = '#5a6472';
    ctx.font = '12px sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText('运行瞬态仿真后显示波形', W / 2, H / 2);
    return;
  }
  let tmin = times[0], tmax = times[times.length - 1], vmin = Infinity, vmax = -Infinity;
  for (const d of data) for (const v of d) {
    if (isFinite(v)) { vmin = Math.min(vmin, v); vmax = Math.max(vmax, v); }
  }
  if (!isFinite(vmin)) { vmin = 0; vmax = 1; }
  if (vmin === vmax) { vmin -= 1; vmax += 1; }
  const vpad = (vmax - vmin) * 0.1;
  vmin -= vpad; vmax += vpad;
  const sx = v => padL + (v - tmin) / ((tmax - tmin) || 1) * plotW;
  const sy = v => padT + (vmax - v) / (vmax - vmin) * plotH;
  ctx.strokeStyle = '#1e2430';
  ctx.lineWidth = 1;
  ctx.font = '9px sans-serif';
  ctx.fillStyle = '#5a6472';
  const vstep = niceStep(vmax - vmin, 4);
  ctx.textAlign = 'right';
  for (let v = Math.ceil(vmin / vstep) * vstep; v <= vmax + 1e-9; v += vstep) {
    ctx.beginPath(); ctx.moveTo(padL, sy(v)); ctx.lineTo(W - padR, sy(v)); ctx.stroke();
    ctx.fillText(v.toFixed(3), padL - 3, sy(v) + 3);
  }
  const tstep = niceStep(tmax - tmin, 5);
  ctx.textAlign = 'center';
  for (let t = Math.ceil(tmin / tstep) * tstep; t <= tmax + 1e-9; t += tstep) {
    ctx.beginPath(); ctx.moveTo(sx(t), padT); ctx.lineTo(sx(t), H - padB); ctx.stroke();
    ctx.fillText(t.toFixed(4), sx(t), H - 4);
  }
  data.forEach((d, i) => {
    const color = (EDB.probes[i] && EDB.probes[i].color) || PROBE_COLORS[i % PROBE_COLORS.length];
    ctx.strokeStyle = color;
    ctx.lineWidth = 1.6;
    ctx.beginPath();
    let started = false;
    for (let k = 0; k < times.length; k++) {
      const v = d[k];
      if (!isFinite(v)) continue;
      const x = sx(times[k]), y = sy(v);
      if (!started) { ctx.moveTo(x, y); started = true; }
      else ctx.lineTo(x, y);
    }
    ctx.stroke();
  });
  const leg = document.getElementById('wave-legend');
  if (leg) {
    const nodeIdx = EDB.probeNodeIndexes();
    leg.innerHTML = EDB.probes.map((p, i) =>
      '<span style="color:' + p.color + '">P' + (i + 1) + ' → N' + (nodeIdx[i] !== undefined ? nodeIdx[i] : '?') + '</span>').join(' ');
  }
}

// ---------- 文件操作 ----------
function saveCircuit() {
  const data = JSON.stringify({
    version: 1,
    components: EDB.components,
    wires: EDB.wires,
    probes: EDB.probes.map(p => ({ x: p.x, y: p.y }))
  }, null, 2);
  const blob = new Blob([data], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'circuit.json';
  a.click();
  URL.revokeObjectURL(a.href);
  toast('电路已保存');
}

function openCircuit() {
  const inp = document.getElementById('fileinput');
  inp.onchange = () => {
    const f = inp.files[0];
    if (!f) return;
    const rd = new FileReader();
    rd.onload = () => {
      try {
        const o = JSON.parse(rd.result);
        EDB.recordUndo();
        EDB.components = (o.components || []).map(c => {
          const n = Object.assign({}, c, { params: Object.assign({}, c.params) });
          n.id = EDB._id();
          return n;
        });
        EDB.wires = (o.wires || []).map(w => {
          const n = { id: EDB._id(), points: w.points.map(p => ({ x: p.x, y: p.y })) };
          if (w.color) n.color = w.color;
          return n;
        });
        EDB.probes = (o.probes || []).map((p, i) => ({ x: p.x, y: p.y, color: PROBE_COLORS[i % PROBE_COLORS.length] }));
        EDB.selected = null;
        EDB.fit();
        EDB.rebuildNetlist();
        EDB.emit('change');
        toast('电路已打开');
      } catch (err) {
        alert('文件格式错误：' + err.message);
      }
    };
    rd.readAsText(f);
  };
  inp.value = '';
  inp.click();
}

function exportPNG() {
  EDB.render();
  const a = document.createElement('a');
  a.href = EDB.canvas.toDataURL('image/png');
  a.download = 'circuit.png';
  a.click();
  toast('已导出 PNG');
}

// ---------- 示例电路 ----------
const TEMPLATES = {
  '分压电路': {
    components: [
      { type: 'vsource', x: 80, y: 120, rotation: 180, params: { voltage: 10 } },
      { type: 'resistor', x: 260, y: 120, rotation: 0, params: { resistance: 10000 } },
      { type: 'resistor', x: 430, y: 120, rotation: 0, params: { resistance: 5000 } },
      { type: 'ground', x: 540, y: 120, rotation: 0, params: {} },
      { type: 'ground', x: 30, y: 200, rotation: 0, params: {} }
    ],
    wires: [
      [{ x: 130, y: 120 }, { x: 210, y: 120 }],
      [{ x: 310, y: 120 }, { x: 380, y: 120 }],
      [{ x: 480, y: 120 }, { x: 540, y: 120 }],
      [{ x: 30, y: 120 }, { x: 30, y: 200 }]
    ],
    probes: [{ x: 380, y: 120 }]
  },
  'RC 充放电': {
    components: [
      { type: 'vsource', x: 80, y: 120, rotation: 180, params: { voltage: 5 } },
      { type: 'resistor', x: 280, y: 120, rotation: 0, params: { resistance: 1000 } },
      { type: 'capacitor', x: 450, y: 120, rotation: 0, params: { capacitance: 0.0001 } },
      { type: 'ground', x: 540, y: 120, rotation: 0, params: {} },
      { type: 'ground', x: 30, y: 200, rotation: 0, params: {} }
    ],
    wires: [
      [{ x: 130, y: 120 }, { x: 230, y: 120 }],
      [{ x: 330, y: 120 }, { x: 400, y: 120 }],
      [{ x: 500, y: 120 }, { x: 540, y: 120 }],
      [{ x: 30, y: 120 }, { x: 30, y: 200 }]
    ],
    probes: [{ x: 400, y: 120 }]
  },
  'LED 指示': {
    components: [
      { type: 'vsource', x: 80, y: 120, rotation: 180, params: { voltage: 5 } },
      { type: 'resistor', x: 280, y: 120, rotation: 0, params: { resistance: 330 } },
      { type: 'led', x: 450, y: 120, rotation: 0, params: { Is: 1e-12, n: 2 } },
      { type: 'ground', x: 540, y: 120, rotation: 0, params: {} },
      { type: 'ground', x: 30, y: 200, rotation: 0, params: {} }
    ],
    wires: [
      [{ x: 130, y: 120 }, { x: 230, y: 120 }],
      [{ x: 330, y: 120 }, { x: 400, y: 120 }],
      [{ x: 500, y: 120 }, { x: 540, y: 120 }],
      [{ x: 30, y: 120 }, { x: 30, y: 200 }]
    ],
    probes: [{ x: 400, y: 120 }]
  },
  '二极管整流': {
    components: [
      { type: 'acsource', x: 80, y: 120, rotation: 180, params: { amplitude: 5, frequency: 50 } },
      { type: 'diode', x: 260, y: 120, rotation: 0, params: {} },
      { type: 'resistor', x: 430, y: 120, rotation: 0, params: { resistance: 1000 } },
      { type: 'ground', x: 540, y: 120, rotation: 0, params: {} },
      { type: 'ground', x: 30, y: 200, rotation: 0, params: {} }
    ],
    wires: [
      [{ x: 130, y: 120 }, { x: 210, y: 120 }],
      [{ x: 310, y: 120 }, { x: 380, y: 120 }],
      [{ x: 480, y: 120 }, { x: 540, y: 120 }],
      [{ x: 30, y: 120 }, { x: 30, y: 200 }]
    ],
    probes: [{ x: 380, y: 120 }]
  },
  '变压器降压': {
    components: [
      { type: 'vsource', x: 80, y: 120, rotation: 180, params: { voltage: 5 } },
      { type: 'transformer', x: 280, y: 120, rotation: 0, params: { ratio: 2 } },
      { type: 'resistor', x: 280, y: 180, rotation: 0, params: { resistance: 1000 } },
      { type: 'ground', x: 30, y: 200, rotation: 0, params: {} },
      { type: 'ground', x: 330, y: 120, rotation: 0, params: {} },
      { type: 'ground', x: 330, y: 180, rotation: 0, params: {} }
    ],
    wires: [
      [{ x: 130, y: 120 }, { x: 230, y: 120 }],
      [{ x: 30, y: 120 }, { x: 30, y: 200 }]
    ],
    probes: [{ x: 230, y: 180 }]
  },
  '电池驱动风扇': {
    components: [
      { type: 'battery', x: 80, y: 120, rotation: 0, params: { voltage: 12, resistance: 0.5 } },
      { type: 'fan', x: 280, y: 120, rotation: 0, params: { resistance: 8, inductance: 0.02 } },
      { type: 'ground', x: 30, y: 120, rotation: 0, params: {} },
      { type: 'ground', x: 330, y: 120, rotation: 0, params: {} }
    ],
    wires: [
      [{ x: 130, y: 120 }, { x: 230, y: 120 }]
    ],
    probes: [{ x: 230, y: 120 }]
  }
};

function showTemplates() {
  const names = Object.keys(TEMPLATES);
  const modal = document.createElement('div');
  modal.className = 'modal';
  modal.innerHTML = '<div class="modal-box"><h3>载入示例电路</h3><div class="tpl-list"></div><button class="tpl-cancel">取消</button></div>';
  const list = modal.querySelector('.tpl-list');
  names.forEach(n => {
    const b = document.createElement('button');
    b.className = 'tpl-btn';
    b.textContent = n;
    b.addEventListener('click', () => { loadTemplate(n); document.body.removeChild(modal); });
    list.appendChild(b);
  });
  modal.querySelector('.tpl-cancel').addEventListener('click', () => document.body.removeChild(modal));
  document.body.appendChild(modal);
}

function loadTemplate(name) {
  const t = TEMPLATES[name];
  if (!t) return;
  EDB.clearAll();
  EDB.components = t.components.map(c => Object.assign({}, c, { id: EDB._id(), params: Object.assign({}, c.params) }));
  EDB.wires = t.wires.map(pts => ({ id: EDB._id(), points: pts.map(p => ({ x: p.x, y: p.y })) }));
  EDB.probes = t.probes.map((p, i) => ({ x: p.x, y: p.y, color: PROBE_COLORS[i % PROBE_COLORS.length] }));
  EDB.fit();
  EDB.rebuildNetlist();
  EDB.render();
  EDB.emit('change');
  toast('已载入示例：' + name);
}

// ---------- 初始示例 ----------
function buildSample(editor) {
  const v = { id: editor._id(), type: 'vsource', x: 80, y: 120, rotation: 180, params: { voltage: 5 } };
  const r = { id: editor._id(), type: 'resistor', x: 280, y: 120, rotation: 0, params: { resistance: 330 } };
  const led = { id: editor._id(), type: 'led', x: 450, y: 120, rotation: 0, params: { Is: 1e-12, n: 2 } };
  const g = { id: editor._id(), type: 'ground', x: 540, y: 120, rotation: 0, params: {} };
  const g2 = { id: editor._id(), type: 'ground', x: 30, y: 200, rotation: 0, params: {} };
  editor.components.push(v, r, led, g, g2);
  editor.wires.push({ id: editor._id(), points: [{ x: 130, y: 120 }, { x: 230, y: 120 }] });
  editor.wires.push({ id: editor._id(), points: [{ x: 330, y: 120 }, { x: 400, y: 120 }] });
  editor.wires.push({ id: editor._id(), points: [{ x: 500, y: 120 }, { x: 540, y: 120 }] });
  editor.wires.push({ id: editor._id(), points: [{ x: 30, y: 120 }, { x: 30, y: 200 }] });
  editor.probes.push({ x: 400, y: 120, color: PROBE_COLORS[0] });
  editor.fit();
  editor.rebuildNetlist();
  editor.render();
  editor.emit('change');          // 刷新状态栏（元件/导线/节点计数）
}
