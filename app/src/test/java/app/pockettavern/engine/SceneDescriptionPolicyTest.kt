package app.pockettavern.engine

import app.pockettavern.domain.Chat
import app.pockettavern.domain.Preset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneDescriptionPolicyTest {
    @Test fun writingPromptMakesInvisibleSceneLegibleThroughConcreteCues() {
        val prompt = NarrativePolicy.prompt(Chat(characterId = "guide"), Preset(), emptyList())
        assertTrue(prompt.contains("空间关系"))
        assertTrue(prompt.contains("人物位置"))
        assertTrue(prompt.contains("动作顺序"))
        assertTrue(prompt.contains("关键物件"))
        assertTrue(prompt.contains("声音") || prompt.contains("气味"))
    }

    @Test fun sceneDetailRuleRejectsEmptyAtmosphereAndRepeatedVisualInventory() {
        val prompt = NarrativePolicy.prompt(Chat(characterId = "guide"), Preset(), emptyList())
        assertTrue(prompt.contains("用可观察的动作、位置和变化承载情绪"))
        assertTrue(prompt.contains("不要只写漂亮、压抑、紧张等抽象形容词"))
        assertTrue(prompt.contains("不要每段重新盘点全部环境"))
    }

    @Test fun textOnlyInteractionRequiresAReconstructableActionChain() {
        val prompt = NarrativePolicy.prompt(Chat(characterId = "guide"), Preset(), emptyList())
        assertTrue(prompt.contains("文字是读者唯一的画面来源"))
        assertTrue(prompt.contains("首次出现的人物和关键物件先写清可观察事实"))
        assertTrue(prompt.contains("谁先做什么、身体或物件怎样变化、对方如何看见并回应"))
        assertTrue(prompt.contains("用户要求详细、看清过程或不知道发生了什么时"))
        assertTrue(prompt.contains("不能写“过程略过”“不再赘述"))
        assertFalse(prompt.contains("只要读者自行想象即可"))
    }

    @Test fun multiCharacterWritingKeepsDistinctVoicesWithoutMechanicalChecklists() {
        val prompt = NarrativePolicy.prompt(Chat(characterId = "guide"), Preset(), emptyList())
        assertTrue(prompt.contains("目标、利益、恐惧、经历、关系"))
        assertTrue(prompt.contains("说话节奏、措辞和行动习惯"))
        assertTrue(prompt.contains("对白应有各自的语气、潜台词和回避方式"))
        assertTrue(prompt.contains("不要让所有人使用相同句式"))
        assertTrue(prompt.contains("不影响理解的过渡可以合并"))
        assertTrue(prompt.contains("不要用“他很冷静”“她很善良”这类标签"))
    }
}
