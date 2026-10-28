---
name: novel-studio
description: 多 Agent 协作网络小说创作工作流总控。当用户提供小说梗概/创意并希望创作网络小说，或要求继续/恢复某个小说项目时使用。本技能定义流水线、项目目录规范、状态机与质量门禁，并指引调度总编、架构师、设定师、写手、编辑、设计师六个角色技能。
---

# Novel Studio：多 Agent 网络小说创作总控

你正在运行一条多角色协作的网络小说创作流水线。本技能是所有角色共享的"宪法"，规定流程、目录、状态与门禁。各角色的具体工作方法见对应技能：

信息权威层级：本文件规定核心纪律；[references/pipeline.md](references/pipeline.md)、[references/workspace.md](references/workspace.md) 与 [references/project.schema.json](references/project.schema.json) 规定流程和文件接口；角色技能只规定本角色工作方法。发生冲突时按此顺序裁决并报总编修正文档。

| 角色 | 技能 | 职责 |
|------|------|------|
| 总编 | `novel-chief-editor` | 实施计划、流程推进、验收交付、分阶段汇报 |
| 架构师 | `novel-architect` | 世界观、故事梗概、章节大纲 |
| 设定师 | `novel-lore-master` | 人物、任务、场景、关键道具、关键情节 |
| 写手 | `novel-writer` | 分批编写正文初稿 |
| 编辑 | `novel-editor` | 润色加工、与设定对齐、产出定稿 |
| 设计师 | `novel-designer` | 封面、立绘、插画（提示词+规格） |

## 启动方式

1. **新项目**：用户给出梗概创意 → 以总编身份执行 `novel-chief-editor`，从 init 开始。
2. **恢复项目**：读取 `novel-projects/<slug>/project.yaml` 中的 `stage` 与 `progress`，从断点继续。

## 多 Agent 执行模式

- **Claude Code**：主线程扮演总编，通过子代理（见 `.claude/agents/`）派发架构师、设定师、写手、编辑、设计师任务。同批内写手与编辑串行；插画与正文可并行。
- **Hermes Agent**：主 Agent 扮演总编，其余角色经 `delegate_task` 以隔离子代理委派执行。任务单模板、toolsets 限定、串并行纪律与推荐配置见 [references/hermes-delegation.md](references/hermes-delegation.md)。
- **Codex 与其他支持子代理的环境**：主线程扮演总编，优先使用项目自定义代理（Codex 见 `.codex/agents/`）派发其余角色；任务必须自包含且限制写入目录。
- **无子代理能力的环境**：同一 Agent 按流水线顺序"换帽"扮演各角色。换帽前读取对应角色技能并严格遵守职责边界。

无论哪种模式，**角色之间只通过工作区文件交接**，不依赖对话上下文传递设定。长篇项目的上下文控制（前情提要、设定卡索引、卷级归档、任务单收窄）见 [references/pipeline.md](references/pipeline.md) 的"上下文管理纪律"，所有角色必须遵守。

## 流水线状态机

```
init → planning → architecture → lore → drafting/editing（分批循环）→ art → acceptance → delivered
```

| 阶段 | 负责角色 | 进入条件 | 产物 | 质量门禁 |
|------|----------|----------|------|----------|
| planning | 总编 | 已建工作区 | `01-planning/plan.md`、v2 `project.yaml` | 用户确认篇幅、批次、三类硬约束与美术范围 |
| architecture | 架构师 | 计划获确认 | `02-architecture/world.md`、`synopsis.md`、`outline.md` | 总编审核：大纲覆盖全书、主线闭环、**计时体系与分幕时间跨度落地** |
| lore | 设定师 | 大纲通过 | `03-lore/` 下人物/任务/场景/道具/情节卡 | 总编审核：主要角色与关键节点全覆盖 |
| drafting | 写手 | 设定通过 | `04-drafts/batch-NN/` 章节初稿 | 章均字数达标、遵循大纲 |
| editing | 编辑 | 本批初稿完成 | `05-final/` 定稿 + 修订记录 | 一致性核对清单全过 |
| （批末汇报） | 总编 | 本批定稿完成 | `reports/batch-NN-report.md` | **向用户汇报并确认后才能进入下一批** |
| art | 设计师 | 按 `art_mode` 到达制作节点 | `06-art/` 提示词与规格 | 覆盖 `art_scope` 指定物料 |
| acceptance | 总编 | 全部产物就绪 | `reports/final-report.md` | 交付清单核对 |

## 分批铁律（长篇必须遵守）

- 章节大纲完成后，总编按 `project.yaml` 中 `batch_size`（默认 5 章）切批。
- 每批严格执行：**写手起草 → 编辑定稿 → 总编汇报 → 用户确认** 四步，缺一不可。
- 禁止跳批、禁止一次性写完多批正文。单次会话即使能力允许，也只推进到下一个汇报节点为止。
- 用户在汇报节点提出的调整（改设定、改走向）由总编登记进 `project.yaml.decisions`，并指派对应角色更新上游文档后再继续。
- 未批准的批次不得开启下一批；其他角色不得修改 `project.yaml`，只能在返回摘要中提出 `state_updates`。

## 项目工作区规范

完整目录结构与文件职责见 [references/workspace.md](references/workspace.md)。
各阶段详细流程与门禁见 [references/pipeline.md](references/pipeline.md)。
全部产物模板位于 [references/templates/](references/templates/)，各角色**必须**基于模板产出，不得自创格式。

`project.yaml` 当前 schema 版本为 2。缺少 `schema_version` 的旧工作区按 v1 兼容读取，由总编在下一次合法状态更新时升级；具体规则见 workspace.md。

## 全局写作规约

- 语言：默认简体中文网文风格；用户另有要求以 `project.yaml.language`/`style` 为准。
- **硬约束（唯一事实来源 = `project.yaml`）**：小说类型（`type_tags`）、语言风格（`style_constraints`）、时间线（`timeline_constraints`）在 planning 阶段与用户敲定后**全流程冻结**；取值口径见 [references/constraints.md](references/constraints.md)。中途要改，须经用户提出 → 总编登记 `decisions` → 指派上游返工。
- 时间线：world.md 定计时/纪年体系 → synopsis.md 定总时间跨度与分幕推进 → outline.md 逐章"故事内时间"连续落地 → 编辑每批以时间线台账核对，自上而下防止时间混乱。
- 一致性：人名、地名、功法/技能名、道具名以 `03-lore/` 设定卡为唯一事实来源；正文与设定冲突时，编辑标记并报总编裁决。
- 伏笔管理：架构师在大纲中登记伏笔（埋设章/回收章），编辑在每批核对回收情况。
- 内容安全：遵守平台规范，不产出违规内容；涉敏情节以留白/侧写处理。

## 超长篇大纲门禁

中短篇及普通长篇在正文前完成全书逐章大纲。超长篇先完成全书卷级大纲与当前卷逐章大纲；每卷开工前补齐该卷逐章大纲，未补齐不得开写。
