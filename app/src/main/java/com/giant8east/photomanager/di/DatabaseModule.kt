/** Room 数据库依赖模块，负责向 Hilt 提供单例数据库实例与各 DAO。 */
package com.giant8east.photomanager.di

import android.content.Context
import androidx.room.Room
import com.giant8east.photomanager.data.database.AlbumDao
import com.giant8east.photomanager.data.database.AllowedDeviceDao
import com.giant8east.photomanager.data.database.GroupDao
import com.giant8east.photomanager.data.database.PhotoDao
import com.giant8east.photomanager.data.database.PhotoDatabase
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
    @Singleton
    fun providePhotoDatabase(
        @ApplicationContext context: Context
    ): PhotoDatabase = Room.databaseBuilder(
        context,
        PhotoDatabase::class.java,
        "photo_manager.db"
    )
        .addMigrations(
            PhotoDatabase.MIGRATION_10_11,
            PhotoDatabase.MIGRATION_11_12,
            PhotoDatabase.MIGRATION_12_13
        )
        .build()

    @Provides
    fun provideAlbumDao(database: PhotoDatabase): AlbumDao = database.albumDao()

    @Provides
    fun provideGroupDao(database: PhotoDatabase): GroupDao = database.groupDao()

    @Provides
    fun providePhotoDao(database: PhotoDatabase): PhotoDao = database.photoDao()

    @Provides
    fun provideAllowedDeviceDao(database: PhotoDatabase): AllowedDeviceDao = database.allowedDeviceDao()
}
