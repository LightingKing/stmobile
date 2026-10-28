#!/usr/bin/env python3
"""Opt-in, small-sample Venice writing comparison using neutral fictional scenes.

Default: print the plan only; do not read credentials, create files, or use the network.
Run: python venice-writing-evaluation.py --run --key-file PATH
Alternatively set VENICE_API_KEY in the environment. Never pass the key as an argument.

This compares two parameter combinations across three examples. It is not a test of
the complete Android request/memory/tool pipeline, and cannot establish which setting
caused a difference or certify writing quality. Human review is required.
"""

import argparse
import datetime as dt
import json
import math
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.request


MODEL_ID = "deepseek-v4-1-flash-1017"
CATALOG_URL = "https://api.venice.ai/api/v1/models?type=text"
CHAT_URL = "https://api.venice.ai/api/v1/chat/completions"
MAX_COMPLETION_TOKENS = 10_000
MAX_GENERATIONS = 6
MAX_RESPONSE_BYTES = 8 * 1024 * 1024
LIMITATION = (
    "这是三个中性虚构案例、两种参数组合的小样本对照。两组同时改变思考强度和平台提示，"
    "不能作单因素因果归因，也不等于手机端上下文、记忆、工具和流式回复全链路验收。"
    "不自动评分、不宣称质量达标；请人工比较完整可见回答。每次最多 10000 输出 tokens"
    "（包括模型思考与正文），最多 6 次生成；不自动重试。"
)
COMMON_SYSTEM = (
    "你在协作写一部中性现实题材小说，所有人物均为虚构成年人。"
    "保持时间、地点、在场人物、物品持有者和已发生事件连续。"
    "人物只能依据自己亲历、明确获知或现场可观察的信息判断，猜测应保持为猜测。"
    "不同经历与目标应通过行动和回应体现，不要求全员依次表态。"
    "历史作者更正是剧情外信息，不写成角色对白或道歉；以最新用户消息确定本轮任务。"
    "按要求直接写正文，不附解释、检查表或自评。不要替用户控制的角色作重大决定。"
)

CASES = (
    {
        "id": "resume_after_correction",
        "title": "旧纠错之后继续正文",
        "review_points": [
            "从周二 18:00 的图书馆接续，避免再次停下解释纠错。",
            "钥匙已在馆长周遥手中，避免重复交付。",
            "许岑与周遥有往返互动，避免把作者纠正写入对白。",
        ],
        "messages": [
            {"role": "system", "content": COMMON_SYSTEM},
            {"role": "user", "content": "许岑把库房钥匙交给馆长周遥，两人准备在闭馆前核对新到的书箱。许岑负责清点，周遥负责登记。"},
            {"role": "assistant", "content": "周遥把钥匙挂进腰间的小皮袋，翻开登记簿。许岑把第一只箱子挪到灯下，发现封签的一角翘了起来。走廊的钟敲了六下。"},
            {"role": "user", "content": "【历史作者更正：不是角色台词，本轮任务以最新用户消息为准】日期是周二，时间 18:00。闭馆时间 18:30。钥匙已经交付，不要再写一遍交钥匙。"},
            {"role": "assistant", "content": "【历史剧情外确认】日期与时间已确认；钥匙当前由周遥持有。"},
            {"role": "user", "content": "继续正文，写许岑发现翘起的封签之后，两人如何核对第一只书箱。约 500–800 字，在当前十分钟内推进，给两人的互动留出自然反应。"},
        ],
    },
    {
        "id": "character_knowledge",
        "title": "人物认知边界与不同反应",
        "review_points": [
            "只有修复师林舟知道图纸背面藏着旧检修记录。",
            "孟晴可注意到犹豫并猜测，但不能无依据说出记录内容。",
            "林舟、孟晴、赵宁的不同目标通过具体行为呈现。",
        ],
        "messages": [
            {"role": "system", "content": COMMON_SYSTEM},
            {"role": "user", "content": "虚构场景：修复工作室，周五 15:20。修复师林舟独自发现老桥图纸背面有旧检修记录，尚未告诉任何人。记者孟晴只知道图纸今天刚送到，想赶上采访截稿；项目协调员赵宁只知道今晚要搬工作室，关心打包进度。三人都在桌边。"},
            {"role": "assistant", "content": "林舟压住图纸的卷边，把放大镜挪到桌沿。孟晴合上录音笔的盖子，看了一眼墙钟。赵宁抱来两只空箱，问这张图纸该装进哪一只。"},
            {"role": "user", "content": "继续这一刻，约 500–800 字。林舟暂时不想公开自己的发现；另外两人可以观察、追问或误解。写出三人的目标如何影响说话和动作，不跳时，不让任何人突然知道没有获知的秘密，不罗列三人的心理标签。"},
        ],
    },
    {
        "id": "state_transitions",
        "title": "时间、关系与资源变化连续性",
        "review_points": [
            "当前为第二天 08:20、东码头候船室，09:00 才开船。",
            "沈宁与叶川是多年合作伙伴，昨天的争执已经和解。",
            "沈宁手中仅余一张船票，另一张已交给叶川；雨衣已借给陈桥。",
            "叶川已去窗口核验票据，需要交代返回过程才能重新加入室内互动。",
        ],
        "messages": [
            {"role": "system", "content": COMMON_SYSTEM},
            {"role": "user", "content": "沈宁和叶川是合作五年的调查员。昨天两人因路线争执，晚饭前已讲清误会并和解。沈宁原有两张 09:00 开船的票和一件蓝雨衣。"},
            {"role": "assistant", "content": "第二天 08:10，沈宁在东码头把一张船票交给叶川，又将蓝雨衣借给淋湿的志愿者陈桥。叶川说去窗口核验票据，随后离开候船室。沈宁把自己剩下的那张票夹进笔记本。"},
            {"role": "user", "content": "现在 08:20。沈宁还在候船室，陈桥穿着借来的雨衣，叶川尚未回来。继续约 500–800 字，写陈桥带来一条与码头道路有关的消息，两人据此做些小准备；不要直接跨到开船、重复借雨衣或把已经和解的伙伴写成仍在冷战。沈宁由用户控制，不替她决定更改调查目的地。"},
        ],
    },
)
VARIANTS = (
    {"id": "baseline", "reasoning_effort": "default", "include_platform_prompt": True},
    {"id": "tuned", "reasoning_effort": "low", "include_platform_prompt": False},
)


class EvaluationError(Exception):
    """An error with a fixed, credential-free user-facing description."""


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def utc_now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def write_json(path, value):
    """Flush the ledger before sending, with atomic replacement for later updates."""
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("x", encoding="utf-8", newline="\n") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2, allow_nan=False)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)


def request_json(opener, request, timeout):
    with opener.open(request, timeout=timeout) as response:
        raw = response.read(MAX_RESPONSE_BYTES + 1)
        if len(raw) > MAX_RESPONSE_BYTES:
            raise EvaluationError("响应超过本地大小限制，已停止；不会重试。")
    try:
        parsed = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise EvaluationError("响应不是有效 JSON，已停止；不会保存响应正文或重试。") from None
    if not isinstance(parsed, dict):
        raise EvaluationError("响应结构不符，已停止；不会重试。")
    return parsed


def read_key(key_file):
    try:
        value = key_file.read_text(encoding="utf-8-sig").strip() if key_file else os.environ.get("VENICE_API_KEY", "").strip()
    except (OSError, UnicodeError):
        raise EvaluationError("无法读取 Key 文件；请检查本地文件路径与编码。") from None
    if not value or any(char.isspace() for char in value):
        raise EvaluationError("请通过 VENICE_API_KEY 或 --key-file 提供单行 Key。")
    return value


def validate_catalog(root):
    models = root.get("data")
    model = next((item for item in models if isinstance(item, dict) and item.get("id") == MODEL_ID), None) if isinstance(models, list) else None
    if model is None:
        raise EvaluationError("公开目录未找到固定目标模型；未发送生成请求。")
    spec = model.get("model_spec")
    if not isinstance(spec, dict):
        raise EvaluationError("公开目录缺少模型能力；未发送生成请求。")
    caps = spec.get("capabilities")
    if not isinstance(caps, dict) or caps.get("supportsReasoningEffort") is not True:
        raise EvaluationError("公开目录未确认思考强度参数；未发送生成请求。")
    options = caps.get("reasoningEffortOptions")
    if not isinstance(options, list) or "low" not in options:
        raise EvaluationError("公开目录未确认 low 强度；未发送生成请求。")
    maximum = spec.get("maxCompletionTokens")
    if type(maximum) is not int or maximum < MAX_COMPLETION_TOKENS:
        raise EvaluationError("公开目录未确认至少 10000 tokens 的输出上限；未发送生成请求。")
    if model.get("offline") is True or spec.get("offline") is True:
        raise EvaluationError("公开目录标记目标模型离线；未发送生成请求。")
    default = caps.get("defaultReasoningEffort")
    return {
        "model": MODEL_ID,
        "checked_at": utc_now(),
        "supports_reasoning_effort": True,
        "low_supported": True,
        "default_reasoning_effort": default if isinstance(default, str) and default in options else None,
        "max_completion_tokens": maximum,
    }


def payload_for(case, variant):
    platform = {
        "include_venice_system_prompt": variant["include_platform_prompt"],
        # Thinking can still consume the output budget; its text is not requested or stored.
        "strip_thinking_response": True,
    }
    payload = {
        "model": MODEL_ID,
        "messages": case["messages"],
        "stream": False,
        "max_completion_tokens": MAX_COMPLETION_TOKENS,
        "venice_parameters": platform,
    }
    if variant["reasoning_effort"] != "default":
        payload["reasoning_effort"] = variant["reasoning_effort"]
        platform["disable_thinking"] = False
    return payload


def visible_answer(root):
    choices = root.get("choices")
    choice = choices[0] if isinstance(choices, list) and choices and isinstance(choices[0], dict) else None
    message = choice.get("message") if choice else None
    if not isinstance(message, dict):
        raise EvaluationError("生成响应缺少可识别的消息，已停止；不会保存原始响应或重试。")
    content = message.get("content", "")
    if isinstance(content, list):
        parts = []
        for block in content:
            if isinstance(block, dict) and block.get("type") in ("text", "output_text") and isinstance(block.get("text"), str):
                parts.append(block["text"])
        content = "\n".join(parts)
    elif not isinstance(content, str):
        content = ""
    if not content and isinstance(message.get("refusal"), str):
        content = message["refusal"]
    # Also discard explicit inline thought blocks, including an unclosed final block.
    content = re.sub(r"<(think|thinking|thought|reasoning|analysis)\b[^>]*>.*?(?:</\1\s*>|$)", "", content, flags=re.IGNORECASE | re.DOTALL).strip()
    finish = choice.get("finish_reason")
    return content, finish if isinstance(finish, str) else None, bool(message.get("tool_calls"))


def numeric_usage(root):
    """Keep token accounting only; never retain arbitrary response or reasoning text."""
    usage = root.get("usage")
    if not isinstance(usage, dict):
        return {}
    result = {}
    for key in ("prompt_tokens", "completion_tokens", "total_tokens", "reasoning_tokens", "cached_tokens"):
        value = usage.get(key)
        if type(value) is int and value >= 0:
            result[key] = value
    for key in ("prompt_tokens_details", "completion_tokens_details"):
        source = usage.get(key)
        if isinstance(source, dict):
            result[key] = {
                name: source[name] for name in ("cached_tokens", "reasoning_tokens", "audio_tokens", "image_tokens", "accepted_prediction_tokens", "rejected_prediction_tokens")
                if type(source.get(name)) is int and source[name] >= 0
            }
    return result


def plan():
    return {
        "model": MODEL_ID,
        "maximum_generation_requests": MAX_GENERATIONS,
        "max_completion_tokens_per_request": MAX_COMPLETION_TOKENS,
        "network_without_run": False,
        "limitations": LIMITATION,
        "variants": list(VARIANTS),
        "cases": [{"id": case["id"], "title": case["title"], "review_points": case["review_points"]} for case in CASES],
    }


def run(args):
    key = read_key(args.key_file)
    output = args.output_dir.resolve()
    try:
        output.mkdir(parents=True, exist_ok=False)
    except FileExistsError:
        raise EvaluationError("输出目录已存在，拒绝复用或恢复运行；不会发送任何请求。") from None
    ledger_path = output / "ledger.json"
    ledger = {
        "created_at": utc_now(), "state": "preflight", "model": MODEL_ID,
        "maximum_generation_requests": MAX_GENERATIONS, "reserved_generation_requests": 0,
        "max_completion_tokens_per_request": MAX_COMPLETION_TOKENS, "attempts": [],
    }
    write_json(ledger_path, ledger)
    write_json(output / "plan.json", plan())
    (output / "README.md").write_text(
        LIMITATION + "\n\n默认运行只打印计划。此目录不能复用；ledger 中 reserved 的请求即使结果未知也视为已消耗一次机会，不会自动重发。"
        "\n每个 request 文件仅含中性样例和参数；response 文件仅含可见回答及计费统计，不含 Key、请求头或思考正文。"
        "\nHTTP 成功或 usage 不能证明平台账单已经结算；截断、过滤、空回答都保留其 finish_reason 供人工查看。\n",
        encoding="utf-8",
    )
    opener = urllib.request.build_opener(NoRedirects())
    try:
        catalog = request_json(opener, urllib.request.Request(CATALOG_URL, headers={"Accept": "application/json"}), args.timeout)
        capabilities = validate_catalog(catalog)
        write_json(output / "catalog-capabilities.json", capabilities)
        ledger["state"] = "running"
        write_json(ledger_path, ledger)
        for case in CASES:
            for variant in VARIANTS:
                if ledger["reserved_generation_requests"] >= MAX_GENERATIONS:
                    raise EvaluationError("已达到六次生成上限，已停止。")
                ordinal = ledger["reserved_generation_requests"] + 1
                stem = f"{ordinal:02d}-{case['id']}-{variant['id']}"
                payload = payload_for(case, variant)
                write_json(output / (stem + ".request.json"), payload)
                attempt = {
                    "number": ordinal, "case": case["id"], "variant": variant["id"],
                    "started_at": utc_now(), "state": "reserved",
                    "response_file": stem + ".response.json",
                }
                ledger["reserved_generation_requests"] = ordinal
                ledger["attempts"].append(attempt)
                # Count is durably reserved before the POST; interrupted/unknown requests are never retried.
                write_json(ledger_path, ledger)
                started = time.monotonic()
                request = urllib.request.Request(CHAT_URL, data=json.dumps(payload, ensure_ascii=False).encode("utf-8"), method="POST", headers={"Authorization": "Bearer " + key, "Content-Type": "application/json", "Accept": "application/json"})
                try:
                    response = request_json(opener, request, args.timeout)
                    latency = round(time.monotonic() - started, 3)
                    answer, finish, tool_calls = visible_answer(response)
                    usage = numeric_usage(response)
                    result = {
                        "case": case["id"], "variant": variant["id"], "model": MODEL_ID,
                        "latency_seconds": latency, "finish_reason": finish, "usage": usage,
                        "tool_calls_returned_but_not_executed": tool_calls,
                        "visible_answer": answer,
                    }
                    write_json(output / attempt["response_file"], result)
                    attempt.update(state="completed", latency_seconds=latency, finish_reason=finish, usage=usage, visible_characters=len(answer))
                    write_json(ledger_path, ledger)
                    # No answer, reasoning, key, headers, or error body is printed.
                    print(json.dumps({"request": ordinal, "case": case["id"], "variant": variant["id"], "latency_seconds": latency, "finish_reason": finish, "visible_characters": len(answer), "total_tokens": usage.get("total_tokens")}, ensure_ascii=False))
                    if tool_calls:
                        raise EvaluationError("模型返回工具调用，已记录但不执行；停止剩余请求。")
                except BaseException:
                    if attempt["state"] == "reserved":
                        attempt.update(state="failed_or_unknown", latency_seconds=round(time.monotonic() - started, 3))
                    raise
        ledger["state"] = "completed"
        ledger["completed_at"] = utc_now()
        write_json(ledger_path, ledger)
        print("已保存六份对照结果。请人工比较；本脚本未判定写作质量。")
        return 0
    except urllib.error.HTTPError as error:
        ledger["state"] = "stopped_http_error"
        ledger["http_status"] = error.code
        write_json(ledger_path, ledger)
        print(f"HTTP {error.code}，已停止；不重试。", file=sys.stderr)
        return 1
    except BaseException:
        ledger["state"] = "stopped"
        write_json(ledger_path, ledger)
        raise


def main(argv=None):
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if callable(reconfigure):
            reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--run", action="store_true", help="明确执行最多六次真实生成；不指定时完全离线")
    parser.add_argument("--key-file", type=Path, help="只在 --run 时读取本机单行 Key 文件；否则使用 VENICE_API_KEY")
    parser.add_argument("--output-dir", type=Path, default=Path(__file__).resolve().parent.parent / "evidence" / "venice-writing-evaluation-1017", help="必须不存在的输出目录；默认使用固定 evidence 目录以防意外重复运行")
    parser.add_argument("--timeout", type=float, default=240.0, help="每次网络操作的 socket 超时秒数，默认 240")
    args = parser.parse_args(argv)
    if not args.run:
        print(json.dumps(plan(), ensure_ascii=False, indent=2))
        print("未联网、未读取 Key、未创建输出目录。执行真实对照必须显式添加 --run。")
        return 0
    if not math.isfinite(args.timeout) or args.timeout <= 0:
        parser.error("--timeout 必须是有限正数")
    try:
        return run(args)
    except EvaluationError as error:
        print(str(error), file=sys.stderr)
    except (urllib.error.URLError, TimeoutError, ConnectionError):
        print("网络请求失败或超时，已停止；结果可能未知，不会重试。", file=sys.stderr)
    except KeyboardInterrupt:
        print("已停止；已预留的请求不会自动重新发送。", file=sys.stderr)
    except OSError:
        print("本地文件操作失败，已停止；不会继续发送请求。", file=sys.stderr)
    except Exception:
        print("处理失败，已停止；不会打印原始响应、凭据或请求头，也不会重试。", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
