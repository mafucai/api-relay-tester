# 会话适配架构（v2）

> 管对话史：能存、能找、注入短。  
> 不管 Coomi 系统提示、工具表、引擎预注入。那一层记为宿主上限。

## 1. 现状（已实现 / 未接线）

`scripts/dialogue-lifecycle.js` + `memory-adapter.js`：

| 能力 | 状态 |
|---|---|
| 追加 `raw_turn` | 已实现 |
| 规则抽取候选状态 | 已实现；停用词过滤纯数字 |
| `recall` / 注入 | 已实现硬预算，单条截断 |
| `context` CLI | 已实现：默认只回 `inject`；另回 `skill_cards` |
| `state.json` | 已实现，与 `raw_turn` 条数同步 |
| `summaryEvery` | 已实现，默认 10 |
| 钩子自动 `finishTurn` | 脚本已配上且会触发；**事件无原文，不能当记忆源**。原文仍由代理 `append` |

本会话实测：召回曾把上轮 1000+ 字分析整篇灌回，是加法不是节流。

## 2. 双层日志

```
磁盘（只追加，永不改 raw_turn）
  conversation_log.jsonl
    raw_turn
    structured_state_candidate
    semantic_summary          # 周期写
    inject_clip               # v2 新增：本轮实际注入的截断文本（审计用）

注入（每轮给模型）
  ≤800 tokens 的单一字符串
  不含完整 raw_turn 数组
  不含重复 JSON
```

磁盘原文仍是事实依据。注入层可以丢细节；需要细节时再按 `entry_id` 点读一条。

## 3. 注入硬预算

常量（可配，默认如下）：

```text
MAX_INJECT_CHARS     = 1600     # 约 800 tokens（中英混合保守）
MAX_RECALL_ENTRIES   = 3
MAX_ENTRY_CHARS      = 400      # 单条截断
RECENT_FALLBACK_N    = 2        # 短查询走最近 N 轮
SHORT_QUERY_CHARS    = 24
```

算法：

1. 查询字符数 ≤ `SHORT_QUERY_CHARS`，或分词后无有效词：取最近 `RECENT_FALLBACK_N` 条 `raw_turn`，每条只留 `user` 全文 + `assistant` 首 200 字。
2. 否则：对 `raw_turn` / `semantic_summary` 打分（保留现有中文滑窗，但加停用词）。
3. 每条先截成相关句：含命中词的句子 ± 一句，上限 `MAX_ENTRY_CHARS`。
4. 拼成**一份** Markdown，不是 JSON 数组再加一份副本。
5. 总长超过 `MAX_INJECT_CHARS` 从尾部丢弃整条。
6. 追加一条 `inject_clip`（kind 新，不影响旧读取器：未知 kind 应忽略）。

`context` CLI 输出改为：

```json
{
  "dialogue_id": "dlg-progressive-main",
  "turn_number": 12,
  "inject": "……≤1600 chars……",
  "inject_chars": 1420,
  "sources": ["entry_id", "entry_id"]
}
```

不再把完整 `recall` 数组默认打到 stdout。需要调试时加 `--verbose`。

## 4. state.json 同步

`finishTurn` 在追加日志后，**同进程**写对话目录下 `state.json`：

```json
{
  "dialogue_id": "dlg-progressive-main",
  "turn_number": 12,
  "status": "active",
  "updated_at": "ISO-8601",
  "last_summary_turn": 10
}
```

规则：

- `turn_number` = 日志里 `raw_turn` 条数。
- 写盘用 tmp + rename，避免半截 JSON。
- `session.json` 仍只在创建对话时写一次（mode / library_ids）。
- CLI 的 `append` 必须走这条路径；禁止只写 jsonl 不写 state。

`memory-gate` 已允许读 `state.json`。若要由 adapter 写 state，gate 需增加固定操作：

```text
write-dialogue-state <dialogue_id>
```

只允许写该对话目录的 `state.json`，仍禁止任意路径、禁止写 jsonl 以外的覆盖。jsonl 继续只能 append。

未改 gate 之前，CLI 可在**已授权的绝对日志路径旁**写 `state.json`（与现在直接写 jsonl 同一信任边界）。优先走 gate。

## 5. 抽取（仍是候选，不是权威）

停用词（中英）+ 丢掉纯数字 + 长度 2–12 的词。

保留：

- `tasks`：含 待办/还要/必须/需要/答应/承诺/计划/目标 的整句，最多 5 条。
- `times`：第 N 轮/章，今天/昨天/明天。

`entities` 最多 15 个。`relationships` 仍可空；不假装 NER。

## 6. 摘要周期

保持 `summaryEvery` 默认 10。v2 另加：

- 单条 `assistant` > 1200 字时，**本轮**就生成一条本地截断摘要写入 `inject` 候选（不是 LLM 语义摘要）。
- LLM `semantic_summary` 仍只在周期点、由主模型或用户提供后 `save-summary`。

这样 10 轮之内也不会把长文当注入源。

## 7. 钩子（实测结论：不能当记忆源）

脚本：

```text
scripts/coomi-hook-adapter.sh
scripts/coomi-hook-adapter.js
```

App 配置（已配，路径正确，不要再换）：

```text
事件：每轮结束
命令：/data/data/com.coomi.android/files/usr/bin/bash
参数：--noprofile /data/data/com.coomi.android/files/home/novel-kg-compressor/scripts/coomi-hook-adapter.sh
超时：10
```

与 `memory-guard.sh`（工具调用前守门）不是同一条钩子，都留着。

### 已测事实

| 项 | 结果 |
|---|---|
| 路径填错 | 否 |
| 钩子有没有被调到 | 有。`/workspace/hooks/memory-hook.log` 出现 `invoke node=.../.node-install/bin/node` |
| stdin | 后来改为 bash 先 `cat` 再把 JSON 当参数传给 node。日志有 `stdin-bytes=81` |
| 81 字节对得上什么 | 评分钩子 08-19～08-28 的真实「每轮结束」样张：`{"error":null,"session_id":"...","success":true}`（约 80 字节） |
| 事件里有没有 user/assistant | **没有**。字段只有 `error` / `session_id` / `success` |
| JS 有没有写出 skip/ok | 尚未。日志停在 `invoke node` 之后，推测加载 `dialogue-lifecycle` 过重或超时 |
| 对话有没有被钩子写入 jsonl | **没有**。`raw_turn` 都是代理 `append` |

### 结论

1. **主因是宿主**：每轮结束 JSON 不含对话原文。改 adapter 解析字段名解决不了。
2. **次因是脚本**：早期入口没加载 env.sh；stdin 可能被 App 一直开着。这两项已按 `score.sh` 修过，仍不能变出原文。
3. **记忆生产不走钩子**。唯一已验证闭环：代理开轮 `context`、收轮 `append`、周期 `save-summary`。
4. 不要再为「自动记对话」改钩子。不要再写第二份 `raw_conversations.log`。不要为钩子改 Coomi 源码/APK。
5. 可选诊断（未做）：JS 先解析 JSON 写 `missing-text`，确认有原文才 `require` 重模块。只为把 81 字节记进日志，不能让钩子开始存对话。

旧 `COOMI_INTEGRATION.md` 不删；其中「待实测字段」以本节为准，不再当未测。

## 8. 每轮代理工作流（v2）

固定变量不变：

```text
LOG=/workspace/渐进式记忆/dialogues/dlg-progressive-main/conversation_log.jsonl
DIALOGUE=dlg-progressive-main
CLI=/workspace/novel-kg-compressor/scripts/dialogue-lifecycle.js
```

1. 开轮：`node "$CLI" context "$LOG" "$DIALOGUE" "<用户原文>"`  
   只把返回的 `inject` 当记忆，不把 stdout 其它字段复述进回复。
2. 纯问答：不读 INDEX、不 `read_skill`。
3. 收轮：先定最终回复草稿，再 `append` 用户原文 + 该草稿，然后原样发出。
4. `summary_due` 时生成摘要并 `save-summary`。

## 9. 验收测试（必须新增，旧测试保留）

旧 `test/memory-adapter.test.js` 继续通过。新增：

| 测试 | 期望 |
|---|---|
| 长助手回复 2000 字后 context | `inject_chars` ≤ 1600，且不含全文 |
| 查询「检查」 | 返回最近轮，不空，不含更早长文全文 |
| 连续 append 3 次 | `state.json.turn_number === 3` |
| context stdout | 无完整 `recall` 数组（除非 `--verbose`） |
| 抽取「258 tokens」 | `entities` 不含 `258` |
| 钩子喂非法 JSON | 退出码 0 |

## 10. 非目标

- 不把会话适配做成小说检索。
- 不在适配器里读 `SKILL.md` 全文。
- 不声称会话账单下降 85%。
- 不覆盖、不重写历史 `raw_turn`。
- 不把每轮结束钩子当对话原文来源。
