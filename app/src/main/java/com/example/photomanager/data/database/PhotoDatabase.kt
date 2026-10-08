/** Room 数据库定义，集中声明 PhotoManager 的本地实体和数据库版本。 */
package com.example.photomanager.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 应用业务元数据数据库。具体实体和 DAO 随功能按需增加。
 */
@Database(
    entities = [GroupEntity::class, AlbumEntity::class, PhotoEntity::class],
    version = 2,
    exportSchema = false
)
abstract class PhotoDatabase : RoomDatabase()
