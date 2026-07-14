package com.example.fixture

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.yield
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET

object KotlinStringFixtures {
    fun kotlinSecret(): String = "fixture-kotlin-secret"

    suspend fun coroutineSecret(): String {
        yield()
        return "fixture-coroutine-secret"
    }

    @Composable
    fun composeSecret(): String = "fixture-compose-secret"
}

@Entity(tableName = "fixture_room_table")
data class RoomMessage(
    @PrimaryKey val id: Long,
    val message: String,
)

@Dao
interface RoomMessageDao {
    @Query("SELECT message FROM fixture_messages")
    fun messages(): List<String>
}

@Database(entities = [RoomMessage::class], version = 1)
abstract class FixtureDatabase : RoomDatabase() {
    abstract fun messages(): RoomMessageDao
}

object RoomStringFixture {
    fun database(context: Context): FixtureDatabase =
        Room.databaseBuilder(context, FixtureDatabase::class.java, "fixture-room-database").build()
}

@Serializable
data class SerializedMessage(
    @SerialName("fixture_serial_name") val message: String,
)

interface FixtureApi {
    @GET("fixture-retrofit-path")
    suspend fun message(): String
}

object JniStringFixture {
    init {
        System.loadLibrary("fixture-native-contract")
    }

    external fun nativeMessage(): String

    fun businessSecret(): String = "fixture-jni-business-secret"
}
