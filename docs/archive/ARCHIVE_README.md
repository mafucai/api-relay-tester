# 文档存档（防丢失备份）

> 存档原因：2026-09-11 环境重置导致 /workspace/docs 整体丢失。主人文档（工程产物）一律收进 Git 仓库自包含。
> 来源：/workspace/novel-kg-compressor/docs/（渐进式记忆项目）

## 内容

- `v3-progressive-memory/README.md` — 渐进式记忆 v3 权威文档（2026-09-10 定稿，全链路端到端验证）
  - 原始位置：/workspace/novel-kg-compressor/docs/v3/README.md
  - 权威口径：原文主通道=AI 收轮 append；钩子=时间窗对账保险带；蒸馏=AI 主导；注入≤800 tokens
- `v2-progressive-memory/` — v1/v2 设计文档（README/IMPLEMENTATION_PLAN/CODE_AND_SKILL_INDEX/SESSION_ADAPTER/NOVEL_INDEX_INCREMENT）
  - v3 明确：v1/v2 为历史出处，冲突以 v3 为准

## 恢复指引

若 /workspace/novel-kg-compressor/docs/ 再度丢失：
cp docs/archive/v3-progressive-memory/README.md /workspace/novel-kg-compressor/docs/v3/README.md
