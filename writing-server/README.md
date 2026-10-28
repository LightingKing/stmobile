# 电脑写作服务

Android 0.1.52 起可选择“本机写作服务”平台。电脑通过 Venice 调用 DeepSeek，手机继续管理原会话、角色卡、预设、Skills 和记忆。切换平台不会新建或清空会话。

## 当前提供什么

| 模型 | 一次普通回答 | 用途 |
| --- | --- | --- |
| `pocket-direct` | 1 次上游请求 | 与原模型作对照 |
| `pocket-writer` | 草稿 + 审校建议，默认保留原稿 | 试验性建议；当前未通过写作质量验收 |

两种模式的正文使用相同上游模型与基础参数。默认审校模式是 2 次请求；只有在电脑配置中明确启用 `autoRevise: true` 才允许再修订一次，最多 3 次。首轮真实对照发现误判和修订退步，因此本机保持 `false`。审校采用 `reviewReasoningEffort: none`，避免把预算全花在思考上，正文思考设置不变。额外审校会增加输入 token、输出 token 和等待时间。没有循环自评、无限修订或自动付费重试。摘要、压缩、记忆重建仍只调用一次，不走改文流程。草稿要求调用手机工具时，先返回工具调用，由 App 继续原有工具流程。

当前审校使用仓库已固定 commit、通过 SHA-256 校验的 `sh-shuorenhua` 原文；也可在私密配置中选择 `ss-stop-slop` 或 `none`。上游原文未修改。星数和协议测试不代表写作质量改善，实际效果见对照记录。

原会话传来的完整 messages 是事实依据。服务保存请求、草稿、审校、修订、推理和用量为 JSON / Markdown，方便 Obsidian 阅读，但**这些归档不会自动成为剧情记忆**，避免把失败草稿写成已发生事件。手机的现有记忆与摘要仍然有效。

## 在这台 Windows 电脑启动

需要 Node.js 22+。没有 npm 依赖需要安装。

```powershell
pwsh -NoProfile -File .\start.ps1 -Lan
```

`-Lan` 选择有默认网关的内网 IPv4，只监听该 IP。Windows 防火墙仅允许本地子网访问该 Node 端口。电脑必须开机，手机连接相同局域网。IP 变化后在服务空闲时停止、重新启动，并更新手机地址。

首次启动生成 `%LOCALAPPDATA%\PocketTavernServer` 私密目录和随机网关 Token。当前电脑已配置 Venice Key；以后更换凭据可运行：

```powershell
pwsh -NoProfile -File .\configure-venice.ps1 -KeyFile C:\你的私密文件.txt
```

不要把 Venice Key 填进手机的“本机写作服务”平台。手机使用 `手机连接.txt` 中的网关 Token。私密目录不进入源码包或 APK；Windows 打包应用可能把 AppData 路径重定向到实际存储卷。

## 手机连接

1. 覆盖安装 0.1.52 APK，保留现有记录。
2. 平台页新增平台，协议选“本机写作服务（OpenAI-compatible）”。
3. Base URL 填启动输出的地址，本机目前是 `http://192.168.1.22:8791/v1`。
4. API Key 填私密文件 `手机连接.txt` 中的网关 Token，保存并刷新模型列表。
5. 在原会话选择这个平台和 `pocket-direct` 进行正常聊天。`pocket-writer` 目前仅试用审校建议，会额外计费，建议显示在思考详情中，正文保留原稿。

局域网调试使用 HTTP，因此只用于可信内网。公网和普通模型平台仍要求 HTTPS。App 仅放行本机写作协议的 RFC1918 字面 IPv4，禁止 URL 内的账号、查询参数和跳转。之后需要外网使用时，可改成回环监听并运行 `start.ps1 -Tailnet`，手机和电脑加入同一 Tailscale 网络；本次未启用。

## 参数与输出保留

- `%LOCALAPPDATA%\PocketTavernServer\config.json` 设置真实上游模型、窗口、审校 Skill 和思考强度；改完后在空闲时重启。默认模型为 `deepseek-v4-1-flash-1017`，真实目录窗口为 `1000000`。
- 默认不新增输出预算，不按本地接收字符数或思考量截断。手机明确启用预算才向上游传预算；服务端不能移除模型自身限制。
- 没有整轮生成时限。上游使用 SSE，接收全部已返回内容。用户取消或网络断开时中止该次请求并保存已收到的内容；服务或电脑被强制关闭时不能保证正在缓冲的增量已落盘。
- 审校完成前，App 暂不逐字展示草稿，避免把草稿和最终稿混写；期间每 15 秒发送心跳。完成后返回原稿、审校建议和各阶段推理；只有显式开启自动修订才可能替换正文。
- 审校或修订失败时返回已收到的草稿，并明确标注服务提示，不自动重试。首轮流失败先发送已接收正文/推理，再发送错误，手机可保存 partial。
- 额外阶段会增加上下文；容量估算不足时跳过该付费阶段并提示，不切掉原文。
- 手机的 DeepSeek 专属参数面板仅用于直接 Venice 平台；本服务的思考参数由电脑配置控制，角色、预设正文和记忆仍来自原会话。

## 查看记录与验证

- 可把私密目录 `chats` 作为 Obsidian 文件库打开；每次请求一份 Markdown 和对应 JSON。
- `GET /healthz`：最小存活状态。`ready` 表示有凭据配置，不代表凭据已经通过上游验证。
- 携带网关 Bearer Token 后，`GET /v1/models` 获取两个模式；`GET /v1/runs?chat_id=<会话ID>` 只返回诊断元数据，显式增加 `include_content=true` 才返回原文。
- `npm test` 运行本地协议与归档测试，不产生模型费用。
- `npm run evaluate` 只查看对照计划；加 `-- --run` 才调用 Venice。每次执行有 6 次尝试上限、每次 10000 tokens，生成随机 A/B 文稿与调用账本；不要为了重试反复运行。
- 本次实测与失败样例总结见 [EVALUATION.md](docs/EVALUATION.md)。这轮 6 次已用完，进一步真实测试须另行约定预算。

停止空闲服务：`pwsh -NoProfile -File .\stop.ps1`。如不再需要局域网访问，可移除仅本服务使用的防火墙规则 `PocketTavernWritingLan`。当前没有设置开机自启。

## 候选桌面工具工作流

`src/opencode.mjs` 和 [OPENCODE.md](docs/OPENCODE.md) 是 OpenCode 2 的独立候选适配层，默认关闭，尚未接入手机或部署常驻服务。mock 契约测试不能证明真实文件工具、oh-story 插件及 Venice 写作效果已验收。本版本实际开放的模型只有上表两项。
