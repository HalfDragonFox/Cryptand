'use strict';
// kernel.js — 前端 ↔ MC 模组内核 的仿真桥接层
//
// 架构：test/ 只是前端（原理图编辑 + 结果显示）。真正的仿真由 MC 模组内核
// （common 模块：Network + RealMnaSolver/ComplexMnaSolver）完成。
// 前端把电路序列化为【网表元素列表】（节点由前端分配，语义与内核一致），
// POST 到模组内的 HTTP 接口（SimHttpServer），内核求解后返回节点电压与
// 元件/导线结果，前端按与本地 JS 参考引擎相同的数据结构展示（便于对比）。
//
// 协议见 README「内核桥接协议」。

// Node 环境下加载共享的网表/求解器（浏览器中由 <script> 全局提供）
if (typeof module !== 'undefined' && module.exports) {
  const _s = require('./simulator.js');
  global.Netlist = _s.Netlist;
  global.Simulator = _s.Simulator;
}

// ---------- 内核客户端 ----------
class KernelClient {
  constructor(baseUrl) {
    this.baseUrl = (baseUrl || 'http://127.0.0.1:12787').replace(/\/+$/, '');
  }
  get simulateUrl() { return this.baseUrl + '/simulate'; }
  get healthUrl() { return this.baseUrl + '/health'; }

  async ping() {
    const r = await fetch(this.healthUrl, { method: 'GET' });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    return r.json();
  }

  async simulate(request) {
    // 超时保护（2026-08-17）：MC 内嵌内核在瞬态步数大时计算/响应较慢，
    // fetch 无超时会导致页面无限等待"卡住" → 30s 超时中止并报错
    const TIMEOUT_MS = 30000;
    const ctrl = (typeof AbortController !== 'undefined') ? new AbortController() : null;
    let timer = null;
    if (ctrl) timer = setTimeout(() => ctrl.abort(), TIMEOUT_MS);
    let r;
    try {
      r = await fetch(this.simulateUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
        signal: ctrl ? ctrl.signal : undefined
      });
    } catch (err) {
      if (timer) clearTimeout(timer);
      if (ctrl && ctrl.signal.aborted) throw new Error('内核仿真超时（>30s，请减小步数或电路规模）');
      throw err;
    }
    if (timer) clearTimeout(timer);
    if (!r.ok) throw new Error('内核 HTTP ' + r.status);
    const res = await r.json();
    if (!res || res.ok === false) {
      throw new Error((res && res.error) || '内核求解失败');
    }
    return res;
  }
}

// 全局内核客户端实例（UI 可改地址）
// 多用户：若页面从局域网地址访问（非 localhost），内核地址自动指向同一主机，
// 远程设备无需手动改地址即可连接内核仿真接口。
function defaultKernelBase() {
  const KERNEL_PORT = 12787;
  if (typeof location !== 'undefined' && location.hostname) {
    const h = location.hostname;
    if (h && h !== 'localhost' && h !== '127.0.0.1' && h !== '::1') {
      return 'http://' + h + ':' + KERNEL_PORT;
    }
  }
  return 'http://127.0.0.1:' + KERNEL_PORT;
}
let kernelClient = new KernelClient(defaultKernelBase());

// ---------- 内核连接状态（UI 实时显示；内核由 start.bat 在独立 cmd 窗口启动） ----------
const kernelStatus = {
  state: 'checking',      // 'checking' | 'online' | 'offline'
  info: null,             // /health 返回 {ok, mod, kernel, port}
  lastError: null,
  _listeners: [],
  on(fn) { this._listeners.push(fn); },
  _emit() { for (const fn of this._listeners) { try { fn(this); } catch (e) { /* ignore */ } } }
};

// 检测内核是否就绪（GET /health），更新 kernelStatus 并通知 UI
async function checkKernelStatus() {
  kernelStatus.state = 'checking';
  kernelStatus._emit();
  try {
    const info = await kernelClient.ping();
    kernelStatus.state = 'online';
    kernelStatus.info = info;
    kernelStatus.lastError = null;
    kernelStatus._emit();
    return true;
  } catch (err) {
    kernelStatus.state = 'offline';
    kernelStatus.info = null;
    kernelStatus.lastError = (err && err.message) ? err.message : String(err);
    kernelStatus._emit();
    return false;
  }
}

// ---------- 电路 → 内核网表元素 ----------
// 节点分配：前端 Netlist（电阻导线语义，与内核一致）→ 节点 0 为地。
// 元件按前端组件映射为内核元素；电感展开为 电感 + 串联电阻（内部节点）。
function buildKernelRequest(editor, mode, duration, step, probes) {
  const nl = new Netlist(editor.components, editor.wires, true);
  const elements = [];
  let nextNode = nl.numNodes;
  const warnings = [];

  for (const c of editor.components) {
    const nd = c._nodeIdx;
    switch (c.type) {
      case 'resistor': case 'potentiometer': case 'lamp': case 'heater':
      case 'bell': case 'carbon_pile': case 'rheostat': case 'plotter': case 'gauge': {
        elements.push({
          id: c.id, kind: 'resistor', a: nd[0], b: nd[1],
          value: c.params.resistance || 0,
          tempCoef: c.params.tempCoef || 0,
          thermalConductance: c.params.thermalConductance || 0,
          heatCapacity: c.params.heatCapacity || 0
        });
        break;
      }
      case 'connector':   // 端子（单点汇流点）：不生成元素——多条导线在端子处
        // 已由 Netlist 按同坐标归并为同一电气节点，无需额外短路元件
        break;
      case 'capacitor':
        elements.push({ id: c.id, kind: 'capacitor', a: nd[0], b: nd[1], value: c.params.capacitance || 0 });
        break;
      case 'inductor': case 'fan': case 'motor': case 'electromagnet': case 'basin_heater': {   // R-L 绕组（风扇/电机/电磁铁/盆加热器同款）
        const mid = nextNode++;
        elements.push({ id: c.id, kind: 'inductor', a: nd[0], b: mid, value: c.params.inductance || 0 });
        elements.push({
          id: c.id + '.r', kind: 'resistor', a: mid, b: nd[1],
          value: c.params.resistance || 0,
          tempCoef: c.params.tempCoef || 0,
          thermalConductance: c.params.thermalConductance || 0,
          heatCapacity: c.params.heatCapacity || 0
        });
        break;
      }
      case 'vsource':
        elements.push({ id: c.id, kind: 'vsource', a: nd[0], b: nd[1], value: c.params.voltage || 0, series: 0.01 });
        break;
      case 'generator':   // 电枢 R_a 串联 EMF：vsource + series 内阻
        elements.push({ id: c.id, kind: 'vsource', a: nd[0], b: nd[1], value: c.params.voltage || 0, series: c.params.resistance || 0.5 });
        break;
      case 'acsource':
        elements.push({
          id: c.id, kind: 'acsource', a: nd[0], b: nd[1],
          amplitude: c.params.amplitude || 0, frequency: c.params.frequency || 0, series: 0.01
        });
        break;
      case 'isource':
        elements.push({ id: c.id, kind: 'isource', a: nd[0], b: nd[1], value: c.params.current || 0 });
        break;
      case 'switch': case 'fuse': case 'hvswitch': case 'breaker': case 'contactor':
        if (c.params.closed) elements.push({ id: c.id, kind: 'resistor', a: nd[0], b: nd[1], value: 0.1 });
        break;
      case 'transformer': case 'variac': {   // 理想变压器：V2 = n·V1（内部约束节点 k 由前端分配）
        const k = nextNode++;
        elements.push({ id: c.id, kind: 'transformer', a1: nd[0], a2: nd[1], b1: nd[2], b2: nd[3], k, ratio: c.params.ratio || 1 });
        break;
      }
      case 'three_phase_transformer': {   // 3× 理想变压器
        for (let i = 0; i < 3; i++) {
          const k = nextNode++;
          elements.push({ id: c.id + '.' + i, kind: 'transformer', a1: nd[i], a2: nd[3], b1: nd[4 + i], b2: nd[7], k, ratio: c.params.ratio || 1 });
        }
        break;
      }
      case 'wavesource':   // 波形源（DC/SINE/SQUARE/TRIANGLE/SAWTOOTH）
        elements.push({ id: c.id, kind: 'wavesource', a: nd[0], b: nd[1], waveform: c.params.waveform || 'SINE', amplitude: c.params.amplitude || 0, frequency: c.params.frequency || 0, phase: c.params.phase || 0, duty: c.params.duty != null ? c.params.duty : 0.5, offset: c.params.offset || 0, series: c.params.series || 0.01 });
        break;
      case 'three_phase_source':   // 3× 交流源（0/120/240°）
        for (let i = 0; i < 3; i++) {
          elements.push({ id: c.id + '.' + i, kind: 'wavesource', a: nd[i], b: nd[3], waveform: 'SINE', amplitude: c.params.amplitude || 0, frequency: c.params.frequency || 0, phase: i * 120, series: 0.01 });
        }
        break;
      case 'vfd':   // 3× 交流输出
        for (let i = 0; i < 3; i++) {
          elements.push({ id: c.id + '.' + i, kind: 'wavesource', a: nd[2 + i], b: nd[5], waveform: 'SINE', amplitude: c.params.amplitude || 0, frequency: c.params.frequency || 0, phase: i * 120, series: 0.01 });
        }
        break;
      case 'oscillator': case 'radio_transmitter':   // 正弦输出
        elements.push({ id: c.id, kind: 'acsource', a: nd[0], b: nd[1], amplitude: c.params.amplitude || 0, frequency: c.params.frequency || 0, series: 0.01 });
        break;
      case 'antenna':   // 辐射电阻 + 损耗电阻
        elements.push({ id: c.id, kind: 'resistor', a: nd[0], b: nd[1], value: (c.params.radiationR || 0) + (c.params.lossR || 0) });
        break;
      case 'speaker': case 'radio_receiver': case 'dc_motor': case 'induction_motor': {   // R-L 绕组
        const mid = nextNode++;
        elements.push({ id: c.id, kind: 'inductor', a: nd[0], b: mid, value: c.params.inductance || 0 });
        elements.push({ id: c.id + '.r', kind: 'resistor', a: mid, b: nd[1], value: c.params.resistance || 0, tempCoef: c.params.tempCoef || 0, thermalConductance: c.params.thermalConductance || 0, heatCapacity: c.params.heatCapacity || 0 });
        break;
      }
      case 'three_phase_motor': case 'brushless_motor': {   // 3× R-L 绕组
        for (let i = 0; i < 3; i++) {
          const mid = nextNode++;
          elements.push({ id: c.id + '.' + i, kind: 'inductor', a: nd[i], b: mid, value: c.params.inductance || 0 });
          elements.push({ id: c.id + '.' + i + '.r', kind: 'resistor', a: mid, b: nd[3], value: c.params.resistance || 0, tempCoef: c.params.tempCoef || 0, thermalConductance: c.params.thermalConductance || 0, heatCapacity: c.params.heatCapacity || 0 });
        }
        break;
      }
      case 'solar_panel':   // 光生电流源（近似为理想电流源）
        elements.push({ id: c.id, kind: 'isource', a: nd[0], b: nd[1], value: c.params.photoCurrent || 0 });
        break;
      case 'synchronous_motor':   // 反电动势 + 内阻
        elements.push({ id: c.id, kind: 'vsource', a: nd[0], b: nd[1], value: c.params.voltage || 0, series: c.params.resistance || 0.5 });
        break;
      case 'electromachine': {
        if (c.params.mode === 'generator') {
          elements.push({ id: c.id, kind: 'vsource', a: nd[0], b: nd[1], value: c.params.voltage || 0, series: c.params.resistance || 0.5 });
        } else {
          const mid = nextNode++;
          elements.push({ id: c.id, kind: 'inductor', a: nd[0], b: mid, value: c.params.inductance || 0 });
          elements.push({ id: c.id + '.r', kind: 'resistor', a: mid, b: nd[1], value: c.params.resistance || 0, tempCoef: c.params.tempCoef || 0, thermalConductance: c.params.thermalConductance || 0, heatCapacity: c.params.heatCapacity || 0 });
        }
        break;
      }
      case 'rectifier': {   // 全桥 4 二极管 + 负载
        elements.push({ id: c.id + '.d1', kind: 'diode', a: nd[0], b: nd[2] });
        elements.push({ id: c.id + '.d2', kind: 'diode', a: nd[1], b: nd[2] });
        elements.push({ id: c.id + '.d3', kind: 'diode', a: nd[3], b: nd[0] });
        elements.push({ id: c.id + '.d4', kind: 'diode', a: nd[3], b: nd[1] });
        elements.push({ id: c.id + '.r', kind: 'resistor', a: nd[2], b: nd[3], value: c.params.loadR || 1e6 });
        break;
      }
      case 'three_phase_rectifier': {   // 3 二极管 + 负载
        for (let i = 0; i < 3; i++) elements.push({ id: c.id + '.d' + i, kind: 'diode', a: nd[i], b: nd[4] });
        elements.push({ id: c.id + '.r', kind: 'resistor', a: nd[4], b: nd[5], value: c.params.loadR || 1e6 });
        break;
      }
      case 'vccs': case 'vcvs': case 'dc_dc': case 'inverter': case 'electron_tube': case 'vfet': case 'mutual_inductor':
        warnings.push('内核暂不支持 ' + c.type + '（已跳过 #' + c.id + '）');
        break;
      case 'diode': case 'led':
        elements.push({ id: c.id, kind: 'diode', a: nd[0], b: nd[1], vth: c.type === 'led' ? 1.2 : 0.7 });
        break;
      case 'npn':
        warnings.push('内核暂不支持 NPN 三极管（已跳过 #' + c.id + '）');
        break;
      case 'ground':
        break; // 节点 0 即地
      default:
        warnings.push('未知元件类型 ' + c.type + '（已跳过）');
    }
  }

  // 导线段 → 电阻支路（带温度模型参数）
  for (const seg of nl.wireSegments) {
    const wt = WIRE_TYPES[seg.wire.type] || WIRE_TYPES.copper;
    const R = Math.max(wt.resistancePerMeter * Math.max(seg.length, 0.001), 1e-9);
    elements.push({
      id: seg.wire.id, kind: 'wire', a: seg.n1, b: seg.n2, r: R,
      type: seg.wire.type || 'copper', name: wt.name, length: seg.length,
      ratedCurrent: wt.ratedCurrent,
      thermalConductance: wt.thermalConductance,
      heatCapacity: wt.heatCapacity,
      maxTemp: wt.maxTemp
    });
  }

  return {
    mode: mode || 'dc',
    duration: duration != null ? duration : 0.05,
    step: step != null ? step : 1e-4,
    nodeCount: nextNode,
    groundNode: 0,
    elements: elements,
    probes: probes || [],
    warnings: warnings
  };
}

// ---------- 内核响应 → 前端展示结构（与 JS 参考引擎 lastRes 同构） ----------
function normalizeKernelResponse(res, req) {
  const N = (res.nodeVoltages || []).length;
  const V = new Float64Array(N);
  for (let i = 0; i < N; i++) V[i] = res.nodeVoltages[i] || 0;

  const currents = new Map();
  for (const e of (res.components || [])) {
    if (typeof e.id !== 'number') continue;   // 电感内部串联电阻部分（id 形如 "5.r"）
    currents.set(e.id, { i: e.i || 0, p: e.p || 0, stored: e.stored || 0, t: e.t });
  }
  const energy = new Map();
  for (const e of (res.components || [])) {
    if (typeof e.id === 'number') energy.set(e.id, e.energy || 0);
  }
  const wires = new Map();
  for (const w of (res.wires || [])) {
    wires.set(w.id, {
      type: w.type || 'copper', name: w.name || '导线',
      length: w.length || 0, r: w.r || 0,
      i: w.i || 0, p: w.p || 0, t: w.t != null ? w.t : WIRE_AMBIENT,
      ratedCurrent: w.ratedCurrent || 0,
      load: w.load || 0, energy: w.energy || 0,
      burned: !!w.burned
    });
  }

  const mode = res.mode || req.mode || 'dc';
  const lastRes = {
    V: V,
    mode: mode,
    t: mode === 'tran' ? ((res.transient && res.transient.times && res.transient.times.length)
      ? res.transient.times[res.transient.times.length - 1] : 0) : 0,
    h: req.step || 1e-4,
    currents: currents,
    wires: wires,
    energy: energy,
    converged: !!res.converged,
    burnedWires: res.burnedWires || []
  };

  return {
    lastRes: lastRes,
    times: (res.transient && res.transient.times) || [],
    data: (res.transient && res.transient.waveforms) || [],
    warnings: req.warnings || []
  };
}

// ---------- 后端抽象：本地 JS 参考引擎（离线对照，非正式） ----------
const jsBackend = {
  kind: 'js',
  _sim: null,
  _stopped: false,
  _ensure() {
    if (!this._sim) this._sim = new Simulator();
    return this._sim;
  },
  async runDC() {
    const sim = this._ensure();
    sim.setCircuit(EDB.components, EDB.wires, EDB.wireResistive !== false);
    const res = sim.solve('dc', { t: 0, h: 1e-3, state: { capV: new Map(), indI: new Map() } });
    return { lastRes: res, times: [], data: [], warnings: [] };
  },
  async runTransient(duration, h, probes, onProgress) {
    const sim = this._ensure();
    sim.setCircuit(EDB.components, EDB.wires, EDB.wireResistive !== false);
    sim.startTransient(duration, h, probes, 'zero');
    this._stopped = false;
    return new Promise((resolve) => {
      const CHUNK = 500;
      const tick = () => {
        if (this._stopped) {
          this._stopped = false;
          onProgress(sim.t, Math.min(1, sim.t / duration), sim.times, sim.data);
          resolve({ lastRes: sim.lastRes, times: sim.times, data: sim.data, warnings: [] });
          return;
        }
        const done = sim.transientChunk(CHUNK);
        onProgress(sim.t, Math.min(1, sim.t / duration), sim.times, sim.data);
        if (done) resolve({ lastRes: sim.lastRes, times: sim.times, data: sim.data, warnings: [] });
        else setTimeout(tick, 0);
      };
      tick();
    });
  },
  stop() { this._stopped = true; }
};

// ---------- 后端抽象：MC 模组内核（正式路径） ----------
const kernelBackend = {
  kind: 'kernel',
  _stopped: false,
  async runDC() {
    const req = buildKernelRequest(EDB, 'dc');
    const res = await kernelClient.simulate(req);
    return normalizeKernelResponse(res, req);
  },
  async runTransient(duration, h, probes, onProgress) {
    const req = buildKernelRequest(EDB, 'tran', duration, h, probes);
    this._stopped = false;
    const res = await kernelClient.simulate(req);
    if (this._stopped) { this._stopped = false; }
    const norm = normalizeKernelResponse(res, req);
    onProgress(duration, 1, norm.times, norm.data);
    return norm;
  },
  stop() { this._stopped = true; }
};

// 当前活动后端
let activeBackend = jsBackend;

function setBackend(kind) {
  activeBackend = kind === 'kernel' ? kernelBackend : jsBackend;
  if (activeBackend.stop) activeBackend._stopped = false;
  return activeBackend;
}
function getBackend() { return activeBackend; }

// Node 环境导出（供序列化自检脚本用）
if (typeof module !== 'undefined' && module.exports) {
  module.exports = { KernelClient, buildKernelRequest, normalizeKernelResponse };
}
