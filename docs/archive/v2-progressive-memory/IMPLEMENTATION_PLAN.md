# v2 实现计划（确认文档后再写代码）

本文件只排顺序和落点。每步仍须：备份 → 改 → 跑测试。旧文档与旧 `.lpm` 不删。

## 顺序

```
A 会话注入预算 + state 同步     ← 先做，立刻止住灌长文
B 抽取停用词（会话侧）
C context CLI 去重输出
D 技能索引构建脚本
E 代码符号查找脚本
F 钩子适配器（文件补齐）
N 小说索引增量（停用词 / 摘要加权 / novel-lookup）
G 测试与本目录「已实现」对照
```

小说构建、`src/splitter.js`、已有 `.lpm`：**不改职责**。N 只做增量，见 `NOVEL_INDEX_INCREMENT.md`。若抽代码符号，从 `minimal_build.js` **复制**正则到新文件，避免把小说入口改脆。

## A. 会话注入预算 + state

**改：**

- `/workspace/novel-kg-compressor/scripts/memory-adapter.js`
- `/workspace/novel-kg-compressor/scripts/dialogue-lifecycle.js`
- `/workspace/novel-kg-compressor/test/memory-adapter.test.js`
- `/workspace/novel-kg-compressor/test/dialogue-lifecycle-cli.test.js`

**做：** `MAX_INJECT_CHARS`、单条截断、短查询回退、`finishTurn` 写 `state.json`。  
**验：** 见 `SESSION_ADAPTER.md` §9。  
**备份：** `cp` 上述 js 为 `*.bak.v2-session`。

## B. 抽取停用词

**改：** `memory-adapter.js` 的 `extractStructuredState`。  
**验：** 输入含 `258 tokens` 时 entities 不含纯数字。

## C. context 输出

**改：** `dialogue-lifecycle.js` 的 `buildContext`。  
**默认 stdout：** `{dialogue_id, turn_number, inject, inject_chars, sources}`。  
**`--verbose`：** 才带 recall 数组。

## D. 技能索引

**新文件：**

- `/workspace/novel-kg-compressor/scripts/build-skill-index.js`
- `/workspace/novel-kg-compressor/test/build-skill-index.test.js`

**写出：** `/workspace/渐进式记忆/libraries/skills-library/skill-index.json` 与 `cards/`。  
**不删：** `library-source.txt`、`library-source.lpm/`。

## E. 代码符号

**新文件：**

- `/workspace/novel-kg-compressor/scripts/code-symbols.js`
- `/workspace/novel-kg-compressor/scripts/code-lookup.js`
- `/workspace/novel-kg-compressor/test/code-lookup.test.js`

**默认索引根：** 本仓库 `scripts/` + `src/`。

## F. 钩子

**新文件：**

- `/workspace/novel-kg-compressor/scripts/coomi-hook-adapter.sh`
- `/workspace/novel-kg-compressor/scripts/coomi-hook-adapter.js`
- `/workspace/novel-kg-compressor/test/coomi-hook-adapter.test.js`

非法 JSON 退出码 0。不在本步改 App 配置（你来配）。

## N. 小说索引增量

**改：** `/workspace/novel-kg-compressor/minimal_build.js`（备份 `*.bak.v2-novel`）  
**新：** `scripts/novel-lookup.js`、`test/novel-lookup.test.js`、可选 `scripts/stopwords.js`  
**做：** blacklist 接入实体抽取；摘要句含实体加权；序言段；lookup 恰好一块。  
**验：** 见 `NOVEL_INDEX_INCREMENT.md` §7。旧 `npm test` 与 `node test/test.js` 必须过。  
**不：** `--force` 重建用户百万字库；不改章节正则。

可与 D/E 并行（碰的文件不同）。不要和 A 抢同一轮：A 改 adapter，N 改 `minimal_build.js`。

## G. 对照

在 `docs/v2/README.md` 追加「实现状态」表。更新 `PROJECT_SYMBOLS.md`（治理要求）。旧 `ACCEPTANCE.md` 不删，v2 验收写在本目录。

## 明确不做（本轮代码）

- 不改 `memory/README.md` 里小说 85–90% 段落（避免改旧口径；v2 自己声明边界）。
- 不改铁律 / `mandatory-prerequisites`（属技能加载策略，可另开文档；本次用户指定代码+会话）。
- 不删 40 个 `.bak`。
- 不接向量库。

## 实现状态

A–G 与小说增量 N 已落地。本轮追加：技能触发词场景表 + `skill-lookup` 接到 `context.skill_cards`。

未接线：App 钩子配置、技能加载铁律/引擎预注入、Coomi 读账单。
