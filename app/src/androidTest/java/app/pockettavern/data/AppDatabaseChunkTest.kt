package app.pockettavern.data

import android.test.InstrumentationTestCase
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import junit.framework.TestCase.assertEquals

@Suppress("DEPRECATION")
class AppDatabaseChunkTest : InstrumentationTestCase() {
    fun testLargeStateDocumentRoundTripsThroughChunks() {
        val context = instrumentation.targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val json = "x".repeat(128 * 1024 - 1) + "😀" + "y".repeat(128 * 1024 * 2 + 7)
            val source = StateDocument(json = json, updatedAt = 123L)
            runBlocking {
                database.state().save(source)
                assertEquals(source, database.state().load())
            }
        } finally {
            database.close()
        }
    }
}
