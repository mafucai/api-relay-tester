# api-relay-tester 项目治理规则（RelayScope）

## 项目定位
RelayScope = Android 原生壳（WebView）+ 模块化网页前端 + Java 桥接层的 **API 中转站测速与管理工具**。
它不是普通 CRUD 项目：核心是**真实网络测速**与**成本比较**，任何改动必须同时考虑网络超时/并发上限、外部数据转义、桥接三方契约（前端↔Java↔bridge-sim）和版本/签名纪律。

## 开工流程
1. 先读 `docs/ENGINEERING.md`、`docs/RELAYSCOPE-APP-SPEC.md`、本文件、`RISK_CHECKLIST.md`、`ACCEPTANCE.md`。
2. 明确改动文件、行为变化和验收标准。
3. 修改前备份目标文件（`cp f f.bak-<时间戳>`，备份留在同目录）。
4. 只做用户要求的最小改动，不顺手改无关代码。
5. 改完跑 `tools/preflight.sh` 和相关验证（`tools/verify-*.js`）。

## 硬规则
- **桥接三方契约**：`AndroidRelay.xxx` ↔ Java `@JavascriptInterface` ↔ bridge-sim.js 模拟方法，三方必须一一对应；新增回调要走 `onNative*` 通道，不改方法清单时两端（Java + bridge-sim）都要同步。
- **超时与并发上限**（v0.6.3 起）：单模型连接 5s / 读取 15s / 总 20s；逐模型不重试；整批 10 分钟硬上限；模型级并发固定 8。禁止把上限调成无限。
- **网络请求**：必须走 RelayTester 统一 `open()/openModel()`（超时 + UA + 活动连接登记），禁止在 MainActivity 里散开裸 `HttpURLConnection` 不设超时。
- **外部数据转义**：站点名、模型名、状态、价格等外部数据插入 DOM 必须用 `esc()` 或 `textContent`；禁止直接拼 `innerHTML` 不转义。
- **进度实时性**：全量测试必须逐模型回传（`onNativeModelResult`），禁止整批跑完才一次性回传；重绘要节流，禁止 200+ 模型全量重渲染卡死 UI。
- **前端模块化**：单 JS 文件 ≤30KB；每个 DOM 元素事件绑定只允许存在于一个模块（addEventListener 可叠加=事故）。
- **调试面板**：index.html 的 `<body>` 第一行必须内联调试面板（`__dp_trigger`/`__dp_panel`），业务 JS 崩溃也能在屏幕看到错误。
- **版本纪律**：功能变更必升 versionCode+1 与 versionName；推 GitHub 前必须主人确认；不把密钥/keystore 写进仓库。
- **不删除现有备份**，不覆盖用户未要求的改动。

## 评审禁区
下列文件的行为变化必须单独说明影响并补验证：
- `app/src/main/java/com/mafucai/relayscope/RelayTester.java`（测速/超时/并发/取消核心）
- `app/src/main/java/com/mafucai/relayscope/MainActivity.java`（桥接层）
- `app/src/main/assets/js/progress.js`（进度/重测超时）
- `app/src/main/assets/index.html`（DOM 骨架/脚本加载顺序）
- `app/build.gradle`（版本号）

## 近期改动记录
- 2026-09-12：v0.6.3 逐模型实时进度 + 超时上限（8 路并发/20s 单模型/10min 整批/完成队列/重测超时）；补齐本治理四件套 + 调试面板 + preflight。
- 2026-09-07：v0.6.2 双模式测试、拉取模型按钮、模块化重构（10 文件）、分组/折叠/巡检等。