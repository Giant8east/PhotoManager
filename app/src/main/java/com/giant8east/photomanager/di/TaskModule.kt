/** 文件夹任务调度的依赖注入：把各类任务的处理器装配成按任务类型索引的映射。 */
package com.giant8east.photomanager.di

import com.giant8east.photomanager.data.delete.DeleteFilesHandler
import com.giant8east.photomanager.data.task.FolderTaskHandler
import com.giant8east.photomanager.data.scan.ScanFolderHandler
import com.giant8east.photomanager.data.task.TaskKind
import com.giant8east.photomanager.data.scan.UnindexHandler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 调度器只认识"任务类型 → 处理器"的映射，具体实现都在 data 层。
 *
 * 这里显式列出三条对应关系，新增任务类型时编译器会提示补映射（构造器参数是强类型的），
 * 比用 multibinding + @IntoMap 更容易一眼看清有哪些任务。
 */
@Module
@InstallIn(SingletonComponent::class)
object TaskModule {
    @Provides
    @Singleton
    fun provideFolderTaskHandlers(
        deleteFiles: DeleteFilesHandler,
        scanFolder: ScanFolderHandler,
        unindex: UnindexHandler
    ): Map<TaskKind, @JvmSuppressWildcards FolderTaskHandler> = mapOf(
        TaskKind.DELETE_FILES to deleteFiles,
        TaskKind.SCAN_IMMEDIATE to scanFolder,
        TaskKind.SCAN_REFRESH to scanFolder,
        TaskKind.UNINDEX to unindex
    )
}
