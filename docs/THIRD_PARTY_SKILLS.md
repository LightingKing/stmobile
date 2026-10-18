# 第三方写作 Skills

核对日期：2026-10-18。仅收录下列 GitHub 仓库的现有 SKILL.md，未自编或改写正文。应用中的中文显示名称、简介和分类属于界面元数据。所有条目默认禁用、无工具权限。

## 来源与版本

| 应用名称 | 上游原文（固定提交） | 语言 | 许可证 |
| --- | --- | --- | --- |
| 奇幻世界构建 | [SKILL.md](https://github.com/wordflowlab/novel-writer-skills/blob/5bc9b373ff609e8910e0e8d179e4a697bf2b1268/templates/skills/genre-knowledge/fantasy/SKILL.md) | 中文 | MIT |
| 悬疑小说规范 | [SKILL.md](https://github.com/wordflowlab/novel-writer-skills/blob/5bc9b373ff609e8910e0e8d179e4a697bf2b1268/templates/skills/genre-knowledge/mystery/SKILL.md) | 中文 | MIT |
| 自然对白技巧 | [SKILL.md](https://github.com/wordflowlab/novel-writer-skills/blob/5bc9b373ff609e8910e0e8d179e4a697bf2b1268/templates/skills/writing-techniques/dialogue-techniques/SKILL.md) | 中文 | MIT |
| 场景结构技巧 | [SKILL.md](https://github.com/wordflowlab/novel-writer-skills/blob/5bc9b373ff609e8910e0e8d179e4a697bf2b1268/templates/skills/writing-techniques/scene-structure/SKILL.md) | 中文 | MIT |
| 言情小说规范 | [SKILL.md](https://github.com/wordflowlab/novel-writer-skills/blob/5bc9b373ff609e8910e0e8d179e4a697bf2b1268/templates/skills/genre-knowledge/romance/SKILL.md) | 中文 | MIT |
| 剧情结构设计 | [SKILL.md](https://github.com/SkillMedev/skills/blob/97a13607d669157a4a16d5ba79b77f208319f7b9/skills/story-structure-architect/SKILL.md) | 英文 | MIT |
| 小说场景写作 | [SKILL.md](https://github.com/SkillMedev/skills/blob/97a13607d669157a4a16d5ba79b77f208319f7b9/skills/fiction-scene-writer/SKILL.md) | 英文 | MIT |

wordflowlab/novel-writer-skills 固定提交：`5bc9b373ff609e8910e0e8d179e4a697bf2b1268`。SkillMedev/skills 固定提交：`97a13607d669157a4a16d5ba79b77f208319f7b9`。

许可证原文位于 `app/src/main/assets/writing-skills/licenses/`，并随每个 Skill 加载到 LICENSE 文件中；详情页可直接查看。每个 SKILL.md 均按下载的 UTF-8 原始字节保存，加载时核对以下 SHA-256。

| 条目 ID | SKILL.md SHA-256 |
| --- | --- |
| wf-fantasy | `afaa802c1bc9611a9d072e12cb18ce600251687b4d53d494da008bbae784da9e` |
| wf-mystery | `fbdb55abc0d5293602154d64ada127f519f4396fd032a8e7829e0c5daf87bc3e` |
| wf-dialogue | `bf4e48d259a7376c04f93bb3c1c97ae97c3f5bbc15c6ccf298a9b2cd03bae197` |
| wf-scene | `6957bcefb32c7591855b6329c52d5b9520635873ba6c99c3f68990108455fe09` |
| wf-romance | `a3d899c702bf40256986a9120bbe35fc33499ab8403eb445ed722a18f9ab8202` |
| sm-story-structure | `689a403eb020e2ba6a572c15d61a7819a52953cba4cb0407b446b62b3334e339` |
| sm-fiction-scene | `59e0ef744e9aaa986093a05c6a65e7b556938a466db26f4a5b2449b43005c64d` |

## 审查结果与移动端兼容性

- 来源检查：wordflowlab/novel-writer-skills 在检查时为 267 stars，GitHub 更新时间 2026-10-18；SkillMedev/skills 为 19 stars，更新时间 2026-10-15。作者身份与独立安全评价未额外核实；星数不作为安全保证。
- 审查范围：7 份 SKILL.md 全文、2 份 MIT LICENSE。仅这些文件及应用生成的来源清单进入 APK，未安装上游 npm 包、插件或命令行运行时。
- 所选文件均为静态写作指导。没有可执行脚本、凭据读取、下载执行、数据上传或绕过规则的指令，风险评估为低。未执行上游安装脚本。
- 所需权限：正文注入无需文件、网络或命令权限；所选包不依赖可执行命令。结论：可以默认禁用的形式随应用分发，由用户勾选启用。
- 已重新获取固定 commit 的 7 份原文和 2 份许可证，逐字节 SHA-256 全部匹配本地打包文件。证据位于开发机 `evidence/github-skill-review/upstream-hash-verification.json`。
- wordflowlab 的 Read/Grep 工具元数据以及 `/specify`、`/plan`、`/write`、`/analyze` 是上游桌面工作流约定；本应用不实现这些同名命令。文件保存提示不会自动授予写入权限。
- 手机端将勾选且全局启用的 Skill 正文加入当前会话上下文；上游工具声明不会转换为授权。若用户另行授予工具权限，仍仅能调用应用提供的手机端工具。
- SkillMedev 的两份英文原文提及其他写作角色用于划分职责，不包含必须随包提供的脚本或参考文件。
- 言情小说规范覆盖感情发展、关系边界和类型节奏，是普通言情 Skill；本包不将其标记为露骨 NSFW 写作技能。
- 运行时验证限于解析、原文哈希、升级去重、默认关闭、会话选择和上下文注入的 JVM 测试。尚未完成 Android 设备上的勾选及模型写作效果验收。

删除内置条目后不会因重启再次出现；更新应用不会覆盖用户对已存在条目的修改。来源链接记录首次导入原文的固定版本，用户后续修改内容时应自行区分。
