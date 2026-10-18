# Pocket Tavern（口袋酒馆）

Pocket Tavern 是一个独立的 Android SillyTavern 风格应用。它使用自己的应用数据、工作区和 API Key 存储，不依赖桌面版 SillyTavern 的运行时。聊天推理通过用户配置的云端 Provider 完成；角色、预设、记忆、工作区文件和导入的资源保存在本机。

当前为 **0.1.3 候选版**：重新设计聊天页，采用暖白 / 深森林配色、长文排版、紧凑标题栏和圆角输入栏；历史会话、模型查询和会话操作按需展开。保留模型价格、Provider 筛选和 7 个 GitHub 写作 Skills。Android 实机安装/运行验收仍未完成，图片编辑、放大的历史真实请求未通过。各层验证与设计方案的差异见 [验证记录](docs/VERIFICATION.md)。

## 当前可用范围

- Android 8.0（API 26）及以上；应用的 `minSdk` 为 26，目标 SDK 为 35。
- Venice 动态模型目录：从 `/models` 分页读取模型，并按 `text`、`image`、`inpaint`、`upscale`、`tts`、`asr`、`video`、`music`、`embedding`、`decision` 十类目录标签展示。目录中的原始能力 JSON、上下文长度、工具/视觉/推理能力和离线标记会保留在模型信息中。
- Venice 媒体调用：图片、局部重绘、放大、TTS、ASR、视频、音乐、embedding 和 decision 使用对应的 JSON 或 multipart 请求；模型特有的高级参数可以随请求传入，界面也提供常用参数模板。
- 文本聊天：支持 Venice/OpenAI 兼容接口和 Anthropic Messages 接口。Provider 的 Base URL 必须是 HTTPS，API Key 保存到 Android Keystore 保护的本地密钥存储中。
- 角色、系统提示词、全局 Persona、Worldbook、聊天预设、摘要预设和压缩回退均由应用本地数据模型管理。
- Skills 采用显式启用和权限授予流程；每个 Skill 的文件访问、网络和记忆权限都可以由用户审核。
- 内置 7 个 GitHub 写作 Skills：奇幻、悬疑、对白、场景结构、言情、剧情结构和小说场景。来源为 wordflowlab/novel-writer-skills 与 SkillMedev/skills，原文和 MIT 许可证随 APK 提供；[完整来源清单](docs/THIRD_PARTY_SKILLS.md)。

## 安装与源码构建

构建环境要求 JDK 17、Android SDK 35。项目包含 Gradle 8.14.3 Wrapper；脚本优先使用 `JAVA_HOME`，本开发机也支持 `I:\jdk1.17`；Android SDK 使用 `ANDROID_HOME` 或默认的用户 SDK 目录。Windows PowerShell 7 中执行：

```powershell
cd I:\workspace\SillyTavern\apps\pocket-tavern
pwsh .\build.ps1
pwsh .\build.ps1 -WithTests
pwsh .\build.ps1 -Release
```

需要打包并生成本地签名 APK 时执行：

```powershell
pwsh .\package.ps1
```

`package.ps1` 会在用户目录下创建或使用 `pocket-tavern-signing` 的个人签名材料，并把 APK 和 SHA-256 校验文件放入 `dist/`。签名材料不应提交到仓库。网络受限时，`build.ps1` 支持传入 `-AndroidMirror <url>`。

本文档只记录构建入口和代码声明的环境要求；构建、单元测试、仪器测试和真机验收的具体结果统一查看 [`docs/VERIFICATION.md`](docs/VERIFICATION.md)。

## 首次配置

1. 安装应用后打开“模型”，编辑内置的 Venice 平台或新增平台。
2. 选择 Venice、OpenAI 兼容或 Anthropic，填写 HTTPS Base URL 和自己的 API Key。应用不会从桌面版读取 Key，也不会把 Key 写入备份导出。
3. 在“模型”页刷新目录。Venice 通过 `/models?type=all` 及分页读取动态目录；OpenAI 兼容和 Anthropic 读取各自的 `/models` 响应。需要时可手动添加模型 ID，但聊天仍要求它成为可用的文本模型目录项。
4. 选择文本模型并创建聊天。附件、工具调用和摘要是否生效还取决于当前模型返回的能力信息。

模型卡片会直接显示“价格参考”，价格较多时可展开全部档位；媒体工具里也显示相同信息。金额来自平台模型目录，保留货币和计价单位；缺失价格不会显示成免费。Venice 视频价格需在工具中按具体参数查询报价。平台卡片显示目录缓存时间，点击“刷新模型”可更新价格。旧版已缓存的模型原始数据可直接用于价格展示，无需清空聊天或重新配置平台。

当前定价结构适配针对 Venice；其他平台会明确提示定价格式尚未适配，不沿用 Venice 的计费单位。

模型目录的“平台（Provider）”可筛选已配置平台，并与模型类型组合使用。搜索框同时匹配模型名称/ID/类型和平台名称/ID/协议/地址，不区分大小写；多个空格分隔的词同时匹配，例如 `Venice Gemini`。没有匹配项时可一键清除筛选。这里的 Provider 是应用中配置的平台账户。

## 聊天、角色、预设与记忆

聊天页左上角打开历史会话与搜索，右上角 `+` 新建会话；顶部模型按钮打开可搜索、可按 Provider 筛选的模型面板，右侧书本按钮切换预设。右上角 `···` 提供记忆 / 压缩 / Skills、重命名、导出和删除入口。消息旁 `···` 提供复制、编辑用户消息和分支；最新一轮助手回复下方可重新生成。

助手回复使用开放的正文排版，用户消息使用浅色气泡。正文支持标题、强调、列表、引用和代码块；思考过程与工具结果默认折叠，输出不执行 HTML 或脚本。向上阅读历史时流式回复不会强制滚动，可用向下箭头回到最新消息；切换会话或底部栏目时保留未发送草稿。键盘弹出时底部导航隐藏，输入栏继续可用。视觉样例及检查说明见 [聊天界面验收](docs/CHAT_UI_ACCEPTANCE.md)。

角色可以保存名称、描述、人格/系统提示词、开场内容和 Worldbook 关联；聊天上下文会加入全局 Persona、角色提示词、命中的 Worldbook 条目、聊天记忆和附件提示。预设保存系统提示词、温度、最大输出、摘要提示词以及摘要 Provider/模型 ID，并可单独导入导出。

记忆分为全局、角色和聊天范围。模型可以通过受授权的 Skill 搜索记忆或保存聊天记忆，用户也可以在界面编辑和恢复摘要。上下文接近限制且有可压缩的旧轮次时，应用按当前预设生成摘要。摘要失败会停止本次发送，原文和旧摘要保留；编辑来源消息后旧摘要失效，重新使用原文并校验预算。当前 `memory_search` 是本地词项匹配；语义 vector 记忆索引尚未集成。

## Skills 与工作区

应用只在自己的沙盒工作区 `filesDir/workspace` 内读写文件。路径会做规范化和边界检查，敏感文件名（例如 `.env`、密钥、密码和 token 文件）会被拒绝或隐藏；网络请求要求 HTTPS 且不跟随重定向。认证仅能由应用注入当前会话选中的 Provider；Skill 不能选择其他已配置账户或自行设置认证头。一般公开 HTTPS API 可以直接调用。

在“Skills”页选择“小说写作”或“言情与关系”，勾选“用于本会话”即可启用并将该 Skill 加入当前会话。可同时勾选多个；取消勾选只从当前会话移除。“允许启用”是全局开关，关闭后所有会话都会停止载入该 Skill。没有会话时先创建会话。详情页可查看上游原文、固定版本 GitHub 链接和许可证。

内置条目默认禁用且无工具权限；写作正文不依赖工具调用，可用于普通文本模型。若需要工具读写文件、联网或保存记忆，再在详情页授予所需权限，并选用支持工具调用的聊天模型。上游 Skill 中的 Read/Grep 和 `/write` 等是桌面环境约定，不是本应用中的可执行命令。源码中的 `examples/story-continuity/SKILL.md` 是单独的工具验证示例，不属于 GitHub 精选库。

首次安装或从旧版升级时添加这 7 个条目；重启不会重复添加，已删除的内置条目不会自动恢复。已有自定义 Skills、授权、会话选择和用户修改会保留。内置 Skill 的显示名称和简介使用中文，SKILL.md 正文本身未经改写（5 个中文、2 个英文）。言情条目是普通言情与关系写作指导，不是露骨 NSFW 技能。

按能力可归为 7 类：工作区列举、工作区读取、工作区写入、网页读取、一般 HTTPS 请求、记忆搜索/保存、Skill 文件读取。源码实际注册的函数名是 8 个：`workspace_list`、`workspace_read`、`workspace_write`、`web_fetch`、`http_request`、`memory_search`、`memory_save`、`skill_read`；其中“网络”包含两个独立函数，“记忆”包含两个独立函数。每个 Skill 初始为禁用且无权限，导入或下载后须由用户审核并明确授予权限。

Skill 的 `SKILL.md` 和 ZIP 会做大小、条目数和路径穿越检查。Skill 中的 Python、Shell、Node 或其他桌面脚本不会在 Android 上运行；文件内容只能在获得文件读取权限后被读取。导出的 Skill 当前是 `SKILL.md` 文本，不能视为完整 ZIP 资源包导出。

## 导入与导出

支持备份、角色、Worldbook、聊天、预设和 Skill 导入。应用还支持导出对应的 JSON/JSONL/文本数据，以及包含 `state.json`、`media/` 和 `workspace/` 的备份 ZIP。备份恢复前会提示确认，备份中的 API Key 不会被导入；ZIP 内路径穿越、未知文件和超出大小限制的内容会被拒绝。

应用通过 Android 系统文件选择器读写用户选定的文件，不申请共享存储读写权限。媒体文件存放在应用自己的 `media` 目录中，需要分享或保存时由系统 URI 权限完成。

## 数据与隐私

角色、聊天、预设、记忆、媒体、Skill 和工作区数据默认留在应用私有存储；API Key 使用 Android Keystore 保护的本地 SecretStore 保存。应用只声明 `INTERNET` 权限，推理和用户主动发起的模型/工具请求会发送到所选 Provider 的云端服务。云端服务的日志、保留和隐私政策不由本应用控制，使用前应按 Provider 的政策配置模型和内容。

应用关闭明文流量并要求 Provider/工具 URL 使用 HTTPS。备份导出仍可能包含聊天、角色、记忆、媒体和工作区内容，分享备份前应自行检查其中的敏感信息。

## 已知限制

- 语义 vector 记忆索引尚未集成，当前记忆检索是本地词项匹配。
- SillyTavern 宏、桌面插件和任意扩展运行时没有完整兼容；可导入的角色、Worldbook、聊天和预设只覆盖应用实现的字段。
- 目录中未知模型类型可以展示原始信息，但不会自动获得对应的调用适配器。
- 媒体运行时目前针对 Venice；其他 Provider 的模型目录可以读取，但媒体请求不会被路由到通用接口。
- 媒体、工具和附件能力取决于当前模型的能力声明以及 Provider 的实际响应。
