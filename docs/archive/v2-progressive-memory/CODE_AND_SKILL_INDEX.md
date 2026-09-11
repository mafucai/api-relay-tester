# 代码与技能索引架构（v2）

> 小说 `.lpm` 切块器不用于技能全文，也不作为代码导航的主索引。

## 1. 问题（已观测）

当前把资料糊成 `library-source.txt` 再跑小说构建：

| 库 | 源体积 | 实际索引 | 问题 |
|---|---|---|---|
| `core-library` | 83KB | 1 块，实体「文档/文件」 | 单位错误，整库一块 |
| `skills-library` | 631KB | 12 块标题碎片 | 不能替代 `read_skill` |

`minimal_build.js` 里已有按扩展名抽 `function/class` 的半套逻辑，但没有接到「按符号加载片段」的会话路径。

## 2. 目标形状

```
query
  ├─ skill name / 场景词  →  skill-card (≤2KB)  →  必要时 SKILL.md 一节
  └─ symbol / file path   →  code-hit (file:line + snippet ≤120 行)
磁盘仍保留原件。索引只存指针。
```

## 3. 技能索引

### 3.1 单位

一条技能 = 一个卡片文件，不是「16 段拼一块」。

```text
libraries/skills-library/
  source/<name>/SKILL.md          # 原件副本，只读
  cards/<name>.md                 # v2 生成，20–40 行
  skill-index.json                # 名 → 触发 → 卡片路径
```

旧的 `library-source.txt` 与 `library-source.lpm/` **保留不删**，v2 加载路径不再读它们。

### 3.2 `skill-index.json`

```json
{
  "version": 2,
  "skills": [
    {
      "name": "debugger",
      "triggers": ["bug", "报错", "stack", "traceback"],
      "card": "cards/debugger.md",
      "full": "source/debugger/SKILL.md",
      "when": "出现可复现错误且准备改代码时",
      "not_when": ["纯问答", "只解释概念"]
    }
  ]
}
```

约束：

- `name` 与目录名一致。
- `triggers` 最多 12 个，禁止单字（如单独一个 `token`）。
- `card` 目标 ≤2KB；超过则卡片只留「何时用 / 禁做什么 / 全文路径」。
- 构建时从 `SKILL.md` 的 YAML `description` + 首个二级标题生成卡片，不手工维护 89 份（P0 可手修）。

### 3.3 加载策略（给会话适配用）

| 场景 | 读什么 |
|---|---|
| 纯问答 / 状态 / 解释 | 不读 INDEX，不读卡片，不读全文 |
| 要动工具 / 改文件 / 跑命令 | 命中 1–2 张卡片 |
| 卡片声明必须执行该技能流程 | 再读 `full`，优先按标题一节，禁止默认全文 |

同一会话已读过的卡片，场景未变则不重读全文。这条是**代理行为规则**，索引只提供数据。

### 3.4 构建命令（已实现）

```text
node scripts/build-skill-index.js \
  --source /workspace/渐进式记忆/libraries/skills-library/source \
  --out    /workspace/渐进式记忆/libraries/skills-library
```

- 只读 `source/**/SKILL.md`
- 写 `cards/` 与 `skill-index.json`（原子替换：写 `*.tmp` 再 rename）
- 不修改 `source/`
- `--dry-run` 只打印将生成的 name 列表

查询（已实现，接到 `context`）：

```text
node scripts/skill-lookup.js --q debugger
node scripts/dialogue-lifecycle.js context <log> <dialogue> "调试这段报错"
```

纯问答不返回卡片。要动工具/改文件/跑命令才返回最多 2 条 `{name, card, when}`，不含 SKILL.md 全文。

## 4. 代码索引

### 4.1 单位

```text
symbol_id:  <relpath>::<kind>::<name>
kind:       function | class | method | type | file
```

一个命中默认返回：

- `path`（仓库相对路径）
- `line_start` / `line_end`
- `signature` 一行
- `snippet`：签名所在行 ± 上下文，合计 ≤120 行，且 ≤64KB

禁止默认把整个源文件读进上下文。`>64KB` 的文件必须走符号命中或行区间。

### 4.2 `code-index.json`

```json
{
  "version": 2,
  "root": "novel-kg-compressor",
  "files": [
    {
      "path": "scripts/memory-adapter.js",
      "bytes": 7966,
      "symbols": [
        {
          "name": "extractStructuredState",
          "kind": "function",
          "line": 27,
          "end": 46
        }
      ]
    }
  ]
}
```

索引本身目标：中型仓库 ≤200KB。超出则按目录拆 `code-index.<dir>.json`，根索引只留目录指针。

### 4.3 查询

```text
node scripts/code-lookup.js --index <code-index.json> --q extractStructuredState
```

输出最多 5 条，按精确名 > 前缀 > 子串。每条只含 snippet，不含全文件。

复用 `minimal_build.js` 里已有的扩展名正则，**抽到独立模块** `scripts/code-symbols.js`，不在小说构建里继续膨胀。小说构建文件保持小说职责。

### 4.4 范围

默认索引：

- `/workspace/novel-kg-compressor/scripts`
- `/workspace/novel-kg-compressor/src`
- 明确授权的其它项目根（配置里写绝对路径）

默认排除：`node_modules`、`*.bak*`、`.git`、构建产物。

## 5. 与 memory-gate 的关系

新增只读相对路径（仍在已批准的 `library_id` 下）：

```text
read-library <library_id> cards/<name>.md
read-library <library_id> skill-index.json
read-library <library_id> code-index.json
```

不新增任意绝对路径操作。不授权删除旧 `.lpm`。

## 6. 验收（写代码时必须有测试）

- 构建 95 个技能：`skill-index.json` 条数 = 源目录数；每张卡片 ≤2KB 或标记 `truncated`。
- 查询 `debugger` 只返回卡片路径，不返回 89 份全文。
- 查询 `extractStructuredState` 返回 `memory-adapter.js` 行号 + ≤120 行。
- 对 `taste-skill/SKILL.md`（>64KB）默认只出卡片，不出全文。
- 旧 `library-source.lpm` 仍在磁盘，构建不删除。

## 7. 非目标

- 不替换小说实体索引。
- 不做 LSP / 语义分析 / tree-sitter（可后续加，v2 用正则符号表）。
- 不把技能卡片写进系统提示；卡片按需读。
