# RelayScope 工程手册（下一个 AI 必读）

> 最后更新：2026-09-16（v0.6.5：perf-metrics 主路径【零调用拿健康度】+ 探活兜底【并发 8→2、Retry-After、抖动、429 停批】+ 额度读取；v0.6.4 修复全站测试按钮卡死等）
> 用途：接手本项目的 AI / 人类先读这份。读完即知：技术栈、代码地图、数据位置、构建交付、签名、历史坑。
> 配套：产品交互规格 `docs/RELAYSCOPE-APP-SPEC.md`（v2.0，功能与视觉以此为准）。
> 治理：项目根 `PROJECT_RULES.md` / `RISK_CHECKLIST.md` / `ACCEPTANCE.md` / `LOW_MODEL_TASK_TEMPLATE.md`（2026-09-12 补齐）；入口 `bash tools/preflight.sh`。

## 1. 一句话简介

RelayScope = Android 原生壳（WebView）+ 模块化网页前端 + Java 桥接层。测多个 OpenAI 兼容中转站的真实表现（首包/流式/逐模型可用性/真实余额成本），本地保存站点与价格。

- 包名 `com.mafucai.relayscope`
- 仓库 `https://github.com/mafucai/api-relay-tester`（git+SSH，账号 mafucai）
- 技术栈：**纯 Java + XML，零第三方依赖**；WebView 加载 `assets/index.html`；JavascriptInterface 桥

## 2. 代码地图

```
app/src/main/java/com/mafucai/relayscope/
  MainActivity.java       WebView 宿主 + NativeBridge（18 个 @JavascriptInterface 方法）
                          + pushState 状态回传 + noteFailStreak 成功率/连败统计
                          + fetchModelList(scope) 并发拉模型（AtomicInteger 计数 + LinkedHashSet 去重）
                          + setTestMode/setTestScope；stopTest→onNativeTestDone(true) 复位
  RelaySite.java          站点模型：name/baseUrl/apiKey/priceUrl/group；URL 拼接不猜测（填什么用什么）
  RelayTester.java        测速核心：
                          - testAsyncCancelable / cancelAll（AtomicBoolean + pendingThreads + activeConnections）
                          - reset() 每批测试前清标记；testSingleModel 单模型一发
                          - CANCELLED_STATUS="已停止"；巡检走 health() 独立路径不受取消影响
                          - fetchModels 现为 public（MainActivity 复用）；ModelsResponse 为 public static class
                          - v0.6.3：MODEL_WORKERS=8 路并发 + ExecutorCompletionService 完成队列，按完成顺序 emitModel
                          - v0.6.3：逐模型不重试（MAX_RETRIES 只留给 health/single）；连接 5s/读取 15s
                          - v0.6.3：MODEL_BATCH_TIMEOUT_MS=10 分钟整批硬上限，超时项标记「超时」
                          - BROWSER_UA 防 WAF；looksLikeHtml 防 HTML 网关页；classify 错误分类
  SiteStore.java          站点持久化（SharedPreferences "relayscope_sites"）
  SecretBox.java          API Key 加密：Android Keystore AES-GCM，alias "relayscope-site-keys-v1"，密文前缀 "enc1:"
  PriceStore.java         价格持久化（"relayscope_prices"）
  PriceFetcher.java       JSON 价格源拉取
  PriceOcrParser.java     价格截图 OCR（ML Kit）
  InspectionService.java  前台巡检服务（dataSync 类型，任意小数分钟；独立 RelayTester 实例）

app/src/main/assets/
  index.html              壳（DOM + 9 个有序 script）
  css/app.css             全部样式
  js/                     9 模块（state / bridge-sim / bridge / render / group / mode / progress / inspection / init）
                          详见 RELAYSCOPE-APP-SPEC.md §1.1；bridge-sim.js=浏览器模拟桥接
                          progress.js=v0.6.3 逐模型实时进度层（进度条/用时/预计剩余/重测超时）

app/build.gradle          versionCode 23 / versionName '0.6.5' 在这里改
```

## 3. 桥接对齐（三方契约，最高优先级）

前端 `AndroidRelay.xxx` ↔ Java `@JavascriptInterface` ↔ bridge-sim.js 模拟方法，**三方必须一一对应（当前 18 个）**：

```
syncState addSite removeSite updateSite testAll testSite testGroup stopTest
fetchBalance fetchPrices fetchModelList setTestMode setTestScope saveManualPrice
pickPriceImage startInspection stopInspection copyText
```

回调：`onNativeState / onNativeSiteResult / onNativeTestStart / onNativeTestDone(stopped) / onNativeModelList / onNativeOcr* / onNativeSiteCount`。

**改任何一边，另两边必须同步。** 推送前跑桥接对齐检查（见 §6 检查体系）。

## 4. 数据位置

| 数据 | 位置 | 覆盖安装 | 卸载 |
|---|---|---|---|
| 站点列表（含加密密钥） | SharedPreferences `relayscope_sites` | ✅ | ❌ |
| 价格与倍率 | SharedPreferences `relayscope_prices` | ✅ | ❌ |
| 加密密钥本体 | Android Keystore alias `relayscope-site-keys-v1` | ✅ | ❌ |
| 测试结果 | 内存 Map | 启动清零 | - |

**签名一致即可覆盖安装、数据不丢**（签名见 §5）。卸载全丢。

## 5. 签名（命根）

- 正式 keystore：`relayscope-release.jks`（仓库根目录，已 gitignore）；alias `relayscope`；storepass/keypass `relayscope-2026`
- 证书 SHA-256：`CC:62:BD:5C:E0:C3:A9:B9:B9:42:BF:22:25:C1:2D:2C:7F:5D:E1:05:5B:6B:2B:4D:B8:54:9B:A0:26:4F:CA:2C`
- CI 从 GitHub Secrets `KEYSTORE_BASE64` 还原；build-13 起全部固定签名
- keystore 丢失 = 永远无法覆盖安装。主人已被告知自行再备份

## 6. 开发流程与检查体系（第 2 阶段固化）

### 6.1 钦定顺序（不可跳步）

```text
① 前端（模块化，bridge-sim 第一个写）
② 浏览器打开 index.html 验证（控制台 [RelayScope] 模块自检: 全部就绪）
③ 全绿 → Java 层
④ 本地验证 → 主人确认 → push（推送纪律：主人不确认不推）
⑤ Actions 编译 → Release → 主人真机验证
```

### 6.2 本地验证（无 javac 环境的等价检查）

- JS：`node --check` 每个模块
- Java：括号/圆括号配平（strip 字符串注释后计数）+ 方法重复定义 grep
- 桥接：前端 `AndroidRelay.x(` ↔ Java `@JavascriptInterface` ↔ bridge-sim 三方清点
- 运行时：vm 上下文按加载顺序跑全链（onNativeState→面板→拉取→测速→停止→分组）
- 专项：addEventListener 唯一性、勾选翻转次数、lambda 捕获变量赋值次数

### 6.3 推送后

`gh run list --limit 1` 轮询（手机代理下 `gh run watch` 易 EOF）；失败看 `gh run view N --log-failed | grep error`。

## 7. 血泪教训（第 2 阶段实录，每条都真踩过）

1. **addEventListener 可叠加**：模块拆分时同段代码进两个文件 → 委托注册 2 次 → 点击翻转 2 次抵消 →"勾选失效"。拆分后必须清点每个元素绑定次数
2. **label 嵌 checkbox/button**：label 原生转发 + 自定义委托 = 双重翻转；label 嵌 button 在 WebView 点击不可靠。勾选行统一 `<div>` + 事件委托
3. **Java lambda 捕获**：变量赋值 2 次（声明即赋+重赋）再被 lambda 捕获 = 编译错。固化：`final X x; if(){x=a;}else{x=b;}`（连续炸了 build-33/34）
4. **int[] 计数竞态**：`--remaining[0]` 非原子，多线程丢更新 → 回调永不触发 →"拉取没反应"。用 `AtomicInteger.decrementAndGet()`
5. **nativeResults 是对象不是数组**：`.forEach` 直接崩，用 `Object.values()`
6. **嵌套类可见性**：方法改 public，其返回的嵌套类（ModelsResponse）也要 public（build-33 前炸过）
7. **推送纪律**：本地验证 → 主人确认 → 才推。多次违反被主人纠正，不可再犯
8. **检查脚本会误报**：整行匹配对合法修改误报，定性前必须人工逐条复核
9. **失败必沉淀**：每个构建失败/功能 bug 写「现象→根因→应对」入本表，不许只修不留痕
10. **逐模型 must not 按提交顺序 get()**：`for (entry : futures) value.get()` 会让第 1 个慢模型挡住后面 231 个已完成的快模型（232 模型站 15 分钟只出 15 个的根因之一）。应对：`ExecutorCompletionService` 完成队列 + `poll(deadline)`，谁先完成先回调
11. **逐模型不能带重试**：`withRetry` 对每个模型最多 3 次 ×（8s 连接 + 15s 读取）= 单模型最坏约 69 秒；232 模型在 4 路并发下等于必然超时。应对：批内逐模型零重试 + 连接 5s/读取 15s + 整批 10 分钟硬上限；重试只留给巡检与单模型入口
13. **`int[]` 计数竞态在 `testAll` 里漏修**（v0.6.4 实锤）：教训 4 只在 `fetchModelList` 修过，`testAll` / `testGroup` / `singleResult` 三处仍是 `--remaining[0]`。多站点并发时丢更新 → `onNativeTestDone` 永不触发 → **按钮永久卡在「正在测试… · 点此停止」**，单站点正常、多站点必挂。应对：三处统一 `AtomicInteger.decrementAndGet()`；`results`/`balances`/`failStreak` 三个多线程 Map 一并改 `ConcurrentHashMap`。**同一类 bug 要全局搜一遍，不能只修报警的那一处。**
14. **前端乐观置 `running` 是隐患**（v0.6.4）：`modeSave` 先置 `dataset.running='1'` 再调 `testAll()`；若 Java 因前置校验提前 `return`（如无站点）而不发任何回调，按钮就卡死。应对：running 只由 `onNativeTestStart` 置位，并加 12 分钟看门狗兜底（整批硬上限 10 分钟）。
15. **`.copy` 被两个模块重复绑定**：`render.js` 用 `closest('.station')` + `.name.textContent` 取站点名，但 `.name` 里含分组 `<span class="gtag">`，取到的名字带分组名 → `nativeSites.find` 永远找不到 → 复制静默失效；`mode.js` 又用 `onclick=` 覆盖了它，只 toast 不真复制。应对：改用 `data-copy="${esc(s.name)}"` 精确传名，并删除重复绑定。
16. **探活 232 模型必被限流**（v0.6.5）：逐模型真实请求 × 8 路并发 = 一次打 232 次调用，站点必然 429。**根因是方法选错，不是并发调优能救的。** 应对：主路径改读站点自带的 `/api/perf-metrics/summary`（**1 次请求拿全部模型**，零调用零额度零限流）；探活降级为兜底，并发 8→2 + 请求间 100-300ms 抖动 + 读 `Retry-After` + **遇 429 立即停批**（剩余模型标「已跳过（限流）」，不把限流推得更深）。
17. **429 原本漏到 else 分支**：`probeChat` 里 `classify()` 虽定义了 429→「限流」，但批处理层没有据此中断，会把剩余模型全部打完。应对：批处理循环检测到「限流」立即 break 并取消在途 Future。
12. **无进度 = 无法判断是卡死还是慢**：原实现只在整站结束后回传一次结果。应对：新增 `onNativeModelResult` 逐条回调 + 进度块（已完成/总数、用时、预计剩余、超时计数），并节流重绘（500ms）避免 232 次全量渲染卡 UI

## 8. 版本历史要点

| 版本 | 干了什么 / 坑 |
|---|---|
| build-4 | 旧原生手绘界面 + CI 临时签名（弃） |
| build-7~9 | WebView 化；状态同步 + addIfAbsent 去重；POST_NOTIFICATIONS |
| build-10~12 | looksLikeHtml 防 HTML 网关；BROWSER_UA 防 WAF；4 路模型并发；编辑站点 |
| build-13 | **固定签名**（此后覆盖安装无忧） |
| build-14~23 | v0.5.x：价格三通道、OCR、巡检前台服务、分组、连败统计、模型矩阵折叠 |
| build-24 | v0.6.1：停止按钮卡死修复（stopTest→onNativeTestDone(true)） |
| build-25~27 | v0.6.2：双模式测试（全量/单模型）；拉取模型按钮（嵌套类可见性炸过一次） |
| build-28~30 | 模块化重构（35KB 单文件→10 文件）；AtomicInteger 竞态修复；label→div |
| build-31 | **勾选失效根因修复**：拆分时分组代码重复进两模块，addEventListener 双注册 → 翻转抵消 |
| build-32 | 排行榜卡片折叠 |
| build-33~34 | 指定站点测试范围（lambda final 连炸两次，见教训 3/§5.2） |
| build-35~36 | final 写法固化；只拉选中站点按钮；拉取逐站诊断 toast |
| build-39 | v0.6.3：逐模型实时进度（8 路并发+完成队列+逐条回传）；单模型 20 秒上限（连接 5s/读取 15s）；整批 10 分钟硬上限；逐模型不重试；只重测超时模型 |
| **build-41** | **v0.6.5：perf-metrics 主路径（零调用拿健康度）+ 探活兜底（并发 8→2 / Retry-After / 抖动 / 429 停批，教训 16/17）+ 额度读取（/api/user/self）** |
| **build-40** | **v0.6.4：修复全站测试按钮卡死（int[] 计数竞态，教训 13）+ 前端乐观 running（教训 14）+ 重复 id retryTimeout + 调试面板 `\'` 语法错误（曾使整段内联脚本不执行）+ 卡片多余 `</div>` + 组内排序表达式 + 复制配置（教训 15）** |
| build-39 治理 | 补齐治理四件套（PROJECT_RULES/RISK_CHECKLIST/ACCEPTANCE/LOW_MODEL_TASK_TEMPLATE）+ tools/preflight.sh 检查入口 + body 内联调试面板（铁律 2） |

## 9. 本地开发环境

- ProotLinux：源码在 `/workspace/api-relay-tester`（若工作区被清理：`git clone git@github.com:mafucai/api-relay-tester.git`）；**无 Android SDK，只能云端编译**
- 工具：gh CLI（已登录 mafucai）、node 18（--check + vm 运行时模拟）、python3（配平/等价测试）
- 备份纪律：改前 `cp f f.bak-<标记>`
- keystore 备份：主人自行保管一份

## 10. 下一个 AI 的检查单

1. 读本文 + `docs/RELAYSCOPE-APP-SPEC.md`（功能视觉以它为准）
2. `git log --oneline -5` 看最新状态；`gh release list --limit 1` 看最新发布
3. 改码前备份；改完跑 §6.2 全套本地验证
4. 功能变更升 versionCode，推前问主人
5. 交付报告必含：Release 直链、SHA-256、versionName
6. 别碰 `.bak*`；keystore/密钥不进仓库
7. 遇到"点了没反应"：先浏览器 bridge-sim 复现，JS 层通了再查 Java
