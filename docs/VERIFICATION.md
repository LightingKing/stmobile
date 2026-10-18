# 口袋酒馆 0.1.0 验证记录

验证日期：2026-10-18（Asia/Shanghai）。本文件区分源码实现、JVM/接口验证、APK 构建和 Android 设备验收。构建成功不能代替手机端验收。

## 交付与构建

在 Windows + JDK 17 + Android SDK 35 上执行 `package.ps1 -Offline -AndroidMirror https://maven.aliyun.com/repository/google`，构建任务包含 debug APK、release APK、Android instrumentation APK、单元测试和 lint。

本地测试 **41 项通过，0 失败**；另有 7 项需要显式开启的真实 API 测试在普通构建中跳过，不能把它们算作通过。分组：DataTest 8、EngineTest 12、ProviderClientTest 12、MediaClientTest 9。真实 API 测试结果另列于下方。

`lintDebug` 和 release lint 均完成：**0 错误，12 条警告**，涉及 SDK/依赖更新、KAPT、KTX 使用建议和 Android 12 备份规则建议。Manifest 已关闭 `allowBackup` 和明文流量；新系统设备迁移行为尚未实测。

发布文件为 `dist/PocketTavern-0.1.0.apk`；使用本机独立的个人 RSA 签名，`apksigner verify` 验证 APK Signature Scheme v2 成功。APK 的最低版本为 Android 8.0（API 26），不是应用商店发布包。最终 SHA-256 见 `dist/SHA256SUMS.txt`，源码包为 `dist/PocketTavern-0.1.0-source.zip`。

源码包含 Gradle Wrapper、构建/签名/源码打包脚本；签名私钥、API Key、构建缓存和本地媒体测试输出不进入源码压缩包。签名材料保存在本机用户目录的 `.android/pocket-tavern-signing/`，后续保持同一签名才能覆盖安装升级。

## 真实 Venice API 验证

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
