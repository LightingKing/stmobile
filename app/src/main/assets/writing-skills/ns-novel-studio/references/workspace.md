# 项目工作区规范

每部小说一个工作区，位于仓库（或用户指定位置）的 `novel-projects/<slug>/`。`<slug>` 为书名的拼音或英文短横线小写形式（如《星渊葬天录》→ `xing-yuan-zang-tian-lu`）。

## 目录结构

```
novel-projects/<slug>/
├── project.yaml                  # 项目元信息 + 状态机，唯一事实来源（仅总编可写）
├── 01-planning/
│   └── plan.md                   # 总编：实施计划（模板 templates/plan.md）
├── 02-architecture/
│   ├── world.md                  # 世界观设定（模板 templates/world.md）
│   ├── synopsis.md               # 故事梗概（模板 templates/synopsis.md）
│   └── outline.md                # 章节大纲（模板 templates/outline.md）
├── 03-lore/
│   ├── index.md                  # 设定卡索引（模板 templates/lore-index.md）
│   ├── characters/<id>-<名>.md   # 人物卡（模板 templates/character.md）
│   ├── quests/<id>-<名>.md       # 任务线（模板 templates/quest.md）
│   ├── scenes/<id>-<名>.md       # 场景卡（模板 templates/scene.md）
│   ├── items/<id>-<名>.md        # 道具卡（模板 templates/item.md）
│   └── plot-points/<id>-<名>.md  # 关键情节卡（模板 templates/plot-point.md）
├── 04-drafts/
│   └── batch-<NN>/
│       └── chapter-<NNN>-<章名>.md   # 初稿
├── 05-final/
│   ├── chapter-<NNN>-<章名>.md       # 定稿
│   ├── story-so-far.md               # 前情提要，编辑每批滚动更新（模板 templates/story-so-far.md）
│   └── revisions/batch-<NN>-revision-log.md  # 修订记录
├── 06-art/
│   ├── cover/cover-spec.md
│   ├── portraits/<人物id>-portrait.md
│   ├── illustrations/ch<NNN>-<情节>.md
│   └── （若有出图能力）对应 .png/.svg 成品
└── reports/
    ├── batch-<NN>-report.md      # 阶段汇报（模板 templates/stage-report.md）
    ├── volume-<N>-report.md      # 卷级归档报告，超长篇专用（模板 templates/volume-report.md）
    └── final-report.md           # 终验报告（模板 templates/final-report.md）
```

## 命名规则

- `<NN>` 批次号、`<NNN>` 章节号，均零填充（`batch-01`、`chapter-007`）。
- 设定卡 `<id>` 为两位序号：`characters/01-林晚.md`、`items/03-焚天剑.md`。
- 文件名中的中文名取正式定名；改名时必须全工作区同步并在 `project.yaml.decisions` 登记。

## project.yaml 字段约定

见模板 [templates/project.yaml](templates/project.yaml) 与机器可读规范 [project.schema.json](project.schema.json)。关键字段：

- `schema_version`：当前为 2；缺失时按 v1 兼容读取，由总编在下一次状态更新时升级。
- `stage`：当前阶段（init/planning/architecture/lore/drafting/editing/art/acceptance/delivered）。
- `type_tags` / `style_constraints` / `timeline_constraints`：类型、语言风格与时间线硬约束，planning 阶段敲定后冻结（口径见 [constraints.md](constraints.md)）。
- `art_enabled` / `art_mode` / `art_scope`：是否制作美术、制作时机与覆盖范围。
- `progress.current_batch` / `progress.batches`：批次进度，每批含 `status`（pending/drafting/editing/reported/approved）。
- `progress.art`：美术独立进度；增量美术不得覆盖正文 `stage`。
- `decisions`：用户决策与改动登记（带日期），所有角色开工前必读。
- **只有总编可写 `project.yaml`**。其他角色只读，并在交付摘要的 `state_updates` 中提出建议，由总编核验后落盘。

### v1 兼容读取

缺少 `schema_version` 的工作区视为 v1：时间线从 `01-planning/plan.md`、world.md、synopsis.md 回退；美术范围由 `art_enabled`/`art_mode` 推导。总编在下一次合法状态变更时补齐 v2 字段，并在 `decisions` 登记迁移，不要求批量重写历史产物。

## 交接规则

1. 上游产物未过门禁，下游角色不得开工。
2. 角色只写自己目录：架构师只写 `02-architecture/`，设定师只写 `03-lore/`，写手只写 `04-drafts/`，编辑只写 `05-final/`，设计师只写 `06-art/`，总编写 `01-planning/`、`reports/` 并独占维护 `project.yaml`。
3. 发现上游问题不要私改，在自己的产出中以 `> [!ISSUE]` 块记录并报总编，由总编指派上游角色修正。
4. 并行任务不得写同一文件：设定卡可按类别并行，但并行子代理不得修改 `03-lore/index.md`；全部返回后由一个指定设定师统一生成并校验索引。设计任务仅在输出路径完全不同时并行。
