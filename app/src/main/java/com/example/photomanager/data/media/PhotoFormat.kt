/** 图片文件格式归一化，供数据层和展示层判断缩略图能力。 */
package com.example.photomanager.data.media

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
    }
}
