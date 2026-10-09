/** Room 数据库依赖模块，负责向 Hilt 提供单例数据库实例。 */
package com.example.photomanager.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.photomanager.data.database.PhotoDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    fun provideAlbumDao(database: PhotoDatabase) = database.albumDao()

    @Provides
    fun provideGroupDao(database: PhotoDatabase) = database.groupDao()

    @Provides
    @Singleton
    fun providePhotoDatabase(
        @ApplicationContext context: Context
    ): PhotoDatabase = Room.databaseBuilder(
        context,
        PhotoDatabase::class.java,
        "photo_manager.db"
    ).addMigrations(object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 保留相册及关联记录，旧创建时间不能当作修改时间使用。
            // 重建表兼容旧版 SQLite；先备份图片，避免删除旧表时级联丢失。
            db.execSQL("CREATE TEMP TABLE photos_backup AS SELECT * FROM photos")
            db.execSQL("CREATE TABLE albums_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, modifiedAt TEXT, photoCount INTEGER NOT NULL, coverPath TEXT, albumPath TEXT NOT NULL, sortOrder INTEGER NOT NULL, groupId INTEGER, FOREIGN KEY(groupId) REFERENCES groups(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
            db.execSQL("INSERT INTO albums_new SELECT id, name, NULL, photoCount, coverPath, albumPath, sortOrder, groupId FROM albums")
            db.execSQL("DROP TABLE albums")
            db.execSQL("ALTER TABLE albums_new RENAME TO albums")
            db.execSQL("CREATE INDEX index_albums_groupId ON albums(groupId)")
            db.execSQL("CREATE UNIQUE INDEX index_albums_albumPath ON albums(albumPath)")
            db.execSQL("INSERT OR IGNORE INTO photos SELECT * FROM photos_backup")
            db.execSQL("DROP TABLE photos_backup")
            
        }
    }).build()
}
