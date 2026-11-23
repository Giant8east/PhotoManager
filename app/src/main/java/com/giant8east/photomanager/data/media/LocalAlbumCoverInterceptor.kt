/** 相册封面请求拦截器：在 IO 线程检查目录内 `.thumb` 压缩封面并写入缓存键，避免主线程 FUSE stat 卡顿。 */
package com.giant8east.photomanager.data.media

import coil.intercept.Interceptor
import coil.request.ImageResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalAlbumCoverInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        // 只拦截列表卡标注了本地相册目录的封面请求；远端 URL 与普通图片请求直接放行
        val albumPath = request.parameters.value<String>(ALBUM_PATH_PARAM)
        if (albumPath.isNullOrEmpty()) {
            return chain.proceed(request)
        }
        // Coil 拦截器默认运行在 Main.immediate，文件系统存在性与修改时间检查必须在 IO 线程执行
        val thumbInfo = withContext(Dispatchers.IO) {
            val candidate = File(albumPath, ThumbGenerator.THUMB_FILE_NAME)
            if (candidate.isFile) Pair(candidate, candidate.lastModified()) else null
        }
        if (thumbInfo == null) {
            return chain.proceed(request)
        }
        val (thumb, modifiedAt) = thumbInfo
        val updated = request.newBuilder()
            .data(thumb)
            .memoryCacheKey("${thumb.path}|$modifiedAt")
            .build()
        return chain.proceed(updated)
    }

    // 等价于静态常量容器，统一请求参数键名
    companion object {
        const val ALBUM_PATH_PARAM = "local_album_path"
    }
}
