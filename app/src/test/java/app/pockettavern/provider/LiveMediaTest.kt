package app.pockettavern.provider

import app.pockettavern.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/** Opt in deliberately. Small paid integration probes; never enabled by ordinary builds. */
class LiveMediaTest {
    private fun key(): String {
        assumeTrue(System.getenv("POCKET_LIVE_MEDIA")=="true")
        return (System.getenv("VENICE_API_KEY") ?: System.getenv("VIRELIA_VENICE_API_KEY")).orEmpty().also { require(it.isNotBlank()) }
    }
    private val output=File("build/live-media").apply { mkdirs() }
    private val provider=ProviderConfig(id="isolated-media-verification")
    private val client=MediaClient()
    private suspend fun invoke(secret: String, id: String, type: String, payload: JSONObject): MediaJob {
        val job=client.execute(provider,secret,ModelInfo(id=id,type=type),payload.toString(),output)
        val evidence=JSONObject().put("model",id).put("type",type).put("status",job.status).put("queueId",job.queueId)
            .put("file",File(job.filePath).name).put("bytes",File(job.filePath).takeIf { it.isFile }?.length() ?: 0)
        File(output,"$type-evidence.json").writeText(evidence.toString(2))
        println("LIVE $type $id ${job.status}")
        return job
    }
    @Test fun embeddingAndDecision() = runBlocking {
        val secret=key()
        val embeddings=invoke(secret,"text-embedding-bge-m3","embedding",JSONObject().put("input","Pocket Tavern integration verification"))
        val vectors=if(embeddings.filePath.endsWith(".json")) File(embeddings.filePath).readText() else embeddings.resultJson
        assertTrue(JSONObject(vectors).getJSONArray("data").getJSONObject(0).getJSONArray("embedding").length()>0)
        assertTrue(embeddings.filePath.isBlank() || embeddings.filePath.endsWith(".json"))
        val decision=invoke(secret,"jev-latest","decision",JSONObject().put("state","The meeting starts tomorrow.").put("questions",JSONObject().put("urgent",JSONObject().put("type","noul").put("instructions","Does this describe an immediate emergency?"))))
        assertTrue(JSONObject(decision.resultJson).has("answers"))
    }
    @Test fun speechAndTranscription() = runBlocking {
        val secret=key()
        val speech=invoke(secret,"tts-kokoro","tts",JSONObject().put("input","Hello. This is a short application test.").put("voice","af_heart").put("response_format","wav"))
        val file=File(speech.filePath); assertTrue(file.length()>100)
        val asr=invoke(secret,"nvidia/parakeet-tdt-0.6b-v3","asr",JSONObject().put("file",Base64.getEncoder().encodeToString(file.readBytes())).put("filename","test.wav").put("mime_type","audio/wav"))
        assertTrue(JSONObject(asr.resultJson).optString("text").isNotBlank())
    }
    private suspend fun sourceImage(secret: String): File {
        val savedEvidence=File(output,"image-evidence.json")
        val existing=if(savedEvidence.isFile) File(output,JSONObject(savedEvidence.readText()).optString("file")) else null
        val file=existing?.takeIf { it.isFile && it.length()>100 } ?: File(invoke(secret,"venice-sd35","image",JSONObject().put("prompt","A single blue ceramic cup on a plain white table, product photograph").put("width",512).put("height",512).put("steps",10).put("format","png")).filePath)
        assertTrue(file.length()>100); assertTrue(file.extension in listOf("png","jpg","webp"))
        return file
    }
    @Test fun imageGeneration() = runBlocking { sourceImage(key()); Unit }
    @Test fun imageUpscale() = runBlocking {
        val secret=key()
        val file=sourceImage(secret)
        val encoded=Base64.getEncoder().encodeToString(file.readBytes())
        val upscale=invoke(secret,"upscaler","upscale",JSONObject().put("image",encoded).put("scale",2))
        assertTrue(File(upscale.filePath).length()>100)
        assertTrue(File(upscale.filePath).extension in listOf("png","jpg","webp"))
    }
    @Test fun imageEdit() = runBlocking {
        val secret=key()
        val encoded=Base64.getEncoder().encodeToString(sourceImage(secret).readBytes())
        val edit=invoke(secret,"firered-image-edit","inpaint",JSONObject().put("image",encoded).put("prompt","Make the ceramic cup green.").put("output_format","png"))
        assertTrue(File(edit.filePath).length()>100)
    }
    @Test fun boundedMusicAndVideoQueues() = runBlocking {
        val secret=key()
        val requests=listOf(
            Triple("ace-step-15","music",JSONObject().put("duration_seconds",60).put("prompt","Peaceful instrumental piano music, gentle melody, no singing.")),
            Triple("wan-3-0-text-to-video","video",JSONObject().put("duration","2s").put("resolution","480p").put("aspect_ratio","16:9").put("audio",false).put("prompt","A blue ceramic cup on a plain table, subtle camera movement."))
        )
        for((id,type,payload) in requests) {
            val quote=invoke(secret,id,type,JSONObject(payload.toString()).put("_action","quote"))
            val cost=JSONObject(quote.resultJson).getDouble("quote")
            require(cost in 0.0..0.3) { "Live test quote exceeds the 0.30 USD per-job test limit: $cost" }
            var job=invoke(secret,id,type,JSONObject(payload.toString()).put("_action","queue"))
            require(job.queueId.isNotBlank()) { "Queue did not return an ID" }
            File(output,"$type-queue.json").writeText(app.pockettavern.data.DataCodec.gson.toJson(job))
            var attempts=0
            while(job.status !in listOf("complete","failed","cancelled") && attempts++<30) {
                delay(10000)
                job=client.retrieve(provider,secret,job,output)
                File(output,"$type-queue.json").writeText(app.pockettavern.data.DataCodec.gson.toJson(job))
            }
            assertEquals("complete",job.status)
            assertTrue(File(job.filePath).length()>100)
            println("LIVE $type complete downloaded ${File(job.filePath).length()} bytes")
        }
    }
}
