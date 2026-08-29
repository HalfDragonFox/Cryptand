'use strict';
// components.js — 元件定义、参数解析与绘制
// 每个元件：terminals(引脚相对坐标)、params(默认参数)、paramDefs(属性面板)、draw(绘制)

const GRID = 20;                       // 网格间距(px)
const Vt = 0.02585;                    // 热电压 (V)
const PROBE_COLORS = ['#ff5252', '#ffab40', '#ffee58', '#69f0ae', '#40c4ff', '#b388ff'];
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));

// ---------- 导线类型（数据取自 PowerGrid/Cryptand：Ω/米、额定电流、热模型） ----------
const WIRE_AMBIENT = 20;               // 环境温度 °C
// 额定电流 = 安全持续工作电流（超过只发热，不直接烧毁）；烧毁与否只看温升
// （温度超 maxTemp=200°C）。热容 5 → 热时间常数 τ=C/G=2.5s，瞬态几秒内可见温升。
// 电阻越低的导线同电流下温升越低（P=I²R），越难烧毁——符合现实。
const WIRE_TYPES = {
  copper: { name: '铜线', resistancePerMeter: 0.00075, ratedCurrent: 80,  thermalConductance: 2.0, heatCapacity: 5, maxTemp: 200 },
  iron:   { name: '铁线', resistancePerMeter: 0.0025,  ratedCurrent: 80,  thermalConductance: 2.0, heatCapacity: 5, maxTemp: 200 },
  golden: { name: '金线', resistancePerMeter: 0.0015,  ratedCurrent: 80,  thermalConductance: 2.0, heatCapacity: 5, maxTemp: 200 }
};

// 颜色混合（用于导线温度着色）
function blendHex(a, b, k) {
  const pa = parseInt(a.slice(1), 16), pb = parseInt(b.slice(1), 16);
  const r = Math.round(((pa >> 16) & 255) * (1 - k) + ((pb >> 16) & 255) * k);
  const g = Math.round(((pa >> 8) & 255) * (1 - k) + ((pb >> 8) & 255) * k);
  const bl = Math.round((pa & 255) * (1 - k) + (pb & 255) * k);
  return '#' + ((1 << 24) | (r << 16) | (g << 8) | bl).toString(16).slice(1);
}

// ---------- 数值解析与格式化 ----------
function formatSI(v, unit = '') {
  if (!isFinite(v)) return '∞';
  const a = Math.abs(v);
  const prefixes = [['p', 1e-12], ['n', 1e-9], ['µ', 1e-6], ['m', 1e-3], ['', 1], ['k', 1e3], ['M', 1e6], ['G', 1e9]];
  for (let i = prefixes.length - 1; i >= 0; i--) {
    if (a >= prefixes[i][1]) return trimNum(v / prefixes[i][1]) + prefixes[i][0] + unit;
  }
  return trimNum(v) + unit;
}
function trimNum(x) { return String(Math.round(x * 1e6) / 1e6); }

function parseSI(str) {
  if (typeof str === 'number') return str;
  let s = String(str).trim().toLowerCase();
  s = s.replace(/[Ωω]/g, '').replace(/ohm|ohms/g, '');
  s = s.replace(/[fhvawsz]$/, '');            // 去掉尾随单位字母
  const m = /^([+-]?[0-9]*\.?[0-9]+(?:e[+-]?[0-9]+)?)\s*(meg|[pnumµkg])?$/.exec(s);
  if (!m) return NaN;
  const val = parseFloat(m[1]);
  const suf = m[2] || '';
  const mult = { p: 1e-12, n: 1e-9, µ: 1e-6, u: 1e-6, m: 1e-3, k: 1e3, meg: 1e6, g: 1e9 }[suf] || 1;
  return val * mult;
}

// 元件第 i 个引脚的世界坐标（取整到网格，避免旋转时 cos/sin 浮点噪声导致连接判断失败）
function absTerminal(comp, i) {
  const def = COMPONENT_DEFS[comp.type];
  const t = def.terminals[i];
  const r = comp.rotation * Math.PI / 180;
  return {
    x: Math.round(comp.x + t.dx * Math.cos(r) - t.dy * Math.sin(r)),
    y: Math.round(comp.y + t.dx * Math.sin(r) + t.dy * Math.cos(r))
  };
}

// ---------- 元件定义 ----------
const COMPONENT_GROUPS = ['被动元件', '组装器设备', '电源与信号', '三相', '无线电', '半导体', '其他'];
const COMPONENT_DEFS = {};

function defType(type, o) { o.type = type; COMPONENT_DEFS[type] = o; }

function label(ctx, y, c, def) {
  ctx.save();
  ctx.font = '10px Consolas, monospace';
  ctx.fillStyle = '#93a0b3';
  ctx.textAlign = 'center';
  ctx.fillText(def.valueOf(c), 0, y);
  ctx.restore();
}

defType('resistor', {
  name: '电阻', group: '被动元件', short: 'R',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { resistance: 1000, tempCoef: 0, thermalConductance: 0, heatCapacity: 0 },
  paramDefs: [
    { key: 'resistance', label: '阻值', unit: 'Ω' },
    { key: 'tempCoef', label: '温度系数', unit: '/°C' },
    { key: 'thermalConductance', label: '散热', unit: 'W/K' },
    { key: 'heatCapacity', label: '热容', unit: 'J/K' }
  ],
  valueOf(c) { return formatSI(c.params.resistance, 'Ω'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0);
    ctx.lineTo(-32, 0);
    ctx.lineTo(-22, -12); ctx.lineTo(-7, 12); ctx.lineTo(7, -12); ctx.lineTo(22, 12); ctx.lineTo(32, 0);
    ctx.lineTo(50, 0);
    ctx.stroke();
    label(ctx, 26, c, def);
  }
});

defType('potentiometer', {
  name: '电位器', group: '被动元件', short: 'POT',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { resistance: 5000 },
  paramDefs: [{ key: 'resistance', label: '阻值', unit: 'Ω' }],
  valueOf(c) { return formatSI(c.params.resistance, 'Ω'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0);
    ctx.lineTo(-32, 0);
    ctx.lineTo(-22, -12); ctx.lineTo(-7, 12); ctx.lineTo(7, -12); ctx.lineTo(22, 12); ctx.lineTo(32, 0);
    ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(0, 22); ctx.lineTo(0, 10);
    ctx.lineTo(-5, 16); ctx.moveTo(0, 10); ctx.lineTo(5, 16);
    ctx.stroke();
    label(ctx, 32, c, def);
  }
});

defType('capacitor', {
  name: '电容', group: '被动元件', short: 'C',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { capacitance: 0.00001 },
  paramDefs: [{ key: 'capacitance', label: '容值', unit: 'F' }],
  valueOf(c) { return formatSI(c.params.capacitance, 'F'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-10, 0);
    ctx.moveTo(-10, -18); ctx.lineTo(-10, 18);
    ctx.moveTo(10, -18); ctx.lineTo(10, 18);
    ctx.moveTo(10, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    label(ctx, 26, c, def);
  }
});

defType('inductor', {
  name: '电感（绕组）', group: '被动元件', short: 'L',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { inductance: 0.01, resistance: 1, tempCoef: 0.0039, thermalConductance: 0.5, heatCapacity: 20 },
  paramDefs: [
    { key: 'inductance', label: '电感', unit: 'H' },
    { key: 'resistance', label: '线阻', unit: 'Ω' },
    { key: 'tempCoef', label: '温度系数', unit: '/°C' },
    { key: 'thermalConductance', label: '散热', unit: 'W/K' },
    { key: 'heatCapacity', label: '热容', unit: 'J/K' }
  ],
  valueOf(c) { return formatSI(c.params.inductance, 'H'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-30, 0);
    for (let i = 0; i < 4; i++) ctx.arc(-22.5 + i * 15, 0, 7.5, Math.PI, 0, false);
    ctx.lineTo(50, 0);
    ctx.stroke();
    label(ctx, 26, c, def);
  }
});

defType('lamp', {
  name: '灯泡', group: '被动元件', short: 'LAMP',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { resistance: 240, tempCoef: 0.0045, thermalConductance: 0.2, heatCapacity: 3 },
  paramDefs: [
    { key: 'resistance', label: '阻值', unit: 'Ω' },
    { key: 'tempCoef', label: '温度系数', unit: '/°C' },
    { key: 'thermalConductance', label: '散热', unit: 'W/K' },
    { key: 'heatCapacity', label: '热容', unit: 'J/K' }
  ],
  valueOf(c) { return formatSI(c.params.resistance, 'Ω'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-22, 0);
    ctx.arc(0, 0, 22, 0, Math.PI * 2);
    ctx.moveTo(22, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(-11, -11); ctx.lineTo(11, 11);
    ctx.moveTo(-11, 11); ctx.lineTo(11, -11);
    ctx.stroke();
  }
});

defType('vsource', {
  name: '直流电压源', group: '电源与信号', short: 'V',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { voltage: 5 },
  paramDefs: [{ key: 'voltage', label: '电压', unit: 'V' }],
  valueOf(c) { return formatSI(c.params.voltage, 'V'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-25, 0);
    ctx.arc(0, 0, 25, 0, Math.PI * 2);
    ctx.moveTo(25, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(-8, -14); ctx.lineTo(-8, 14);
    ctx.moveTo(-13, 0); ctx.lineTo(-3, 0);
    ctx.moveTo(8, -14); ctx.lineTo(8, 14);
    ctx.stroke();
    label(ctx, 36, c, def);
  }
});

defType('acsource', {
  name: '交流电压源', group: '电源与信号', short: 'AC',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { amplitude: 5, frequency: 50 },
  paramDefs: [{ key: 'amplitude', label: '幅值', unit: 'V' }, { key: 'frequency', label: '频率', unit: 'Hz' }],
  valueOf(c) { return formatSI(c.params.amplitude, 'V') + ' ' + formatSI(c.params.frequency, 'Hz'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-25, 0);
    ctx.arc(0, 0, 25, 0, Math.PI * 2);
    ctx.moveTo(25, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(-12, 0);
    ctx.quadraticCurveTo(-6, -14, 0, 0);
    ctx.quadraticCurveTo(6, 14, 12, 0);
    ctx.stroke();
    label(ctx, 36, c, def);
  }
});

defType('isource', {
  name: '电流源', group: '电源与信号', short: 'I',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { current: 0.01 },
  paramDefs: [{ key: 'current', label: '电流', unit: 'A' }],
  valueOf(c) { return formatSI(c.params.current, 'A'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-25, 0);
    ctx.arc(0, 0, 25, 0, Math.PI * 2);
    ctx.moveTo(25, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(0, 16); ctx.lineTo(0, -6);
    ctx.lineTo(-6, 0); ctx.moveTo(0, -6); ctx.lineTo(6, 0);
    ctx.stroke();
    label(ctx, 36, c, def);
  }
});

defType('ground', {
  name: '地线', group: '其他', short: 'GND',
  terminals: [{ dx: 0, dy: 0 }],
  params: {},
  paramDefs: [],
  valueOf() { return ''; },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(0, -24); ctx.lineTo(0, 0);
    ctx.moveTo(-18, 0); ctx.lineTo(18, 0);
    ctx.moveTo(-11, 9); ctx.lineTo(11, 9);
    ctx.moveTo(-4, 18); ctx.lineTo(4, 18);
    ctx.stroke();
  }
});

defType('switch', {
  name: '开关', group: '其他', short: 'SW',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { closed: false },
  paramDefs: [{ key: 'closed', label: '闭合', type: 'bool' }],
  valueOf(c) { return c.params.closed ? 'ON' : 'OFF'; },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-14, 0);
    ctx.moveTo(14, 0); ctx.lineTo(50, 0);
    if (c.params.closed) {
      ctx.moveTo(-14, 0); ctx.lineTo(14, 0);
    } else {
      ctx.moveTo(-14, 0); ctx.lineTo(4, -24);
    }
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(-14, 0, 3, 0, Math.PI * 2);
    ctx.fill(); ctx.stroke();
  }
});

defType('diode', {
  name: '二极管', group: '半导体', short: 'D',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { Is: 1e-14, n: 1 },
  paramDefs: [],
  valueOf() { return 'D'; },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-15, 0);
    ctx.moveTo(15, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(-15, -18); ctx.lineTo(15, 0); ctx.lineTo(-15, 18);
    ctx.closePath(); ctx.fill(); ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(15, -18); ctx.lineTo(15, 18);
    ctx.stroke();
  }
});

defType('led', {
  name: '发光二极管', group: '半导体', short: 'LED',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { Is: 1e-12, n: 2 },
  paramDefs: [],
  valueOf() { return 'LED'; },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-15, 0);
    ctx.moveTo(15, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(-15, -18); ctx.lineTo(15, 0); ctx.lineTo(-15, 18);
    ctx.closePath(); ctx.fill(); ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(15, -18); ctx.lineTo(15, 18);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(20, -22); ctx.lineTo(30, -32);
    ctx.moveTo(28, -22); ctx.lineTo(38, -32);
    ctx.stroke();
  }
});

defType('npn', {
  name: 'NPN 三极管', group: '半导体', short: 'Q',
  terminals: [{ dx: -40, dy: 0 }, { dx: 0, dy: -40 }, { dx: 0, dy: 40 }], // B, C, E
  params: { beta: 100, Is: 1e-14 },
  paramDefs: [{ key: 'beta', label: 'β' }],
  valueOf(c) { return 'β=' + c.params.beta; },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-40, 0); ctx.lineTo(-22, 0);
    ctx.moveTo(-22, -22); ctx.lineTo(-22, 22);
    ctx.moveTo(0, -40); ctx.lineTo(0, -22); ctx.lineTo(-22, -22);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(-22, 22); ctx.lineTo(0, 22); ctx.lineTo(0, 40);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(-22, 14); ctx.lineTo(-8, 22); ctx.lineTo(-22, 30);
    ctx.closePath(); ctx.fill(); ctx.stroke();
  }
});

// ============================================================
// 组装器设备（与引擎 composite model 对应；复合元件在求解时拆分为基础元件）
//   风扇/电机/电磁铁 = MotorModel（R-L 绕组 + 热模型）
//   加热器/灯泡     = 纯阻 + 热模型
//   发电机         = GeneratorModel（电枢 R_a 串联 EMF）
// ============================================================

defType('fan', {
  name: '风扇', group: '组装器设备', short: 'FAN',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { resistance: 8, inductance: 0.02, tempCoef: 0.0039, thermalConductance: 0.8, heatCapacity: 30 },
  paramDefs: [
    { key: 'resistance', label: '绕组电阻', unit: 'Ω' },
    { key: 'inductance', label: '绕组电感', unit: 'H' },
    { key: 'tempCoef', label: '温度系数', unit: '/°C' },
    { key: 'thermalConductance', label: '散热', unit: 'W/K' },
    { key: 'heatCapacity', label: '热容', unit: 'J/K' }
  ],
  valueOf(c) { return formatSI(c.params.resistance, 'Ω') + ' + ' + formatSI(c.params.inductance, 'H'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-24, 0);
    ctx.moveTo(24, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(0, 0, 24, 0, Math.PI * 2);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(0, 0); ctx.lineTo(0, -17);
    ctx.moveTo(0, 0); ctx.lineTo(14.7, 8.5);
    ctx.moveTo(0, 0); ctx.lineTo(-14.7, 8.5);
    ctx.stroke();
    label(ctx, 34, c, def);
  }
});

defType('motor', {
  name: '电机', group: '组装器设备', short: 'M',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { resistance: 5, inductance: 0.05, tempCoef: 0.0039, thermalConductance: 1.0, heatCapacity: 50 },
  paramDefs: [
    { key: 'resistance', label: '绕组电阻', unit: 'Ω' },
    { key: 'inductance', label: '绕组电感', unit: 'H' },
    { key: 'tempCoef', label: '温度系数', unit: '/°C' },
    { key: 'thermalConductance', label: '散热', unit: 'W/K' },
    { key: 'heatCapacity', label: '热容', unit: 'J/K' }
  ],
  valueOf(c) { return formatSI(c.params.resistance, 'Ω') + ' + ' + formatSI(c.params.inductance, 'H'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-24, 0);
    ctx.moveTo(24, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(0, 0, 24, 0, Math.PI * 2);
    ctx.stroke();
    ctx.font = 'bold 16px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('M', 0, 1);
    label(ctx, 34, c, def);
  }
});

defType('electromagnet', {
  name: '电磁铁', group: '组装器设备', short: 'EM',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { resistance: 20, inductance: 0.5, tempCoef: 0.0039, thermalConductance: 0.5, heatCapacity: 100 },
  paramDefs: [
    { key: 'resistance', label: '绕组电阻', unit: 'Ω' },
    { key: 'inductance', label: '绕组电感', unit: 'H' },
    { key: 'tempCoef', label: '温度系数', unit: '/°C' },
    { key: 'thermalConductance', label: '散热', unit: 'W/K' },
    { key: 'heatCapacity', label: '热容', unit: 'J/K' }
  ],
  valueOf(c) { return formatSI(c.params.resistance, 'Ω') + ' + ' + formatSI(c.params.inductance, 'H'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-30, 0);
    for (let i = 0; i < 4; i++) ctx.arc(-22.5 + i * 15, 0, 7.5, Math.PI, 0, false);
    ctx.lineTo(50, 0);
    ctx.stroke();
    // 铁芯
    ctx.beginPath();
    ctx.moveTo(-13, -18); ctx.lineTo(-13, 18);
    ctx.moveTo(-5, -20); ctx.lineTo(-5, 20);
    ctx.stroke();
    label(ctx, 30, c, def);
  }
});

defType('heater', {
  name: '加热器', group: '组装器设备', short: 'HTR',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { resistance: 50, tempCoef: 0.0004, thermalConductance: 0.3, heatCapacity: 10 },
  paramDefs: [
    { key: 'resistance', label: '阻值', unit: 'Ω' },
    { key: 'tempCoef', label: '温度系数', unit: '/°C' },
    { key: 'thermalConductance', label: '散热', unit: 'W/K' },
    { key: 'heatCapacity', label: '热容', unit: 'J/K' }
  ],
  valueOf(c) { return formatSI(c.params.resistance, 'Ω'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0);
    ctx.lineTo(-32, 0);
    ctx.lineTo(-22, -12); ctx.lineTo(-7, 12); ctx.lineTo(7, -12); ctx.lineTo(22, 12); ctx.lineTo(32, 0);
    ctx.lineTo(50, 0);
    ctx.stroke();
    // 发热辐射线
    ctx.beginPath();
    ctx.moveTo(-26, -22); ctx.quadraticCurveTo(-16, -30, -6, -22);
    ctx.moveTo(-6, -24); ctx.quadraticCurveTo(4, -32, 14, -24);
    ctx.stroke();
    label(ctx, 34, c, def);
  }
});

defType('generator', {
  name: '发电机', group: '组装器设备', short: 'GEN',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { voltage: 10, resistance: 0.5, tempCoef: 0, thermalConductance: 0, heatCapacity: 0 },
  paramDefs: [
    { key: 'voltage', label: '电动势', unit: 'V' },
    { key: 'resistance', label: '电枢电阻', unit: 'Ω' },
    { key: 'tempCoef', label: '温度系数', unit: '/°C' },
    { key: 'thermalConductance', label: '散热', unit: 'W/K' },
    { key: 'heatCapacity', label: '热容', unit: 'J/K' }
  ],
  valueOf(c) { return formatSI(c.params.voltage, 'V'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-24, 0);
    ctx.moveTo(24, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(0, 0, 24, 0, Math.PI * 2);
    ctx.stroke();
    ctx.font = 'bold 15px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('G', 0, 1);
    label(ctx, 34, c, def);
  }
});

defType('battery', {
  name: '电池', group: '组装器设备', short: 'BT',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { voltage: 6, resistance: 0.5 },
  paramDefs: [
    { key: 'voltage', label: '电压', unit: 'V' },
    { key: 'resistance', label: '内阻', unit: 'Ω' }
  ],
  valueOf(c) { return formatSI(c.params.voltage, 'V'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-14, 0);
    ctx.moveTo(14, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(-14, -20); ctx.lineTo(-14, 20);
    ctx.moveTo(-4, -12); ctx.lineTo(-4, 12);
    ctx.moveTo(4, -12); ctx.lineTo(4, 12);
    ctx.moveTo(14, -20); ctx.lineTo(14, 20);
    ctx.stroke();
    label(ctx, 30, c, def);
  }
});

defType('bell', {
  name: '警铃', group: '组装器设备', short: 'BEL',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { resistance: 30, tempCoef: 0.001, thermalConductance: 0.5, heatCapacity: 20 },
  paramDefs: [
    { key: 'resistance', label: '阻值', unit: 'Ω' },
    { key: 'tempCoef', label: '温度系数', unit: '/°C' },
    { key: 'thermalConductance', label: '散热', unit: 'W/K' },
    { key: 'heatCapacity', label: '热容', unit: 'J/K' }
  ],
  valueOf(c) { return formatSI(c.params.resistance, 'Ω'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-18, 0);
    ctx.moveTo(18, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(0, -4, 16, Math.PI, 0);       // 铃碗
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(-16, -4); ctx.lineTo(16, -4);
    ctx.moveTo(0, -4); ctx.lineTo(0, 14);  // 敲锤
    ctx.arc(0, 16, 3, 0, Math.PI * 2);
    ctx.fill(); ctx.stroke();
    label(ctx, 30, c, def);
  }
});

defType('basin_heater', {
  name: '盆加热器', group: '组装器设备', short: 'BH',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { resistance: 40, inductance: 0.01, tempCoef: 0.0004, thermalConductance: 0.4, heatCapacity: 50 },
  paramDefs: [
    { key: 'resistance', label: '绕组电阻', unit: 'Ω' },
    { key: 'inductance', label: '绕组电感', unit: 'H' },
    { key: 'tempCoef', label: '温度系数', unit: '/°C' },
    { key: 'thermalConductance', label: '散热', unit: 'W/K' },
    { key: 'heatCapacity', label: '热容', unit: 'J/K' }
  ],
  valueOf(c) { return formatSI(c.params.resistance, 'Ω') + ' + ' + formatSI(c.params.inductance, 'H'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-30, 0);
    for (let i = 0; i < 4; i++) ctx.arc(-22.5 + i * 15, 0, 7.5, Math.PI, 0, false);
    ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();                      // 盆
    ctx.moveTo(-22, 6); ctx.lineTo(22, 6);
    ctx.lineTo(16, 24); ctx.lineTo(-16, 24);
    ctx.closePath(); ctx.stroke();
    label(ctx, 34, c, def);
  }
});

defType('carbon_pile', {
  name: '碳堆', group: '组装器设备', short: 'CP',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { resistance: 5, tempCoef: -0.0002, thermalConductance: 0.6, heatCapacity: 40 },
  paramDefs: [
    { key: 'resistance', label: '阻值', unit: 'Ω' },
    { key: 'tempCoef', label: '温度系数', unit: '/°C' },
    { key: 'thermalConductance', label: '散热', unit: 'W/K' },
    { key: 'heatCapacity', label: '热容', unit: 'J/K' }
  ],
  valueOf(c) { return formatSI(c.params.resistance, 'Ω'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-24, 0);
    ctx.moveTo(24, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    for (let i = 0; i < 3; i++) {
      ctx.beginPath();
      const y = -14 + i * 14;
      ctx.moveTo(-20, y); ctx.lineTo(20, y);
      ctx.stroke();
    }
    ctx.beginPath();
    ctx.moveTo(-20, -14); ctx.lineTo(-20, 14);
    ctx.moveTo(20, -14); ctx.lineTo(20, 14);
    ctx.stroke();
    label(ctx, 30, c, def);
  }
});

defType('rheostat', {
  name: '变阻器', group: '组装器设备', short: 'RH',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { resistance: 200 },
  paramDefs: [{ key: 'resistance', label: '阻值', unit: 'Ω' }],
  valueOf(c) { return formatSI(c.params.resistance, 'Ω'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-32, 0);
    ctx.lineTo(-22, -12); ctx.lineTo(-7, 12); ctx.lineTo(7, -12); ctx.lineTo(22, 12); ctx.lineTo(32, 0);
    ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();                      // 滑片
    ctx.moveTo(-8, -24); ctx.lineTo(8, -12); ctx.lineTo(-8, 4);
    ctx.stroke();
    label(ctx, 32, c, def);
  }
});

defType('transformer', {
  name: '变压器', group: '组装器设备', short: 'T',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }, { dx: -50, dy: 60 }, { dx: 50, dy: 60 }], // 原边(a1,a2) 副边(b1,b2)
  params: { ratio: 2 },
  paramDefs: [{ key: 'ratio', label: '变比(副/原)' }],
  valueOf(c) { return '1:' + c.params.ratio; },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-30, 0);
    for (let i = 0; i < 4; i++) ctx.arc(-22.5 + i * 15, 0, 7.5, Math.PI, 0, false);
    ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(-50, 60); ctx.lineTo(-30, 60);
    for (let i = 0; i < 4; i++) ctx.arc(-22.5 + i * 15, 60, 7.5, Math.PI, 0, false);
    ctx.lineTo(50, 60);
    ctx.stroke();
    ctx.beginPath();                      // 铁芯
    ctx.moveTo(-8, 12); ctx.lineTo(-8, 48);
    ctx.moveTo(8, 12); ctx.lineTo(8, 48);
    ctx.stroke();
    label(ctx, 72, c, def);
  }
});

defType('variac', {
  name: '自耦调压器', group: '组装器设备', short: 'VR',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }, { dx: -50, dy: 60 }, { dx: 50, dy: 60 }],
  params: { ratio: 1 },
  paramDefs: [{ key: 'ratio', label: '变比' }],
  valueOf(c) { return '1:' + c.params.ratio; },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-30, 0);
    for (let i = 0; i < 4; i++) ctx.arc(-22.5 + i * 15, 0, 7.5, Math.PI, 0, false);
    ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(-50, 60); ctx.lineTo(-30, 60);
    for (let i = 0; i < 4; i++) ctx.arc(-22.5 + i * 15, 60, 7.5, Math.PI, 0, false);
    ctx.lineTo(50, 60);
    ctx.stroke();
    ctx.beginPath();                      // 调压箭头
    ctx.moveTo(0, 10); ctx.lineTo(0, 50);
    ctx.moveTo(-5, 20); ctx.lineTo(0, 10); ctx.lineTo(5, 20);
    ctx.stroke();
    label(ctx, 72, c, def);
  }
});

defType('gauge', {
  name: '仪表', group: '组装器设备', short: 'GAU',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { resistance: 0.05, mode: '电流表' },
  paramDefs: [
    { key: 'resistance', label: '内阻', unit: 'Ω' },
    { key: 'mode', label: '类型', type: 'select', options: ['电流表', '电压表', '功率表'] }
  ],
  valueOf(c) { return c.params.mode || '仪表'; },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-24, 0);
    ctx.moveTo(24, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(0, 0, 24, 0, Math.PI * 2);
    ctx.stroke();
    ctx.beginPath();                      // 指针
    ctx.moveTo(0, 0); ctx.lineTo(10, -10);
    ctx.moveTo(-6, 6); ctx.lineTo(6, -6);
    ctx.stroke();
    label(ctx, 34, c, def);
  }
});

defType('grounding_rod', {
  name: '接地棒', group: '组装器设备', short: 'GRD',
  terminals: [{ dx: 0, dy: 0 }],
  params: {},
  paramDefs: [],
  valueOf() { return ''; },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(0, -24); ctx.lineTo(0, 0);
    ctx.moveTo(-18, 0); ctx.lineTo(18, 0);
    ctx.moveTo(-11, 9); ctx.lineTo(11, 9);
    ctx.moveTo(-4, 18); ctx.lineTo(4, 18);
    ctx.stroke();
  }
});

defType('connector', {
  name: '端子', group: '组装器设备', short: 'T',
  terminals: [{ dx: 0, dy: 0 }],
  params: {},
  paramDefs: [],
  valueOf() { return ''; },
  draw(ctx, c, def) {
    // 单点大圆点端子（接线汇流点）
    ctx.beginPath();
    ctx.arc(0, 0, 12, 0, Math.PI * 2);
    ctx.fillStyle = '#4da6ff';
    ctx.fill();
    ctx.strokeStyle = '#4fc3f7';
    ctx.lineWidth = 2;
    ctx.stroke();
    // 中心亮点
    ctx.beginPath();
    ctx.arc(0, 0, 4, 0, Math.PI * 2);
    ctx.fillStyle = '#0d0f15';
    ctx.fill();
  }
});

defType('fuse', {
  name: '保险丝', group: '组装器设备', short: 'F',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { closed: true, rating: 10 },
  paramDefs: [
    { key: 'closed', label: '导通', type: 'bool' },
    { key: 'rating', label: '额定电流', unit: 'A' }
  ],
  valueOf(c) { return c.params.closed ? 'ON ' + (c.params.rating || 0) + 'A' : 'OFF'; },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-20, 0);
    ctx.moveTo(20, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.strokeRect(-20, -12, 40, 24);
    if (c.params.closed) {
      ctx.beginPath(); ctx.moveTo(-14, 0); ctx.lineTo(14, 0); ctx.stroke();
    }
    label(ctx, 22, c, def);
  }
});

defType('hvswitch', {
  name: '高压开关', group: '组装器设备', short: 'HSW',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { closed: false },
  paramDefs: [{ key: 'closed', label: '闭合', type: 'bool' }],
  valueOf(c) { return c.params.closed ? 'ON' : 'OFF'; },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-14, 0);
    ctx.moveTo(14, 0); ctx.lineTo(50, 0);
    if (c.params.closed) { ctx.moveTo(-14, 0); ctx.lineTo(14, 0); }
    else { ctx.moveTo(-14, 0); ctx.lineTo(4, -24); }
    ctx.stroke();
    ctx.beginPath(); ctx.arc(-14, 0, 3, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
    ctx.beginPath(); ctx.arc(14, 0, 3, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
  }
});

defType('breaker', {
  name: '断路器', group: '组装器设备', short: 'BRK',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { closed: true },
  paramDefs: [{ key: 'closed', label: '闭合', type: 'bool' }],
  valueOf(c) { return c.params.closed ? 'ON' : 'OFF'; },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-14, 0);
    ctx.moveTo(14, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();                      // 断开标志 X
    ctx.moveTo(-4, -14); ctx.lineTo(10, 14);
    ctx.moveTo(-4, 14); ctx.lineTo(10, -14);
    ctx.stroke();
    ctx.beginPath(); ctx.arc(-14, 0, 3, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
  }
});

defType('contactor', {
  name: '接触器', group: '组装器设备', short: 'CTR',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { closed: false },
  paramDefs: [{ key: 'closed', label: '闭合', type: 'bool' }],
  valueOf(c) { return c.params.closed ? 'ON' : 'OFF'; },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-14, 0);
    ctx.moveTo(14, 0); ctx.lineTo(50, 0);
    if (c.params.closed) { ctx.moveTo(-14, 0); ctx.lineTo(14, 0); }
    else { ctx.moveTo(-14, 0); ctx.lineTo(4, -24); }
    ctx.stroke();
    ctx.beginPath();                      // 控制线圈
    ctx.moveTo(24, -16); ctx.lineTo(38, -16);
    ctx.moveTo(24, 16); ctx.lineTo(38, 16);
    ctx.stroke();
  }
});

defType('plotter', {
  name: '绘图仪', group: '组装器设备', short: 'PLT',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { resistance: 20000 },
  paramDefs: [{ key: 'resistance', label: '采样阻抗', unit: 'Ω' }],
  valueOf(c) { return formatSI(c.params.resistance, 'Ω'); },
  draw(ctx, c, def) {
    ctx.strokeRect(-24, -16, 48, 32);
    ctx.beginPath();                      // 笔
    ctx.moveTo(-16, 8); ctx.lineTo(12, -10);
    ctx.stroke();
    ctx.beginPath(); ctx.arc(14, -12, 3, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-24, 0);
    ctx.moveTo(24, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    label(ctx, 26, c, def);
  }
});

// ============================================================
// 基础元件（引擎 model/elements）+ 其余复合元件（无 BE 绑定也算）
// ============================================================

// 通用画线圈 + 文字 的辅助（电机/源类符号）
function circleLabel(ctx, txt, r = 24) {
  ctx.beginPath();
  ctx.arc(0, 0, r, 0, Math.PI * 2);
  ctx.stroke();
  ctx.font = 'bold 14px sans-serif';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(txt, 0, 1);
}

// ---- 波形源（DC/SINE/SQUARE/TRIANGLE/SAWTOOTH，可带相位/占空比/偏移） ----
defType('wavesource', {
  name: '波形源', group: '电源与信号', short: 'WAV',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { waveform: 'SINE', amplitude: 5, frequency: 50, phase: 0, duty: 0.5, offset: 0, series: 0.01 },
  paramDefs: [
    { key: 'waveform', label: '波形', type: 'select', options: ['DC', 'SINE', 'SQUARE', 'TRIANGLE', 'SAWTOOTH'] },
    { key: 'amplitude', label: '幅值', unit: 'V' },
    { key: 'frequency', label: '频率', unit: 'Hz' },
    { key: 'phase', label: '相位', unit: '°' },
    { key: 'duty', label: '占空比' },
    { key: 'offset', label: '偏移', unit: 'V' }
  ],
  valueOf(c) { return c.params.waveform + ' ' + formatSI(c.params.amplitude, 'V'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-25, 0);
    ctx.arc(0, 0, 25, 0, Math.PI * 2);
    ctx.moveTo(25, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();                      // 波形示意
    ctx.moveTo(-12, 8); ctx.lineTo(-6, 8); ctx.lineTo(-6, -8); ctx.lineTo(6, -8); ctx.lineTo(6, 8); ctx.lineTo(12, 8);
    ctx.stroke();
    label(ctx, 36, c, def);
  }
});

// ---- 压控电流源 / 压控电压源（4 端子：控制 2 + 输出 2） ----
defType('vccs', {
  name: '压控电流源', group: '电源与信号', short: 'VCCS',
  terminals: [{ dx: -50, dy: -30 }, { dx: -50, dy: 30 }, { dx: 50, dy: -30 }, { dx: 50, dy: 30 }],
  params: { transconductance: 0.01 },
  paramDefs: [{ key: 'transconductance', label: '跨导 gm', unit: 'S' }],
  valueOf(c) { return 'gm=' + formatSI(c.params.transconductance, 'S'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, -30); ctx.lineTo(-24, -30);
    ctx.moveTo(-50, 30); ctx.lineTo(-24, 30);
    ctx.moveTo(24, -30); ctx.lineTo(50, -30);
    ctx.moveTo(24, 30); ctx.lineTo(50, 30);
    ctx.stroke();
    ctx.beginPath();                      // 菱形受控源
    ctx.moveTo(0, -24); ctx.lineTo(24, 0); ctx.lineTo(0, 24); ctx.lineTo(-24, 0);
    ctx.closePath(); ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(0, 10); ctx.lineTo(0, -10); ctx.lineTo(6, -4); ctx.moveTo(0, -10); ctx.lineTo(-6, -4);
    ctx.stroke();
    label(ctx, 40, c, def);
  }
});

defType('vcvs', {
  name: '压控电压源', group: '电源与信号', short: 'VCVS',
  terminals: [{ dx: -50, dy: -30 }, { dx: -50, dy: 30 }, { dx: 50, dy: -30 }, { dx: 50, dy: 30 }],
  params: { gain: 2 },
  paramDefs: [{ key: 'gain', label: '增益' }],
  valueOf(c) { return 'A=' + c.params.gain; },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, -30); ctx.lineTo(-24, -30);
    ctx.moveTo(-50, 30); ctx.lineTo(-24, 30);
    ctx.moveTo(24, -30); ctx.lineTo(50, -30);
    ctx.moveTo(24, 30); ctx.lineTo(50, 30);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(0, -24); ctx.lineTo(24, 0); ctx.lineTo(0, 24); ctx.lineTo(-24, 0);
    ctx.closePath(); ctx.stroke();
    ctx.beginPath();                      // +/- 号
    ctx.moveTo(-6, -8); ctx.lineTo(6, -8); ctx.moveTo(0, -14); ctx.lineTo(0, -2);
    ctx.moveTo(-6, 8); ctx.lineTo(6, 8);
    ctx.stroke();
    label(ctx, 40, c, def);
  }
});

// ---- 互感（4 端子：原边 a1-a2 / 副边 b1-b2，互感 M） ----
defType('mutual_inductor', {
  name: '互感', group: '被动元件', short: 'M',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }, { dx: -50, dy: 60 }, { dx: 50, dy: 60 }],
  params: { l1: 0.01, l2: 0.01, m: 0.005 },
  paramDefs: [
    { key: 'l1', label: '原边电感', unit: 'H' },
    { key: 'l2', label: '副边电感', unit: 'H' },
    { key: 'm', label: '互感', unit: 'H' }
  ],
  valueOf(c) { return 'L1=' + formatSI(c.params.l1, 'H') + ' L2=' + formatSI(c.params.l2, 'H'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-30, 0);
    for (let i = 0; i < 4; i++) ctx.arc(-22.5 + i * 15, 0, 7.5, Math.PI, 0, false);
    ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(-50, 60); ctx.lineTo(-30, 60);
    for (let i = 0; i < 4; i++) ctx.arc(-22.5 + i * 15, 60, 7.5, Math.PI, 0, false);
    ctx.lineTo(50, 60);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(-6, 12); ctx.lineTo(-6, 48);
    ctx.moveTo(6, 12); ctx.lineTo(6, 48);
    ctx.stroke();
    label(ctx, 72, c, def);
  }
});

// ============================================================
// 三相设备
// ============================================================

defType('three_phase_source', {
  name: '三相电源', group: '三相', short: '3P-V',
  terminals: [{ dx: -60, dy: -40 }, { dx: -60, dy: 0 }, { dx: -60, dy: 40 }, { dx: 60, dy: 0 }], // L1,L2,L3,N
  params: { amplitude: 220, frequency: 50 },
  paramDefs: [
    { key: 'amplitude', label: '相幅值', unit: 'V' },
    { key: 'frequency', label: '频率', unit: 'Hz' }
  ],
  valueOf(c) { return formatSI(c.params.amplitude, 'V') + ' ' + formatSI(c.params.frequency, 'Hz'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-60, -40); ctx.lineTo(-26, -40);
    ctx.moveTo(-60, 0); ctx.lineTo(-26, 0);
    ctx.moveTo(-60, 40); ctx.lineTo(-26, 40);
    ctx.moveTo(26, 0); ctx.lineTo(60, 0);
    ctx.stroke();
    circleLabel(ctx, '3~', 26);
    ctx.font = '10px Consolas, monospace';
    ctx.textAlign = 'center';
    ctx.fillText('N', 60, 12);
    label(ctx, 50, c, def);
  }
});

defType('three_phase_motor', {
  name: '三相电机', group: '三相', short: '3P-M',
  terminals: [{ dx: -60, dy: -40 }, { dx: -60, dy: 0 }, { dx: -60, dy: 40 }, { dx: 60, dy: 0 }], // L1,L2,L3,N
  params: { resistance: 5, inductance: 0.05, tempCoef: 0.0039, thermalConductance: 1, heatCapacity: 60 },
  paramDefs: [
    { key: 'resistance', label: '相绕组电阻', unit: 'Ω' },
    { key: 'inductance', label: '相绕组电感', unit: 'H' },
    { key: 'tempCoef', label: '温度系数', unit: '/°C' },
    { key: 'thermalConductance', label: '散热', unit: 'W/K' },
    { key: 'heatCapacity', label: '热容', unit: 'J/K' }
  ],
  valueOf(c) { return formatSI(c.params.resistance, 'Ω') + ' + ' + formatSI(c.params.inductance, 'H'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-60, -40); ctx.lineTo(-26, -40);
    ctx.moveTo(-60, 0); ctx.lineTo(-26, 0);
    ctx.moveTo(-60, 40); ctx.lineTo(-26, 40);
    ctx.moveTo(26, 0); ctx.lineTo(60, 0);
    ctx.stroke();
    circleLabel(ctx, 'M3', 26);
    label(ctx, 50, c, def);
  }
});

defType('three_phase_transformer', {
  name: '三相变压器', group: '三相', short: '3P-T',
  terminals: [{ dx: -60, dy: -45 }, { dx: -60, dy: 0 }, { dx: -60, dy: 45 }, { dx: 60, dy: -45 }, { dx: -60, dy: 90 }, { dx: -60, dy: 135 }, { dx: -60, dy: 180 }, { dx: 60, dy: 135 }], // pa1..3,n1,pb1..3,n2
  params: { ratio: 2 },
  paramDefs: [{ key: 'ratio', label: '变比(副/原)' }],
  valueOf(c) { return '1:' + c.params.ratio; },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-60, -45); ctx.lineTo(-34, -45);
    ctx.moveTo(-60, 0); ctx.lineTo(-34, 0);
    ctx.moveTo(-60, 45); ctx.lineTo(-34, 45);
    ctx.moveTo(34, -45); ctx.lineTo(60, -45);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(-60, 90); ctx.lineTo(-34, 90);
    ctx.moveTo(-60, 135); ctx.lineTo(-34, 135);
    ctx.moveTo(-60, 180); ctx.lineTo(-34, 180);
    ctx.moveTo(34, 135); ctx.lineTo(60, 135);
    ctx.stroke();
    ctx.beginPath();
    for (let i = 0; i < 3; i++) {
      for (let j = 0; j < 4; j++) ctx.arc(-27 + j * 15, i * 45, 7.5, Math.PI, 0, false);
    }
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(-6, 50); ctx.lineTo(-6, 85);
    ctx.moveTo(6, 50); ctx.lineTo(6, 85);
    ctx.stroke();
    label(ctx, 192, c, def);
  }
});

defType('three_phase_rectifier', {
  name: '三相整流器', group: '三相', short: '3P-R',
  terminals: [{ dx: -60, dy: -45 }, { dx: -60, dy: 0 }, { dx: -60, dy: 45 }, { dx: -60, dy: 90 }, { dx: 60, dy: -45 }, { dx: 60, dy: 45 }], // ia,ib,ic,nin,dout,ngnd
  params: { loadR: 1000, filterCap: 0.001 },
  paramDefs: [
    { key: 'loadR', label: '负载', unit: 'Ω' },
    { key: 'filterCap', label: '滤波电容', unit: 'F' }
  ],
  valueOf(c) { return '负载 ' + formatSI(c.params.loadR, 'Ω'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-60, -45); ctx.lineTo(-28, -45);
    ctx.moveTo(-60, 0); ctx.lineTo(-28, 0);
    ctx.moveTo(-60, 45); ctx.lineTo(-28, 45);
    ctx.moveTo(-60, 90); ctx.lineTo(-28, 90);
    ctx.moveTo(28, -45); ctx.lineTo(60, -45);
    ctx.moveTo(28, 45); ctx.lineTo(60, 45);
    ctx.stroke();
    ctx.beginPath();                      // 整流桥示意
    ctx.moveTo(-28, -45); ctx.lineTo(0, -18);
    ctx.moveTo(-28, 45); ctx.lineTo(0, 18);
    ctx.moveTo(-28, 0); ctx.lineTo(0, -18);
    ctx.moveTo(-28, 90); ctx.lineTo(0, 18);
    ctx.moveTo(0, -18); ctx.lineTo(28, -45);
    ctx.moveTo(0, 18); ctx.lineTo(28, 45);
    ctx.stroke();
    label(ctx, 100, c, def);
  }
});

// ============================================================
// 无线电 / 其他复合
// ============================================================

defType('antenna', {
  name: '天线', group: '无线电', short: 'ANT',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { radiationR: 73, lossR: 5 },
  paramDefs: [
    { key: 'radiationR', label: '辐射电阻', unit: 'Ω' },
    { key: 'lossR', label: '损耗电阻', unit: 'Ω' }
  ],
  valueOf(c) { return formatSI((c.params.radiationR || 0) + (c.params.lossR || 0), 'Ω'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-20, 0);
    ctx.moveTo(20, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(-20, 0); ctx.lineTo(0, -28);
    ctx.moveTo(0, -28); ctx.lineTo(0, 28);
    ctx.moveTo(-8, 16); ctx.lineTo(0, 28); ctx.lineTo(8, 16);
    ctx.stroke();
    label(ctx, 36, c, def);
  }
});

defType('radio_transmitter', {
  name: '无线电发射机', group: '无线电', short: 'TX',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { amplitude: 5, frequency: 1e6, outputR: 50 },
  paramDefs: [
    { key: 'amplitude', label: '载波幅值', unit: 'V' },
    { key: 'frequency', label: '载波频率', unit: 'Hz' },
    { key: 'outputR', label: '输出阻抗', unit: 'Ω' }
  ],
  valueOf(c) { return formatSI(c.params.frequency, 'Hz'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-25, 0);
    ctx.arc(0, 0, 25, 0, Math.PI * 2);
    ctx.moveTo(25, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(-14, 10); ctx.lineTo(-4, -10); ctx.lineTo(6, 10); ctx.lineTo(16, -10);
    ctx.stroke();
    ctx.font = 'bold 11px sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.fillText('TX', 0, -16);
    label(ctx, 36, c, def);
  }
});

defType('radio_receiver', {
  name: '无线电接收机', group: '无线电', short: 'RX',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { resistance: 5, inductance: 0.001 },
  paramDefs: [
    { key: 'resistance', label: '输入电阻', unit: 'Ω' },
    { key: 'inductance', label: '调谐电感', unit: 'H' }
  ],
  valueOf(c) { return 'R-L'; },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-25, 0);
    ctx.arc(0, 0, 25, 0, Math.PI * 2);
    ctx.moveTo(25, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.font = 'bold 11px sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.fillText('RX', 0, 0);
    label(ctx, 36, c, def);
  }
});

defType('oscillator', {
  name: '振荡器', group: '无线电', short: 'OSC',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { amplitude: 3, frequency: 1000 },
  paramDefs: [
    { key: 'amplitude', label: '幅值', unit: 'V' },
    { key: 'frequency', label: '频率', unit: 'Hz' }
  ],
  valueOf(c) { return formatSI(c.params.frequency, 'Hz'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-25, 0);
    ctx.arc(0, 0, 25, 0, Math.PI * 2);
    ctx.moveTo(25, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(-10, 0); ctx.quadraticCurveTo(-5, -12, 0, 0); ctx.quadraticCurveTo(5, 12, 10, 0);
    ctx.stroke();
    label(ctx, 36, c, def);
  }
});

defType('speaker', {
  name: '扬声器', group: '无线电', short: 'SPK',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { resistance: 8, inductance: 0.0004, tempCoef: 0.0039, thermalConductance: 0.5, heatCapacity: 20 },
  paramDefs: [
    { key: 'resistance', label: '音圈电阻', unit: 'Ω' },
    { key: 'inductance', label: '音圈电感', unit: 'H' },
    { key: 'tempCoef', label: '温度系数', unit: '/°C' },
    { key: 'thermalConductance', label: '散热', unit: 'W/K' },
    { key: 'heatCapacity', label: '热容', unit: 'J/K' }
  ],
  valueOf(c) { return formatSI(c.params.resistance, 'Ω') + ' + ' + formatSI(c.params.inductance, 'H'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-25, 0);
    ctx.moveTo(25, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(-25, 16); ctx.lineTo(25, 16);
    ctx.moveTo(-25, 0); ctx.lineTo(25, 0);
    ctx.moveTo(-25, -16); ctx.lineTo(25, -16);
    ctx.moveTo(-25, 16); ctx.lineTo(-25, -16);
    ctx.moveTo(25, 16); ctx.lineTo(25, -16);
    ctx.stroke();
    label(ctx, 26, c, def);
  }
});

defType('solar_panel', {
  name: '太阳能板', group: '电源与信号', short: 'PV',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { photoCurrent: 0.5, seriesR: 0.5, shuntR: 1000 },
  paramDefs: [
    { key: 'photoCurrent', label: '光生电流', unit: 'A' },
    { key: 'seriesR', label: '串联电阻', unit: 'Ω' },
    { key: 'shuntR', label: '并联电阻', unit: 'Ω' }
  ],
  valueOf(c) { return formatSI(c.params.photoCurrent, 'A'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-25, 0);
    ctx.moveTo(25, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.strokeRect(-25, -18, 50, 36);
    ctx.beginPath();                      // 箭头
    ctx.moveTo(-8, 6); ctx.lineTo(8, -6);
    ctx.moveTo(2, -6); ctx.lineTo(8, -6); ctx.lineTo(8, 2);
    ctx.stroke();
    label(ctx, 26, c, def);
  }
});

defType('rectifier', {
  name: '整流器', group: '电源与信号', short: 'REC',
  terminals: [{ dx: -50, dy: -30 }, { dx: -50, dy: 30 }, { dx: 50, dy: -30 }, { dx: 50, dy: 30 }], // ain,nin,dout,ngnd
  params: { loadR: 1000, filterCap: 0.001 },
  paramDefs: [
    { key: 'loadR', label: '负载', unit: 'Ω' },
    { key: 'filterCap', label: '滤波电容', unit: 'F' }
  ],
  valueOf(c) { return '负载 ' + formatSI(c.params.loadR, 'Ω'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, -30); ctx.lineTo(-24, -30);
    ctx.moveTo(-50, 30); ctx.lineTo(-24, 30);
    ctx.moveTo(24, -30); ctx.lineTo(50, -30);
    ctx.moveTo(24, 30); ctx.lineTo(50, 30);
    ctx.stroke();
    ctx.beginPath();                      // 桥
    ctx.moveTo(-24, -30); ctx.lineTo(0, -10);
    ctx.moveTo(-24, 30); ctx.lineTo(0, 10);
    ctx.moveTo(0, -10); ctx.lineTo(24, -30);
    ctx.moveTo(0, 10); ctx.lineTo(24, 30);
    ctx.moveTo(-24, -30); ctx.lineTo(-8, -14);
    ctx.moveTo(-24, 30); ctx.lineTo(-8, 14);
    ctx.stroke();
    label(ctx, 40, c, def);
  }
});

defType('dc_dc', {
  name: 'DC-DC 变换器', group: '电源与信号', short: 'DC/DC',
  terminals: [{ dx: -50, dy: -30 }, { dx: -50, dy: 30 }, { dx: 50, dy: -30 }, { dx: 50, dy: 30 }], // vin,vgnd,vout,ognd
  params: { ratio: 2 },
  paramDefs: [{ key: 'ratio', label: '变比(Vout/Vin)' }],
  valueOf(c) { return '1:' + c.params.ratio; },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, -30); ctx.lineTo(-22, -30);
    ctx.moveTo(-50, 30); ctx.lineTo(-22, 30);
    ctx.moveTo(22, -30); ctx.lineTo(50, -30);
    ctx.moveTo(22, 30); ctx.lineTo(50, 30);
    ctx.stroke();
    ctx.strokeRect(-22, -22, 44, 44);
    ctx.font = 'bold 11px sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.fillText('DC', 0, -6); ctx.fillText('DC', 0, 6);
    label(ctx, 40, c, def);
  }
});

defType('inverter', {
  name: '逆变器', group: '电源与信号', short: 'INV',
  terminals: [{ dx: -50, dy: -30 }, { dx: -50, dy: 30 }, { dx: 50, dy: -30 }, { dx: 50, dy: 30 }], // din,gnd,oout,oret
  params: { gain: 1 },
  paramDefs: [{ key: 'gain', label: '增益' }],
  valueOf(c) { return 'A=' + c.params.gain; },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, -30); ctx.lineTo(-22, -30);
    ctx.moveTo(-50, 30); ctx.lineTo(-22, 30);
    ctx.moveTo(22, -30); ctx.lineTo(50, -30);
    ctx.moveTo(22, 30); ctx.lineTo(50, 30);
    ctx.stroke();
    ctx.strokeRect(-22, -22, 44, 44);
    ctx.beginPath();
    ctx.moveTo(-10, 4); ctx.lineTo(-10, -8); ctx.lineTo(2, -8);
    ctx.moveTo(-10, -8); ctx.lineTo(-10, -4); ctx.moveTo(-10, -2); ctx.lineTo(-10, 2);
    ctx.stroke();
    label(ctx, 40, c, def);
  }
});

defType('vfd', {
  name: '变频器', group: '三相', short: 'VFD',
  terminals: [{ dx: -60, dy: -40 }, { dx: -60, dy: 0 }, { dx: 60, dy: -40 }, { dx: 60, dy: 0 }, { dx: 60, dy: 40 }, { dx: 0, dy: 80 }], // lin,nin,oa,ob,oc,on
  params: { amplitude: 100, frequency: 50 },
  paramDefs: [
    { key: 'amplitude', label: '输出幅值', unit: 'V' },
    { key: 'frequency', label: '输出频率', unit: 'Hz' }
  ],
  valueOf(c) { return formatSI(c.params.frequency, 'Hz'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-60, -40); ctx.lineTo(-24, -40);
    ctx.moveTo(-60, 0); ctx.lineTo(-24, 0);
    ctx.moveTo(24, -40); ctx.lineTo(60, -40);
    ctx.moveTo(24, 0); ctx.lineTo(60, 0);
    ctx.moveTo(24, 40); ctx.lineTo(60, 40);
    ctx.moveTo(0, 60); ctx.lineTo(0, 80);
    ctx.stroke();
    ctx.strokeRect(-24, -24, 48, 48);
    ctx.font = 'bold 10px sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.fillText('VFD', 0, 0);
    label(ctx, 92, c, def);
  }
});

// ---- 电机族 ----
defType('dc_motor', {
  name: '直流电机', group: '组装器设备', short: 'DCM',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { resistance: 3, inductance: 0.02, tempCoef: 0.0039, thermalConductance: 1, heatCapacity: 40 },
  paramDefs: [
    { key: 'resistance', label: '绕组电阻', unit: 'Ω' },
    { key: 'inductance', label: '绕组电感', unit: 'H' },
    { key: 'tempCoef', label: '温度系数', unit: '/°C' },
    { key: 'thermalConductance', label: '散热', unit: 'W/K' },
    { key: 'heatCapacity', label: '热容', unit: 'J/K' }
  ],
  valueOf(c) { return formatSI(c.params.resistance, 'Ω') + ' + ' + formatSI(c.params.inductance, 'H'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-24, 0);
    ctx.moveTo(24, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    circleLabel(ctx, 'M', 24);
    label(ctx, 34, c, def);
  }
});

defType('brushless_motor', {
  name: '无刷电机', group: '三相', short: 'BLM',
  terminals: [{ dx: -60, dy: -40 }, { dx: -60, dy: 0 }, { dx: -60, dy: 40 }, { dx: 60, dy: 0 }], // a,b,c,n
  params: { resistance: 2, inductance: 0.01, tempCoef: 0.0039, thermalConductance: 1, heatCapacity: 50 },
  paramDefs: [
    { key: 'resistance', label: '相绕组电阻', unit: 'Ω' },
    { key: 'inductance', label: '相绕组电感', unit: 'H' },
    { key: 'tempCoef', label: '温度系数', unit: '/°C' },
    { key: 'thermalConductance', label: '散热', unit: 'W/K' },
    { key: 'heatCapacity', label: '热容', unit: 'J/K' }
  ],
  valueOf(c) { return formatSI(c.params.resistance, 'Ω') + ' + ' + formatSI(c.params.inductance, 'H'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-60, -40); ctx.lineTo(-26, -40);
    ctx.moveTo(-60, 0); ctx.lineTo(-26, 0);
    ctx.moveTo(-60, 40); ctx.lineTo(-26, 40);
    ctx.moveTo(26, 0); ctx.lineTo(60, 0);
    ctx.stroke();
    circleLabel(ctx, 'BL', 26);
    label(ctx, 50, c, def);
  }
});

defType('induction_motor', {
  name: '感应电机', group: '组装器设备', short: 'IM',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { resistance: 2, inductance: 0.03, tempCoef: 0.0039, thermalConductance: 1, heatCapacity: 60 },
  paramDefs: [
    { key: 'resistance', label: '定子电阻', unit: 'Ω' },
    { key: 'inductance', label: '定子电感', unit: 'H' },
    { key: 'tempCoef', label: '温度系数', unit: '/°C' },
    { key: 'thermalConductance', label: '散热', unit: 'W/K' },
    { key: 'heatCapacity', label: '热容', unit: 'J/K' }
  ],
  valueOf(c) { return formatSI(c.params.resistance, 'Ω') + ' + ' + formatSI(c.params.inductance, 'H'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-24, 0);
    ctx.moveTo(24, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    circleLabel(ctx, 'IM', 24);
    label(ctx, 34, c, def);
  }
});

defType('synchronous_motor', {
  name: '同步电机', group: '组装器设备', short: 'SM',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { voltage: 10, resistance: 2, tempCoef: 0.0039, thermalConductance: 1, heatCapacity: 50 },
  paramDefs: [
    { key: 'voltage', label: '反电动势', unit: 'V' },
    { key: 'resistance', label: '定子电阻', unit: 'Ω' },
    { key: 'tempCoef', label: '温度系数', unit: '/°C' },
    { key: 'thermalConductance', label: '散热', unit: 'W/K' },
    { key: 'heatCapacity', label: '热容', unit: 'J/K' }
  ],
  valueOf(c) { return formatSI(c.params.voltage, 'V'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-24, 0);
    ctx.moveTo(24, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    circleLabel(ctx, 'SM', 24);
    label(ctx, 34, c, def);
  }
});

defType('electromachine', {
  name: '电机/发电机', group: '组装器设备', short: 'EMC',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }],
  params: { mode: 'motor', resistance: 5, inductance: 0.05, voltage: 10, tempCoef: 0.0039, thermalConductance: 1, heatCapacity: 50 },
  paramDefs: [
    { key: 'mode', label: '模式', type: 'select', options: ['motor', 'generator'] },
    { key: 'resistance', label: '绕组电阻', unit: 'Ω' },
    { key: 'inductance', label: '绕组电感', unit: 'H' },
    { key: 'voltage', label: '电动势(发电)', unit: 'V' },
    { key: 'tempCoef', label: '温度系数', unit: '/°C' },
    { key: 'thermalConductance', label: '散热', unit: 'W/K' },
    { key: 'heatCapacity', label: '热容', unit: 'J/K' }
  ],
  valueOf(c) { return c.params.mode === 'generator' ? formatSI(c.params.voltage, 'V') : formatSI(c.params.resistance, 'Ω') + '+' + formatSI(c.params.inductance, 'H'); },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-24, 0);
    ctx.moveTo(24, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    circleLabel(ctx, c.params.mode === 'generator' ? 'G' : 'M', 24);
    label(ctx, 34, c, def);
  }
});

defType('electron_tube', {
  name: '电子管', group: '半导体', short: 'TUBE',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }, { dx: 0, dy: -50 }], // anode, cathode, grid
  params: { mu: 20, rp: 10000, vCutoff: -1 },
  paramDefs: [
    { key: 'mu', label: '放大系数 μ' },
    { key: 'rp', label: '内阻 rp', unit: 'Ω' },
    { key: 'vCutoff', label: '截止电压', unit: 'V' }
  ],
  valueOf(c) { return 'μ=' + c.params.mu; },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-30, 0);
    ctx.moveTo(30, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();                      // 玻璃管
    ctx.arc(0, 0, 30, 0, Math.PI * 2);
    ctx.stroke();
    ctx.beginPath();                      // 栅极
    ctx.moveTo(-14, 14); ctx.lineTo(-14, -14);
    ctx.moveTo(-8, 18); ctx.lineTo(-8, -18);
    ctx.moveTo(0, 20); ctx.lineTo(0, -20);
    ctx.stroke();
    ctx.beginPath();                      // 栅极引脚
    ctx.moveTo(0, -50); ctx.lineTo(0, -20);
    ctx.stroke();
    label(ctx, 40, c, def);
  }
});

defType('vfet', {
  name: 'VFET', group: '半导体', short: 'VFET',
  terminals: [{ dx: -50, dy: 0 }, { dx: 50, dy: 0 }, { dx: 0, dy: -50 }], // drain, source, gate
  params: { gm: 0.05, vth: 2 },
  paramDefs: [
    { key: 'gm', label: '跨导', unit: 'S' },
    { key: 'vth', label: '阈值', unit: 'V' }
  ],
  valueOf(c) { return 'Vth=' + c.params.vth + 'V'; },
  draw(ctx, c, def) {
    ctx.beginPath();
    ctx.moveTo(-50, 0); ctx.lineTo(-18, 0);
    ctx.moveTo(18, 0); ctx.lineTo(50, 0);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(-18, -18); ctx.lineTo(-18, 18);
    ctx.moveTo(18, -18); ctx.lineTo(18, 18);
    ctx.moveTo(-18, 0); ctx.lineTo(18, 0);
    ctx.moveTo(-18, -18); ctx.lineTo(18, -18);
    ctx.moveTo(-18, 18); ctx.lineTo(18, 18);
    ctx.stroke();
    ctx.beginPath();                      // 栅极
    ctx.moveTo(0, -50); ctx.lineTo(0, -18);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(-14, -14); ctx.lineTo(8, 8);
    ctx.moveTo(14, -14); ctx.lineTo(-8, 8);
    ctx.stroke();
    label(ctx, 40, c, def);
  }
});

// ---------- Node 环境导出（供命令行/脚本/测试直接调用） ----------
if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    GRID, Vt, clamp, formatSI, trimNum, parseSI,
    absTerminal, COMPONENT_GROUPS, COMPONENT_DEFS, PROBE_COLORS,
    WIRE_TYPES, WIRE_AMBIENT, blendHex
  };
}
