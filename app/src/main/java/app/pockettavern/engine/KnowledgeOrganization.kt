package app.pockettavern.engine

import app.pockettavern.domain.Chat
import app.pockettavern.domain.KnowledgeNote
import java.text.Normalizer
import java.util.Locale

data class KnowledgeCategory(val id: String, val label: String)
data class KnowledgePage(val entity: String, val title: String, val aliases: List<String>, val notes: List<KnowledgeNote>)

/** Local views over append-only notes. They never turn an old observation into a current fact. */
object KnowledgeOrganization {
    val categories = listOf(
        KnowledgeCategory("chapter", "章节"),
        KnowledgeCategory("profile", "人物档案"),
        KnowledgeCategory("person", "人物"),
        KnowledgeCategory("relationship", "关系"),
        KnowledgeCategory("event", "事件"),
        KnowledgeCategory("agreement", "约定与伏笔"),
        KnowledgeCategory("place", "地点"),
        KnowledgeCategory("correction", "作者更正"),
        KnowledgeCategory("fact", "其他事实"),
        KnowledgeCategory("archive", "归档范围"),
        KnowledgeCategory("legacy", "旧版记忆"),
        KnowledgeCategory("research", "外部资料")
    )

    fun category(note: KnowledgeNote): String = when {
        note.isResearch -> "research"
        categories.any { it.id == note.kind && it.id != "research" } -> note.kind
        else -> "legacy"
    }

    fun label(category: String): String = categories.firstOrNull { it.id == category }?.label ?: "旧版记忆"

    fun title(note: KnowledgeNote, position: Int): String = note.title.trim().ifBlank {
        when (category(note)) {
            "legacy" -> "分段记忆 $position"
            "research" -> "研究资料 $position"
            else -> "${label(category(note))} $position"
        }
    }

    // Paths use hashes, never names. This normalization only joins explicit identical keys;
    // aliases are searchable labels, not permission to merge two different people.
    fun canonicalEntity(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    fun pages(chat: Chat, notes: List<KnowledgeNote> = KnowledgeVault.validNotes(chat)): List<KnowledgePage> {
        val positions = chat.messages.mapIndexed { index, message -> message.id to index }.toMap()
        val chronology = compareBy<KnowledgeNote> { note -> note.sourceMessageIds.mapNotNull(positions::get).maxOrNull() ?: -1 }
            .thenBy { it.createdAt }.thenBy { it.id }
        return notes.asSequence()
            .filter { !it.isResearch && category(it) != "archive" && it.entity.isNotBlank() }
            .groupBy { canonicalEntity(it.entity) }
            .map { (entity, records) ->
                val ordered = records.sortedWith(chronology)
                val name = ordered.first().entity.trim().substringAfter(':').ifBlank { entity }
                KnowledgePage(entity, name, ordered.flatMap { it.aliases }.map(String::trim).filter(String::isNotBlank).distinct(), ordered)
            }.sortedBy { it.entity }
    }
}
