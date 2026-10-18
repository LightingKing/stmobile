# Pocket Tavern（口袋酒馆）

Pocket Tavern 是一个独立的 Android SillyTavern 风格应用。它使用自己的应用数据、工作区和 API Key 存储，不依赖桌面版 SillyTavern 的运行时。聊天推理通过用户配置的云端 Provider 完成；角色、预设、记忆、工作区文件和导入的资源保存在本机。

当前为 **0.1.0 候选版**：已有签名 APK 和真实 Venice 接口测试；本机模拟器故障导致 Android 安装/运行验收尚未完成。图片编辑、放大的真实请求未通过。详细结果和与设计方案的差异见 [验证记录](docs/VERIFICATION.md)。

## 当前可用范围

- Android 8.0（API 26）及以上；应用的 `minSdk` 为 26，目标 SDK 为 35。
- Venice 动态模型目录：从 `/models` 分页读取模型，并按 `text`、`image`、`inpaint`、`upscale`、`tts`、`asr`、`video`、`music`、`embedding`、`decision` 十类目录标签展示。目录中的原始能力 JSON、上下文长度、工具/视觉/推理能力和离线标记会保留在模型信息中。
- Venice 媒体调用：图片、局部重绘、放大、TTS、ASR、视频、音乐、embedding 和 decision 使用对应的 JSON 或 multipart 请求；模型特有的高级参数可以随请求传入，界面也提供常用参数模板。
- 文本聊天：支持 Venice/OpenAI 兼容接口和 Anthropic Messages 接口。Provider 的 Base URL 必须是 HTTPS，API Key 保存到 Android Keystore 保护的本地密钥存储中。
- 角色、系统提示词、全局 Persona、Worldbook、聊天预设、摘要预设和压缩回退均由应用本地数据模型管理。
- Skills 采用显式启用和权限授予流程；每个 Skill 的文件访问、网络和记忆权限都可以由用户审核。

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

## 聊天、角色、预设与记忆

角色可以保存名称、描述、人格/系统提示词、开场内容和 Worldbook 关联；聊天上下文会加入全局 Persona、角色提示词、命中的 Worldbook 条目、聊天记忆和附件提示。预设保存系统提示词、温度、最大输出、摘要提示词以及摘要 Provider/模型 ID，并可单独导入导出。

记忆分为全局、角色和聊天范围。模型可以通过受授权的 Skill 搜索记忆或保存聊天记忆，用户也可以在界面编辑和恢复摘要。上下文接近限制且有可压缩的旧轮次时，应用按当前预设生成摘要。摘要失败会停止本次发送，原文和旧摘要保留；编辑来源消息后旧摘要失效，重新使用原文并校验预算。当前 `memory_search` 是本地词项匹配；语义 vector 记忆索引尚未集成。

## Skills 与工作区

应用只在自己的沙盒工作区 `filesDir/workspace` 内读写文件。路径会做规范化和边界检查，敏感文件名（例如 `.env`、密钥、密码和 token 文件）会被拒绝或隐藏；网络请求要求 HTTPS 且不跟随重定向。认证仅能由应用注入当前会话选中的 Provider；Skill 不能选择其他已配置账户或自行设置认证头。一般公开 HTTPS API 可以直接调用。

导入 Skill 后，在“Skills”页启用并授予所需权限，再到会话选项中的“本会话 Skills”勾选它。当前聊天模型还必须支持工具调用。源码中的 `examples/story-continuity/SKILL.md` 可用于验证安装、工作区文件读写和聊天记忆。

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
