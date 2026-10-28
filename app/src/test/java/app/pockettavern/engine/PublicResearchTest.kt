package app.pockettavern.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PublicResearchTest {
    private val request = PublicResearchRequest(
        subject = "林岚",
        categories = ResearchCategory.values().toList(),
        allowFiction = true
    )

    @Test
    fun parsesAllSixCategoriesAndEvidenceLayers() {
        val rows = JSONArray()
        ResearchCategory.values().forEachIndexed { index, category ->
            val evidence = when (index % 3) {
                0 -> "fact"
                1 -> "inference"
                else -> "fictional"
            }
            val row = JSONObject()
                .put("category", category.wireName)
                .put("text", if (evidence == "fictional") "虚构设定：${category.displayName}待定" else "公开资料中的${category.displayName}线索")
                .put("evidence", evidence)
                .put("observedAt", "2026-10-24T01:02:03Z")
                .put("confidence", 0.5)
                .put("privacy", "public")
            if (evidence != "fictional") row.put("source", JSONObject().put("url", "https://example.com/$index").put("title", "公开来源"))
            if (evidence == "inference") row.put("basis", "来源明确描述了可观察行为")
            rows.put(row)
        }
        val report = PublicResearch.parse(
            JSONObject()
                .put("subject", "林岚")
                .put("requestedAt", "2026-10-24T01:02:03Z")
                .put("items", rows)
                .toString(),
            request
        )

        assertTrue(report.isValid)
        assertEquals(6, report.result?.items?.size)
        assertEquals(setOf(ResearchCategory.values().toList()), setOf(report.result!!.items.map { it.category }))
        assertEquals(4, report.result.items.count { it.evidence == ResearchEvidence.FACT || it.evidence == ResearchEvidence.INFERENCE })
        assertEquals(2, report.result.items.count { it.evidence == ResearchEvidence.FICTIONAL })
    }

    @Test
    fun factsAndInferencesRequirePublicSourceAndInferenceBasis() {
        val rows = JSONArray()
            .put(JSONObject().put("category", "appearance").put("text", "没有来源的事实").put("evidence", "fact").put("observedAt", "now").put("confidence", 0.8))
            .put(JSONObject().put("category", "style").put("text", "来源充分但没有推断依据").put("evidence", "inference").put("observedAt", "now").put("confidence", 0.8).put("source", JSONObject().put("url", "https://example.com")))
            .put(JSONObject().put("category", "body").put("text", "可核实事实").put("evidence", "fact").put("observedAt", "now").put("confidence", 0.8).put("source", JSONObject().put("url", "https://example.com")))
        val report = PublicResearch.parse(JSONObject().put("subject", "林岚").put("requestedAt", "now").put("items", rows).toString())

        assertFalse(report.isValid)
        assertEquals(2, report.rejectedItems)
        assertEquals(1, report.result?.items?.size)
        assertEquals(ResearchEvidence.FACT, report.result!!.items.single().evidence)
    }

    @Test
    fun rejectsInvalidCategoryConfidenceAndNonPublicOrSensitiveRows() {
        val rows = JSONArray()
            .put(JSONObject().put("category", "unknown").put("text", "x").put("evidence", "fictional").put("observedAt", "now").put("confidence", 0.2))
            .put(JSONObject().put("category", "appearance").put("text", "x").put("evidence", "fictional").put("observedAt", "now").put("confidence", 1.4))
            .put(JSONObject().put("category", "appearance").put("text", "精确住址是某某路1号").put("evidence", "fictional").put("observedAt", "now").put("confidence", 0.0))
            .put(JSONObject().put("category", "appearance").put("text", "x").put("evidence", "fictional").put("observedAt", "now").put("confidence", 0.0).put("privacy", "private"))
        val report = PublicResearch.parse(JSONObject().put("subject", "林岚").put("requestedAt", "now").put("items", rows).toString())

        assertNull(report.result)
        assertEquals(4, report.rejectedItems)
        assertTrue(report.errors.any { it.contains("没有可安全使用") })
    }

    @Test
    fun failedSearchNeverManufacturesFictionalPlaceholderRecords() {
        val result = PublicResearch.fallback(request, "网络连接超时", "2026-10-24T01:02:03Z")

        assertEquals("failed", result.networkStatus)
        assertTrue(result.items.isEmpty())
        assertEquals("2026-10-24T01:02:03Z", result.requestedAt)
        assertTrue(result.warnings.any { it.contains("超时") })
    }

    @Test
    fun fallbackCanBeDisabledWhenUserRequestsFactsOnly() {
        val factsOnly = request.copy(allowFiction = false)
        val result = PublicResearch.fallback(factsOnly, "没有结果", "now")

        assertTrue(result.items.isEmpty())
        assertEquals("failed", result.networkStatus)
        assertTrue(result.warnings.single().contains("没有结果"))
    }

    @Test
    fun toolDefinitionExposesSixCategoriesAndPublicOnlyContract() {
        val function = PublicResearch.toolDefinition().getJSONObject("function")
        assertEquals(PublicResearch.TOOL_NAME, function.getString("name"))
        val enumValues = function.getJSONObject("parameters").getJSONObject("properties")
            .getJSONObject("categories").getJSONObject("items").getJSONArray("enum")
        assertEquals(6, enumValues.length())
        assertTrue(function.getString("description").contains("公开"))
        assertTrue(PublicResearch.instructions(request).contains("fact"))
        assertTrue(PublicResearch.instructions(request).contains("fictional"))
    }

    @Test
    fun parsesBoundedDuckDuckGoSourcesAndDropsUnsafeLinks() {
        val html = """
            <a class="result__a" href="https://example.com/a?x=1">公开 <b>页面</b></a>
            <a class="result__snippet">这是 <b>摘要</b> &amp; 线索</a>
            <a class="result__a" href="http://insecure.example/b">不应进入</a>
            <a class="result__snippet">不安全来源</a>
            <a class="result__a" href="https://example.com/a?x=1">重复来源</a>
            <a class="result__snippet">重复</a>
        """.trimIndent()

        val hits = PublicResearch.parseSearchHtml(html)

        assertEquals(1, hits.size)
        assertEquals("https://example.com/a?x=1", hits.single().url)
        assertEquals("公开 页面", hits.single().title)
        assertTrue(hits.single().snippet.contains("摘要 & 线索"))
    }

    @Test
    fun parsesBingSourcesAndKeepsOnlyHttps() {
        val html = """
            <ol id="b_results">
              <li class="b_algo"><h2><a href="https://example.com/person">人物资料</a></h2><div class="b_caption"><p>公开摘要 &amp; 线索</p></div></li>
              <li class="b_algo"><h2><a href="http://unsafe.example/person">不安全</a></h2><div class="b_caption"><p>不应进入</p></div></li>
            </ol>
        """.trimIndent()
        val hits = PublicResearch.parseSearchHtml(html)
        assertEquals(1, hits.size)
        assertEquals("https://example.com/person", hits.single().url)
        assertEquals("人物资料", hits.single().title)
        assertTrue(hits.single().snippet.contains("公开摘要 & 线索"))
    }

    @Test
    fun sourcePrivacyAndRequestedCategoryBoundariesAreEnforced() {
        val request = PublicResearchRequest("林岚", listOf(ResearchCategory.APPEARANCE), allowFiction = true)
        val rows = JSONArray()
            .put(JSONObject().put("category", "style").put("text", "不应越过请求范围").put("evidence", "fictional").put("observedAt", "now").put("confidence", 0.0))
            .put(JSONObject().put("category", "appearance").put("text", "公开外貌线索").put("evidence", "fact").put("observedAt", "now").put("confidence", 0.8).put("source", JSONObject().put("url", "https://example.com")))
        val report = PublicResearch.parse(JSONObject().put("subject", "林岚").put("requestedAt", "now").put("items", rows).toString(), request)
        assertEquals(1, report.result?.items?.size)
        assertEquals(1, report.rejectedItems)
        assertFalse(PublicResearch.isSafeSourceHit(PublicResearchSourceHit("x", "https://127.0.0.1/private", "摘要")))
        assertFalse(PublicResearch.isSafeSourceHit(PublicResearchSourceHit("x", "https://example.com", "联系电话 123")))
        assertTrue(PublicResearch.isSafeSourceHit(PublicResearchSourceHit("x", "https://example.com", "公开资料摘要")))
    }

    @Test
    fun sourceLinksRejectAllIpLiteralsAndLocalNamesWithoutRejectingFcDomains() {
        listOf("https://[::1]/x", "https://[::ffff:127.0.0.1]/x", "https://[fc00::1]/x", "https://0x7f000001/x", "https://2130706433/x", "https://127.1/x", "https://127.0.0.1/x", "https://192.168.1.2/x", "https://public.local/x", "https://printer/x", "https://10.0.0.1.nip.io:8000/x").forEach {
            assertFalse(it, PublicResearch.isPublicHttps(it))
        }
        listOf("https://fcloud.example.com/profile", "https://fdaily.example.org/profile", "https://example.com/profile#reference").forEach {
            assertTrue(it, PublicResearch.isPublicHttps(it))
        }
    }

    @Test
    fun fictionDisabledAndUnreturnedSourcesAreRejected() {
        val rows = JSONArray()
            .put(JSONObject().put("category", "appearance").put("text", "未经核实的眼镜设定").put("evidence", "fictional").put("observedAt", "now").put("confidence", 0.0))
            .put(JSONObject().put("category", "appearance").put("text", "来源不在实际搜索结果里").put("evidence", "fact").put("observedAt", "now").put("confidence", 0.8).put("source", JSONObject().put("url", "https://other.example.com")))
        val root = JSONObject().put("subject", "林岚").put("requestedAt", "now").put("items", rows)
        val report = PublicResearch.parse(root.toString(), request.copy(allowFiction = false), setOf("https://example.com/"))
        assertNull(report.result)
        assertEquals(2, report.rejectedItems)
    }

    @Test
    fun rejectsUnrelatedSearchResultsAndKeepsSubjectQueryBroad() {
        val request = PublicResearchRequest("周迅")
        val url = java.net.URLDecoder.decode(PublicResearch.searchUrl(request), "UTF-8")
        assertTrue(url.contains("q=周迅&"))
        assertFalse(PublicResearch.isRelevantSourceHit(PublicResearchSourceHit("Windows 设置", "https://example.com", "百度帮助"), request))
        assertTrue(PublicResearch.isRelevantSourceHit(PublicResearchSourceHit("人物专访", "https://example.com", "演员周迅谈表演"), request))
    }

    @Test
    fun profileSubjectsKeepOriginalLinksAndRejectInvalidUrlBoundaries() {
        val target = "https://www.douyin.com/user/MS4wLjABAAAAexample?from_tab_name=main"
        assertEquals(target, PublicResearch.profileUrlSubject(target))
        assertNull(PublicResearch.profileUrlSubject("http://www.douyin.com/user/MS4wLjABAAAAexample"))
        assertNull(PublicResearch.profileUrlSubject("https://www.douyin.com/video/123"))
        assertNull(PublicResearch.profileUrlSubject("https://example.com/user/MS4wLjABAAAAexample"))
        assertNull(PublicResearch.profileUrlSubject("https://www.douyin.com:444/user/MS4wLjABAAAAexample"))
        assertEquals("https://www.douyin.com:443/user/MS4wLjABAAAAexample", PublicResearch.profileUrlSubject("https://www.douyin.com:443/user/MS4wLjABAAAAexample"))
        assertNull(PublicResearch.profileUrlSubject("https://name:password@www.douyin.com/user/MS4wLjABAAAAexample"))
        assertNull(PublicResearch.profileUrlSubject("https://www.douyin.com/user/MS4wLjABAAAAexample/extra"))
        assertNull(PublicResearch.profileUrlSubject("https://www.douyin.com/user/MS4wLjABAAAAexample%2Fextra"))
        val request = PublicResearchRequest(target)
        assertTrue(PublicResearch.isRelevantSourceHit(PublicResearchSourceHit("公开主页", "https://www.douyin.com/user/MS4wLjABAAAAexample", "公开简介"), request))
        assertFalse(PublicResearch.sameProfile("https://www.douyin.com/user/MS4wLjABAAAAother", target))
        assertTrue(PublicResearch.sameProfile("https://douyin.com/user/MS4wLjABAAAAexample?other=tracking", target))
        val note = PublicResearch.profileCandidateText(target, "动态页面或反爬验证")
        assertTrue(note.contains(target))
        assertTrue(note.contains("本次检索没有找到可关联到该主页的资料"))
        assertTrue(note.contains("待核验"))
        assertFalse(note.contains("已读取"))
    }

    @Test
    fun profileSourcesMayAssociateByAnExactCaseSensitiveAccountToken() {
        val id = "MS4wLjABAAAAexample_Ab-12"
        val request = PublicResearchRequest("https://www.douyin.com/user/$id?from_tab_name=main")
        fun hit(text: String, url: String = "https://baike.example.org/person") = PublicResearchSourceHit("人物百科", url, text)
        assertTrue(PublicResearch.isRelevantSourceHit(hit("公开主页：https://www.douyin.com/user/$id?from=baike"), request))
        assertTrue(PublicResearch.isRelevantSourceHit(hit("抖音 sec_uid：$id。公开资料"), request))
        assertFalse(PublicResearch.isRelevantSourceHit(hit("另一个账号", "https://www.douyin.com/user/MS4wLjABAAAAother"), request))
        assertFalse(PublicResearch.isRelevantSourceHit(hit(id.dropLast(1)), request))
        assertFalse(PublicResearch.isRelevantSourceHit(hit(id + "x"), request))
        assertFalse(PublicResearch.isRelevantSourceHit(hit("x" + id), request))
        assertFalse(PublicResearch.isRelevantSourceHit(hit(id.lowercase()), request))
    }

    @Test
    fun runtimeOnlyExposesResearchWhenNetworkPermissionIsGranted() {
        val withoutNetwork = ToolRuntime.definitionsForPermissions(emptySet())
        val withNetwork = ToolRuntime.definitionsForPermissions(setOf("network"))

        assertFalse(withoutNetwork.contains(PublicResearch.TOOL_NAME))
        assertTrue(withNetwork.contains(PublicResearch.TOOL_NAME))
    }
}
