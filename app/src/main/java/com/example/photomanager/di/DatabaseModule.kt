/** Room 数据库依赖模块，负责向 Hilt 提供单例数据库实例。 */
package com.example.photomanager.di

import android.content.Context
import androidx.room.Room
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
    ).build()
}
