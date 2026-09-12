# RelayScope 应用产品与交互规格

> 版本：v2.0
> 最后更新：2026-09-12（v0.6.3 / build-39，逐模型实时进度）
> 状态：第 2 阶段迭代完成，功能全部经主人真机验证
> 用途：产品、前端、Android、OCR、视觉审查 AI 的统一上下文。**接手前必读，配套 `docs/ENGINEERING.md`（工程手册）。**

## 1. 产品定位

RelayScope 是一个 **API 中转站测速与管理工具**，用于比较多个 OpenAI 兼容中转站的真实表现。

它帮用户回答：

> 哪个中转站当前最稳定、最快、模型最全、余额实际消耗最少？

核心工作流：

```text
添加中转站（网址 + API 密钥，只存本机）
→ 测试连通 / 首包 / 流式 / 逐模型可用性（全量或单模型）
→ 录入或识别价格与站内余额扣费倍率（自动拉取 / OCR / 手动）
→ 查看排行榜、模型矩阵、真实成本试算
→ 复制最优站点配置
→ 按自定义间隔自动巡检
```

不是聊天软件，不是代理服务，不是多用户后台。

## 1.1 前端架构（v0.6.2 起模块化，v0.6.3 增 progress 层，硬规则）

前端是唯一视觉基准，Android 只做 WebView 壳 + 原生桥，**禁止 Java 重画界面**。

```text
app/src/main/assets/
├── index.html            壳：DOM 骨架 + 9 个有序 <script> + css link
├── css/app.css           全部样式
└── js/
    ├── state.js          状态声明（nativeSites/nativeResults…）+ $/toast/esc/isImageModel 工具
    ├── bridge-sim.js     ★浏览器模拟桥接：无 AndroidRelay 时注入假方法，浏览器可测全部交互
    ├── bridge.js         onNativeState 回调 + copyText
    ├── render.js         排行榜 / 模型矩阵 / 价格 / 成本试算 渲染
    ├── group.js          分组对话框 + 添加/编辑站点 modal
    ├── mode.js           测试模式面板（全量/单模型/范围/拉取模型）
    ├── progress.js       ★v0.6.3 逐模型实时进度：进度条 + 用时/预计剩余 + 重测超时模型
    ├── inspection.js     巡检开关与倒计时
    └── init.js           启动渲染 + 模块就绪自检（控制台 [RelayScope] 模块自检: 全部就绪）
```

加载顺序固定：`state → bridge-sim → bridge → render → group → mode → progress → inspection → init`。

**模块化硬规则：**
1. 单文件 JS 超 30KB 必拆；每个元素的事件绑定**只允许存在于一个模块**（`addEventListener` 可叠加，重复注册=事故）
2. 勾选行用 `<div>` + 行点击委托，**禁止 `<label>` 包 checkbox/button**（原生转发与委托双重翻转；label 包 button 点击不可靠）
3. 开发顺序：先前端 → 浏览器验（打开 index.html 看 `[RelayScope] 模块自检`）→ 全绿才写 Java → Actions 编 APK
4. 前端演示数据只许存在于显式 `demo` 模式；正式 APK 默认空状态

## 2. 核心功能

### 2.1 四层测速

1. **连通性**：请求 `/v1/models`，判断网址与密钥可用；
2. **首包延迟**：`/v1/models` TTFB；
3. **流式质量**：最小流式请求记录首 token 时间；
4. **模型真实可用性**：逐模型最小请求（8 路并发，v0.6.3 起），不把"列表里有"当"真可用"。

**超时与进度（v0.6.3 硬规则，专治 200+ 模型站点）**

| 项 | 值 | 说明 |
|---|---|---|
| 单模型连接超时 | 5 秒 | 连不上就不再等 |
| 单模型读取超时 | 15 秒 | 首字返回上限 |
| 单模型总上限 | 20 秒 | 超时即标「超时」，不阻塞整批 |
| 整批硬上限 | 10 分钟 | 到时未完成项统一标记「超时」 |
| 并发 | 8 | 模型级并发 |
| 逐模型重试 | 不重试 | 重试只保留给巡检/单模型入口 |
| 结果回传 | 完成顺序 | 哪个模型先测完，UI 先显示哪个，不按提交顺序等待 |

进度显示：总览顶部进度块实时显示 `站点 · 模型 → 状态`、`已完成/总数（百分比）`、进度条、`已用时 / 预计剩余`，并统计可用与失败/超时数量。完成后出现「↻ 只重测超时模型（N）」，只重测超时项，不重跑全部 232 个。

错误分类（必须如实显示）：`认证失败 / 接口/模型不存在 / 限流 / 服务端错误 / 网络错误 / 超时 / 流式空响应 / 部分可用 / 网关返回网页`。
401/403 时透传站点原始 JSON message。

**测试模式面板**（点「开始全站测试」弹出）：

| 选项 | 说明 |
|---|---|
| 全量测试 / 单模型测试 | 单模型只测选定模型一发，最快 |
| 选择模型 | 下拉（历史测过 + 价格库已有）或手动输入，手动优先 |
| ⟳ 拉取全部模型 | 并发拉所有站 `/v1/models`，去重合并填下拉 |
| ⟳ 只拉选中站点 | 只拉「指定站点」勾选的站 |
| 测试范围 | 全部站点（默认）/ 指定站点（勾选名单） |

**停止**：测试中按钮变「点此停止」，点击立即掐断在途请求（`cancelAll` 断 HTTP 连接 + 置取消标记），按钮复位显示「已手动停止」。**后台巡检走独立 `health()` 路径，不受停止影响。**

每站点拉取/测试结果实时 toast 诊断（成功「拉到 N 个模型」/失败「站点名 拉取失败：原因」）。

### 2.2 排行榜（总览）

- 组内排名 01/02…；状态标签分级色：<1s 绿 g、<3s 黄 y、更慢红 r
- 最优站青绿推荐高亮（best）
- 五格指标：首包 / 测试结果 / 可用模型（N 文本 · N 🎨）/ 余额 / 成功率
- 连败 ⚠ 标识（≥2 次失败）、成功率百分比
- 操作：重新测试 / 取余额 / 编辑 / 停用 / 删除 / 复制配置
- **卡片可折叠**：点头部分收起 metrics/操作区（`__stHide` 记忆，重渲染不丢）
- 未分组站点显示为纯卡片（无组头标题），有分组的显示组头（▾ 组名 + 统计 + 测本组）

### 2.3 模型可用性矩阵

横向站点、纵向模型；每站结果卡片可折叠（`mx-head` 点击）。
颜色语义：可用=青绿、限流/波动=黄、不支持/不存在/网络错误=红。
状态来自逐模型真实请求，不只看列表。矩阵允许内部横向滚动，整页不横向溢出。

### 2.4 价格与站内余额扣费倍率

每个模型记录：模型名、输入价/1M、输出价/1M、币种、**站内余额扣费倍率**、来源、更新时间。

```
实际输入成本 = 标价输入价格 × 站内余额扣费倍率
实际输出成本 = 标价输出价格 × 站内余额扣费倍率
```

来源标注：`自动拉取 · 2小时前 / 截图识别 · 已确认 / 截图识别 · 待确认 / 本地手动 · 已保存 / 获取失败 · 使用旧价`。
不完整数据不得覆盖已存价格。倍率专指站内扣费倍率（usage cost/actual_cost 实测），不是官方加价倍数。

### 2.5 价格录入三通道

1. **自动拉取**：可选价格源 JSON URL，通用适配器识别顶层数组/data/models/prices 数组与常见字段名；带 Bearer 密钥；只有完整有效行才写入
2. **截图 OCR**：ML Kit 本地识别 → 字段完整性检查 → 完整开确认表 / 不完整要求补传或手动 → 用户确认才保存；识别结果不自动覆盖
3. **手动录入**：模型名非空、价格非负、倍率>0，非法不保存

### 2.6 成本试算器

输入 token 数（万），输出各站实际预计扣费（含倍率）。底部固定：`试算仅用于比较，实际扣费以站点账单为准。`

### 2.7 自动巡检

- 任意正数分钟间隔（支持小数，0.1=6 秒），5/15/30/60/180 只是快捷填充
- Android 前台服务（dataSync 类型），START_STICKY 不保证；UI 文案只说「按设置尽量运行」
- 低成本模式：`/v1/models` + 一个代表模型流式请求，不逐模型全测
- 巡检与手动测试**隔离**：独立 RelayTester 实例、走 `health()` 不可取消路径

## 3. 视觉设计系统

### 3.1 方向

深色网络监控控制台：专业、克制、工程化。不用营销插画、不大面积紫色、无意义大渐变。

### 3.2 颜色

```text
页面背景 #0E151B   卡片 #172129   次级 #1D2A33   边框 #30404A
主文字 #EDF4F3     次要 #8FA0AA   主强调 #4CE0CA  浅青 #A5F5E8
警告 #FFCC70       错误 #FF7D80
```

语义：青绿=正常/可用/推荐；黄=慢/波动/待确认；红=认证失败/限流/不存在/服务错误。

### 3.3 布局

移动端单列，最大 700px。品牌栏 → Hero → Tabs（总览|模型矩阵|价格与倍率|成本试算）→ 当前页 → 巡检区。卡片圆角 15–18px。

## 4. 数据与隐私

- API 密钥：Android Keystore AES-GCM（alias `relayscope-site-keys-v1`，密文前缀 `enc1:`）存 SharedPreferences；不进 Git/日志/错误提示/第三方服务器
- 价格/站点：SharedPreferences 私有目录；覆盖安装保留，卸载才丢
- 测试结果内存态，重启清零
- 正式 APK 默认空状态，不内置任何演示站点/固定数据

## 5. Android 工程

工程：`https://github.com/mafucai/api-relay-tester`（Java + XML 单 Activity，零第三方依赖；org.json 为系统自带）。

```
MainActivity.java       WebView 宿主 + NativeBridge（18 个 @JavascriptInterface）+ pushState
RelaySite.java          站点模型与 URL 拼接（不猜测 /v1）
RelayTester.java        测速核心；fetchModels(public)；testAsyncCancelable/cancelAll/reset；
                        testSingleModel；CANCELLED_STATUS；ModelsResponse(public static)
SiteStore/PriceStore    持久化；SecretBox Keystore 加密
PriceFetcher/OcrParser  价格拉取与 ML Kit OCR
InspectionService.java  前台巡检（任意小数分钟）
```

### 5.1 桥接方法（18 个，前端↔Java↔bridge-sim 三方必须一一对应）

```
syncState addSite removeSite updateSite testAll testSite testGroup stopTest
fetchBalance fetchPrices fetchModelList setTestMode setTestScope saveManualPrice
pickPriceImage startInspection stopInspection copyText
```

原生→前端回调：`onNativeState / onNativeSiteResult / onNativeModelResult / onNativeTestStart / onNativeTestDone(stopped) / onNativeModelList / onNativeOcr* / onNativeSiteCount`。
**改任何一边，另两边（Java + bridge-sim）必须同步，并跑桥接对齐检查。**
`onNativeModelResult(siteName, model, status, completed, total)` 为 v0.6.3 新增回调，不走桥接方法清单（无需新增 @JavascriptInterface 方法）。

### 5.2 Java 固化写法（防编译错，全部踩过坑）

```java
// lambda 捕获变量：声明不赋值 + 互斥分支各赋一次（赋值 2 次再捕获 = 编译错）
final List<RelaySite> sites;
if (cond) { sites = scoped; } else { sites = all; }

// 多线程完成计数：禁用 int[] --x[0]（竞态丢更新），用 AtomicInteger
final AtomicInteger remaining = new AtomicInteger(n);
if (remaining.decrementAndGet() == 0) { /* 最后一个 */ }
```

## 6. 构建与交付

1. 本地改码 → 本地验证（语法/括号配平/桥接对齐/运行时模拟）→ **主人确认 → 才 push**（推送纪律，多次被强调）
2. push main → Actions 自动 Gradle assembleRelease + apksigner 固定签名 + 发 Release（tag build-N）
3. 交付：request_file_export 或浏览器直链 Release；禁止 cp /sdcard/Download
4. 版本号规则：功能变更必升（versionCode+1）；纯重构可沿用当前版本

签名/Secrets/版本历史详见 `docs/ENGINEERING.md` §5–6。

## 7. 视觉审查指令

将本文件与截图交给识图 AI，逐项检查：整体布局、背景颜色、字体可读性、卡片圆角边框间距、按钮 Tab 样式、排行榜信息完整性、矩阵完整性、价格倍率完整性、OCR 流程体现、巡检间隔体现、缺失/错位/比例失调/与本文冲突。
输出分级：必须修改 / 建议修改 / 可接受差异。
不要把「站内余额扣费倍率」误解成官方加价倍数。

## 8. 当前状态（2026-09-12）

**已实现并经主人真机验证：**
WebView + 桥接四层测速；双模式测试（全量/单模型）；测试范围（全站/指定站）；停止按钮；拉取全部/只拉选中模型；排行榜（分级色/best/连败/成功率/折叠）；模型矩阵（可折叠）；价格三通道；成本试算；巡检（任意小数间隔）；固定签名覆盖安装；前端模块化 + bridge-sim 浏览器调试。

**v0.6.3 新增（待真机验证）：**
逐模型实时进度（8 路并发 + 完成队列 + 逐条回传，哪个先完成先显示）；单模型 20 秒上限（连接 5s / 读取 15s）；整批 10 分钟硬上限；逐模型不再重试；总览进度块（进度条 / 已用时 / 预计剩余 / 可用与失败计数）；「只重测超时模型」按钮。

**待验证/后续方向：**
- v0.6.3 真机验证 232 模型站点：整批是否在 10 分钟内收敛、进度是否逐条刷新
- 不同中转站价格源格式覆盖
- OCR 真实截图识别率
- 厂商后台保活差异
- 并发限制探测（已评估：读 RateLimit 响应头零成本 / 主动探测花钱有风控风险，暂缓）
