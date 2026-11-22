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
    version = 12,
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

        /**
         * 11 → 12：albums 表非破坏性追加 `.nomedia` 隐藏状态与局域网下载来源列，不动既有行数据。
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `albums` ADD COLUMN `isHidden` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `albums` ADD COLUMN `sourceName` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `albums` ADD COLUMN `remoteDeviceId` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `albums` ADD COLUMN `remoteAlbumId` INTEGER DEFAULT NULL")
            }
        }
    }
}
