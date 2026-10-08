package io.github.ming.qingban.domain

import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class KnowledgeService(val store: KnowledgeStore, private val clock: Clock, private val memories: MemoryStore? = null) {
    suspend fun delete(id: String, deleteLinkedPreferences: Boolean = false) {
        memories?.listPreferences()?.filter { it.sourceId == id }?.forEach { if(deleteLinkedPreferences) memories.delete(it.key, it.scope) else memories.save(it.copy(sourceDeleted = true)) }
        store.delete(id)
    }
    suspend fun linkedPreferences(id: String) = memories?.listPreferences()?.count { it.sourceId == id } ?: 0
    suspend fun update(id: String, title: String, text: String, enabled: Boolean, validFrom: String?, validUntil: String?, origin: String, reliability: String): KnowledgeSource {
        require(title.isNotBlank() && title.length<=240 && text.isNotBlank() && text.length <= 250_000) { "资料名称最多 240 字，正文最多 25 万字，均不能为空" }
        validFrom?.let(LocalDate::parse); validUntil?.let(LocalDate::parse)
        require(validFrom == null || validUntil == null || validFrom <= validUntil) { "资料有效期顺序不正确" }
        val old = store.all().firstOrNull { it.id == id } ?: error("资料不存在")
        val contentChanged = old.title != title || old.text != text
        val result = old.copy(title = title.trim(), text = text, enabled = enabled, validFrom = validFrom, validUntil = validUntil, origin = origin, reliability = reliability, version = if(contentChanged) old.version + 1 else old.version, revisions = if(contentChanged) (old.revisions + KnowledgeRevision(old.version, old.title, old.text, clock.millis())).takeLast(5) else old.revisions)
        store.save(result); return result
    }
    suspend fun allowRemote(id: String, enabled: Boolean) { val source = store.all().firstOrNull { it.id == id } ?: error("资料不存在"); store.save(source.copy(allowRemote = enabled)) }
    suspend fun importText(title: String, text: String, allowRemote: Boolean = false, attachment: KnowledgeAttachment? = null): KnowledgeSource {
        require(title.isNotBlank() && title.length<=240 && text.isNotBlank()) { "资料名称最多 240 字，名称与正文不能为空" }
        attachment?.let(BackupCodec::validateAttachment)
        require(text.length <= 250_000) { "首版单份文本最多 25 万字" }
        return KnowledgeSource(UUID.randomUUID().toString(), title.trim(), text, clock.millis(), allowRemote = allowRemote, attachment=attachment).also { store.save(it) }
    }
    suspend fun search(query: String, remote: Boolean, includeHistorical: Boolean = false): List<KnowledgeHit> = withContext(Dispatchers.Default) {
        val words = query.lowercase().split(Regex("[\\s，。！？、:：]+" )).filter { it.isNotBlank() }
        val grams = words.flatMap { word -> if (word.length > 2) word.windowed(2) + word else listOf(word) }.distinct()
        val queryVector = TextVector.of(query)
        store.all().filter { it.enabled && (!remote || it.allowRemote) && (includeHistorical || ((it.validUntil == null || !LocalDate.now(clock).isAfter(LocalDate.parse(it.validUntil))) && (it.validFrom == null || !LocalDate.now(clock).isBefore(LocalDate.parse(it.validFrom))))) }
            .flatMap { source -> source.text.chunked(700).map { chunk -> source to chunk } }
            .map { (source, chunk) -> Triple(source, chunk, grams.count { it in (source.title + chunk).lowercase() }.toDouble() + TextVector.cosine(queryVector, TextVector.of(chunk)) ) }
            .filter { it.third > 0.35 }.sortedByDescending { it.third }.take(5)
            .map { KnowledgeHit(it.first.id, it.first.title, it.second, it.first.version, it.first.allowRemote, it.first.validUntil) }
    }
}

/** Local character vectors assist keyword ranking; they do not claim semantic understanding. */
object TextVector {
    fun of(text: String): Map<String, Double> = text.lowercase().replace(Regex("[\\s\\p{P}]+"), "").let { clean ->
        (if(clean.length < 2) listOf(clean) else clean.windowed(2)).filter { it.isNotBlank() }.groupingBy { it }.eachCount().mapValues { kotlin.math.ln(1.0 + it.value) }
    }
    fun cosine(a: Map<String, Double>, b: Map<String, Double>): Double {
        val denominator = kotlin.math.sqrt(a.values.sumOf { it*it } * b.values.sumOf { it*it })
        return if(denominator == 0.0) 0.0 else a.entries.sumOf { it.value * (b[it.key] ?: 0.0) } / denominator
    }
}
