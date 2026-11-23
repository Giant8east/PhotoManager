/** Room 数据库定义，集中声明 PhotoManager 的本地实体、版本和迁移。 */
package com.giant8east.photomanager.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 应用业务元数据数据库。具体实体和 DAO 随功能按需增加。
 */
@Database(
    entities = [
        GroupEntity::class,
        AlbumEntity::class,
        PhotoEntity::class,
        AllowedDeviceEntity::class
    ],
    version = 13,
    exportSchema = false
)
abstract class PhotoDatabase : RoomDatabase() {
    abstract fun albumDao(): AlbumDao
    abstract fun groupDao(): GroupDao
    abstract fun photoDao(): PhotoDao
    abstract fun allowedDeviceDao(): AllowedDeviceDao
}
