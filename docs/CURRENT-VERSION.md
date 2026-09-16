# RelayScope 最终采用版本（权威记录）

> 记录时间：2026-09-16
> 用途：明确「当前在用哪个版本」，避免再交付错包、再走弯路。
> 权威性：以本文件为准；历史版本仅作回溯。

---

## 一、当前采用版本

| 项 | 值 |
|---|---|
| **版本名** | **v0.6.7** |
| **versionCode** | **25** |
| **Release tag** | **build-45** |
| **APK 文件名** | `RelayScope-0.6.7-build45.apk` |
| **大小** | 46,326,245 bytes（46.3 MB） |
| **SHA-256** | `a979d48ad9d1c1a7eea2848eb2a9611e65070f03429db01ca274be4cc87d7ee2` |
| **直链** | https://github.com/mafucai/api-relay-tester/releases/download/build-45/relayscope-release.apk |
| **仓库** | `mafucai/api-relay-tester` |
| **签名** | 固定签名（可覆盖安装，数据不丢） |
| **编译** | GitHub Actions（`.github/workflows/apk.yml`） |

**已解包验证**（交付前必做，见教训 20）：
```
bridge.js asModelArray:        2  ✅
mode.js asModelArray:          1  ✅
index.html window.onerror:     1  ✅
mode.js 看门狗:                1  ✅
RelayTester fetchPerfMetrics:  3  ✅
```

---

## 二、这个版本包含的功能

### 1. 限流解决方案（核心）
- **主路径**：`GET /api/perf-metrics/summary` —— 站点自带性能统计，**232 次模型调用 → 1 次请求**，零额度消耗、零限流
  - 前端入口：「⚡ 拉取站点统计」按钮
  - 拿到 `success_rate` / `latency` / `tps` / `requests` 等真实流量数据
- **兜底探活**（四道防线）：
  - 并发 **8 → 2**（`MODEL_WORKERS = 2`）
  - 读 `Retry-After` 头，按站点要求等待
  - 请求间 **100–300ms 抖动**（`jitterPause()`）
  - **遇 429 立即停批**，剩余模型标「已跳过（限流）」

### 2. 全站测试按钮修复
- **根因**：`MainActivity.pushState()` 用 `item.put("models", modelsJson(...))`，`modelsJson()` 返回 **String**，JSON 里 `models` 是 `'["gpt-4o",...]'` **字符串**；而 `mode.js` 的 `(r.models||[]).forEach(...)` 按**数组**用 → 抛 `is not a function` → `openModeModal()` 中断 → 面板打不开
- **修复**：`bridge.js` 入口加 `asModelArray()` 统一归一化（数组原样 / JSON 字符串 parse / 裸字符串包单元素），`onNativeState` 与 `onNativeSiteResult` 两处都过一遍

### 3. `int[]` 计数竞态修复
- `testAll` / `testGroup` / `singleResult` 三处 `--remaining[0]` 非原子 → 多站点并发丢更新 → `onNativeTestDone` 永不触发 → 按钮永久卡「正在测试」
- **修复**：三处统一 `AtomicInteger.decrementAndGet()`；`results` / `balances` / `failStreak` 三个多线程 Map 改 `ConcurrentHashMap`

### 4. 调试面板错误可见
- **问题**：`file://` 下 `js/*.js` 是 opaque origin，浏览器屏蔽错误细节，只显示 `Script error. @ :`
- **修复**：调试面板改用 `window.onerror` 五参数版（可拿 stack），并对 `Script error.` 给出可操作提示

### 5. 其他修复
- `id="retryTimeout"` 重复 → 删除 modeModal 里的死按钮
- 卡片模板多余 `</div>` → `.st-body` 被挤出 `article.station`
- `.copy` 用 `.name.textContent` 取站点名（含分组名）→ 永远找不到站点；且被 `mode.js` 重复绑定覆盖 → 改 `data-copy`
- 组内排序表达式 `okModels(a.name&&ra?...)` 写错 → 排序恒为 0
- `modeSave` 乐观置 `running` → Java 提前 return 时按钮卡死；改为只由 `onNativeTestStart` 置位 + 12 分钟看门狗

---

## 三、明确不做的事（本次决定）

| 项 | 决定 | 原因 |
|---|---|---|
| **额度读取（方案 A）** | ❌ **不做** | 已评估：`/api/usage/token/` 多数站点返回 `unlimited_quota=true`（token 无限额度，按用户余额计费），**拿不到真实余额数字**；只有导入用户会话（accessToken + userId）才能读 `/api/user/self`。性价比不足，**不采纳** |
| **Antigravity Tools 全套吸收** | ❌ 不做 | 一键预热违反「不调用模型」；API 反代聚合违反「不引入独立服务」且与 NewAPI 重叠；导出账号违反「凭据不序列化」 |
| **服务端 `jzcangshu/RelayScope` 改动** | ⏸ 暂停 | 当前账号 `mafucai` 无该仓库推送权限（403） |

---

## 四、历史版本（仅回溯）

| 版本 | Release | 状态 | 说明 |
|---|---|---|---|
| v0.6.7 | build-45 | ✅ **当前采用** | 最终版 |
| v0.6.6 | build-44 | 已被取代 | 修 models 类型不一致 |
| v0.6.5 | build-43 | ⚠️ **勿用** | **不含 models 修复**，全站测试面板仍打不开 |
| v0.6.4 | — | 已被取代 | 修按钮卡死（int[] 竞态） |
| v0.6.3 | build-39 | 已被取代 | 逐模型实时进度 |

> ⚠️ **build-43（v0.6.5）曾因未解包验证被误交付**，用户装上后问题依旧。此后所有交付必须先解包验证（教训 20）。

---

## 五、交付检查清单（每次必做）

1. [ ] `bash tools/preflight.sh` → **FAIL=0 WARN=0**
2. [ ] `node tools/verify-0.6.3.js` + `verify-0.6.3-runtime.js` 全过
3. [ ] 真实浏览器验证（**用被测方真实数据格式，禁止自编**）
4. [ ] CI 编译成功（`gh run view <id>`）
5. [ ] **解包验证**：`unzip -p ... assets/js/*.js` + `grep` 确认修复真在包里
6. [ ] 记录大小 + SHA-256
7. [ ] 交付：`request_file_export`（超时 1 次即换 Release 直链）

---

## 六、踩坑速查（给下一个 AI）

| 现象 | 根因 | 解法 |
|---|---|---|
| 调试面板只显示 `Script error.` | `file://` 下外部 JS 是 opaque origin，错误被屏蔽 | **把外部 JS 内联成 `<script>` 再跑浏览器**，错误即可见 |
| 「点了没反应」 | 先怀疑 JS 层 | 浏览器 bridge-sim 复现；通了再查 Java |
| 全站测试按钮卡「正在测试」 | `int[]` 计数竞态 或 前端乐观 running | `AtomicInteger` + 只由回调置位 |
| 面板打不开 | `models` 字符串/数组类型不一致 | 入口 `asModelArray()` 归一化 |
| CI 挂在 Set up Android SDK | Google 下架 `tools` 包 | workflow 显式 `packages: 'platform-tools'` |
| workflow 推不上 | OAuth token 缺 `workflow` scope | **改用 SSH 推送** `git@github.com:owner/repo.git` |
