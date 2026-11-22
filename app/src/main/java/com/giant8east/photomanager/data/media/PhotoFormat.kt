/** 图片文件格式归一化，供数据层和展示层判断缩略图与动图能力。 */
package com.giant8east.photomanager.data.media

import android.graphics.ImageDecoder
import java.io.File
import java.util.Locale

enum class PhotoFormat {
    JPEG,
    PNG,
    GIF,
    WEBP,
    BMP,
    HEIC,
    HEIF,
    AVIF,
    OTHER;

    companion object {
        private object HeaderOnlyAbort : RuntimeException()

        fun fromFileName(name: String): PhotoFormat =
            when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                "jpg", "jpeg" -> JPEG
                "png" -> PNG
                "gif" -> GIF
                "webp" -> WEBP
                "bmp" -> BMP
                "heic" -> HEIC
                "heif" -> HEIF
                "avif" -> AVIF
                else -> OTHER
            }

        /**
         * 使用 Android 原生 ImageDecoder 解析文件头判断是否为动图（与阅读器共用同一底层解析器）。
         * 读到 [ImageDecoder.ImageInfo.isAnimated] 后立即中止，不执行像素解码；
         * 自动覆盖 GIF、Animated WebP、Animated HEIF/AVIF 以及扩展名与内容不符的动图。
         */
        fun isAnimatedImage(file: File): Boolean {
            if (!file.isFile) return false
            var animated = false
            try {
                val source = ImageDecoder.createSource(file)
                ImageDecoder.decodeBitmap(source) { _, info, _ ->
                    animated = info.isAnimated
                    throw HeaderOnlyAbort
                }
            } catch (_: HeaderOnlyAbort) {
                // 仅读取文件头信息，拿到 isAnimated 后主动中止
            } catch (_: Exception) {
                return false
            }
            return animated
        }
    }
}
