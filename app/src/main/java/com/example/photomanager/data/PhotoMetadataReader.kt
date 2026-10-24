/** 按需读取图片文件头与 EXIF 方向供连续布局确定宽高，缓存有上限，不解码原图像素。 */
package com.example.photomanager.data

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import com.example.photomanager.data.database.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.IOException

internal data class PhotoDimensions(val width: Int, val height: Int) {
    val aspectRatio get() = width.toFloat() / height
}

internal class PhotoMetadataReader(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val cache = LruCache<String, PhotoDimensions>(512)

    fun cachedSize(photo: PhotoEntity): PhotoDimensions? = if (photo.width > 0 && photo.height > 0) {
        PhotoDimensions(photo.width, photo.height)
    } else cache.get(cacheKey(photo))

    suspend fun readSize(photo: PhotoEntity): PhotoDimensions = withContext(Dispatchers.IO) {
        cachedSize(photo)?.let { return@withContext it }
        currentCoroutineContext().ensureActive()
        val uri = Uri.parse(photo.contentUri)
        val size = try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            val rotation = if (options.outMimeType == "image/jpeg" || options.outMimeType == "image/webp") {
                readOrientation(uri)
            } else ExifInterface.ORIENTATION_NORMAL
            val isRotated = rotation in setOf(ExifInterface.ORIENTATION_TRANSPOSE,
                ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSVERSE,
                ExifInterface.ORIENTATION_ROTATE_270)
            val width = options.outWidth.takeIf { it > 0 } ?: 1
            val height = options.outHeight.takeIf { it > 0 } ?: 1
            if (isRotated) PhotoDimensions(height, width) else PhotoDimensions(width, height)
        } catch (_: IOException) {
            PhotoDimensions(1, 1)
        } catch (_: SecurityException) {
            PhotoDimensions(1, 1)
        }
        // 无法读取的文件保留固定方形区域，具体打开失败由单页组件展示。
        cache.put(cacheKey(photo), size)
        size
    }

    private fun readOrientation(uri: Uri): Int = try {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun cacheKey(photo: PhotoEntity) = "${photo.contentUri}|${photo.modifiedAt}|${photo.sizeBytes}"
}
