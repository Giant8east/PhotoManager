/** Room 数据库定义，集中声明 PhotoManager 的本地实体、版本和迁移。 */
package com.example.photomanager.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 应用业务元数据数据库。具体实体和 DAO 随功能按需增加。
 */
@Database(
    entities = [GroupEntity::class, AlbumEntity::class, PhotoEntity::class],
    version = 4,
    exportSchema = false
)
abstract class PhotoDatabase : RoomDatabase() {
    abstract fun albumDao(): AlbumDao
    abstract fun groupDao(): GroupDao
    abstract fun photoDao(): PhotoDao

    companion object {
        /** 将 albums 重建为 coverPath 非空，并用首张图片补齐旧数据。 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 先保留旧表；直接 DROP albums 会触发 photos 的级联删除。
                database.execSQL("ALTER TABLE photos RENAME TO photos_old")
                database.execSQL("ALTER TABLE albums RENAME TO albums_old")

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS albums (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        modifiedAt TEXT,
                        photoCount INTEGER NOT NULL,
                        totalSizeBytes INTEGER,
                        lastViewedPhotoIndex INTEGER NOT NULL,
                        coverPath TEXT NOT NULL,
                        albumPath TEXT NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        groupId INTEGER,
                        FOREIGN KEY(groupId) REFERENCES groups(id)
                            ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )

                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS photos (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        albumId INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        contentUri TEXT NOT NULL,
                        modifiedAt TEXT,
                        width INTEGER NOT NULL,
                        height INTEGER NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        FOREIGN KEY(albumId) REFERENCES albums(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                // 旧记录没有封面时用其首张图片补齐；没有图片也没有封面的记录无法满足新约束，直接丢弃。
                database.execSQL(
                    """
                    INSERT INTO albums (
                        id, name, modifiedAt, photoCount, totalSizeBytes,
                        lastViewedPhotoIndex, coverPath, albumPath, sortOrder, groupId
                    )
                    SELECT
                        album.id,
                        album.name,
                        album.modifiedAt,
                        album.photoCount,
                        album.totalSizeBytes,
                        album.lastViewedPhotoIndex,
                        COALESCE(
                            album.coverPath,
                            (
                                SELECT photo.contentUri
                                FROM photos_old AS photo
                                WHERE photo.albumId = album.id
                                ORDER BY photo.name
                                LIMIT 1
                            )
                        ),
                        album.albumPath,
                        album.sortOrder,
                        album.groupId
                    FROM albums_old AS album
                    WHERE album.coverPath IS NOT NULL
                       OR EXISTS (
                           SELECT 1
                           FROM photos_old AS photo
                           WHERE photo.albumId = album.id
                       )
                    """.trimIndent()
                )

                database.execSQL(
                    """
                    INSERT INTO photos (
                        id, albumId, name, contentUri, modifiedAt, width, height, sizeBytes
                    )
                    SELECT
                        photo.id,
                        photo.albumId,
                        photo.name,
                        photo.contentUri,
                        photo.modifiedAt,
                        photo.width,
                        photo.height,
                        photo.sizeBytes
                    FROM photos_old AS photo
                    INNER JOIN albums AS album ON album.id = photo.albumId
                    """.trimIndent()
                )

                database.execSQL("DROP TABLE photos_old")
                database.execSQL("DROP TABLE albums_old")

                database.execSQL("CREATE INDEX IF NOT EXISTS index_albums_groupId ON albums(groupId)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_albums_albumPath ON albums(albumPath)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_photos_albumId ON photos(albumId)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_photos_contentUri ON photos(contentUri)")
            }
        }
    }
}
