// v0.6.3 本地等价验证：DOM/ID 一致性 + 进度回调运行时模拟（无浏览器，vm 上下文）
const fs = require('fs'), vm = require('vm'), path = require('path');
const dir = '/workspace/api-relay-tester/app/src/main/assets';
const html = fs.readFileSync(path.join(dir, 'index.html'), 'utf8');
const scripts = [...html.matchAll(/<script src="([^"]+)"><\/script>/g)].map(m => m[1]);
let fail = 0;
const say = (ok, msg) => { if (!ok) fail++; console.log((ok ? 'PASS ' : 'FAIL ') + msg); };

// 1. 加载顺序包含 progress.js，且在 mode.js 之后
const iMode = scripts.indexOf('js/mode.js'), iProg = scripts.indexOf('js/progress.js'), iInit = scripts.indexOf('js/init.js');
say(iMode >= 0 && iProg === iMode + 1, '加载顺序 mode.js → progress.js（实际 ' + iMode + '→' + iProg + '）');
say(iProg < iInit, 'progress.js 在 init.js 之前');

// 2. 进度所需 DOM 元素全部存在
const needIds = ['testProgress', 'tpModel', 'tpPct', 'tpBar', 'tpStat', 'retryTimeout', 'progMsg'];
const htmlIds = new Set([...html.matchAll(/id="([^"]+)"/g)].map(m => m[1]));
needIds.forEach(id => say(htmlIds.has(id), 'DOM id 存在: ' + id));

// 3. progress.js 内引用的 #xxx 与 HTML id 对齐
const prog = fs.readFileSync(path.join(dir, 'js/progress.js'), 'utf8');
const used = new Set([...prog.matchAll(/\$\('#([A-Za-z0-9_]+)'\)/g)].map(m => m[1]));
[...used].forEach(id => say(htmlIds.has(id), 'progress.js 引用 id 已定义: ' + id));
const cssSel = [...prog.matchAll(/querySelector\('#([A-Za-z0-9_]+)\s+\.([A-Za-z0-9_-]+)'\)/g)];
cssSel.forEach(([, id, cls]) => say(htmlIds.has(id), 'querySelector 目标 id 已定义: #' + id + ' .' + cls));

// 4. 事件绑定唯一性：onNativeModelResult 只在 progress.js 定义
const files = scripts.map(s => path.join(dir, s));
const defs = files.filter(f => fs.readFileSync(f, 'utf8').includes('window.onNativeModelResult='));
say(defs.length === 1 && defs[0].endsWith('progress.js'), 'onNativeModelResult 仅单点定义');
const cancelDefs = files.filter(f => fs.readFileSync(f, 'utf8').includes("$('#modeCancel').onclick"));
say(cancelDefs.length === 1, 'modeCancel 绑定唯一（' + cancelDefs.length + ' 处）');

// 5. 运行时模拟：加载全部模块，触发模拟桥接的逐模型进度
const listeners = {};
const sandbox = {
  console,
  navigator: {},
  setTimeout: (fn, ms) => setTimeout(fn, 0), clearTimeout,
  setInterval: () => 0, clearInterval: () => {},
  document: {
    querySelector: () => ({ style: {}, classList: { add() {}, remove() {}, toggle() {}, contains: () => false }, addEventListener() {}, textContent: '', innerHTML: '', onclick: null, disabled: false, dataset: {}, value: '' }),
    querySelectorAll: () => [],
    createElement: () => ({ style: {}, classList: { add() {}, remove() {} }, innerHTML: '' }),
    getElementById: () => ({ addEventListener() {}, onclick: null }),
    addEventListener: (t, fn) => { (listeners[t] = listeners[t] || []).push(fn) },
    body: { firstChild: null }
  },
  window: { addEventListener() {}, AndroidRelay: undefined }
};
sandbox.window.window = sandbox.window;
sandbox.globalThis = sandbox;
const ctx = vm.createContext(sandbox);
try {
  for (const f of files) vm.runInContext(fs.readFileSync(f, 'utf8'), ctx, { filename: path.basename(f) });
  say(true, '九个模块按序在 vm 中执行无异常');
} catch (e) { say(false, '模块执行异常: ' + e.message); }

// 6. 模拟桥接注入 → 触发 testAll → 逐模型回调
try {
  const w = sandbox.window;
  say(typeof w.AndroidRelay === 'object' && w.AndroidRelay !== null, 'bridge-sim 已注入假桥接');
  say(typeof w.onNativeModelResult === 'function', 'onNativeModelResult 已注册');
  say(typeof w.onNativeTestStart === 'function', 'onNativeTestStart 已注册');
  say(typeof w.onNativeTestDone === 'function', 'onNativeTestDone 已注册（包装后仍为函数）');
  let events = 0;
  const orig = w.onNativeModelResult;
  w.onNativeModelResult = function () { events++; return orig.apply(this, arguments) };
  w.onNativeTestStart(1);
  w.AndroidRelay.testAll();
} catch (e) { say(false, '运行时模拟异常: ' + e.message); }

// 7. 桥接三方对齐（前端调用 ↔ Java 注解 ↔ bridge-sim）
const frontCalls = new Set();
files.forEach(f => { const s = fs.readFileSync(f, 'utf8'); [...s.matchAll(/AndroidRelay\.([A-Za-z]+)\(/g)].forEach(m => frontCalls.add(m[1])); });
const java = fs.readFileSync('/workspace/api-relay-tester/app/src/main/java/com/mafucai/relayscope/MainActivity.java', 'utf8');
const javaMethods = new Set([...java.matchAll(/@JavascriptInterface\s+public void ([A-Za-z]+)\(/g)].map(m => m[1]));
const sim = fs.readFileSync(path.join(dir, 'js/bridge-sim.js'), 'utf8');
const missingJava = [...frontCalls].filter(x => !javaMethods.has(x));
const missingSim = [...frontCalls].filter(x => !new RegExp('\\b' + x + '\\s*\\(').test(sim));
say(missingJava.length === 0, '前端调用均存在 Java 注解方法' + (missingJava.length ? ' 缺: ' + missingJava.join(',') : ''));
say(missingSim.length === 0, '前端调用均存在 bridge-sim 模拟' + (missingSim.length ? ' 缺: ' + missingSim.join(',') : ''));

// 8. Java 新增回调契约存在
const rt = fs.readFileSync('/workspace/api-relay-tester/app/src/main/java/com/mafucai/relayscope/RelayTester.java', 'utf8');
say(rt.includes('interface ModelCallback'), 'RelayTester.ModelCallback 已定义');
say(java.includes('window.onNativeModelResult'), 'MainActivity 回传 onNativeModelResult');

console.log(fail === 0 ? '\n[RelayScope] 全部检查通过' : '\n[RelayScope] 失败 ' + fail + ' 项');
process.exit(fail === 0 ? 0 : 1);
