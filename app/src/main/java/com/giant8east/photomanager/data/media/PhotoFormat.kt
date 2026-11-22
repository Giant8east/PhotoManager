/** 图片文件格式归一化，供数据层和展示层判断缩略图能力。 */
package com.giant8east.photomanager.data.media

import java.io.File
import java.io.FileInputStream
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

        /**
         * 通过读取 WebP（RIFF）容器前 21 字节判断是否为动态 WebP（Animated WebP）：
         * 字节 0..3 为 "RIFF"，8..11 为 "WEBP"，12..15 为 "VP8X"，第 20 字节 bit 1 为动画标志位。
         */
        fun isAnimatedWebp(file: File): Boolean {
            if (!file.isFile) return false
            return try {
                val header = ByteArray(21)
                val read = FileInputStream(file).use { it.read(header) }
                if (read < header.size) return false
                header[0] == 'R'.code.toByte() &&
                    header[1] == 'I'.code.toByte() &&
                    header[2] == 'F'.code.toByte() &&
                    header[3] == 'F'.code.toByte() &&
                    header[8] == 'W'.code.toByte() &&
                    header[9] == 'E'.code.toByte() &&
                    header[10] == 'B'.code.toByte() &&
                    header[11] == 'P'.code.toByte() &&
                    header[12] == 'V'.code.toByte() &&
                    header[13] == 'P'.code.toByte() &&
                    header[14] == '8'.code.toByte() &&
                    header[15] == 'X'.code.toByte() &&
                    (header[20].toInt() and 0x02) != 0
            } catch (_: Exception) {
                false
            }
        }
    }
}
