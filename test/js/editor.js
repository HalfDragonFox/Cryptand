'use strict';
// editor.js — 原理图编辑器：放置/移动/旋转/删除元件、布线与探针、缩放平移、撤销重做

function segDist(a, b, p) {
  const dx = b.x - a.x, dy = b.y - a.y;
  const len2 = dx * dx + dy * dy;
  if (len2 === 0) return Math.hypot(p.x - a.x, p.y - a.y);
  let t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2;
  t = Math.max(0, Math.min(1, t));
  return Math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy));
}

class Editor {
  constructor(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.components = [];
    this.wires = [];
    this.probes = [];
    this.mode = 'select';                 // 'select' | 'wire' | 'probe' | 元件类型
    this.rotation = 0;
    this.selected = null;                 // {type:'comp'|'wire', obj}（主选中）
    this.multi = [];                      // 多选集合（框选/Ctrl+点击）
    this.marquee = null;                  // 框选矩形（世界坐标）
    this.gridSnap = true;
    this.ortho = true;
    this.wireColor = '#4fc3f7';
    this.wireWidth = 2;
    this.wireType = 'copper';       // 新建导线的类型（铜/铁/金）
    this.wireResistive = true;      // 导线是否带电阻/温度模型
    this.zoom = 1;
    this.offset = { x: 60, y: 60 };
    this.hover = null;
    this.drag = null;
    this.panning = null;
    this.wireDraft = null;
    this.draftPos = null;
    this.simResults = null;               // 用于元件发光显示
    this.nextId = 1;
    this.undoStack = [];
    this.redoStack = [];
    this.netlist = null;
    this._callbacks = {};
    this._ctxmenu = document.getElementById('ctxmenu');
    this._bind();
  }

  on(name, fn) { this._callbacks[name] = fn; }
  emit(name, ...a) { if (this._callbacks[name]) this._callbacks[name](...a); }
  _id() { return this.nextId++; }

  // ---------- 坐标 ----------
  rect() { return this.canvas.getBoundingClientRect(); }
  getPos(e) { const r = this.rect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; }
  toWorld(sx, sy) { return { x: (sx - this.offset.x) / this.zoom, y: (sy - this.offset.y) / this.zoom }; }
  toScreen(wx, wy) { return { x: wx * this.zoom + this.offset.x, y: wy * this.zoom + this.offset.y }; }
  snap(v) { return this.gridSnap ? Math.round(v / GRID) * GRID : v; }
  snapPoint(p) { return { x: this.snap(p.x), y: this.snap(p.y) }; }

  resize() {
    const parent = this.canvas.parentElement;
    const w = parent.clientWidth, h = parent.clientHeight;
    const dpr = window.devicePixelRatio || 1;
    this.canvas.width = Math.round(w * dpr);
    this.canvas.height = Math.round(h * dpr);
    this.canvas.style.width = w + 'px';
    this.canvas.style.height = h + 'px';
    this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    this.render();
  }

  // ---------- 事件 ----------
  _bind() {
    const cv = this.canvas;
    cv.addEventListener('mousedown', e => this.onDown(e));
    window.addEventListener('mousemove', e => this.onMove(e));
    window.addEventListener('mouseup', e => this.onUp(e));
    cv.addEventListener('wheel', e => this.onWheel(e), { passive: false });
    cv.addEventListener('contextmenu', e => this.onCtx(e));
    window.addEventListener('keydown', e => this.onKey(e));
    window.addEventListener('resize', () => this.resize());
    document.addEventListener('click', () => this.hideCtxMenu());
    document.getElementById('help-close').addEventListener('click', () =>
      document.getElementById('help-modal').classList.add('hidden'));
    this.resize();
  }

  onDown(e) {
    if (e.button === 1) {
      e.preventDefault();
      this.panning = { sx: e.clientX, sy: e.clientY, ox: this.offset.x, oy: this.offset.y };
      return;
    }
    if (e.button !== 0) return;
    const p = this.getPos(e);
    if (this.mode === 'wire') { this.wireClick(p); return; }
    if (this.mode === 'probe') { this.probeClick(p); return; }
    if (this.mode !== 'select') { this.placeComponent(p); return; }
    const hit = this.hitTest(p);
    const w = this.toWorld(p.x, p.y);
    if (hit && e.ctrlKey) {
      // Ctrl+点击：切换多选（AD 风格）
      const idx = this.multi.findIndex(s => s.obj === hit.obj);
      if (idx >= 0) this.multi.splice(idx, 1); else this.multi.push({ type: hit.type, obj: hit.obj });
      this.selected = this.multi.length ? this.multi[this.multi.length - 1] : null;
      this.emit('select'); this.emit('change'); this.render();
      return;
    }
    if (hit && this.multi.length > 1 && this.multi.some(s => s.obj === hit.obj)) {
      // 已多选中：保持多选，整组拖拽
      this.selected = { type: hit.type, obj: hit.obj };
      this.emit('select');
    } else {
      this.select(hit);
    }
    if (hit) {
      const group = (this.multi.length > 1 && this.multi.some(s => s.obj === hit.obj))
        ? this.multi.filter(s => s.type === 'comp').map(s => s.obj)
        : [hit.obj];
      const first = group[0] || hit.obj;
      this.drag = { kind: hit.type, obj: hit.obj, gx: w.x - first.x, gy: w.y - first.y, moved: false, sx: e.clientX, sy: e.clientY, comps: group, attach: new Map() };
      if (hit.type === 'comp') {
        // 记录各元件相连的导线端点（拖动时连线跟随）
        for (const c of group) {
          const def = COMPONENT_DEFS[c.type];
          const att = [];
          for (let ti = 0; ti < def.terminals.length; ti++) {
            const t = absTerminal(c, ti);
            for (const wire of this.wires) for (let j = 0; j < wire.points.length; j++) {
              if (wire.points[j].x === t.x && wire.points[j].y === t.y) att.push({ w: wire, j, ti });
            }
          }
          this.drag.attach.set(c, att);
        }
      }
    } else {
      this.marquee = { x0: w.x, y0: w.y, x1: w.x, y1: w.y };
      this.multi = [];
    }
    this.render();
  }

  onMove(e) {
    const p = this.getPos(e);
    if (this.panning) {
      this.offset = { x: this.panning.ox + (e.clientX - this.panning.sx), y: this.panning.oy + (e.clientY - this.panning.sy) };
      this.render();
      return;
    }
    if (this.marquee) {
      const w = this.toWorld(p.x, p.y);
      this.marquee.x1 = w.x; this.marquee.y1 = w.y;
      this.render();
      return;
    }
    if (this.drag) {
      const w = this.toWorld(p.x, p.y);
      const o = this.drag;
      if (!o.moved && Math.hypot(e.clientX - o.sx, e.clientY - o.sy) > 3) {
        o.moved = true;
        this.recordUndo();
      }
      if (o.moved) {
        if (o.kind === 'comp') {
          for (const c of o.comps) {
            c.x = this.snap(w.x - o.gx);
            c.y = this.snap(w.y - o.gy);
          }
          // 连线跟随：把与元件引脚重合的导线端点移动到新引脚位置
          for (const c of o.comps) {
            const att = o.attach.get(c);
            if (!att) continue;
            for (const { w: wire, j, ti } of att) {
              const nt = absTerminal(c, ti);
              wire.points[j].x = nt.x; wire.points[j].y = nt.y;
            }
          }
        } else {
          this._wireDx = Math.round((w.x - o.gx) / GRID) * GRID;
          this._wireDy = Math.round((w.y - o.gy) / GRID) * GRID;
        }
        this.rebuildNetlist();
        this.emit('change');
        this.render();
      }
      return;
    }
    if (this.mode === 'wire' && this.wireDraft) {
      this.draftPos = this.toWorld(p.x, p.y);
      this.render();
      return;
    }
    this.hover = this.mode === 'select' ? this.hitTest(p) : null;
    if (this.mode !== 'select') this.draftPos = this.toWorld(p.x, p.y);
    this.render();
  }

  onUp(e) {
    if (this.panning) { this.panning = null; return; }
    if (this.marquee) {
      const m = this.marquee;
      this.marquee = null;
      const x0 = Math.min(m.x0, m.x1), x1 = Math.max(m.x0, m.x1);
      const y0 = Math.min(m.y0, m.y1), y1 = Math.max(m.y0, m.y1);
      const sel = [];
      for (const c of this.components) {
        const def = COMPONENT_DEFS[c.type];
        for (let i = 0; i < def.terminals.length; i++) {
          const t = absTerminal(c, i);
          if (t.x >= x0 && t.x <= x1 && t.y >= y0 && t.y <= y1) { sel.push({ type: 'comp', obj: c }); break; }
        }
      }
      for (const w of this.wires) {
        for (const p of w.points) {
          if (p.x >= x0 && p.x <= x1 && p.y >= y0 && p.y <= y1) { sel.push({ type: 'wire', obj: w }); break; }
        }
      }
      this.multi = sel;
      this.selected = sel.length ? sel[sel.length - 1] : null;
      this.emit('select'); this.emit('change'); this.render();
      return;
    }
    if (this.drag) {
      if (this.drag.kind === 'wire' && this._wireDx) {
        const dx = this._wireDx, dy = this._wireDy;
        this._wireDx = 0; this._wireDy = 0;
        this.drag.obj.points.forEach(pt => { pt.x += dx; pt.y += dy; });
        this.rebuildNetlist();
        this.render();
      }
      this.drag = null;
    }
  }

  onWheel(e) {
    e.preventDefault();
    const p = this.getPos(e);
    const w = this.toWorld(p.x, p.y);
    const f = e.deltaY < 0 ? 1.1 : 0.9;
    this.zoom = clamp(this.zoom * f, 0.15, 10);
    this.offset.x = p.x - w.x * this.zoom;
    this.offset.y = p.y - w.y * this.zoom;
    this.render();
  }

  onKey(e) {
    const t = e.target;
    if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT')) return;
    const k = e.key.toLowerCase();
    if (e.ctrlKey || e.metaKey) {
      if (k === 'z') { e.preventDefault(); if (e.shiftKey) this.redo(); else this.undo(); }
      else if (k === 'y') { e.preventDefault(); this.redo(); }
      else if (k === 's') { e.preventDefault(); this.emit('save'); }
      else if (k === 'o') { e.preventDefault(); this.emit('open'); }
      else if (k === 'd') { e.preventDefault(); this.duplicate(); }
      return;
    }
    if (k === 'delete' || k === 'backspace') this.deleteSelected();
    else if (k === 'r') this.rotate();
    else if (k === 'w') this.setMode('wire');
    else if (k === 'p') this.setMode('probe');
    else if (k === 'escape') {
      if (this.wireDraft) { this.wireDraft = null; this.draftPos = null; this.render(); }
      else this.setMode('select');
    }
    else if (k === ' ') { e.preventDefault(); this.setMode('select'); }
    else if (k === 'arrowleft') { this.offset.x += 40; this.render(); }
    else if (k === 'arrowright') { this.offset.x -= 40; this.render(); }
    else if (k === 'arrowup') { this.offset.y += 40; this.render(); }
    else if (k === 'arrowdown') { this.offset.y -= 40; this.render(); }
  }

  onCtx(e) {
    e.preventDefault();
    this.hideCtxMenu();
    const p = this.getPos(e);
    const hit = this.hitTest(p);
    if (hit) this.select(hit);
    const items = [];
    if (this.selected && this.selected.type === 'comp') {
      items.push({ label: '旋转 (R)', fn: () => this.rotate() });
      items.push({ label: '复制 (Ctrl+D)', fn: () => this.duplicate() });
      items.push({ label: '删除 (Del)', fn: () => this.deleteSelected() });
    } else if (this.selected && this.selected.type === 'wire') {
      items.push({ label: '删除 (Del)', fn: () => this.deleteSelected() });
    } else {
      items.push({ label: '全部清除', fn: () => this.clearAll() });
    }
    items.push({ label: '取消', fn: () => {} });
    const m = this._ctxmenu;
    m.innerHTML = '';
    items.forEach(it => {
      const d = document.createElement('div');
      d.className = 'ctx-item';
      d.textContent = it.label;
      d.addEventListener('click', ev => { ev.stopPropagation(); m.style.display = 'none'; it.fn(); });
      m.appendChild(d);
    });
    m.style.display = 'block';
    const r = this.rect();
    m.style.left = Math.min(p.x, r.width - 140) + 'px';
    m.style.top = Math.min(p.y, r.height - items.length * 26) + 'px';
  }
  hideCtxMenu() { if (this._ctxmenu) this._ctxmenu.style.display = 'none'; }

  // ---------- 模式与操作 ----------
  setMode(m) {
    this.mode = m;
    this.wireDraft = null;
    this.draftPos = null;
    this.canvas.style.cursor = (m === 'select') ? 'default' : 'crosshair';
    this.render();
    this.emit('mode');
  }

  select(sel) {
    this.selected = sel ? { type: sel.type, obj: sel.obj } : null;
    this.multi = sel ? [{ type: sel.type, obj: sel.obj }] : [];
    this.emit('select');
    this.emit('change');
    this.render();
  }

  placeComponent(p) {
    const w = this.toWorld(p.x, p.y);
    const pt = this.snapPoint(w);
    const type = this.mode;
    const comp = {
      id: this._id(), type,
      x: pt.x, y: pt.y, rotation: this.rotation,
      params: Object.assign({}, COMPONENT_DEFS[type].params)
    };
    this.recordUndo();
    this.components.push(comp);
    this.select({ type: 'comp', obj: comp });
    this.rebuildNetlist();
    this.emit('change');
    this.render();
  }

  rotate() {
    if (this.selected && this.selected.type === 'comp') {
      this.recordUndo();
      const c = this.selected.obj;
      c.rotation = (c.rotation + 90) % 360;
      this.rotation = c.rotation;
      this.rebuildNetlist();
      this.emit('change');
    } else {
      this.rotation = (this.rotation + 90) % 360;
    }
    this.render();
  }

  duplicate() {
    if (!this.selected || this.selected.type !== 'comp') return;
    const c = this.selected.obj;
    this.recordUndo();
    const nc = JSON.parse(JSON.stringify(c));
    nc.id = this._id();
    nc.x += GRID * 2; nc.y += GRID * 2;
    this.components.push(nc);
    this.selected = { type: 'comp', obj: nc };
    this.rebuildNetlist();
    this.emit('change');
    this.render();
  }

  deleteSelected() {
    const targets = (this.multi && this.multi.length) ? this.multi : (this.selected ? [this.selected] : []);
    if (!targets.length) return;
    this.recordUndo();
    for (const s of targets) {
      if (s.type === 'comp') this.components = this.components.filter(c => c !== s.obj);
      else this.wires = this.wires.filter(w => w !== s.obj);
    }
    this.selected = null;
    this.multi = [];
    this.rebuildNetlist();
    this.emit('change');
    this.render();
  }

  clearAll() {
    this.recordUndo();
    this.components = [];
    this.wires = [];
    this.probes = [];
    this.selected = null;
    this.wireDraft = null;
    this.rebuildNetlist();
    this.emit('change');
    this.render();
  }

  // ---------- 撤销/重做 ----------
  snapshot() { return JSON.stringify({ components: this.components, wires: this.wires, probes: this.probes }); }
  recordUndo() {
    this.undoStack.push(this.snapshot());
    if (this.undoStack.length > 80) this.undoStack.shift();
    this.redoStack.length = 0;
  }
  undo() {
    if (!this.undoStack.length) return;
    this.redoStack.push(this.snapshot());
    this.restore(this.undoStack.pop());
  }
  redo() {
    if (!this.redoStack.length) return;
    this.undoStack.push(this.snapshot());
    this.restore(this.redoStack.pop());
  }
  restore(s) {
    const o = JSON.parse(s);
    this.components = o.components;
    this.wires = o.wires;
    this.probes = o.probes || [];
    this.selected = null;
    this.wireDraft = null;
    this.rebuildNetlist();
    this.emit('change');
    this.render();
  }

  rebuildNetlist() { this.netlist = new Netlist(this.components, this.wires, this.wireResistive !== false); }

  // ---------- 命中测试 ----------
  hitTest(p) {
    const w = this.toWorld(p.x, p.y);
    for (let i = this.components.length - 1; i >= 0; i--) {
      const c = this.components[i];
      if (this.inCompBounds(c, w)) return { type: 'comp', obj: c };
    }
    for (let i = this.wires.length - 1; i >= 0; i--) {
      if (this.distToWire(this.wires[i], w) <= 7 / this.zoom) return { type: 'wire', obj: this.wires[i] };
    }
    return null;
  }

  inCompBounds(c, w) {
    const def = COMPONENT_DEFS[c.type];
    let mx = 0, my = 0;
    for (const t of def.terminals) { mx = Math.max(mx, Math.abs(t.dx)); my = Math.max(my, Math.abs(t.dy)); }
    const dx = w.x - c.x, dy = w.y - c.y;
    const r = -c.rotation * Math.PI / 180;
    const lx = dx * Math.cos(r) - dy * Math.sin(r);
    const ly = dx * Math.sin(r) + dy * Math.cos(r);
    return Math.abs(lx) <= mx + 15 && Math.abs(ly) <= my + 15;
  }

  distToWire(wire, w) {
    let d = Infinity;
    for (let i = 0; i < wire.points.length - 1; i++) {
      d = Math.min(d, segDist(wire.points[i], wire.points[i + 1], w));
    }
    return d;
  }

  nearestTerminal(w, maxDist) {
    let best = null, bd = maxDist;
    for (const c of this.components) {
      const def = COMPONENT_DEFS[c.type];
      for (let i = 0; i < def.terminals.length; i++) {
        const t = absTerminal(c, i);
        const d = Math.hypot(t.x - w.x, t.y - w.y);
        if (d < bd) { bd = d; best = { x: t.x, y: t.y }; }
      }
    }
    return best;
  }

  isJunction(x, y) {
    for (const c of this.components) {
      const def = COMPONENT_DEFS[c.type];
      for (let i = 0; i < def.terminals.length; i++) {
        const t = absTerminal(c, i);
        if (t.x === x && t.y === y) return true;
      }
    }
    return false;
  }

  // ---------- 布线 ----------
  orthoPath(from, to) {
    if (Math.abs(to.x - from.x) >= Math.abs(to.y - from.y)) {
      return [{ x: to.x, y: from.y }, { x: to.x, y: to.y }];
    }
    return [{ x: from.x, y: to.y }, { x: to.x, y: to.y }];
  }

  pushWirePoints(pts) {
    let last = this.wireDraft.points[this.wireDraft.points.length - 1];
    for (const pt of pts) {
      if (pt.x === last.x && pt.y === last.y) continue;
      this.wireDraft.points.push(pt);
      last = pt;
    }
  }

  wireClick(p) {
    const w = this.toWorld(p.x, p.y);
    if (!this.wireDraft) {
      const term = this.nearestTerminal(w, 12 / this.zoom);
      const pt = term ? { x: term.x, y: term.y } : this.snapPoint(w);
      this.wireDraft = { points: [pt] };
      this.render();
      return;
    }
    const last = this.wireDraft.points[this.wireDraft.points.length - 1];
    const term = this.nearestTerminal(w, 12 / this.zoom);
    if (term) {
      this.pushWirePoints(this.ortho ? this.orthoPath(last, term) : [term]);
      this.finishWire();
    } else {
      const pt = this.snapPoint(w);
      this.pushWirePoints(this.ortho ? this.orthoPath(last, pt) : [pt]);
      this.render();
    }
  }

  finishWire() {
    if (this.wireDraft.points.length >= 2) {
      this.recordUndo();
      this.wires.push({ id: this._id(), type: this.wireType, points: this.wireDraft.points });
      this.rebuildNetlist();
      this.emit('change');
    }
    this.wireDraft = null;
    this.draftPos = null;
    this.render();
  }

  // ---------- 探针 ----------
  probeClick(p) {
    const w = this.toWorld(p.x, p.y);
    const term = this.nearestTerminal(w, 12 / this.zoom);
    let pos = null;
    if (term) pos = term;
    else {
      let bd = 12 / this.zoom, bp = null;
      for (const wire of this.wires) for (const pt of wire.points) {
        const d = Math.hypot(pt.x - w.x, pt.y - w.y);
        if (d < bd) { bd = d; bp = pt; }
      }
      if (bp) pos = { x: bp.x, y: bp.y };
    }
    if (!pos) return;
    const idx = this.probes.findIndex(pr => pr.x === pos.x && pr.y === pos.y);
    if (idx >= 0) this.probes.splice(idx, 1);
    else this.probes.push({ x: pos.x, y: pos.y, color: PROBE_COLORS[this.probes.length % PROBE_COLORS.length] });
    this.rebuildNetlist();
    this.emit('change');
    this.render();
  }

  probeNodeIndexes() {
    return this.probes.map(pr => this.netlist ? this.netlist.nodeForPoint(pr.x, pr.y) : 0);
  }

  // ---------- 视图 ----------
  zoomBy(f) {
    const cx = this.canvas.clientWidth / 2, cy = this.canvas.clientHeight / 2;
    const w = this.toWorld(cx, cy);
    this.zoom = clamp(this.zoom * f, 0.15, 10);
    this.offset.x = cx - w.x * this.zoom;
    this.offset.y = cy - w.y * this.zoom;
    this.render();
  }
  zoomIn() { this.zoomBy(1.25); }
  zoomOut() { this.zoomBy(0.8); }

  fit() {
    if (!this.components.length && !this.wires.length) {
      this.zoom = 1; this.offset = { x: 60, y: 60 }; this.render(); return;
    }
    let minx = Infinity, miny = Infinity, maxx = -Infinity, maxy = -Infinity;
    const acc = (x, y) => { minx = Math.min(minx, x); maxx = Math.max(maxx, x); miny = Math.min(miny, y); maxy = Math.max(maxy, y); };
    for (const c of this.components) {
      const def = COMPONENT_DEFS[c.type];
      for (let i = 0; i < def.terminals.length; i++) { const t = absTerminal(c, i); acc(t.x, t.y); }
    }
    for (const w of this.wires) for (const p of w.points) acc(p.x, p.y);
    const pad = 60;
    const cw = this.canvas.clientWidth, ch = this.canvas.clientHeight;
    const w = cw - pad * 2, h = ch - pad * 2;
    this.zoom = clamp(Math.min(w / Math.max(maxx - minx, 1), h / Math.max(maxy - miny, 1)), 0.1, 5);
    this.offset.x = cw / 2 - (minx + maxx) / 2 * this.zoom;
    this.offset.y = ch / 2 - (miny + maxy) / 2 * this.zoom;
    this.render();
  }

  // ---------- 渲染 ----------
  render() {
    const ctx = this.ctx;
    const W = this.canvas.clientWidth, H = this.canvas.clientHeight;
    ctx.clearRect(0, 0, W, H);
    ctx.fillStyle = '#0f1117';
    ctx.fillRect(0, 0, W, H);
    this.drawGrid();
    for (const wire of this.wires) this.drawWire(wire);
    if (this.wireDraft) this.drawWireDraft();
    for (const c of this.components) this.drawComponent(c);
    this.drawProbes();
    this.drawSelection();
    if (this.marquee) this.drawMarquee();
    this.drawWireSnapHint();
    this.drawPlacement();
  }

  // AD 风格：框选矩形
  drawMarquee() {
    const m = this.marquee, ctx = this.ctx;
    const a = this.toScreen(m.x0, m.y0), b = this.toScreen(m.x1, m.y1);
    const x = Math.min(a.x, b.x), y = Math.min(a.y, b.y), w = Math.abs(b.x - a.x), h = Math.abs(b.y - a.y);
    ctx.fillStyle = 'rgba(77,166,255,0.08)';
    ctx.fillRect(x, y, w, h);
    ctx.strokeStyle = '#4da6ff';
    ctx.lineWidth = 1;
    ctx.setLineDash([5, 4]);
    ctx.strokeRect(x, y, w, h);
    ctx.setLineDash([]);
  }

  // 布线时吸附提示：高亮可连接的端子
  drawWireSnapHint() {
    if (this.mode !== 'wire' || !this.draftPos) return;
    const term = this.nearestTerminal(this.draftPos, 14 / this.zoom);
    if (!term) return;
    const ctx = this.ctx;
    const s = this.toScreen(term.x, term.y);
    ctx.strokeStyle = '#69f0ae';
    ctx.lineWidth = 1.5;
    ctx.setLineDash([3, 3]);
    ctx.beginPath();
    ctx.arc(s.x, s.y, 7, 0, Math.PI * 2);
    ctx.stroke();
    ctx.setLineDash([]);
  }

  drawGrid() {
    const ctx = this.ctx;
    const g = GRID * this.zoom;
    if (g < 6) return;
    ctx.strokeStyle = '#171b26';
    ctx.lineWidth = 1;
    const x0 = this.offset.x % g, y0 = this.offset.y % g;
    const W = this.canvas.clientWidth, H = this.canvas.clientHeight;
    ctx.beginPath();
    for (let x = x0; x <= W; x += g) { ctx.moveTo(x, 0); ctx.lineTo(x, H); }
    for (let y = y0; y <= H; y += g) { ctx.moveTo(0, y); ctx.lineTo(W, y); }
    ctx.stroke();
  }

  dot(x, y, r, fill) {
    const ctx = this.ctx;
    const s = this.toScreen(x, y);
    ctx.beginPath();
    ctx.arc(s.x, s.y, r, 0, Math.PI * 2);
    if (fill) { ctx.fillStyle = this.wireColor; ctx.fill(); }
    else { ctx.strokeStyle = this.wireColor; ctx.lineWidth = 1.5; ctx.stroke(); }
  }

  drawWire(wire) {
    const ctx = this.ctx;
    const pts = wire.points;
    let color = wire.color || this.wireColor;
    let burned = false, temp = 0;
    if (this.simResults && this.simResults.wires) {
      const info = this.simResults.wires.get(wire.id);
      if (info) { burned = info.burned; temp = info.t || 0; }
    }
    if (burned) {
      // 烧毁：红色虚线
      ctx.strokeStyle = '#ff5252';
      ctx.lineWidth = this.wireWidth;
      ctx.setLineDash([8, 6]);
    } else {
      // 温度着色：蓝 → 橙红（基于导线类型 maxTemp）
      if (temp > WIRE_AMBIENT + 1) {
        const wt = WIRE_TYPES[wire.type] || WIRE_TYPES.copper;
        color = blendHex(color, '#ff5722', clamp((temp - WIRE_AMBIENT) / (wt.maxTemp - WIRE_AMBIENT), 0, 1));
      }
      ctx.strokeStyle = color;
      ctx.lineWidth = this.wireWidth;
    }
    ctx.lineJoin = 'round';
    ctx.lineCap = 'round';
    ctx.beginPath();
    let s = this.toScreen(pts[0].x, pts[0].y);
    ctx.moveTo(s.x, s.y);
    for (let i = 1; i < pts.length; i++) {
      const p = this.toScreen(pts[i].x, pts[i].y);
      ctx.lineTo(p.x, p.y);
    }
    ctx.stroke();
    ctx.setLineDash([]);
    for (const pt of pts) this.dot(pt.x, pt.y, this.isJunction(pt.x, pt.y) ? 3 : 2, this.isJunction(pt.x, pt.y));
  }

  drawWireDraft() {
    const ctx = this.ctx;
    const pts = this.wireDraft.points;
    ctx.strokeStyle = 'rgba(79,195,247,0.85)';
    ctx.lineWidth = this.wireWidth;
    ctx.setLineDash([6, 4]);
    ctx.beginPath();
    let s = this.toScreen(pts[0].x, pts[0].y);
    ctx.moveTo(s.x, s.y);
    for (let i = 1; i < pts.length; i++) {
      const p = this.toScreen(pts[i].x, pts[i].y);
      ctx.lineTo(p.x, p.y);
    }
    if (this.draftPos) {
      const last = pts[pts.length - 1];
      const target = this.snapPoint(this.draftPos);
      const preview = this.ortho ? this.orthoPath(last, target) : [target];
      for (const pp of preview) { const q = this.toScreen(pp.x, pp.y); ctx.lineTo(q.x, q.y); }
    }
    ctx.stroke();
    ctx.setLineDash([]);
  }

  drawComponent(c) {
    const def = COMPONENT_DEFS[c.type];
    const s = this.toScreen(c.x, c.y);
    const ctx = this.ctx;
    const sel = this.selected && this.selected.obj === c;
    const hov = !sel && this.hover && this.hover.obj === c;
    ctx.save();
    ctx.translate(s.x, s.y);
    ctx.scale(this.zoom, this.zoom);
    ctx.rotate(c.rotation * Math.PI / 180);
    ctx.strokeStyle = sel ? '#ffd54f' : (hov ? '#4da6ff' : '#c9d4e3');
    ctx.fillStyle = sel ? '#ffd54f' : (hov ? '#4da6ff' : '#c9d4e3');
    ctx.lineWidth = 2 / this.zoom;
    ctx.lineJoin = 'round';
    ctx.lineCap = 'round';
    def.draw(ctx, c, def);
    // 仿真结果发光效果
    if ((c.type === 'led' || c.type === 'lamp') && this.simResults && this.simResults.currents) {
      const cur = this.simResults.currents.get(c.id);
      if (cur && Math.abs(cur.i) > 1e-6) {
        const a = Math.min(0.85, 0.18 + Math.abs(cur.i) * 300 + (c.type === 'lamp' ? Math.min(0.5, cur.p * 2) : 0));
        ctx.save();
        ctx.beginPath();
        ctx.arc(0, 0, c.type === 'lamp' ? 20 : 16, 0, Math.PI * 2);
        ctx.fillStyle = c.type === 'led' ? 'rgba(57,255,20,' + a + ')' : 'rgba(255,214,79,' + a + ')';
        ctx.fill();
        ctx.restore();
      }
    }
    ctx.restore();
    // 引脚
    for (let i = 0; i < def.terminals.length; i++) {
      const t = absTerminal(c, i);
      this.dot(t.x, t.y, 3.2, this.isJunction(t.x, t.y));
    }
  }

  drawProbes() {
    const ctx = this.ctx;
    this.probes.forEach((pr, i) => {
      const s = this.toScreen(pr.x, pr.y);
      ctx.strokeStyle = pr.color;
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(s.x, s.y, 8, 0, Math.PI * 2);
      ctx.stroke();
      ctx.fillStyle = pr.color;
      ctx.font = '10px sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText('P' + (i + 1), s.x, s.y);
    });
  }

  drawSelection() {
    const sel = this.selected;
    if (!sel) return;
    const ctx = this.ctx;
    ctx.strokeStyle = '#ffd54f';
    ctx.lineWidth = 1.5;
    ctx.setLineDash([5, 4]);
    if (sel.type === 'comp') {
      const def = COMPONENT_DEFS[sel.obj.type];
      let mx = 0, my = 0;
      for (const t of def.terminals) { mx = Math.max(mx, Math.abs(t.dx)); my = Math.max(my, Math.abs(t.dy)); }
      const r = sel.obj.rotation * Math.PI / 180;
      const corners = [[-mx - 10, -my - 10], [mx + 10, -my - 10], [mx + 10, my + 10], [-mx - 10, my + 10]].map(([x, y]) => {
        const rx = x * Math.cos(r) - y * Math.sin(r), ry = x * Math.sin(r) + y * Math.cos(r);
        return this.toScreen(sel.obj.x + rx, sel.obj.y + ry);
      });
      ctx.beginPath();
      ctx.moveTo(corners[0].x, corners[0].y);
      for (let i = 1; i < 4; i++) ctx.lineTo(corners[i].x, corners[i].y);
      ctx.closePath();
      ctx.stroke();
    } else {
      let minx = Infinity, miny = Infinity, maxx = -Infinity, maxy = -Infinity;
      for (const pt of sel.obj.points) {
        minx = Math.min(minx, pt.x); maxx = Math.max(maxx, pt.x);
        miny = Math.min(miny, pt.y); maxy = Math.max(maxy, pt.y);
      }
      const a = this.toScreen(minx - 8, miny - 8), b = this.toScreen(maxx + 8, maxy + 8);
      ctx.strokeRect(a.x, a.y, b.x - a.x, b.y - a.y);
    }
    ctx.setLineDash([]);
  }

  drawPlacement() {
    if (this.mode === 'select' || this.mode === 'wire' || this.mode === 'probe') return;
    if (!this.draftPos) return;
    const w = this.snapPoint(this.draftPos);
    const ctx = this.ctx;
    ctx.save();
    ctx.globalAlpha = 0.55;
    const s = this.toScreen(w.x, w.y);
    ctx.translate(s.x, s.y);
    ctx.scale(this.zoom, this.zoom);
    ctx.rotate(this.rotation * Math.PI / 180);
    ctx.strokeStyle = '#4da6ff';
    ctx.fillStyle = '#4da6ff';
    ctx.lineWidth = 2 / this.zoom;
    ctx.lineJoin = 'round';
    ctx.lineCap = 'round';
    const def = COMPONENT_DEFS[this.mode];
    def.draw(ctx, { params: def.params, rotation: this.rotation }, def);
    ctx.restore();
  }
}
