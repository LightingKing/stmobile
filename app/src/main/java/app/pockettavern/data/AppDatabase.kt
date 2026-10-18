package app.pockettavern.data

import android.content.Context
import androidx.room.*

data class StateDocument(
    val id: Int = 1,
    val json: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "state_chunks",
    primaryKeys = ["documentId", "chunkIndex"]
)
data class StateChunk(
    val documentId: Int = 1,
    val chunkIndex: Int,
    val content: String,
    val updatedAt: Long
)

@Dao
abstract class StateDao {
    @Query("SELECT * FROM state_chunks WHERE documentId = :documentId ORDER BY chunkIndex ASC")
    protected abstract suspend fun loadChunks(documentId: Int): List<StateChunk>

    @Query("DELETE FROM state_chunks WHERE documentId = :documentId")
    protected abstract suspend fun deleteChunks(documentId: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertChunks(chunks: List<StateChunk>)

    /** Reassembles the compatibility StateDocument from bounded rows. */
    @Transaction
    open suspend fun load(): StateDocument? {
        val chunks = loadChunks(DOCUMENT_ID)
        if (chunks.isEmpty()) return null
        return StateDocument(
            id = DOCUMENT_ID,
            json = chunks.joinToString(separator = "") { it.content },
            updatedAt = chunks.maxOf { it.updatedAt }
        )
    }

    /** Replaces all rows atomically while keeping the existing StateDocument API. */
    @Transaction
    open suspend fun save(document: StateDocument) {
        val chunks = splitJson(document.json)
            .mapIndexed { index, content ->
                StateChunk(
                    documentId = document.id,
                    chunkIndex = index,
                    content = content,
                    updatedAt = document.updatedAt
                )
            }
        deleteChunks(document.id)
        insertChunks(chunks)
    }

    private fun splitJson(json: String): List<String> {
        if (json.isEmpty()) return listOf("")
        val chunks = ArrayList<String>((json.length + CHUNK_CHARACTERS - 1) / CHUNK_CHARACTERS)
        var start = 0
        while (start < json.length) {
            var end = minOf(start + CHUNK_CHARACTERS, json.length)
            if (end < json.length && Character.isHighSurrogate(json[end - 1]) && Character.isLowSurrogate(json[end])) {
                end--
            }
            if (end <= start) end = minOf(start + 1, json.length)
            chunks += json.substring(start, end)
            start = end
        }
        return chunks
    }

    private companion object {
        const val DOCUMENT_ID = 1
        const val CHUNK_CHARACTERS = 128 * 1024
    }
}

@Database(entities = [StateChunk::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun state(): StateDao
    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "pocket-tavern.db").build()
    }
}
