# Pocket Tavern 能力清单

本文档按当前源码描述能力状态，供发布前核对使用。构建、测试和设备验收结果不在这里臆测，统一记录在 [`VERIFICATION.md`](VERIFICATION.md)（由 root 维护）。

| 功能 | 状态 | 当前行为与边界 |
| --- | --- | --- |
| 独立 Android 应用 | 已实现 | `applicationId` 为 `app.pockettavern`；`minSdk 26`（Android 8.0），目标 SDK 35；使用应用自己的 Room、文件目录和密钥存储。 |
| Provider 配置 | 已实现 | 支持 Venice、OpenAI 兼容和 Anthropic；Base URL 要求 HTTPS、无用户信息/查询/片段；API Key 通过 Android Keystore 保护的 SecretStore 保存。 |
| Venice 动态模型目录 | 已实现 | 请求 `/models?type=all`，支持 `after_id` 分页；保留上下文、工具/视觉/推理能力、离线标记和原始 JSON。 |
| 十类目录标签 | 已实现 | UI 按 `text`、`image`、`inpaint`、`upscale`、`tts`、`asr`、`video`、`music`、`embedding`、`decision` 展示。未知类型可展示但没有自动适配器。 |
| OpenAI 兼容聊天 | 已实现 | 使用 `/chat/completions`，支持流式响应、JSON 回退、工具调用和推理字段；聊天入口要求可用的文本模型。 |
| Anthropic 聊天 | 已实现 | 使用 `/messages`、`x-api-key` 和 Anthropic 工具格式转换，支持 SSE；聊天入口要求可用的文本模型。 |
| Venice 媒体请求 | 已实现 | 支持 image、inpaint、upscale、tts、asr、video、music、embedding、decision；按接口要求使用 JSON 或 multipart，并允许高级参数。视频和音乐采用 quote 后 queue 流程。 |
| 媒体保存与打开 | 已实现 | 输出保存到应用私有 `media` 目录；通过 FileProvider 或系统文件选择器访问，不申请共享存储权限。 |
| 角色与 Worldbook | 已实现 | 支持角色提示词、开场内容、全局 Persona、关键词/always Worldbook 条目和角色关联。字段覆盖以应用数据模型为准。 |
| 预设 | 已实现 | 保存系统提示词、温度、最大输出、摘要提示词、摘要 Provider ID 和摘要模型 ID；独立导入/导出保留这些字段。 |
| 记忆 | 已实现但有边界 | 支持 global/chat/character 范围和用户/Skill 保存；当前 `memory_search` 是本地词项匹配。语义 vector 记忆索引尚未集成。 |
| 上下文压缩 | 已实现但有边界 | 按摘要提示词和摘要 Provider/模型生成摘要，校验源消息 hash；摘要失败停止本次发送并保留旧数据；摘要来源失效时使用原文并重新校验预算。 |
| Skills 导入 | 已实现 | 支持 `SKILL.md` 或 HTTPS ZIP；限制输入/解压大小、条目数和文件路径，保留文本或受控的 base64 文件内容。导入后默认禁用且无权限。 |
| GitHub 写作 Skills | 已实现，待设备验收 | 7 个上游原版 Skill，附 MIT 许可证、固定 commit 链接和 SHA-256。按小说/言情分类，可在 Skills 页直接勾选当前会话；默认关闭且不自动授权工具。详见 THIRD_PARTY_SKILLS.md。 |
| Skills 工具 | 已实现 | 按能力归为 7 类：工作区列举、读取、写入，网页读取，一般 HTTPS 请求，记忆搜索/保存，Skill 文件读取。源码注册 8 个函数：`workspace_list`、`workspace_read`、`workspace_write`、`web_fetch`、`http_request`、`memory_search`、`memory_save`、`skill_read`。 |
| Skills 授权 | 已实现 | 文件读写、network、memory 分开授权；启用的 Skill 和所授权限共同决定可用工具。网络要求 HTTPS、不跟随重定向；Provider 认证头由应用注入。 |
| 工作区边界 | 已实现 | 所有工作区操作限制在应用私有 `filesDir/workspace`；路径规范化后检查边界，敏感文件名拒绝或隐藏，读写有大小和数量上限。 |
| 桌面脚本 | 明确不支持 | 不运行 Python、Shell、Node 或其他桌面脚本。Skill 内容可在授权后读取，脚本文件不会被执行。 |
| 导入/导出 | 已实现但有边界 | 支持备份、角色、Worldbook、聊天、预设和 Skill；备份 ZIP 含 `state.json`、`media/`、`workspace/`，恢复前确认，API Key 不进入备份。Skill 导出当前是 `SKILL.md` 文本，不是完整 ZIP 资源包。 |
| 数据存储 | 已实现 | Room 保存应用状态，较大的 JSON 通过 128 KiB 字符分块保存；媒体和工作区在应用私有目录。 |
| 隐私与网络 | 已实现 | Manifest 只声明 `INTERNET`；关闭明文流量。离线数据默认留在本机，模型推理和用户主动发起的工具请求发送到选择的云端 Provider。Provider 的服务端日志和保留策略由其自身控制。 |
| SillyTavern 兼容 | 部分支持 | 可处理应用支持的常见角色卡、Worldbook、聊天和预设数据；ST 宏、桌面插件和任意扩展运行时没有全兼容。 |

## 目录与参数细节

Venice 目录按十种 `type` 标签筛选，其中 `text` 是聊天模型，另外九类是媒体或专用模型类型。模型卡会显示 Provider 返回的能力 JSON；目录能展示某个类型不代表应用已经实现该类型的完整端到端适配。

媒体请求会保留模型需要的 JSON 高级参数。界面提供的常用字段包括尺寸、mask、voice、response format、speed、duration、duration_seconds 和 force_instrumental；ASR 还支持文件、数据 URL 或 base64 输入。Provider 返回的校验错误会阻止不完整请求继续发送。

## 验证入口

本文件只描述源码能力和限制。请在 [`VERIFICATION.md`](VERIFICATION.md) 中记录实际构建、测试输出、安装和设备验收证据；没有该文件中的证据时，不应把某项能力描述为已通过测试或设备验收。
