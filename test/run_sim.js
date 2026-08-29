'use strict';
// run_sim.js — 命令行电路仿真测试工具（调用核心算法，喂入任意电路 JSON）
//
// 用法:
//   node test/run_sim.js <circuit.json> [选项]
//
// 选项:
//   --tran <秒>      执行瞬态仿真（默认仅做直流工作点分析）
//   --step <秒>      瞬态步长（默认 0.0001 s）
//   --probe <x,y|节点号>  添加探针，可多次；默认取文件内 probes 的位置
//   --csv <文件>     将探针波形写入 CSV
//   --nodes          打印节点编号对照表
//   --init zero|dc   瞬态初始状态：zero=零状态(默认)，dc=直流工作点
//
// 电路 JSON 格式（与编辑器保存格式一致，也支持更精简的数据）:
// {
//   "components": [ { "type":"resistor", "x":220, "y":120, "rotation":0, "params":{"resistance":1000} } ],
//   "wires":      [ [ {"x":110,"y":120}, {"x":170,"y":120} ] ],     // 或 {points:[...]}
//   "probes":     [ {"x":350,"y":120} ]
// }

const fs = require('fs');
const { Simulator } = require('./js/simulator.js');
const { COMPONENT_DEFS, formatSI, absTerminal } = require('./js/components.js');

function fail(msg) { console.error('错误: ' + msg); process.exit(1); }

function parseArgs() {
  const out = { tran: null, step: 0.0001, probe: [], csv: null, nodes: false, init: 'zero' };
  const a = process.argv.slice(2);
  for (let i = 1; i < a.length; i++) {
    const k = a[i];
    if (k === '--tran') out.tran = parseFloat(a[++i]);
    else if (k === '--step') out.step = parseFloat(a[++i]);
    else if (k === '--probe') out.probe.push(a[++i]);
    else if (k === '--csv') out.csv = a[++i];
    else if (k === '--nodes') out.nodes = true;
    else if (k === '--init') out.init = a[++i];
    else fail('未知选项 ' + k);
  }
  return out;
}

function loadCircuit(file) {
  if (!fs.existsSync(file)) fail('找不到文件: ' + file);
  let circuit;
  try { circuit = JSON.parse(fs.readFileSync(file, 'utf8')); }
  catch (e) { fail('JSON 解析失败: ' + e.message); }
  const comps = (circuit.components || []).map(c => {
    const def = COMPONENT_DEFS[c.type];
    if (!def) fail('未知元件类型: ' + c.type);
    return {
      id: c.id,
      type: c.type,
      x: c.x || 0,
      y: c.y || 0,
      rotation: c.rotation || 0,
      params: Object.assign({}, def.params, c.params || {})
    };
  });
  if (comps.some(c => c.id === undefined || c.id === null)) comps.forEach((c, i) => { c.id = i + 1; });
  const wires = (circuit.wires || []).map(w => ({
    points: Array.isArray(w) ? w.map(p => ({ x: p.x, y: p.y })) : w.points.map(p => ({ x: p.x, y: p.y }))
  }));
  return { components: comps, wires, probes: circuit.probes || [] };
}

function main() {
  const file = process.argv[2];
  if (!file) {
    console.log('用法: node test/run_sim.js <circuit.json> [--tran 秒] [--step 秒] [--probe x,y|节点号] [--csv 文件] [--nodes] [--init zero|dc]');
    process.exit(1);
  }
  const circuit = loadCircuit(file);
  const opts = parseArgs();

  const sim = new Simulator();
  sim.setCircuit(circuit.components, circuit.wires);

  console.log('===== 电路信息 =====');
  console.log('元件: ' + circuit.components.length + ' 个, 导线: ' + circuit.wires.length + ' 条, 节点: ' + sim.netlist.numNodes + ' 个');
  for (const c of circuit.components) {
    const def = COMPONENT_DEFS[c.type];
    console.log('  [' + c.id + '] ' + def.name + '  ' + def.valueOf(c) + '  引脚节点: ' + c._nodeIdx.join(','));
  }
  if (opts.nodes) {
    console.log('---- 节点编号对照 ----');
    for (const c of circuit.components) {
      const def = COMPONENT_DEFS[c.type];
      for (let i = 0; i < def.terminals.length; i++) {
        const t = absTerminal(c, i);
        console.log('  N' + c._nodeIdx[i] + ' <- (' + t.x + ',' + t.y + ')  ' + def.name + '[' + c.id + '] 引脚' + i);
      }
    }
  }

  // 直流工作点
  console.log('===== 直流工作点 =====');
  const dc = sim.solve('dc', { t: 0, h: 1e-3, state: { capV: new Map(), indI: new Map() } });
  console.log('收敛: ' + (dc.converged ? '是' : '否'));
  for (let n = 1; n < dc.V.length; n++) {
    console.log('  节点 N' + n + ' = ' + dc.V[n].toFixed(6) + ' V');
  }
  for (const c of circuit.components) {
    const cur = dc.currents.get(c.id);
    if (!cur) continue;
    const def = COMPONENT_DEFS[c.type];
    console.log('  ' + def.name + '[' + c.id + ']  I=' + formatSI(cur.i, 'A') + '  P=' + formatSI(cur.p, 'W'));
  }

  // 探针解析
  const rawProbes = opts.probe.length ? opts.probe : (circuit.probes || []).map(p => p.x + ',' + p.y);
  const probeIdx = rawProbes.map(rp => {
    const m = /^([-0-9.]+),([-0-9.]+)$/.exec(String(rp));
    return m ? sim.netlist.nodeForPoint(parseFloat(m[1]), parseFloat(m[2])) : parseInt(rp, 10);
  });

  // 瞬态仿真
  if (opts.tran != null) {
    console.log('===== 瞬态仿真 =====');
    console.log('时长 ' + opts.tran + ' s, 步长 ' + opts.step + ' s, 步数 ' + Math.ceil(opts.tran / opts.step) + ', 初始状态: ' + (opts.init === 'dc' ? '直流工作点' : '零状态'));
    sim.startTransient(opts.tran, opts.step, probeIdx, opts.init);
    let guard = 0;
    while (!sim.transientChunk(5000) && guard++ < 1e6) { }
    console.log('结束于 t=' + sim.t.toFixed(6) + ' s, 实际步数=' + sim.times.length);
    probeIdx.forEach((ni, k) => {
      const arr = sim.data[k];
      if (!arr.length) return;
      const last = arr[arr.length - 1];
      const vmin = Math.min(...arr);
      const vmax = Math.max(...arr);
      console.log('  探针' + (k + 1) + ' (N' + ni + '): 终值=' + last.toFixed(6) + ' V, 最小=' + vmin.toFixed(6) + ', 最大=' + vmax.toFixed(6));
    });
    if (opts.csv) {
      let csv = 't';
      probeIdx.forEach((ni, k) => { csv += ',N' + ni; });
      csv += '\n';
      for (let i = 0; i < sim.times.length; i++) {
        csv += sim.times[i].toFixed(9);
        for (let k = 0; k < probeIdx.length; k++) csv += ',' + sim.data[k][i].toFixed(9);
        csv += '\n';
      }
      fs.writeFileSync(opts.csv, csv);
      console.log('波形已写入 ' + opts.csv);
    }
  }
  console.log('完成。');
}

main();
