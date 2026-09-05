# RelayScope 工程手册（下一个 AI 必读）

> 最后更新：2026-09-05（v0.4.2 阶段）
> 用途：接手本项目的一切 AI / 人类，先读这份。读完就能知道：用什么技术、数据在哪、怎么构建、密码是什么、下一步做什么。
> 配套文档：产品交互规格 `docs/RELAYSCOPE-APP-SPEC.md`（视觉与功能细节）；构建坑清单 `/workspace/docs/ANDROID-APP-GITHUB-ACTIONS-BUILD.md`。

## 1. 一句话简介

RelayScope = Android 原生壳（WebView）+ 已确认的网页前端 + Java 桥接层。测多个 OpenAI 兼容中转站的真实表现（首包/流式/模型可用性），本地保存站点与价格。

- 包名 `com.mafucai.relayscope`
- 仓库 `https://github.com/mafucai/api-relay-tester`（git+SSH，账号 mafucai）
- 技术栈：**纯 Java + XML，零第三方依赖**（org.json 是 Android 自带）；WebView 加载 `assets/index.html`；`JavascriptInterface` 桥

## 2. 代码地图（谁负责什么）

```
app/src/main/java/com/mafucai/relayscope/
  MainActivity.java      WebView 宿主 + NativeBridge（全部 @JavascriptInterface 方法）+ pushState 状态回传
  RelaySite.java         站点模型：name/baseUrl/apiKey/priceUrl；URL 拼接（不猜测，填什么用什么）
  RelayTester.java       测速核心：fetchModels→probeChat；4 路模型级并发；BROWSER_UA 防 WAF；
                         looksLikeHtml() 防网关 HTML 页；classify() 错误分类 + 403 透传站点原始 message
  SiteStore.java         站点持久化（SharedPreferences "relayscope_sites"）：load/addIfAbsent/removeByName/updateByBaseUrl
  SecretBox.java         API Key 加密：Android Keystore AES-GCM，alias "relayscope-site-keys-v1"，密文前缀 "enc1:"
  PriceStore.java        价格持久化（SharedPreferences "relayscope_prices"）
  PriceFetcher.java      JSON 价格源拉取（需站点填 priceUrl；aimover 类 HTML 渲染站点拉不到，靠 OCR/手动）
  PriceOcrParser.java    价格截图 OCR（ML Kit）
  InspectionService.java 前台巡检服务（dataSync 类型，任意小数分钟间隔）
app/src/main/assets/index.html  唯一前端（HTML+CSS+JS 单文件，就是浏览器原型同一份）
app/src/main/AndroidManifest.xml 权限：INTERNET + POST_NOTIFICATIONS + FOREGROUND_SERVICE(_DATA_SYNC)
app/build.gradle         versionCode/versionName 在这里改；当前 6 / 0.4.1
```

### 桥接方法清单（前端↔原生，必须一一对应）
前端调用 `AndroidRelay.xxx`，原生必须同名 `@JavascriptInterface`：
`syncState, addSite, updateSite, removeSite, testAll, testSite, fetchPrices, pickPriceImage, saveManualPrice, startInspection, stopInspection`
原生→前端回调：`window.onNativeState / onNativeSiteResult / onNativeTestStart / onNativeTestDone / onNativeOcr*`。
**改任何一边，另一边必须同步，并用本地验证脚本核对无缺失。**

## 3. 数据在哪（覆盖安装会不会丢）

| 数据 | 位置 | 覆盖安装 | 卸载 |
|---|---|---|---|
| 站点列表（含加密密钥） | SharedPreferences `relayscope_sites`（app 私有） | ✅ 保留 | ❌ 丢 |
| 价格与倍率 | SharedPreferences `relayscope_prices` | ✅ 保留 | ❌ 丢 |
| 加密密钥本体 | Android Keystore（alias `relayscope-site-keys-v1`） | ✅ 保留 | ❌ 丢 |
| 测试结果（内存） | `MainActivity.results` Map | 每次启动清零 | - |

**结论：只要签名一致就能覆盖安装、数据不丢。** 卸载才丢。签名见 §5。

## 4. 构建与交付（钦定流程）

1. 本地改码 → **必须先本地验证**（括号配平/逻辑等价 Python 测试/桥接-前端对应核对）→ 给主人看 → 确认后才 push（推送纪律）
2. push main → GitHub Actions（`.github/workflows/apk.yml`）自动 Gradle assembleRelease + apksigner 签名 + 发 Release（tag build-N）
3. `gh release download build-N` 下来校验 SHA-256 / unzip -tq / apksigner verify / aapt 版本号
4. 交付手机：**首选 `request_file_export`**（弹系统保存框；超时 1 次换浏览器直链 `github.com/mafucai/api-relay-tester/releases/download/build-N/relayscope-release.apk`）；**禁止 cp 到 /sdcard/Download**（被系统清理）
5. 门禁：编译前必须过相关 Skills 检查（webapp-testing/dom-static-check/debugger/visual-verdict），全绿才能推

## 5. 签名（覆盖安装的命根）

- **正式 keystore 已固定**：`relayscope-release.jks`（本仓库根目录，已 gitignore 不入库）
- **alias**: `relayscope`
- **storepass / keypass**: `relayscope-2026`
- **证书 SHA-256**: `CC:62:BD:5C:E0:C3:A9:B9:B9:42:BF:22:25:C1:2D:2C:7F:5D:E1:05:5B:6B:2B:4D:B8:54:9B:A0:26:4F:CA:2C`
- CI 从 GitHub Secrets `KEYSTORE_BASE64` 还原；Secrets 同名值即此 keystore 的 base64
- build-12 及以前都是 CI 临时签名（每次不同）→ 从 build-13 起全部固定签名
- **一次性代价**：build-13 装不进已装的 build-12，最后卸载一次；此后永不卸载
- keystore 丢了 = 以后所有版本无法覆盖安装。备份在 `/workspace/api-relay-tester/relayscope-release.jks`，主人自行再备份一份

## 6. 版本历史要点（踩过的坑）

| 版本 | 干了什么 / 坑 |
|---|---|
| build-4 | 旧原生手绘界面 + CI 临时签名（弃） |
| build-7 | WebView 化 + 删演示数据；站点加了不显示（桥接只回数量）、可重复添加 |
| build-8 | ea0494d：WebView 状态同步 + addIfAbsent 去重 |
| build-9 | v0.3.0：友好的"响应不是 JSON"提示；补 POST_NOTIFICATIONS；图标去 R |
| build-10 | v0.3.1：One API 式 looksLikeHtml Content-Type 防护（借鉴 songquanpeng/one-api） |
| build-11 | v0.4.0：浏览器 UA 防 WAF 拦 HTML；URL 不猜测拼接；删除站点；403 透传站点 message；图标居中重裁 |
| build-12 | v0.4.1：4 路模型并发（17 模型 ~80s→~15s）；fetchPrices 真实现；编辑站点；单站点重测按钮 |
| build-13 | v0.4.2：**固定签名**（本节）+ 排行榜真排序 + 生图模型 🎨 标注 + 模型名一键复制（ClipboardManager 桥接） |

其他坑：`/v1` 自动拼接已废除（One API 哲学：用户填什么用什么）；aimover.cc 无公开价格 JSON 接口（价格页 HTML 渲染），价格靠 OCR/手动；图标字母残留用「以青绿图案为中心重新裁剪」解决而非修补。

## 7. 本地开发环境（这台手机）

- ProotLinux (Debian)：`/workspace/api-relay-tester` 源码；**没有 Android SDK，不能本地编译**，只能云端
- 工具：gh CLI（已登录 mafucai）、aapt、apksigner、python3+PIL（图标处理）
- 验证套路：无 javac，用 Python 模拟 Java 逻辑做等价测试 + 括号配平 + grep 核对桥接对应
- 备份纪律：改哪个文件先 `cp f f.bak-<版本>`
- 图标素材：`/tmp/new-icon-src.jpg`（原图 680×260；青绿图案中心 x60-178/y49-167）；mipmap-*/ic_launcher_foreground.png 是产物

## 8. 下一个 AI 的检查单

1. 读本文 + RELAYSCOPE-APP-SPEC.md（视觉细节以它为准）
2. `git -C /workspace/api-relay-tester log --oneline -5` 看最新状态
3. 改码前备份；改完跑 §4 第 1 步的本地验证
4. 版本号必升（versionCode+1），推前问主人
5. 交付报告必含：SHA-256、versionName、是否需卸旧版（现在固定签名后=不需要）
6. 别碰 `.bak*` 文件；别把 keystore/密钥提交进仓库
