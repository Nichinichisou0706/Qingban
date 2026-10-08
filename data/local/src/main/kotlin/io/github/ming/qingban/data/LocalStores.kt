package io.github.ming.qingban.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import io.github.ming.qingban.domain.*
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.UUID

private val codec = Json { encodeDefaults = true }
class RoomItemStore(private val db: QingbanDatabase) : ItemStore {
    private val dao = db.dao()
    private fun ItemEntity.toDomain() = codec.decodeFromString<PlanItem>(json).copy(delivery = DeliveryState.valueOf(delivery))
    override fun observe() = dao.observeItems().map { rows -> rows.map { it.toDomain() } }
    override suspend fun all() = dao.items().map { it.toDomain() }
    override suspend fun get(id: String) = dao.item(id)?.toDomain()
    override suspend fun commit(before: PlanItem?, after: PlanItem, requestKey: String, timestamp: Long): StoredChange? = db.withTransaction {
        dao.changeByRequest(requestKey)?.let { return@withTransaction codec.decodeFromString<StoredChange>(it.json) }
        val current = dao.item(after.id)
        if ((before == null && current != null) || (before != null && current?.version != before.version)) return@withTransaction null
        // Saving pending delivery in the same transaction makes process death recoverable.
        val change = StoredChange(UUID.randomUUID().toString(), before, after, requestKey, timestamp)
        dao.putItem(ItemEntity(after.id, after.version, codec.encodeToString(after), after.delivery.name))
        dao.putChange(ChangeEntity(change.id, requestKey, timestamp, codec.encodeToString(change)))
        dao.putSession(SessionEntity("last_change_id", change.id))
        change
    }
    override suspend fun changeForRequest(requestKey: String) = dao.changeByRequest(requestKey)?.let { codec.decodeFromString<StoredChange>(it.json) }
    override suspend fun latestChange() = dao.latestChange()?.let { codec.decodeFromString<StoredChange>(it.json) }
    override suspend fun getChange(id: String) = dao.change(id)?.let { codec.decodeFromString<StoredChange>(it.json) }
    override suspend fun setDelivery(id: String, version: Int, state: DeliveryState) = dao.delivery(id, version, state.name)
    override suspend fun batchForRequest(requestKey: String) = dao.session("batch_request:$requestKey")?.let { getBatch(it) }
    override suspend fun getBatch(id: String) = dao.session(id)?.let { codec.decodeFromString<StoredBatch>(it) }
    override suspend fun commitBatch(entries: List<Pair<PlanItem?, PlanItem>>, requestKey: String, timestamp: Long): StoredBatch? = db.withTransaction {
        batchForRequest(requestKey)?.let { return@withTransaction it }
        if(entries.any { (before, after) -> dao.item(after.id)?.version != before?.version }) return@withTransaction null
        val changes = entries.mapIndexed { index, (before, after) ->
            val change = StoredChange(UUID.randomUUID().toString(), before, after, "$requestKey:$index", timestamp)
            dao.putItem(ItemEntity(after.id, after.version, codec.encodeToString(after), after.delivery.name))
            dao.putChange(ChangeEntity(change.id, change.requestKey, timestamp, codec.encodeToString(change))); change
        }
        val batch = StoredBatch("batch:${UUID.randomUUID()}", changes, requestKey, timestamp)
        dao.putSession(SessionEntity(batch.id, codec.encodeToString(batch)))
        dao.putSession(SessionEntity("batch_request:$requestKey", batch.id))
        dao.putSession(SessionEntity("last_change_id", batch.id))
        batch
    }
}
class RoomKnowledgeStore(private val dao: QingbanDao) : KnowledgeStore {
    override fun observe() = dao.observeKnowledge().map { rows -> rows.map { codec.decodeFromString<KnowledgeSource>(it.json) } }
    override suspend fun all() = dao.knowledge().map { codec.decodeFromString<KnowledgeSource>(it.json) }
    override suspend fun save(source: KnowledgeSource) = dao.putKnowledge(KnowledgeEntity(source.id, codec.encodeToString(source)))
    override suspend fun delete(id: String) = dao.deleteKnowledge(id)
}
class RoomMemoryStore(private val dao: QingbanDao) : MemoryStore {
    override fun preferences() = dao.observePreferences().map { rows -> rows.map { codec.decodeFromString<Preference>(it.json) } }
    override suspend fun listPreferences() = dao.preferences().map { codec.decodeFromString<Preference>(it.json) }
    override suspend fun save(preference: Preference) = dao.putPreference(PreferenceEntity(preference.key, preference.scope, codec.encodeToString(preference)))
    override suspend fun delete(key: String, scope: String) = dao.deletePreference(key, scope)
    override suspend fun observations() = dao.observations().map { codec.decodeFromString<ChoiceObservation>(it.json) }
    override suspend fun observeChoice(observation: ChoiceObservation) = dao.putObservation(ObservationEntity(observation.id, codec.encodeToString(observation)))
    override suspend fun clearObservations() = dao.clearObservations()
}
class RoomSessionStore(private val db: QingbanDatabase) : SessionStore {
    private val dao = db.dao()
    override suspend fun read(key: String) = dao.session(key)
    override suspend fun write(key: String, value: String) = dao.putSession(SessionEntity(key, value))
    override suspend fun remove(key: String) = dao.deleteSession(key)
    override suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }
}
class LocalStores(context: Context, val database: QingbanDatabase = Room.databaseBuilder(context, QingbanDatabase::class.java, "qingban.db").build()) {
    val items: ItemStore = RoomItemStore(database)
    val knowledge: KnowledgeStore = RoomKnowledgeStore(database.dao())
    val memory: MemoryStore = RoomMemoryStore(database.dao())
    val sessions: SessionStore = RoomSessionStore(database)
}
