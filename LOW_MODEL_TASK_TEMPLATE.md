# 给低级模型的任务模板

把下面模板复制给低级模型，替换任务目标即可。

## 标准任务

```text
你现在维护 api-relay-tester（RelayScope）项目，项目路径：
/workspace/api-relay-tester

任务目标：
【在这里写唯一目标】

开工前必须读取：
1. /workspace/api-relay-tester/docs/ENGINEERING.md
2. /workspace/api-relay-tester/docs/RELAYSCOPE-APP-SPEC.md
3. /workspace/api-relay-tester/PROJECT_RULES.md
4. /workspace/api-relay-tester/RISK_CHECKLIST.md
5. /workspace/api-relay-tester/ACCEPTANCE.md

执行约束：
- 只做任务目标要求的改动。
- 修改前备份目标文件（cp f f.bak-<时间戳>，备份留在同目录）。
- 不重构无关代码。
- 不删除旧文件。
- 不修改 node_modules、cache、logs 等生成物。
- 不改系统级文件；涉及密钥/keystore 不进仓库。
- 涉及 RelayTester（测速/超时/并发/取消）、MainActivity（桥接）、progress.js（进度）、index.html（DOM）、build.gradle（版本）时，先列风险再动手。

本项目硬规则：
- 桥接三方契约：前端 AndroidRelay.xxx ↔ Java @JavascriptInterface ↔ bridge-sim.js 必须一一对应。
- 超时/并发上限：单模型连接 ≤5s / 读取 ≤15s / 总 20s；逐模型不重试；整批 10 分钟硬上限；并发 8。
- 结果按完成顺序回传（ExecutorCompletionService），禁止按提交顺序阻塞。
- 外部数据插入 DOM 前必须 esc() 或 textContent；事件绑定每元素只能在一个模块注册。
- 进度重绘节流；业务 JS 崩溃时调试面板（__dp_trigger/__dp_panel）仍可显示错误。
- 功能变更必须升 versionCode+1 / versionName；推 GitHub 前必须主人确认。

完成后必须运行：
cd /workspace/api-relay-tester
bash tools/preflight.sh
node tools/verify-0.6.3.js
node tools/verify-0.6.3-runtime.js

最终报告必须包含：
1. 改动文件绝对路径
2. 备份文件绝对路径
3. 风险检查结果
4. 验证命令和结果
5. 未解决问题
```

## 小任务示例

```text
任务目标：
只把 RelayTester 的模型级并发从 8 改为可配置（SharedPreferences）。
禁止修改前端进度逻辑。
禁止修改 MainActivity 桥接签名。
必须证明 232 模型站点仍在 10 分钟整批上限内收敛。
```

```text
任务目标：
只修复进度重绘卡顿：把 renderMatrix 全量重绘改为按增量插入。
禁止修改 Java 侧。
禁止修改进度计数逻辑。
必须证明 500ms 节流下 232 个模型连续回调不触发整页重绘风暴。
```