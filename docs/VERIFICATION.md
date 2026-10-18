# 口袋酒馆验证记录

验证日期：2026-10-18（Asia/Shanghai）。本文件区分源码实现、JVM/接口验证、APK 构建和 Android 设备验收。构建成功不能代替手机端验收。

## 0.1.1 GitHub Skills 更新

本次加入 7 个 GitHub 上已有的写作 Skill：5 个来自 wordflowlab/novel-writer-skills、2 个来自 SkillMedev/skills。固定提交、原文链接、MIT 许可证及逐文件 SHA-256 见 [来源清单](THIRD_PARTY_SKILLS.md)。重新获取上游固定提交的 7 份 SKILL.md 和 2 份 LICENSE 后，全部与打包原文哈希一致。内置精选库不包含此前临时编写的 11 个条目。

Skills 页提供分类、当前会话勾选、全局开关和原文/来源/许可证详情。首次升级补入尚未见过的条目；默认禁用且无权限。删除、已有自定义条目和用户修改会保留；未选中 Skill 不提供正文或工具权限。设置保存保留迁移标记，避免恢复备份后保存旧界面快照导致已删除条目重现。

本次本地单元测试 **50 项通过，0 失败**；另有 7 项真实 API 测试按配置跳过，没有新增计费 API 调用。分组：BuiltinSkillsTest 8、DataTest 8、EngineTest 13、ProviderClientTest 12、MediaClientTest 9。新增覆盖真实内置资源解析、原文 hash 拒绝、默认关闭、旧状态迁移、重复加载与删除保留、500 条容量上限、会话选择与持久化后的上下文变化，以及未勾选时禁止工具实际写入文件。

`package.ps1 -Offline -AndroidMirror https://maven.aliyun.com/repository/google` 构建成功，包含 debug/release APK、instrumentation APK、JVM 测试与 lint。Debug lint 为 0 错误、12 条警告；release vital lint 通过。最终 `dist/PocketTavern-0.1.1.apk` 为 11,630,215 字节，版本 code 2、name 0.1.1，SHA-256 为 `4BD4F513DF84A835D6E0DB3B20F37BE310C7168C43B9960266F0BAB6FE79CC1D`。签名 v2 验证通过，证书与 0.1.0 相同，具备同签名升级条件；覆盖安装过程未实测。

从最终 APK 直接读取并校验全部 10 个写作资源文件（7 份 SKILL.md、2 份许可证、1 份 catalog），与源码原始字节完全一致；没有遗留临时自编条目。源码包为 `dist/PocketTavern-0.1.1-source.zip`，包含 Git 属性规则以保留上游文件字节，便于跨平台检出后继续通过原文校验。

本次没有 Android 安装、界面操作或模型写作质量的实测；此前设备障碍仍见下方。JVM 测试证明解析和选择逻辑，不代表已完成手机端体验验收。0.1.0 的真实接口结果仅作为历史基线，不视为 0.1.1 重新执行的验收。

## 0.1.0 交付与构建基线

在 Windows + JDK 17 + Android SDK 35 上执行 `package.ps1 -Offline -AndroidMirror https://maven.aliyun.com/repository/google`，构建任务包含 debug APK、release APK、Android instrumentation APK、单元测试和 lint。

本地测试 **41 项通过，0 失败**；另有 7 项需要显式开启的真实 API 测试在普通构建中跳过，不能把它们算作通过。分组：DataTest 8、EngineTest 12、ProviderClientTest 12、MediaClientTest 9。真实 API 测试结果另列于下方。

`lintDebug` 和 release lint 均完成：**0 错误，12 条警告**，涉及 SDK/依赖更新、KAPT、KTX 使用建议和 Android 12 备份规则建议。Manifest 已关闭 `allowBackup` 和明文流量；新系统设备迁移行为尚未实测。

发布文件为 `dist/PocketTavern-0.1.0.apk`；使用本机独立的个人 RSA 签名，`apksigner verify` 验证 APK Signature Scheme v2 成功。APK 的最低版本为 Android 8.0（API 26），不是应用商店发布包。最终 SHA-256 见 `dist/SHA256SUMS.txt`，源码包为 `dist/PocketTavern-0.1.0-source.zip`。

源码包含 Gradle Wrapper、构建/签名/源码打包脚本；签名私钥、API Key、构建缓存和本地媒体测试输出不进入源码压缩包。签名材料保存在本机用户目录的 `.android/pocket-tavern-signing/`，后续保持同一签名才能覆盖安装升级。

## 0.1.0 真实 Venice API 验证

使用本机已有的环境凭据直接请求 Venice，没有把凭据写入源码、APK 或测试报告。以下调用运行于 JVM，使用应用实际的 ProviderClient/MediaClient；不是模拟服务器，但也不是 Android 设备上的测试。

动态目录请求 `/models?type=all` 成功，读取到 **393** 个目录项：text 135、image 45、inpaint 29、upscale 1、tts 11、asr 5、video 139、music 18、embedding 9、decision 1。这是当时的目录快照；模型可用性和数量会变化。未逐一执行全部 393 个模型。

| 类型 | 本次样本 | 已观察结果 |
| --- | --- | --- |
| 文本 / 工具 | `mistral-small-3-2-24b-instruct` | 普通聊天、函数调用、工具结果回传后的回复均成功 |
| 图片 | `venice-sd35` | 生成 PNG，425,519 字节 |
| TTS | `tts-kokoro` | 生成 WAV，146,444 字节 |
| ASR | `nvidia/parakeet-tdt-0.6b-v3` | 对上述 WAV 转写，获得非空文本 |
| Embedding | `text-embedding-bge-m3` | 返回 1,024 维向量；完整 JSON 保存为文件，21,874 字节 |
| Decision | `jev-latest` | 返回结构化 answers |
| 音乐 | `ace-step-15` | 报价、提交、按队列 ID 查询、下载均成功；FLAC 3,640,823 字节 |
| 视频 | `wan-3-0-text-to-video` | 报价、提交、按队列 ID 查询、下载均成功；MP4 845,253 字节 |
| 放大 | `upscaler` | 请求等待 120 秒后 `SocketTimeoutException`，未收到输出，未通过 |
| 图片编辑 | `firered-image-edit` | 请求出现 HTTP/2 `stream was reset: CANCEL`，未收到输出，未通过；后续组合测试在放大阶段停止，没有重复发送编辑请求 |

音乐/视频测试在提交前验证单任务报价不超过 0.30 美元。已提交任务通过原队列 ID 查询，没有为轮询重复提交任务。生成文件已验证落盘和文件格式；没有进行人工视听质量评审。其他平台的 OpenAI 兼容与 Anthropic 协议通过本地 fixture 验证，尚未进行其真实账号调用。

放大和图片编辑的 JSON/base64 请求格式已对照官方 [Upscale](https://docs.venice.ai/api-reference/endpoint/image/upscale) 与 [Edit](https://docs.venice.ai/api-reference/endpoint/image/edit) 文档。核对后去掉了 upscale 请求中多余的 `model` 字段，并增加参数测试；修正后没有重新发起计费请求，因此真实放大仍未通过验收。现有错误不能确定发生于服务端还是网络链路。应用不自动重试付费生成请求，状态未知时由用户判断是否重新提交。

本机详细证据保存在被 Git 忽略的 `evidence/`、`app/build/test-results/` 和 `app/build/live-media/`，不随源码包分发；本文件保留不含凭据的结果摘要。

## Android 设备验收

**未完成安装、启动和原生界面验收。** 本机专用 `PocketTavern_API35` AVD 无法启动：硬件加速报 `WHPX Unexpected VP exit code 4`；软件渲染尝试也未进入可用系统。检查发现 Windows Hypervisor Platform 处于 disabled/staged 状态。更换已安装的模拟器版本仍复现故障；没有修改主机虚拟化配置或重启电脑。

另有已连接设备的 ADB shell 返回 `error: closed`，没有在该设备上安装或改动应用。Android instrumentation APK 的编译与其实际运行分开记录；Room 大数据分块、Keystore、系统文件选择器、软键盘布局、后台恢复仍需可用的 Android 设备验证。

可在专用测试设备上运行：

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

这会安装并运行测试，测试会创建会话和测试记忆，适用于干净的测试设备。正式使用前至少验证：首次配置、真实流式聊天、工具文件读写、导入角色/Skill、压缩与恢复原文、杀进程重开、备份导出与恢复、媒体保存与打开。

## 已知能力边界

- 记忆目前使用本地词项匹配，未集成语义向量索引。
- Skills 执行应用提供的手机工具，不能运行桌面 Shell/Python/Node 脚本。
- 自动注入认证的 `http_request` 限于当前会话 Provider 的配置地址；一般公开 HTTPS API 可以直接调用，不提供独立的任意 API 凭据库。
- SillyTavern 角色卡、聊天、Worldbook、预设支持部分常见字段；不声称完整兼容 ST 宏、插件和所有预设格式。
- 媒体适配器目前针对 Venice；其他平台支持目录与文本协议，媒体需要增加对应适配器。
- 应用状态 JSON 有 64 MiB 上限；附件会占用状态空间。备份归档大小、解压条目、网络响应和工具输入也有明确上限。
- 同名 Skill 导入会新增独立记录，没有同名升级关系或版本回退管理。
- 当前会话选中 Skill 的正文会全部进入上下文；references/assets 按需读取，尚未实现正文的自动发现/按需激活。
- 专用媒体生成入口在“模型”页，尚未注册为 Skills 工具；Skill 的 `http_request` 可访问授权的 API，但不等同于专用媒体生成工具。
- 文件工具限于应用工作区；用户通过系统选择器添加的附件参与聊天输入，尚未作为独立的授权 URI 文件工具提供给 Skill。

完整实现范围见 [CAPABILITIES.md](CAPABILITIES.md)，首次使用步骤见 [README.md](../README.md)。
