# 电脑写作服务

Android 0.1.52 起可选择“本机写作服务”平台。电脑替手机调用 Venice，Venice Key 只放在电脑上；手机继续管理原会话、角色卡、预设、Skills 和记忆。切换平台不会新建或清空会话。0.1.68 起手机能在“设置 → 电脑连接”查看服务状态，并把备份存到这台电脑；0.1.70（服务 0.3.0）起可以在模型页选用上游的任意文本模型，并在“电脑设置”页修改电脑上的设置。

## 当前提供什么

| 模型 | 一次普通回答 | 用途 |
| --- | --- | --- |
| `pocket-direct`（默认模型） | 1 次上游请求 | 用电脑上设定的默认模型直接回答 |
| `pocket-writer`（默认模型 + 审校建议） | 草稿 + 审校建议，默认保留原稿 | 试验性建议；当前未通过写作质量验收 |
| 上游的每个文本模型，例如 `llama-3.3-70b` | 1 次上游请求 | 原样转给上游；上下文、能力和价格按上游目录显示 |

模型列表由电脑向上游读取，缓存 10 分钟；上游暂时读不到时继续用上次的列表，至少保留两个默认模型入口。上游标明不支持思考的模型，不会收到电脑配置的思考参数。

## 手机上能改的电脑设置

“设置 → 电脑连接 → 电脑设置”通过 `GET/PATCH /v1/pocket/config` 读写 `config.json`，保存后立即生效，不用重启服务。只发送改动过的项，改动记录在 `server.log`。

| 设置 | config.json 字段 |
| --- | --- |
| 默认模型（须在上游目录里） | `upstreamModel` |
| 思考强度：默认 / 关闭 / 低 / 中 / 高 | `reasoningEffort` |
| 审校建议模式的审校 Skill，以及审校思考强度 | `reviewSkill`、`reviewReasoningEffort` |
| 按审校意见自动改正文（试验） | `autoRevise` |
| 附带 Venice 自带的系统提示 | `includeVeniceSystemPrompt` |
| 上游最长等待（0 表示不限） | `upstreamIdleTimeoutSec` |
| 电脑保留备份份数 | `backupKeep` |

上游地址、各类 Key、网关 Token、监听地址和端口只能在电脑上改。手机请求修改这些字段会被拒绝并提示“不能从手机修改”，这样即使网关 Token 泄露，别人也无法把 Venice Key 转到其他服务器。

两个默认模型入口的正文使用相同上游模型与基础参数。默认审校模式是 2 次请求；只有在电脑配置中明确启用 `autoRevise: true` 才允许再修订一次，最多 3 次。首轮真实对照发现误判和修订退步，因此本机保持 `false`。审校采用 `reviewReasoningEffort: none`，避免把预算全花在思考上，正文思考设置不变。额外审校会增加输入 token、输出 token 和等待时间。没有循环自评、无限修订或自动付费重试。摘要、压缩、记忆重建仍只调用一次，不走改文流程。草稿要求调用手机工具时，先返回工具调用，由 App 继续原有工具流程。

审校使用仓库已固定 commit、通过 SHA-256 校验的 `ss-stop-slop` 原文，也可在私密配置中设为 `none`。原来的 `sh-shuorenhua` 已在 App 0.1.65 移出内置目录；旧配置里写着它时自动改用 `ss-stop-slop`，服务照常启动。上游原文未修改。星数和协议测试不代表写作质量改善，实际效果见对照记录。

原会话传来的完整 messages 是事实依据。服务保存请求、草稿、审校、修订、推理和用量为 JSON / Markdown，方便 Obsidian 阅读，但**这些归档不会自动成为剧情记忆**，避免把失败草稿写成已发生事件。手机的现有记忆与摘要仍然有效。

## 安装与开机自启

需要 Node.js 22+，没有 npm 依赖需要安装。脚本都要用 Windows 自带的 **Windows PowerShell**（`powershell.exe`）运行。Microsoft Store 版的 PowerShell 7（`pwsh`）和 Codex 这类商店应用一样，会把 AppData 写到自己的包目录里，开机任务读不到那份配置；脚本检测到这种情况会直接拒绝运行。

第一次在**管理员** Windows PowerShell 中运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\install-autostart.ps1 -Lan
```

- 注册计划任务 `PocketTavernWritingServer`：当前用户登录时启动，普通权限运行，不需要保存密码。
- 任务运行 `run.ps1`。它在前台运行服务，服务崩溃后 5 秒重启，连续失败时间隔逐步加长，最长 60 秒；每次启动前按当前局域网地址更新 `config.json`，DHCP 换 IP 后重启即可恢复。
- `-Lan` 写入防火墙规则 `PocketTavernWritingLan`：只放行本地子网访问 8791 端口和 `node.exe`，不绑定具体 IP，只用于“专用”网络。如果 Windows 把家里的网络标成了“公用”，脚本会提示改成专用的命令；确实要在公用网络下开放时加 `-AllowPublicNetwork`。
- 服务总是监听 `127.0.0.1:8791`，供本机的 NPS 客户端或 Tailscale 转发；局域网模式下还会监听这台电脑的内网 IP。

其他脚本：

| 脚本 | 作用 |
| --- | --- |
| `start.ps1 [-Lan] [-Tailnet]` | 已装计划任务时启动它，否则在后台运行 `run.ps1`；写出私密的 `手机连接.txt` |
| `stop.ps1` | 请求停止：服务让正在生成的请求最多再跑 60 秒，然后退出；75 秒后仍未退出才强制结束 |
| `uninstall-autostart.ps1 [-RemoveFirewallRule]` | 停止服务并删除计划任务；私密数据、记录和备份保留 |
| `configure-venice.ps1 [-KeyFile 路径]` | 保存或更换 Venice Key（不填路径时隐藏输入） |

首次启动生成 `%LOCALAPPDATA%\PocketTavernServer` 私密目录（只允许当前用户和 SYSTEM 访问）和随机网关 Token。不要把 Venice Key 填进手机的“本机写作服务”平台；手机使用 `手机连接.txt` 中的网关 Token。私密目录不进入源码包或 APK。日志在私密目录的 `server.log`（超过 5 MB 时保留一份 `.1`）和 `supervisor.log`。

## 手机连接

1. 平台页新增平台，协议选“本机写作服务（OpenAI-compatible）”。
2. Base URL 填 `手机连接.txt` 里的地址，局域网目前是 `http://192.168.1.22:8791/v1`。
3. API Key 填 `手机连接.txt` 中的网关 Token，保存并刷新模型列表。
4. 在原会话选择这个平台和 `pocket-direct` 进行正常聊天。`pocket-writer` 目前仅试用审校建议，会额外计费，建议显示在思考详情中，正文保留原稿。
5. “设置 → 电脑连接”可检查连接、立即备份、开启离开 App 时自动备份，以及从电脑恢复。

局域网使用 HTTP，只用于可信内网。App 只对“本机写作服务”协议放行 RFC1918 字面 IPv4 的 HTTP 地址，禁止 URL 内的账号、查询参数和跳转；其他情况必须是 HTTPS。

## 从外网访问（NPS 或 Tailscale）

服务本身只说 HTTP，外网访问必须由隧道提供 HTTPS，手机端也只接受 HTTPS 的外网地址。

- **NPS**：在 NPS 管理后台给本机客户端添加“域名解析”（host）：域名填你的子域名，协议选 HTTPS（或 all），**目标填 `127.0.0.1:8791`**，并为该域名配置证书。`nps.conf` 中 `https_just_proxy=true` 时 NPS 只转发 TLS、不解密，请改为 `false` 并让 NPS 用证书终止 HTTPS，否则会把 TLS 直接转给只说 HTTP 的服务。建议同时开启 `http_add_origin_header=true`，服务据此区分不同来源的客户端。手机 Base URL 填 `https://你的域名/v1`。不要用普通 TCP 端口映射暴露 8791，那样 Token 会以明文在公网传输。
- **Nginx 在 NPS 前面**（Nginx 用证书终止 443，再转给 NPS 的 HTTP 端口）：Nginx 默认只收 1 MB 的请求体，备份和长会话都会被它直接回 `413`。在该域名的 `server { }` 中加 `client_max_body_size 160m;`（备份最大 128 MB、聊天请求最大 32 MB），并加 `proxy_read_timeout 600s;`、`proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`。服务的 SSE 响应带 `X-Accel-Buffering: no`，逐字输出不会被 Nginx 缓冲；实测经 Nginx 和 NPS 后，每秒一段的输出按秒到达手机。
- **Tailscale**：`start.ps1 -Tailnet` 用 `tailscale serve` 在 8443 端口提供 HTTPS，手机和电脑登录同一 Tailscale 网络。

公网暴露时的保护：所有接口（`/healthz` 除外）都要网关 Token；同一来源 10 分钟内 10 次错误 Token 后封锁 15 分钟，被封期间不再校验它发来的 Token（只计“带了错误 Token”的请求，扫描器不带 Token 的请求只返回 401，不会封住你）。来源按连接地址判断；连接来自本机隧道时，取 `X-Forwarded-For` 的最后一跳。NPS 没有开启 `http_add_origin_header` 时，所有外网请求共用一个来源。

## 生成、超时与并发

- **逐字转发**：`pocket-direct`，以及未开启自动修订的 `pocket-writer`，会把上游的正文和思考边生成边发给手机。`pocket-writer` 的审校结论和审校思考在最后一帧补发，不会重复已发送的正文。开启 `autoRevise` 时最终文字可能是修订稿，因此仍等全部阶段结束后一次发送。
- **上游空闲超时**：上游连续 `upstreamIdleTimeoutSec` 秒（默认 900，即 15 分钟；填 `0` 不设限）没有任何数据就中止本次请求，已收到的正文和思考作为 partial 发回手机并归档。上下文很长时，上游在第一个字之前可能沉默好几分钟，因此默认值放得很宽；只要还在输出就不会被打断，整轮生成没有总时限。等待期间服务每 15 秒给手机发一次心跳，手机、Nginx、NPS 的连接不会因此断开。
- **同一会话再次请求**：同一会话、同一用途（聊天 / 摘要 / 压缩 / 重建）的新请求会取消仍在运行的旧请求，不再返回 409；聊天回复和摘要可以同时进行。手机断开时请求立即释放；手机不读取数据导致积压超过 8 MB 时也会中止。
- **心跳**：每个阶段运行时每 15 秒发送一次 SSE 注释，避免中间设备断开空闲连接。

## 手机备份

- `POST /v1/backups`：请求体为 App 生成的备份 ZIP（`application/zip`），最大 128 MB，与 App 的恢复上限一致。服务边接收边写入临时文件并计算 SHA-256，完整收到后才出现在列表中；内容与最新一份相同时不再保存，返回已有记录和 `duplicate: true`。磁盘剩余空间不足（备份大小 + 512 MB）时拒绝。同一时间只接收一个上传，60 秒没有数据的上传会被中止。
- `GET /v1/backups`：列出备份（时间、大小、SHA-256、设备名、App 版本、原因：手动 / 自动 / 恢复前自动保存）。`GET /v1/backups/<id>` 下载，响应头 `X-Pocket-Sha256` 供手机校验；`DELETE /v1/backups/<id>` 删除。
- 备份存放在私密目录的 `backups/`，按时间只保留最近 `backupKeep` 份（默认 20）。

## 参数与输出保留

- `%LOCALAPPDATA%\PocketTavernServer\config.json` 设置真实上游模型、窗口、审校 Skill、思考强度、`upstreamIdleTimeoutSec`（`0` 或 30–3600）和 `backupKeep`（1–200）；在电脑上手改后运行 `stop.ps1` 再 `start.ps1`，从手机“电脑设置”改的项立即生效。默认模型为 `deepseek-v4-1-flash-1017`，真实目录窗口为 `1000000`。
- 默认不新增输出预算，不按本地接收字符数或思考量截断。手机明确启用预算才向上游传预算；服务端不能移除模型自身限制。
- 审校或修订失败时返回已收到的草稿，并在 `pocket_tavern_notice` 中标注服务提示，不自动重试，提示不会写进正文。首轮流失败时先补发未发送的正文/推理，再发送错误，手机可保存 partial。
- 额外阶段会增加上下文；容量估算不足时跳过该付费阶段并提示，不切掉原文。
- 手机的 DeepSeek 专属参数面板仅用于直接 Venice 平台；本服务的思考参数由电脑配置控制，角色、预设正文和记忆仍来自原会话。

## 查看记录与验证

- 可把私密目录 `chats` 作为 Obsidian 文件库打开；每次请求一份 Markdown 和对应 JSON。
- `GET /healthz`：最小存活状态，不需要 Token。`ready` 表示有凭据配置，不代表凭据已经通过上游验证。
- 携带网关 Bearer Token 后：`GET /v1/pocket/status` 返回版本、运行时长、监听地址、上游主机、默认模型、可选模型数、审校 Skill、进行中的请求数和备份统计，不含任何密钥；`GET /v1/models` 获取两个默认模型入口和上游文本模型（`?refresh=true` 跳过 10 分钟缓存）；`GET/PATCH /v1/pocket/config` 读写手机可改的设置；`GET /v1/runs?chat_id=<会话ID>` 只返回诊断元数据，显式增加 `include_content=true` 才返回原文。
- `node --test test/*.test.mjs`（或 `npm test`）运行本地协议、超时、备份与限流测试，不产生模型费用。
- `npm run evaluate` 只查看对照计划；加 `-- --run` 才调用 Venice。每次执行有 6 次尝试上限、每次 10000 tokens，生成随机 A/B 文稿与调用账本；不要为了重试反复运行。
- 本次实测与失败样例总结见 [EVALUATION.md](docs/EVALUATION.md)。这轮 6 次已用完，进一步真实测试须另行约定预算。

## 候选桌面工具工作流

`src/opencode.mjs` 和 [OPENCODE.md](docs/OPENCODE.md) 是 OpenCode 2 的独立候选适配层，默认关闭，尚未接入手机或部署常驻服务。mock 契约测试不能证明真实文件工具、oh-story 插件及 Venice 写作效果已验收。本版本实际开放的模型只有上表两项。
