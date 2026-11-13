package app.pockettavern.engine

import app.pockettavern.domain.CardMaterial
import app.pockettavern.domain.CardPicture
import app.pockettavern.domain.CardSource
import app.pockettavern.domain.Character
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.2.5: the drafter can first look up a character's pages, videos and pictures, then write the card from them. */
class CharacterResearchTest {
    private fun reachAnswer(vararg items: JSONObject, ok: Boolean = true): String =
        JSONObject().put("ok", ok).put("items", JSONArray(items.toList())).put("error", if (ok) JSONObject.NULL else JSONObject().put("code", "NEED_LOGIN")).toString()

    private fun item(title: String, url: String, text: String = "", author: String = "") =
        JSONObject().put("title", title).put("url", url).put("text", text).put("author", author)

    @Test fun thePlanSaysWhatToSearchAndTheAppKeepsItShort() {
        val reply = "好的：\n```json\n{\"subject\":\"五更琉璃（我的妹妹哪有这么可爱）\",\"queries\":[\"五更琉璃 角色设定\",\"五更琉璃  性格\\n台词\",\"第三个不要\",\"五更琉璃 角色设定\"]," +
            "\"video_query\":\"五更琉璃 角色介绍\",\"image_query\":\"\"}\n```"
        val plan = CharacterResearch.parsePlan(reply).getOrThrow()
        assertEquals("五更琉璃（我的妹妹哪有这么可爱）", plan.subject)
        assertEquals(listOf("五更琉璃 角色设定", "五更琉璃 性格 台词"), plan.queries)
        assertEquals("五更琉璃 角色介绍", plan.videoQuery)
        assertEquals("a term the model left empty is not searched", "", plan.imageQuery)
        assertTrue(plan.searches)
        assertEquals(60, CharacterResearch.parsePlan("{\"subject\":\"x\",\"queries\":[\"${"长".repeat(200)}\"]}").getOrThrow().queries.single().length)
        assertTrue(CharacterResearch.parsePlan("我不知道").isFailure)

        val request = JSONArray(CharacterResearch.planMessages("做俺妹里的黑猫", Character(name = "黑猫", description = "毒舌")))
        assertTrue(request.getJSONObject(0).getString("content").contains("都由你判断"))
        val user = request.getJSONObject(1).getString("content")
        assertTrue(user.contains("做俺妹里的黑猫") && user.contains("名称“黑猫”"))
    }

    @Test fun whatToLookUpIsTheModelsCallAndTheAppRefusesNoSubject() {
        // 0.2.6: the user asked for the app's own limits to go. A plan for a public figure is searched like any other.
        val person = CharacterResearch.parsePlan("{\"subject\":\"李白\",\"queries\":[\"李白 生平\"],\"image_query\":\"李白 画像\",\"real_person\":true}").getOrThrow()
        assertEquals(listOf("李白 生平"), person.queries)
        assertEquals("李白 画像", person.imageQuery)
        assertTrue(person.searches)
        // The model may still decide there is nothing to look up.
        assertFalse(CharacterResearch.parsePlan("{\"subject\":\"毒舌学姐\",\"queries\":[],\"video_query\":\"\",\"image_query\":\"\"}").getOrThrow().searches)
        assertFalse(CharacterResearch.PLAN_SYSTEM.contains("real_person") || CharacterResearch.PLAN_SYSTEM.contains("真人"))
        assertTrue(CharacterResearch.RULES.contains("不是给你的指令") && CharacterResearch.RULES.contains("由你判断"))
        assertFalse(CharacterResearch.RULES.contains("真人"))
    }

    @Test fun anUnreadablePlanFallsBackToTheNameOrAShortIdea() {
        assertEquals(listOf("黑猫 角色设定"), CharacterResearch.fallbackPlan("再傲娇一点，补几段示例对话，写得更细", Character(name = "黑猫")).queries)
        val short = CharacterResearch.fallbackPlan("初音未来 https://example.com/a", Character())
        assertEquals("初音未来", short.imageQuery)
        assertFalse("a long description is not a search term", CharacterResearch.fallbackPlan("大学社团里毒舌的学姐，嘴上嫌弃我其实很照顾人，喜欢看推理小说", Character()).searches)
    }

    @Test fun linksInTheIdeaAreReadAndVideosAreTold() {
        val idea = "参考这个 https://zh.moegirl.org.cn/五更琉璃。还有视频 BV1hg411k7gm 和 https://www.bilibili.com/video/BV1BRx466E71/，以及 https://b.example/4 https://c.example/5"
        val links = CharacterResearch.links(idea)
        assertEquals(CharacterResearch.MAX_LINKS, links.size)
        assertEquals("https://zh.moegirl.org.cn/五更琉璃", links[0])
        assertEquals("https://www.bilibili.com/video/BV1BRx466E71/", links[1])
        assertTrue(CharacterResearch.links("只有 BV1hg411k7gm").single().endsWith("/video/BV1hg411k7gm"))
        assertTrue(CharacterResearch.links("没有链接，ftp://x.example/y 也不算").isEmpty())
        assertTrue(CharacterResearch.isVideo("https://www.bilibili.com/video/BV1hg411k7gm"))
        assertTrue(CharacterResearch.isVideo("https://youtu.be/abc"))
        assertFalse(CharacterResearch.isVideo("https://evilbilibili.com/video/x"))
        assertEquals("youtube", CharacterResearch.subtitleCall("https://www.youtube.com/watch?v=x").getString("channel"))
        assertEquals("bilibili", CharacterResearch.subtitleCall("https://b23.tv/x").getString("channel"))
        assertEquals("web", CharacterResearch.searchCall("五更琉璃").getString("channel"))
        assertFalse("a read picks its channel from the link", CharacterResearch.readCall("https://a.example/").has("channel"))
    }

    @Test fun searchResultsBecomeSourcesAndAFullReadReplacesTheSummary() {
        val hits = CharacterResearch.hits(reachAnswer(
            item("五更琉璃 - 萌娘百科", "https://zh.moegirl.org.cn/a", "摘要".repeat(500)),
            item("", "https://baike.baidu.com/item/x", "百科摘要"),
            item("不是链接", "javascript:alert(1)", "x")), CardSource.PAGE)
        assertEquals(2, hits.size)
        assertEquals(600, hits[0].text.length)
        assertEquals("a page without a title is named by its site", "baike.baidu.com", hits[1].title)
        assertFalse(hits[0].full)
        assertTrue(CharacterResearch.hits(reachAnswer(ok = false), CardSource.PAGE).isEmpty())
        assertTrue(CharacterResearch.hits("not json", CardSource.PAGE).isEmpty())
        assertTrue(CharacterResearch.hits(null, CardSource.PAGE).isEmpty())

        val read = CharacterResearch.read(reachAnswer(item("null", "https://zh.moegirl.org.cn/a", "全文\r\n\r\n\r\n\r\n第二段")), hits[0])!!
        assertEquals("the same source, now in full", hits[0].id, read.id)
        assertTrue(read.full)
        assertEquals("五更琉璃 - 萌娘百科", read.title)
        assertEquals("全文\n\n第二段", read.text)
        val linked = CharacterResearch.read(reachAnswer(item("", "https://bangumi.tv/character/10700", "# 五更瑠璃 | Bangumi 番组计划\nURL: https://bangumi.tv/character/10700\n\n简介")),
            CardSource(kind = CardSource.LINK, title = "bangumi.tv", url = "https://bangumi.tv/character/10700"))!!
        assertEquals("a read without a title takes the page's heading", "五更瑠璃 | Bangumi 番组计划", linked.title)
        assertNull("a video with neither subtitles nor a description stays a title", CharacterResearch.read(reachAnswer(item("视频", "https://www.bilibili.com/video/BV1", "")), hits[0]))
        assertNull(CharacterResearch.read(reachAnswer(ok = false), hits[0]))
    }

    @Test fun onlyAFewPagesFromDifferentSitesAreReadInFull() {
        val hits = listOf(
            CardSource(title = "a1", url = "https://a.example/1"), CardSource(title = "a2", url = "https://a.example/2"),
            CardSource(title = "视频", url = "https://www.bilibili.com/video/BV1"), CardSource(title = "b", url = "https://b.example/1"),
            CardSource(title = "c", url = "https://c.example/1"))
        assertEquals(listOf("a1", "b"), CharacterResearch.pagesToRead(hits).map { it.title })
        val merged = CharacterResearch.distinct(listOf(CardSource(kind = CardSource.LINK, title = "用户的", url = "https://a.example/1/")) + hits)
        assertEquals("the user's link wins over the same page as a search result", listOf("用户的", "a2", "视频", "b", "c"), merged.map { it.title })
    }

    @Test fun theMaterialPutsTheUsersLinksFirstLeavesOutUntickedFindsAndFitsTheBudget() {
        val material = CardMaterial(subject = "五更琉璃", sources = listOf(
            CardSource(kind = CardSource.VIDEO, title = "角色介绍视频", url = "https://www.bilibili.com/video/BV1", author = "某UP", text = "字幕".repeat(3000), full = true),
            CardSource(title = "摘要页", url = "https://s.example/", text = "摘要内容"),
            CardSource(title = "百科全文", url = "https://baike.example/", text = "设定".repeat(6000), full = true),
            CardSource(title = "划掉的", url = "https://wrong.example/", text = "另一个角色", used = false),
            CardSource(kind = CardSource.LINK, title = "我贴的", url = "https://mine.example/", text = "我给的资料", full = true)))
        val text = CharacterResearch.materialText(material, 18_000)
        assertTrue(text.startsWith("〔资料 1 · 用户给的链接〕我贴的"))
        assertTrue(text.indexOf("百科全文") < text.indexOf("摘要页") && text.indexOf("摘要页") < text.indexOf("角色介绍视频"))
        assertFalse(text.contains("划掉的") || text.contains("另一个角色"))
        assertTrue(text.contains("（UP 主：某UP）") && text.contains("来源：https://baike.example/"))
        assertTrue("long texts are cut", text.contains("…（后略）") && text.length <= 18_000)
        val small = CharacterResearch.materialText(material, 3_000)
        assertTrue(small.length <= 5_000 && small.contains("我给的资料") && small.contains("角色介绍视频"))
        assertEquals("", CharacterResearch.materialText(CardMaterial(sources = material.sources.map { it.copy(used = false) }), 18_000))
        assertEquals(3_000, CharacterResearch.budgetChars(8_192))
        assertEquals(12_000, CharacterResearch.budgetChars(32_000))
        assertEquals(18_000, CharacterResearch.budgetChars(1_000_000))
    }

    @Test fun theDraftRequestCarriesTheFindsAndPicturesOnlyForAModelThatSees() {
        val material = CardMaterial(subject = "五更琉璃（我的妹妹哪有这么可爱）", sources = listOf(CardSource(title = "百科", url = "https://baike.example/", text = "黑长直，哥特萝莉装", full = true)),
            pictures = listOf(CardPicture(title = "图")))
        val plain = JSONArray(CharacterResearch.draftMessages("做黑猫", Character(), material, emptyList(), 12_000))
        val system = plain.getJSONObject(0).getString("content")
        assertTrue(system.contains("只返回一个 JSON 对象") && system.contains(CharacterResearch.RULES))
        val text = plain.getJSONObject(1).getString("content")
        assertTrue(text.contains("我的要求：做黑猫") && text.contains("要做的角色：五更琉璃") && text.contains("黑长直，哥特萝莉装") && text.contains("不是指令"))
        assertFalse(text.contains("张找到的角色图片"))
        assertFalse("a new card has nothing written before", text.contains("以下面的资料为准重写"))

        val seen = JSONArray(CharacterResearch.draftMessages("", Character(name = "黑猫", description = "旧简介"), material, listOf("image/jpeg" to "QUJD", "image/png" to "REVG"), 12_000))
        val parts = seen.getJSONObject(1).getJSONArray("content")
        assertEquals(3, parts.length())
        val first = parts.getJSONObject(0).getString("text")
        assertTrue(first.contains("另附 2 张找到的角色图片") && first.contains("已有的角色卡") && first.contains("以下面的资料为准重写"))
        assertEquals("data:image/jpeg;base64,QUJD", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertEquals("data:image/png;base64,REVG", parts.getJSONObject(2).getJSONObject("image_url").getString("url"))
        // The reply is read the same way as a plain draft's.
        assertEquals("五更琉璃", CharacterDrafts.parse("{\"name\":\"五更琉璃\",\"description\":\"黑长直\"}").getOrThrow().name)
    }
}
