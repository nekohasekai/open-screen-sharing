package io.nekohasekai.screensharing.model

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import io.nekohasekai.screensharing.App
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import java.util.UUID

@Entity(tableName = "preferences")
class Preference(@PrimaryKey val name: String, val data: String)

@Dao
interface ComputerDao {
    @Query("SELECT * FROM computers ORDER BY createdAt")
    fun observeAll(): Flow<List<Computer>>

    @Query("SELECT * FROM computers ORDER BY createdAt")
    fun all(): List<Computer>

    @Query("SELECT * FROM computers WHERE id = :id")
    fun get(id: UUID): Computer?

    @Upsert
    fun save(computer: Computer)

    @Query("DELETE FROM computers WHERE id = :id")
    fun delete(id: UUID)
}

@Dao
interface PreferenceDao {
    @Query("SELECT * FROM preferences WHERE name = :name")
    fun get(name: String): Preference?

    @Upsert
    fun save(preference: Preference)

    @Delete
    fun delete(preference: Preference)

    @Query("DELETE FROM preferences WHERE name = :name")
    fun delete(name: String)
}

class AppConverters {
    @TypeConverter
    fun fromUUID(value: UUID): String = value.toString()

    @TypeConverter
    fun toUUID(value: String): UUID = UUID.fromString(value)

    @TypeConverter
    fun fromStrings(value: List<String>): String = Json.encodeToString(value)

    @TypeConverter
    fun toStrings(value: String): List<String> = Json.decodeFromString(value)

    @TypeConverter
    fun fromNetworkQuality(value: NetworkQuality): String = value.name

    @TypeConverter
    fun toNetworkQuality(value: String): NetworkQuality = NetworkQuality.valueOf(value)

    @TypeConverter
    fun fromMaximumFrameRate(value: MaximumFrameRate): Int = value.rawValue

    @TypeConverter
    fun toMaximumFrameRate(value: Int): MaximumFrameRate = MaximumFrameRate.of(value) ?: MaximumFrameRate.Sixty

    @TypeConverter
    fun fromRemoteProtocol(value: RemoteProtocol): String = value.rawValue

    @TypeConverter
    fun toRemoteProtocol(value: String): RemoteProtocol = RemoteProtocol.of(value) ?: RemoteProtocol.Vnc

    @TypeConverter
    fun fromSessionQuality(value: SessionQuality): String = value.rawValue

    @TypeConverter
    fun toSessionQuality(value: String): SessionQuality = SessionQuality.entries.firstOrNull { it.rawValue == value } ?: SessionQuality.Adaptive
}

@Database(entities = [Computer::class, Preference::class], version = 1, exportSchema = false)
@TypeConverters(AppConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun computers(): ComputerDao

    abstract fun preferences(): PreferenceDao

    companion object {
        val shared: AppDatabase by lazy {
            Room.databaseBuilder(App.instance, AppDatabase::class.java, "Screen Sharing.sqlite")
                .allowMainThreadQueries()
                .fallbackToDestructiveMigration(true)
                .build()
        }
    }
}
