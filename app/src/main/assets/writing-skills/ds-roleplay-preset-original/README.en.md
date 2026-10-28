# Roleplay Mode (DeepSeek-Tuned)

An immersive roleplay agent preset tuned specifically for DeepSeek models: **zero tools, pure conversation**. The system prompt embeds the official DeepSeek roleplay adaptation instruction and SillyTavern-community-proven presentation rules, with a file-based memory system to survive long stories.

> [简体中文 README](README.md)

## 💬 Community

Want to chat about roleplay techniques and preset tuning? Join our Feishu (Lark) community group ~(｡•̀ᴗ-)✧

- How to write character cards that hit, and how to tune OOC behavior
- Tips and pitfalls for presets and plugins
- Practical DeepSeek roleplay tricks (temperature, injection instructions, memory usage)

Join via the link below — no approval needed (*≧ω≦):

https://applink.feishu.cn/client/chat/chatter/add_by_link?link_token=781sa5d9-64c7-416f-b44f-9d19cd7a3656

## Installation (dsh)

dsh agent presets are file-system based: drop this repo into `~/.dsh/.agent-presets/roleplay/` and dsh picks it up immediately — no service restart required:

```bash
git clone https://github.com/oliblue-evan/dsh-roleplay-preset.git ~/.dsh/.agent-presets/roleplay
```

Then select **Roleplay Mode** in the session preset list and start a new session.

## Design rationale

1. **Official DeepSeek roleplay adaptation**: the DeepSeek V4 family (`deepseek-v4-flash` / `deepseek-v4-pro`, plus the official app/web expert mode) was specifically trained with a roleplay thinking-mode switch. The official "character immersion requirement" instruction is embedded **verbatim** in this preset's system prompt (see section 2 of `agent.cordis.yml`).
   - Source: [victorchen96/deepseek_v4_rolepaly_instruct](https://github.com/victorchen96/deepseek_v4_rolepaly_instruct/blob/main/README.md) ([English version](https://github.com/AsyncFuncAI/deepseek_v4_roleplay/blob/main/README.md))
2. **SillyTavern community conventions**: dialogue/action/inner-monologue formatting, OOC out-of-character commands, character-card-driven play, and an anti-AI-slop checklist, all drawn from common SillyTavern community practice.
   - References: [SillyTavern guide](https://guide.sillytavern.one/), [SillyTavern parameters](https://guide.sillytavern.one/presets-lorebooks/temperature-topp/), [Sukino community presets](https://huggingface.co/Sukino/SillyTavern-Settings-and-Presets), [DeepSeek V4 Flash SillyTavern presets](https://rpfiend.com/deepseek-v4-flash-presets-sillytavern/)
3. **DeepSeek official prompt library**: creative-writing prompt ideas from the [Prompt Library](https://api-docs.deepseek.com/zh-cn/prompt-library/) (concreteness, example-driven, step-by-step constraints) are folded into the preset's style rules.

## Usage

1. Switch to the **Roleplay Mode** preset in a session and start a new one.
2. Your first message can be just "开始" (start) — the character will run a **guided setup**, walking you through the settings step by step, then immediately begin.
3. Want to skip the guide? Send a **character card** directly as your first message, e.g.:

   ```text
   【角色卡】
   姓名：林晚
   外貌：黑发及腰，左眼下方有一颗泪痣，常年穿着白色风衣
   性格：外冷内热，毒舌但心软；对陌生人戒备，对认定的人毫无保留
   背景：夜班书店老板，曾经是考古系学生
   说话方式：简短、冷淡，偶尔带一点嘲讽；对熟人话会多起来
   口头禅："关你什么事。"
   ```

   You can also just drop an opening scene and let the character enter naturally, skipping the guide.
4. Chat normally. The character thinks in the official "immersion" mode (inner monologue → plan reply) and outputs in SillyTavern formatting (dialogue "…", action 「…」, inner monologue （…）).

### Guided setup (zero-barrier start)

When the first message of a new session has no character card and no opening scene, the character automatically enters the guide flow:

1. Opens with a self-introduction and explains that it will finish the setup with you, then start immediately;
2. Asks step by step, one question at a time with example options: who to play → who you are → world/setting → tone → other preferences (address, quirks, reply length, etc.);
3. Say "你定/随机" (you decide/random) at any step to skip — the character designs it on the spot;
4. Once collected, the character **restates everything in 【角色卡】 format** (name, appearance, personality, world, tone, address, etc.) for confirmation, then starts; change anything anytime (OOC or a new character card).

In short: your very first message only needs "开始" — then just fill in the blanks as the character asks.

### Edge cases (verified in practice)

- Sending a full 【角色卡】 mid-guide → stops asking immediately and gets in character;
- First message like "直接开始/你安排/随便" (just start / you arrange / whatever) → skips the guide, character designs a role on the spot and begins;
- Ensemble/tavern scenes → the character can play multiple NPCs at once, marking switches with names or actions (verified: shopkeeper + landlady + regular customer "Old Wang" played simultaneously without hijacking your characters).

### Memory system (`.roleplay-memory/` auto-save)

Roleplay Mode doesn't rely on context compaction — it fights forgetfulness with a **file-based memory library**. As the story grows, the character organizes it into novel-style chapters in `.roleplay-memory/` under the working directory:

| File | Content |
| --- | --- |
| `story.md` | Story body: after each arc (~10–15 turns, or say 【存档】), appends "chapter title + outline + content" |
| `characters.md` | Character archive (name/appearance/personality/relations/quirks/status/secrets) |
| `world.md` | World-building archive (locations/factions/timeline/key items/unresolved hooks) |
| `index.md` | Index + recent progress + current time and place |

- **Auto trigger**: saves after each completed arc; **manual trigger**: say 【存档】 anytime;
- **Recall**: when a reply involves earlier plot, the character checks the memory library first; say 【读取记忆】 for a summary, 【记忆库】 for the file list;
- **Cross-session recovery**: if a new session detects the memory library, the character proactively says "memory restored" and asks whether to continue the old story or start a new one;
- **Editable**: memory files are plain markdown — open and edit them anytime (like editing your own worldbook); the character follows your edits; delete `.roleplay-memory/` to wipe all memory;
- **Anti-bloat**: when `story.md` exceeds ~600 lines, the earliest chapters auto-compress to one-line outlines (foreshadowing moves to `world.md`).

Note: an extremely long single session will still approach the model's context window (the memory library solves "amnesia after compaction", not the single-session window cap); near the limit, start a new session and recover via memory — that's the intended full workflow.

### OOC commands

| Input | Effect |
| --- | --- |
| `OOC: 解释一下刚才的设定` | Drops out of character, answers as the assistant, then resumes automatically |
| `（OOC）重写上一条回复` | Same, rewrites per instruction |
| `【导演】三小时后，雨停了` | Director command: advances the plot / switches scenes directly |

### Belt-and-suspenders: official Marker injection (recommended)

The official FAQ notes that **an instruction at the end of the first user message (rather than in the system prompt) is the training-time injection position and is most stable**. For maximum effect, paste the following verbatim at the end of your first message (after a blank line):

```text
【角色沉浸要求】在你的思考过程（<think>标签内）中，请遵守以下规则：
1. 请以角色第一人称进行内心独白，用括号包裹内心活动，例如"（心想：……）"或"(内心OS：……)"
2. 用第一人称描写角色的内心感受，例如"我心想""我觉得""我暗自"等
3. 思考内容应沉浸在角色中，通过内心独白分析剧情和规划回复
```

No need to repeat it in later turns — the model sees the full history every turn, so the instruction stays in effect. Official docs note this mode is not 100% triggered; retry a few times if it doesn't take.

### Other modes

- **Pure analysis mode (director/GM mode)**: paste this at the end of your first message to turn the character's thinking into pure logical planning (good for TTRPG GMs and plot planning):

  ```text
  【思维模式要求】在你的思考过程（<think>标签内）中，请遵守以下规则：
  1. 禁止使用圆括号包裹内心独白，例如"（心想：……）"或"(内心OS：……)"，所有分析内容直接陈述即可
  2. 禁止以角色第一人称描写内心活动，例如"我心想""我觉得""我暗自"等，请用分析性语言替代
  3. 思考内容应聚焦于剧情走向分析和回复内容规划，不要在思考中进行角色扮演式的内心戏表演
  ```

## Recommended model parameters (DeepSeek)

Your deployment's model routing (temperature, sampling) is managed by the host config; the preset cannot change it. The values below are community- and officially-verified recommendations, for reference in your deployment's model config:

| Model | temperature | top_p | repetition/frequency penalty | Notes |
| --- | --- | --- | --- | --- |
| `deepseek-chat` (V3.x non-reasoning) | **1.0 ~ 1.3** | 0.9 ~ 0.95 | 1.05 ~ 1.15 | Creative roleplay wants high temperature; keep character consistency via prompts, not lower temperature |
| `deepseek-reasoner` / R1 family | 0.6 (official recommendation) | 0.95 | not needed (ignored) | R1 doesn't support system prompts; merge character settings into the user message; this preset's system prompt partially fails on it — prefer `deepseek-chat` |
| `deepseek-v4-flash` / `deepseek-v4-pro` (current default) | 0.8 ~ 1.2 | 0.9 ~ 0.95 | low or 0 | Trained for roleplay thinking switching; this preset is tuned for it; official R1 temperature 0.6 recommendation at [DeepSeek deployment settings](https://www.163.com/dy/article/JOEFC11F0511ABV6.html) |

## Files

- `agent.cordis.yml`: a single `persona` plugin row (`complete: true` — the persona IS the full system prompt; `includeRuntimeContext: false` — hides runtime context) plus two file-tool rows (read/write/glob/grep, backing the memory system; no shell, no web).
- `preset.yml`: preset display name and description.

---

Unofficial community project, independently developed and maintained. Not affiliated with or endorsed by DeepSeek.
