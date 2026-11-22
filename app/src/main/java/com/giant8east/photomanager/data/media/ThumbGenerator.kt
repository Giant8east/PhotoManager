/** 相册封面 .thumb 与远端预览图生成器：使用 Android 11+ ImageDecoder 自动处理 EXIF 方向并按长边缩放编码。 */
package com.giant8east.photomanager.data.media

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class ThumbGenerator @Inject constructor() {

    fun thumbFile(albumPath: String): File = File(albumPath, THUMB_FILE_NAME)

    /**
     * 确保相册目录下的 `.thumb` 存在且与首图同步；仅在缺失、首图换人或首图更新时重新生成。
     * 生成失败只记录日志并返回 null，不阻塞相册同步，展示层自动回退原图。
     */
    suspend fun ensureAlbumThumb(
        albumPath: String,
        firstPhotoPath: String,
        forceRebuild: Boolean
    ): File? = withContext(Dispatchers.IO) {
        val source = File(firstPhotoPath)
        if (!source.isFile) return@withContext null

        val target = thumbFile(albumPath)
        val needsBuild = forceRebuild ||
            !target.isFile ||
            target.lastModified() < source.lastModified()
        if (!needsBuild) return@withContext target

        val bytes = encodePreviewBytes(source) ?: return@withContext null
        val temp = File(albumPath, THUMB_TEMP_FILE_NAME)
        try {
            temp.writeBytes(bytes)
            if (target.exists() && !target.delete()) {
                Log.w(TAG, "Failed to replace old thumb: ${target.path}")
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                Log.w(TAG, "Failed to rename thumb temp file: ${target.path}")
                return@withContext null
            }
            target
        } catch (error: Exception) {
            temp.delete()
            Log.w(TAG, "Failed to write album thumb: ${target.path}", error)
            null
        }
    }

    /**
     * 将单张图片按长边不超过 [MAX_EDGE_PX] 降采样并编码为 JPEG 字节数组。
     * ImageDecoder 原生处理 EXIF 旋转，并支持 JPG/PNG/WebP/HEIC/GIF 首帧。
     */
    suspend fun encodePreviewBytes(sourceFile: File): ByteArray? = withContext(Dispatchers.IO) {
        if (!sourceFile.isFile) return@withContext null
        var bitmap: Bitmap? = null
        try {
            val source = ImageDecoder.createSource(sourceFile)
            bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width
                val height = info.size.height
                val longest = max(width, height)
                if (longest > MAX_EDGE_PX) {
                    val scale = MAX_EDGE_PX.toFloat() / longest
                    val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
                    val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
                    decoder.setTargetSize(targetWidth, targetHeight)
                }
            }
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            output.toByteArray()
        } catch (error: Exception) {
            Log.w(TAG, "Failed to encode preview for ${sourceFile.path}", error)
            null
        } finally {
            bitmap?.recycle()
        }
    }

    companion object {
        const val THUMB_FILE_NAME = ".thumb"
        private const val THUMB_TEMP_FILE_NAME = ".thumb.tmp"
        private const val MAX_EDGE_PX = 480
        private const val JPEG_QUALITY = 80
        private const val TAG = "ThumbGenerator"
    }
}
