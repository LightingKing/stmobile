# 请求诊断与 Venice 扣款核对

从 0.1.7 开始，在“设置 → 请求诊断”查看实际安装版本、最近请求摘要、导出 JSONL 日志，或核对最近一次请求的 Venice 账本。覆盖安装保留本机数据；旧版本没有生成过的日志不能补录。

## 排查空回复

1. 安装当前版本 0.1.42，确认设置显示 `0.1.42 (43)`。
2. 如已出现失败，先不要发起其他生成或压缩，进入设置查看最近请求并导出日志。旧记录保留 `finishReason` 时，升级后也能显示对应原因，无需重新生成。
3. 查看 `outcome`、`phase`、`finish_reason`、HTTP、耗时、正文/推理字符数以及 usage。导出日志可保留这一轮证据；导出不包含聊天正文、系统提示词或 API Key。
4. 使用 Venice 时，点“核对最近请求的 Venice 扣款”。没有请求 ID、没有匹配记录或查询失败都会明确显示无法确认。

`empty_response` 表示请求结束后没有可用正文或工具调用；`content_filter` 表示平台或上游标记内容过滤；`timeout` 表示网络等待超时；`incomplete_stream` 表示流未收到正常终止标记；`cancelled` 表示已取消。HTTP 200 只代表 HTTP 请求成功，不能证明有正文。

## 压缩、工具与余额（0.1.42）

`context_budget` 记录整轮输入估算、触发线、输入上限、模型窗口以及 Skills/摘要/近期对话等数值；这与压缩进度中的“本次待归档原文 tokens”不是同一个量。`tool_turn_finished` 记录停止继续检索的原因和轮次，后续最终正文请求标记为 `purpose=chat_final`。不记录工具参数、查询词或返回正文。

余额查询标记为 `purpose=venice_balance`，包括固定 `endpoint_path`、实际网络阶段、HTTP、耗时与连接/读取/整次时限。`account_*` 为账户余额，`key_allowance_*` 为 Key 额度。余额事件不会被“核对最近请求扣款”当成生成请求。查询超时不说明余额为零，也不能据此判断账号权限；旧成功结果保留并标注刷新失败。

0.1.7 的过滤事件仍可能记为 `empty_response`，请结合 `finish_reason=content_filter` 识别；历史日志保持原样。0.1.8 新请求将过滤单独记为 `content_filter`，有部分正文也不会标为完整成功，过滤响应的工具调用不会被执行。依据兼容协议的 [finish_reason 定义](https://developers.openai.com/api/reference/resources/chat)，这一标记表示内容被过滤，无法仅凭它判断具体触发内容或是 Venice/上游哪一层作出决定。客户端更新不会让平台未返回的正文出现，也不会自动重新发送请求。

输出上限结束时，有正文的回复会保留并提示截断；只有思考过程则保留思考并显示未收到正文。拒绝文本和兼容接口的文本块会显示为正文。空回复和错误原因持久化到消息，不再只依赖短暂提示；旧版空消息显示“旧版本未记录原因”。下一轮上下文跳过没有正文、附件或工具调用的助手空消息，保留原始历史和已收到的部分回复。

## 日志范围

- 实际安装版本、Android SDK、随机 trace ID、平台 ID/名称/协议、模型、API 主机名。
- 开始时间、经过时间、请求用途（聊天或压缩）、输入消息数/字符数、输出上限。
- HTTP 状态、阶段、首字节/首个增量时间、正常终止标记、事件数、格式错误事件数。
- 服务端 request ID 与响应 body ID 分开记录，finish reason、已收到的正文/思考字符数、工具调用数。
- 平台实际返回的数值 token usage（含 reasoning/cached）和允许的数值费用字段；不猜测缺失用量。
- 0.1.44 起普通聊天（含工具最终答复）的读取空闲与整轮超时均为 0，即不设置自动超时；等待思考与持续流式输出不会因本地计时结束，界面显示“未设置（等待完成或手动停止）”。连接/写入仍有限时，网络故障、手动停止和系统结束进程仍可中断。摘要压缩使用独立的读取 300 秒、整次 900 秒策略；模型目录、余额等非生成请求也保留各自超时。旧版聊天曾使用读取 120 秒、整轮 180 秒，后者即使持续收到思考也会断开。
- 0.1.13 起：`app_state` 记录事件发生时应用在前台还是后台；失败请求的 `error_type` 只记异常类名（如 `ConnectException`、`UnknownHostException`、`SocketTimeoutException`），不记异常消息。后台时出现连接类异常，多半是系统或厂商省电策略切断了网络；前台时出现，多半是网络或平台问题。

日志位于应用私有目录 `files/diagnostics/request-diagnostics.jsonl`，最多 200 条事件、1 MiB。一轮请求包含多个事件，因此不等于保留 200 轮。没有正文、提示词、工具参数、Authorization、请求/响应原始 body；白名单外字段不会写入文件。导出有模型与用量等元数据，用户可自行查看或清空。没有自动上传日志。

## 可选生成预算（0.1.44）

预设编辑和 DeepSeek 写作调优的“自定义生成预算”默认关闭，旧预设缺少开关时同样按关闭处理，已有数字仍保留。关闭时 Venice/OpenAI-compatible 请求省略 `max_tokens` 与 `max_completion_tokens`，诊断显示“输出预算：未发送（跟随平台）”；思考档位单独保留。关闭不等于平台无限输出，平台默认值和模型窗口仍有效。[Venice Chat Completions](https://docs.venice.ai/api-reference/endpoint/chat/completions) 将生成上限作为可选参数，且说明总量包含 reasoning 与可见文本。

开启后沿用已保存数字，Venice 发送正文额度与有效思考预留之和；这是平台总预算，不是 App 在接收端按字数切断。即使服务端返回超过所请求的额度，客户端也完整接收。Anthropic Messages 要求 `max_tokens`，所以该协议仍使用保留的正文值；需要调整时开启开关编辑。摘要/研究总结的独立内部预算不随聊天开关变化。上下文预检只在实际发送上限时扣除对应额度；关闭时不使用旧额度扣减，仍保留上下文安全余量与日常输入限制。平台默认生成额度未知时，不能承诺接近窗口极限的输入一定被平台接受。

`generation_budget_enabled` 记录开关，`output_budget_field=omitted` 明确参数未发送。没有自动重试、续写或额外付费请求；手动停止与网络失败保留已收到的文本，失败不代表平台未计费。

## 账单核对规则与限制

使用官方 [Billing Usage History](https://docs.venice.ai/api-reference/endpoint/billing/usage-history) 的 `GET /api/v1/billing/usage-history`。仅用户点击时读取，限配置的官方 `api.venice.ai` HTTPS 地址，不自动重新生成、不自动重试付费请求。

以最近完成的推理请求为目标，查询起止时间前后各扩展 5 分钟；仅当 `inferenceDetails.requestId` 精确等于该轮 request ID 或 response ID 时才统计。首页带时间窗口和 pageSize=100，后续只传 cursor；最多 10 页，每页最多 1 MiB，单次 HTTP 调用最多 20 秒。按币种分别显示负金额扣款、正金额退款和零金额记录，不把不同币种相加。

有匹配负金额才说明查询到扣款记录。查询窗口、页数限制、账单同步延迟、账号/Key 权限、缺失 ID 都可能造成未匹配；没有记录不能解释为没有扣款。不能按相近时间或相同模型猜测记录属于哪一轮。账单请求不会发送聊天正文，也不会展示账本中其他请求的明细。

HTTP 401/403 代表账单查询被拒绝，不代表聊天请求没有扣款，也不能仅凭此确定 Key 失效或需要某种特定权限。可从 Venice 网页导出包含该次请求时段的 CSV，再用 request ID / response ID 精确核对。北京时间比 UTC 快 8 小时，导出日期要覆盖换算后的时段。

扣款也不能证明手机已经收到正文。推理可能消耗输出预算，网络可能在结果传回前中断，客户端也可能无法解析返回格式。Venice [Chat Completions 文档](https://docs.venice.ai/api-reference/endpoint/chat/completions) 说明输出预算包含可见输出和 reasoning token；是否实际扣款仍以匹配账本为准。

当前验证使用本地 MockWebServer 和固定账本样本，未访问用户真实账单。真机使用、实际平台权限和账单对照仍需手机端复现；具体测试和构建记录见 [VERIFICATION.md](VERIFICATION.md)。
