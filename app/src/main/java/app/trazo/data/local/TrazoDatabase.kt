package app.trazo.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TripEntity::class, PointEntity::class, VehicleEntity::class, KeyValueEntity::class, FriendCacheEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class TrazoDatabase : RoomDatabase() {
    abstract fun trips(): TripDao
    abstract fun points(): PointDao
    abstract fun vehicles(): VehicleDao
    abstract fun kv(): KeyValueDao
    abstract fun friendCache(): FriendCacheDao

    companion object {
        fun build(context: Context): TrazoDatabase =
            Room.databaseBuilder(context, TrazoDatabase::class.java, "trazo.db")
                // WAL keeps the 1 Hz point inserts from blocking UI reads.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
