# RelayScope 工程手册（下一个 AI 必读）

> 最后更新：2026-09-07（v0.6.2 / build-36，第 2 阶段完成）
> 用途：接手本项目的 AI / 人类先读这份。读完即知：技术栈、代码地图、数据位置、构建交付、签名、历史坑。
> 配套：产品交互规格 `docs/RELAYSCOPE-APP-SPEC.md`（v2.0，功能与视觉以此为准）。

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
                          - 4 路模型级并发；BROWSER_UA 防 WAF；looksLikeHtml 防 HTML 网关页；classify 错误分类
  SiteStore.java          站点持久化（SharedPreferences "relayscope_sites"）
  SecretBox.java          API Key 加密：Android Keystore AES-GCM，alias "relayscope-site-keys-v1"，密文前缀 "enc1:"
  PriceStore.java         价格持久化（"relayscope_prices"）
  PriceFetcher.java       JSON 价格源拉取
  PriceOcrParser.java     价格截图 OCR（ML Kit）
  InspectionService.java  前台巡检服务（dataSync 类型，任意小数分钟；独立 RelayTester 实例）

app/src/main/assets/
  index.html              壳（DOM + 8 个有序 script）
  css/app.css             全部样式
  js/                     8 模块（state / bridge-sim / bridge / render / group / mode / inspection / init）
                          详见 RELAYSCOPE-APP-SPEC.md §1.1；bridge-sim.js=浏览器模拟桥接（18 方法）

app/build.gradle          versionCode 20 / versionName '0.6.2' 在这里改
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
