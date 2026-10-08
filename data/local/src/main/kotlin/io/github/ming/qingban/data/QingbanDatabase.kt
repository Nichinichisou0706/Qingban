package io.github.ming.qingban.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "items") data class ItemEntity(@PrimaryKey val id: String, val version: Int, val json: String, val delivery: String)
@Entity(tableName = "changes", indices = [Index(value = ["requestKey"], unique = true)]) data class ChangeEntity(@PrimaryKey val id: String, val requestKey: String, val timestamp: Long, val json: String)
@Entity(tableName = "knowledge") data class KnowledgeEntity(@PrimaryKey val id: String, val json: String)
@Entity(tableName = "preferences", primaryKeys = ["key", "scope"]) data class PreferenceEntity(val key: String, val scope: String, val json: String)
@Entity(tableName = "observations") data class ObservationEntity(@PrimaryKey val id: String, val json: String)
@Entity(tableName = "session") data class SessionEntity(@PrimaryKey val key: String, val value: String)

@Dao interface QingbanDao {
    @Query("SELECT * FROM items") fun observeItems(): Flow<List<ItemEntity>>
    @Query("SELECT * FROM items") suspend fun items(): List<ItemEntity>
    @Query("SELECT * FROM items WHERE id=:id") suspend fun item(id: String): ItemEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putItem(item: ItemEntity)
    @Query("UPDATE items SET delivery=:delivery WHERE id=:id AND version=:version") suspend fun delivery(id: String, version: Int, delivery: String)
    @Insert suspend fun putChange(change: ChangeEntity)
    @Query("SELECT * FROM changes WHERE requestKey=:requestKey LIMIT 1") suspend fun changeByRequest(requestKey: String): ChangeEntity?
    @Query("SELECT * FROM changes WHERE id=:id") suspend fun change(id: String): ChangeEntity?
    @Query("SELECT * FROM changes ORDER BY timestamp DESC, rowid DESC LIMIT 1") suspend fun latestChange(): ChangeEntity?
    @Query("SELECT * FROM knowledge") fun observeKnowledge(): Flow<List<KnowledgeEntity>>
    @Query("SELECT * FROM knowledge") suspend fun knowledge(): List<KnowledgeEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putKnowledge(source: KnowledgeEntity)
    @Query("DELETE FROM knowledge WHERE id=:id") suspend fun deleteKnowledge(id: String)
    @Query("SELECT * FROM preferences") fun observePreferences(): Flow<List<PreferenceEntity>>
    @Query("SELECT * FROM preferences") suspend fun preferences(): List<PreferenceEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPreference(preference: PreferenceEntity)
    @Query("DELETE FROM preferences WHERE `key`=:key AND scope=:scope") suspend fun deletePreference(key: String, scope: String)
    @Query("SELECT * FROM observations") suspend fun observations(): List<ObservationEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun putObservation(observation: ObservationEntity)
    @Query("DELETE FROM observations") suspend fun clearObservations()
    @Query("SELECT value FROM session WHERE `key`=:key") suspend fun session(key: String): String?
    @Query("SELECT * FROM session") suspend fun allSessions(): List<SessionEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSession(session: SessionEntity)
    @Query("DELETE FROM session WHERE `key`=:key") suspend fun deleteSession(key: String)
}

@Database(entities = [ItemEntity::class, ChangeEntity::class, KnowledgeEntity::class, PreferenceEntity::class, ObservationEntity::class, SessionEntity::class], version = 1, exportSchema = true)
abstract class QingbanDatabase : RoomDatabase() { abstract fun dao(): QingbanDao }
