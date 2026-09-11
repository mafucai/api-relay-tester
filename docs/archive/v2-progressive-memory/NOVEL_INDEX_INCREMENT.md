# 小说索引增量修补（7.5 → 约 8）

> 不是重设计。切章 → 实体指向块 → 按需加载，保持不变。  
> 你已亲测百万字有效。本文件只补三个缺口：停用词、块摘要、查询只回一块。  
> 状态：已实现。旧 `.lpm` 与 `memory/README.md` 不删。

## 1. 不碰的部分

- `splitSegments` / `splitChapters` 的章节模式
- `.lpm/index.txt` + `block_NNN.txt` 目录形状
- 零 LLM 构建、确定性输出
- 已有小说测试夹具（`test/test_novel.txt`、`test/test.js`）必须继续通过
- 不把技能/代码再送进这条构建链（那是 v2 另外两条线）

推倒重写目标 9：不在本增量范围。先掉到 5 的风险高于多 0.5 分。

## 2. 已观测的三个缺口

| 缺口 | 证据 | 修补 |
|---|---|---|
| 停用词两套 | `minimal_build.extractEntities` 内置短 `bad` 表；`config/blacklist.json` 更全，构建小说索引时**没接上** | 构建时合并 blacklist；丢掉纯数字、长度 1、通用词 |
| 块摘要偏位置分 | `extractKeySentences` 按句在段内越靠前越高分，不看实体是否在句中 | 含已抽取实体的句子加权；每块仍最多 3 句 |
| 查询无「一块」帽 | 现在靠代理读 `index.txt` 再自己 `read_file` 块；没有 CLI 强制只回 1 块 | 新增 `novel-lookup.js`：命中实体 → **恰好 1 个 block 文件**，stdout ≤64KB |

`core-library` 被切成 1 块、实体「文档/文件」：那是误用小说构建器切文档，**不在本增量修**。见 `CODE_AND_SKILL_INDEX.md`。

## 3. 停用词

加载顺序：

1. `config/blacklist.json`（已有，偏网文套话）
2. 内置 `bad` 字后缀表（已有）
3. v2 追加：纯 `[0-9]+`、单字、`文件/文档/时候/什么/自己/他们` 等（若已在 blacklist 则不重复）

规则：姓氏锚定仍在（`SUR` 首字）。命中 blacklist 的整词丢弃，即使频次 ≥ `minFreq`。

`--min-freq` 保持默认 3。不靠降频来当停用词。

## 4. 块摘要

仍每块 3 句。计分改为：

```
score = position_score          # 现有：段内越前越高
      + length_bonus            # 现有：20–60 字
      + 0.5 if 句子含本块实体
```

不引入 TextRank 作为默认（`src/textrank.js` 可留着，本增量不换引擎）。实体加权足够让「萧炎在广场」压过开场套话。

块文件格式保持可读文本，多一节可选：

```text
## 实体
萧炎(12)、薰儿(4)

## 摘要
- …
```

旧块没有「实体」节也可以被 lookup 使用（从总 `index.txt` 反查）。新构建才写实体节。`--incremental` 只重建变更源，不强制重写全部旧块。

## 5. 查询：只回一块

查询 CLI（已实现）：

```text
node scripts/novel-lookup.js \
  --index <path-to-index.txt> \
  --blocks <dir-of-block_*.txt> \
  --q 萧炎
```

行为：

1. 在 `index.txt` 里找实体。精确名优先，其次前缀。
2. 该实体若指向多块：取**频率最高**的一块；并列取编号最小。
3. stdout：该 `block_NNN.txt` 全文。若文件 >64KB，只输出 `## 摘要` + `## 实体` 两节，并打印 `truncated: true` 到 stderr。
4. 未命中：退出码 0，stdout 空 JSON `{"hits":[]}`（方便脚本）。
5. **从不**一次输出两块。需要第二块必须再调一次，换 `--q` 或 `--block=N`。

代理工作流（小说问答）：

```text
读 index.txt（通常 <2KB）
→ novel-lookup --q <人物/章名>
→ 只用这一块回答
禁止把 block_001…block_039 连读。
```

## 6. 章前序言

`splitSegments` 在第一个「第X章」之前的行会丢。增量：无标题前缀收成 `label: 序言` 的第 0 段，再进入切块。空序言不写块。

这是唯一允许的切分行为变化；章节标题正则不改。

## 7. 回归护栏

写代码时必须：

```text
npm test
node test/test.js
```

另增：

| 测试 | 期望 |
|---|---|
| 构建 `test/test_novel.txt` | `index.txt` 含 `萧炎`，不含 `什么`/`时候` |
| `novel-lookup --q 萧炎` | 恰好一块，且块内有萧炎 |
| `novel-lookup --q 萧炎` 再 `--q 薰儿` | 两次调用，不是一次两块 |
| 无「第X章」的短文 | 不崩溃；有序言块或 0 块，退出码 0 |
| 旧 `src/.lpm/index.txt` | 不删除；lookup 仍能读旧格式 |

`--force` 重建测试输出到 `test/` 下临时目录，不覆盖用户小说库。

## 8. 落点（已改）

**改（备份 `*.bak.v2-novel`）：**

- `/workspace/novel-kg-compressor/minimal_build.js`  
  仅：`extractEntities` 接 blacklist；`extractKeySentences` 实体加权；序言段。  
  不把代码符号逻辑继续堆进此文件。

**新：**

- `/workspace/novel-kg-compressor/scripts/novel-lookup.js`
- `/workspace/novel-kg-compressor/test/novel-lookup.test.js`

**不改：** `src/splitter.js`、`src/compressor.js`（旧压缩管线继续走现有测试）。若 blacklist 读取要共用，抽 10 行 `loadBlacklist()` 到 `scripts/stopwords.js`，两边引用。

## 9. 非目标

- 不重写姓氏表去追全部复姓（可后续加 `config/surnames.json`，本增量不依赖）。
- 不做向量检索、不做人物关系图 UI。
- 不把 85–90% 写进本文件当会话承诺。小说场景的节省仍以你亲测为准。
- 不重建用户已有的百万字 `.lpm`，除非你明确要求 `--force`。lookup 必须兼容旧块格式。
