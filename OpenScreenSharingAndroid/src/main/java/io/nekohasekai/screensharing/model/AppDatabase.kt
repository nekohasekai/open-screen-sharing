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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    fun fromRemoteProtocol(value: RemoteProtocol): String = value.rawValue

    @TypeConverter
    fun toRemoteProtocol(value: String): RemoteProtocol = RemoteProtocol.of(value) ?: RemoteProtocol.Vnc

    @TypeConverter
    fun fromSessionQuality(value: SessionQuality): String = value.rawValue

    @TypeConverter
    fun toSessionQuality(value: String): SessionQuality = SessionQuality.entries.firstOrNull { it.rawValue == value } ?: SessionQuality.Adaptive
}

@Database(entities = [Computer::class, Preference::class], version = 3, exportSchema = false)
@TypeConverters(AppConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun computers(): ComputerDao

    abstract fun preferences(): PreferenceDao

    companion object {
        val shared: AppDatabase by lazy {
            io.nekohasekai.screensharing.demo.Demo.current?.database ?: Room.databaseBuilder(App.instance, AppDatabase::class.java, "Screen Sharing.sqlite")
                .allowMainThreadQueries()
                .addMigrations(
                    object : Migration(1, 2) {
                        override fun migrate(db: SupportSQLiteDatabase) {
                            db.execSQL("ALTER TABLE computers ADD COLUMN reversesScrolling INTEGER NOT NULL DEFAULT 0")
                        }
                    },
                    object : Migration(2, 3) {
                        override fun migrate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE `computers_new` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, " +
                                    "`username` TEXT NOT NULL, `password` TEXT NOT NULL, `authenticates` INTEGER NOT NULL, `model` TEXT NOT NULL, " +
                                    "`enclosureColor` TEXT NOT NULL, `lastConnected` INTEGER, `thumbnail` BLOB, `createdAt` INTEGER NOT NULL, " +
                                    "`usesHighPerformance` INTEGER NOT NULL, `networkQuality` TEXT NOT NULL, `internetHighDefinition` INTEGER NOT NULL, " +
                                    "`internetBandwidthMbps` INTEGER NOT NULL, `remoteProtocol` TEXT NOT NULL, `trustedFingerprint` TEXT NOT NULL, " +
                                    "`keepsAlive` INTEGER NOT NULL, `disablesUDP` INTEGER NOT NULL, `reversesScrolling` INTEGER NOT NULL, " +
                                    "`quality` TEXT NOT NULL, `observes` INTEGER NOT NULL, `sharesClipboard` INTEGER NOT NULL, `resolutionScale` REAL NOT NULL, " +
                                    "`displayID` INTEGER NOT NULL, `usesHDR` INTEGER NOT NULL, `receivesAudio` INTEGER NOT NULL, `sendsAudio` INTEGER NOT NULL, " +
                                    "`keyboardSources` TEXT NOT NULL, `windowWidth` REAL NOT NULL, `windowHeight` REAL NOT NULL, `locksPointer` INTEGER NOT NULL, " +
                                    "`usesNativeTouch` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                            )
                            db.execSQL(
                                "INSERT INTO `computers_new` SELECT `id`, `name`, `host`, `port`, `username`, `password`, `authenticates`, `model`, " +
                                    "`enclosureColor`, `lastConnected`, `thumbnail`, `createdAt`, `usesHighPerformance`, `networkQuality`, 0, " +
                                    "${NetworkConfiguration.DEFAULT_BANDWIDTH_MBPS}, `remoteProtocol`, `trustedFingerprint`, `keepsAlive`, `disablesUDP`, " +
                                    "`reversesScrolling`, `quality`, `observes`, `sharesClipboard`, `resolutionScale`, `displayID`, `usesHDR`, " +
                                    "`receivesAudio`, `sendsAudio`, `keyboardSources`, `windowWidth`, `windowHeight`, `locksPointer`, `usesNativeTouch` FROM `computers`",
                            )
                            db.execSQL("DROP TABLE `computers`")
                            db.execSQL("ALTER TABLE `computers_new` RENAME TO `computers`")
                        }
                    },
                )
                .fallbackToDestructiveMigration(true)
                .build()
        }
    }
}
