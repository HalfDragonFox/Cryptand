'use strict';
// check_kernel.js — 前端内核桥接自检：验证电路 → 内核网表请求 的序列化
// 用法: node test/check_kernel.js
require('./js/components.js');
require('./js/simulator.js');
const { buildKernelRequest, normalizeKernelResponse } = require('./js/kernel.js');

// 模拟一个 LED 指示电路（与 examples/led_indicator.json 相同的拓扑）
const components = [
  { id: 1, type: 'vsource', x: 80, y: 120, rotation: 180, params: { voltage: 5 } },
  { id: 2, type: 'resistor', x: 280, y: 120, rotation: 0, params: { resistance: 330 } },
  { id: 3, type: 'led', x: 450, y: 120, rotation: 0, params: { Is: 1e-12, n: 2 } },
  { id: 4, type: 'ground', x: 540, y: 120, rotation: 0, params: {} },
  { id: 5, type: 'ground', x: 30, y: 200, rotation: 0, params: {} }
];
const wires = [
  { id: 1, type: 'copper', points: [{ x: 130, y: 120 }, { x: 230, y: 120 }] },
  { id: 2, type: 'copper', points: [{ x: 330, y: 120 }, { x: 400, y: 120 }] },
  { id: 3, type: 'copper', points: [{ x: 500, y: 120 }, { x: 540, y: 120 }] },
  { id: 4, type: 'copper', points: [{ x: 30, y: 120 }, { x: 30, y: 200 }] }
];
const editor = { components, wires };

const req = buildKernelRequest(editor, 'dc');
console.log('===== 内核网表请求 =====');
console.log('mode:', req.mode, '| nodeCount:', req.nodeCount, '| ground:', req.groundNode);
console.log('元素数:', req.elements.length, '| 探针:', req.probes);
req.elements.forEach(e => console.log('  ', e.kind, e.a + '→' + e.b, JSON.stringify(e).slice(0, 120)));
console.log('警告:', req.warnings);

// 模拟内核响应（DC）
const res = {
  ok: true, solver: 'RealMnaSolver', mode: 'dc', converged: true, iterations: 1,
  nodeVoltages: [0, 5, 4.998, 1.198, 1.197, 0, 0],
  components: [
    { id: 1, i: -0.0115, p: -0.0576, t: 20, energy: 0, stored: 0 },
    { id: 2, i: 0.0115, p: 0.0438, t: 20, energy: 0, stored: 0 },
    { id: 3, i: 0.0115, p: 0.0138, t: 20, energy: 0, stored: 0 }
  ],
  wires: [
    { id: 1, r: 0.075, length: 100, i: 0.0115, p: 9.9e-6, t: 20, energy: 0, burned: false, type: 'copper', name: '铜线', ratedCurrent: 80, load: 0.0001 }
  ],
  burnedWires: []
};
const norm = normalizeKernelResponse(res, req);
console.log('\n===== 归一化结果（前端展示结构） =====');
console.log('节点电压:', Array.from(norm.lastRes.V));
console.log('元件 currents:', Array.from(norm.lastRes.currents.entries()));
console.log('导线:', Array.from(norm.lastRes.wires.entries()));
console.log('converged:', norm.lastRes.converged);
console.log('\n✅ 序列化/归一化自检完成');
