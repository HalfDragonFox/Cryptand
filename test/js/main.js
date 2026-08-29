'use strict';
// main.js — 入口：创建编辑器与仿真器并初始化 UI
(function () {
  const editor = new Editor(document.getElementById('canvas'));
  const sim = new Simulator();
  window.editor = editor;
  window.sim = sim;
  buildUI(editor, sim);
})();
