package app.pockettavern.domain

import app.pockettavern.data.DataCodec
import app.pockettavern.engine.ContextEngine
import app.pockettavern.engine.StickerGate
import app.pockettavern.engine.VideoBrowse
import app.pockettavern.ui.ChatListModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.2.3 group chats: several characters in one chat. One model call per message writes every
 * speaker's part under a "【名字】" heading; the app splits it into bubbles.
 */
class GroupChatTest {
    private val fish = Character(id = "fish", name = "大肥鱼", description = "FISH_CARD", stickerPacks = listOf("大肥鱼"))
    private val cat = Character(id = "cat", name = "黑猫", description = "CAT_CARD", personality = "毒舌", stickerPacks = listOf("黑猫"))
    private val lin = Character(id = "lin", name = "林小满", description = "LIN_CARD")
    private val characters = listOf(fish, cat, lin)
    private val setup = GroupSetup(members = listOf("fish", "cat", "lin"), scene = "周五深夜一起看新番",
        relations = listOf(GroupRelation("fish", "cat", "觉得她嘴硬心软"), GroupRelation(GroupRelation.USER, "lin", "是她的前辈")))

    @Test fun aSetupIsCleanedBeforeItIsKept() {
        val messy = GroupSetup(members = listOf("fish", "ghost", "fish", "cat"), mode = "chaos", maxSpeakers = 9,
            relations = listOf(GroupRelation("fish", "fish", "自己"), GroupRelation("fish", "ghost", "不存在"), GroupRelation("cat", "fish", "  "),
                GroupRelation("cat", "fish", "嫌她吵"), GroupRelation("cat", "fish", "重复的一条")))
        val clean = GroupChats.normalize(messy, setOf("fish", "cat", "lin"))!!
        assertEquals(listOf("fish", "cat"), clean.members)
        assertEquals(GroupSetup.AUTO, clean.mode)
        assertEquals(2, clean.maxSpeakers)
        assertEquals(listOf(GroupRelation("cat", "fish", "嫌她吵")), clean.relations)
        assertEquals(6, GroupChats.normalize(GroupSetup(members = (1..9).map { "c$it" }))!!.members.size)
        assertNull(GroupChats.normalize(null))
    }

    @Test fun aGroupNeedsANameAndTwoMembers() {
        assertEquals("请填写群名", GroupChats.problem(setup, " "))
        assertEquals("至少选 2 个角色", GroupChats.problem(setup.copy(members = listOf("fish")), "群"))
        assertNull(GroupChats.problem(setup, "深夜动画同好会"))
    }

    @Test fun anAtAtTheStartNamesWhoAnswers() {
        val members = listOf(fish, cat, Character(id = "cat2", name = "黑猫猫"))
        assertEquals("cat", GroupChats.mentioned("@黑猫 你觉得呢", members)?.id)
        assertEquals("the longest name wins", "cat2", GroupChats.mentioned("@黑猫猫 来", members)?.id)
        assertEquals("cat", GroupChats.mentioned("＠黑猫你觉得呢", members)?.id)
        assertNull(GroupChats.mentioned("你觉得呢 @黑猫", members))
        assertEquals("@黑猫 你觉得呢", GroupChats.withMention("你觉得呢", cat))
        assertEquals("not twice", "@黑猫 你觉得呢", GroupChats.withMention("@黑猫 你觉得呢", cat))
        assertEquals("你觉得呢", GroupChats.withMention("你觉得呢", null))
    }

    @Test fun aReplyIsSplitBySpeakerHeadings() {
        val names = listOf("大肥鱼", "黑猫", "旁白")
        val reply = "【大肥鱼】主人来啦！\n我们刚看到第三集\n【旁白】黑猫翻了个白眼。\n黑猫：吾只是在陈述事实。\n比分：三比一\n【路人】不算数的名字"
        val lines = GroupChats.split(reply, names)
        assertEquals(listOf("大肥鱼", "旁白", "黑猫"), lines.map { it.speaker })
        assertEquals("主人来啦！\n我们刚看到第三集", lines[0].text)
        assertTrue("a colon inside the story is not a speaker", lines[2].text.contains("比分：三比一"))
        assertTrue("an unknown name stays in the text", lines[2].text.contains("【路人】不算数的名字"))
        assertEquals(listOf(GroupLine(null, "没有名字的回复")), GroupChats.split("没有名字的回复", names))
        assertEquals("黑猫：吾只是在陈述事实。", GroupChats.lastLine("【大肥鱼】先说\n【黑猫】吾只是在陈述事实。", names))
    }

    @Test fun theRulesSaySceneRelationsFormatAndWhoSpeaks() {
        val auto = GroupChats.rules(setup, characters, "大家觉得呢")
        assertTrue(auto.contains("周五深夜一起看新番"))
        assertTrue(auto.contains("大肥鱼 → 黑猫：觉得她嘴硬心软"))
        assertTrue(auto.contains("{{user}} → 林小满：是她的前辈"))
        assertTrue(auto.contains("名字只能是：大肥鱼、黑猫、林小满。"))
        assertTrue(auto.contains("最多 2 人"))
        assertFalse("no narrator unless switched on", auto.contains("【旁白】"))
        val named = GroupChats.rules(setup, characters, "@林小满 你觉得呢")
        assertTrue(named.contains("只由林小满回答"))
        assertTrue(GroupChats.rules(setup.copy(mode = GroupSetup.ROUND), characters, "开始").contains("大肥鱼 → 黑猫 → 林小满"))
        assertTrue(GroupChats.rules(setup.copy(narrator = true), characters, "开始").contains("名字只能是：大肥鱼、黑猫、林小满、旁白。"))
        // A member whose card was deleted is left out rather than named as a stranger.
        assertFalse(GroupChats.rules(setup, listOf(fish, cat), "开始").contains("林小满、"))
    }

    @Test fun theRequestCarriesEveryMembersCardAndTheGroupRules() {
        val provider = ProviderConfig(id = "p", models = listOf(ModelInfo(id = "m", contextTokens = 200_000)))
        val chat = Chat(id = "g", providerId = "p", modelId = "m", group = setup, messages = listOf(Message(content = "@黑猫 新番怎么样")))
        val json = ContextEngine.build(AppData(providers = listOf(provider), characters = characters, chats = listOf(chat)), chat).messagesJson
        assertTrue(json.contains("FISH_CARD") && json.contains("CAT_CARD") && json.contains("LIN_CARD"))
        assertTrue(json.contains("【群聊】") && json.contains("只由黑猫回答"))
        assertFalse("not the one-to-one card wrapper", json.contains("【角色卡优先级】"))
    }

    @Test fun aGroupUsesEveryMembersStickersAndDoesNotBrowseOnItsOwn() {
        fun pack(name: String, key: String) = StickerPack(id = name, name = name, stickers = listOf(Sticker(key = key)))
        val packs = listOf(pack("大肥鱼", "fish-hi"), pack("黑猫", "cat-hmph"), pack("熊猫头", "panda"))
        val pc = ProviderConfig(id = "pc", kind = "writing-server", models = listOf(ModelInfo(id = "m", supportsTools = true)))
        val group = Chat(providerId = "pc", modelId = "m", group = setup.copy(members = listOf("fish", "cat")))
        assertEquals(setOf("fish-hi", "cat-hmph"), StickerGate.forChat(group, listOf(pc), packs, characters).map { it.key }.toSet())
        assertFalse(VideoBrowse.eligible(group, listOf(pc)))
        assertTrue(VideoBrowse.eligible(group.copy(group = null), listOf(pc)))
    }

    @Test fun groupsSurviveSavingAndTheListNamesTheLastSpeaker() {
        val chat = Chat(id = "g", title = "深夜动画同好会", group = setup, messages = listOf(Message(role = "assistant", content = "【大肥鱼】先说\n【黑猫】哼。")))
        val decoded = DataCodec.decode(DataCodec.encode(AppData(chats = listOf(chat), characters = characters))).chats.single()
        assertEquals(setup, decoded.group)
        assertNull(DataCodec.decode(DataCodec.encode(AppData(chats = listOf(Chat(id = "c"))))).chats.single().group)
        assertEquals("黑猫：哼。", ChatListModel.preview(chat, GroupChats.speakerNames(setup, characters)))
    }
}
