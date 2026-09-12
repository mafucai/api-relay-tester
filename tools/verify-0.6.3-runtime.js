// v0.6.3 异步运行时验证：逐模型进度累计 + 完成态 + 重测超时流程
const fs = require('fs'), vm = require('vm'), path = require('path');
const dir = '/workspace/api-relay-tester/app/src/main/assets';
const html = fs.readFileSync(path.join(dir, 'index.html'), 'utf8');
const files = [...html.matchAll(/<script src="([^"]+)"><\/script>/g)].map(m => path.join(dir, m[1]));

let fail = 0;
const say = (ok, msg) => { if (!ok) fail++; console.log((ok ? 'PASS ' : 'FAIL ') + msg); };

// 最小 DOM 桩：进度块元素带真实可观察状态
const mkEl = id => ({ id: id || '', style: {}, textContent: '', innerHTML: '', disabled: false, value: '', dataset: {}, classList: { add() {}, remove() {}, toggle() {}, contains: () => false }, addEventListener() {}, onclick: null, querySelector: () => mkEl() });
const els = {};
['#testProgress', '#tpModel', '#tpPct', '#tpBar', '#tpStat', '#retryTimeout', '#progMsg', '#test', '#toast', '#stations', '#matrix', '#prices', '#calculator', '#siteCount', '#tested', '#modelSelect', '#modelManual', '#scopeAll', '#scopePick', '#sitePickWrap', '#sitePickList', '#modeModal'].forEach(s => els[s] = mkEl(s.slice(1)));
els['#sitePickList'].innerHTML = '';

const clickHandlers = [];
const sandbox = {
  console, navigator: {},
  setTimeout, clearTimeout, setInterval: () => 0, clearInterval: () => {},
  document: {
    querySelector: s => els[s] || mkEl(),
    querySelectorAll: () => [],
    createElement: () => mkEl(),
    getElementById: () => mkEl(),
    addEventListener: (t, fn) => clickHandlers.push(fn),
  },
  window: { addEventListener() {} }
};
sandbox.window.window = sandbox.window;
sandbox.globalThis = sandbox;
const ctx = vm.createContext(sandbox);
for (const f of files) vm.runInContext(fs.readFileSync(f, 'utf8'), ctx, { filename: path.basename(f) });

const w = sandbox.window;
const calls = [];

(async () => {
  // 1) 开始测试 → 进度块显示、复位
  w.onNativeTestStart(1);
  say(els['#testProgress'].style.display === 'block', '开始测试后进度块显示');
  say(els['#tpBar'].style.width === '0%', '进度条从 0% 起');

  // 2) 逐模型回调（模拟 bridge-sim 的 8 个模型，含 1 个超时）
  const total = 8;
  const seq = ['可用 · 320 ms', '限流', '可用 · 420 ms', '可用 · 480 ms', '可用 · 520 ms', '超时', '可用 · 600 ms', '可用 · 680 ms'];
  // 接管桥接调用记录
  w.AndroidRelay.testAll = () => calls.push(['testAll']);
  w.AndroidRelay.setTestMode = (m, mo) => calls.push(['setTestMode', m, mo]);
  w.AndroidRelay.setTestScope = n => calls.push(['setTestScope', n]);

  seq.forEach((st, i) => w.onNativeModelResult('演示站点', 'model-' + (i + 1), st, i + 1, total));

  say(els['#tpPct'].textContent.includes('8 / 8'), '进度计数到达 8/8（实际 ' + els['#tpPct'].textContent + '）');
  say(els['#tpBar'].style.width === '100%', '进度条到达 100%');
  say(els['#tpStat'].textContent.includes('已用时'), '显示已用时与预计剩余（' + els['#tpStat'].textContent + '）');

  // 3) 完成 → 出现「只重测超时模型」，且只计 1 个超时
  w.onNativeTestDone(false);
  say(els['#retryTimeout'].style.display === 'inline-block', '完成后出现重测超时按钮');
  say(els['#retryTimeout'].textContent.includes('1'), '重测按钮只计 1 个超时模型（' + els['#retryTimeout'].textContent + '）');

  // 4) 结果已并入内存态（模型矩阵逐条增长）
  const res = vm.runInContext('nativeResults["演示站点"]', ctx);
  say(!!res && Object.keys(res.modelResults).length === total, '逐模型结果已并入 nativeResults（' + (res ? Object.keys(res.modelResults).length : 0) + ' 条）');
  say(res && res.modelResults['model-6'] === '超时', '超时模型状态正确记录');

  // 5) 点「只重测超时模型」→ 只测那 1 个模型，不重跑全部
  clickHandlers.forEach(fn => fn({ target: els['#retryTimeout'] }));
  await new Promise(r => setTimeout(r, 60));
  const single = calls.filter(c => c[0] === 'setTestMode' && c[1] === 'single');
  say(single.length === 1, '重测只发起 1 次单模型测试（实际 ' + single.length + '）');
  say(single[0] && single[0][2] === 'model-6', '重测目标正是超时模型 model-6（' + (single[0] && single[0][2]) + '）');

  // 6) 二次开始不叠加计时器 / 事件绑定唯一
  w.onNativeTestStart(1); w.onNativeTestStart(1);
  say(els['#tpPct'].textContent === '0 / 0' || els['#tpPct'].textContent.includes('0 /'), '二次开始正确复位进度');

  console.log(fail === 0 ? '\n[RelayScope] 运行时验证全部通过' : '\n[RelayScope] 失败 ' + fail + ' 项');
  process.exit(fail === 0 ? 0 : 1);
})();
