# api-relay-tester 验收标准

## 每次改动必跑

```bash
cd /workspace/api-relay-tester
bash tools/preflight.sh
node tools/verify-0.6.3.js
node tools/verify-0.6.3-runtime.js
```

## 通用验收
- `tools/preflight.sh` 输出所有检查项：通过项 `PASS`，遗留风险 `WARN`，阻断项 `FAIL`；**必须 0 FAIL**。
- `tools/verify-0.6.3.js` 契约验证全过（桥接三方对齐、DOM id、加载顺序、onNativeModelResult 单点）。
- `tools/verify-0.6.3-runtime.js` 运行时验证全过（进度累计、超时计数、重测超时流程）。
- 所有 JS 模块 `node --check` 通过。
- Java 源文件括号配平（状态机精确统计，不含字符串字面量）。

## 按改动类型追加验收

### 测速 / 超时 / 并发（改 RelayTester.java 必跑）
- 8 路并发；单模型连接 ≤5s / 读取 ≤15s；逐模型不重试。
- 整批 10 分钟硬上限生效：超时项标记「超时」，不无限等待。
- 结果按完成顺序回传，不按提交顺序阻塞。

### 桥接层（改 MainActivity.java 必跑）
- 前端调用均有 Java 注解方法；bridge-sim 同步。
- 新增回调走 `onNative*` 通道，两端注册。

### 前端 / DOM（改 assets 必跑）
- 外部数据插入 DOM 前已转义或 `textContent`。
- 每个元素事件绑定只在一个模块注册。
- 进度重绘节流（200+ 模型不卡 UI）。
- 业务 JS 崩溃时，独立调试面板仍可显示错误。

### 版本 / 交付
- 功能变更 versionCode+1、versionName 更新。
- 交付报告含：改动文件绝对路径、备份路径、风险检查、验证命令与结果、未解决问题。
- 推 GitHub 前主人确认；Release 交付含直链 + SHA-256。

## 最终报告模板

```text
改动文件：
- /abs/path/file

风险检查：
- 网络超时/并发：通过/未涉及/遗留风险
- 桥接契约：通过/未涉及/遗留风险
- 前端 XSS/事件重复：通过/未涉及/遗留风险
- 版本/交付：通过/未涉及/遗留风险

验证命令：
- bash tools/preflight.sh: PASS/WARN/FAIL
- node tools/verify-0.6.3.js: PASS/FAIL
- node tools/verify-0.6.3-runtime.js: PASS/FAIL

未解决问题：
- ...
```