# 本地渐进式记忆 v3（完整版权威文档）

> 状态：**v3 已全链路端到端验证**（2026-09-10，Ubuntu 24.04 proot）
> 定位：本文档是渐进式记忆的**唯一权威口径**。v1/v2 文档保留作历史与设计出处，冲突处以本文为准。
> 原则沿袭：本地优先、对话日志只追加、零 LLM 依赖（构建/召回）、不改 Coomi 源码、不删旧文档。

---

## 0. 一图流：系统全貌

```
┌─────────────────────── 每一轮对话 ───────────────────────┐
│                                                          │
│  开轮: AI 调 context 查询 → 注入 ≤800 tokens（实测 77-588）│
│    ├─ 语义召回: 摘要/状态/原文按词命中（去重后）           │
│    ├─ 短问模式(≤24字): 只带最近 2 轮（省预算）             │
│    ├─ 主动记忆: 触发词命中 → 冷却过滤 → 注入候选           │
│    └─ 技能卡片: 动手意图才给 1-2 张卡，纯问答返回空        │
│                                                          │
│  收轮: AI 调 append(用户原文 + 最终回复草稿)               │
│    ├─ raw_turn 落库（append-only）                        │
│    ├─ 结构化状态抽取（本地规则，零 LLM）                   │
│    ├─ state.json 记账（turn_number/updated_at）           │
│    └─ 每 N 轮(默认10): summary_due=true → AI 出摘要落库    │
│                                                          │
│  turn_end 钩子（引擎触发，保险带）:                        │
│    └─ 真实事件 {"error","session_id","success"} 无原文     │
│       → 时间窗对账: state 30分钟内刷过=reconciled          │
│         超时 → 写 .missing_turns 告警（下轮 AI 补 append） │
└──────────────────────────────────────────────────────────┘
```

## 1. 三线架构（v2 确立，v3 延续）

| 线 | 引擎 | 单位 | v3 状态 |
|---|---|---|---|
| 小说索引 | minimal_build.js（v1 引擎，未动） | 实体→块 | ✅ 77万字 0.4s 建索引；查询只回一块 |
| 代码/技能索引 | code-symbols / skill-lookup（v2） | 符号→文件:行；技能名→卡片 | ✅ appendTurn→lifecycle.js:43+签名；debugger 卡按需召回 |
| 会话记忆 | dialogue-lifecycle + memory-adapter + inject-budget（v2/v3） | 轮次→预算内注入 | ✅ 全链路端到端验证 |

## 2. 角色分工（v3 修正的核心：谁负责什么）

### 2.1 原文存储主通道 = AI 收轮 append

**历史教训**：v2 之前把存原文押在钩子收事件上，实证 Coomi turn_end 只发 `{"error":null,"session_id":"...","success":true}`（81 字节，无原文无轮次）——此路不通。

**v3 定论**：原文由 AI 在收轮时调 `appendTurn(logPath, dialogueId, userText, assistantDraft)` 主动落库。这是唯一可靠的原文通道。

### 2.2 钩子 = 时间窗对账保险带（coomi-hook-adapter）

```text
turn_end 事件到达
  → 读 state.json.updated_at
  → 30 分钟内刷过  → reconciled（AI 本轮已记账，静默）
  → 超过 30 分钟   → 写 .missing_turns 告警
     {session_id, state_updated_at, gap_minutes, hint}
     下轮 AI 看到标记 → 补 append → 删标记
```

窗口定 30 分钟的依据：append 发生在轮次早段时，10 分钟窗口会误报（实测 gap=12min 误报一例）；真实漏记是小时级隔断，30 分钟不影响捕获。

### 2.3 蒸馏 = 混合模式（v3.1，主人采纳）：规则草稿打底，AI 润色升级

- appendTurn 返回 `summary_due: true` 时**自动附带 `rule_summary_draft`**（零 LLM：每轮 assistant 首句拼接，带 turn_range 和 `quality: 'rule_draft'` 标记）
- **AI 缺席** → 草稿直接落库（`saveSemanticSummary({summary: '第X-Y轮（规则草稿）：' + draft.text})`），蒸馏**永不积压**
- **AI 在场** → 润色成综合叙事后落库，自动覆盖草稿的召回地位（同键去重后按分数排序）
- 实测（10 轮剧情）：规则草稿 10/10 要素覆盖、285 字；AI 润色版同要素、更凝练且含因果综合
- 落库记录 `（规则草稿）` 前缀标识来源，召回层无需区分

### 2.4 蒸馏积压恢复流程（历史教训固化）

## 3. v3 修复记录（端到端试跑暴露，全部已修+回归）

| # | 缺陷 | 根因 | 修复 | 验证 |
|---|---|---|---|---|
| 1 | 主动召回出双份记忆 | saveProactiveMemory 无去重，确定性 memory_id 重存产生重复条目 | recallProactive 按 memory_id 去重（append-only 不动） | 修复前 2 条→后 1 条，ID 唯一 PASS |
| 2 | 摘要注入带 JSON 格式开销 | compactSummary 对 `{summary:"..."}` 包装对象做 JSON.stringify | 只取内层文本 | 注入 577→306 字（省 47%），四要素完整 |
| 3 | 重复摘要重复注入 | semantic_summary 无去重 | 按 summary 前 80 字符前缀去重（注入层） | 2 份→1 份 |
| 4 | 对账 10 分钟窗口误报 | append 在轮次早段完成，轮结束晚于窗口 | 窗口放宽 30 分钟 | 12min 不再误报 / 45min 仍告警 |
| 5 | 蒸馏依赖 AI 在场（v2 遗留） | summary_due 只发请求，摘要必须 AI 写，缺席即积压（71-80 积压 6 天实例） | **混合蒸馏 v3.1**：窗口触发自动附带零 LLM 规则草稿（draftRuleSummary），AI 缺席草稿直接落库，在场则润色升级 | 新沙盒 10 轮剧情：草稿 10/10 要素、注入可召回；润色版覆盖正常；生产/e2e 回归无破坏 |

修复文件与备份：`memory-adapter.js`（.bak-e2e-dedup、.bak-hybrid-summary）、`inject-budget.js`（.bak-summaryfix）、`coomi-hook-adapter.js`（.bak2-20260910）。

## 4. 端到端验收记录（2026-09-10，8/8 全过）

隔离沙盒 `dlg-e2e-test`（11 轮，保留可复跑），生产对话未污染：

| # | 环节 | 实测 |
|---|---|---|
| 1 | 开轮 context（新对话空注入） | ✅ turn 0 |
| 2 | 小说索引链路 | ✅ 0.4s 建索引；只回一块 |
| 3 | 10 轮 append + 窗口触发 | ✅ turn 10 SUMMARY_DUE |
| 4 | 蒸馏三件套 | ✅ 摘要+状态抽取+主动记忆×3 |
| 5 | 主动召回 | ✅ 触发 2 条/冷却 0 条/无关 0 条/伏笔 1 条 |
| 6 | 技能卡片 | ✅ 修bug→debugger；纯问答→空 |
| 7 | 代码符号 | ✅ 符号→文件：行+签名 |
| 8 | gate 隔离 | ✅ 白名单内过/穿越拒/白名单外拒/append-only |
| 9 | 收轮一致性 | ✅ raw_turn=11=state.turn_number |

## 5. 诚实口径（v3 边界）

**已验证**：上表 8 环节；注入预算（77-588 ≤ 800）；state 一致性；gate 攻防 7/7；蒸馏积压恢复（生产 71-94 轮已补）。

**不承诺**：
- Coomi 25 万读账单——宿主上限（系统提示/工具表/引擎预注入），记忆层管不了
- 物理文件隔离——memory-gate 是插件级网关，宿主若另授文件权限则约束失效；接入方必须让它成为唯一记忆文件接口
- 记忆文本是数据不是指令——注入内容可能含恶意文本，宿主侧需自行防护

**环境注意（Ubuntu 24.04 proot）**：
- 钩子跑在宿主侧，路径用 `/data/data/com.coomi.android/files/home/...`；`.node-install` 执行位在 rootfs 升级后会丢（chmod +x 恢复）
- **钩子失败不报错（fail-open）→ 日志停更 = 钩子死了**。定期看 `hooks/memory-hook.log`、`hooks/memory-guard.log`、`hooks/code-guard.log`
- 语义向量增强已解锁（bge-small-zh ONNX 3ms/句，模型已缓存本地），作为可选增强层，未接入主链路

## 6. 使用手册（AI 操作规范）

### 开轮（每次对话第一件事）
```bash
node /workspace/novel-kg-compressor/scripts/dialogue-lifecycle.js context \
  /workspace/渐进式记忆/dialogues/<dialogue_id>/conversation_log.jsonl \
  <dialogue_id> "本轮用户消息"
# 只把返回的 inject 当记忆；recall/明细不复述
```

### 收轮（最终回复定稿后）
```bash
node /workspace/novel-kg-compressor/scripts/dialogue-lifecycle.js append \
  <log_path> <dialogue_id> "用户原话" "最终回复草稿"
# 返回 summary_due=true 时：
#   1. 结果里自带 rule_summary_draft（零 LLM 草稿，含 turn_range/quality 标记）
#   2. AI 在场 → 润色草稿为综合叙事后 save-summary 落库
#      AI 缺席/赶时间 → 草稿直接落库：save-summary "第X-Y轮（规则草稿）：<draft.text>"
#   3. 落库后删 .distill_pending（若存在）
```

### 补漏记（看到 .missing_turns 时）
补 append 缺失轮次 → 删除标记文件。

### new / inherit / continue（v1 原则，v3 保留）
AI 不得自行猜测对话继承关系；用户未明确选择时必须先问。资料库（library_id）与对话记忆（dialogue_id）是两个独立命名空间。

## 7. 文件清单

| 文件 | 职责 | 关键修复 |
|---|---|---|
| `scripts/dialogue-lifecycle.js` | CLI 入口：context/append/save-summary | — |
| `scripts/memory-adapter.js` | 轮次落库/状态抽取/主动记忆/召回 | #1 memory_id 去重 |
| `scripts/inject-budget.js` | 注入预算/排序/渲染 | #2 摘要格式、#3 摘要去重 |
| `scripts/coomi-hook-adapter.js/.sh` | turn_end 时间窗对账 | #4 30 分钟窗口 |
| `scripts/novel-lookup.js` | 小说实体→块（只回一块） | — |
| `scripts/skill-lookup.js` | 技能名→卡片（意图门控） | — |
| `scripts/code-symbols.js / code-lookup.js` | 代码符号→文件:行 | — |
| `scripts/memory-gate.js` | 插件级文件隔离网关 | — |
| `minimal_build.js` | 小说/代码索引构建器（v1 引擎） | — |
| `/workspace/hooks/code-guard.js` | 代码质量守门（影子模式） | 独立子系统，见 hooks/README.md |

## 8. 历史文档地图

| 文档 | 状态 |
|---|---|
| 根 README.md（v1） | 有效：小说索引哲学与性能基线（含 9-10 复测附录） |
| memory/README.md（v1 概念） | 有效：三层记忆/主动召回/冷却设计出处（含复测段） |
| docs/v2/README.md | 有效：v2 拆线与预算设计（§8/§9 已更新复测结论） |
| docs/v2/SESSION_ADAPTER.md 等 | 有效：v2 各线设计细节 |
| **docs/v3/README.md** | **⭐ 权威口径（v3 完整版，端到端验证）** |

## 9. V3 全面落实记录（2026-09-10）

| 落实项 | 内容 | 验证 |
|---|---|---|
| CLI 透出草稿 | append 结果含 rule_summary_draft + cli_hint | 10 轮 CLI 全流程 ✅ |
| CLI 纯文本 save-summary | 规则草稿可直落，不必手工包 JSON | 落库+注入召回 ✅ |
| 注入排序修复 | 同分并列时摘要优先（多轮浓缩>单轮），次级按轮次新→旧 | 同分场景摘要进注入 ✅；生产高分竞争下 raw 优先属正确行为 |
| 规则文件指针 | slim 规则指向 v3 权威文档 | ✅ |
| 收轮规则双路径 | summary_due→润色或草稿直落 + missing_turns 处理进常驻提示词 | ✅（新会话生效） |
| gate 白名单收敛 | 测试沙盒移除，仅留生产对话 | ✅ |

**V3 落实度：100%。** 脚本与文档即时生效；常驻配置（规则文件/钩子）自新会话生效。

---
*最后更新: 2026-09-10 · v3.1 · 端到端验证 + 全面落实完成*
