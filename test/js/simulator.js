'use strict';
// simulator.js — 电路仿真引擎
// 采用改进节点分析 (MNA) + 后向欧拉伴随模型 + 牛顿迭代（二极管/三极管）
// 节点编号：0 为地(参考节点)，其余按发现顺序编号

// Node 环境下加载共享的元件定义（浏览器中由 <script> 全局提供）
if (typeof module !== 'undefined' && module.exports) {
  const _c = require('./components.js');
  global.GRID = _c.GRID;
  global.Vt = _c.Vt;
  global.clamp = _c.clamp;
  global.PROBE_COLORS = _c.PROBE_COLORS;
  global.COMPONENT_DEFS = _c.COMPONENT_DEFS;
  global.absTerminal = _c.absTerminal;
  global.formatSI = _c.formatSI;
  global.WIRE_TYPES = _c.WIRE_TYPES;
  global.WIRE_AMBIENT = _c.WIRE_AMBIENT;
}

// 二极管电流/电导模型（带指数钳位 + 线性外推）
// 目的：正向电压过大时电导有界（≈Is/Vt·e^30），保证 MNA 矩阵良态、牛顿迭代稳健
function diodeIV(vd, Is, n) {
  const vt = (n || 1) * Vt;
  const xmax = 30;
  const x = vd / vt;
  if (x <= xmax) {
    const ex = Math.exp(x);
    return { i: Is * (ex - 1), g: (Is / vt) * ex };
  }
  const ex = Math.exp(xmax);
  const i0 = Is * (ex - 1);
  const g0 = (Is / vt) * ex;
  return { i: i0 + g0 * (vd - xmax * vt), g: g0 };
}

// 波形源瞬时值：DC/SINE/SQUARE/TRIANGLE/SAWTOOTH（带相位/占空比/偏移）
function waveValue(c, t) {
  const wf = c.params.waveform || 'SINE';
  const amp = c.params.amplitude || 0;
  const f = c.params.frequency || 0;
  const ph = (c.params.phase || 0) * Math.PI / 180;
  const off = c.params.offset || 0;
  const duty = c.params.duty != null ? c.params.duty : 0.5;
  const w = 2 * Math.PI * f * t + ph;
  let u = 0;
  if (wf === 'DC') u = 1;
  else if (wf === 'SINE') u = Math.sin(w);
  else if (wf === 'SQUARE') u = Math.sin(w) >= 0 ? 1 : -1;
  else if (wf === 'TRIANGLE') u = 2 / Math.PI * Math.asin(Math.sin(w));
  else if (wf === 'SAWTOOTH') u = 2 * (((w / (2 * Math.PI)) % 1) + 1) % 1 - 1;
  else u = 0;
  return off + amp * u;
}

// 动态并查集
class DynUF {  constructor() { this.p = []; this.r = []; }
  add() { const i = this.p.length; this.p.push(i); this.r.push(0); return i; }
  find(a) { while (this.p[a] !== a) { this.p[a] = this.p[this.p[a]]; a = this.p[a]; } return a; }
  union(a, b) {
    a = this.find(a); b = this.find(b);
    if (a === b) return;
    if (this.r[a] < this.r[b]) { const t = a; a = b; b = t; }
    this.p[b] = a;
    if (this.r[a] === this.r[b]) this.r[a]++;
  }
}

// 网表：把元件引脚与导线顶点归并为电气节点；导线按段建立电阻支路（带温度模型）
class Netlist {
  constructor(components, wires, resistive = true) {
    this.components = components;
    this.wires = wires;
    this.resistive = resistive;       // true=导线带电阻(每段为电阻支路)；false=理想导线
    this.numNodes = 0;
    this.groundNode = 0;
    this._posMap = new Map();         // 'x,y' -> point id
    this._nodeByPoint = [];
    this.compNodes = new Map();       // comp -> [nodeIdx...]
    this.wireSegments = [];           // {wire, n1, n2, length, pt1, pt2}
    this._genPt = new Map();          // generator/battery comp -> 内部节点 X（内阻 R 与 EMF 之间）
    this.build();
  }

  build() {
    const uf = new DynUF();
    this._posMap = new Map();
    const compPts = new Map();
    const addPoint = (x, y) => {
      const key = Math.round(x) + ',' + Math.round(y);
      const pt = uf.add();
      if (this._posMap.has(key)) uf.union(pt, this._posMap.get(key));
      else this._posMap.set(key, pt);
      return pt;
    };
    // 导线顶点也统一取整
    const addWirePoint = (x, y) => addPoint(Math.round(x), Math.round(y));

    for (const c of this.components) {
      const def = COMPONENT_DEFS[c.type];
      const pts = [];
      for (let i = 0; i < def.terminals.length; i++) {
        const t = absTerminal(c, i);
        pts.push(addPoint(t.x, t.y));
      }
      compPts.set(c, pts);
    }
    // 发电机类（内阻/串联电阻 R(A-X) 串联 EMF 或电流源(X-B)）：内部节点 X（特殊坐标避免与网格点冲突）
    for (const c of this.components) {
      const genLike = c.type === 'generator' || c.type === 'battery' || c.type === 'solar_panel'
        || c.type === 'synchronous_motor' || (c.type === 'electromachine' && c.params.mode === 'generator');
      if (genLike) {
        const kx = -(1e9 + c.id);
        this._genPt.set(c, addPoint(kx, kx));
      }
    }
    // 所有地线引脚并到同一节点
    let gfirst = null;
    for (const c of this.components) {
      if (c.type === 'ground') {
        const pt = compPts.get(c)[0];
        if (gfirst === null) gfirst = pt; else uf.union(gfirst, pt);
      }
    }
    if (this.resistive) {
      // 电阻导线：相邻顶点之间是独立电阻段（不再合并为同一节点）
      this.wireSegments = [];
      for (const w of this.wires) {
        const pts = [];
        for (const p of w.points) pts.push(addWirePoint(p.x, p.y));
        for (let i = 0; i < pts.length - 1; i++) {
          const a = w.points[i], b = w.points[i + 1];
          this.wireSegments.push({
            wire: w, pt1: pts[i], pt2: pts[i + 1], n1: -1, n2: -1,
            length: Math.hypot(b.x - a.x, b.y - a.y)
          });
        }
      }
    } else {
      // 理想导线：相邻顶点直接合并
      for (const w of this.wires) {
        let prev = null;
        for (const p of w.points) {
          const pt = addWirePoint(p.x, p.y);
          if (prev !== null) uf.union(prev, pt);
          prev = pt;
        }
      }
    }

    // 节点编号
    const rootIdx = new Map();
    let count = 0;
    const nodeOf = (pt) => {
      const r = uf.find(pt);
      if (!rootIdx.has(r)) rootIdx.set(r, count++);
      return rootIdx.get(r);
    };
    let ground = null;
    if (gfirst !== null) ground = nodeOf(gfirst);
    const remap = (idx) => {
      if (ground === null) return idx;
      if (idx === ground) return 0;
      return idx < ground ? idx + 1 : idx;
    };
    // 先为所有引脚/导线顶点编号，再统计节点总数
    this._nodeByPoint = new Array(uf.p.length);
    for (let pt = 0; pt < uf.p.length; pt++) this._nodeByPoint[pt] = remap(nodeOf(pt));
    this.numNodes = count;
    this.groundNode = 0;
    for (const seg of this.wireSegments) {
      seg.n1 = this._nodeByPoint[seg.pt1];
      seg.n2 = this._nodeByPoint[seg.pt2];
    }

    this.compNodes = new Map();
    for (const c of this.components) {
      const pts = compPts.get(c);
      let nodes = pts.map(pt => this._nodeByPoint[pt]);
      if (this._genPt.has(c)) {
        nodes = [nodes[0], this._nodeByPoint[this._genPt.get(c)], nodes[1]];   // A, X, B
      }
      this.compNodes.set(c, nodes);
      c._nodeIdx = nodes;
    }
  }

  nodeForPoint(x, y) {
    const pt = this._posMap.get(x + ',' + y);
    if (pt === undefined) return 0;
    return this._nodeByPoint[pt];
  }
}

// ---------- 求解器 ----------
class Simulator {
  constructor() {
    this.components = [];
    this.wires = [];
    this.netlist = null;
    this.lastRes = null;
    this.stopped = false;
    this.times = [];
    this.data = [];
    // 热/能量模型状态
    this._compTemp = new Map();     // compId -> °C
    this._compEnergy = new Map();   // compId -> J（累计能量）
    this._wireTemp = new Map();     // wireId -> °C
    this._wireEnergy = new Map();   // wireId -> J（累计能量）
    this._burned = new Set();       // 已烧毁的导线 id
  }

  setCircuit(components, wires, resistive = true) {
    this.components = components;
    this.wires = wires;
    this.netlist = new Netlist(components, wires, resistive);
    this._compTemp.clear();
    this._compEnergy.clear();
    this._wireTemp.clear();
    this._wireEnergy.clear();
    this._burned.clear();
  }

  // ---------- 温度/电阻模型（R(T)=R0·(1+α·(T−Tamb))） ----------
  compR(c) {
    let R = Math.abs(c.params.resistance) || 1e-9;
    const a = c.params.tempCoef || 0;
    if (a) {
      const T = this._compTemp.get(c.id) ?? WIRE_AMBIENT;
      R = R * (1 + a * (T - WIRE_AMBIENT));
    }
    return Math.max(R, 1e-9);
  }
  wireType(w) { return WIRE_TYPES[w.type] || WIRE_TYPES.copper; }
  wireLen(w) {
    let len = 0;
    for (let i = 0; i < w.points.length - 1; i++) {
      len += Math.hypot(w.points[i + 1].x - w.points[i].x, w.points[i + 1].y - w.points[i].y);
    }
    return len;
  }
  wireR(w) { return Math.max(this.wireType(w).resistancePerMeter * this.wireLen(w), 1e-9); }
  wireCurrent(w, V) {
    if (!V || this._burned.has(w.id)) return 0;
    for (const seg of this.netlist.wireSegments) {
      if (seg.wire !== w) continue;
      const R = Math.max(this.wireType(w).resistancePerMeter * Math.max(seg.length, 0.001), 1e-9);
      return (V[seg.n1] - V[seg.n2]) / R;
    }
    return 0;
  }
  _hasThermal() {
    if (this.netlist.wireSegments.length) return true;
    return this.components.some(c => (c.params.thermalConductance || 0) > 0 && (c.params.heatCapacity || 0) > 0);
  }

  // 直流热稳态：T_ss = Tamb + P/G；返回最大温差（用于 R(T)↔温度 外循环）
  updateTempsDC(V, currents) {
    let dT = 0;
    const Tamb = WIRE_AMBIENT;
    for (const c of this.components) {
      const G = c.params.thermalConductance || 0, C = c.params.heatCapacity || 0;
      if (!(G > 0 && C > 0)) continue;
      const cur = currents.get(c.id);
      if (!cur) continue;
      const Tnew = Tamb + Math.abs(cur.p) / G;
      const Told = this._compTemp.get(c.id) ?? Tamb;
      this._compTemp.set(c.id, Tnew);
      dT = Math.max(dT, Math.abs(Tnew - Told));
    }
    for (const w of this.wires) {
      const wt = this.wireType(w);
      const R = this.wireR(w);
      const I = this.wireCurrent(w, V);
      const Tnew = Tamb + (I * I * R) / wt.thermalConductance;
      const Told = this._wireTemp.get(w.id) ?? Tamb;
      if (Tnew > wt.maxTemp) this._burned.add(w.id);
      this._wireTemp.set(w.id, Math.min(Tnew, wt.maxTemp));
      dT = Math.max(dT, Math.abs(Tnew - Told));
    }
    return dT;
  }

  // 瞬态热演进（欧拉）：T += (P − G·(T−Tamb))·dt/C；累计能量；导线过温烧毁
  updateTempsTran(h, currents, V) {
    const Tamb = WIRE_AMBIENT;
    for (const c of this.components) {
      const G = c.params.thermalConductance || 0, C = c.params.heatCapacity || 0;
      if (!(G > 0 && C > 0)) continue;
      const cur = currents.get(c.id);
      if (!cur) continue;
      let T = this._compTemp.get(c.id) ?? Tamb;
      T += (Math.abs(cur.p) - G * (T - Tamb)) * h / C;
      this._compTemp.set(c.id, T);
    }
    for (const w of this.wires) {
      const wt = this.wireType(w);
      const R = this.wireR(w);
      const I = this.wireCurrent(w, V);
      const P = I * I * R;
      let T = this._wireTemp.get(w.id) ?? Tamb;
      if (this._burned.has(w.id)) {
        T = wt.maxTemp;                    // 烧毁后保持温度（已销毁状态）
      } else {
        T += (P - wt.thermalConductance * (T - Tamb)) * h / wt.heatCapacity;
        if (T > wt.maxTemp) { T = wt.maxTemp; this._burned.add(w.id); }
      }
      this._wireTemp.set(w.id, T);
      this._wireEnergy.set(w.id, (this._wireEnergy.get(w.id) || 0) + P * h);
    }
  }

  _wireResults(V) {
    const res = new Map();
    for (const w of this.wires) {
      const wt = this.wireType(w);
      const R = this.wireR(w);
      const burned = this._burned.has(w.id);
      const I = burned ? 0 : this.wireCurrent(w, V);
      res.set(w.id, {
        type: w.type || 'copper', name: wt.name,
        length: this.wireLen(w), r: R,
        i: I, p: I * I * R,
        t: this._wireTemp.get(w.id) ?? WIRE_AMBIENT,
        ratedCurrent: wt.ratedCurrent,
        load: wt.ratedCurrent ? Math.abs(I) / wt.ratedCurrent : 0,
        energy: this._wireEnergy.get(w.id) || 0,
        burned
      });
    }
    return res;
  }

  // 各元件电流/功率/储能/温度
  computeCurrents(V, sol, mode, opts, state, vComps) {
    const N = this.netlist.numNodes;
    const currents = new Map();
    const trafos = this.components.filter(x => x.type === 'transformer' || x.type === 'variac');
    const trafoIdx = new Map();
    trafos.forEach((x, k) => trafoIdx.set(x, k));
    const tptrafos = this.components.filter(x => x.type === 'three_phase_transformer');
    const multiSrc = this.components.filter(x => x.type === 'three_phase_source' || x.type === 'vfd');
    const tbase = (N - 1) + vComps.length;
    const tpbase = tbase + trafos.length * 2;
    const msbase = tpbase + tptrafos.length * 6;
    for (const c of this.components) {
      const nd = c._nodeIdx;
      let i = 0, p = 0, stored = 0;
      switch (c.type) {
        case 'resistor': case 'potentiometer': case 'lamp': case 'heater': case 'bell': case 'carbon_pile': case 'rheostat': case 'plotter': case 'gauge': {
          const vd = V[nd[0]] - V[nd[1]];
          i = vd / Math.max(this.compR(c), 1e-9); p = i * vd;
          break;
        }
        case 'connector':   // 端子（单点汇流点）：无电流
          break;
        case 'antenna': {
          const R = Math.max((c.params.radiationR || 0) + (c.params.lossR || 0), 1e-6);
          const vd = V[nd[0]] - V[nd[1]];
          i = vd / R; p = i * vd;
          break;
        }
        case 'isource': {
          i = c.params.current || 0; p = i * (V[nd[0]] - V[nd[1]]);
          break;
        }
        case 'diode': case 'led': {
          const vd = V[nd[0]] - V[nd[1]];
          i = diodeIV(vd, c.params.Is || 1e-14, c.params.n || 1).i; p = i * vd;
          break;
        }
        case 'npn': {
          const bN = nd[0], cN = nd[1], eN = nd[2];
          const vbe = V[bN] - V[eN], vbc = V[bN] - V[cN];
          const Is = c.params.Is || 1e-14, bf = c.params.beta || 100, br = c.params.br || 2;
          const be = diodeIV(vbe, Is, 1);
          const bc = diodeIV(vbc, Is, 1);
          const ic = be.i - bc.i;
          const ib = be.i / bf + bc.i / br;
          currents.set(c.id, { i: ic, ic, ib, p: ic * (V[cN] - V[eN]), stored: 0 });
          if ((c.params.thermalConductance || 0) > 0 && (c.params.heatCapacity || 0) > 0) {
            currents.get(c.id).t = this._compTemp.get(c.id) ?? WIRE_AMBIENT;
          }
          continue;
        }
        case 'capacitor': {
          if (mode === 'dc') { i = 0; p = 0; }
          else {
            const g = (c.params.capacitance || 0) / opts.h;
            const vd = V[nd[0]] - V[nd[1]];
            const vp = state.capV.get(c.id) || 0;
            i = g * (vd - vp); p = i * vd;
            stored = 0.5 * (c.params.capacitance || 0) * vd * vd;
          }
          break;
        }
        case 'inductor': case 'fan': case 'motor': case 'electromagnet': case 'basin_heater': case 'speaker': case 'radio_receiver': case 'dc_motor': case 'induction_motor': {
          const L = Math.max(Math.abs(c.params.inductance) || 1e-9, 1e-9);
          const R = this.compR(c);
          const vd = V[nd[0]] - V[nd[1]];
          if (mode === 'dc') {
            i = vd / R;
          } else {
            const ip = state.indI.get(c.id) || 0;
            const k = 1 + (R * opts.h) / L;
            i = (opts.h / L) / k * vd + ip / k;
          }
          p = i * vd;
          stored = 0.5 * L * i * i;
          break;
        }
        case 'generator': case 'battery': case 'synchronous_motor': {
          const mi = vComps.indexOf(c);
          if (mi >= 0) i = sol[(N - 1) + mi];
          p = i * (V[nd[0]] - V[nd[2]]);
          break;
        }
        case 'transformer': case 'variac': {
          const k = trafoIdx.get(c);
          const i1 = sol[tbase + 2 * k], i2 = sol[tbase + 2 * k + 1];
          i = i1;
          p = i1 * (V[nd[0]] - V[nd[1]]);
          currents.set(c.id, { i, p, stored: 0, i1, i2 });
          continue;
        }
        case 'three_phase_transformer': {
          const k = tptrafos.indexOf(c);
          const base = tpbase + 6 * k;
          let pt = 0;
          for (let j = 0; j < 3; j++) {
            const i1j = sol[base + 2 * j], i2j = sol[base + 2 * j + 1];
            pt += i1j * (V[nd[j]] - V[nd[3]]);
            if (j === 0) { i = i1j; }
          }
          currents.set(c.id, { i, p: pt, stored: 0 });
          continue;
        }
        case 'three_phase_source': case 'vfd': {
          const k = multiSrc.indexOf(c);
          const base = msbase + 3 * k;
          const i1 = sol[base], i2 = sol[base + 1], i3 = sol[base + 2];
          const oi = c.type === 'vfd' ? 2 : 0, ni = c.type === 'vfd' ? 5 : 3;
          p = i1 * (V[nd[oi]] - V[nd[ni]]) + i2 * (V[nd[oi + 1]] - V[nd[ni]]) + i3 * (V[nd[oi + 2]] - V[nd[ni]]);
          currents.set(c.id, { i: i1, p, stored: 0, i1, i2, i3 });
          continue;
        }
        case 'three_phase_motor': case 'brushless_motor': {
          const R = this.compR(c);
          const L = Math.max(Math.abs(c.params.inductance) || 1e-9, 1e-9);
          let pt = 0, isq = 0;
          for (let j = 0; j < 3; j++) {
            const vd = V[nd[j]] - V[nd[3]];
            let i1j;
            if (mode === 'dc') { i1j = vd / R; }
            else {
              const ip = state.indI.get(c.id + '.' + j) || 0;
              const kk = 1 + (R * opts.h) / L;
              i1j = (opts.h / L) / kk * vd + ip / kk;
            }
            state.indI.set(c.id + '.' + j, i1j);
            pt += i1j * vd; isq += i1j * i1j;
            if (j === 0) i = i1j;
          }
          p = pt; stored = 0.5 * L * isq;
          break;
        }
        case 'electromachine': {
          if (c.params.mode === 'generator') {
            const mi = vComps.indexOf(c);
            if (mi >= 0) i = sol[(N - 1) + mi];
            p = i * (V[nd[0]] - V[nd[2]]);
          } else {
            const R = this.compR(c);
            const L = Math.max(Math.abs(c.params.inductance) || 1e-9, 1e-9);
            const vd = V[nd[0]] - V[nd[1]];
            if (mode === 'dc') { i = vd / R; }
            else {
              const ip = state.indI.get(c.id) || 0;
              const kk = 1 + (R * opts.h) / L;
              i = (opts.h / L) / kk * vd + ip / kk;
            }
            state.indI.set(c.id, i);
            p = i * vd; stored = 0.5 * L * i * i;
          }
          break;
        }
        case 'solar_panel': {
          i = c.params.photoCurrent || 0;
          p = i * (V[nd[0]] - V[nd[2]]);
          break;
        }
        case 'vccs': {
          const gm = c.params.transconductance || 0.01;
          i = gm * (V[nd[0]] - V[nd[1]]);
          p = i * (V[nd[2]] - V[nd[3]]);
          break;
        }
        case 'electron_tube': {
          const gm = (c.params.mu || 20) / (c.params.rp || 10000);
          const vcut = c.params.vCutoff != null ? c.params.vCutoff : -1;
          i = Math.max(0, gm * (V[nd[2]] - V[nd[1]] - vcut));
          p = i * (V[nd[0]] - V[nd[1]]);
          break;
        }
        case 'vfet': {
          const gm = c.params.gm || 0.05, vth = c.params.vth || 2;
          i = Math.max(0, gm * (V[nd[2]] - V[nd[1]] - vth));
          p = i * (V[nd[0]] - V[nd[1]]);
          break;
        }
        case 'rectifier': {
          const Is = 1e-12, n = 1.5;
          const i1 = diodeIV(V[nd[0]] - V[nd[2]], Is, n).i;
          const i2 = diodeIV(V[nd[1]] - V[nd[2]], Is, n).i;
          const i3 = diodeIV(V[nd[3]] - V[nd[0]], Is, n).i;
          const i4 = diodeIV(V[nd[3]] - V[nd[1]], Is, n).i;
          i = i1 + i2;
          p = i1 * (V[nd[0]] - V[nd[2]]) + i2 * (V[nd[1]] - V[nd[2]]) + i3 * (V[nd[3]] - V[nd[0]]) + i4 * (V[nd[3]] - V[nd[1]]);
          break;
        }
        case 'three_phase_rectifier': {
          const Is = 1e-12, n = 1.5;
          let it = 0, pt = 0;
          for (let j = 0; j < 3; j++) {
            const vd = V[nd[j]] - V[nd[4]];
            const i1j = diodeIV(vd, Is, n).i;
            it += i1j; pt += i1j * vd;
          }
          i = it; p = pt;
          break;
        }
        case 'mutual_inductor': {
          if (mode === 'dc') {
            i = (V[nd[0]] - V[nd[1]]) * 1e-9;   // 短路
            currents.set(c.id, { i: 0, p: 0, stored: 0, i1: 0, i2: 0 });
            continue;
          }
          const L1 = Math.max(Math.abs(c.params.l1) || 1e-6, 1e-9);
          const L2 = Math.max(Math.abs(c.params.l2) || 1e-6, 1e-9);
          const M = c.params.m || 0;
          const det = L1 * L2 - M * M;
          const v1 = V[nd[0]] - V[nd[1]], v2 = V[nd[2]] - V[nd[3]];
          if (Math.abs(det) < 1e-18) { currents.set(c.id, { i: 0, p: 0, stored: 0, i1: 0, i2: 0 }); continue; }
          const g11 = opts.h * L2 / det, g12 = -opts.h * M / det, g22 = opts.h * L1 / det;
          const i1 = g11 * v1 + g12 * v2, i2 = g12 * v1 + g22 * v2;
          state.mutV.set(c.id + '.1', v1);
          state.mutV.set(c.id + '.2', v2);
          p = i1 * v1 + i2 * v2;
          currents.set(c.id, { i: i1, p, stored: 0.5 * (L1 * i1 * i1 + 2 * M * i1 * i2 + L2 * i2 * i2), i1, i2 });
          continue;
        }
        case 'vcvs': case 'dc_dc': case 'inverter': {
          const mi = vComps.indexOf(c);
          if (mi >= 0) i = sol[(N - 1) + mi];
          p = i * (V[nd[2]] - V[nd[3]]);
          break;
        }
        case 'vsource': case 'acsource': case 'wavesource': case 'oscillator': case 'radio_transmitter': case 'switch': case 'fuse': case 'hvswitch': case 'breaker': case 'contactor': {
          const mi = vComps.indexOf(c);
          if (mi >= 0) i = sol[(N - 1) + mi];
          p = i * (V[nd[0]] - V[nd[1]]);
          break;
        }
      }
      const e = { i, p, stored };
      if ((c.params.thermalConductance || 0) > 0 && (c.params.heatCapacity || 0) > 0) {
        e.t = this._compTemp.get(c.id) ?? WIRE_AMBIENT;
      }
      currents.set(c.id, e);
    }
    return currents;
  }

  // 高斯消元（部分主元）
  gauss(A, b, n) {
    const M = A.map(r => Float64Array.from(r));
    const bb = Float64Array.from(b);
    for (let k = 0; k < n; k++) {
      let piv = k;
      for (let i = k + 1; i < n; i++) if (Math.abs(M[i][k]) > Math.abs(M[piv][k])) piv = i;
      if (Math.abs(M[piv][k]) < 1e-14) continue;
      if (piv !== k) {
        const t = M[k]; M[k] = M[piv]; M[piv] = t;
        const tb = bb[k]; bb[k] = bb[piv]; bb[piv] = tb;
      }
      const d = M[k][k];
      for (let i = k + 1; i < n; i++) {
        const f = M[i][k] / d;
        if (f === 0) continue;
        for (let j = k; j < n; j++) M[i][j] -= f * M[k][j];
        bb[i] -= f * bb[k];
      }
    }
    const x = new Float64Array(n);
    for (let i = n - 1; i >= 0; i--) {
      let s = bb[i];
      for (let j = i + 1; j < n; j++) s -= M[i][j] * x[j];
      x[i] = Math.abs(M[i][i]) > 1e-14 ? s / M[i][i] : 0;
    }
    return x;
  }

  // 求解一次直流/瞬态工作点。mode: 'dc' | 'tran'
  solve(mode, opts) {
    const nl = this.netlist;
    const N = nl.numNodes;
    const state = opts.state || { capV: new Map(), indI: new Map(), mutV: new Map() };

    // 电压源类（需要支路电流变量）；变压器 2 支路、三相变压器 6 支路、三相源/变频器 3 支路
    const vComps = [];
    for (const c of this.components) {
      if (c.type === 'vsource' || c.type === 'acsource' || c.type === 'wavesource'
        || c.type === 'generator' || c.type === 'battery' || c.type === 'synchronous_motor'
        || c.type === 'vcvs' || c.type === 'dc_dc' || c.type === 'inverter'
        || c.type === 'oscillator' || c.type === 'radio_transmitter'
        || (c.type === 'electromachine' && c.params.mode === 'generator')) vComps.push(c);
      else if ((c.type === 'switch' || c.type === 'fuse' || c.type === 'hvswitch' || c.type === 'breaker' || c.type === 'contactor') && c.params.closed) vComps.push(c);
    }
    const trafos = this.components.filter(x => x.type === 'transformer' || x.type === 'variac');
    const tptrafos = this.components.filter(x => x.type === 'three_phase_transformer');
    const multiSrc = this.components.filter(x => x.type === 'three_phase_source' || x.type === 'vfd');
    const M = vComps.length + trafos.length * 2 + tptrafos.length * 6 + multiSrc.length * 3;
    const size = (N - 1) + M;
    const row = (n) => n - 1;               // 节点 n(>=1) 对应行
    const branch = (m) => (N - 1) + m;      // 支路电流列

    let mat = null, b = null;
    // 初值：默认 0，瞬态时传上一时刻解可大幅提高非线性收敛性
    const V = Float64Array.from(opts.v0 || new Float64Array(N));
    const hasNonlinear = this.components.some(c => c.type === 'diode' || c.type === 'led' || c.type === 'npn' || c.type === 'rectifier' || c.type === 'three_phase_rectifier');
    const hasThermal = this._hasThermal();

    const stampG = (g, n1, n2, Is) => {
      if (n1 > 0) { mat[row(n1)][row(n1)] += g; b[row(n1)] -= Is; }
      if (n2 > 0) { mat[row(n2)][row(n2)] += g; b[row(n2)] += Is; }
      if (n1 > 0 && n2 > 0) { mat[row(n1)][row(n2)] -= g; mat[row(n2)][row(n1)] -= g; }
    };
    // 受控电流源：I = g*(va-vb)+I0，从 p 流向 q
    const stampCtrl = (g, a, bN, p, q, I0) => {
      if (p > 0) {
        if (a > 0) mat[row(p)][row(a)] += g;
        if (bN > 0) mat[row(p)][row(bN)] -= g;
        b[row(p)] -= I0;
      }
      if (q > 0) {
        if (a > 0) mat[row(q)][row(a)] -= g;
        if (bN > 0) mat[row(q)][row(bN)] += g;
        b[row(q)] += I0;
      }
    };
    // R-L 绕组伴随（直流=电阻；瞬态=后向欧拉）
    const stampRL = (na, nb, R, L, ip, mode2) => {
      if (mode2 === 'dc') { stampG(1 / Math.max(R, 1e-9), na, nb, 0); return; }
      const k = 1 + (R * opts.h) / L;
      stampG((opts.h / L) / k, na, nb, ip / k);
    };
    // 二极管伴随
    const stampD = (na, nb, pIs, pN) => {
      const vd = V[na] - V[nb];
      const m = diodeIV(vd, pIs, pN);
      stampG(m.g, na, nb, m.i - m.g * vd);
    };

    const stamp = () => {
      mat = Array.from({ length: size }, () => new Float64Array(size));
      b = new Float64Array(size);
      for (const c of this.components) {
        const nd = c._nodeIdx;
        switch (c.type) {
          case 'resistor': case 'potentiometer': case 'lamp': case 'heater': case 'bell': case 'carbon_pile': case 'rheostat': case 'plotter': case 'gauge': {
            stampG(1 / Math.max(this.compR(c), 1e-9), nd[0], nd[1], 0);
            break;
          }
          case 'connector':   // 端子（单点汇流点）：不 stamp（节点由导线归并）
            break;
          case 'antenna': {   // 辐射电阻 + 损耗电阻
            const R = Math.max((c.params.radiationR || 0) + (c.params.lossR || 0), 1e-6);
            stampG(1 / R, nd[0], nd[1], 0);
            break;
          }
          case 'isource': {
            const I = c.params.current || 0;
            if (nd[0] > 0) b[row(nd[0])] -= I;
            if (nd[1] > 0) b[row(nd[1])] += I;
            break;
          }
          case 'capacitor': {
            if (mode === 'dc') break;                       // 直流视为开路
            const g = (c.params.capacitance || 0) / opts.h;
            const vp = state.capV.get(c.id) || 0;
            stampG(g, nd[0], nd[1], -g * vp);
            break;
          }
          case 'inductor': case 'fan': case 'motor': case 'electromagnet': case 'basin_heater': case 'speaker': case 'radio_receiver': case 'dc_motor': case 'induction_motor': {  // R-L 绕组（组装器设备同款）
            if (mode === 'dc') { stampG(1 / this.compR(c), nd[0], nd[1], 0); break; }
            const L = Math.max(Math.abs(c.params.inductance) || 1e-9, 1e-9);
            const R = this.compR(c);
            const ip = state.indI.get(c.id) || 0;
            const k = 1 + (R * opts.h) / L;
            stampG((opts.h / L) / k, nd[0], nd[1], ip / k);
            break;
          }
          case 'generator': case 'battery': case 'synchronous_motor': {   // 内阻 R(A-X) 串联 EMF(X-B)
            stampG(1 / Math.max(this.compR(c), 1e-6), nd[0], nd[1], 0);
            break;
          }
          case 'electromachine': {
            if (c.params.mode === 'generator') {
              stampG(1 / Math.max(this.compR(c), 1e-6), nd[0], nd[1], 0);
            } else {
              const R = this.compR(c);
              const L = Math.max(Math.abs(c.params.inductance) || 1e-9, 1e-9);
              stampRL(nd[0], nd[1], R, L, state.indI.get(c.id) || 0, mode);
            }
            break;
          }
          case 'three_phase_motor': case 'brushless_motor': {   // 3× R-L 绕组（Y 型，各相对 N）
            const R = this.compR(c);
            const L = Math.max(Math.abs(c.params.inductance) || 1e-9, 1e-9);
            for (let i = 0; i < 3; i++) {
              stampRL(nd[i], nd[3], R, L, state.indI.get(c.id + '.' + i) || 0, mode);
            }
            break;
          }
          case 'solar_panel': {   // 光生电流源 + 串联电阻（内部节点 X）
            stampG(1 / Math.max(this.compR(c), 1e-6), nd[0], nd[1], 0);
            const I0 = c.params.photoCurrent || 0;
            if (nd[1] > 0) b[row(nd[1])] -= I0;
            if (nd[2] > 0) b[row(nd[2])] += I0;
            break;
          }
          case 'vccs': {   // 压控电流源：I = gm·(Vc+ − Vc−) 从 io+ 流向 io−
            stampCtrl(c.params.transconductance || 0.01, nd[0], nd[1], nd[2], nd[3], 0);
            break;
          }
          case 'electron_tube': {   // 三极管线性近似：Ia = gm·(Vgk − vcut)
            const gm = (c.params.mu || 20) / (c.params.rp || 10000);
            const vcut = c.params.vCutoff != null ? c.params.vCutoff : -1;
            stampCtrl(gm, nd[2], nd[1], nd[0], nd[1], -gm * vcut);
            break;
          }
          case 'vfet': {   // VFET 线性近似：Id = gm·(Vgs − vth)
            const gm = c.params.gm || 0.05, vth = c.params.vth || 2;
            stampCtrl(gm, nd[2], nd[1], nd[0], nd[1], -gm * vth);
            break;
          }
          case 'rectifier': {   // 全桥 4 二极管 + 滤波电容 + 负载
            const Is = 1e-12, n = 1.5;
            stampD(nd[0], nd[2], Is, n);
            stampD(nd[1], nd[2], Is, n);
            stampD(nd[3], nd[0], Is, n);
            stampD(nd[3], nd[1], Is, n);
            if (mode !== 'dc') stampG((c.params.filterCap || 0) / opts.h, nd[2], nd[3], 0);
            stampG(1 / Math.max(c.params.loadR || 1e6, 1e-6), nd[2], nd[3], 0);
            break;
          }
          case 'three_phase_rectifier': {   // 3 二极管（相→DC+）+ 滤波 + 负载
            const Is = 1e-12, n = 1.5;
            for (let i = 0; i < 3; i++) stampD(nd[i], nd[4], Is, n);
            if (mode !== 'dc') stampG((c.params.filterCap || 0) / opts.h, nd[4], nd[5], 0);
            stampG(1 / Math.max(c.params.loadR || 1e6, 1e-6), nd[4], nd[5], 0);
            break;
          }
          case 'mutual_inductor': {   // 耦合电感：BE 伴随（直流=短路）
            if (mode === 'dc') {
              stampG(1e9, nd[0], nd[1], 0);
              stampG(1e9, nd[2], nd[3], 0);
              break;
            }
            const L1 = Math.max(Math.abs(c.params.l1) || 1e-6, 1e-9);
            const L2 = Math.max(Math.abs(c.params.l2) || 1e-6, 1e-9);
            const M = c.params.m || 0;
            const det = L1 * L2 - M * M;
            if (Math.abs(det) < 1e-18) break;
            const g11 = opts.h * L2 / det, g12 = -opts.h * M / det, g22 = opts.h * L1 / det;
            const v1p = state.mutV.get(c.id + '.1') || 0;
            const v2p = state.mutV.get(c.id + '.2') || 0;
            stampG(g11, nd[0], nd[1], -(g11 * v1p + g12 * v2p));
            stampG(g22, nd[2], nd[3], -(g12 * v1p + g22 * v2p));
            stampCtrl(g12, nd[2], nd[3], nd[0], nd[1], 0);
            stampCtrl(g12, nd[0], nd[1], nd[2], nd[3], 0);
            break;
          }
          case 'diode': case 'led': {
            const vd = V[nd[0]] - V[nd[1]];
            const m = diodeIV(vd, c.params.Is || 1e-14, c.params.n || 1);
            stampG(m.g, nd[0], nd[1], m.i - m.g * vd);
            break;
          }
          case 'npn': {                                     // 简化的 Ebers-Moll 输运模型（带钳位，电导有界）
            const bN = nd[0], cN = nd[1], eN = nd[2];
            const vbe = V[bN] - V[eN], vbc = V[bN] - V[cN];
            const Is = c.params.Is || 1e-14, bf = c.params.beta || 100, br = c.params.br || 2;
            const be = diodeIV(vbe, Is, 1);
            const bc = diodeIV(vbc, Is, 1);
            const ic = be.i - bc.i;
            const ib = be.i / bf + bc.i / br;
            const g_cbe = be.g, g_cbc = -bc.g;
            const g_bf = be.g / bf, g_br = bc.g / br;
            stampCtrl(g_cbe, bN, eN, cN, eN, ic - g_cbe * vbe - g_cbc * vbc);
            stampCtrl(g_cbc, bN, cN, cN, eN, 0);
            stampCtrl(g_bf, bN, eN, bN, eN, ib - g_bf * vbe - g_br * vbc);
            stampCtrl(g_br, bN, cN, bN, eN, 0);
            break;
          }
        }
      }
      // 导线段（电阻支路；烧毁=开路；R=电阻率×段长）
      for (const seg of nl.wireSegments) {
        if (this._burned.has(seg.wire.id)) continue;
        const R = Math.max(this.wireType(seg.wire).resistancePerMeter * Math.max(seg.length, 0.001), 1e-9);
        stampG(1 / R, seg.n1, seg.n2, 0);
      }
      // 电压源支路
      vComps.forEach((c, mi) => {
        const nd = c._nodeIdx;
        const col = branch(mi);
        let val = 0, sa, sb;
        if (c.type === 'vsource') { val = c.params.voltage || 0; sa = nd[0]; sb = nd[1]; }
        else if (c.type === 'acsource') { val = (c.params.amplitude || 0) * Math.sin(2 * Math.PI * (c.params.frequency || 0) * opts.t); sa = nd[0]; sb = nd[1]; }
        else if (c.type === 'wavesource') { val = waveValue(c, opts.t); sa = nd[0]; sb = nd[1]; }
        else if (c.type === 'oscillator' || c.type === 'radio_transmitter') { val = (c.params.amplitude || 0) * Math.sin(2 * Math.PI * (c.params.frequency || 0) * opts.t); sa = nd[0]; sb = nd[1]; }
        else if (c.type === 'generator' || c.type === 'battery' || c.type === 'synchronous_motor' || (c.type === 'electromachine' && c.params.mode === 'generator')) { val = c.params.voltage || 0; sa = nd[1]; sb = nd[2]; }
        else if (c.type === 'vcvs' || c.type === 'dc_dc' || c.type === 'inverter') {
          // 压控电压源：Vout = gain·Vin；控制 (nd0,nd1) 输出 (nd2,nd3)
          const gain = (c.params.gain != null ? c.params.gain : c.params.ratio) || 1;
          val = 0; sa = nd[2]; sb = nd[3];
          if (nd[0] > 0) mat[col][row(nd[0])] -= gain;
          if (nd[1] > 0) mat[col][row(nd[1])] += gain;
        }
        else { val = 0; sa = nd[0]; sb = nd[1]; }   // 闭合开关类（switch/fuse/hvswitch/breaker/contactor）= 0V 理想短接
        if (sa > 0) { mat[row(sa)][col] += 1; mat[col][row(sa)] += 1; }
        if (sb > 0) { mat[row(sb)][col] -= 1; mat[col][row(sb)] -= 1; }
        b[col] = val;
      });
      // 理想变压器支路（transformer/variac）：V2 = n·V1；I1 + (1/n)·I2 = 0（2 支路变量）
      trafos.forEach((c, k) => {
        const nd = c._nodeIdx;                              // [a1, a2, b1, b2]
        const base = (N - 1) + vComps.length + 2 * k;
        const cI1 = base, cI2 = base + 1, r1 = base, r2 = base + 1;
        const n = Math.max(Math.abs(c.params.ratio) || 1, 1e-6);
        if (nd[0] > 0) mat[row(nd[0])][cI1] += 1;           // KCL：I1 流入 a1
        if (nd[1] > 0) mat[row(nd[1])][cI1] -= 1;           //      I1 流出 a2
        if (nd[2] > 0) mat[row(nd[2])][cI2] += 1;           // KCL：I2 流入 b1
        if (nd[3] > 0) mat[row(nd[3])][cI2] -= 1;           //      I2 流出 b2
        const addN = (idx, val) => { if (idx > 0) mat[r1][row(idx)] += val; };
        addN(nd[0], 1); addN(nd[1], -1); addN(nd[2], -1 / n); addN(nd[3], 1 / n);   // 约束1：V1 - V2/n = 0
        mat[r2][cI1] += 1; mat[r2][cI2] += 1 / n;           // 约束2：I1 + I2/n = 0
        mat[r1][cI1] += 1e-9; mat[r2][cI2] += 1e-9;         // 防奇异弱导纳（浮空时）
      });
      // 三相变压器：3× 理想变压器（pa1..3,n1, pb1..3,n2，每相 2 支路）
      tptrafos.forEach((c, k) => {
        const nd = c._nodeIdx;
        const base = (N - 1) + vComps.length + trafos.length * 2 + 6 * k;
        const n = Math.max(Math.abs(c.params.ratio) || 1, 1e-6);
        for (let i = 0; i < 3; i++) {
          const a1 = nd[i], a2 = nd[3], b1 = nd[4 + i], b2 = nd[7];
          const cI1 = base + 2 * i, cI2 = base + 2 * i + 1, r1 = base + 2 * i, r2 = base + 2 * i + 1;
          if (a1 > 0) mat[row(a1)][cI1] += 1;
          if (a2 > 0) mat[row(a2)][cI1] -= 1;
          if (b1 > 0) mat[row(b1)][cI2] += 1;
          if (b2 > 0) mat[row(b2)][cI2] -= 1;
          const addN = (idx, val2) => { if (idx > 0) mat[r1][row(idx)] += val2; };
          addN(a1, 1); addN(a2, -1); addN(b1, -1 / n); addN(b2, 1 / n);
          mat[r2][cI1] += 1; mat[r2][cI2] += 1 / n;
          mat[r1][cI1] += 1e-9; mat[r2][cI2] += 1e-9;
        }
      });
      // 三相电源 / 变频器：3× 交流源（0/120/240°）
      multiSrc.forEach((c, k) => {
        const nd = c._nodeIdx;
        const base = (N - 1) + vComps.length + trafos.length * 2 + tptrafos.length * 6 + 3 * k;
        const amp = c.params.amplitude || 0, freq = c.params.frequency || 0;
        const outIdx = c.type === 'vfd' ? 2 : 0;
        const nIdx = c.type === 'vfd' ? 5 : 3;
        for (let i = 0; i < 3; i++) {
          const col = base + i;
          const sa = nd[outIdx + i], sb = nd[nIdx];
          const val = amp * Math.sin(2 * Math.PI * freq * opts.t + i * 2 * Math.PI / 3);
          if (sa > 0) { mat[row(sa)][col] += 1; mat[col][row(sa)] += 1; }
          if (sb > 0) { mat[row(sb)][col] -= 1; mat[col][row(sb)] -= 1; }
          b[col] = val;
        }
      });
    };

    const solveLinearized = () => {
      let sol = null;
      const iters = hasNonlinear ? 80 : 1;
      const damp = hasNonlinear ? 0.85 : 1;      // 轻微阻尼，防止非线性振荡
      let converged = true;
      for (let iter = 0; iter < iters; iter++) {
        stamp();
        sol = this.gauss(mat, b, size);
        const Vnew = new Float64Array(N);
        for (let n = 1; n < N; n++) Vnew[n] = sol[n - 1];
        let dmax = 0;
        for (let n = 0; n < N; n++) {
          dmax = Math.max(dmax, Math.abs(Vnew[n] - V[n]));
          V[n] = V[n] + damp * (Vnew[n] - V[n]);
        }
        if (dmax < 1e-10) { converged = true; break; }
        converged = false;
      }
      if (!hasNonlinear) converged = true;   // 线性电路一次求解即为精确解
      return { sol, converged };
    };

    let currents = null, converged = true;
    if (mode === 'dc' && hasThermal) {
      // 直流热稳态：R(T) ↔ 温度 外循环
      for (let ti = 0; ti < 12; ti++) {
        const r = solveLinearized();
        converged = r.converged;
        currents = this.computeCurrents(V, r.sol, mode, opts, state, vComps);
        const dT = this.updateTempsDC(V, currents);
        if (dT < 0.1) break;
      }
    } else {
      const r = solveLinearized();
      converged = r.converged;
      currents = this.computeCurrents(V, r.sol, mode, opts, state, vComps);
      if (mode === 'tran') {
        this.updateTempsTran(opts.h, currents, V);
        // 能量模型：累计每个元件的能量 ∫P·dt
        for (const c of this.components) {
          const cur = currents.get(c.id);
          if (cur) this._compEnergy.set(c.id, (this._compEnergy.get(c.id) || 0) + cur.p * opts.h);
        }
      }
    }

    // 更新动态元件状态
    if (mode === 'tran') {
      for (const c of this.components) {
        if (c.type === 'capacitor') state.capV.set(c.id, V[c._nodeIdx[0]] - V[c._nodeIdx[1]]);
        else if (c.type === 'inductor' || c.type === 'fan' || c.type === 'motor' || c.type === 'electromagnet') state.indI.set(c.id, currents.get(c.id).i);
      }
    }

    this.lastRes = {
      V, mode, t: opts.t, h: opts.h, currents, converged,
      wires: this._wireResults(V),                 // 导线：R/I/P/T/负载/能量/烧毁
      energy: this._compEnergy,                    // 元件累计能量
      burnedWires: Array.from(this._burned)        // 烧毁导线 id 列表
    };
    return this.lastRes;
  }

  // ---------- 瞬态仿真（增量执行，便于 UI 分块刷新） ----------
  // init: 'zero'(默认，电容0V/电感0A) | 'dc'(以直流工作点为初始状态)
  startTransient(duration, h, probeIdx, init = 'zero') {
    this.stopped = false;
    this.state = { capV: new Map(), indI: new Map(), mutV: new Map() };
    if (init === 'dc') {
      this.solve('dc', { t: 0, h, state: this.state });      // 直流初始工作点
      const dc = this.lastRes;
      for (const c of this.components) {
        if (c.type === 'capacitor') this.state.capV.set(c.id, dc.V[c._nodeIdx[0]] - dc.V[c._nodeIdx[1]]);
        else if (c.type === 'inductor' || c.type === 'fan' || c.type === 'motor' || c.type === 'electromagnet') this.state.indI.set(c.id, dc.currents.get(c.id).i);
      }
      this._lastV = dc.V;
    } else {
      this._lastV = null;
    }
    this.t = 0;
    this.duration = duration;
    this.h = h;
    this.probeIdx = probeIdx;
    this.times = [];
    this.data = probeIdx.map(() => []);
    this.done = false;
  }

  transientChunk(n) {
    if (this.done) return true;
    for (let k = 0; k < n && this.t <= this.duration && !this.stopped; k++) {
      const res = this.solve('tran', { t: this.t, h: this.h, state: this.state, v0: this._lastV });
      this._lastV = res.V;
      this.times.push(this.t);
      this.probeIdx.forEach((ni, i) => this.data[i].push(res.V[ni]));
      this.t += this.h;
    }
    if (this.t > this.duration || this.stopped) this.done = true;
    return this.done;
  }
}

// ---------- 对外 API：核心算法可直接喂入任意电路数据（JSON） ----------
// circuit 格式: { components:[{type,x,y,rotation,params}], wires:[{points:[{x,y},...]}|[x,y]...], probes:[{x,y}] }
// 元件引脚按定义顺序编号；节点 0 为地。返回 { V, currents, ... }

function normalizeCircuit(circuit) {
  const comps = (circuit.components || []).map(c => ({
    id: c.id,
    type: c.type,
    x: c.x || 0,
    y: c.y || 0,
    rotation: c.rotation || 0,
    params: Object.assign({}, COMPONENT_DEFS[c.type] ? COMPONENT_DEFS[c.type].params : {}, c.params || {})
  }));
  // 补全元件 id（Map 键需要唯一）
  if (comps.some(c => c.id === undefined || c.id === null)) {
    comps.forEach((c, i) => { c.id = i + 1; });
  }
  const wires = (circuit.wires || []).map(w => ({
    id: w.id,
    type: w.type,
    points: Array.isArray(w) ? w.map(p => ({ x: p.x, y: p.y })) : w.points.map(p => ({ x: p.x, y: p.y }))
  }));
  // 补全导线 id
  if (wires.some(w => w.id === undefined || w.id === null)) {
    wires.forEach((w, i) => { w.id = i + 1; });
  }
  return { components: comps, wires, probes: circuit.probes || [] };
}

// 直流工作点分析
function solveCircuitJSON(circuit, opts = {}) {
  const c = normalizeCircuit(circuit);
  const sim = new Simulator();
  sim.setCircuit(c.components, c.wires, opts.wireResistive !== false);
  const res = sim.solve('dc', { t: 0, h: opts.h || 1e-3, state: { capV: new Map(), indI: new Map() } });
  res.netlist = sim.netlist;
  return res;
}

// 瞬态仿真。opts: { duration, step, probes(节点号数组), init('zero'|'dc'), wireResistive }；probes 也可传位置字符串 'x,y'
function transientJSON(circuit, opts = {}) {
  const c = normalizeCircuit(circuit);
  const sim = new Simulator();
  sim.setCircuit(c.components, c.wires, opts.wireResistive !== false);
  const dur = opts.duration != null ? opts.duration : 0.05;
  const h = opts.step != null ? opts.step : 1e-4;
  let probeIdx = [];
  const rawProbes = (opts.probes && opts.probes.length) ? opts.probes : (c.probes || []).map(p => p.x + ',' + p.y);
  for (const p of rawProbes) {
    const m = /^([-0-9.]+),([-0-9.]+)$/.exec(String(p));
    if (m) probeIdx.push(sim.netlist.nodeForPoint(parseFloat(m[1]), parseFloat(m[2])));
    else probeIdx.push(parseInt(p, 10));
  }
  sim.startTransient(dur, h, probeIdx, opts.init || 'zero');
  let guard = 0;
  while (!sim.transientChunk(5000) && guard++ < 1e6) { }
  return { times: sim.times, data: sim.data, last: sim.lastRes, netlist: sim.netlist };
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { DynUF, Netlist, Simulator, normalizeCircuit, solveCircuitJSON, transientJSON };
}
