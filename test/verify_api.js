'use strict';
// verify_api.js — 核心算法 API 自检脚本
// 用法: node test/verify_api.js
// 直接调用 test/js/simulator.js 导出的 API，喂入电路数据（JSON），检查仿真结果是否与理论值一致。
// 新增测试直接在这里加，或把电路数据写成 JSON 放到 test/examples/ 下。

const { solveCircuitJSON, transientJSON } = require('./js/simulator.js');

let pass = 0, fail = 0;
function check(name, actual, expected, tol = 1e-3, unit = '') {
  const ok = Math.abs(actual - expected) <= tol;
  if (ok) { pass++; console.log('  ✓ ' + name + ' = ' + actual.toFixed(6) + unit); }
  else { fail++; console.log('  ✗ ' + name + ' = ' + actual.toFixed(6) + unit + '（期望 ' + expected + ' ±' + tol + '）'); }
}
function checkRange(name, actual, lo, hi, unit = '') {
  const ok = actual >= lo && actual <= hi;
  if (ok) { pass++; console.log('  ✓ ' + name + ' = ' + actual.toFixed(6) + unit); }
  else { fail++; console.log('  ✗ ' + name + ' = ' + actual.toFixed(6) + unit + '（期望范围 ' + lo + '~' + hi + '）'); }
}

// ---------- 1. 电阻分压（线性直流） ----------
console.log('\n[1] 电阻分压 (10V / 10k+5k → 理论 3.333V, 电流 0.667mA)');
{
  const div = solveCircuitJSON(require('./examples/voltage_divider.json'));
  const vMid = div.V[div.netlist.nodeForPoint(380, 120)];   // R1/R2 分压中点（导线带电阻，节点编号变化）
  check('分压点电压', vMid, 3.333333, 1e-3, ' V');
  check('串联电流 (R1)', div.currents.get(2).i, 6.6667e-4, 1e-6, ' A');
  check('R2 功率', div.currents.get(3).p, 2.2222e-3, 1e-6, ' W');
}

// ---------- 2. RC 充电（瞬态） ----------
console.log('\n[2] RC 充电 (5V, 1k + 100µF, τ=0.1s, t=0.05s → 理论 1.967V)');
{
  const t = transientJSON(require('./examples/rc_charge.json'), { duration: 0.05, step: 0.0001 });
  const last = t.data[0][t.data[0].length - 1];
  check('电容电压 @0.05s', last, 5 * (1 - Math.exp(-0.5)), 2e-3, ' V');
}

// ---------- 3. 二极管半波整流（非线性瞬态） ----------
console.log('\n[3] 二极管半波整流 (5V/50Hz 交流 → 峰值约 5-0.7=4.3V)');
{
  const t = transientJSON(require('./examples/rectifier.json'), { duration: 0.04, step: 0.0001 });
  const vmax = Math.max(...t.data[0]);
  const vmin = Math.min(...t.data[0]);
  check('负载电压峰值', vmax, 4.3075, 5e-3, ' V');
  check('负半周被截止 (min≈0)', vmin, 0, 1e-6, ' V');
}

// ---------- 4. LED 指示（非线性直流，发光） ----------
console.log('\n[4] LED 指示 (5V / 330Ω + LED，导通压降约 1.1~1.3V，电流约 11mA)');
{
  const led = solveCircuitJSON(require('./examples/led_indicator.json'));
  // LED 为第 3 个元件（id=3）
  const cur = led.currents.get(3);
  const vLed = 5 - cur.i * 330;                    // LED 压降 = 电源电压 - 电阻压降
  checkRange('LED 导通电流', cur.i * 1000, 8, 15, ' mA');
  checkRange('LED 压降', vLed, 1.0, 1.6, ' V');
}

// ---------- 5. NPN 共射放大（直流偏置） ----------
console.log('\n[5] NPN 共射偏置 (β=100, Rb=100k, Rc=1k, 5V 基极 / 10V 集电极)');
{
  const circuit = {
    components: [
      { type: 'vsource',  x: 200, y: 40,  rotation: 180, params: { voltage: 10 } },   // 集电极电源 Vcc
      { type: 'vsource',  x: 50,  y: 80,  rotation: 180, params: { voltage: 5 } },    // 基极电源 Vb
      { type: 'resistor', x: 300, y: 40,  rotation: 0, params: { resistance: 1000 } },// Rc
      { type: 'resistor', x: 150, y: 80,  rotation: 0, params: { resistance: 100000 } },// Rb
      { type: 'npn',      x: 350, y: 80,  rotation: 0, params: { beta: 100 } },       // Q1
      { type: 'ground',   x: 150, y: 140, rotation: 0, params: {} },
      { type: 'ground',   x: 0,   y: 140, rotation: 0, params: {} },
      { type: 'ground',   x: 350, y: 200, rotation: 0, params: {} }
    ],
    wires: [
      [{ x: 310, y: 80 }, { x: 200, y: 80 }],   // B → Rb
      [{ x: 150, y: 40 }, { x: 150, y: 140 }],  // Vcc- → gnd
      [{ x: 0,   y: 80 }, { x: 0,   y: 140 }],  // Vb- → gnd
      [{ x: 350, y: 120 }, { x: 350, y: 200 }]  // E → gnd
    ]
  };
  const res = solveCircuitJSON(circuit);
  const nl = res.netlist;
  const vC = res.V[nl.nodeForPoint(350, 40)];   // 集电极
  const vB = res.V[nl.nodeForPoint(310, 80)];   // 基极
  const iC = res.currents.get(5).i;
  const iB = res.currents.get(5).ib;
  check('Vbe ≈ 0.7V', vB, 0.7, 3e-2, ' V');
  check('Ic ≈ 4.3mA', iC * 1000, 4.3, 0.3, ' mA');
  check('Ib ≈ 43µA', iB * 1e6, 43, 5, ' µA');
  check('Vce = 10 - Ic·Rc ≈ 5.7V', vC, 5.7, 0.3, ' V');
}

// ---------- 6. 电流源 ----------
console.log('\n[6] 电流源 (2mA 注入 1kΩ → 2V)');
{
  const circuit = {
    components: [
      { type: 'isource', x: 80, y: 120, rotation: 0, params: { current: 0.002 } },
      { type: 'resistor', x: 260, y: 120, rotation: 0, params: { resistance: 1000 } },
      { type: 'ground', x: 30, y: 200, rotation: 0, params: {} },
      { type: 'ground', x: 310, y: 200, rotation: 0, params: {} }
    ],
    wires: [
      [{ x: 130, y: 120 }, { x: 210, y: 120 }],
      [{ x: 30, y: 120 }, { x: 30, y: 200 }],
      [{ x: 310, y: 120 }, { x: 310, y: 200 }]
    ]
  };
  const res = solveCircuitJSON(circuit);
  const vR = res.V[res.netlist.nodeForPoint(210, 120)];   // 电阻左引脚节点
  check('电阻两端电压 = 2mA × 1k = 2V', vR, 2.0, 1e-3, ' V');
}

// ---------- 7. 导线电阻（铜线 100m → R=0.075Ω，1A → 压降 0.075V） ----------
console.log('\n[7] 导线电阻与温度模型 (100m 铜线, R=ρ·L=0.00075×100=0.075Ω)');
{
  const circuit = {
    components: [
      { type: 'isource', x: 0, y: 120, rotation: 0, params: { current: 1 } },
      { type: 'ground', x: -50, y: 200, rotation: 0, params: {} },
      { type: 'ground', x: 150, y: 200, rotation: 0, params: {} }
    ],
    wires: [
      { type: 'copper', points: [{ x: 50, y: 120 }, { x: 150, y: 120 }] },
      { points: [{ x: -50, y: 120 }, { x: -50, y: 200 }] },
      { points: [{ x: 150, y: 120 }, { x: 150, y: 200 }] }
    ]
  };
  const res = solveCircuitJSON(circuit);
  const w = res.wires.get(1);
  check('导线电阻 = 0.075Ω', w.r, 0.075, 1e-3, ' Ω');
  check('导线电流 = 1A', w.i, 1, 1e-3, ' A');
  // 100m 铜线两端压降 = 1A × 0.075Ω = 0.075V
  check('导线两端压降 = 0.075V',
    res.V[res.netlist.nodeForPoint(50, 120)] - res.V[res.netlist.nodeForPoint(150, 120)],
    0.075, 1e-3, ' V');
  check('导线温度 = 环境 20°C（小电流不发热）', w.t, 20, 0.5, ' °C');
}

// ---------- 8. 导线过载烧毁（70A 通过 100m 铜线 → 升至 200°C 烧毁） ----------
console.log('\n[8] 导线过载烧毁 (70A, P=I²R=367W, T_ss=203.75°C > 200°C → 烧毁)');
{
  const circuit = {
    components: [
      { type: 'isource', x: 0, y: 120, rotation: 0, params: { current: 70 } },
      { type: 'ground', x: -50, y: 200, rotation: 0, params: {} },
      { type: 'ground', x: 150, y: 200, rotation: 0, params: {} }
    ],
    wires: [
      { type: 'copper', points: [{ x: 50, y: 120 }, { x: 150, y: 120 }] },
      { points: [{ x: -50, y: 120 }, { x: -50, y: 200 }] },
      { points: [{ x: 150, y: 120 }, { x: 150, y: 200 }] }
    ]
  };
  const t = transientJSON(circuit, { duration: 150, step: 0.05 });
  const w = t.last.wires.get(1);
  check('导线已烧毁', w.burned ? 1 : 0, 1, 0);
  check('烧毁温度 ≥ 200°C', w.t, 200, 0.5, ' °C');
  check('烧毁后电流为 0', w.i, 0, 1e-6, ' A');
  check('累计损耗能量 > 0', w.energy > 0 ? 1 : 0, 1, 0);
}

console.log('\n===== 结果: ' + pass + ' 通过, ' + fail + ' 失败 =====');
process.exit(fail ? 1 : 0);
