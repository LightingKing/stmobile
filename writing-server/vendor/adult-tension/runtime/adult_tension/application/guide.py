"""`guide`: the play guide for a new player ("怎么玩"), fixed text the model
relays as it is. Plain words only: no NPC, turn, command or field names."""

from .. import schema as S
from ..errors import INVALID_INPUT, AppError
from ..persistence import repo
from . import specs

TITLE = "怎么玩"
EXAMPLE = "· 直接打字，写你做什么、说什么。例如：我把伞递过去：“雨还大，一起走吧。”"

WHAT = [
    "【这是什么】",
    "一个由你当主角的互动故事。你打字说你做什么、说什么，AI 接着把故事写下去。",
    "故事里的其他人有自己的脾气和想法，会答应你，也会拒绝你。",
]
CUSTOM = "· 创造专属世界：说“自定义世界”，会给你一份灵感清单；保存过的世界说“我的世界”就能查看。"
START = [
    "【第一步：开一局】",
    "· 直接开局：说“开局”，然后选氛围：",
    "  日常：平淡日子里的微妙相处，细水长流。",
    "  压力：开局就身处难题或险境，暗流涌动。",
    "  不想选就直接说“随便”。",
    "· 自挑剧本：说“世界列表”浏览预设世界；或者直接指定类型，如“开局 民国”“开局 悬疑”。",
    "· 不想太紧绷：说“开局 轻松点”，避开重度天灾险情。",
    CUSTOM,
]
AGAIN = "· 继续进度：说“继续上次”，接着玩上一局未完的故事。"
FIRST_STEPS = [
    "【第二步：开始玩】",
    EXAMPLE,
    "· 只写你自己的事，别人怎么反应交给故事。",
    "· 想不出来就说“继续”，故事会自己往下走一点。",
]
HOW = [
    "【怎么玩】",
    EXAMPLE,
    "· 写得越具体，故事越照你的意思走。",
    "· 你只决定你自己。别人会答应，也会拒绝；想走近一点，就像现实里一样慢慢来。",
    "· 想不出来就说“继续”，故事会自己往下走一点。",
]
ANYTIME = [
    "【随时能说】",
    "· 存档 / 读档：保存进度，或回到存过的进度",
    "· 撤销：刚才那步不算，重来",
]
COMMON = [
    "【常用的话】",
    "· 状态：看看现在几点、在哪、和谁关系怎么样",
    "· 存档 / 读档：保存进度，或回到存过的进度",
    "· 撤销：刚才那步不算，重来",
    "· 快进到明早：跳过一段时间",
    "· 来点转折：让故事出点意外",
    "· 内心可见：看到别人心里没说出口的话",
    "· 节奏 慢热 / 直接，尺度 含蓄 / 直白：换个写法",
]
SAFETY = [
    "【不舒服的时候】",
    "· 暂停：马上停下",
    "· 换个场景：离开眼前这一幕",
    "· 边界：不要 ××：以后都不会写 ××",
]
CUSTOM_TITLE = "自定义世界"
CUSTOM_LINES = [
    "想玩预设列表里没有的故事？挑你想到的说两句就行，剩下的交给我补齐，不用每项都填。",
    "",
    "【1 时代与场景】民国上海的雨夜弄堂、八十年代的南方小镇、近未来的轨道空间站、深夜无人的街角便利店、悬崖边的避雨驿站",
    "【2 你的身份】隐姓埋名的前朝后裔、刚调任的外来医生、背负人情债的账房、看似温和的旧派手艺人",
    "【3 想遇见的人（两三个）】说说身份和脾气即可，例如：嘴硬心软的寡言房东、捉摸不透的旧相识、地位悬殊且克制的年长上司；都是成年人，不说就随机生成",
    "【4 彼此间的拉扯】欠着还不清的人情、隔着不能点破的身份、互相提防又不得不依靠、握有只有两人知道的把柄、同一屋檐下的私下妥协",
    "【5 节奏倾向】日常（暗流涌动的慢热相处）/ 压力（开局就有急事催逼，如清账、宵禁、瞒住一场见不得光的交易）",
    "【6 避雷与偏好】例如：不要流血暴力、不要现代职场、不要过度沉重",
    "",
    "你可以直接这么发：“民国租界，我是刚回国的诊所医生，想遇到开裁缝铺的冷淡老板娘和精明管账人，彼此有旧账但要互相掩护，日常向，不要太沉重。”",
    "觉得满意，开局后随时说“保存世界 <你起的名字>”（例如：保存世界 弄堂旧事）存下来；下次说“我的世界”就能查看与重玩。",
]
MORE = "想看所有能说的话，说“帮助”。"


def lines_before_playing(has_played):
    start = START + ([AGAIN] if has_played else [])
    return WHAT + [""] + start + [""] + FIRST_STEPS + [""] + ANYTIME + [""] + SAFETY + ["", MORE]


def lines_while_playing(world_title):
    return ["你正在玩：《%s》。" % world_title, ""] + HOW + [""] + COMMON + [""] + SAFETY + ["", MORE]


def run(ctx, payload):
    payload, errors = S.validate(specs.GUIDE, payload or {})
    if errors:
        raise AppError(INVALID_INPUT, "输入有 %d 处问题" % len(errors) if len(errors) > 1 else errors[0]["reason"], errors)
    if payload["topic"] == "custom_world":
        out = {"stage": "custom_world", "title": CUSTOM_TITLE, "lines": CUSTOM_LINES}
        out["text"] = "\n".join([CUSTOM_TITLE, ""] + CUSTOM_LINES)
        return out
    conn = ctx.db()
    if payload["session_id"]:
        session = repo.session_summary(conn, payload["session_id"])
        out = {"stage": "playing", "session_id": payload["session_id"], "world_title": session["world_title"]}
        lines = lines_while_playing(session["world_title"])
    else:
        out = {"stage": "start"}
        lines = lines_before_playing(repo.has_sessions(conn))
    out.update({"title": TITLE, "lines": lines, "text": "\n".join([TITLE, ""] + lines)})
    return out
