# OpenCode 2 适配层（候选工作流，默认关闭）

这份适配层供后续 `pocket-agent` 使用。目前没有接入 Android 产品，也没有启动常驻 OpenCode 服务。现有 `pocket-direct` / `pocket-writer` 的验收不能替代它的验收。

## 已实现的接口

```js
import { runAgent } from '../src/opencode.mjs';

const result = await runAgent({
  request: { messages: [
    { role: 'system', content: '会话已有设定及写作要求' },
    { role: 'user', content: '本轮输入' },
  ] },
  chatId: 'stable-app-chat-id',
  config: {
    upstreamKey: process.env.VENICE_API_KEY,
    opencode: {
      enabled: true,
      baseUrl: 'http://127.0.0.1:4896',
      username: 'opencode',
      password: process.env.POCKET_OPENCODE_PASSWORD,
      providerID: 'venice',
      modelID: 'deepseek-v4-1-flash-1017',
      workspaceRoot: 'C:/private/pocket-writing/chats',
      // agent: '已验证的独立写作 agent 名称',
    },
  },
  signal: abortController.signal,
  onProgress: ({ stage, status, sessionId }) => {},
});
```

返回 `{ id, content, reasoning, finish_reason, usage, message, opencode }`，可直接转换为 OpenAI completion。`message` 是兼容别名，当前 writing-server 使用顶层 `content`、`reasoning`。`opencode` 中包含 session ID、工作目录、prompt ID 和推理步骤数。

- 同一 `chatId` 映射到固定 SHA-256 目录，不使用用户输入拼接路径。目录保留文件记忆。
- **每次请求新建 session**，发送 App 的完整文字快照。不会在已有 OpenCode 历史后反复追加整个 App 历史。代价是每次仍需发送当前快照，并非零成本增量对话。旧 session 可用于排查，不自动删除。
- 完整输入另存于 `workspaceRoot/.requests/<chat-hash>/<run-id>.json`，不写入 APK，也不向其他会话共享。
- 目前只能接收纯文字消息。不接受手机工具定义、多模态附件、tool 消息；也不会静默忽略逐请求 temperature、top_p、max_tokens、max_completion_tokens、stop 等参数。调用者须显式选择纯文字代理模式，或者使用现有普通模式。
- `upstreamKey` 缺失时直接报错。此检查不会替 OpenCode 配置模型密钥：独立 OpenCode provider 必须配置同一个 Venice 账号。适配层不向 OpenCode REST API 发送 Venice Key，不使用其默认模型或其他已登录账号。
- 不设置整轮生成时限，不根据已返回的思考/正文字数断流。`requestTimeoutMs` 只约束控制接口；等待生成结束的 `/wait` 不使用它。用户取消时只中断本次 session，不重试付费生成。
- 正文只取最后一个 assistant 步骤的文本，避免把工具前的旁白或草稿混入最终答案。思考单独返回；用量合计当前轮所有 assistant/compaction 步骤，包含推理及缓存用量。
- 权限确认、会话表单及本会话目录的全局鉴权表单会明确结束为 `opencode_interaction_required`，不会无期限等手机上不存在的确认按钮。
- 取消、传输失败会尽力取回最近 200 条投影消息中的已有正文/思考，放入 `error.partial`。若尾部分页未覆盖全轮，`usageComplete` 为 false。控制接口也无法连通时不能承诺拿到 partial；原输入和 OpenCode session 仍可排查。

## 依据的实际 OpenCode 2 协议

核对对象是本机 `I:/workspace/opencode` 的 2.0.11 源码，主要文件：

- `packages/protocol/src/groups/{session,message,permission,form,server}.ts`
- `packages/schema/src/{session-message,token-usage,prompt,permission}.ts`
- `packages/cli/src/run/noninteractive.ts`
- `packages/schema/src/config/{provider,model}.ts`

| 动作 | REST API |
| --- | --- |
| 版本 | `GET /api/info` |
| 创建独立 session | `POST /api/session`，`location: {directory}`，`model: {providerID, id}` |
| 提交输入 | `POST /api/session/:id/prompt`，`{id, text, delivery: "steer"}` |
| 等待完成 | `POST /api/experimental/session/:id/wait` |
| 分页取回结果 | `GET /api/session/:id/message?limit=200&order=asc` |
| 中断 | `POST /api/session/:id/interrupt` |
| 待确认权限 | `GET /api/session/:id/permission`，`POST .../:requestID/reply` |
| 表单 | `GET /api/session/:id/form`，`DELETE .../:formID` |
| 目录全局表单 | `GET /api/form`，带 `x-opencode-directory` |

这不是 OpenCode 1 的 `/session/:id/message` API。OpenCode 2 的 provider 配置是 `providers/package/settings`，不能套用旧版的 `provider/npm/options` 示例。

## 独立 bootstrap 要点

本机已有 4096 实例；**不要接管、重启、修改它的配置或认证库**。先在私有运行目录生成独立配置、数据库、缓存和日志，再启动独立回环端口（示例 4896，使用前检查端口占用）。适配层自身不会启动服务或安装插件。

子进程应独立设置这些环境变量，不修改用户级环境：

```text
OPENCODE_CONFIG_DIR=<private-runtime>/config
OPENCODE_CONFIG=<private-runtime>/config/opencode.json
OPENCODE_DB=<private-runtime>/opencode.db
XDG_CONFIG_HOME=<private-runtime>/xdg-config
XDG_DATA_HOME=<private-runtime>/data
XDG_STATE_HOME=<private-runtime>/state
XDG_CACHE_HOME=<private-runtime>/cache
OPENCODE_SERVER_PASSWORD=<独立随机服务密码>
OPENCODE_DISABLE_PROJECT_CONFIG=true
OPENCODE_DISABLE_MODELS_FETCH=true
OPENCODE_DISABLE_FILEWATCHER=true
```

启动前清掉子进程继承的其他模型账号、OpenCode 配置覆盖项和认证环境变量，仅显式传入本次 Venice 凭据。不要把密钥写入命令行、仓库或输出日志。不要启动自动更新。Windows 使用隐藏的子进程窗口。

独立配置的 **OpenCode 2** 结构示例：

```json
{
  "update": "disable",
  "share": "disabled",
  "model": "venice/deepseek-v4-1-flash-1017",
  "providers": {
    "venice": {
      "package": "@opencode/ai/providers/openai-compatible",
      "settings": {
        "baseURL": "https://api.venice.ai/api/v1",
        "apiKey": "{env:VENICE_API_KEY}"
      },
      "models": {
        "deepseek-v4-1-flash-1017": {
          "name": "Venice DeepSeek writing"
        }
      }
    }
  }
}
```

实际运行前还需用当前 Venice 模型目录核对模型 ID、上下文/输出能力和工具能力；示例不伪造价格或限制。预算与思考参数需要通过该 provider 的实际请求验证，不能把 REST prompt 不支持的字段悄悄丢掉。

已安装的 OpenCode 2 二进制可使用 `serve --hostname 127.0.0.1 --port 4896`。本机源码入口为 `I:/workspace/opencode/packages/cli/src/index.ts`，Bun 路径为 `C:/Users/Administrator/.bun/bin/bun.exe`；源码运行需要 CLI 的 `bunfig.toml` 中 `@opentui/solid/preload`，不应从任意 cwd 直接运行。源码默认版本标记为 `local`，适配层的版本检查会拒绝它；应使用正确构建的 2.x binary，不能把改版本字符串当运行验收。

## 原版 oh-story 工作流如何接入

候选上游：[zenstory-ai/oh-story-claudecode](https://github.com/zenstory-ai/oh-story-claudecode)，固定 commit `70d1cf426f8cbe3d374e2b06e4c2cbdbd986da06`，MIT。依据其 [hosts 文档](https://github.com/zenstory-ai/oh-story-claudecode/blob/70d1cf426f8cbe3d374e2b06e4c2cbdbd986da06/docs/hosts.md) 与 OpenCode 部署/测试入口，OpenCode 2 是官方支持的宿主。

1. 将该 commit 下载到独立 private runtime，保留 LICENSE 和引用文件。使用 Git blob 或 `git archive` 取出原文，避免 Windows checkout 的 CRLF 改写；不要改写上游 SKILL.md。
2. 在宿主配置中显式载入原版 skill 路径。先确定每会话原版资源的 provisioning 方案；参考文件应位于该会话目录内，否则当前 `external_directory: deny` 会拒绝跨目录读取。适配器目前**没有实现资源复制或 skill 安装**。
3. 原版 OpenCode agent/command/plugin 模板见 `skills/story-setup/references/opencode/`；完整长文工作流还有 `storyctl.py`、`tracking_commit.py` 等 Python 入口。这些是宿主执行组件，不等于把 SKILL.md 拼进 system 消息。
4. 目前 adapter 仅授予工作目录里的 Read、Write/Edit、Glob、Grep、Skill。Shell、任务子代理、联网、外部目录均默认禁止；禁止模型编辑 `.opencode`、`.agents`、`.claude`、`.git` 和 OpenCode 配置文件。**Python 审校、插件钩子、多 Agent 工作流尚未接入**。
5. 需要执行脚本时，先人工核对固定 commit 的脚本入口、参数及依赖，并通过受限执行器/独立 OS 账号规定目录和允许的命令。不能仅给 `python *` 或 `shell *` 放行，也不能把提示词中的“只访问此目录”当作沙箱。插件 hooks 不受普通工具权限等价约束，须单独核对。
6. 后续真实验收需证明：outline/state 文件确实写入；审校确实读取草稿并产出问题；修订使用这些问题；重开会话能检索文件记忆；跨会话无串读。最后再以同输入对比普通写作与工作流写作的质量和费用。

上游的 OpenCode e2e 使用模拟 OpenAI-compatible provider，证明宿主协议/工具链的测试设计；它不证明 Venice 上的实际写作改善。GitHub 星数同样不能代替这种验证。

## 当前验证记录

`node --test test/opencode.test.mjs`：13 项通过。覆盖 2.x 路由、显式模型选择、chat 哈希隔离/新 session、权限和两类表单、取消并回收 partial、错误不自动重试、分页边界、正文/思考拆分、token 合计、无密钥/不支持输入的早期报错。

文件工具的当前证据仅是 **mock session 创建携带明确权限规则**；未真实执行 OpenCode Read/Write，不能声称已验收文件记忆、原版脚本或插件。

2026-10-27 本机隔离协议检查未完成：第一次源码启动报 `react/jsx-dev-runtime` 缺失；切换至 CLI cwd 后未在检查窗口内得到 ready。仅使用本机 mock provider，未发生付费模型调用；测试子进程已终止，4096 未修改。收到任务范围调整后停止进一步部署。真实 OpenCode API 运行、Venice 工具调用、写作质量、Android 接入均为未验收。
