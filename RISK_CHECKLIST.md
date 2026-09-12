# api-relay-tester 风险清单

每次修改前后逐项检查，并在最终报告中说明结果。

## 网络 / 超时 / 并发
- [ ] 单模型连接超时是否 ≤5s、读取 ≤15s、总 ≤20s？
- [ ] 逐模型是否**不重试**（重试只保留给巡检 health()/单模型入口）？
- [ ] 整批是否有 10 分钟硬上限？到时未完成项是否标记「超时」并取消？
- [ ] 模型级并发是否固定 8，没有无限并发？
- [ ] 结果是否按**完成顺序**回传，而不是按提交顺序阻塞？
- [ ] 停止按钮是否仍能掐断在途 HTTP 连接 + 置取消标记？
- [ ] `activeConnections` 是否在 finally 中移除，避免连接泄漏？

## 桥接三方契约
- [ ] 前端 `AndroidRelay.xxx(` 是否都有 Java `@JavascriptInterface`？
- [ ] bridge-sim.js 是否同步了所有调用方法？
- [ ] 新增回调是否走 `onNative*` 通道且两端（Java + 前端）都注册？
- [ ] 新增方法时三方清点是否更新？

## 前端 / DOM / XSS
- [ ] 外部数据插入 DOM 前是否 `esc()` 或 `textContent`？
- [ ] 事件绑定是否每个元素只在一个模块注册（无 addEventListener 叠加）？
- [ ] 进度重绘是否节流（不因 200+ 模型全量重渲染卡死）？
- [ ] 业务 JS 崩溃时，独立调试面板（`__dp_trigger`/`__dp_panel`）是否仍可显示错误？
- [ ] 是否避免依赖不可用的外部 CDN（零第三方依赖）？

## 数据 / 版本 / 交付
- [ ] 修改前是否备份目标文件？
- [ ] 是否只修改必要文件？
- [ ] 是否运行 `tools/preflight.sh`？
- [ ] 功能变更是否升 versionCode/versionName？
- [ ] 推 GitHub 前是否主人确认过？
- [ ] keystore/API Key/密钥是否没有进仓库、没有进日志？
- [ ] 交付报告是否含 Release 直链、SHA-256、versionName？

## 变更与验证
- [ ] 是否根据改动范围跑 `tools/verify-0.6.3.js` / `tools/verify-0.6.3-runtime.js`？
- [ ] JS 模块是否 `node --check` 全过？
- [ ] Java 括号是否配平（状态机精确统计）？