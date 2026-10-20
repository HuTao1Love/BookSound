package com.zyagodin.booksound.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [BookEntity::class, ChapterEntity::class, PlaybackStateEntity::class, ServerBookEntity::class, ServerPlaybackEntity::class],
    version = 2,
    exportSchema = true,
    // 2: the server's catalog and positions (new tables only).
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun playback(): PlaybackDao
    abstract fun server(): ServerDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "booksound.db").build()
    }
}
