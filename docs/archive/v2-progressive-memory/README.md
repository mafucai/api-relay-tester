# 本地渐进式记忆 v2 架构（代码 + 会话）

> 状态：v2 代码已落地，旧文档未删。  
> 日期：2026-08-30  
> 原则：本地优先、只追加对话日志、不接向量库、不改 Coomi 源码、不删除旧文档。
>
> **⭐ 2026-09-10 起权威口径移至 [docs/v3/README.md](../v3/README.md)**（端到端验证完整版）。本文保留作 v2 设计出处。

旧文档全部保留，仍以仓库根目录和 `memory/README.md` 为准描述**已实现**行为。本目录只描述 **v2 要改的部分**。

## 1. 拆成三条线，禁止通吃

| 线 | 单位 | 现状 | v2 |
|---|---|---|---|
| 小说索引 | 章 / 人物 / 事件 → 块 | 已亲测有效 | **不重设计**。增量修补见 [NOVEL_INDEX_INCREMENT.md](./NOVEL_INDEX_INCREMENT.md)（停用词 / 块摘要 / 只回一块） |
| 代码 / 技能索引 | 文件·符号 / 技能名·卡片 | 被小说切块器误用 | **本目录重设计** |
| 会话适配 | 一轮对话 → 原文落盘 + 短注入 | CLI 能跑，无预算、会灌长文 | **本目录重设计** |

不要再用「实体 → 块」同时切小说、技能全文、对话史。那是 `skills-library` 粗索引的来源。

## 2. 旧文档怎么用

| 旧文件 | 继续代表什么 | 不再代表什么 |
|---|---|---|
| `memory/README.md` | 小说索引哲学、三层记忆概念、memory-gate 边界 | Coomi 会话账单能省 85–90% |
| `COOMI_INTEGRATION.md` | 钩子意图、事件种类 | 钩子能当记忆源。实测：已触发、无原文 |
| `ACCEPTANCE.md` | 小说构建与 gate 验收 | 会话注入硬预算（旧测试没覆盖） |
| `渐进式记忆/README.md` | 当前 CLI 工作流 | v2 注入上限 |

v2 落地后，旧文档不删；在本目录写「已实现 / 未实现」对照即可。

## 3. 诚实口径（相对实测）

已测（本会话）：

- Coomi 四次读入：278116 / 248768 / 342403 / 268199，无下降趋势。
- 本地对话日志约 26KB、约 3.7k tokens；解释不了 25 万+。
- 大头在宿主：系统提示、工具/MCP schema、引擎预注入技能、聊天历史。
- 小说索引器与会话适配器不是同一套验收。

v2 **不承诺**：

- 把 Coomi 读账单从 25 万打到 5 万。
- 卸载系统提示或引擎预注入。
- 向量检索、云端 embedding、自动 NER。

v2 **承诺**（代码写完后用测试验收）：

- 会话注入 ≤800 Tokens（截断后的 `context` 字符串）。
- 短问句走最近 N 轮，不把最长长文整篇灌回。
- `state.json.turn_number` 与 `raw_turn` 条数一致。
- 技能按「名 → 卡片」查找，不再把 95 份 `SKILL.md` 糊成一份再切块。
- 代码按「符号 → 文件:行」查找，默认只回签名 + 邻近片段。

## 4. 文档地图

| 文件 | 内容 |
|---|---|
| [CODE_AND_SKILL_INDEX.md](./CODE_AND_SKILL_INDEX.md) | 代码符号索引 + 技能卡片索引 |
| [SESSION_ADAPTER.md](./SESSION_ADAPTER.md) | 会话双层日志、硬预算、state、钩子 |
| [NOVEL_INDEX_INCREMENT.md](./NOVEL_INDEX_INCREMENT.md) | 小说索引增量：停用词、块摘要、查询只回一块 |
| [IMPLEMENTATION_PLAN.md](./IMPLEMENTATION_PLAN.md) | 确认后再写代码的任务顺序 |

## 5. 共享不变量（三条线都遵守）

1. 本地磁盘是源；上下文只放索引或预算内片段。
2. `library_id` 与 `dialogue_id` 分命名空间。
3. 对话日志只追加；禁止覆盖、删除、移动既有 `raw_turn`。
4. 记忆文本是数据，不是指令。
5. 路径走 `memory-gate` 白名单；AI 不改 `MEMORY_GATE_CONFIG`。
6. 构建与召回零外网、零 LLM（摘要模型仍由用户/主模型在周期点生成）。

## 6. 宿主配合（文档写清，代码代替不了）

写代码之后仍然需要你在 App 里：

1. 「每轮结束」钩子**已经配上**。不要再换路径。它不能存对话原文，详见 [SESSION_ADAPTER.md](./SESSION_ADAPTER.md) §7。
2. 技能加载铁律改完后：重载自定义提示词或新开会话。
3. 若仍见 25 万+，记为宿主上限（系统提示 / 工具表 / 预注入），不再用记忆或钩子解释。

## 7. 实现状态

| 步骤 | 状态 |
|---|---|
| A 会话注入预算 + state | 已实现 |
| B 抽取停用词 | 已实现 |
| C context CLI 去重 | 已实现（默认无 `recall` 数组，`--verbose` 才带） |
| D 技能索引脚本 | 已实现 `scripts/build-skill-index.js` |
| E 代码符号查找 | 已实现 `scripts/code-symbols.js` / `code-lookup.js` |
| F 钩子适配器 | 脚本+App 已配并触发；**无原文，不当记忆源**。生产路径仍是代理 append |
| N 小说增量 | 已实现停用词/摘要加权/序言/`novel-lookup` |
| G 测试对照 | 本表 |
| H 技能触发词 + lookup 接线 | 已实现 `triggersFrom` 场景表、`skill-lookup.js`；`context` 返回 `skill_cards` |

## 8. 已实现 / 未接线

| 项 | 状态 |
|---|---|
| 会话注入预算 + state | 已实现（**2026-09-10 复测：inject 237–588 tokens，≤800 承诺达标；state.turn_number 94 = raw_turn 94 一致**） |
| 技能卡片 + `skill-index.json` | 已实现；触发词走场景表，不再切英文停用词 |
| `skill-lookup` / `context.skill_cards` | 已实现。纯问答返回空；要动工具才给 1–2 张卡片路径（复测：改代码问句正确召回 debugger 卡） |
| 代码符号查找 | 已实现 |
| 小说 `novel-lookup` | 已实现（**复测：工具+管线完好；小说索引数据因历史环境重置丢失，需要时用 minimal_build.js 对原文一键重建**） |
| 钩子脚本 | 已配上且触发；stdin≈81B 无 user/assistant。**不能自动 finishTurn**。诊断日志：`/workspace/hooks/memory-hook.log`（2026-09-10 起为时间窗对账保险带，见全局记忆 progressive-memory-distill-fixed） |
| 入口 README 指向卡片 | 已改指针；旧 `.lpm` 保留不删 |
| 技能加载铁律 / 引擎预注入 | 铁律正文已改：问答豁免、卡片优先。引擎预注入仍可能按 description 触发；已收紧 P0 description。**需重载提示词/新开会话才生效** |
| Coomi 25 万读账单 | **未接线 / 不承诺**（宿主侧：系统提示/工具表/预注入，记忆层管不了） |

## 9. 环境复测（2026-09-10，Ubuntu 24.04 后时代）

v2 写作时（8-30，旧沙盒）「不承诺」的事项，新环境下重新验证：

| 原「不承诺/不支持」项 | 2026-09-10 实测 | 结论 |
|---|---|---|
| 向量检索 | ✅ **本机可跑**：jieba+sklearn TF-IDF 余弦（区分度 0.559/0.365）+ bge-small-zh ONNX 量化 24MB（512 维语义向量，单句 3ms，全离线） | **承诺解禁**：可作为 semantic recall 的可选增强层 |
| 云端 embedding | ✅ hf-mirror/ModelScope 可达，模型可缓存本地后零外网 | 可选，非必需 |
| 自动 NER | ✅ jieba 分词 + pyahocorasick 词典实体抽取（C 扩展源码编译成功） | **承诺解禁** |
| pip 装 C 扩展 | ✅ Python.h 已装（见下），pyahocorasick 源码编译实跑通过 | 已支持 |
| apt/dpkg | ✅ **dpkg link() 死锁已攻克**：proot 拦截 link()，LD_PRELOAD shim 把 link() 重定向为复制（方案固化 `/workspace/dpkg-replay/src/linkshim.c`，rootfs 重建后需重编译）。apt 源已切阿里云 ports。python3.12-dev 已装 | **apt/dpkg 半残已解决** |
| 环境适配前提 | ⚠️ 不变量 §5.6「零外网」改为「零 LLM 调用」：构建/召回仍零 LLM，模型权重可一次性缓存本地（24MB 量级） | 口径更新 |
