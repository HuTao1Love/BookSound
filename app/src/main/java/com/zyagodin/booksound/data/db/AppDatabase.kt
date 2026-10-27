package com.zyagodin.booksound.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        BookEntity::class, ChapterEntity::class, PlaybackStateEntity::class, ServerBookEntity::class, ServerPlaybackEntity::class,
        ListeningStartEntity::class,
    ],
    version = 3,
    exportSchema = true,
    // 2: the server's catalog and positions (new tables only).
    // 3: where listening sessions began (a new table only).
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun playback(): PlaybackDao
    abstract fun server(): ServerDao
    abstract fun listeningStarts(): ListeningStartDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "booksound.db").build()
    }
}
