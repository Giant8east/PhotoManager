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
         * 判断扩展名为 `.webp` 的文件实际是否为动图（读前 64 字节，零像素解码）：
         * 1. 兼容被存成 `.webp` 后缀的真 GIF（魔数 `GIF87a` / `GIF89a`，ImageDecoder 按魔数照常播放）；
         * 2. 标准 Animated WebP：`RIFF` + `WEBP` + `VP8X` 第 20 字节动画位（`0x02`）；
         * 3. 兜底扫描前 64 字节是否含 `ANIM` 块。
         */
        fun isAnimatedWebp(file: File): Boolean {
            if (!file.isFile) return false
            return try {
                val header = ByteArray(64)
                val read = FileInputStream(file).use { it.read(header) }
                if (read < 6) return false
                // 1. 魔数是 GIF87a / GIF89a（实际是 GIF 但扩展名被存成了 .webp）
                val isGifMagic = header[0] == 'G'.code.toByte() &&
                    header[1] == 'I'.code.toByte() &&
                    header[2] == 'F'.code.toByte() &&
                    header[3] == '8'.code.toByte() &&
                    (header[4] == '7'.code.toByte() || header[4] == '9'.code.toByte()) &&
                    header[5] == 'a'.code.toByte()
                if (isGifMagic) return true

                if (read < 21) return false
                val isWebpContainer = header[0] == 'R'.code.toByte() &&
                    header[1] == 'I'.code.toByte() &&
                    header[2] == 'F'.code.toByte() &&
                    header[3] == 'F'.code.toByte() &&
                    header[8] == 'W'.code.toByte() &&
                    header[9] == 'E'.code.toByte() &&
                    header[10] == 'B'.code.toByte() &&
                    header[11] == 'P'.code.toByte()
                if (!isWebpContainer) return false

                // 2. 标准 VP8X 动画标志位
                val isVp8xAnimated = header[12] == 'V'.code.toByte() &&
                    header[13] == 'P'.code.toByte() &&
                    header[14] == '8'.code.toByte() &&
                    header[15] == 'X'.code.toByte() &&
                    (header[20].toInt() and 0x02) != 0
                if (isVp8xAnimated) return true

                // 3. 扫描前 64 字节内是否存在 ANIM 块标识
                for (i in 12..read - 4) {
                    if (header[i] == 'A'.code.toByte() &&
                        header[i + 1] == 'N'.code.toByte() &&
                        header[i + 2] == 'I'.code.toByte() &&
                        header[i + 3] == 'M'.code.toByte()
                    ) {
                        return true
                    }
                }
                false
            } catch (_: Exception) {
                false
            }
        }
    }
}
