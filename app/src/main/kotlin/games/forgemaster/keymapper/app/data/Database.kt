package games.forgemaster.keymapper.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import games.forgemaster.keymapper.core.model.ControllerConfig
import games.forgemaster.keymapper.core.model.KmJson
import games.forgemaster.keymapper.core.model.Profile
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val json: String,
    val updatedAt: Long,
) {
    fun toProfile(): Profile? = runCatching { KmJson.decodeProfile(json) }.getOrNull()

    companion object {
        fun of(profile: Profile) = ProfileEntity(profile.id, profile.name, KmJson.encodeProfile(profile), System.currentTimeMillis())
    }
}

@Entity(tableName = "games")
data class GameEntity(
    @PrimaryKey val packageName: String,
    val label: String,
    val profileId: String?,
    val addedAt: Long,
)

@Entity(tableName = "controllers")
data class ControllerEntity(
    @PrimaryKey val key: String,
    val vendor: Int,
    val product: Int,
    val name: String,
    val configJson: String,
    val selected: Boolean,
) {
    fun config(): ControllerConfig =
        runCatching { KmJson.json.decodeFromString(ControllerConfig.serializer(), configJson) }
            .getOrElse { ControllerConfig(vendor, product, name) }

    fun withConfig(config: ControllerConfig) =
        copy(configJson = KmJson.json.encodeToString(ControllerConfig.serializer(), config))

    companion object {
        fun new(vendor: Int, product: Int, name: String) = ControllerEntity(
            key = games.forgemaster.keymapper.core.model.controllerKey(vendor, product),
            vendor = vendor,
            product = product,
            name = name,
            configJson = KmJson.json.encodeToString(ControllerConfig.serializer(), ControllerConfig(vendor, product, name)),
            selected = false,
        )
    }
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY name COLLATE NOCASE")
    fun observe(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun get(id: String): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun observeOne(id: String): Flow<ProfileEntity?>

    @Upsert
    suspend fun upsert(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface GameDao {
    @Query("SELECT * FROM games ORDER BY label COLLATE NOCASE")
    fun observe(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE packageName = :pkg")
    suspend fun get(pkg: String): GameEntity?

    @Upsert
    suspend fun upsert(game: GameEntity)

    @Query("DELETE FROM games WHERE packageName = :pkg")
    suspend fun delete(pkg: String)

    @Query("UPDATE games SET profileId = :profileId WHERE packageName = :pkg")
    suspend fun assign(pkg: String, profileId: String?)

    @Query("UPDATE games SET profileId = NULL WHERE profileId = :profileId")
    suspend fun clearProfile(profileId: String)

    @Query("SELECT * FROM games WHERE profileId = :profileId")
    suspend fun forProfile(profileId: String): List<GameEntity>
}

@Dao
interface ControllerDao {
    @Query("SELECT * FROM controllers ORDER BY name COLLATE NOCASE")
    fun observe(): Flow<List<ControllerEntity>>

    @Query("SELECT * FROM controllers WHERE `key` = :key")
    suspend fun get(key: String): ControllerEntity?

    @Upsert
    suspend fun upsert(controller: ControllerEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(controller: ControllerEntity)

    @Query("DELETE FROM controllers WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Database(entities = [ProfileEntity::class, GameEntity::class, ControllerEntity::class], version = 1, exportSchema = false)
abstract class KmDatabase : RoomDatabase() {
    abstract fun profiles(): ProfileDao
    abstract fun games(): GameDao
    abstract fun controllers(): ControllerDao

    companion object {
        fun build(context: Context): KmDatabase =
            Room.databaseBuilder(context, KmDatabase::class.java, "keymapper.db").build()
    }
}
