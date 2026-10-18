package app.pockettavern.provider

import app.pockettavern.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in only: these tests use a small amount of the developer's inference balance. */
class LiveVeniceTest {
    @Test fun realCatalogueChatAndToolReplay() = runBlocking {
        assumeTrue(System.getenv("POCKET_LIVE_TESTS")=="true")
        val key=System.getenv("VIRELIA_VENICE_API_KEY") ?: System.getenv("VENICE_API_KEY") ?: ""
        assumeTrue(key.isNotBlank())
        val client=ProviderClient()
        val provider=ProviderConfig(id="live-verification")
        val models=client.models(provider,key)
        assertTrue(models.any { it.type=="text" && it.supportsTools })
        assertTrue(models.any { it.type=="image" })
        val model=models.firstOrNull { it.id=="mistral-small-3-2-24b-instruct" && it.supportsTools && !it.offline }
            ?: models.first { it.type=="text" && it.supportsTools && !it.supportsReasoning && !it.offline }
        val preset=Preset(maxOutput=128,temperature=0.1)
        val basic=JSONArray().put(JSONObject().put("role","user").put("content","Reply with only the word PONG."))
        val answer=client.chat(provider,key,model.id,basic.toString(),preset)
        assertTrue("The real inference must return content",answer.content.isNotBlank())
        val tools="""[{"type":"function","function":{"name":"memory_save","description":"Save a note in this isolated verification session.","parameters":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}}}]"""
        val history=JSONArray().put(JSONObject().put("role","user").put("content","Use memory_save to save exactly this note: verification only. Do not answer until you have called the tool."))
        val first=client.chat(provider,key,model.id,history.toString(),preset,tools)
        val calls=JSONArray(first.toolCallsJson)
        assertTrue("The real model must issue a tool call",calls.length()>0)
        history.put(JSONObject().put("role","assistant").put("content",first.content).put("tool_calls",calls))
        for(i in 0 until calls.length()) history.put(JSONObject().put("role","tool").put("tool_call_id",calls.getJSONObject(i).getString("id")).put("content","Saved in the isolated verification session."))
        val second=client.chat(provider,key,model.id,history.toString(),preset)
        assertTrue("Tool replay must produce a final reply",second.content.isNotBlank())
    }
}
