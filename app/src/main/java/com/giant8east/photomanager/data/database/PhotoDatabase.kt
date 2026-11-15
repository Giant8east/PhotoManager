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
    version = 11,
    exportSchema = false
)
abstract class PhotoDatabase : RoomDatabase() {
    abstract fun albumDao(): AlbumDao
    abstract fun groupDao(): GroupDao
    abstract fun photoDao(): PhotoDao
    abstract fun allowedDeviceDao(): AllowedDeviceDao

    companion object {
        /**
         * 10 → 11：新增局域网白名单表。
         *
         * 迁移只加表，不动既有数据；以后版本继续追加 Migration，不修改已发布的这个对象。
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `allowed_devices` (
                        `deviceId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `pairedAt` INTEGER NOT NULL,
                        `lastSeenAt` INTEGER NOT NULL,
                        `autoApprove` INTEGER NOT NULL,
                        PRIMARY KEY(`deviceId`)
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
