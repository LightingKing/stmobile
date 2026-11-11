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

## 电脑上的管理网页

服务 0.6.0 起，在这台电脑的浏览器打开 **http://127.0.0.1:8792** 可以看到：

- **概览**：运行状态、版本、运行时长、默认模型、可选模型数、正在处理的请求、上游最长等待、监听地址。
- **连接**：局域网、本机和远程地址；网关 Token 默认隐藏，可以显示或复制；远程地址（NPS 域名）可以在这里填写。
- **最近的请求**：时间、模型、用途、状态、耗时、tokens 和备注；点一行查看这次请求的完整记录。
- **电脑设置**：与手机“电脑设置”页相同，保存后立即生效。
- **手机备份**：列表、下载、删除。
- **表情包**：缩略图、启用/停用、删除。
- **DSH 引擎**：是否可用、Python 版本。
- **日志**：服务日志和崩溃重启记录。

管理网页单独监听 `127.0.0.1:8792`（`config.json` 里的 `adminPort`，默认是服务端口加 1），不需要输入 Token，只能在这台电脑上打开。NPS 只转发 8791，所以外网访问不到这个页面。为了防止其他网站借你的浏览器操作它：请求的 Host 必须是 `127.0.0.1` 或 `localhost` 加这个端口，用来挡住 DNS 重绑定；来自其他网站的 Origin 一律拒绝；所有修改操作都必须带页面自己的请求头，跨站页面无法发出这种请求。页面带内容安全策略，只加载本站的脚本、样式和图片。

## 在家自动走内网

手机上的“本机写作服务”平台可以同时填两个地址：Base URL 填远程地址（`https://域名/v1`），“家里的局域网地址”填 `http://192.168.1.22:8791/v1`。App 每次请求前先判断该走哪条：

1. 手机向局域网地址的 `/v1/pocket/hello` 发一个随机数，电脑用网关 Token 对它做 HMAC-SHA256 签名后返回。手机用自己保存的 Token 核对，结果一致才走内网。这一步不发送 Token。
2. 这个接口只在局域网监听上回答；经本机回环地址或 NPS 进来的请求一律返回 404。所以别人无法通过外网拿到签名，再到另一个网络里冒充你的电脑。
3. 判断结果会缓存：走内网的结果保留 5 分钟，走远程的保留 1 分钟。手机网络一变化就重新判断；请求在网络层失败时也会重新判断，下次改走远程。
4. 手机上“电脑连接”卡片会显示当前线路。

## DSH 成人互动引擎

DSH 成人互动（`at-adult-tension-original`，上游 `daha1216/dsh-adult-tension`）用一套本地 Python 引擎记录世界、人物、时间、事实、边界和存档，它的 SKILL.md 要求模型在终端里运行 `adult_tension.py`。手机跑不了，所以 0.1.72（服务 0.5.0）起由这台电脑运行：

- **代码来源**：`vendor/adult-tension/` 只收录上游固定提交 `26641ebe` 的 `scripts/` 和 `runtime/`（51 个 Python 文件），`MANIFEST.json` 记下每个文件的 git blob 哈希，每次启动都会校验。SKILL.md、`references/` 和 `content/`（211 个世界）直接用 App 收录的同一份，在这个提交下逐字节一致。上游没有声明许可证，这里只作个人使用。
- **安全检查**：收录前查过源码，只用 Python 标准库，不联网，不启动其他程序，没有 `exec`/`eval`。数据只写在 `--data-dir` 指定的目录里，唯一例外是导出、导入存档可以指定任意路径，服务会拒绝这两个命令里的 `path` 字段。引擎的开发自检命令（`verify-content`、`smoke`）不开放。调用 Python 时只传最少的环境变量，Venice Key 等变量不会传进去。
- **运行方式**：第一次调用时，把引擎和 App 的 Skill 文件拼成私密目录下的 `dsh/skill/`，存档放在 `dsh/data/`。`POST /v1/pocket/dsh`（`{"command", "input"}`）按顺序执行，每次最多 60 秒，返回引擎原样的 JSON（`ok`、`data`、`error`）。Python 默认依次找 `python`、`py -3`、`python3`，要求 3.10 及以上；也可以在 `config.json` 里写 `pythonPath`（只能在电脑上改）。`/v1/pocket/status` 的 `dsh` 字段显示引擎是否可用和 Python 版本。
- **手机端**：只有会话使用电脑写作服务、模型支持工具、并且勾选了 DSH 成人互动时，模型才会拿到 `dsh_runtime` 工具，同时收到一句说明：SKILL.md 里在终端运行引擎的步骤，在这里改用这个工具。其他平台上，这个 Skill 仍然只用文字规则。0.1.72–0.1.76 的 App 把 Skill ID 写错了，这个工具实际上一直没有出现；0.1.77 已修正。

## 联网搜索（Agent Reach）

0.1.77（服务 0.7.0）起，电脑会话可以联网；从 App 0.1.79 起，只要会话使用电脑写作服务、模型支持工具，就会自动带上联网工具，不需要再勾选 Skill。实际查询由这台电脑上的 [Agent Reach](https://github.com/Panniantong/Agent-Reach)（MIT）完成：

| 渠道 | 能做什么 | 实际由谁完成 |
| --- | --- | --- |
| `web` | 搜索、读网页正文 | 搜索：Exa 的免费公开接口；读网页：先 Jina Reader，失败再用 Exa。两者都不需要密钥 |
| `bilibili` | 搜索、视频信息、热门 | `bili-cli`，不登录 |
| `youtube` | 搜索、视频信息、字幕 | `yt-dlp`；字幕可能被 YouTube 限流 |
| `github` | 搜仓库、读仓库或 issue | GitHub 公开 API；这台电脑当前连不上，会返回 503 |
| `v2ex` | 热帖、读帖子 | V2EX 公开接口 |

- **安装**：在本目录运行 `install-agent-reach.ps1`。它的做法：
  - 用 Python 3.10+ 在 `runtime/agent-reach/` 建一个独立虚拟环境，从 GitHub 安装固定提交 `c70ae6da`（1.6.1）。PyPI 上同名的 `agent-reach` 是另一个项目，不用。
  - Agent Reach 的数据目录设在 `runtime/agent-reach-home/`（通过 `HOME`），再添加 B 站工具。
  - `runtime/` 不进 git。
- **没做的事**：
  - 不运行 Agent Reach 自己的 `setup`。它会把自己的 Skill 链接进本机所有 AI 工具的目录（Claude Code、Codex、Cursor 等）。
  - 需要登录的渠道（小红书、Twitter、Reddit 等）不开放。这类渠道会复制 Chrome 里的网站登录，或打开专用浏览器。
- **安全检查**：收录前读过源码。
  - 只访问各平台的公开接口，以及 Exa、Jina；读网页前会拒绝本机、局域网和内网地址。
  - 服务调用它时只传最少的环境变量，Venice Key 不会传进去。
- **调用方式**：`POST /v1/pocket/reach`，请求体为 `{"channel", "action", "query", "target", "limit"}`。
  - 服务只接受上表里的渠道和动作，自己拼出固定的命令行，模型给的内容只作为参数。
  - `read` 不指定渠道时，按链接选：B 站、YouTube、GitHub、V2EX 的链接用对应渠道，其余用 `web`。
  - 每次最多 75 秒，最多 2 个同时进行。
  - 返回结果去掉原始数据：搜索每条正文最多 600 字，读网页最多 1.2 万字，整体不超过 4 万字。
  - `/v1/pocket/status` 的 `reach` 字段和管理网页显示是否可用。
- **隐私**：搜索词和要读的链接会发给 Exa、Jina 或对应平台。
- **不读浏览器登录**：Agent Reach 自带的 bili-cli 里装有 `browser_cookie3`，没有保存的登录时会尝试读取本机 Chrome、Edge、Firefox 里的 B 站 Cookie。服务 0.8.0 起调用 Agent Reach 时不再传入 `LOCALAPPDATA` 和 `APPDATA`，它就找不到浏览器配置目录。
- **B 站扫码登录（服务 0.8.0）**：在管理网页“联网搜索”一栏点“扫码登录”，用 B 站 App 扫码，建议用小号；登录后模型可以读 B 站视频字幕（`bilibili subtitle`）。
  - bili-cli 0.6.2 自己的扫码登录已经失效：B 站把登录 Cookie 改放在 `Set-Cookie` 里。所以服务自己走 B 站网页版扫码流程：生成二维码、轮询，成功后从 `Set-Cookie` 取 Cookie。
  - 登录信息写进 Agent Reach 私密目录下 bili-cli 读取的 `credential.json`，不经过手机、不写日志。管理网页可以查看登录的账号名，也可以退出登录。
  - 生成、轮询、退出都是带管理页请求头的 POST/DELETE；GET 只读状态和当前二维码图片。
  - `/v1/pocket/status` 的 `reach.bilibili_login` 告诉手机是否已登录。

## 表情包

表情包保存在私密目录的 `stickers/<包ID>/`：每个包一份 `pack.json`，加上 `001.png` 这样编号的图片。手机读取用户的 ZIP（识别 stickers.json、Markdown 表格、manifest.json 或纯图片），再把整理好的包上传到电脑，电脑不解析 ZIP。

- `GET /v1/stickers`：列出全部表情包和上限。
- `POST /v1/stickers/packs`：类型 `application/vnd.pocket-tavern.stickers`，内容依次为 4 字节大端的说明长度、JSON 说明（名称、许可、来源和每张图的名称、描述、标签、大小），以及按顺序排列的图片字节。电脑会重新检查每张图的文件头（PNG / JPEG / GIF / WebP）和大小（≤ 2 MB），以及名称是否有效、是否与已有表情重名。最多 20 个包，每包最多 300 张，单次上传最大 64 MB。保存时先写入临时目录，完整后才出现在列表中。
- `PATCH /v1/stickers/packs/<id>`（`{"enabled": true/false}`）启用或停用；`DELETE` 删除。
- `GET /v1/stickers/files/<id>/<NNN.ext>`：读取图片。上传后图片不会再变，所以响应带一年的不可变缓存头，手机缓存后不再重复下载。

只有会话使用电脑写作服务时，手机才把表情清单发给模型；用其他平台聊天时不发图。

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
